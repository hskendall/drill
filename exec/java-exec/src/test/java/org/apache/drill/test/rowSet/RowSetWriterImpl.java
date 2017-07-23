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
package org.apache.drill.test.rowSet;

import org.apache.drill.exec.record.TupleMetadata;
import org.apache.drill.exec.vector.ValueVector;
import org.apache.drill.exec.vector.VectorOverflowException;
import org.apache.drill.exec.vector.accessor.ColumnWriterIndex;
import org.apache.drill.exec.vector.accessor.writer.AbstractObjectWriter;
import org.apache.drill.exec.vector.accessor.writer.AbstractTupleWriter;
import org.apache.drill.test.rowSet.RowSet.ExtendableRowSet;
import org.apache.drill.test.rowSet.RowSet.SingleRowSet;

/**
 * Implementation of a row set writer. Only available for newly-created,
 * empty, direct, single row sets. Rewriting is not allowed, nor is writing
 * to a hyper row set.
 */

public class RowSetWriterImpl extends AbstractTupleWriter implements RowSetWriter {

  /**
   * Writer index that points to each row in the row set. The index starts at
   * the 0th row and advances one row on each increment. This allows writers to
   * start positioned at the first row. Writes happen in the current row.
   * Calling <tt>next()</tt> advances to the next position, effectively saving
   * the current row. The most recent row can be abandoned easily simply by not
   * calling <tt>next()</tt>. This means that the number of completed rows is
   * the same as the row index.
   */

  static class WriterIndexImpl implements ColumnWriterIndex {

    public enum State { OK, VECTOR_OVERFLOW, END_OF_BATCH }

    private int rowIndex = 0;
    private State state = State.OK;

    @Override
    public int vectorIndex() { return rowIndex; }

    public boolean next() {
      if (++rowIndex < ValueVector.MAX_ROW_COUNT) {
        return true;
      } else {
        // Should not call next() again once batch is full.
        assert rowIndex == ValueVector.MAX_ROW_COUNT;
        rowIndex = ValueVector.MAX_ROW_COUNT;
        state = state == State.OK ? State.END_OF_BATCH : state;
        return false;
      }
    }

    public int size() {
      // The index always points to the next slot past the
      // end of valid rows.
      return rowIndex;
    }

    public boolean valid() { return state == State.OK; }

    public boolean hasOverflow() { return state == State.VECTOR_OVERFLOW; }

    @Override
    public void overflowed() {
      state = State.VECTOR_OVERFLOW;
    }
  }

  private final WriterIndexImpl index;
  private final ExtendableRowSet rowSet;

  protected RowSetWriterImpl(ExtendableRowSet rowSet, TupleMetadata schema, WriterIndexImpl index, AbstractObjectWriter[] writers) {
    super(schema, writers);
    this.rowSet = rowSet;
    this.index = index;
    startWrite();
  }

  @Override
  public void setRow(Object...values) throws VectorOverflowException {
    for (int i = 0; i < values.length;  i++) {
      set(i, values[i]);
    }
    save();
  }

  @Override
  public int rowIndex() { return index.vectorIndex(); }

  @Override
  public boolean save() {
    endRow();
    boolean more = index.next();
    if (more) {
      startRow();
    }
    return more;
  }

  @Override
  public boolean isFull( ) { return ! index.valid(); }

  @Override
  public SingleRowSet done() {
    try {
      endWrite();
      rowSet.container().setRecordCount(index.vectorIndex());
    } catch (VectorOverflowException e) {
      throw new IllegalStateException(e);
    }
    return rowSet.toIndirect();
  }
}