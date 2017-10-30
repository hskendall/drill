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
package org.apache.drill.exec.physical.impl.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.apache.drill.common.types.TypeProtos.DataMode;
import org.apache.drill.common.types.TypeProtos.MajorType;
import org.apache.drill.common.types.TypeProtos.MinorType;
import org.apache.drill.exec.physical.impl.protocol.SchemaTracker;
import org.apache.drill.exec.physical.impl.scan.project.ScanSchemaOrchestrator;
import org.apache.drill.exec.physical.impl.scan.project.ScanSchemaOrchestrator.ReaderSchemaOrchestrator;
import org.apache.drill.exec.physical.rowSet.ResultSetLoader;
import org.apache.drill.exec.record.BatchSchema;
import org.apache.drill.exec.record.BatchSchema.SelectionVectorMode;
import org.apache.drill.exec.record.TupleMetadata;
import org.apache.drill.exec.record.VectorContainer;
import org.apache.drill.test.SubOperatorTest;
import org.apache.drill.test.rowSet.RowSet.SingleRowSet;
import org.apache.drill.test.rowSet.RowSetComparison;
import org.apache.drill.test.rowSet.SchemaBuilder;
import org.junit.Test;

/**
 * Test the early-schema support of the scan orchestrator. "Early schema"
 * refers to the case in which the reader can provide a schema when the
 * reader is opened. Examples: CSV, HBase, MapR-DB binary, JDBC.
 * <p>
 * The tests here focus on the scan orchestrator itself; the tests assume
 * that tests for lower-level components have already passed.
 */

public class TestScanOrchestratorEarlySchema extends SubOperatorTest {

  /**
   * Test SELECT * from an early-schema table of (a, b)
   */

  @Test
  public void testEarlySchemaWildcard() {
    ScanSchemaOrchestrator scanner = new ScanSchemaOrchestrator(fixture.allocator());

    // SELECT * ...

    scanner.build(ScanTestUtils.projectAll());

    // ... FROM table

    ReaderSchemaOrchestrator reader = scanner.startReader();

    // file schema (a, b)

    TupleMetadata tableSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .buildSchema();

    // Create the table loader

    ResultSetLoader loader = reader.makeTableLoader(tableSchema);

    // Schema provided, so an empty batch is available to
    // send downstream.

    {
      SingleRowSet expected = fixture.rowSetBuilder(tableSchema)
          .build();

      assertNotNull(scanner.output());
      new RowSetComparison(expected)
          .verifyAndClearAll(fixture.wrap(scanner.output()));
    }

    // Create a batch of data.

    reader.startBatch();
    loader.writer()
      .addRow(1, "fred")
      .addRow(2, "wilma");

    reader.endBatch();

    // Verify

    {
      SingleRowSet expected = fixture.rowSetBuilder(tableSchema)
          .addRow(1, "fred")
          .addRow(2, "wilma")
          .build();

      new RowSetComparison(expected)
          .verifyAndClearAll(fixture.wrap(scanner.output()));
    }

    // Second batch.

    reader.startBatch();
    loader.writer()
      .addRow(3, "barney")
      .addRow(4, "betty");

    reader.endBatch();

    // Verify

    {
      SingleRowSet expected = fixture.rowSetBuilder(tableSchema)
          .addRow(3, "barney")
          .addRow(4, "betty")
          .build();

      new RowSetComparison(expected)
          .verifyAndClearAll(fixture.wrap(scanner.output()));
    }

    // Explicit reader close. (All other tests are lazy, they
    // use an implicit close.)

    scanner.closeReader();

    scanner.close();
  }

  /**
   * Test SELECT a, b FROM table(a, b)
   */

