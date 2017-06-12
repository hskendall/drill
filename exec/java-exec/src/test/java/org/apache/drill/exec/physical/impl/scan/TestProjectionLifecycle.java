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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.apache.drill.common.types.TypeProtos.MinorType;
import org.apache.drill.exec.physical.impl.scan.ScanOutputColumn.ColumnType;
import org.apache.drill.exec.physical.impl.scan.ScanOutputColumn.FileMetadata;
import org.apache.drill.exec.physical.impl.scan.ScanOutputColumn.MetadataColumn;
import org.apache.drill.test.SubOperatorTest;
import org.apache.drill.test.rowSet.SchemaBuilder;
import org.apache.hadoop.fs.Path;
import org.junit.Test;

public class TestProjectionLifecycle extends SubOperatorTest {

  /**
   * Sanity test for the simple, discrete case. The purpose of
   * discrete is just to run the basic lifecycle in a way that
   * is compatible with the schema-persistence version.
   */

  @Test
  public void testDiscrete() {
    ScanProjectionDefn.Builder scanProjBuilder = new ScanProjectionDefn.Builder(fixture.options());
    scanProjBuilder.useLegacyWildcardExpansion(true);
    scanProjBuilder.queryCols(TestScanProjectionDefn.projectList("filename", "a", "b"));
    ProjectionLifecycle lifecycle = ProjectionLifecycle.newDiscreteLifecycle(scanProjBuilder);

    {
      // Define a file a.csv

      FileMetadata fileInfo = new FileMetadata(new Path("hdfs:///w/x/y/a.csv"), "hdfs:///w");
      lifecycle.startFile(fileInfo);

      // Verify

      List<ScanOutputColumn> fileSchema = lifecycle.fileProjection().output();
      assertEquals("filename", fileSchema.get(0).name());
      assertEquals("a.csv", ((MetadataColumn) fileSchema.get(0)).value());
      assertEquals(ColumnType.TABLE, fileSchema.get(1).columnType());

      // Build the output schema from the (a, b) table schema

      MaterializedSchema twoColSchema = new SchemaBuilder()
          .add("a", MinorType.INT)
          .addNullable("b", MinorType.VARCHAR, 10)
          .buildSchema();
      lifecycle.startSchema(twoColSchema);
      assertEquals(1, lifecycle.schemaVersion());

      // Verify the full output schema

      MaterializedSchema expectedSchema = new SchemaBuilder()
          .add("filename", MinorType.VARCHAR)
          .add("a", MinorType.INT)
          .addNullable("b", MinorType.VARCHAR, 10)
          .buildSchema();
      assertTrue(lifecycle.outputSchema().isEquivalent(expectedSchema));

      // Verify

      List<ScanOutputColumn> tableSchema = lifecycle.tableProjection().output();
      assertEquals("filename", tableSchema.get(0).name());
      assertEquals("a.csv", ((MetadataColumn) tableSchema.get(0)).value());
      assertEquals(ColumnType.PROJECTED, tableSchema.get(1).columnType());
    }
    {
      // Define a file b.csv

      FileMetadata fileInfo = new FileMetadata(new Path("hdfs:///w/x/y/b.csv"), "hdfs:///w");
      lifecycle.startFile(fileInfo);

      // Verify

      assertNull(lifecycle.tableProjection());
      List<ScanOutputColumn> fileSchema = lifecycle.fileProjection().output();
      assertEquals(3, fileSchema.size());
      assertEquals("filename", fileSchema.get(0).name());
      assertEquals("b.csv", ((MetadataColumn) fileSchema.get(0)).value());
      assertEquals(ColumnType.TABLE, fileSchema.get(1).columnType());
      assertEquals(ColumnType.TABLE, fileSchema.get(2).columnType());

      // Build the output schema from the (a) table schema

      MaterializedSchema oneColSchema = new SchemaBuilder()
          .add("a", MinorType.INT)
          .buildSchema();
      lifecycle.startSchema(oneColSchema);
      assertEquals(2, lifecycle.schemaVersion());

      // Verify the full output schema
      // Since this mode is "discrete", we don't remember the type
      // of the missing column. (Instead, it is filled in at the
      // vector level as part of vector persistance.)

      MaterializedSchema expectedSchema = new SchemaBuilder()
          .add("filename", MinorType.VARCHAR)
          .add("a", MinorType.INT)
          .addNullable("b", MinorType.NULL)
          .buildSchema();
      assertTrue(lifecycle.outputSchema().isEquivalent(expectedSchema));

      // Verify

      List<ScanOutputColumn> tableSchema = lifecycle.tableProjection().output();
      assertEquals(3, tableSchema.size());
      assertEquals("filename", tableSchema.get(0).name());
      assertEquals("b.csv", ((MetadataColumn) tableSchema.get(0)).value());
      assertEquals(ColumnType.PROJECTED, tableSchema.get(1).columnType());
      assertEquals(ColumnType.NULL, tableSchema.get(2).columnType());
    }
  }

