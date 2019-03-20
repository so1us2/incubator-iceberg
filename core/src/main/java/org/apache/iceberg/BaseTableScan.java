/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import org.apache.iceberg.TableMetadata.SnapshotLogEntry;
import org.apache.iceberg.events.Listeners;
import org.apache.iceberg.events.ScanEvent;
import org.apache.iceberg.expressions.Binder;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.expressions.InclusiveManifestEvaluator;
import org.apache.iceberg.expressions.ResidualEvaluator;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.types.TypeUtil;
import org.apache.iceberg.util.BinPacking;
import org.apache.iceberg.util.ParallelIterable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.Closeable;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

import static org.apache.iceberg.util.ThreadPools.getWorkerPool;

/**
 * Base class for {@link TableScan} implementations.
 */
class BaseTableScan implements TableScan {
  private static final Logger LOG = LoggerFactory.getLogger(TableScan.class);

  private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
  private static final List<String> SNAPSHOT_COLUMNS = ImmutableList.of(
      "snapshot_id", "file_path", "file_ordinal", "file_format", "block_size_in_bytes",
      "file_size_in_bytes", "record_count", "partition", "value_counts", "null_value_counts",
      "lower_bounds", "upper_bounds"
  );
  private static final boolean PLAN_SCANS_WITH_WORKER_POOL =
      SystemProperties.getBoolean(SystemProperties.SCAN_THREAD_POOL_ENABLED, true);

  private final TableOperations ops;
  private final Table table;
  private final Long snapshotId;
  private final Schema schema;
  private final Expression rowFilter;
  private final boolean caseSensitive;
  private final Collection<String> selectedColumns;

  BaseTableScan(TableOperations ops, Table table) {
    this(ops, table, null, table.schema(), Expressions.alwaysTrue(), true, null);
  }

  private BaseTableScan(TableOperations ops, Table table, Long snapshotId, Schema schema,
                        Expression rowFilter, boolean caseSensitive, Collection<String> selectedColumns) {
    this.ops = ops;
    this.table = table;
    this.snapshotId = snapshotId;
    this.schema = schema;
    this.rowFilter = rowFilter;
    this.caseSensitive = caseSensitive;
    this.selectedColumns = selectedColumns;
  }

  @Override
  public Table table() {
    return table;
  }

  @Override
  public TableScan useSnapshot(long snapshotId) {
    Preconditions.checkArgument(this.snapshotId == null,
        "Cannot override snapshot, already set to id=%s", snapshotId);
    Preconditions.checkArgument(ops.current().snapshot(snapshotId) != null,
        "Cannot find snapshot with ID %s", snapshotId);
    return new BaseTableScan(ops, table, snapshotId, schema, rowFilter, caseSensitive, selectedColumns);
  }

  @Override
  public TableScan asOfTime(long timestampMillis) {
    Preconditions.checkArgument(this.snapshotId == null,
        "Cannot override snapshot, already set to id=%s", snapshotId);

    Long lastSnapshotId = null;
    for (SnapshotLogEntry logEntry : ops.current().snapshotLog()) {
      if (logEntry.timestampMillis() <= timestampMillis) {
        lastSnapshotId = logEntry.snapshotId();
      }
    }

    // the snapshot ID could be null if no entries were older than the requested time. in that case,
    // there is no valid snapshot to read.
    Preconditions.checkArgument(lastSnapshotId != null,
        "Cannot find a snapshot older than %s", DATE_FORMAT.format(new Date(timestampMillis)));

    return useSnapshot(lastSnapshotId);
  }

  public TableScan project(Schema schema) {
    return new BaseTableScan(ops, table, snapshotId, schema, rowFilter, caseSensitive, selectedColumns);
  }

  @Override
  public TableScan caseSensitive(boolean caseSensitive) {
    return new BaseTableScan(ops, table, snapshotId, schema, rowFilter, caseSensitive, selectedColumns);
  }

  @Override
  public TableScan select(Collection<String> columns) {
    return new BaseTableScan(ops, table, snapshotId, schema, rowFilter, caseSensitive, columns);
  }

  @Override
  public TableScan filter(Expression expr) {
    return new BaseTableScan(ops, table, snapshotId, schema, Expressions.and(rowFilter, expr),
                             caseSensitive, selectedColumns);
  }

  private final LoadingCache<Integer, InclusiveManifestEvaluator> EVAL_CACHE = CacheBuilder
      .newBuilder()
      .build(new CacheLoader<Integer, InclusiveManifestEvaluator>() {
        @Override
        public InclusiveManifestEvaluator load(Integer specId) {
          PartitionSpec spec = ops.current().spec(specId);
          return new InclusiveManifestEvaluator(spec, rowFilter, caseSensitive);
        }
      });