  @Test
  public void testEarlySchemaSelectAll() {
    ScanSchemaOrchestrator scanner = new ScanSchemaOrchestrator(fixture.allocator());

    // SELECT a, b ...

    scanner.build(ScanTestUtils.projectList("a", "b"));

    // ... FROM table

    ReaderSchemaOrchestrator reader = scanner.startReader();

    // file schema (a, b)

    TupleMetadata tableSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .buildSchema();

    // Create the table loader

    ResultSetLoader loader = reader.makeTableLoader(tableSchema);

    // Verify empty batch.

    {
      SingleRowSet expected = fixture.rowSetBuilder(tableSchema)
          .build();

      assertNotNull(scanner.output());
      new RowSetComparison(expected)
          .verifyAndClearAll(fixture.wrap(scanner.output()));
    }

    // Create a batch of data.

    reader.startBatch();
    loader.writer()
      .addRow(1, "fred")
      .addRow(2, "wilma");
    reader.endBatch();

    // Verify

    {
      SingleRowSet expected = fixture.rowSetBuilder(tableSchema)
          .addRow(1, "fred")
          .addRow(2, "wilma")
          .build();

      new RowSetComparison(expected)
          .verifyAndClearAll(fixture.wrap(scanner.output()));
    }

    scanner.close();
  }

  /**
   * Test SELECT b, a FROM table(a, b)
   */

  @Test
  public void testEarlySchemaSelectAllReorder() {
    ScanSchemaOrchestrator scanner = new ScanSchemaOrchestrator(fixture.allocator());

    // SELECT b, a ...

    scanner.build(ScanTestUtils.projectList("b", "a"));

    // ... FROM table

    ReaderSchemaOrchestrator reader = scanner.startReader();

    // file schema (a, b)

    TupleMetadata tableSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .buildSchema();

    // Create the table loader

    ResultSetLoader loader = reader.makeTableLoader(tableSchema);

    // Verify empty batch.

    BatchSchema expectedSchema = new SchemaBuilder()
        .add("b", MinorType.VARCHAR)
        .add("a", MinorType.INT)
        .build();
   {
      SingleRowSet expected = fixture.rowSetBuilder(expectedSchema)
          .build();

      assertNotNull(scanner.output());
      new RowSetComparison(expected)
          .verifyAndClearAll(fixture.wrap(scanner.output()));
   }

    // Create a batch of data.

   reader.startBatch();
   loader.writer()
     .addRow(1, "fred")
     .addRow(2, "wilma");
   reader.endBatch();

    // Verify

   {
     SingleRowSet expected = fixture.rowSetBuilder(expectedSchema)
        .addRow("fred", 1)
        .addRow("wilma", 2)
        .build();

      new RowSetComparison(expected)
          .verifyAndClearAll(fixture.wrap(scanner.output()));
   }

    scanner.close();
  }

  /**
   * Test SELECT a, b, c FROM table(a, b)
   * c will be null
   */

  @Test
  public void testEarlySchemaSelectExtra() {
    ScanSchemaOrchestrator scanner = new ScanSchemaOrchestrator(fixture.allocator());

    // SELECT a, b, c ...

    scanner.build(ScanTestUtils.projectList("a", "b", "c"));

    // ... FROM table

    ReaderSchemaOrchestrator reader = scanner.startReader();

    // file schema (a, b)

    TupleMetadata tableSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .buildSchema();

    // Create the table loader

    ResultSetLoader loader = reader.makeTableLoader(tableSchema);

    // Verify empty batch

    BatchSchema expectedSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .addNullable("c", MinorType.INT)
        .build();
    {
      SingleRowSet expected = fixture.rowSetBuilder(expectedSchema)
          .build();

      assertNotNull(scanner.output());
      new RowSetComparison(expected)
          .verifyAndClearAll(fixture.wrap(scanner.output()));
   }

   // Create a batch of data.

    reader.startBatch();
    loader.writer()
      .addRow(1, "fred")
      .addRow(2, "wilma");
    reader.endBatch();

    // Verify

    {
      SingleRowSet expected = fixture.rowSetBuilder(expectedSchema)
        .addRow(1, "fred", null)
        .addRow(2, "wilma", null)
        .build();

      new RowSetComparison(expected)
          .verifyAndClearAll(fixture.wrap(scanner.output()));
    }

    scanner.close();
  }

