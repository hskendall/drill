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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;

import org.apache.drill.common.types.TypeProtos.MajorType;
import org.apache.drill.common.types.TypeProtos.MinorType;
import org.apache.drill.exec.expr.TypeHelper;
import org.apache.drill.exec.memory.BufferAllocator;
import org.apache.drill.exec.record.BatchSchema.SelectionVectorMode;
import org.apache.drill.exec.record.TupleMetadata;
import org.apache.drill.exec.record.TupleMetadata.ColumnMetadata;
import org.apache.drill.exec.record.TupleMetadata.StructureType;
import org.apache.drill.exec.record.VectorContainer;
import org.apache.drill.exec.record.selection.SelectionVector2;
import org.apache.drill.exec.vector.ValueVector;
import org.apache.drill.exec.vector.accessor.ValueType;
import org.apache.drill.exec.vector.complex.AbstractMapVector;
import org.bouncycastle.util.Arrays;
import org.joda.time.Duration;
import org.joda.time.Period;

/**
 * Various utilities useful for working with row sets, especially for testing.
 */

public class RowSetUtilities {

  private RowSetUtilities() { }

  /**
   * Reverse a row set by reversing the entries in an SV2. This is a quick
   * and easy way to reverse the sort order of an expected-value row set.
   * @param sv2 the SV2 which is reversed in place
   */

  public static void reverse(SelectionVector2 sv2) {
    int count = sv2.getCount();
    for (int i = 0; i < count / 2; i++) {
      char temp = sv2.getIndex(i);
      int dest = count - 1 - i;
      sv2.setIndex(i, sv2.getIndex(dest));
      sv2.setIndex(dest, temp);
    }
  }

  public static Object testDataFromInt(ValueType valueType, MajorType dataType, int value) {
    switch (valueType) {
    case BYTES:
      return Integer.toHexString(value).getBytes();
    case DOUBLE:
      return (double) value;
    case INTEGER:
      switch (dataType.getMinorType()) {
      case BIT:
        return value & 0x01;
      case SMALLINT:
        return value % 32768;
      case UINT2:
        return value & 0xFFFF;
      case TINYINT:
        return value % 128;
      case UINT1:
        return value & 0xFF;
      default:
        return value;
      }
    case LONG:
      return (long) value;
    case STRING:
      return Integer.toString(value);
    case DECIMAL:
      return BigDecimal.valueOf(value, dataType.getScale());
    case PERIOD:
      return periodFromInt(dataType.getMinorType(), value);
    default:
      throw new IllegalStateException("Unknown writer type: " + valueType);
    }
  }

  /**
   * Ad-hoc, test-only method to set a Period from an integer. Periods are made up of
   * months and millseconds. There is no mapping from one to the other, so a period
   * requires at least two number. Still, we are given just one (typically from a test
   * data generator.) Use that int value to "spread" some value across the two kinds
   * of fields. The result has no meaning, but has the same comparison order as the
   * original ints.
   *
   * @param writer column writer for a period column
   * @param minorType the Drill data type
   * @param value the integer value to apply
   * @throws VectorOverflowException
   */

  public static Period periodFromInt(MinorType minorType, int value) {
    switch (minorType) {
    case INTERVAL:
      return Duration.millis(value).toPeriod();
    case INTERVALYEAR:
      return Period.years(value / 12).withMonths(value % 12);
    case INTERVALDAY:
      int sec = value % 60;
      value = value / 60;
      int min = value % 60;
      value = value / 60;
      return Period.days(value).withMinutes(min).withSeconds(sec);
    default:
      throw new IllegalArgumentException("Writer is not an interval: " + minorType);
    }
  }

  public static VectorContainer buildVectors(BufferAllocator allocator, TupleMetadata schema) {
    VectorContainer container = new VectorContainer(allocator);
    for (int i = 0; i < schema.size(); i++) {
      ColumnMetadata colSchema = schema.metadata(i);
      @SuppressWarnings("resource")
      ValueVector vector = TypeHelper.getNewVector(colSchema.schema(), allocator, null);
      container.add(vector);
      if (colSchema.structureType() == StructureType.TUPLE) {
        buildMap(allocator, (AbstractMapVector) vector, colSchema.mapSchema());
      }
    }
    container.buildSchema(SelectionVectorMode.NONE);
    return container;
  }

  private static void buildMap(BufferAllocator allocator, AbstractMapVector mapVector, TupleMetadata mapSchema) {
    for (int i = 0; i < mapSchema.size(); i++) {
      ColumnMetadata colSchema = mapSchema.metadata(i);
      @SuppressWarnings("resource")
      ValueVector vector = TypeHelper.getNewVector(colSchema.schema(), allocator, null);
      mapVector.putChild(colSchema.name(), vector);
      if (colSchema.structureType() == StructureType.TUPLE) {
        buildMap(allocator, (AbstractMapVector) vector, colSchema.mapSchema());
      }
    }
  }

  public static void assertEqualValues(ValueType type, Object expectedObj, Object actualObj) {
    assertEqualValues(type.toString(), type, expectedObj, actualObj);
  }

  public static void assertEqualValues(String msg, ValueType type, Object expectedObj, Object actualObj) {
    switch (type) {
    case BYTES: {
        byte expected[] = (byte[]) expectedObj;
        byte actual[] = (byte[]) actualObj;
        assertEquals(msg + " - byte lengths differ", expected.length, actual.length);
        assertTrue(msg, Arrays.areEqual(expected, actual));
        break;
     }
     case DOUBLE:
       assertEquals(msg, (double) expectedObj, (double) actualObj, 0.0001);
       break;
     case INTEGER:
     case LONG:
     case STRING:
     case DECIMAL:
       assertEquals(msg, expectedObj, actualObj);
       break;
     case PERIOD: {
       Period expected = (Period) expectedObj;
       Period actual = (Period) actualObj;
       assertEquals(msg, expected.normalizedStandard(), actual.normalizedStandard());
       break;
     }
     default:
        throw new IllegalStateException( "Unexpected type: " + type);
    }
  }
}
