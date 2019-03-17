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
package org.apache.drill.exec.physical.impl.scan.file;

import org.apache.drill.common.exceptions.ExecutionSetupException;
import org.apache.drill.exec.physical.impl.scan.file.BaseFileScanFramework.FileSchemaNegotiator;
import org.apache.drill.exec.physical.impl.scan.framework.ManagedReader;
import org.apache.drill.exec.physical.impl.scan.framework.SchemaNegotiatorImpl;
import org.apache.drill.exec.physical.impl.scan.framework.ShimBatchReader;
import org.apache.drill.exec.store.dfs.DrillFileSystem;
import org.apache.hadoop.mapred.FileSplit;

/**
 * The file scan framework adds into the scan framework support for implicit
 * file metadata columns. The file scan framework brings together a number of
 * components:
 * <ul>
 * <li>The projection list provided by the physical operator definition. This
 * list identifies the set of "output" columns whih this framework is obliged
 * to produce.</li>
 * <li>The set of files and/or blocks to read.</li>
 * <li>The file system configuration to use for working with the files
 * or blocks.</li>
 * <li>The factory class to create a reader for each of the files or blocks
 * defined above. (Readers are created one-by-one as files are read.)</li>
 * <li>Options as defined by the base class.</li>
 * </ul>
 * <p>
 * @See {AbstractScanFramework} for details.
 */

public class FileScanFramework extends BaseFileScanFramework<T> {

  /**
   * Creates a batch reader on demand. Unlike earlier versions of Drill,
   * this framework creates readers one by one, when they are needed.
   * Doing so avoids excessive resource demands that come from creating
   * potentially thousands of readers up front.
   * <p>
   * The reader itself is unique to each file type. This interface
   * provides a common interface that this framework can use to create the
   * file-specific reader on demand.
   */

  public interface FileReaderFactory<T extends FileSchemaNegotiator> {
    ManagedReader<T> makeBatchReader(
        DrillFileSystem dfs,
        FileSplit split) throws ExecutionSetupException;

    T makeSchemaNegotiator();
  }

  /**
   * Options for a file-based scan.
   */

  public static class FileScanBuilder<T extends FileSchemaNegotiator> extends BaseFileScanBuilder {
    private FileReaderFactory<T> readerCreator;

    public void setReaderCreator(FileReaderFactory<T> readerCreator) {
      this.readerCreator = readerCreator;
    }
  }

  /**
   * Implementation of the file-level schema negotiator. At present, no
   * file-specific features exist. This class shows, however, where we would
   * add such features.
   */

  public static class FileSchemaNegotiatorImpl extends SchemaNegotiatorImpl
      implements FileSchemaNegotiator {
  }

  private final FileReaderFactory<T> readerCreator;

  public FileScanFramework(FileScanBuilder<T> builder) {
    super(builder);
    readerCreator = builder.readerCreator;
    assert readerCreator != null;
  }

  @Override
  protected ManagedReader<T> newReader(FileSplit split) throws ExecutionSetupException {
    return readerCreator.makeBatchReader(dfs, split);
  }

  // Works for the
  @SuppressWarnings("unchecked")
  @Override
  public boolean openReader(ShimBatchReader<T> shim, ManagedReader<T> reader) {
    return ((ManagedReader<FileSchemaNegotiator>) reader).open(
        new FileSchemaNegotiatorImpl(this, shim));
  }
}
