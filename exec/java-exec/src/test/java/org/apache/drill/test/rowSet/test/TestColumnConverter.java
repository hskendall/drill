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
package org.apache.drill.test.rowSet.test;

import static org.apache.drill.test.rowSet.RowSetUtilities.intArray;
import static org.apache.drill.test.rowSet.RowSetUtilities.strArray;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import org.apache.drill.categories.RowSetTests;
import org.apache.drill.common.types.TypeProtos.MinorType;
import org.apache.drill.exec.record.metadata.ColumnMetadata;
import org.apache.drill.exec.record.metadata.SchemaBuilder;
import org.apache.drill.exec.record.metadata.TupleMetadata;
import org.apache.drill.exec.vector.accessor.InvalidConversionError;
import org.apache.drill.exec.vector.accessor.ScalarWriter;
import org.apache.drill.exec.vector.accessor.convert.AbstractWriteConverter;
import org.apache.drill.exec.vector.accessor.convert.ColumnConversionFactory;
import org.apache.drill.exec.vector.accessor.convert.StandardConversions;
import org.apache.drill.exec.vector.accessor.convert.StandardConversions.ConversionDefn;
import org.apache.drill.exec.vector.accessor.convert.StandardConversions.ConversionType;
import org.apache.drill.test.SubOperatorTest;
import org.apache.drill.test.rowSet.RowSet;
import org.apache.drill.test.rowSet.RowSet.SingleRowSet;
import org.joda.time.DateTimeZone;
import org.joda.time.Instant;
import org.joda.time.LocalDate;
import org.joda.time.LocalTime;
import org.joda.time.Period;
import org.apache.drill.test.rowSet.RowSetBuilder;
import org.apache.drill.test.rowSet.RowSetUtilities;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Tests the column type converter feature of the column metadata
 * and of the RowSetWriter.
 * <p>
 * Also tests the set of standard conversions.
 * <p>
 * TODO: At present, the set is incomplete. It handles the most
 * common conversions, but others (such as Decimal) are incomplete.
 */

@Category(RowSetTests.class)
public class TestColumnConverter extends SubOperatorTest {

  public static final String CONVERTER_PROP = "test.conversion";
  public static final String CONVERT_TO_INT = "int";

  public static void setConverterProp(ColumnMetadata colSchema, String value) {
    colSchema.setProperty(CONVERTER_PROP, value);
  }

  /**
   * Simple type converter that allows string-to-int conversions.
   * Inherits usual int value support from the base writer.
   */
  public static class TestConverter extends AbstractWriteConverter {

    public TestConverter(ScalarWriter baseWriter) {
      super(baseWriter);
    }

    @Override
    public void setString(String value) {
      setInt(Integer.parseInt(value));
    }

    @Override
    public void setValue(Object value) {
      setString((String) value);
    }
  }

  /**
   * Mock conversion factory that uses a property on the column metadata
   * to indicate that a converter should be inserted. This is primarily a
   * proof-of-concept: that the conversion factory works, and that it can
   * use properties. Also verifies that the plumbing works for the
   * three data modes.
   */
  public static class ConverterFactory implements ColumnConversionFactory {

    @Override
    public AbstractWriteConverter newWriter(ScalarWriter baseWriter) {
      String value = baseWriter.schema().property(CONVERTER_PROP);
      if (value == null) {
        return null;
      }
      if (value.equals(CONVERT_TO_INT)) {
        return new TestConverter(baseWriter);
      }
      return null;
    }
  }

  /**
   * Test doing type conversion using (ad-hoc) properties on the
   * column metadata to drive conversion. Verifies that the properties
   * are available to the converter.
   */
  @Test
  public void testScalarConverter() {

    // Create the schema

    TupleMetadata schema = new SchemaBuilder()
        .add("n1", MinorType.INT)
        .addNullable("n2", MinorType.INT)
        .buildSchema();

    // Add a type converter. Passed in as a factory
    // since we must create a new one for each row set writer.

    setConverterProp(schema.metadata("n1"), CONVERT_TO_INT);
    setConverterProp(schema.metadata("n2"), CONVERT_TO_INT);

    // Write data as both a string as an integer

    ConverterFactory conversionFactory = new ConverterFactory();
    RowSet actual = new RowSetBuilder(fixture.allocator(), schema, conversionFactory)
        .addRow("123", "12")
        .addRow(234, 23)
        .build();

    // Build the expected vector without a type converter.

    TupleMetadata expectedSchema = new SchemaBuilder()
        .add("n1", MinorType.INT)
        .addNullable("n2", MinorType.INT)
        .buildSchema();
    final SingleRowSet expected = fixture.rowSetBuilder(expectedSchema)
        .addRow(123, 12)
        .addRow(234, 23)
        .build();

    // Compare

    RowSetUtilities.verify(expected, actual);
  }