  /**
   * Case in which the table schema is a superset of the prior
   * schema. Discard prior schema. Turn off auto expansion of
   * metadata for a simpler test.
   */

  @Test
  public void testSmaller() {
    MaterializedSchema priorSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .buildSchema();
    MaterializedSchema tableSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .buildSchema();

    ScanProjectionDefn.Builder scanProjBuilder = new ScanProjectionDefn.Builder(fixture.options());
    scanProjBuilder.useLegacyWildcardExpansion(false);
    scanProjBuilder.projectAll();
    ProjectionLifecycle lifecycle = ProjectionLifecycle.newContinuousLifecycle(scanProjBuilder);

    {
      FileMetadata fileInfo = new FileMetadata(new Path("hdfs:///w/x/y/a.csv"), "hdfs:///w");
      lifecycle.startFile(fileInfo);
      lifecycle.startSchema(priorSchema);
      assertEquals(1, lifecycle.schemaVersion());
      assertTrue(lifecycle.outputSchema().isEquivalent(priorSchema));
    }
    {
      FileMetadata fileInfo = new FileMetadata(new Path("hdfs:///w/x/y/b.csv"), "hdfs:///w");
      lifecycle.startFile(fileInfo);
      lifecycle.startSchema(tableSchema);
      assertEquals(2, lifecycle.schemaVersion());
      assertTrue(lifecycle.outputSchema().isEquivalent(tableSchema));
    }
  }

  /**
   * Case in which the table schema and prior are disjoint
   * sets. Discard the prior schema.
   */

  @Test
  public void testDisjoint() {
    MaterializedSchema priorSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .buildSchema();
    MaterializedSchema tableSchema = new SchemaBuilder()
        .add("b", MinorType.VARCHAR)
        .buildSchema();

    ScanProjectionDefn.Builder scanProjBuilder = new ScanProjectionDefn.Builder(fixture.options());
    scanProjBuilder.useLegacyWildcardExpansion(false);
    scanProjBuilder.projectAll();
    ProjectionLifecycle lifecycle = ProjectionLifecycle.newContinuousLifecycle(scanProjBuilder);

    {
      FileMetadata fileInfo = new FileMetadata(new Path("hdfs:///w/x/y/a.csv"), "hdfs:///w");
      lifecycle.startFile(fileInfo);
      lifecycle.startSchema(priorSchema);
    }
    {
      FileMetadata fileInfo = new FileMetadata(new Path("hdfs:///w/x/y/b.csv"), "hdfs:///w");
      lifecycle.startFile(fileInfo);
      lifecycle.startSchema(tableSchema);
      assertEquals(2, lifecycle.schemaVersion());
      assertTrue(lifecycle.outputSchema().isEquivalent(tableSchema));
    }
  }

  /**
   * Column names match, but types differ. Discard the prior schema.
   */

  @Test
  public void testDifferentTypes() {
    MaterializedSchema priorSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .buildSchema();
    MaterializedSchema tableSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .addNullable("b", MinorType.VARCHAR)
        .buildSchema();

    ScanProjectionDefn.Builder scanProjBuilder = new ScanProjectionDefn.Builder(fixture.options());
    scanProjBuilder.useLegacyWildcardExpansion(false);
    scanProjBuilder.projectAll();
    ProjectionLifecycle lifecycle = ProjectionLifecycle.newContinuousLifecycle(scanProjBuilder);

    {
      FileMetadata fileInfo = new FileMetadata(new Path("hdfs:///w/x/y/a.csv"), "hdfs:///w");
      lifecycle.startFile(fileInfo);
      lifecycle.startSchema(priorSchema);
    }
    {
      FileMetadata fileInfo = new FileMetadata(new Path("hdfs:///w/x/y/b.csv"), "hdfs:///w");
      lifecycle.startFile(fileInfo);
      lifecycle.startSchema(tableSchema);
      assertEquals(2, lifecycle.schemaVersion());
      assertTrue(lifecycle.outputSchema().isEquivalent(tableSchema));
    }
  }