  @Override
  public CloseableIterable<FileScanTask> planFiles() {
    Snapshot snapshot = snapshotId != null ?
        ops.current().snapshot(snapshotId) :
        ops.current().currentSnapshot();

    if (snapshot != null) {
      LOG.info("Scanning table {} snapshot {} created at {} with filter {}", table,
          snapshot.snapshotId(), DATE_FORMAT.format(new Date(snapshot.timestampMillis())),
          rowFilter);

      Listeners.notifyAll(
          new ScanEvent(table.toString(), snapshot.snapshotId(), rowFilter, schema()));

      Iterable<ManifestFile> matchingManifests = Iterables.filter(snapshot.manifests(),
          manifest -> EVAL_CACHE.getUnchecked(manifest.partitionSpecId()).eval(manifest));

      ConcurrentLinkedQueue<Closeable> toClose = new ConcurrentLinkedQueue<>();
      Iterable<Iterable<FileScanTask>> readers = Iterables.transform(
          matchingManifests,
          manifest -> {
            ManifestReader reader = ManifestReader
                .read(ops.io().newInputFile(manifest.path()))
                .caseSensitive(caseSensitive);
            toClose.add(reader);
            String schemaString = SchemaParser.toJson(reader.spec().schema());
            String specString = PartitionSpecParser.toJson(reader.spec());
            ResidualEvaluator residuals = new ResidualEvaluator(reader.spec(), rowFilter, caseSensitive);
            return Iterables.transform(
                reader.filterRows(rowFilter).select(SNAPSHOT_COLUMNS),
                file -> new BaseFileScanTask(file, schemaString, specString, residuals)
            );
          });

      if (PLAN_SCANS_WITH_WORKER_POOL && snapshot.manifests().size() > 1) {
        return CloseableIterable.combine(
            new ParallelIterable<>(readers, getWorkerPool()),
            toClose);
      } else {
        return CloseableIterable.combine(Iterables.concat(readers), toClose);
      }

    } else {
      LOG.info("Scanning empty table {}", table);
      return CloseableIterable.empty();
    }
  }

  @Override
  public CloseableIterable<CombinedScanTask> planTasks() {
    long splitSize = ops.current().propertyAsLong(
        TableProperties.SPLIT_SIZE, TableProperties.SPLIT_SIZE_DEFAULT);
    int lookback = ops.current().propertyAsInt(
        TableProperties.SPLIT_LOOKBACK, TableProperties.SPLIT_LOOKBACK_DEFAULT);
    long openFileCost = ops.current().propertyAsLong(
      TableProperties.SPLIT_OPEN_FILE_COST, TableProperties.SPLIT_OPEN_FILE_COST_DEFAULT);

    Function<FileScanTask, Long> weightFunc = file -> Math.max(file.length(), openFileCost);

    return CloseableIterable.transform(
        CloseableIterable.wrap(splitFiles(splitSize), splits ->
            new BinPacking.PackingIterable<>(splits, splitSize, lookback, weightFunc)),
        BaseCombinedScanTask::new);
  }

  @Override
  public Schema schema() {
    return lazyColumnProjection();
  }

  @Override
  public Expression filter() {
    return rowFilter;
  }

  @Override
  public boolean isCaseSensitive() {
    return caseSensitive;
  }

  @Override
  public String toString() {
    return Objects.toStringHelper(this)
        .add("table", table)
        .add("projection", schema().asStruct())
        .add("filter", rowFilter)
        .add("caseSensitive", caseSensitive)
        .toString();
  }

  private CloseableIterable<FileScanTask> splitFiles(long splitSize) {
    CloseableIterable<FileScanTask> fileScanTasks = planFiles();
    Iterable<FileScanTask> splitTasks = FluentIterable
        .from(fileScanTasks)
        .transformAndConcat(input -> input.split(splitSize));
    // Capture manifests which can be closed after scan planning
    return CloseableIterable.combine(splitTasks, ImmutableList.of(fileScanTasks));
  }

  /**
   * To be able to make refinements {@link #select(Collection)} and {@link #caseSensitive(boolean)} in any order,
   * we resolve the schema to be projected lazily here.
   *
   * @return the Schema to project
   */
  private Schema lazyColumnProjection() {
    if (selectedColumns != null ) {
      Set<Integer> requiredFieldIds = Sets.newHashSet();

      // all of the filter columns are required
      requiredFieldIds.addAll(
          Binder.boundReferences(table.schema().asStruct(), Collections.singletonList(rowFilter), caseSensitive));

      // all of the projection columns are required
      Set<Integer> selectedIds;
      if (caseSensitive) {
        selectedIds = TypeUtil.getProjectedIds(table.schema().select(selectedColumns));
      } else {
        selectedIds = TypeUtil.getProjectedIds(table.schema().caseInsensitiveSelect(selectedColumns));
      }
      requiredFieldIds.addAll(selectedIds);

      return TypeUtil.select(table.schema(), requiredFieldIds);
    }

    return schema;
  }
}
