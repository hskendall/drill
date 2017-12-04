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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.apache.drill.common.types.TypeProtos.MinorType;
import org.apache.drill.exec.physical.impl.protocol.SchemaTracker;
import org.apache.drill.exec.physical.impl.scan.file.FileMetadataManager;
import org.apache.drill.exec.physical.impl.scan.file.FileMetadataManager.FileMetadataColumn;
import org.apache.drill.exec.physical.impl.scan.project.ScanLevelProjection;
import org.apache.drill.exec.physical.impl.scan.project.ScanSchemaOrchestrator;
import org.apache.drill.exec.physical.impl.scan.project.ScanSchemaOrchestrator.ReaderSchemaOrchestrator;
import org.apache.drill.exec.physical.impl.scan.project.SchemaLevelProjection;
import org.apache.drill.exec.physical.impl.scan.project.SchemaLevelProjection.ExplicitSchemaProjection;
import org.apache.drill.exec.physical.impl.scan.project.SchemaLevelProjection.NullProjectedColumn;
import org.apache.drill.exec.physical.impl.scan.project.SchemaLevelProjection.ResolvedColumn;
import org.apache.drill.exec.physical.impl.scan.project.SchemaLevelProjection.ResolvedTableColumn;
import org.apache.drill.exec.physical.impl.scan.project.SchemaLevelProjection.WildcardSchemaProjection;
import org.apache.drill.exec.physical.impl.scan.project.SchemaSmoother;
import org.apache.drill.exec.physical.impl.scan.project.SchemaSmoother.IncompatibleSchemaException;
import org.apache.drill.exec.physical.impl.scan.project.SchemaSmoother.SmoothingProjection;
import org.apache.drill.exec.physical.rowSet.ResultSetLoader;
import org.apache.drill.exec.record.metadata.TupleMetadata;
import org.apache.drill.test.SubOperatorTest;
import org.apache.drill.test.rowSet.RowSet.SingleRowSet;
import org.apache.hadoop.fs.Path;
import org.apache.drill.test.rowSet.RowSetComparison;
import org.apache.drill.test.rowSet.SchemaBuilder;
import org.junit.Test;

import com.google.common.collect.Lists;

/**
 * Tests schema smoothing at the schema projection level.
 * This level handles reusing prior types when filling null
 * values. But, because no actual vectors are involved, it
 * does not handle the schema chosen for a table ahead of
 * time, only the schema as it is merged with prior schema to
 * detect missing columns.
 * <p>
 * Focuses on the <tt>SmoothingProjection</tt> class itself.
 */

public class TestSchemaSmoothing extends SubOperatorTest {

  /**
   * Sanity test for the simple, discrete case. The purpose of
   * discrete is just to run the basic lifecycle in a way that
   * is compatible with the schema-persistence version.
   */

