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
package org.apache.drill.exec.physical.impl.scan.project;

import org.apache.drill.exec.physical.rowSet.ResultSetLoader;
import org.apache.drill.exec.physical.rowSet.ResultVectorCache;
import org.apache.drill.exec.physical.rowSet.RowSetLoader;
import org.apache.drill.exec.physical.rowSet.impl.OptionBuilder;
import org.apache.drill.exec.physical.rowSet.impl.ResultSetLoaderImpl;
import org.apache.drill.exec.record.VectorContainer;
import org.apache.drill.exec.vector.accessor.TupleWriter;

/**
 * Base class for columns that take values based on the
 * reader, not individual rows.
 */

public abstract class StaticColumnLoader {
  protected final ResultSetLoader loader;
  protected final ResultVectorCache vectorCache;

  public StaticColumnLoader(ResultVectorCache vectorCache) {

    ResultSetLoaderImpl.ResultSetOptions options = new OptionBuilder()
          .setVectorCache(vectorCache)
          .build();
    loader = new ResultSetLoaderImpl(vectorCache.allocator(), options);
    this.vectorCache = vectorCache;
  }

  /**
   * Populate static vectors with the defined static values.
   *
   * @param rowCount number of rows to generate. Must match the
   * row count in the batch returned by the reader
   */

  public VectorContainer load(int rowCount) {
    loader.startBatch();
    RowSetLoader writer = loader.writer();
    for (int i = 0; i < rowCount; i++) {
      writer.start();
      loadRow(writer);
      writer.save();
    }
    return loader.harvest();
  }

  protected abstract void loadRow(TupleWriter writer);

  public void close() {
    loader.close();
  }
}