  /**
   * Test SELECT a, b, c FROM table(a, b)
   * c will be null of type VARCHAR
   */

  @Test
  public void testEarlySchemaSelectExtraCustomType() {
    ScanSchemaOrchestrator scanner = new ScanSchemaOrchestrator(fixture.allocator());

    // Null columns of type VARCHAR

    MajorType nullType = MajorType.newBuilder()
        .setMinorType(MinorType.VARCHAR)
        .setMode(DataMode.OPTIONAL)
        .build();
    scanner.setNullType(nullType);

    // SELECT a, b, c ...

    scanner.build(ScanTestUtils.projectList("a", "b", "c"));

    // ... FROM table ...

    ReaderSchemaOrchestrator reader = scanner.startReader();

    // file schema (a, b)

    TupleMetadata tableSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .buildSchema();

    // Create the table loader

    ResultSetLoader loader = reader.makeTableLoader(tableSchema);

    // Verify empty batch

    BatchSchema expectedSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .addNullable("c", MinorType.VARCHAR)
        .build();
    {
      SingleRowSet expected = fixture.rowSetBuilder(expectedSchema)
          .build();

      assertNotNull(scanner.output());
      new RowSetComparison(expected)
          .verifyAndClearAll(fixture.wrap(scanner.output()));
   }

    // Create a batch of data.

    reader.startBatch();
    loader.writer()
      .addRow(1, "fred")
      .addRow(2, "wilma");
    reader.endBatch();

    // Verify

    {
      SingleRowSet expected = fixture.rowSetBuilder(expectedSchema)
        .addRow(1, "fred", null)
        .addRow(2, "wilma", null)
        .build();

      new RowSetComparison(expected)
          .verifyAndClearAll(fixture.wrap(scanner.output()));
    }

    scanner.close();
  }

  /**
   * Test SELECT a FROM table(a, b)
   */

  @Test
  public void testEarlySchemaSelectSubset() {
    ScanSchemaOrchestrator scanner = new ScanSchemaOrchestrator(fixture.allocator());

    // SELECT a ...

    scanner.build(ScanTestUtils.projectList("a"));

    // ... FROM table

    ReaderSchemaOrchestrator reader = scanner.startReader();

    // file schema (a, b)

    TupleMetadata tableSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .buildSchema();

    // Create the table loader

    ResultSetLoader loader = reader.makeTableLoader(tableSchema);

    // Verify that unprojected column is unprojected in the
    // table loader.

    assertFalse(loader.writer().column("b").schema().isProjected());

    // Verify empty batch.

    BatchSchema expectedSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .build();
    {
      SingleRowSet expected = fixture.rowSetBuilder(expectedSchema)
          .build();

      assertNotNull(scanner.output());
      new RowSetComparison(expected)
          .verifyAndClearAll(fixture.wrap(scanner.output()));
    }

    // Create a batch of data.

    reader.startBatch();
    loader.writer()
      .addRow(1, "fred")
      .addRow(2, "wilma");
    reader.endBatch();

    // Verify

    {
      SingleRowSet expected = fixture.rowSetBuilder(expectedSchema)
        .addRow(1)
        .addRow(2)
        .build();

      new RowSetComparison(expected)
          .verifyAndClearAll(fixture.wrap(scanner.output()));
    }

    scanner.close();
  }


  /**
   * Test SELECT * from an early-schema table of () (that is,
   * a schema that consists of zero columns.
   */

  @Test
  public void testEmptySchema() {
    ScanSchemaOrchestrator scanner = new ScanSchemaOrchestrator(fixture.allocator());

    // SELECT * ...

    scanner.build(ScanTestUtils.projectAll());

    // ... FROM table

    ReaderSchemaOrchestrator reader = scanner.startReader();

    // file schema ()

    TupleMetadata tableSchema = new SchemaBuilder()
        .buildSchema();

    // Create the table loader

    reader.makeTableLoader(tableSchema);

    // Schema provided, so an empty batch is available to
    // send downstream.

    {
      SingleRowSet expected = fixture.rowSetBuilder(tableSchema)
          .build();

      assertNotNull(scanner.output());
      new RowSetComparison(expected)
          .verifyAndClearAll(fixture.wrap(scanner.output()));
    }

    // Create a batch of data. Because there are no columns, it does
    // not make sense to ready any rows.

    reader.startBatch();
    reader.endBatch();

    // Verify

    {
      SingleRowSet expected = fixture.rowSetBuilder(tableSchema)
          .build();

      new RowSetComparison(expected)
          .verifyAndClearAll(fixture.wrap(scanner.output()));
    }

    scanner.close();
  }