  @Test
  public void testDiscrete() {

    // Set up the file metadata manager

    Path filePathA = new Path("hdfs:///w/x/y/a.csv");
    Path filePathB = new Path("hdfs:///w/x/y/b.csv");
    FileMetadataManager metadataManager = new FileMetadataManager(
        fixture.options(), true,
        new Path("hdfs:///w"),
        Lists.newArrayList(filePathA, filePathB));

    // Set up the scan level projection

    ScanLevelProjection scanProj = new ScanLevelProjection(
        ScanTestUtils.projectList(ScanTestUtils.FILE_NAME_COL, "a", "b"),
        ScanTestUtils.parsers(metadataManager.projectionParser()));

    // Define the schema smoother

    ScanTestUtils.DummySource dummySource = new ScanTestUtils.DummySource();

    {
      // Define a file a.csv

      metadataManager.startFile(filePathA);

      // Build the output schema from the (a, b) table schema

      TupleMetadata twoColSchema = new SchemaBuilder()
          .add("a", MinorType.INT)
          .addNullable("b", MinorType.VARCHAR, 10)
          .buildSchema();
      SchemaLevelProjection schemaProj = new ExplicitSchemaProjection(
          scanProj, twoColSchema, dummySource, dummySource,
          ScanTestUtils.resolvers(metadataManager));

      // Verify the full output schema

      TupleMetadata expectedSchema = new SchemaBuilder()
          .add("filename", MinorType.VARCHAR)
          .add("a", MinorType.INT)
          .addNullable("b", MinorType.VARCHAR, 10)
          .buildSchema();

      // Verify

      List<ResolvedColumn> tableSchema = schemaProj.columns();
      assertTrue(ScanTestUtils.schema(tableSchema).isEquivalent(expectedSchema));
      assertEquals(ScanTestUtils.FILE_NAME_COL, tableSchema.get(0).name());
      assertEquals("a.csv", ((FileMetadataColumn) tableSchema.get(0)).value());
      assertEquals(ResolvedTableColumn.ID, tableSchema.get(1).nodeType());
    }
    {
      // Define a file b.csv

      metadataManager.startFile(filePathB);

      // Build the output schema from the (a) table schema

      TupleMetadata oneColSchema = new SchemaBuilder()
          .add("a", MinorType.INT)
          .buildSchema();
      SchemaLevelProjection schemaProj = new ExplicitSchemaProjection(
          scanProj, oneColSchema, dummySource, dummySource,
          ScanTestUtils.resolvers(metadataManager));

      // Verify the full output schema
      // Since this mode is "discrete", we don't remember the type
      // of the missing column. (Instead, it is filled in at the
      // vector level as part of vector persistence.)

      TupleMetadata expectedSchema = new SchemaBuilder()
          .add("filename", MinorType.VARCHAR)
          .add("a", MinorType.INT)
          .addNullable("b", MinorType.NULL)
          .buildSchema();

      // Verify

      List<ResolvedColumn> tableSchema = schemaProj.columns();
      assertTrue(ScanTestUtils.schema(tableSchema).isEquivalent(expectedSchema));
      assertEquals(3, tableSchema.size());
      assertEquals(ScanTestUtils.FILE_NAME_COL, tableSchema.get(0).name());
      assertEquals("b.csv", ((FileMetadataColumn) tableSchema.get(0)).value());
      assertEquals(ResolvedTableColumn.ID, tableSchema.get(1).nodeType());
      assertEquals(NullProjectedColumn.ID, tableSchema.get(2).nodeType());
    }
  }

  /**
   * Low-level test of the smoothing projection, including the exceptions
   * it throws when things are not going its way.
   */