  @Test
  public void testArrayConverter() {

    // Create the schema

    TupleMetadata schema = new SchemaBuilder()
        .addArray("n", MinorType.INT)
        .buildSchema();

    // Add a type converter. Passed in as a factory
    // since we must create a new one for each row set writer.

    setConverterProp(schema.metadata("n"), CONVERT_TO_INT);

    // Write data as both a string as an integer

    ConverterFactory conversionFactory = new ConverterFactory();
    RowSet actual = new RowSetBuilder(fixture.allocator(), schema, conversionFactory)
        .addSingleCol(strArray("123", "124"))
        .addSingleCol(intArray(234, 235))
        .build();

    // Build the expected vector without a type converter.

    TupleMetadata expectedSchema = new SchemaBuilder()
        .addArray("n", MinorType.INT)
        .buildSchema();
    final SingleRowSet expected = fixture.rowSetBuilder(expectedSchema)
        .addSingleCol(intArray(123, 124))
        .addSingleCol(intArray(234, 235))
        .build();

    // Compare

    RowSetUtilities.verify(expected, actual);
  }

  /**
   * Mock column conversion factory that takes an input schema, matches it against
   * the given writer, and inserts a standard type conversion shim.
   */
  private static class ConversionTestFixture implements ColumnConversionFactory {

    private TupleMetadata inputSchema;

    public ConversionTestFixture(TupleMetadata inputSchema) {
      this.inputSchema = inputSchema;
    }

    @Override
    public AbstractWriteConverter newWriter(ScalarWriter baseWriter) {
      ColumnMetadata inputCol = inputSchema.metadata(baseWriter.schema().name());
      assertNotNull(inputCol);
      ConversionDefn defn = StandardConversions.analyze(inputCol, baseWriter.schema());
      assertNotNull(defn.conversionClass);
      return StandardConversions.newInstance(defn.conversionClass, baseWriter);
    }
  }

  /**
   * Test the standard string-to-type conversion using an ad-hoc conversion
   * from the input type (the type used by the row set builder) to the output
   * (vector) type.
   */
  @Test
  public void testStringToNumberConversion() {

    // Create the schema

    TupleMetadata outputSchema = new SchemaBuilder()
        .add("ti", MinorType.TINYINT)
        .add("si", MinorType.SMALLINT)
        .add("int", MinorType.INT)
        .add("bi", MinorType.BIGINT)
        .add("fl", MinorType.FLOAT4)
        .add("db", MinorType.FLOAT8)
       .buildSchema();
    TupleMetadata inputSchema = new SchemaBuilder()
        .add("ti", MinorType.VARCHAR)
        .add("si", MinorType.VARCHAR)
        .add("int", MinorType.VARCHAR)
        .add("bi", MinorType.VARCHAR)
        .add("fl", MinorType.VARCHAR)
        .add("db", MinorType.VARCHAR)
       .buildSchema();

    RowSet actual = new RowSetBuilder(fixture.allocator(), outputSchema,
        new ConversionTestFixture(inputSchema))
        .addRow("11", "12", "13", "14", "15.5", "16.25")
        .addRow("127", "32757", Integer.toString(Integer.MAX_VALUE),
            Long.toString(Long.MAX_VALUE), "10E6", "10E200")
        .build();

    // Build the expected vector without a type converter.

    final SingleRowSet expected = fixture.rowSetBuilder(outputSchema)
        .addRow(11, 12, 13, 14L, 15.5F, 16.25D)
        .addRow(127, 32757, Integer.MAX_VALUE, Long.MAX_VALUE, 10E6F, 10E200D)
        .build();

    // Compare

    RowSetUtilities.verify(expected, actual);
  }

