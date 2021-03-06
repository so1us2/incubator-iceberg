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

package com.netflix.iceberg.parquet;

import com.google.common.collect.ImmutableMap;
import com.netflix.iceberg.Metrics;
import com.netflix.iceberg.Schema;
import com.netflix.iceberg.common.DynConstructors;
import com.netflix.iceberg.common.DynMethods;
import com.netflix.iceberg.exceptions.RuntimeIOException;
import com.netflix.iceberg.io.FileAppender;
import com.netflix.iceberg.io.OutputFile;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.bytes.ByteBufferAllocator;
import org.apache.parquet.column.ColumnWriteStore;
import org.apache.parquet.column.ParquetProperties;
import org.apache.parquet.column.page.PageWriteStore;
import org.apache.parquet.hadoop.CodecFactory;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.schema.MessageType;
import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.function.Function;

import static com.netflix.iceberg.parquet.ParquetSchemaUtil.convert;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static org.apache.parquet.column.ParquetProperties.WriterVersion.PARQUET_1_0;

class ParquetWriter<T> implements FileAppender<T>, Closeable {
  private static final DynConstructors.Ctor<PageWriteStore> pageStoreCtor = DynConstructors
      .builder(PageWriteStore.class)
      .hiddenImpl("org.apache.parquet.hadoop.ColumnChunkPageWriteStore",
          CodecFactory.BytesCompressor.class,
          MessageType.class,
          ByteBufferAllocator.class,
          int.class)
      .build();

  private static final DynMethods.UnboundMethod flushToWriter = DynMethods
      .builder("flushToFileWriter")
      .hiddenImpl("org.apache.parquet.hadoop.ColumnChunkPageWriteStore", ParquetFileWriter.class)
      .build();

  private final OutputFile output;
  private final long targetRowGroupSize;
  private final Map<String, String> metadata;
  private final ParquetProperties props = ParquetProperties.builder()
      .withWriterVersion(PARQUET_1_0)
      .build();
  private final CodecFactory.BytesCompressor compressor;
  private final MessageType parquetSchema;
  private final ParquetValueWriter<T> model;
  private final ParquetFileWriter writer;

  private DynMethods.BoundMethod flushPageStoreToWriter;
  private ColumnWriteStore writeStore;
  private long nextRowGroupSize = 0;
  private long recordCount = 0;
  private long nextCheckRecordCount = 10;

  @SuppressWarnings("unchecked")
  ParquetWriter(Configuration conf, OutputFile output, Schema schema, long rowGroupSize,
                Map<String, String> metadata,
                Function<MessageType, ParquetValueWriter<?>> createWriterFunc,
                CompressionCodecName codec) {
    this.output = output;
    this.targetRowGroupSize = rowGroupSize;
    this.metadata = ImmutableMap.copyOf(metadata);
    this.compressor = new CodecFactory(conf, props.getPageSizeThreshold()).getCompressor(codec);
    this.parquetSchema = convert(schema, "table");
    this.model = (ParquetValueWriter<T>) createWriterFunc.apply(parquetSchema);

    try {
      this.writer = new ParquetFileWriter(ParquetIO.file(output, conf), parquetSchema,
          ParquetFileWriter.Mode.OVERWRITE, rowGroupSize, 0);
    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to create Parquet file");
    }

    try {
      writer.start();
    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to start Parquet file writer");
    }

    startRowGroup();
  }

  @Override
  public void add(T value) {
    recordCount += 1;
    model.write(0, value);
    writeStore.endRecord();
    checkSize();
  }

  @Override
  public Metrics metrics() {
    return ParquetMetrics.fromMetadata(writer.getFooter());
  }

  private void checkSize() {
    if (recordCount >= nextCheckRecordCount) {
      long bufferedSize = writeStore.getBufferedSize();
      double avgRecordSize = ((double) bufferedSize) / recordCount;

      if (bufferedSize > (nextRowGroupSize - 2 * avgRecordSize)) {
        flushRowGroup(false);
      } else {
        long remainingSpace = nextRowGroupSize - bufferedSize;
        long remainingRecords = (long) (remainingSpace / avgRecordSize);
        this.nextCheckRecordCount = recordCount + min(max(remainingRecords / 2, 100), 10000);
      }
    }
  }

  private void flushRowGroup(boolean finished) {
    try {
      if (recordCount > 0) {
        writer.startBlock(recordCount);
        writeStore.flush();
        flushPageStoreToWriter.invoke(writer);
        writer.endBlock();
        if (!finished) {
          startRowGroup();
        }
      }
    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to flush row group");
    }
  }

  private void startRowGroup() {
    try {
      this.nextRowGroupSize = min(writer.getNextRowGroupSize(), targetRowGroupSize);
    } catch (IOException e) {
      throw new RuntimeIOException(e);
    }
    this.nextCheckRecordCount = min(max(recordCount / 2, 100), 10000);
    this.recordCount = 0;

    PageWriteStore pageStore = pageStoreCtor.newInstance(
        compressor, parquetSchema, props.getAllocator(), props.getColumnIndexTruncateLength());

    this.flushPageStoreToWriter = flushToWriter.bind(pageStore);
    this.writeStore = props.newColumnWriteStore(parquetSchema, pageStore);

    model.setColumnStore(writeStore);
  }

  @Override
  public void close() throws IOException {
    flushRowGroup(true);
    writeStore.close();
    writer.end(metadata);
  }
}
