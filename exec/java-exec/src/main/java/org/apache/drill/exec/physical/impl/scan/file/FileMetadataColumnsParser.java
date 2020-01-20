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
package org.apache.drill.exec.physical.impl.scan.file;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.drill.exec.physical.impl.scan.project.ColumnProjection;
import org.apache.drill.exec.physical.impl.scan.project.ScanLevelProjection;
import org.apache.drill.exec.physical.impl.scan.project.ScanLevelProjection.ScanProjectionParser;
import org.apache.drill.exec.physical.resultSet.project.RequestedColumn;

/**
 * Parses the implicit file metadata columns out of a project list,
 * and marks them for special handling by the file metadata manager.
 */

public class FileMetadataColumnsParser implements ScanProjectionParser {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(FileMetadataColumnsParser.class);

  // Internal

  private final FileMetadataManager metadataManager;
  private final Pattern partitionPattern;
  private ScanLevelProjection builder;
  private final Set<Integer> referencedPartitions = new HashSet<>();

  // Output

  private boolean hasImplicitCols;

  private boolean expandPartitionsAtEnd;

  public FileMetadataColumnsParser(FileMetadataManager metadataManager) {
    this.metadataManager = metadataManager;
    partitionPattern = Pattern.compile(metadataManager.partitionDesignator + "(\\d+)", Pattern.CASE_INSENSITIVE);
  }

  @Override
  public void bind(ScanLevelProjection builder) {
    this.builder = builder;
  }

  @Override
  public boolean parse(RequestedColumn inCol) {
    Matcher m = partitionPattern.matcher(inCol.name());
    if (m.matches()) {
      return buildPartitionColumn(m, inCol);
    }

    FileMetadataColumnDefn defn = metadataManager.fileMetadataColIndex.get(inCol.name());
    if (defn != null) {
      return buildMetadataColumn(defn, inCol);
    }
    if (inCol.isWildcard()) {
      buildWildcard();

      // Don't consider this a match.
    }
    return false;
  }

  private boolean buildPartitionColumn(Matcher m, RequestedColumn inCol) {

    // If the projected column is a map or array, then it shadows the
    // partition column. Example: dir0.x, dir0[2].

    if (! inCol.isSimple()) {
      logger.warn("Partition column {} is shadowed by a projected {}",
          inCol.name(), inCol.summary());
      return false;
    }

    // Partition column

    int partitionIndex = Integer.parseInt(m.group(1));
    if (! referencedPartitions.contains(partitionIndex)) {
      builder.addMetadataColumn(
          new PartitionColumn(
            inCol.name(),
            partitionIndex));

      // Remember the partition for later wildcard expansion

      referencedPartitions.add(partitionIndex);
      hasImplicitCols = true;
    }
    return true;
  }

  private boolean buildMetadataColumn(FileMetadataColumnDefn defn,
      RequestedColumn inCol) {

    // If the projected column is a map or array, then it shadows the
    // metadata column. Example: filename.x, filename[2].

    if (! inCol.isSimple()) {
      logger.warn("File metadata column {} is shadowed by a projected {}",
          inCol.name(), inCol.summary());
      return false;
    }

    // File metadata (implicit) column

    builder.addMetadataColumn(new FileMetadataColumn(inCol.name(), defn));
    hasImplicitCols = true;
    return true;
  }

  private void buildWildcard() {
    if (!metadataManager.options().useLegacyWildcardExpansion) {
      return;
    }
    if (metadataManager.options().useLegacyExpansionLocation) {

      // Star column: this is a SELECT * query.

      // Old-style wildcard handling inserts all partition columns in
      // the scanner, removes them in Project.
      // Fill in the file metadata columns. Can do here because the
      // set is constant across all files.

      expandPartitions();
    } else {
      expandPartitionsAtEnd = true;
    }
  }

  @Override
  public void validate() {

    // Expand partitions if using a wildcard appears, if using the
    // feature to expand partitions for wildcards, and we want the
    // partitions after data columns.

    if (expandPartitionsAtEnd) {
      expandPartitions();
    }
  }

  private void expandPartitions() {

    // Legacy wildcard expansion: include the file partitions for this file.
    // This is a disadvantage for a * query: files at different directory
    // levels will have different numbers of columns. Would be better to
    // return this data as an array at some point.
    // Append this after the *, keeping the * for later expansion.

    for (int i = 0; i < metadataManager.partitionCount(); i++) {
      if (referencedPartitions.contains(i)) {
        continue;
      }
      builder.addMetadataColumn(new PartitionColumn(
          metadataManager.partitionName(i), i));
      referencedPartitions.add(i);
    }
    hasImplicitCols = true;
  }

  @Override
  public void validateColumn(ColumnProjection outCol) { }

  @Override
  public void build() { }

  public boolean hasImplicitCols() { return hasImplicitCols; }
}