  @Test
  public void testStringToNumberConversionError() {

    TupleMetadata outputSchema = new SchemaBuilder()
       .add("int", MinorType.INT)
       .buildSchema();
    TupleMetadata inputSchema = new SchemaBuilder()
       .add("int", MinorType.VARCHAR)
       .buildSchema();

    RowSetBuilder builder = new RowSetBuilder(fixture.allocator(), outputSchema,
        new ConversionTestFixture(inputSchema));
    try {
      builder.addRow("foo");
      fail();
    } catch (InvalidConversionError e) {
      // Expected
    } finally {
      builder.build().clear();
    }
  }

  /**
   * Tests the implicit conversions provided by the column writer itself.
   * No conversion mechanism is needed in this case.
   */
  @Test
  public void testImplicitConversion() {

    TupleMetadata schema = new SchemaBuilder()
        .add("ti", MinorType.TINYINT)
        .add("si", MinorType.SMALLINT)
        .add("int", MinorType.INT)
        .add("bi", MinorType.BIGINT)
        .add("fl", MinorType.FLOAT4)
        .add("db", MinorType.FLOAT8)
        .buildSchema();

    // Test allowed implicit conversions.

    RowSet actual = new RowSetBuilder(fixture.allocator(), schema)
        .addRow(11,  12,  13,  14,  15,  16)  // int
        .addRow(21L, 22L, 23L, 24L, 25L, 26L) // long
        .addRow(31F, 32F, 33F, 34F, 35F, 36F) // float
        .addRow(41D, 42D, 43D, 44D, 45D, 46D) // double
        .build();

    // Build the expected vector without a type converter.

    final SingleRowSet expected = fixture.rowSetBuilder(schema)
        .addRow(11, 12, 13, 14L, 15F, 16D)
        .addRow(21, 22, 23, 24L, 25F, 26D)
        .addRow(31, 32, 33, 34L, 35F, 36D)
        .addRow(41, 42, 43, 44L, 45F, 46D)
        .build();

    // Compare

    RowSetUtilities.verify(expected, actual);
  }

  /**
   * The column accessors provide only int setters. For performance, the int value is
   * assumed to be of the correct range for the target column. If not, truncation of
   * the highest bytes occurs.
   * <p>
   * The assumption is, if the reader or other code expects that overflow might
   * occur, that code should be implemented in the client (or in a type conversion
   * shim), leaving the normal code path to optimize for the 99% of the cases where
   * the value is in the proper range.
   */
  @Test
  public void testImplicitConversionIntTruncation() {

    TupleMetadata schema = new SchemaBuilder()
        .add("ti", MinorType.TINYINT)
        .add("si", MinorType.SMALLINT)
        .buildSchema();

    // Test allowed implicit conversions.
    RowSet actual = new RowSetBuilder(fixture.allocator(), schema)
        .addRow(Byte.MAX_VALUE + 1, Short.MAX_VALUE + 1)
        .addRow(Byte.MAX_VALUE + 2, Short.MAX_VALUE + 2)
        .build();

    // Build the expected vector without a type converter.

    final SingleRowSet expected = fixture.rowSetBuilder(schema)
        .addRow(Byte.MIN_VALUE, Short.MIN_VALUE)
        .addRow(Byte.MIN_VALUE + 1, Short.MIN_VALUE + 1)
        .build();

    // Compare

    RowSetUtilities.verify(expected, actual);
  }

  /**
   * Overflow from double-to-int is detected.
   */
  @Test
  public void testImplicitConversionIntOverflow() {

    TupleMetadata schema = new SchemaBuilder()
        .add("int", MinorType.INT)
        .buildSchema();

    {
      RowSetBuilder builder = new RowSetBuilder(fixture.allocator(), schema);
      try {
        builder.addRow((long) Integer.MAX_VALUE + 1);
        fail();
      } catch (InvalidConversionError e) {
        // Expected
      } finally {
        builder.build().clear();
      }
    }
    {
      RowSetBuilder builder = new RowSetBuilder(fixture.allocator(), schema);
      try {
        builder.addRow((double) Integer.MAX_VALUE + 1);
        fail();
      } catch (InvalidConversionError e) {
        // Expected
      } finally {
        builder.build().clear();
      }
    }
  }