  /**
   * Test SELECT a from an early-schema table of () (that is,
   * a schema that consists of zero columns.
   */

  @Test
  public void testEmptySchemaExtra() {
    ScanSchemaOrchestrator scanner = new ScanSchemaOrchestrator(fixture.allocator());

    // SELECT * ...

    scanner.build(ScanTestUtils.projectList("a"));

    // ... FROM table

    ReaderSchemaOrchestrator reader = scanner.startReader();

    // file schema ()

    TupleMetadata tableSchema = new SchemaBuilder()
        .buildSchema();

    // Create the table loader

    reader.makeTableLoader(tableSchema);

    // Verify initial empty batch.

    BatchSchema expectedSchema = new SchemaBuilder()
        .addNullable("a", MinorType.INT)
        .build();
    {
      SingleRowSet expected = fixture.rowSetBuilder(expectedSchema)
          .build();

      assertNotNull(scanner.output());
      new RowSetComparison(expected)
          .verifyAndClearAll(fixture.wrap(scanner.output()));
    }

    // Create a batch of data. Because there are no columns, it does
    // not make sense to ready any rows.

    reader.startBatch();
    reader.endBatch();

    // Verify

    {
      SingleRowSet expected = fixture.rowSetBuilder(expectedSchema)
          .build();

      new RowSetComparison(expected)
          .verifyAndClearAll(fixture.wrap(scanner.output()));
    }

    scanner.close();
  }
  /**
   * The projection mechanism provides "type smoothing": null
   * columns prefer the type of previously-seen non-null columns.
   *
   * <code><pre>
   * SELECT a, b ...
   *
   * Table 1: (a: BIGINT, b: VARCHAR)
   * Table 2: (a: BIGINT)
   * Table 3: (b: VARCHAR)
   * </pre></code>
   * The result in all cases should be
   * <tt>(a : BIGINT, b: VARCHAR)</tt>
   */

