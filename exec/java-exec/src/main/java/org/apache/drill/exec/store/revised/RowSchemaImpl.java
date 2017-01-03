package org.apache.drill.exec.store.revised;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.drill.exec.store.revised.Sketch.ColumnSchema;
import org.apache.drill.exec.store.revised.Sketch.RowSchema;

public class RowSchemaImpl implements RowSchema {

  private final int flattenCount;
  private final boolean caseSensitive;
  private final List<ColumnSchema> columns;
  private final Map<String, ColumnSchema> nameIndex;

  public RowSchemaImpl(boolean caseSensitive, List<ColumnSchema> columns) {
    this.caseSensitive = caseSensitive;
    this.columns = columns;
    nameIndex = new HashMap<>( );
    int totalCount = 0;
    for ( ColumnSchema col : columns ) {
      nameIndex.put(key(col.name()), col);
      totalCount++;
      RowSchema mapSchema = col.map();
      if (mapSchema != null) {
        totalCount += mapSchema.totalSize();
      }
    }
    flattenCount = totalCount;
  }

  @Override
  public int size() { return columns.size( ); }

  @Override
  public int totalSize() { return flattenCount; }

  @Override
  public List<ColumnSchema> schema() { return columns; }

  @Override
  public ColumnSchema column(String name) {
    return nameIndex.get(key(name));
  }

  @Override
  public ColumnSchema column(int index) {
    return columns.get(index);
  }

  public String key(String name) {
    return key(caseSensitive, name);
  }

  public static String key(boolean caseSensitive, String name) {
    return caseSensitive ? name : name.toLowerCase();
  }

  @Override
  public boolean isCaseSensitive() {
    return caseSensitive;
  }

  public static class FlattenedSchemaImpl implements RowSchema {

    private final boolean caseSensitive;
    private final List<ColumnSchema> columns;
    private final Map<String, ColumnSchema> nameIndex;

    public FlattenedSchemaImpl(boolean caseSensitive, List<ColumnSchema> columns, Map<String,ColumnSchema> nameIndex) {
      this.caseSensitive = caseSensitive;
      this.columns = columns;
      this.nameIndex = nameIndex;
    }

    @Override
    public int size() { return columns.size(); }

    @Override
    public int totalSize() { return columns.size(); }

    @Override
    public List<ColumnSchema> schema() { return columns; }

    @Override
    public ColumnSchema column(String name) {
      return nameIndex.get(key(caseSensitive,name));
    }

    @Override
    public ColumnSchema column(int index) { return columns.get(index); }

    @Override
    public RowSchema flatten() {
      return this;
    }

    @Override
    public boolean isCaseSensitive() {
      return caseSensitive;
    }
  }

  @Override
  public RowSchema flatten() {
    List<ColumnSchema> flatColumns = new ArrayList<>( );
    Map<String,ColumnSchema> flatIndex = new HashMap<>( );
    doFlatten( flatColumns, flatIndex, "" );
    return new FlattenedSchemaImpl( caseSensitive, flatColumns, flatIndex );
  }

  protected void doFlatten( List<ColumnSchema> flatColumns, Map<String,ColumnSchema> flatIndex, String prefix ) {
    for ( ColumnSchema col : columns ) {
      String fullName = prefix + col.name();
      flatColumns.add( col );
      flatIndex.put( key(fullName), col);
      RowSchema mapSchema = col.map();
      if (mapSchema != null) {
        ((RowSchemaImpl) mapSchema).doFlatten( flatColumns, flatIndex, fullName );
      }
    }
  }
}
