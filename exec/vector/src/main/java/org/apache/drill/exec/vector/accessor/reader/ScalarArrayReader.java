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
package org.apache.drill.exec.vector.accessor.reader;

import java.util.ArrayList;
import java.util.List;

import org.apache.drill.common.types.TypeProtos.MajorType;
import org.apache.drill.exec.vector.accessor.ColumnReaderIndex;
import org.apache.drill.exec.vector.accessor.ObjectType;
import org.apache.drill.exec.vector.accessor.ScalarElementReader;
import org.apache.drill.exec.vector.complex.RepeatedValueVector;

public class ScalarArrayReader extends AbstractArrayReader {

  private final BaseElementReader elementReader;

  private ScalarArrayReader(BaseElementReader elementReader) {
    this.elementReader = elementReader;
  }

  public static ArrayObjectReader buildRepeated(RepeatedValueVector vector,
                                        BaseElementReader elementReader) {
    elementReader.bindVector(vector.getDataVector());
    elementReader.bindNullState(NullStateReader.REQUIRED_STATE_READER);
    ScalarArrayReader arrayReader = new ScalarArrayReader(elementReader);
    arrayReader.bindVector(vector);
    arrayReader.bindNullState(NullStateReader.REQUIRED_STATE_READER);
    return new ArrayObjectReader(arrayReader);
  }

  public static ArrayObjectReader buildHyperRepeated(
      MajorType majorType, VectorAccessor va,
      BaseElementReader elementReader) {
    elementReader.bindVectorAccessor(majorType, va);
    elementReader.bindNullState(NullStateReader.REQUIRED_STATE_READER);
    ScalarArrayReader arrayReader = new ScalarArrayReader(elementReader);
    arrayReader.bindVectorAccessor(null, va);
    arrayReader.bindNullState(NullStateReader.REQUIRED_STATE_READER);
    return new ArrayObjectReader(arrayReader);
  }

  @Override
  public void bindIndex(ColumnReaderIndex index) {
    super.bindIndex(index);
    elementIndex = new ElementReaderIndex(baseIndex);
    elementReader.bindIndex(elementIndex);
  }

  @Override
  public ObjectType entryType() {
    return ObjectType.SCALAR;
  }

  @Override
  public ScalarElementReader elements() {
    return elementReader;
  }

  @Override
  public void setPosn(int index) {
    throw new IllegalStateException("setPosn() not supported for scalar arrays");
  }

  @Override
  public boolean next() {
    throw new IllegalStateException("next() not supported for scalar arrays");
  }

  @Override
  public Object getObject() {
    List<Object> elements = new ArrayList<>();
    for (int i = 0; i < size(); i++) {
      elements.add(elementReader.getObject(i));
    }
    return elements;
  }

  @Override
  public String getAsString() {
    StringBuilder buf = new StringBuilder();
    buf.append("[");
    for (int i = 0; i < size(); i++) {
      if (i > 0) {
        buf.append( ", " );
      }
      buf.append(elementReader.getAsString(i));
    }
    buf.append("]");
    return buf.toString();
  }
}