  @Test
  public void testTypeSmoothingExplicit() {
    ScanSchemaOrchestrator scanner = new ScanSchemaOrchestrator(fixture.allocator());
    TupleMetadata table1Schema = new SchemaBuilder()
        .add("A", MinorType.BIGINT)
        .addNullable("B", MinorType.VARCHAR)
        .addArray("C", MinorType.INT)
        .buildSchema();
    BatchSchema resultSchema = new BatchSchema(SelectionVectorMode.NONE, table1Schema.toFieldList());
    SchemaTracker tracker = new SchemaTracker();

    // SELECT * ...

    scanner.build(ScanTestUtils.projectList("a", "b", "c"));

    int schemaVersion;
    {
      // ... FROM table1(a, b, c)

      ReaderSchemaOrchestrator reader = scanner.startReader();
      reader.makeTableLoader(table1Schema);
      VectorContainer output = scanner.output();
      tracker.trackSchema(output);
      schemaVersion = tracker.schemaVersion();
      assertTrue(resultSchema.isEquivalent(output.getSchema()));
      scanner.closeReader();
    }

    {
      // ... FROM table1(a, c)
      //
      // B is dropped. But, it is nullable, so the vector cache
      // can supply the proper type to ensure continuity.

      TupleMetadata table2Schema = new SchemaBuilder()
          .add("A", MinorType.BIGINT)
          .addArray("C", MinorType.INT)
          .buildSchema();
      ReaderSchemaOrchestrator reader = scanner.startReader();
      reader.makeTableLoader(table2Schema);
      VectorContainer output = scanner.output();
      tracker.trackSchema(output);
      assertEquals(schemaVersion, tracker.schemaVersion());
      assertTrue(resultSchema.isEquivalent(output.getSchema()));
      scanner.closeReader();
    }

    {
      // ... FROM table1(a, b)
      //
      // C is dropped. But, it is an array, which uses zero-elements
      // to indicate null, so the vector cache can fill in the type.

      TupleMetadata table3Schema = new SchemaBuilder()
          .add("A", MinorType.BIGINT)
          .addNullable("B", MinorType.VARCHAR)
          .buildSchema();
      ReaderSchemaOrchestrator reader = scanner.startReader();
      reader.makeTableLoader(table3Schema);
      VectorContainer output = scanner.output();
      tracker.trackSchema(output);
      assertEquals(schemaVersion, tracker.schemaVersion());
      assertTrue(resultSchema.isEquivalent(output.getSchema()));
      scanner.closeReader();
    }

    {
      // ... FROM table1(b, c)
      //
      // This version carries over a non-nullable BIGINT, but that
      // can't become a null column, so nullable BIGINT is substituted,
      // result in a schema change.

      TupleMetadata table2Schema = new SchemaBuilder()
          .addNullable("B", MinorType.VARCHAR)
          .addArray("C", MinorType.INT)
          .buildSchema();
      ReaderSchemaOrchestrator reader = scanner.startReader();
      reader.makeTableLoader(table2Schema);
      VectorContainer output = scanner.output();
      tracker.trackSchema(output);
      assertEquals(MinorType.BIGINT, output.getSchema().getColumn(0).getType().getMinorType());
      assertEquals(DataMode.OPTIONAL, output.getSchema().getColumn(0).getType().getMode());
      assertTrue(schemaVersion < tracker.schemaVersion());
      scanner.closeReader();
    }

    scanner.close();
  }

  /**
   * Test the ability of the scan projector to "smooth" out schema changes
   * by reusing the type from a previous reader, if known. That is,
   * given three readers:<br>
   * (a, b)<br>
   * (b)<br>
   * (a, b)<br>
   * Then the type of column a should be preserved for the second reader that
   * does not include a. This works if a is nullable. If so, a's type will
   * be used for the empty column, rather than the usual nullable int.
   * <p>
   * Detailed testing of type matching for "missing" columns is done
   * in {@link #testNullColumnLoader()}.
   * <p>
   * As a side effect, makes sure that two identical tables (in this case,
   * separated by a different table) results in no schema change.
   */

  @Test
  public void testTypeSmoothing() {
    ScanSchemaOrchestrator projector = new ScanSchemaOrchestrator(fixture.allocator());

    // SELECT a, b ...

    projector.build(ScanTestUtils.projectList("a", "b"));

    // file schema (a, b)

    TupleMetadata twoColSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .addNullable("b", MinorType.VARCHAR, 10)
        .buildSchema();

    SchemaTracker tracker = new SchemaTracker();
    int schemaVersion;
    {
      // ... FROM table 1

      ReaderSchemaOrchestrator reader = projector.startReader();
      ResultSetLoader loader = reader.makeTableLoader(twoColSchema);

      // Projection of (a, b) to (a, b)

      reader.startBatch();
      loader.writer()
          .addRow(10, "fred")
          .addRow(20, "wilma");
      reader.endBatch();

      tracker.trackSchema(projector.output());
      schemaVersion = tracker.schemaVersion();

      SingleRowSet expected = fixture.rowSetBuilder(twoColSchema)
          .addRow(10, "fred")
          .addRow(20, "wilma")
          .build();
      new RowSetComparison(expected)
        .verifyAndClearAll(fixture.wrap(projector.output()));
    }
    {
      // ... FROM table 2

      ReaderSchemaOrchestrator reader = projector.startReader();

      // File schema (a)

      TupleMetadata oneColSchema = new SchemaBuilder()
          .add("a", MinorType.INT)
          .buildSchema();

      // Projection of (a) to (a, b), reusing b from above.

      ResultSetLoader loader = reader.makeTableLoader(oneColSchema);

      reader.startBatch();
      loader.writer()
          .addRow(30)
          .addRow(40);
      reader.endBatch();

      tracker.trackSchema(projector.output());
      assertEquals(schemaVersion, tracker.schemaVersion());

      SingleRowSet expected = fixture.rowSetBuilder(twoColSchema)
          .addRow(30, null)
          .addRow(40, null)
          .build();
      new RowSetComparison(expected)
        .verifyAndClearAll(fixture.wrap(projector.output()));
    }
    {
      // ... FROM table 3

      ReaderSchemaOrchestrator reader = projector.startReader();

      // Projection of (a, b), to (a, b), reusing b yet again

      ResultSetLoader loader = reader.makeTableLoader(twoColSchema);

      reader.startBatch();
      loader.writer()
          .addRow(50, "dino")
          .addRow(60, "barney");
      reader.endBatch();

      tracker.trackSchema(projector.output());
      assertEquals(schemaVersion, tracker.schemaVersion());

      SingleRowSet expected = fixture.rowSetBuilder(twoColSchema)
          .addRow(50, "dino")
          .addRow(60, "barney")
          .build();
      new RowSetComparison(expected)
        .verifyAndClearAll(fixture.wrap(projector.output()));
    }

    projector.close();
  }