  /**
   * Implicit conversion from double (or float) follows the Java Math.round
   * rules: round to the closest long value. Readers that want other behavior
   * should insert a type-conversion shim to implement the preferred rules.
   */
  @Test
  public void testImplicitConversionDoubleClamp() {

    TupleMetadata schema = new SchemaBuilder()
        .add("bi", MinorType.BIGINT)
        .buildSchema();

    RowSet actual = new RowSetBuilder(fixture.allocator(), schema)
        .addRow(Long.MAX_VALUE * 10D)
        .addRow(Double.NaN)
        .addRow(Double.MAX_VALUE)
        .addRow(Double.MIN_VALUE)
        .addRow(Double.POSITIVE_INFINITY)
        .addRow(Double.NEGATIVE_INFINITY)
        .build();

    final SingleRowSet expected = fixture.rowSetBuilder(schema)
        .addRow(Long.MAX_VALUE)
        .addRow(0)
        .addRow(Long.MAX_VALUE)
        .addRow(0)
        .addRow(Long.MAX_VALUE)
        .addRow(Long.MIN_VALUE)
        .build();

    RowSetUtilities.verify(expected, actual);
  }

  /**
   * Implicit conversion from String to period using default ISO
   * format.
   */
  @Test
  public void testStringToInterval() {

    TupleMetadata outputSchema = new SchemaBuilder()
        .add("id", MinorType.INTERVALDAY)
        .add("iy", MinorType.INTERVALYEAR)
        .add("int", MinorType.INTERVAL)
        .buildSchema();

    TupleMetadata inputSchema = new SchemaBuilder()
        .add("id", MinorType.VARCHAR)
        .add("iy", MinorType.VARCHAR)
        .add("int", MinorType.VARCHAR)
        .buildSchema();

    RowSet actual = new RowSetBuilder(fixture.allocator(), outputSchema,
        new ConversionTestFixture(inputSchema))
        .addRow("P2DT3H4M5S", "P9Y8M", "P9Y8M2DT3H4M5S")
        .build();

    Period p1 = Period.days(2).plusHours(3).plusMinutes(4).plusSeconds(5);
    Period p2 = Period.years(9).plusMonths(8);
    Period p3 = p1.plus(p2);
    final SingleRowSet expected = fixture.rowSetBuilder(outputSchema)
        .addRow(p1, p2, p3)
        .build();

    RowSetUtilities.verify(expected, actual);
  }

  /**
   * Test VARCHAR to DATE, TIME and TIMESTAMP conversion
   * using default ISO formats.
   */
  @Test
  public void testStringToDateTimeDefault() {

    TupleMetadata outputSchema = new SchemaBuilder()
        .add("date", MinorType.DATE)
        .add("time", MinorType.TIME)
        .add("ts", MinorType.TIMESTAMP)
        .buildSchema();

    TupleMetadata inputSchema = new SchemaBuilder()
        .add("date", MinorType.VARCHAR)
        .add("time", MinorType.VARCHAR)
        .add("ts", MinorType.VARCHAR)
        .buildSchema();

    RowSet actual = new RowSetBuilder(fixture.allocator(), outputSchema,
        new ConversionTestFixture(inputSchema))
        .addRow("2019-03-28", "12:34:56", "2019-03-28T12:34:56")
        .addRow("2019-03-28", "12:34:56", "2019-03-28T12:34:56")
        .build();

    LocalTime lt = new LocalTime(12, 34, 56);
    LocalDate ld = new LocalDate(2019, 3, 28);
    Instant ts = ld.toDateTime(lt, DateTimeZone.UTC).toInstant();
    final SingleRowSet expected = fixture.rowSetBuilder(outputSchema)
        .addRow(ld, lt, ts)
        .addRow(ld.toDateTimeAtStartOfDay(DateTimeZone.UTC).toInstant().getMillis(),
                lt.getMillisOfDay(), ts.getMillis())
        .build();

    RowSetUtilities.verify(expected, actual);
  }

