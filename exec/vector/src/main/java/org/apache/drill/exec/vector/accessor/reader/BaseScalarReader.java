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
import org.apache.drill.exec.vector.BaseDataValueVector;
import org.apache.drill.exec.vector.accessor.ColumnReaderIndex;
import org.apache.drill.exec.vector.accessor.ObjectType;
import org.apache.drill.exec.vector.accessor.ScalarReader;
import org.apache.drill.exec.vector.accessor.impl.AccessorUtilities;
import org.joda.time.Period;

import io.netty.buffer.DrillBuf;

/**
 * Column reader implementation that acts as the basis for the
 * generated, vector-specific implementations. All set methods
 * throw an exception; subclasses simply override the supported
 * method(s).
 */

public abstract class BaseScalarReader implements ScalarReader, ReaderEvents {

  public static class ScalarObjectReader extends AbstractObjectReader {

    private BaseScalarReader scalarReader;

    public ScalarObjectReader(ColumnMetadata schema, BaseScalarReader scalarReader) {
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

  public abstract static class BaseFixedWidthReader extends BaseScalarReader {

    public abstract int width();
  }

  public abstract static class BaseVarWidthReader extends BaseScalarReader {

    protected OffsetVectorReader offsetsReader;

    @Override
    public void bindVector(VectorAccessor va) {
      super.bindVector(va);
      offsetsReader = new OffsetVectorReader(
          VectorAccessors.varWidthOffsetVectorAccessor(va));
    }

    @Override
    public void bindIndex(ColumnReaderIndex index) {
      super.bindIndex(index);
      offsetsReader.bindIndex(index);
    }
  }

  /**
   * Provide access to the DrillBuf for the data vector.
   */

  public interface BufferAccessor {
    DrillBuf buffer();
  }

  private static class SingleVectorBufferAccessor implements BufferAccessor {
    private final DrillBuf buffer;

    public SingleVectorBufferAccessor(VectorAccessor va) {
      BaseDataValueVector vector = va.vector();
      buffer = vector.getBuffer();
    }

    @Override
    public DrillBuf buffer() { return buffer; }
  }

  private static class HyperVectorBufferAccessor implements BufferAccessor {
    private final VectorAccessor vectorAccessor;

    public HyperVectorBufferAccessor(VectorAccessor va) {
      vectorAccessor = va;
    }

    @Override
    public DrillBuf buffer() {
      BaseDataValueVector vector = vectorAccessor.vector();
      return vector.getBuffer();
    }
  }

  protected ColumnReaderIndex vectorIndex;
  protected VectorAccessor vectorAccessor;
  protected BufferAccessor bufferAccessor;
  protected NullStateReader nullStateReader;

  public static ScalarObjectReader buildOptional(ColumnMetadata schema,
      VectorAccessor va, BaseScalarReader reader) {

    // Reader is bound to the values vector inside the nullable vector.

    reader.bindVector(VectorAccessors.nullableValuesAccessor(va));

    // The nullability of each value depends on the "bits" vector
    // in the nullable vector.

    reader.bindNullState(new NullStateReaders.NullableIsSetVectorStateReader(va));

    // Wrap the reader in an object reader.

    return new ScalarObjectReader(schema, reader);
  }

  public static ScalarObjectReader buildRequired(ColumnMetadata schema,
      VectorAccessor va, BaseScalarReader reader) {

    // Reader is bound directly to the required vector.

    reader.bindVector(va);

    // The reader is required, values can't be null.

    reader.bindNullState(NullStateReaders.REQUIRED_STATE_READER);

    // Wrap the reader in an object reader.

    return new ScalarObjectReader(schema, reader);
  }

  public void bindVector(VectorAccessor va) {
    vectorAccessor = va;
    bufferAccessor = bufferAccessor(va);
  }

  protected BufferAccessor bufferAccessor(VectorAccessor va) {
    if (va.isHyper()) {
      return new HyperVectorBufferAccessor(va);
    } else {
      return new SingleVectorBufferAccessor(va);
    }
  }

  @Override
  public void bindNullState(NullStateReader nullStateReader) {
    this.nullStateReader = nullStateReader;
  }

  @Override
  public NullStateReader nullStateReader() { return nullStateReader; }

  @Override
  public void bindIndex(ColumnReaderIndex rowIndex) {
    this.vectorIndex = rowIndex;
    vectorAccessor.bind(rowIndex);
    nullStateReader.bindIndex(rowIndex);
  }

  @Override
  public boolean isNull() {
    return nullStateReader.isNull();
  }

  @Override
  public void reposition() { }

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
}