  @Test
  public void testModeSmoothing() {
    ScanSchemaOrchestrator scanner = new ScanSchemaOrchestrator(fixture.allocator());
    scanner.enableSchemaSmoothing(true);
    scanner.build(ScanTestUtils.projectList("a"));

    // Most general schema: nullable, with precision.

    TupleMetadata schema1 = new SchemaBuilder()
        .addNullable("a", MinorType.VARCHAR, 10)
        .buildSchema();

    SchemaTracker tracker = new SchemaTracker();
    int schemaVersion;
    {
      // Table 1: most permissive type

      ReaderSchemaOrchestrator reader = scanner.startReader();
      ResultSetLoader loader = reader.makeTableLoader(schema1);

      tracker.trackSchema(scanner.output());
      schemaVersion = tracker.schemaVersion();

      // Create a batch

      reader.startBatch();
      loader.writer()
        .addRow("fred")
        .addRow("wilma");
      reader.endBatch();

      // Verify

      SingleRowSet expected = fixture.rowSetBuilder(schema1)
        .addRow("fred")
        .addRow("wilma")
        .build();

      new RowSetComparison(expected)
          .verifyAndClearAll(fixture.wrap(scanner.output()));
      scanner.closeReader();
    }
    {
      // Table 2: required, use nullable

      // Required version.

      TupleMetadata schema2 = new SchemaBuilder()
          .add("a", MinorType.VARCHAR, 10)
          .buildSchema();

      ReaderSchemaOrchestrator reader = scanner.startReader();
      ResultSetLoader loader = reader.makeTableLoader(schema2);

      tracker.trackSchema(scanner.output());
      assertEquals(schemaVersion, tracker.schemaVersion());

      // Create a batch

      reader.startBatch();
      loader.writer()
        .addRow("barney")
        .addRow("betty");
      reader.endBatch();

      // Verify, using persistent schema

      SingleRowSet expected = fixture.rowSetBuilder(schema1)
        .addRow("barney")
        .addRow("betty")
        .build();

      new RowSetComparison(expected)
          .verifyAndClearAll(fixture.wrap(scanner.output()));
      scanner.closeReader();
    }
    {
      // Table 3: narrower precision, use wider

      // Required version with narrower precision.

      TupleMetadata schema3 = new SchemaBuilder()
          .add("a", MinorType.VARCHAR, 5)
          .buildSchema();

      ReaderSchemaOrchestrator reader = scanner.startReader();
      ResultSetLoader loader = reader.makeTableLoader(schema3);

      tracker.trackSchema(scanner.output());
      assertEquals(schemaVersion, tracker.schemaVersion());

      // Create a batch

      reader.startBatch();
      loader.writer()
        .addRow("bam-bam")
        .addRow("pebbles");
      reader.endBatch();

      // Verify, using persistent schema

      SingleRowSet expected = fixture.rowSetBuilder(schema1)
        .addRow("bam-bam")
        .addRow("pebbles")
        .build();

      new RowSetComparison(expected)
          .verifyAndClearAll(fixture.wrap(scanner.output()));
      scanner.closeReader();
    }

    scanner.close();
  }