  /**
   * The prior and table schemas are identical. Preserve the prior
   * schema (though, the output is no different than if we discarded
   * the prior schema...)
   */

  @Test
  public void testSameSchemas() {
    MaterializedSchema priorSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .buildSchema();
    MaterializedSchema tableSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .buildSchema();

    ScanProjectionDefn.Builder scanProjBuilder = new ScanProjectionDefn.Builder(fixture.options());
    scanProjBuilder.useLegacyWildcardExpansion(false);
    scanProjBuilder.projectAll();
    ProjectionLifecycle lifecycle = ProjectionLifecycle.newContinuousLifecycle(scanProjBuilder);

    {
      FileMetadata fileInfo = new FileMetadata(new Path("hdfs:///w/x/y/a.csv"), "hdfs:///w");
      lifecycle.startFile(fileInfo);
      lifecycle.startSchema(priorSchema);
    }
    {
      FileMetadata fileInfo = new FileMetadata(new Path("hdfs:///w/x/y/b.csv"), "hdfs:///w");
      lifecycle.startFile(fileInfo);
      lifecycle.startSchema(tableSchema);
      assertEquals(1, lifecycle.schemaVersion());
      assertTrue(lifecycle.outputSchema().isEquivalent(tableSchema));
      assertTrue(lifecycle.outputSchema().isEquivalent(priorSchema));
    }
  }

  /**
   * The prior and table schemas are identical, but the cases of names differ.
   * Preserve the case of the first schema.
   */

  @Test
  public void testDifferentCase() {
    MaterializedSchema priorSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .buildSchema();
    MaterializedSchema tableSchema = new SchemaBuilder()
        .add("A", MinorType.INT)
        .add("B", MinorType.VARCHAR)
        .buildSchema();

    ScanProjectionDefn.Builder scanProjBuilder = new ScanProjectionDefn.Builder(fixture.options());
    scanProjBuilder.useLegacyWildcardExpansion(false);
    scanProjBuilder.projectAll();
    ProjectionLifecycle lifecycle = ProjectionLifecycle.newContinuousLifecycle(scanProjBuilder);

    {
      FileMetadata fileInfo = new FileMetadata(new Path("hdfs:///w/x/y/a.csv"), "hdfs:///w");
      lifecycle.startFile(fileInfo);
      lifecycle.startSchema(priorSchema);
    }
    {
      FileMetadata fileInfo = new FileMetadata(new Path("hdfs:///w/x/y/b.csv"), "hdfs:///w");
      lifecycle.startFile(fileInfo);
      lifecycle.startSchema(tableSchema);
      assertEquals(1, lifecycle.schemaVersion());
      assertTrue(lifecycle.outputSchema().isEquivalent(priorSchema));
    }
  }

  /**
   * Can't preserve the prior schema if it had required columns
   * where the new schema has no columns.
   */

  @Test
  public void testRequired() {
    MaterializedSchema priorSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .addNullable("b", MinorType.VARCHAR)
        .buildSchema();
    MaterializedSchema tableSchema = new SchemaBuilder()
        .addNullable("b", MinorType.VARCHAR)
        .buildSchema();

    ScanProjectionDefn.Builder scanProjBuilder = new ScanProjectionDefn.Builder(fixture.options());
    scanProjBuilder.useLegacyWildcardExpansion(false);
    scanProjBuilder.projectAll();
    ProjectionLifecycle lifecycle = ProjectionLifecycle.newContinuousLifecycle(scanProjBuilder);

    {
      FileMetadata fileInfo = new FileMetadata(new Path("hdfs:///w/x/y/a.csv"), "hdfs:///w");
      lifecycle.startFile(fileInfo);
      lifecycle.startSchema(priorSchema);
    }
    {
      FileMetadata fileInfo = new FileMetadata(new Path("hdfs:///w/x/y/b.csv"), "hdfs:///w");
      lifecycle.startFile(fileInfo);
      lifecycle.startSchema(tableSchema);
      assertEquals(2, lifecycle.schemaVersion());
      assertTrue(lifecycle.outputSchema().isEquivalent(tableSchema));
    }
  }

