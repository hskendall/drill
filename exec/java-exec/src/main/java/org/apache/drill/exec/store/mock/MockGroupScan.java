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
package org.apache.drill.exec.store.mock;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.directory.api.util.Strings;
import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.common.types.TypeProtos.DataMode;
import org.apache.drill.common.types.TypeProtos.MinorType;
import org.apache.drill.exec.expr.TypeHelper;
import org.apache.drill.exec.physical.base.AbstractGroupScan;
import org.apache.drill.exec.physical.base.GroupScan;
import org.apache.drill.exec.physical.base.PhysicalOperator;
import org.apache.drill.exec.physical.base.ScanStats;
import org.apache.drill.exec.physical.base.ScanStats.GroupScanProperty;
import org.apache.drill.exec.physical.base.SubScan;
import org.apache.drill.exec.planner.cost.DrillCostBase;
import org.apache.drill.exec.proto.CoordinationProtos.DrillbitEndpoint;
import org.apache.drill.exec.record.metadata.ColumnBuilder;
import org.apache.drill.exec.record.metadata.ColumnMetadata;
import org.apache.drill.exec.store.mock.MockTableDef.MockScanEntry;
import org.apache.drill.shaded.guava.com.google.common.base.Preconditions;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Describes a "group" scan of a (logical) mock table. The mock table has a
 * schema described by the {@link MockScanEntry}. Class. To simulate a scan that
 * can be parallelized, this group scan can contain a list of
 * {@link MockScanEntry}, each of which simulates a separate file on disk, or
 * block within a file. Each will give rise to a separate minor fragment
 * (assuming sufficient parallelization.)
 */

@JsonTypeName("mock-scan")
public class MockGroupScan extends AbstractGroupScan {

  /**
   * The set of simulated files to scan.
   */
  protected final List<MockScanEntry> readEntries;
  private LinkedList<MockScanEntry>[] mappings;

  /**
   * Whether this group scan uses a newer "extended" schema definition, or the
   * original (non-extended) definition.
   */

  private boolean extended;
  private ScanStats scanStats = ScanStats.TRIVIAL_TABLE;

  @JsonCreator
  public MockGroupScan(
      @JsonProperty("entries") List<MockScanEntry> readEntries) {
    super((String) null);
    this.readEntries = readEntries;

    // Compute decent row-count stats for this mock data source so that
    // the planner is "fooled" into thinking that this operator will do
    // disk I/O.

    double rowCount = 0;
    int rowWidth = 0;

    // Can have multiple "read entries" which simulate blocks or
    // row groups.

    for (MockScanEntry entry : readEntries) {
      rowCount += entry.getRecords();
      int groupRowWidth = 0;
      if (entry.getTypes() == null) {
        // If no columns, assume a row width.
        groupRowWidth = 50;
      } else {
        // The normal case: we do have columns. Use them
        // to compute the row width.

        for (ColumnMetadata col : entry.getTypes()) {
          int colWidth = 0;
          if (col.precision() == ColumnMetadata.UNDEFINED) {
            // Fixed width columns
            colWidth = TypeHelper.getSize(col.majorType());
          } else {
            // Variable width columns with a specified column
            // width
            colWidth = col.precision();
          }

          // Columns can repeat
          colWidth *= col.intProperty(MockTableDef.REPEAT_PROP, 1);
          groupRowWidth += colWidth;
        }
      }

      // Overall row width is the greatest group row width.

      rowWidth = Math.max(rowWidth, groupRowWidth);
    }
    double dataSize = rowCount * rowWidth;
    scanStats = new ScanStats(GroupScanProperty.EXACT_ROW_COUNT,
                               rowCount,
                               DrillCostBase.BASE_CPU_COST * dataSize,
                               DrillCostBase.BYTE_DISK_READ_COST * dataSize);
  }

  @Override
  public ScanStats getScanStats() {
    return scanStats;
  }

  @JsonProperty("entries")
  public List<MockScanEntry> getReadEntries() {
    return readEntries;
  }