  @Test
  public void testStringToDateTimeCustom() {

    TupleMetadata outputSchema = new SchemaBuilder()
        .add("date", MinorType.DATE)
        .add("time", MinorType.TIME)
        .add("ts", MinorType.TIMESTAMP)
        .buildSchema();

    outputSchema.metadata("date").setFormat("M/d/yyyy");
    outputSchema.metadata("time").setFormat("hh:mm:ss a");
    outputSchema.metadata("ts").setFormat("M/d/yyyy hh:mm:ss a X");

    TupleMetadata inputSchema = new SchemaBuilder()
        .add("date", MinorType.VARCHAR)
        .add("time", MinorType.VARCHAR)
        .add("ts", MinorType.VARCHAR)
        .buildSchema();

    RowSet actual = new RowSetBuilder(fixture.allocator(), outputSchema,
        new ConversionTestFixture(inputSchema))
        .addRow("3/28/2019", "12:34:56 PM", "3/28/2019 12:34:56 PM Z")
        .addRow("3/28/2019", "12:34:56 PM", "3/28/2019 12:34:56 PM Z")
        .build();

    LocalTime lt = new LocalTime(12, 34, 56);
    LocalDate ld = new LocalDate(2019, 3, 28);
    Instant ts = ld.toDateTime(lt, DateTimeZone.UTC).toInstant();
    final SingleRowSet expected = fixture.rowSetBuilder(outputSchema)
        .addRow(ld, lt, ts)
        .addRow(ld.toDateTimeAtStartOfDay(DateTimeZone.UTC).toInstant().getMillis(),
                lt.getMillisOfDay(), ts.getMillis())
        .build();

    RowSetUtilities.verify(expected, actual);
  }

  private static void expect(ConversionType type, ConversionDefn defn) {
    assertEquals(type, defn.type);
  }

