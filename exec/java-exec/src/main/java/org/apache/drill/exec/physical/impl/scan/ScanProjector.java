package org.apache.drill.exec.physical.impl.scan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.drill.common.types.TypeProtos.DataMode;
import org.apache.drill.common.types.TypeProtos.MajorType;
import org.apache.drill.common.types.TypeProtos.MinorType;
import org.apache.drill.exec.memory.BufferAllocator;
import org.apache.drill.exec.physical.impl.scan.RowBatchMerger.Builder;
import org.apache.drill.exec.physical.impl.scan.ScanProjection.OutputColumn;
import org.apache.drill.exec.physical.impl.scan.ScanProjection.ProjectedColumn;
import org.apache.drill.exec.physical.impl.scan.ScanProjection.StaticColumn;
import org.apache.drill.exec.physical.impl.scan.SchemaNegotiator.TableSchemaType;
import org.apache.drill.exec.physical.rowSet.ResultSetLoader;
import org.apache.drill.exec.physical.rowSet.TupleLoader;
import org.apache.drill.exec.physical.rowSet.TupleSchema;
import org.apache.drill.exec.physical.rowSet.impl.ResultSetLoaderImpl;
import org.apache.drill.exec.record.MaterializedField;
import org.apache.drill.exec.record.VectorContainer;

/**
 * Performs projection of a record reader, along with a set of static
 * columns, to produce the final "public" result set (record batch)
 * for the scan operator. Primarily solve the "vector permanence"
 * problem: that the scan operator must present the same set of vectors
 * to downstream operators despite the fact that the scan operator hosts
 * a series of readers, each of which builds its own result set.
 * <p>
 * This class "publishes" a vector container that has the final, projected
 * form of a scan. The projected schema include:
 * <ul>
 * <li>Columns from the reader.</li>
 * <li>Static columns, such as implicit or partition columns.</li>
 * <li>Null columns for items in the select list, but not found in either
 * of the above two categories.</li>
 * </ul>
 * The order of columns is that set by the select list (or, by the reader for
 * a <tt>SELECT *</tt> query.
 * <p>
 * The mapping handle a variety of cases:
 * <ul>
 * <li>An early-schema table (one in which we know the schema and
 * the schema remains constant for the whole table.</li>
 * <li>A late schema table (one in which we discover the schema as
 * we read the table, and where the schema can change as the read
 * progresses.)<ul>
 * <li>Late schema table with SELECT * (we want all columns, whatever
 * they happen to be.)</li>
 * <li>Late schema with explicit select list (we want only certain
 * columns when they happen to appear in the input.)</li></ul></li>
 * </ul>
 * <p>
 * Major tasks of this class include:
 * <ul>
 * <li>Project table columns (change position and or name).</li>
 * <li>Insert static and null columns.</li>
 * <li>Schema smoothing. That is, if table A produces columns (a, b), but
 * table B produces only (a), use the type of the first table's b column for the
 * null created for the missing b in table B.</li>
 * <li>Vector persistence: use the same set of vectors across readers as long
 * as the reader schema does not cause a "hard" schema change (change in type,
 * introduction of a new column.</li>
 * <li>Detection of schema changes (change of type, introduction of a new column
 * for a <tt>SELECT *</tt> query, changing the projected schema, and reporting
 * the change downstream.</li>
 * </ul>
 * A projection is needed to:
 * <ul>
 * <li>Reorder table columns</li>
 * <li>Select a subset of table columns</li>
 * <li>Fill in missing select columns</li>
 * <li>Fill in implicit or partition columns</li>
 * </ul>
 * Creates and returns the batch merger that does the projection.
 * <p>
 * To visualize this, assume we have numbered table columns, lettered
 * implicit, null or partition columns:<pre><code>
 * [ 1 | 2 | 3 | 4 ]    Table columns in table order
 * [ A | B | C ]        Static columns
 * </code></pre>
 * Now, we wish to project them into select order.
 * Let's say that the SELECT clause looked like this, with "t"
 * indicating table columns:<pre><code>
 * SELECT t2, t3, C, B, t1, A, t2 ...
 * </code></pre>
 * Then the projection looks like this:<pre><code>
 * [ 2 | 3 | C | B | 1 | A | 2 ]
 * </code></pre>
 * Often, not all table columns are projected. In this case, the
 * result set loader presents the full table schema to the reader,
 * but actually writes only the projected columns. Suppose we
 * have:<pre><code>
 * SELECT t3, C, B, t1,, A ...
 * </code></pre>
 * Then the abbreviated table schema looks like this:<pre><code>
 * [ 1 | 3 ]</code></pre>
 * Note that table columns retain their table ordering.
 * The projection looks like this:<pre><code>
 * [ 2 | C | B | 1 | A ]
 * </code></pre>
 * <p>
 * The projector is created once per schema, then can be reused for any
 * number of batches.
 * <p>
 * Merging is done in one of two ways, depending on the input source:
 * <ul>
 * <li>For the table loader, the merger discards any data in the output,
 * then exchanges the buffers from the input columns to the output,
 * leaving projected columns empty. Note that unprojected columns must
 * be cleared by the caller.</li>
 * <li>For implicit and null columns, the output vector is identical
 * to the input vector.</li>
 */

