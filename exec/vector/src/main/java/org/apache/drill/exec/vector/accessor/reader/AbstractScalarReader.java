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

import java.math.BigDecimal;

import org.apache.drill.exec.record.metadata.ColumnMetadata;
import org.apache.drill.exec.vector.accessor.ColumnReaderIndex;
import org.apache.drill.exec.vector.accessor.ObjectType;
import org.apache.drill.exec.vector.accessor.ScalarReader;
import org.apache.drill.exec.vector.accessor.ValueType;
import org.apache.drill.exec.vector.accessor.impl.AccessorUtilities;
import org.joda.time.Period;

public abstract class AbstractScalarReader implements ScalarReader, ReaderEvents {

  public static class ScalarObjectReader extends AbstractObjectReader {

    private AbstractScalarReader scalarReader;

    public ScalarObjectReader(ColumnMetadata schema, AbstractScalarReader scalarReader) {
      super(schema);
      this.scalarReader = scalarReader;
    }

    @Override
    public ObjectType type() {
      return ObjectType.SCALAR;
    }

    @Override
    public ScalarReader scalar() {
      return scalarReader;
    }

    @Override
    public Object getObject() {
      return scalarReader.getObject();
    }

    @Override
    public String getAsString() {
      return scalarReader.getAsString();
    }

    @Override
    public ReaderEvents events() { return scalarReader; }
  }

  public static class NullReader extends AbstractScalarReader {

    @Override
    public ValueType valueType() { return ValueType.NULL; }

    @Override
    public boolean isNull() { return true; }

    @Override
    public void bindIndex(ColumnReaderIndex rowIndex) { }
  }

  protected ColumnReaderIndex vectorIndex;
  protected NullStateReader nullStateReader;

  public static ScalarObjectReader nullReader(ColumnMetadata schema) {
    return new ScalarObjectReader(schema, new NullReader());
  }

  @Override
  public void bindIndex(ColumnReaderIndex rowIndex) {
    vectorIndex = rowIndex;
    nullStateReader.bindIndex(rowIndex);
  }

  @Override
  public void bindNullState(NullStateReader nullStateReader) {
    this.nullStateReader = nullStateReader;
  }

  @Override
  public NullStateReader nullStateReader() { return nullStateReader; }

  @Override
  public void reposition() { }

  @Override
  public boolean isNull() {
    return nullStateReader.isNull();
  }

  @Override
  public int getInt() {
    throw new UnsupportedOperationException();
  }

  @Override
  public long getLong() {
    throw new UnsupportedOperationException();
  }

  @Override
  public double getDouble() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getString() {
    throw new UnsupportedOperationException();
  }

  @Override
  public byte[] getBytes() {
    throw new UnsupportedOperationException();
  }

  @Override
  public BigDecimal getDecimal() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Period getPeriod() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Object getObject() {
    if (isNull()) {
      return null;
    }
    switch (valueType()) {
    case BYTES:
      return getBytes();
    case DECIMAL:
      return getDecimal();
    case DOUBLE:
      return getDouble();
    case INTEGER:
      return getInt();
    case LONG:
      return getLong();
    case PERIOD:
      return getPeriod();
    case STRING:
      return getString();
    default:
      throw new IllegalStateException("Unexpected type: " + valueType());
    }
  }

  @Override
  public String getAsString() {
    if (isNull()) {
      return "null";
    }
    switch (valueType()) {
    case BYTES:
      return AccessorUtilities.bytesToString(getBytes());
    case DOUBLE:
      return Double.toString(getDouble());
    case INTEGER:
      return Integer.toString(getInt());
    case LONG:
      return Long.toString(getLong());
    case STRING:
      return "\"" + getString() + "\"";
    case DECIMAL:
      return getDecimal().toPlainString();
    case PERIOD:
      return getPeriod().normalizedStandard().toString();
    default:
      throw new IllegalArgumentException("Unsupported type " + valueType());
    }
  }
}