  /**
   * Test the conversion type for a subset of type pairs.
   */
  @Test
  public void testImplicitConversionType() {
    TupleMetadata schema = new SchemaBuilder()
        .add("ti", MinorType.TINYINT)
        .add("si", MinorType.SMALLINT)
        .add("int", MinorType.INT)
        .add("bi", MinorType.BIGINT)
        .add("fl", MinorType.FLOAT4)
        .add("db", MinorType.FLOAT8)
        .add("dec", MinorType.VARDECIMAL)
        .buildSchema();
    ColumnMetadata tinyIntCol = schema.metadata("ti");
    ColumnMetadata smallIntCol = schema.metadata("si");
    ColumnMetadata intCol = schema.metadata("int");
    ColumnMetadata bigIntCol = schema.metadata("bi");
    ColumnMetadata float4Col = schema.metadata("fl");
    ColumnMetadata float8Col = schema.metadata("db");
    ColumnMetadata decimalCol = schema.metadata("dec");

    // TinyInt --> x
    expect(ConversionType.NONE, StandardConversions.analyze(tinyIntCol, tinyIntCol));
    expect(ConversionType.IMPLICIT, StandardConversions.analyze(tinyIntCol, smallIntCol));
    expect(ConversionType.IMPLICIT, StandardConversions.analyze(tinyIntCol, intCol));
    expect(ConversionType.IMPLICIT, StandardConversions.analyze(tinyIntCol, bigIntCol));
    expect(ConversionType.IMPLICIT, StandardConversions.analyze(tinyIntCol, float4Col));
    expect(ConversionType.IMPLICIT, StandardConversions.analyze(tinyIntCol, float8Col));
    expect(ConversionType.IMPLICIT, StandardConversions.analyze(tinyIntCol, decimalCol));

    // SmallInt --> x
    expect(ConversionType.IMPLICIT_UNSAFE, StandardConversions.analyze(smallIntCol, tinyIntCol));
    expect(ConversionType.NONE, StandardConversions.analyze(smallIntCol, smallIntCol));
    expect(ConversionType.IMPLICIT, StandardConversions.analyze(smallIntCol, intCol));
    expect(ConversionType.IMPLICIT, StandardConversions.analyze(smallIntCol, bigIntCol));
    expect(ConversionType.IMPLICIT, StandardConversions.analyze(smallIntCol, float4Col));
    expect(ConversionType.IMPLICIT, StandardConversions.analyze(smallIntCol, float8Col));
    expect(ConversionType.IMPLICIT, StandardConversions.analyze(smallIntCol, decimalCol));

    // Int --> x
    expect(ConversionType.IMPLICIT_UNSAFE, StandardConversions.analyze(intCol, tinyIntCol));
    expect(ConversionType.IMPLICIT_UNSAFE, StandardConversions.analyze(intCol, smallIntCol));
    expect(ConversionType.NONE, StandardConversions.analyze(intCol, intCol));
    expect(ConversionType.IMPLICIT, StandardConversions.analyze(intCol, bigIntCol));
    expect(ConversionType.IMPLICIT, StandardConversions.analyze(intCol, float4Col));
    expect(ConversionType.IMPLICIT, StandardConversions.analyze(intCol, float8Col));
    expect(ConversionType.IMPLICIT, StandardConversions.analyze(intCol, decimalCol));

    // BigInt --> x
    expect(ConversionType.IMPLICIT_UNSAFE, StandardConversions.analyze(bigIntCol, tinyIntCol));
    expect(ConversionType.IMPLICIT_UNSAFE, StandardConversions.analyze(bigIntCol, smallIntCol));
    expect(ConversionType.IMPLICIT_UNSAFE, StandardConversions.analyze(bigIntCol, intCol));
    expect(ConversionType.NONE, StandardConversions.analyze(bigIntCol, bigIntCol));
    expect(ConversionType.IMPLICIT, StandardConversions.analyze(bigIntCol, float4Col));
    expect(ConversionType.IMPLICIT, StandardConversions.analyze(bigIntCol, float8Col));
    expect(ConversionType.IMPLICIT, StandardConversions.analyze(bigIntCol, decimalCol));

    // Float4 --> x
    expect(ConversionType.IMPLICIT_UNSAFE, StandardConversions.analyze(float4Col, tinyIntCol));
    expect(ConversionType.IMPLICIT_UNSAFE, StandardConversions.analyze(float4Col, smallIntCol));
    expect(ConversionType.IMPLICIT_UNSAFE, StandardConversions.analyze(float4Col, intCol));
    expect(ConversionType.IMPLICIT_UNSAFE, StandardConversions.analyze(float4Col, bigIntCol));
    expect(ConversionType.NONE, StandardConversions.analyze(float4Col, float4Col));
    expect(ConversionType.IMPLICIT, StandardConversions.analyze(float4Col, float8Col));
    expect(ConversionType.IMPLICIT, StandardConversions.analyze(float4Col, decimalCol));

    // Float8 --> x
    expect(ConversionType.IMPLICIT_UNSAFE, StandardConversions.analyze(float8Col, tinyIntCol));
    expect(ConversionType.IMPLICIT_UNSAFE, StandardConversions.analyze(float8Col, smallIntCol));
    expect(ConversionType.IMPLICIT_UNSAFE, StandardConversions.analyze(float8Col, intCol));
    expect(ConversionType.IMPLICIT_UNSAFE, StandardConversions.analyze(float8Col, bigIntCol));
    expect(ConversionType.IMPLICIT_UNSAFE, StandardConversions.analyze(float8Col, float4Col));
    expect(ConversionType.NONE, StandardConversions.analyze(float8Col, float8Col));
    expect(ConversionType.IMPLICIT, StandardConversions.analyze(float8Col, decimalCol));

    // Decimal --> x
    expect(ConversionType.EXPLICIT, StandardConversions.analyze(decimalCol, tinyIntCol));
    expect(ConversionType.EXPLICIT, StandardConversions.analyze(decimalCol, smallIntCol));
    expect(ConversionType.EXPLICIT, StandardConversions.analyze(decimalCol, intCol));
    expect(ConversionType.EXPLICIT, StandardConversions.analyze(decimalCol, bigIntCol));
    expect(ConversionType.EXPLICIT, StandardConversions.analyze(decimalCol, float4Col));
    expect(ConversionType.EXPLICIT, StandardConversions.analyze(decimalCol, float8Col));
    expect(ConversionType.NONE, StandardConversions.analyze(decimalCol, decimalCol));
  }
}