  @Test
  public void testSmoothingProjection() {
    ScanLevelProjection scanProj = new ScanLevelProjection(
        ScanTestUtils.projectAll(),
        ScanTestUtils.parsers());

    ScanTestUtils.DummySource dummySource = new ScanTestUtils.DummySource();

    // Table 1: (a: nullable bigint, b)

    TupleMetadata schema1 = new SchemaBuilder()
        .addNullable("a", MinorType.BIGINT)
        .addNullable("b", MinorType.VARCHAR)
        .add("c", MinorType.FLOAT8)
        .buildSchema();
    List<ResolvedColumn> priorSchema = new ArrayList<>();
    {
      WildcardSchemaProjection schemaProj = new WildcardSchemaProjection(
          scanProj, schema1, dummySource,
          ScanTestUtils.resolvers());
      priorSchema = schemaProj.columns();
    }

    // Table 2: (a: nullable bigint, c), column omitted, original schema preserved

    TupleMetadata schema2 = new SchemaBuilder()
        .addNullable("a", MinorType.BIGINT)
        .add("c", MinorType.FLOAT8)
        .buildSchema();
    try {
      SmoothingProjection schemaProj = new SmoothingProjection(
          scanProj, schema2, dummySource, dummySource,
          ScanTestUtils.resolvers(), priorSchema);
      assertTrue(schema1.isEquivalent(ScanTestUtils.schema(schemaProj.columns())));
    } catch (IncompatibleSchemaException e) {
      fail();
    }

    // Table 3: (a, c, d), column added, must replan schema

    TupleMetadata schema3 = new SchemaBuilder()
        .addNullable("a", MinorType.BIGINT)
        .addNullable("b", MinorType.VARCHAR)
        .add("c", MinorType.FLOAT8)
        .add("d", MinorType.INT)
        .buildSchema();
    try {
      new SmoothingProjection(
          scanProj, schema3, dummySource, dummySource,
          ScanTestUtils.resolvers(), priorSchema);
      fail();
    } catch (IncompatibleSchemaException e) {
      // Expected
    }

    // Table 4: (a: double), change type must replan schema

    TupleMetadata schema4 = new SchemaBuilder()
        .addNullable("a", MinorType.FLOAT8)
        .addNullable("b", MinorType.VARCHAR)
        .add("c", MinorType.FLOAT8)
        .buildSchema();
    try {
      new SmoothingProjection(
          scanProj, schema4, dummySource, dummySource,
          ScanTestUtils.resolvers(), priorSchema);
      fail();
    } catch (IncompatibleSchemaException e) {
      // Expected
    }

//    // Table 5: (a: not-nullable bigint): convert to nullable for consistency
//
//    TupleMetadata schema5 = new SchemaBuilder()
//        .addNullable("a", MinorType.BIGINT)
//        .add("c", MinorType.FLOAT8)
//        .buildSchema();
//    try {
//      SmoothingProjection schemaProj = new SmoothingProjection(
//          scanProj, schema5, dummySource, dummySource,
//          new ArrayList<>(), priorSchema);
//      assertTrue(schema1.isEquivalent(ScanTestUtils.schema(schemaProj.columns())));
//    } catch (IncompatibleSchemaException e) {
//      fail();
//    }

    // Table 6: Drop a non-nullable column, must replan

    TupleMetadata schema6 = new SchemaBuilder()
        .addNullable("a", MinorType.BIGINT)
        .addNullable("b", MinorType.VARCHAR)
        .buildSchema();
    try {
      new SmoothingProjection(
          scanProj, schema6, dummySource, dummySource,
          ScanTestUtils.resolvers(), priorSchema);
      fail();
    } catch (IncompatibleSchemaException e) {
      // Expected
    }
  }

  /**
   * Case in which the table schema is a superset of the prior
   * schema. Discard prior schema. Turn off auto expansion of
   * metadata for a simpler test.
   */

  @Test
  public void testSmaller() {
    ScanLevelProjection scanProj = new ScanLevelProjection(
        ScanTestUtils.projectAll(),
        ScanTestUtils.parsers());

    ScanTestUtils.DummySource dummySource = new ScanTestUtils.DummySource();
    SchemaSmoother smoother = new SchemaSmoother(scanProj, dummySource,
        ScanTestUtils.resolvers());

    TupleMetadata priorSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .buildSchema();
    TupleMetadata tableSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .buildSchema();

    {
      SchemaLevelProjection schemaProj = smoother.resolve(priorSchema, dummySource);
      assertEquals(1, smoother.schemaVersion());
      assertTrue(ScanTestUtils.schema(schemaProj.columns()).isEquivalent(priorSchema));
    }
    {
      SchemaLevelProjection schemaProj = smoother.resolve(tableSchema, dummySource);
      assertEquals(2, smoother.schemaVersion());
      assertTrue(ScanTestUtils.schema(schemaProj.columns()).isEquivalent(tableSchema));
    }
  }

  /**
   * Case in which the table schema and prior are disjoint
   * sets. Discard the prior schema.
   */

  @Test
  public void testDisjoint() {
    ScanLevelProjection scanProj = new ScanLevelProjection(
        ScanTestUtils.projectAll(),
        ScanTestUtils.parsers());

    ScanTestUtils.DummySource dummySource = new ScanTestUtils.DummySource();
    SchemaSmoother smoother = new SchemaSmoother(scanProj, dummySource,
        ScanTestUtils.resolvers());

    TupleMetadata priorSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .buildSchema();
    TupleMetadata tableSchema = new SchemaBuilder()
        .add("b", MinorType.VARCHAR)
        .buildSchema();

    smoother.resolve(priorSchema, dummySource);
    SchemaLevelProjection schemaProj = smoother.resolve(tableSchema, dummySource);
    assertEquals(2, smoother.schemaVersion());
    assertTrue(ScanTestUtils.schema(schemaProj.columns()).isEquivalent(tableSchema));
  }