  /**
   * Verify that different table column orders are projected into the
   * SELECT order, preserving vectors, so no schema change for column
   * reordering.
   */

  @Test
  public void testColumnReordering() {

    ScanSchemaOrchestrator scanner = new ScanSchemaOrchestrator(fixture.allocator());
    scanner.enableSchemaSmoothing(true);
    scanner.build(ScanTestUtils.projectList("a", "b", "c"));

    TupleMetadata schema1 = new SchemaBuilder()
        .add("a", MinorType.INT)
        .addNullable("b", MinorType.VARCHAR, 10)
        .add("c", MinorType.BIGINT)
        .buildSchema();
    TupleMetadata schema2 = new SchemaBuilder()
        .add("c", MinorType.BIGINT)
        .add("a", MinorType.INT)
        .addNullable("b", MinorType.VARCHAR, 10)
        .buildSchema();
    TupleMetadata schema3 = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("c", MinorType.BIGINT)
        .addNullable("b", MinorType.VARCHAR, 10)
        .buildSchema();

    SchemaTracker tracker = new SchemaTracker();
    int schemaVersion;
    {
      // ... FROM table 1

      ReaderSchemaOrchestrator reader = scanner.startReader();

      // Projection of (a, b, c) to (a, b, c)

      ResultSetLoader loader = reader.makeTableLoader(schema1);

      reader.startBatch();
      loader.writer()
          .addRow(10, "fred", 110L)
          .addRow(20, "wilma", 110L);
      reader.endBatch();

      tracker.trackSchema(scanner.output());
      schemaVersion = tracker.schemaVersion();

      SingleRowSet expected = fixture.rowSetBuilder(schema1)
          .addRow(10, "fred", 110L)
          .addRow(20, "wilma", 110L)
          .build();
      new RowSetComparison(expected)
        .verifyAndClearAll(fixture.wrap(scanner.output()));

      scanner.closeReader();
    }
    {
      // ... FROM table 2

      ReaderSchemaOrchestrator reader = scanner.startReader();

      // Projection of (c, a, b) to (a, b, c)

      ResultSetLoader loader = reader.makeTableLoader(schema2);

      reader.startBatch();
      loader.writer()
          .addRow(330L, 30, "bambam")
          .addRow(440L, 40, "betty");
      reader.endBatch();

      tracker.trackSchema(scanner.output());
      assertEquals(schemaVersion, tracker.schemaVersion());

      SingleRowSet expected = fixture.rowSetBuilder(schema1)
          .addRow(30, "bambam", 330L)
          .addRow(40, "betty", 440L)
          .build();
      new RowSetComparison(expected)
        .verifyAndClearAll(fixture.wrap(scanner.output()));
    }
    {
      // ... FROM table 3

      ReaderSchemaOrchestrator reader = scanner.startReader();

      // Projection of (a, c, b) to (a, b, c)

      ResultSetLoader loader = reader.makeTableLoader(schema3);

      reader.startBatch();
      loader.writer()
          .addRow(50, 550L, "dino")
          .addRow(60, 660L, "barney");
      reader.endBatch();

      tracker.trackSchema(scanner.output());
      assertEquals(schemaVersion, tracker.schemaVersion());

      SingleRowSet expected = fixture.rowSetBuilder(schema1)
          .addRow(50, "dino", 550L)
          .addRow(60, "barney", 660L)
          .build();
      new RowSetComparison(expected)
        .verifyAndClearAll(fixture.wrap(scanner.output()));
    }

    scanner.close();
  }

  // TODO: Start with early schema, but add columns

}