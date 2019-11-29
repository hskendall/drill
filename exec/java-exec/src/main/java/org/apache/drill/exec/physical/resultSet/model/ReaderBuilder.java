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
package org.apache.drill.exec.physical.resultSet.model;

import org.apache.drill.common.types.TypeProtos.DataMode;
import org.apache.drill.exec.physical.impl.protocol.BatchAccessor;
import org.apache.drill.exec.physical.resultSet.model.hyper.HyperReaderBuilder;
import org.apache.drill.exec.physical.resultSet.model.single.DirectRowIndex;
import org.apache.drill.exec.physical.resultSet.model.single.SimpleReaderBuilder;
import org.apache.drill.exec.physical.rowSet.HyperRowIndex;
import org.apache.drill.exec.physical.rowSet.IndirectRowIndex;
import org.apache.drill.exec.physical.rowSet.RowSetReaderImpl;
import org.apache.drill.exec.record.BatchSchema.SelectionVectorMode;
import org.apache.drill.exec.record.metadata.ColumnMetadata;
import org.apache.drill.exec.vector.accessor.reader.AbstractObjectReader;
import org.apache.drill.exec.vector.accessor.reader.ArrayReaderImpl;
import org.apache.drill.exec.vector.accessor.reader.BaseScalarReader;
import org.apache.drill.exec.vector.accessor.reader.ColumnReaderFactory;
import org.apache.drill.exec.vector.accessor.reader.VectorAccessor;

public abstract class ReaderBuilder {

  public static RowSetReaderImpl buildReader(BatchAccessor batch) {
    if (batch.schema().getSelectionVectorMode() == SelectionVectorMode.FOUR_BYTE) {
      return HyperReaderBuilder.build(batch);
    } else {
      return SimpleReaderBuilder.build(batch);
    }
  }

  /**
   * A somewhat awkward method to reset the current reader index.
   * The index differs in form for each kind of SV. If the reader
   * has the correct index, reset it in a way unique to each kind
   * of SV. Else (for the non-hyper case), replace the index with
   * the right kind.
   * <p>
   * Cannot rebind a hyper reader to non-hyper and visa-versa;
   * they have different internal structures. Must replace the
   * reader itself.
   */

  public static void rebind(RowSetReaderImpl rowSetReader, BatchAccessor batch) {
    ReaderIndex currentIndex = rowSetReader.index();
    switch (batch.schema().getSelectionVectorMode()) {
    case FOUR_BYTE:
      ((HyperRowIndex) currentIndex).bind(batch.selectionVector4());
      return;
    case NONE:
      if (currentIndex instanceof DirectRowIndex) {
        ((DirectRowIndex) currentIndex).resetRowCount();
        return;
      }
      break;
    case TWO_BYTE:
      if (currentIndex instanceof IndirectRowIndex) {
        ((IndirectRowIndex) currentIndex).bind(batch.selectionVector2());
        return;
      }
      break;
    default:
      throw new IllegalStateException();
    }
    rowSetReader.bindIndex(SimpleReaderBuilder.readerIndex(batch));
  }

  protected AbstractObjectReader buildScalarReader(VectorAccessor va, ColumnMetadata schema) {
    BaseScalarReader scalarReader = ColumnReaderFactory.buildColumnReader(va);
    DataMode mode = va.type().getMode();
    switch (mode) {
    case OPTIONAL:
      return BaseScalarReader.buildOptional(schema, va, scalarReader);
    case REQUIRED:
      return BaseScalarReader.buildRequired(schema, va, scalarReader);
    case REPEATED:
      return ArrayReaderImpl.buildScalar(schema, va, scalarReader);
    default:
      throw new UnsupportedOperationException(mode.toString());
    }
  }
}