  @SuppressWarnings("unchecked")
  @Override
  public void applyAssignments(List<DrillbitEndpoint> endpoints) {
    Preconditions.checkArgument(endpoints.size() <= getReadEntries().size());

    mappings = new LinkedList[endpoints.size()];

    int i = 0;
    for (MockScanEntry e : this.getReadEntries()) {
      if (i == endpoints.size()) {
        i -= endpoints.size();
      }
      LinkedList<MockScanEntry> entries = mappings[i];
      if (entries == null) {
        entries = new LinkedList<MockScanEntry>();
        mappings[i] = entries;
      }
      entries.add(e);
      i++;
    }
  }

  @Override
  public SubScan getSpecificScan(int minorFragmentId) {
    assert minorFragmentId < mappings.length : String.format(
        "Mappings length [%d] should be longer than minor fragment id [%d] but it isn't.",
        mappings.length, minorFragmentId);
    return new MockSubScan(extended, mappings[minorFragmentId]);
  }

  @Override
  public int getMaxParallelizationWidth() {
    return readEntries.size();
  }

  @Override
  @JsonIgnore
  public PhysicalOperator getNewWithChildren(List<PhysicalOperator> children) {
    Preconditions.checkArgument(children.isEmpty());
    return new MockGroupScan(readEntries);
  }

  @Override
  public GroupScan clone(List<SchemaPath> columns) {
    if (columns.isEmpty()) {
      throw new IllegalArgumentException("No columns for mock scan");
    }
    List<ColumnMetadata> mockCols = new ArrayList<>();
    for (SchemaPath path : columns) {
      String col = path.getLastSegment().getNameSegment().getPath();
      if (SchemaPath.DYNAMIC_STAR.equals(col)) {
        return this;
      }
      ColumnMetadata mockCol = buildCol(col);
      mockCols.add(mockCol);
    }
    MockScanEntry entry = readEntries.get(0);
    ColumnMetadata types[] = new ColumnMetadata[mockCols.size()];
    mockCols.toArray(types);
    MockScanEntry newEntry = new MockScanEntry(entry.records, true, 0, 1, types);
    List<MockScanEntry> newEntries = new ArrayList<>();
    newEntries.add(newEntry);
    return new MockGroupScan(newEntries);
  }

  private static final Pattern pattern = Pattern.compile("(\\w+)_([isdb])(\\d*)(n(\\d*))?");

  private ColumnMetadata buildCol(String col) {
    Matcher m = pattern.matcher(col);
    if (!m.matches()) {
      throw new IllegalArgumentException(
          "Badly formatted mock column name: " + col);
    }
    // Can't use just the name bit because then Drill creates an INT
    // column of the full name.
    //String name = m.group(1);
    String type = m.group(2);
    String length = m.group(3);
    int width = 0;
    if (!length.isEmpty()) {
      width = Integer.parseInt(length);
    }
    MinorType minorType;
    switch (type) {
    case "i":
      minorType = MinorType.INT;
      break;
    case "s":
      minorType = MinorType.VARCHAR;
      if (width == 0) {
        width = 10;
      }
      break;
    case "d":
      minorType = MinorType.FLOAT8;
      break;
    case "b":
      minorType = MinorType.BIT;
      break;
    default:
      throw new IllegalArgumentException(
          "Unsupported field type " + type + " for mock column " + col);
    }
    DataMode mode;
    int nullRate = -1;
    if (m.group(4) == null) {
      mode = DataMode.REQUIRED;
     } else {
      mode = DataMode.OPTIONAL;
      String rateStr = m.group(5);
      if (! Strings.isEmpty(rateStr)) {
        nullRate = Math.min(100,
            Math.max(0, Integer.parseInt(rateStr)));
       }
    }
    ColumnBuilder builder = ColumnBuilder.builder(col,  minorType,  mode);
    if (width != 0) {
       builder.precision(width);
    }
    ColumnMetadata mockCol = builder.build();
    if (nullRate != -1) {
      mockCol.setIntProperty(MockTableDef.NULL_RATE_PROP, nullRate);
    }
    return mockCol;
  }

  @Override
  public String getDigest() {
    return toString();
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() +
        " [readEntries=" + readEntries + "]";
  }

  @Override
  @JsonIgnore
  public boolean canPushdownProjects(List<SchemaPath> columns) {
    return true;
  }
}