  /**
   * Column names match, but types differ. Discard the prior schema.
   */

  @Test
  public void testDifferentTypes() {
    ScanLevelProjection scanProj = new ScanLevelProjection(
        ScanTestUtils.projectAll(),
        ScanTestUtils.parsers());

    ScanTestUtils.DummySource dummySource = new ScanTestUtils.DummySource();
    SchemaSmoother smoother = new SchemaSmoother(scanProj, dummySource,
        ScanTestUtils.resolvers());

    TupleMetadata priorSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .buildSchema();
    TupleMetadata tableSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .addNullable("b", MinorType.VARCHAR)
        .buildSchema();

    smoother.resolve(priorSchema, dummySource);
    SchemaLevelProjection schemaProj = smoother.resolve(tableSchema, dummySource);
    assertEquals(2, smoother.schemaVersion());
    assertTrue(ScanTestUtils.schema(schemaProj.columns()).isEquivalent(tableSchema));
  }

  /**
   * The prior and table schemas are identical. Preserve the prior
   * schema (though, the output is no different than if we discarded
   * the prior schema...)
   */

  @Test
  public void testSameSchemas() {
    ScanLevelProjection scanProj = new ScanLevelProjection(
        ScanTestUtils.projectAll(),
        ScanTestUtils.parsers());

    ScanTestUtils.DummySource dummySource = new ScanTestUtils.DummySource();
    SchemaSmoother smoother = new SchemaSmoother(scanProj, dummySource,
        ScanTestUtils.resolvers());

    TupleMetadata priorSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .buildSchema();
    TupleMetadata tableSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .buildSchema();

    smoother.resolve(priorSchema, dummySource);
    SchemaLevelProjection schemaProj = smoother.resolve(tableSchema, dummySource);
    assertEquals(1, smoother.schemaVersion());
    TupleMetadata actualSchema = ScanTestUtils.schema(schemaProj.columns());
    assertTrue(actualSchema.isEquivalent(tableSchema));
    assertTrue(actualSchema.isEquivalent(priorSchema));
  }

  /**
   * The prior and table schemas are identical, but the cases of names differ.
   * Preserve the case of the first schema.
   */

  @Test
  public void testDifferentCase() {
    ScanLevelProjection scanProj = new ScanLevelProjection(
        ScanTestUtils.projectAll(),
        ScanTestUtils.parsers());

    ScanTestUtils.DummySource dummySource = new ScanTestUtils.DummySource();
    SchemaSmoother smoother = new SchemaSmoother(scanProj, dummySource,
        ScanTestUtils.resolvers());

    TupleMetadata priorSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .buildSchema();
    TupleMetadata tableSchema = new SchemaBuilder()
        .add("A", MinorType.INT)
        .add("B", MinorType.VARCHAR)
        .buildSchema();

    smoother.resolve(priorSchema, dummySource);
    SchemaLevelProjection schemaProj = smoother.resolve(tableSchema, dummySource);
    assertEquals(1, smoother.schemaVersion());
    assertTrue(ScanTestUtils.schema(schemaProj.columns()).isEquivalent(priorSchema));
  }

  /**
   * Can't preserve the prior schema if it had required columns
   * where the new schema has no columns.
   */

  @Test
  public void testRequired() {
    ScanLevelProjection scanProj = new ScanLevelProjection(
        ScanTestUtils.projectAll(),
        ScanTestUtils.parsers());

    ScanTestUtils.DummySource dummySource = new ScanTestUtils.DummySource();
    SchemaSmoother smoother = new SchemaSmoother(scanProj, dummySource,
        ScanTestUtils.resolvers());

    TupleMetadata priorSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .addNullable("b", MinorType.VARCHAR)
        .buildSchema();
    TupleMetadata tableSchema = new SchemaBuilder()
        .addNullable("b", MinorType.VARCHAR)
        .buildSchema();

    smoother.resolve(priorSchema, dummySource);
    SchemaLevelProjection schemaProj = smoother.resolve(tableSchema, dummySource);
    assertEquals(2, smoother.schemaVersion());
    assertTrue(ScanTestUtils.schema(schemaProj.columns()).isEquivalent(tableSchema));
  }