public class ScanProjector {

  public abstract static class StaticColumnLoader {
    protected final ResultSetLoader loader;

    public StaticColumnLoader(BufferAllocator allocator, ResultVectorCache vectorCache) {

      ResultSetLoaderImpl.ResultSetOptions options = new ResultSetLoaderImpl.OptionBuilder()
            .setVectorCache(vectorCache)
            .build();
      loader = new ResultSetLoaderImpl(allocator, options);
    }

    /**
     * Populate static vectors with the defined static values.
     *
     * @param rowCount number of rows to generate. Must match the
     * row count in the batch returned by the reader
     */

    public void load(int rowCount) {
      loader.startBatch();
      TupleLoader writer = loader.writer();
      for (int i = 0; i < rowCount; i++) {
        loader.startRow();
        loadRow(writer);
        loader.saveRow();
      }
      loader.harvest();
    }

    protected abstract void loadRow(TupleLoader writer);

    public VectorContainer output() {
      return loader.outputContainer();
    }

    public void close() {
      loader.close();
    }
  }

  /**
   * Populate implicit and partition columns.
   */

  public static class ImplicitColumnLoader extends StaticColumnLoader {
    private final String values[];
    private final List<StaticColumn> staticCols;

    public ImplicitColumnLoader(BufferAllocator allocator, List<StaticColumn> defns, ResultVectorCache vectorCache) {
      super(allocator, vectorCache);

      // Populate the loader schema from that provided

      staticCols = defns;
      TupleSchema schema = loader.writer().schema();
      values = new String[defns.size()];
      for (int i = 0; i < defns.size(); i++) {
        StaticColumn defn  = defns.get(i);
        values[i] = defn.value();
        schema.addColumn(defn.schema());
      }
    }

    /**
     * Populate static vectors with the defined static values.
     *
     * @param rowCount number of rows to generate. Must match the
     * row count in the batch returned by the reader
     */

    @Override
    protected void loadRow(TupleLoader writer) {
      for (int i = 0; i < values.length; i++) {

        // Set the column (of any type) to null if the string value
        // is null.

        if (values[i] == null) {
          writer.column(i).setNull();
        } else {
          // Else, set the static (string) value.

          writer.column(i).setString(values[i]);
        }
      }
    }

    public List<StaticColumn> columns() { return staticCols; }
  }

  public static class NullColumnLoader extends StaticColumnLoader {

    private final boolean isArray[];

    public NullColumnLoader(BufferAllocator allocator, List<OutputColumn> defns,
                            ResultVectorCache vectorCache, MajorType nullType) {
      super(allocator, vectorCache);

      // Use the provided null type, else the standard nullable int.

      if (nullType == null ) {
        nullType = MajorType.newBuilder()
              .setMinorType(MinorType.INT)
              .setMode(DataMode.OPTIONAL)
              .build();
      }

      // Populate the loader schema from that provided

      TupleSchema schema = loader.writer().schema();
      isArray = new boolean[defns.size()];
      for (int i = 0; i < defns.size(); i++) {
        OutputColumn defn = defns.get(i);

        // Prefer the type of any previous occurrence of
        // this column.

        MaterializedField colSchema = vectorCache.getType(defn.name());

        // Else, use the type defined in the projection, if any.

        if (colSchema == null) {
          colSchema = defn.schema();
        }

        // Else, use the specified null type.

        if (colSchema == null) {
          colSchema = MaterializedField.create(defn.name(), nullType);
        }

        // Map required to optional. Will cause a schema change.

        if (colSchema.getDataMode() == DataMode.REQUIRED) {
          colSchema = MaterializedField.create(colSchema.getName(),
              MajorType.newBuilder()
                .setMinorType(colSchema.getType().getMinorType())
                .setMode(DataMode.OPTIONAL)
                .build());
        }
        isArray[i] = colSchema.getDataMode() == DataMode.REPEATED;
        schema.addColumn(colSchema);
      }
    }

    /**
     * Populate nullable values with null, repeated vectors with
     * an empty array (which, in Drill, is equivalent to null.).
     *
     * @param rowCount number of rows to generate. Must match the
     * row count in the batch returned by the reader
     */

    @Override
    protected void loadRow(TupleLoader writer) {
      for (int i = 0; i < isArray.length; i++) {

        // Set the column (of any type) to null if the string value
        // is null.

        if (isArray[i]) {
          // Nothing to do, array empty by default
        } else {
          writer.column(i).setNull();
        }
      }
    }
  }