  /**
   * Preserve the prior schema if table is a subset and missing columns
   * are nullable or repeated.
   */

  @Test
  public void testSmoothing() {
    MaterializedSchema priorSchema = new SchemaBuilder()
        .addNullable("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .addArray("c", MinorType.BIGINT)
        .buildSchema();
    MaterializedSchema tableSchema = new SchemaBuilder()
        .add("b", MinorType.VARCHAR)
        .buildSchema();

    ScanProjectionDefn.Builder scanProjBuilder = new ScanProjectionDefn.Builder(fixture.options());
    scanProjBuilder.useLegacyWildcardExpansion(false);
    scanProjBuilder.projectAll();
    ProjectionLifecycle lifecycle = ProjectionLifecycle.newContinuousLifecycle(scanProjBuilder);

    {
      FileMetadata fileInfo = new FileMetadata(new Path("hdfs:///w/x/y/a.csv"), "hdfs:///w");
      lifecycle.startFile(fileInfo);
      lifecycle.startSchema(priorSchema);
    }
    {
      FileMetadata fileInfo = new FileMetadata(new Path("hdfs:///w/x/y/b.csv"), "hdfs:///w");
      lifecycle.startFile(fileInfo);
      lifecycle.startSchema(tableSchema);
      assertEquals(1, lifecycle.schemaVersion());
      assertTrue(lifecycle.outputSchema().isEquivalent(priorSchema));
    }
  }

  /**
   * Preserve the prior schema if table is a subset. Map the table
   * columns to the output using the prior schema orderng.
   */

  @Test
  public void testReordering() {
    MaterializedSchema priorSchema = new SchemaBuilder()
        .addNullable("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .addArray("c", MinorType.BIGINT)
        .buildSchema();
    MaterializedSchema tableSchema = new SchemaBuilder()
        .add("b", MinorType.VARCHAR)
        .addNullable("a", MinorType.INT)
        .buildSchema();

    ScanProjectionDefn.Builder scanProjBuilder = new ScanProjectionDefn.Builder(fixture.options());
    scanProjBuilder.useLegacyWildcardExpansion(false);
    scanProjBuilder.projectAll();
    ProjectionLifecycle lifecycle = ProjectionLifecycle.newContinuousLifecycle(scanProjBuilder);

    {
      FileMetadata fileInfo = new FileMetadata(new Path("hdfs:///w/x/y/a.csv"), "hdfs:///w");
      lifecycle.startFile(fileInfo);
      lifecycle.startSchema(priorSchema);
    }
    {
      FileMetadata fileInfo = new FileMetadata(new Path("hdfs:///w/x/y/b.csv"), "hdfs:///w");
      lifecycle.startFile(fileInfo);
      lifecycle.startSchema(tableSchema);
      assertEquals(1, lifecycle.schemaVersion());
      assertTrue(lifecycle.outputSchema().isEquivalent(priorSchema));
    }
  }

  /**
   * If using the legacy wildcard expansion, reuse schema if partition paths
   * are the same length.
   */

  @Test
  public void testSamePartitionLength() {
    MaterializedSchema tableSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .buildSchema();

    ScanProjectionDefn.Builder scanProjBuilder = new ScanProjectionDefn.Builder(fixture.options());
    scanProjBuilder.useLegacyWildcardExpansion(true);
    scanProjBuilder.projectAll();
    ProjectionLifecycle lifecycle = ProjectionLifecycle.newContinuousLifecycle(scanProjBuilder);

    MaterializedSchema expectedSchema = TestFileProjectionDefn.expandMetadata(lifecycle.scanProjection(), tableSchema, 2);
    {
      FileMetadata fileInfo = new FileMetadata(new Path("hdfs:///w/x/y/a.csv"), "hdfs:///w");
      lifecycle.startFile(fileInfo);
      lifecycle.startSchema(tableSchema);
      assertTrue(lifecycle.outputSchema().isEquivalent(expectedSchema));
    }
    {
      FileMetadata fileInfo = new FileMetadata(new Path("hdfs:///w/x/y/b.csv"), "hdfs:///w");
      lifecycle.startFile(fileInfo);
      lifecycle.startSchema(tableSchema);
      assertEquals(1, lifecycle.schemaVersion());
      assertTrue(lifecycle.outputSchema().isEquivalent(expectedSchema));
     }
  }

  /**
   * If using the legacy wildcard expansion, reuse schema if the new partition path
   * is shorter than the previous. (Unneded partitions will be set to null by the
   * scan projector.)
   */

  @Test
  public void testShorterPartitionLength() {
    MaterializedSchema tableSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .buildSchema();

    ScanProjectionDefn.Builder scanProjBuilder = new ScanProjectionDefn.Builder(fixture.options());
    scanProjBuilder.useLegacyWildcardExpansion(true);
    scanProjBuilder.projectAll();
    ProjectionLifecycle lifecycle = ProjectionLifecycle.newContinuousLifecycle(scanProjBuilder);

    MaterializedSchema expectedSchema = TestFileProjectionDefn.expandMetadata(lifecycle.scanProjection(), tableSchema, 2);
    {
      FileMetadata fileInfo = new FileMetadata(new Path("hdfs:///w/x/y/a.csv"), "hdfs:///w");
      lifecycle.startFile(fileInfo);
      lifecycle.startSchema(tableSchema);
      assertTrue(lifecycle.outputSchema().isEquivalent(expectedSchema));
    }
    {
      FileMetadata fileInfo = new FileMetadata(new Path("hdfs:///w/x/b.csv"), "hdfs:///w");
      lifecycle.startFile(fileInfo);
      lifecycle.startSchema(tableSchema);
      assertEquals(1, lifecycle.schemaVersion());
      assertTrue(lifecycle.outputSchema().isEquivalent(expectedSchema));
     }
  }

  /**
   * If using the legacy wildcard expansion, create a new schema if the new partition path
   * is longer than the previous.
   */

  @Test
  public void testLongerPartitionLength() {
    MaterializedSchema tableSchema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .buildSchema();

    ScanProjectionDefn.Builder scanProjBuilder = new ScanProjectionDefn.Builder(fixture.options());
    scanProjBuilder.useLegacyWildcardExpansion(true);
    scanProjBuilder.projectAll();
    ProjectionLifecycle lifecycle = ProjectionLifecycle.newContinuousLifecycle(scanProjBuilder);

    {
      FileMetadata fileInfo = new FileMetadata(new Path("hdfs:///w/x/a.csv"), "hdfs:///w");
      lifecycle.startFile(fileInfo);
      lifecycle.startSchema(tableSchema);
      MaterializedSchema expectedSchema = TestFileProjectionDefn.expandMetadata(lifecycle.scanProjection(), tableSchema, 1);
      assertTrue(lifecycle.outputSchema().isEquivalent(expectedSchema));
    }
    {
      FileMetadata fileInfo = new FileMetadata(new Path("hdfs:///w/x/y/b.csv"), "hdfs:///w");
      lifecycle.startFile(fileInfo);
      lifecycle.startSchema(tableSchema);
      assertEquals(2, lifecycle.schemaVersion());
      MaterializedSchema expectedSchema = TestFileProjectionDefn.expandMetadata(lifecycle.scanProjection(), tableSchema, 2);
      assertTrue(lifecycle.outputSchema().isEquivalent(expectedSchema));
     }
  }

//@Test
//public void testSelectStarSmoothing() {
//
//  ScanProjector projector = new ScanProjector(fixture.allocator(), null);
//
//  BatchSchema firstSchema = new SchemaBuilder()
//      .add("a", MinorType.INT)
//      .addNullable("b", MinorType.VARCHAR, 10)
//      .addNullable("c", MinorType.BIGINT)
//      .build();
//  BatchSchema subsetSchema = new SchemaBuilder()
//      .addNullable("b", MinorType.VARCHAR, 10)
//      .add("a", MinorType.INT)
//      .build();
//  BatchSchema disjointSchema = new SchemaBuilder()
//      .add("a", MinorType.INT)
//      .addNullable("b", MinorType.VARCHAR, 10)
//      .add("d", MinorType.VARCHAR)
//      .build();
//
//  SchemaTracker tracker = new SchemaTracker();
//  int schemaVersion;
//  {
//    // First table, establishes the baseline
//
//    ProjectionPlanner builder = new ProjectionPlanner(fixture.options());
//    builder.queryCols(TestScanProjectionPlanner.selectList("*"));
//    builder.tableColumns(firstSchema);
//    ScanProjection projection = builder.build();
//    ResultSetLoader loader = projector.makeTableLoader(projection);
//
//    loader.startBatch();
//    loader.writer()
//        .loadRow(10, "fred", 110L)
//        .loadRow(20, "wilma", 110L);
//    projector.publish();
//
//    tracker.trackSchema(projector.output());
//    schemaVersion = tracker.schemaVersion();
//
//    SingleRowSet expected = fixture.rowSetBuilder(firstSchema)
//        .add(10, "fred", 110L)
//        .add(20, "wilma", 110L)
//        .build();
//    new RowSetComparison(expected)
//      .verifyAndClearAll(fixture.wrap(projector.output()));
//  }
//  {
//    // Second table, same schema, the trivial case
//
//    ProjectionPlanner builder = new ProjectionPlanner(fixture.options());
//    builder.queryCols(TestScanProjectionPlanner.selectList("*"));
//    builder.tableColumns(firstSchema);
//    builder.priorSchema(projector.output().getSchema());
//    ScanProjection projection = builder.build();
//    ResultSetLoader loader = projector.makeTableLoader(projection);
//
//    loader.startBatch();
//    loader.writer()
//        .loadRow(70, "pebbles", 770L)
//        .loadRow(80, "hoppy", 880L);
//    projector.publish();
//
//    tracker.trackSchema(projector.output());
//    assertEquals(schemaVersion, tracker.schemaVersion());
//
//    SingleRowSet expected = fixture.rowSetBuilder(firstSchema)
//        .add(70, "pebbles", 770L)
//        .add(80, "hoppy", 880L)
//        .build();
//    new RowSetComparison(expected)
//      .verifyAndClearAll(fixture.wrap(projector.output()));
//  }
//  {
//    // Third table: subset schema of first two
//
//    ProjectionPlanner builder = new ProjectionPlanner(fixture.options());
//    builder.queryCols(TestScanProjectionPlanner.selectList("*"));
//    builder.tableColumns(subsetSchema);
//    builder.priorSchema(projector.output().getSchema());
//    ScanProjection projection = builder.build();
//    ResultSetLoader loader = projector.makeTableLoader(projection);
//
//    loader.startBatch();
//    loader.writer()
//        .loadRow("bambam", 30)
//        .loadRow("betty", 40);
//    projector.publish();
//
//    tracker.trackSchema(projector.output());
//    assertEquals(schemaVersion, tracker.schemaVersion());
//
//    SingleRowSet expected = fixture.rowSetBuilder(firstSchema)
//        .add(30, "bambam", null)
//        .add(40, "betty", null)
//        .build();
//    new RowSetComparison(expected)
//      .verifyAndClearAll(fixture.wrap(projector.output()));
//  }
//  {
//    // Fourth table: disjoint schema, cases a schema reset
//
//    ProjectionPlanner builder = new ProjectionPlanner(fixture.options());
//    builder.queryCols(TestScanProjectionPlanner.selectList("*"));
//    builder.tableColumns(disjointSchema);
//    builder.priorSchema(projector.output().getSchema());
//    ScanProjection projection = builder.build();
//    ResultSetLoader loader = projector.makeTableLoader(projection);
//
//    loader.startBatch();
//    loader.writer()
//        .loadRow(50, "dino", "supporting")
//        .loadRow(60, "barney", "main");
//    projector.publish();
//
//    tracker.trackSchema(projector.output());
//    assertNotEquals(schemaVersion, tracker.schemaVersion());
//
//    SingleRowSet expected = fixture.rowSetBuilder(disjointSchema)
//        .add(50, "dino", "supporting")
//        .add(60, "barney", "main")
//        .build();
//    new RowSetComparison(expected)
//      .verifyAndClearAll(fixture.wrap(projector.output()));
//  }
//
//  projector.close();
//}
}