  /**
   * Preserve the prior schema if table is a subset and missing columns
   * are nullable or repeated.
   */

  @Test
  public void testMissingNullableColumns() {
    ScanLevelProjection scanProj = new ScanLevelProjection(
        ScanTestUtils.projectAll(),
        ScanTestUtils.parsers());

    ScanTestUtils.DummySource dummySource = new ScanTestUtils.DummySource();
    SchemaSmoother smoother = new SchemaSmoother(scanProj, dummySource,
        ScanTestUtils.resolvers());

    TupleMetadata priorSchema = new SchemaBuilder()
        .addNullable("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .addArray("c", MinorType.BIGINT)
        .buildSchema();
    TupleMetadata tableSchema = new SchemaBuilder()
        .add("b", MinorType.VARCHAR)
        .buildSchema();

    smoother.resolve(priorSchema, dummySource);
    SchemaLevelProjection schemaProj = smoother.resolve(tableSchema, dummySource);
    assertEquals(1, smoother.schemaVersion());
    assertTrue(ScanTestUtils.schema(schemaProj.columns()).isEquivalent(priorSchema));
  }

  /**
   * Preserve the prior schema if table is a subset. Map the table
   * columns to the output using the prior schema ordering.
   */

  @Test
  public void testReordering() {
    ScanLevelProjection scanProj = new ScanLevelProjection(
        ScanTestUtils.projectAll(),
        ScanTestUtils.parsers());

    ScanTestUtils.DummySource dummySource = new ScanTestUtils.DummySource();
    SchemaSmoother smoother = new SchemaSmoother(scanProj, dummySource,
        ScanTestUtils.resolvers());

    TupleMetadata priorSchema = new SchemaBuilder()
        .addNullable("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .addArray("c", MinorType.BIGINT)
        .buildSchema();
    TupleMetadata tableSchema = new SchemaBuilder()
        .add("b", MinorType.VARCHAR)
        .addNullable("a", MinorType.INT)
        .buildSchema();

    smoother.resolve(priorSchema, dummySource);
    SchemaLevelProjection schemaProj = smoother.resolve(tableSchema, dummySource);
    assertEquals(1, smoother.schemaVersion());
    assertTrue(ScanTestUtils.schema(schemaProj.columns()).isEquivalent(priorSchema));
  }

  /**
   * If using the legacy wildcard expansion, reuse schema if partition paths
   * are the same length.
   */

  @Test
  public void testSamePartitionLength() {

    // Set up the file metadata manager

    Path filePathA = new Path("hdfs:///w/x/y/a.csv");
    Path filePathB = new Path("hdfs:///w/x/y/b.csv");
    FileMetadataManager metadataManager = new FileMetadataManager(
        fixture.options(), true,
        new Path("hdfs:///w"),
        Lists.newArrayList(filePathA, filePathB));

    // Set up the scan level projection

    ScanLevelProjection scanProj = new ScanLevelProjection(
        ScanTestUtils.projectAll(),
        ScanTestUtils.parsers(metadataManager.projectionParser()));

    // Define the schema smoother

    ScanTestUtils.DummySource dummySource = new ScanTestUtils.DummySource();
    SchemaSmoother smoother = new SchemaSmoother(scanProj, dummySource,
        ScanTestUtils.resolvers(metadataManager));

    TupleMetadata tableSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .buildSchema();

    TupleMetadata expectedSchema = ScanTestUtils.expandMetadata(tableSchema, metadataManager, 2);
    {
      metadataManager.startFile(filePathA);
      SchemaLevelProjection schemaProj = smoother.resolve(tableSchema, dummySource);
      assertEquals(1, smoother.schemaVersion());
      assertTrue(ScanTestUtils.schema(schemaProj.columns()).isEquivalent(expectedSchema));
    }
    {
      metadataManager.startFile(filePathB);
      SchemaLevelProjection schemaProj = smoother.resolve(tableSchema, dummySource);
      assertEquals(1, smoother.schemaVersion());
      assertTrue(ScanTestUtils.schema(schemaProj.columns()).isEquivalent(expectedSchema));
    }
  }

  /**
   * If using the legacy wildcard expansion, reuse schema if the new partition path
   * is shorter than the previous. (Unneeded partitions will be set to null by the
   * scan projector.)
   */

  @Test
  public void testShorterPartitionLength() {

    // Set up the file metadata manager

    Path filePathA = new Path("hdfs:///w/x/y/a.csv");
    Path filePathB = new Path("hdfs:///w/x/b.csv");
    FileMetadataManager metadataManager = new FileMetadataManager(
        fixture.options(), true,
        new Path("hdfs:///w"),
        Lists.newArrayList(filePathA, filePathB));

    // Set up the scan level projection

    ScanLevelProjection scanProj = new ScanLevelProjection(
        ScanTestUtils.projectAll(),
        ScanTestUtils.parsers(metadataManager.projectionParser()));

    // Define the schema smoother

    ScanTestUtils.DummySource dummySource = new ScanTestUtils.DummySource();
    SchemaSmoother smoother = new SchemaSmoother(scanProj, dummySource,
        ScanTestUtils.resolvers(metadataManager));

    TupleMetadata tableSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .buildSchema();

    TupleMetadata expectedSchema = ScanTestUtils.expandMetadata(tableSchema, metadataManager, 2);
    {
      metadataManager.startFile(filePathA);
      SchemaLevelProjection schemaProj = smoother.resolve(tableSchema, dummySource);
      assertEquals(1, smoother.schemaVersion());
      assertTrue(ScanTestUtils.schema(schemaProj.columns()).isEquivalent(expectedSchema));
    }
    {
      metadataManager.startFile(filePathB);
      SchemaLevelProjection schemaProj = smoother.resolve(tableSchema, dummySource);
      assertEquals(1, smoother.schemaVersion());
      assertTrue(ScanTestUtils.schema(schemaProj.columns()).isEquivalent(expectedSchema));
    }
  }

  /**
   * If using the legacy wildcard expansion, we are able to use the same
   * schema even if the new partition path is longer than the previous.
   * Because all file names are provided up front.
   */

  @Test
  public void testLongerPartitionLength() {

    // Set up the file metadata manager

    Path filePathA = new Path("hdfs:///w/x/a.csv");
    Path filePathB = new Path("hdfs:///w/x/y/b.csv");
    FileMetadataManager metadataManager = new FileMetadataManager(
        fixture.options(), true,
        new Path("hdfs:///w"),
        Lists.newArrayList(filePathA, filePathB));

    // Set up the scan level projection

    ScanLevelProjection scanProj = new ScanLevelProjection(
        ScanTestUtils.projectAll(),
        ScanTestUtils.parsers(metadataManager.projectionParser()));

    // Define the schema smoother

    ScanTestUtils.DummySource dummySource = new ScanTestUtils.DummySource();
    SchemaSmoother smoother = new SchemaSmoother(scanProj, dummySource,
        ScanTestUtils.resolvers(metadataManager));

    TupleMetadata tableSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .buildSchema();

    TupleMetadata expectedSchema = ScanTestUtils.expandMetadata(tableSchema, metadataManager, 2);
    {
      metadataManager.startFile(filePathA);
      SchemaLevelProjection schemaProj = smoother.resolve(tableSchema, dummySource);
      assertEquals(1, smoother.schemaVersion());
      assertTrue(ScanTestUtils.schema(schemaProj.columns()).isEquivalent(expectedSchema));
    }
    {
      metadataManager.startFile(filePathB);
      SchemaLevelProjection schemaProj = smoother.resolve(tableSchema, dummySource);
      assertEquals(1, smoother.schemaVersion());
      assertTrue(ScanTestUtils.schema(schemaProj.columns()).isEquivalent(expectedSchema));
    }
  }

  /**
   * Integrated test across multiple schemas at the batch level.
   */

  @Test
  public void testSmoothableSchemaBatches() {
    ScanLevelProjection scanProj = new ScanLevelProjection(
        ScanTestUtils.projectAll(),
        ScanTestUtils.parsers());

    ScanTestUtils.DummySource dummySource = new ScanTestUtils.DummySource();
    SchemaSmoother smoother = new SchemaSmoother(scanProj, dummySource,
        ScanTestUtils.resolvers());

    // Table 1: (a: bigint, b)

    TupleMetadata schema1 = new SchemaBuilder()
        .addNullable("a", MinorType.BIGINT)
        .addNullable("b", MinorType.VARCHAR)
        .add("c", MinorType.FLOAT8)
        .buildSchema();
    {
      SchemaLevelProjection schemaProj = smoother.resolve(schema1, dummySource);

      // Just use the original schema.

      assertTrue(schema1.isEquivalent(ScanTestUtils.schema(schemaProj.columns())));
    }

    // Table 2: (a: nullable bigint, c), column ommitted, original schema preserved

    TupleMetadata schema2 = new SchemaBuilder()
        .addNullable("a", MinorType.BIGINT)
        .add("c", MinorType.FLOAT8)
        .buildSchema();
    {
      SchemaLevelProjection schemaProj = smoother.resolve(schema2, dummySource);
      assertTrue(schema1.isEquivalent(ScanTestUtils.schema(schemaProj.columns())));
    }

    // Table 3: (a, c, d), column added, must replan schema

    TupleMetadata schema3 = new SchemaBuilder()
        .addNullable("a", MinorType.BIGINT)
        .addNullable("b", MinorType.VARCHAR)
        .add("c", MinorType.FLOAT8)
        .add("d", MinorType.INT)
        .buildSchema();
    {
      SchemaLevelProjection schemaProj = smoother.resolve(schema3, dummySource);
      assertTrue(schema3.isEquivalent(ScanTestUtils.schema(schemaProj.columns())));
    }

    // Table 4: Drop a non-nullable column, must replan

    TupleMetadata schema4 = new SchemaBuilder()
        .addNullable("a", MinorType.BIGINT)
        .addNullable("b", MinorType.VARCHAR)
        .buildSchema();
    {
      SchemaLevelProjection schemaProj = smoother.resolve(schema4, dummySource);
      assertTrue(schema4.isEquivalent(ScanTestUtils.schema(schemaProj.columns())));
    }

    // Table 5: (a: double), change type must replan schema

    TupleMetadata schema5 = new SchemaBuilder()
        .addNullable("a", MinorType.FLOAT8)
        .addNullable("b", MinorType.VARCHAR)
        .buildSchema();
    {
      SchemaLevelProjection schemaProj = smoother.resolve(schema5, dummySource);
      assertTrue(schema5.isEquivalent(ScanTestUtils.schema(schemaProj.columns())));
    }

//    // Table 6: (a: not-nullable bigint): convert to nullable for consistency
//
//    TupleMetadata schema6 = new SchemaBuilder()
//        .add("a", MinorType.FLOAT8)
//        .add("b", MinorType.VARCHAR)
//        .buildSchema();
//    {
//      SchemaLevelProjection schemaProj = smoother.resolve(schema3, dummySource);
//      assertTrue(schema5.isEquivalent(ScanTestUtils.schema(schemaProj.columns())));
//    }
  }

  /**
   * A SELECT * query uses the schema of the table as the output schema.
   * This is trivial when the scanner has one table. But, if two or more
   * tables occur, then things get interesting. The first table sets the
   * schema. The second table then has:
   * <ul>
   * <li>The same schema, trivial case.</li>
   * <li>A subset of the first table. The type of the "missing" column
   * from the first table is used for a null column in the second table.</li>
   * <li>A superset or disjoint set of the first schema. This triggers a hard schema
   * change.</li>
   * </ul>
   * <p>
   * It is an open question whether previous columns should be preserved on
   * a hard reset. For now, the code implements, and this test verifies, that a
   * hard reset clears the "memory" of prior schemas.
   */

  @Test
  public void testWildcardSmoothing() {
    ScanSchemaOrchestrator projector = new ScanSchemaOrchestrator(fixture.allocator());
    projector.enableSchemaSmoothing(true);
    projector.build(ScanTestUtils.projectAll());

    TupleMetadata firstSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .addNullable("b", MinorType.VARCHAR, 10)
        .addNullable("c", MinorType.BIGINT)
        .buildSchema();
    TupleMetadata subsetSchema = new SchemaBuilder()
        .addNullable("b", MinorType.VARCHAR, 10)
        .add("a", MinorType.INT)
        .buildSchema();
    TupleMetadata disjointSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .addNullable("b", MinorType.VARCHAR, 10)
        .add("d", MinorType.VARCHAR)
        .buildSchema();

    SchemaTracker tracker = new SchemaTracker();
    int schemaVersion;
    {
      // First table, establishes the baseline
      // ... FROM table 1

      ReaderSchemaOrchestrator reader = projector.startReader();
      ResultSetLoader loader = reader.makeTableLoader(firstSchema);

      reader.startBatch();
      loader.writer()
          .addRow(10, "fred", 110L)
          .addRow(20, "wilma", 110L);
      reader.endBatch();

      tracker.trackSchema(projector.output());
      schemaVersion = tracker.schemaVersion();

      SingleRowSet expected = fixture.rowSetBuilder(firstSchema)
          .addRow(10, "fred", 110L)
          .addRow(20, "wilma", 110L)
          .build();
      new RowSetComparison(expected)
        .verifyAndClearAll(fixture.wrap(projector.output()));
    }
    {
      // Second table, same schema, the trivial case
      // ... FROM table 2

      ReaderSchemaOrchestrator reader = projector.startReader();
      ResultSetLoader loader = reader.makeTableLoader(firstSchema);

      reader.startBatch();
      loader.writer()
          .addRow(70, "pebbles", 770L)
          .addRow(80, "hoppy", 880L);
      reader.endBatch();

      tracker.trackSchema(projector.output());
      assertEquals(schemaVersion, tracker.schemaVersion());

      SingleRowSet expected = fixture.rowSetBuilder(firstSchema)
          .addRow(70, "pebbles", 770L)
          .addRow(80, "hoppy", 880L)
          .build();
      new RowSetComparison(expected)
        .verifyAndClearAll(fixture.wrap(projector.output()));
    }
    {
      // Third table: subset schema of first two
      // ... FROM table 3

      ReaderSchemaOrchestrator reader = projector.startReader();
      ResultSetLoader loader = reader.makeTableLoader(subsetSchema);

      reader.startBatch();
      loader.writer()
          .addRow("bambam", 30)
          .addRow("betty", 40);
      reader.endBatch();

      tracker.trackSchema(projector.output());
      assertEquals(schemaVersion, tracker.schemaVersion());

      SingleRowSet expected = fixture.rowSetBuilder(firstSchema)
          .addRow(30, "bambam", null)
          .addRow(40, "betty", null)
          .build();
      new RowSetComparison(expected)
        .verifyAndClearAll(fixture.wrap(projector.output()));
    }
    {
      // Fourth table: disjoint schema, cases a schema reset
      // ... FROM table 4

      ReaderSchemaOrchestrator reader = projector.startReader();
      ResultSetLoader loader = reader.makeTableLoader(disjointSchema);

      reader.startBatch();
      loader.writer()
          .addRow(50, "dino", "supporting")
          .addRow(60, "barney", "main");
      reader.endBatch();

      tracker.trackSchema(projector.output());
      assertNotEquals(schemaVersion, tracker.schemaVersion());

      SingleRowSet expected = fixture.rowSetBuilder(disjointSchema)
          .addRow(50, "dino", "supporting")
          .addRow(60, "barney", "main")
          .build();
      new RowSetComparison(expected)
        .verifyAndClearAll(fixture.wrap(projector.output()));
    }

    projector.close();
  }

  // TODO: Test schema smoothing with repeated
  // TODO: Test hard schema change
  // TODO: Typed null column tests (resurrect)
  // TODO: Test maps and arrays of maps
}
