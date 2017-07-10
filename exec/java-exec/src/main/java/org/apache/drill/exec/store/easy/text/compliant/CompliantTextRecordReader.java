/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.store.easy.text.compliant;

import java.io.IOException;
import java.io.InputStream;

import org.apache.drill.common.exceptions.ExecutionSetupException;
import org.apache.drill.common.exceptions.UserException;
import org.apache.drill.common.types.TypeProtos.DataMode;
import org.apache.drill.common.types.TypeProtos.MajorType;
import org.apache.drill.common.types.TypeProtos.MinorType;
import org.apache.drill.exec.physical.impl.protocol.OperatorRecordBatch.OperatorExecServices;
import org.apache.drill.exec.physical.impl.scan.MaterializedSchema;
import org.apache.drill.exec.physical.impl.scan.RowBatchReader;
import org.apache.drill.exec.physical.impl.scan.SchemaNegotiator;
import org.apache.drill.exec.physical.rowSet.ResultSetLoader;
import org.apache.drill.exec.record.MaterializedField;
import org.apache.drill.exec.store.dfs.DrillFileSystem;
import org.apache.hadoop.mapred.FileSplit;

import com.univocity.parsers.common.TextParsingException;

import io.netty.buffer.DrillBuf;

// New text reader, complies with the RFC 4180 standard for text/csv files
public class CompliantTextRecordReader implements RowBatchReader {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(CompliantTextRecordReader.class);

  private static final int MAX_RECORDS_PER_BATCH = 8096;
  private static final int READ_BUFFER = 1024*1024;
  private static final int WHITE_SPACE_BUFFER = 64*1024;

  // settings to be used while parsing
  private TextParsingSettings settings;
  // Chunk of the file to be read by this reader
  private FileSplit split;
  // text reader implementation
  private TextReader reader;
  // input buffer
  private DrillBuf readBuffer;
  // working buffer to handle whitespaces
  private DrillBuf whitespaceBuffer;
  private DrillFileSystem dfs;

  private ResultSetLoader loader;

  public CompliantTextRecordReader(FileSplit split, DrillFileSystem dfs, TextParsingSettings settings) {
    this.split = split;
    this.settings = settings;
    this.dfs = dfs;
  }

  /**
   * Performs the initial setup required for the record reader.
   * Initializes the input stream, handling of the output record batch
   * and the actual reader to be used.
   * @param context  operator context from which buffer's will be allocated and managed
   * @param outputMutator  Used to create the schema in the output record batch
   * @throws ExecutionSetupException
   */
  @SuppressWarnings("resource")
  @Override
  public boolean open(SchemaNegotiator schemaNegotiator) {
    OperatorExecServices context = schemaNegotiator.context();

    // Note: DO NOT use managed buffers here. They remain in existence
    // until the fragment is shut down. The buffers here are large.
    // If we scan 1000 files, and allocate 1 MB for each, we end up
    // holding onto 1 GB of memory in managed buffers.
    // Instead, we allocate the buffers explicitly, and must free
    // them.

    readBuffer = context.allocator().buffer(READ_BUFFER);
    whitespaceBuffer = context.allocator().buffer(WHITE_SPACE_BUFFER);

    // TODO: Set this based on size of record rather than
    // absolute count.

    schemaNegotiator.setBatchSize(MAX_RECORDS_PER_BATCH);

    // setup Output, Input, and Reader
    try {
      TextOutput output = null;
      TextInput input = null;
      InputStream stream = null;

      // setup Output using OutputMutator
      if (settings.isHeaderExtractionEnabled()){
        //extract header and use that to setup a set of VarCharVectors
        String [] fieldNames = extractHeader();
        if (fieldNames == null)
          return false;
        MaterializedSchema schema = new MaterializedSchema();
        for (String colName : fieldNames) {
          schema.add(MaterializedField.create(colName,
              MajorType.newBuilder()
                .setMinorType(MinorType.VARCHAR)
                .setMode(DataMode.REQUIRED)
                .build()));
        }
        schemaNegotiator.setTableSchema(schema);
        loader = schemaNegotiator.build();
        output = new FieldVarCharOutput(loader);
      } else {
        //simply use RepeatedVarCharVector
        MaterializedSchema schema = new MaterializedSchema();
        schema.add(MaterializedField.create("columns",
            MajorType.newBuilder()
              .setMinorType(MinorType.VARCHAR)
              .setMode(DataMode.REPEATED)
              .build()));
        loader = schemaNegotiator.build();
        output = new RepeatedVarCharOutput(loader, schemaNegotiator.columnsArrayProjectionMap());
      }

      // setup Input using InputStream
      stream = dfs.openPossiblyCompressedStream(split.getPath());
      input = new TextInput(settings, stream, readBuffer, split.getStart(), split.getStart() + split.getLength());

      // setup Reader using Input and Output
      reader = new TextReader(settings, input, output, whitespaceBuffer);
      reader.start();

      return true;

    } catch (IOException e) {
      throw UserException.dataReadError(e).addContext("File Path", split.getPath().toString()).build(logger);
    }
  }

  /**
   * This method is responsible to implement logic for extracting header from text file
   * Currently it is assumed to be first line if headerExtractionEnabled is set to true
   * TODO: enhance to support more common header patterns
   * @return field name strings
   */
  @SuppressWarnings("resource")
  private String [] extractHeader() throws IOException {
    assert settings.isHeaderExtractionEnabled();

    // don't skip header in case skipFirstLine is set true
    settings.setSkipFirstLine(false);

    HeaderBuilder hOutput = new HeaderBuilder();

    // setup Input using InputStream
    // we should read file header irrespective of split given given to this reader
    InputStream hStream = dfs.openPossiblyCompressedStream(split.getPath());
    TextInput hInput = new TextInput(settings,  hStream, readBuffer, 0, split.getLength());

    // setup Reader using Input and Output
    this.reader = new TextReader(settings, hInput, hOutput, whitespaceBuffer);
    reader.start();

    // extract first row only
    reader.parseNext();

    // grab the field names from output
    String [] fieldNames = hOutput.getHeaders();

    // cleanup and set to skip the first line next time we read input
    reader.close();
    settings.setSkipFirstLine(true);

    readBuffer.clear();
    whitespaceBuffer.clear();
    return fieldNames;
  }

  /**
   * Generates the next record batch
   * @return  number of records in the batch
   *
   */

  @Override
  public boolean next() {
    reader.resetForNextBatch();

    try{
      while(! loader.isFull() && reader.parseNext()){
        ;
      }
      reader.finishBatch();
      return loader.rowCount() > 0;
    } catch (IOException | TextParsingException e) {
      throw UserException.dataReadError(e)
          .addContext("Failure while reading file %s. Happened at or shortly before byte position %d.",
            split.getPath(), reader.getPos())
          .build(logger);
    }
  }

  /**
   * Cleanup state once we are finished processing all the records.
   * This would internally close the input stream we are reading from.
   */
  @Override
  public void close() {

    // Release the buffers allocated above. Double-check to handle
    // unexpected multiple calls to close().

    if (readBuffer != null) {
      readBuffer.release();
      readBuffer = null;
    }
    if (whitespaceBuffer != null) {
      whitespaceBuffer.release();
      whitespaceBuffer = null;
    }
    try {
      if (reader != null) {
        reader.close();
        reader = null;
      }
    } catch (IOException e) {
      logger.warn("Exception while closing stream.", e);
    }
  }
}