  private final BufferAllocator allocator;
  private final ResultVectorCache vectorCache;
  private final MajorType nullType;
  private ScanProjection projection;
  private ResultSetLoader tableLoader;
  private ImplicitColumnLoader implicitColumnLoader;
  private NullColumnLoader nullColumnLoader;
  private RowBatchMerger output;
  private int prevTableSchemaVersion;

  public ScanProjector(BufferAllocator allocator, MajorType nullType) {
    this.allocator = allocator;
    vectorCache = new ResultVectorCache(allocator);
    this.nullType = nullType;
  }

  public void setTableLoader(ResultSetLoader tableLoader, ScanProjection projection) {
    this.tableLoader = tableLoader;
    boolean first = this.projection == null;
    this.projection = projection;
    if (first) {
      buildImplicitColumns();
    }
    planProjection();

    // Set the output container to zero rows. Required so that we can
    // send the schema downstream in the form of an empty batch.

    output.getOutput().setRecordCount(0);
  }

  /**
   * The implicit and partition columns are static: they are the same across
   * all readers. If any such columns exist, build the loader for them.
   */

  private void buildImplicitColumns() {
    List<StaticColumn> staticCols = new ArrayList<>();
    staticCols.addAll(projection.implicitCols());
    staticCols.addAll(projection.partitionCols());
    if (staticCols.isEmpty()) {
      return;
    }
    implicitColumnLoader = new ImplicitColumnLoader(allocator, staticCols, vectorCache);
  }

  private void planProjection() {
    nullColumnLoader = null;
    RowBatchMerger.Builder builder = new RowBatchMerger.Builder();
    List<OutputColumn> nullCols = buildNullColumns();
    if (nullCols != null) {
      mapNullColumns(builder, nullCols);
    }
    mapTableColumns(builder);
    mapImplicitColumns(builder);
    output = builder.build(allocator);
    prevTableSchemaVersion = tableLoader.schemaVersion();
  }

  private List<OutputColumn> buildNullColumns() {
    if (projection.isSelectAll()) {
      return null;
    }
    List<OutputColumn> nullCols = new ArrayList<>();
    if (projection.tableSchemaType() == TableSchemaType.EARLY) {
      nullCols.addAll(projection.nullCols());
    } else {
      Map<String, ProjectedColumn> projMap = new HashMap<>();
      for (ProjectedColumn col : projection.projectedCols()) {
        projMap.put(col.name(), col);
      }
      TupleSchema schema = tableLoader.writer().schema();
      int colCount = schema.columnCount();
      for (int i = 0; i < colCount; i++) {
        MaterializedField tCol = schema.column(i);
        projMap.remove(tCol.getName());
      }
      nullCols.addAll(projMap.values());
    }
    if (nullCols.isEmpty()) {
      return null;
    }
    nullColumnLoader = new NullColumnLoader(allocator, nullCols, vectorCache, nullType);
    return nullCols;
  }

  private void mapTableColumns(Builder builder) {

    // Projection of table columns is from the abbreviated table
    // schema after removing unprojected columns.

    VectorContainer tableContainer = tableLoader.outputContainer();
    List<ProjectedColumn> projections = projection.projectedCols();
    for (int i = 0; i < projections.size(); i++) {
      builder.addExchangeProjection(tableContainer, i, projections.get(i).index());
    }
  }

  /**
   * Project implicit and partition columns into the output. Since
   * these columns are consistent across all readers, just project
   * the result set loader's own vectors; not need to do an exchange.
   * @param builder
   */

  private void mapImplicitColumns(RowBatchMerger.Builder builder) {

    // Project static columns into their output schema locations

    if (implicitColumnLoader == null) {
      return;
    }
    VectorContainer staticContainer = implicitColumnLoader.output();
    List<StaticColumn> staticCols = implicitColumnLoader.columns();
    for (int i = 0; i < staticCols.size(); i++) {
      builder.addDirectProjection(staticContainer, i, staticCols.get(i).index());
    }
  }

  private void mapNullColumns(Builder builder, List<OutputColumn> nullCols) {
    if (nullColumnLoader == null) {
      return;
    }
    VectorContainer staticContainer = nullColumnLoader.output();
    for (int i = 0; i < nullCols.size(); i++) {
      builder.addDirectProjection(staticContainer, i, nullCols.get(i).index());
    }
  }

  public void publish() {
    if (prevTableSchemaVersion < tableLoader.schemaVersion()) {
      planProjection();
    }
    VectorContainer tableContainer = tableLoader.harvest();
    int rowCount = tableContainer.getRecordCount();
    if (implicitColumnLoader != null) {
      implicitColumnLoader.load(rowCount);
    }
    if (nullColumnLoader != null) {
      nullColumnLoader.load(rowCount);
    }
    output.project(rowCount);
  }

  public VectorContainer output() {
    return output.getOutput();
  }

  public void close() {
    if (implicitColumnLoader != null) {
      implicitColumnLoader.close();
      implicitColumnLoader = null;
    }
    if (nullColumnLoader != null) {
      nullColumnLoader.close();
      nullColumnLoader = null;
    }
  }
}
