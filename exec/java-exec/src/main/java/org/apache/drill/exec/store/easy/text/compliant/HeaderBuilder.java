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
package org.apache.drill.exec.store.easy.text.compliant;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.common.base.Charsets;

/**
 * Text output that implements a header reader/parser.
 * The caller parses out the characters of each header;
 * this class assembles UTF-8 bytes into Unicode characters,
 * fixes invalid characters (those not legal for SQL symbols),
 * and maps duplicate names to unique names.
 * <p>
 * That is, this class is as permissive as possible with file
 * headers to avoid spurious query failures for trivial reasons.
 */

// Note: this class uses Java heap strings and the usual Java
// convenience classes. Since we do heavy Unicode string operations,
// and read a single row, there is no good reason to try to use
// value vectors and direct memory for this task.

public class HeaderBuilder extends TextOutput {

  /**
   * Maximum Drill symbol length, as enforced for headers.
   * @see <a href="https://drill.apache.org/docs/lexical-structure/#identifier">
   * identifier documentation</a>
   */
  // TODO: Replace with the proper constant, if available
  public static final int MAX_HEADER_LEN = 1024;

  /**
   * Exception that reports header errors. Is an unchecked exception
   * to avoid cluttering the normal field reader interface.
   */
  public static class HeaderError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HeaderError(String msg) {
      super(msg);
    }

    public HeaderError(int colIndex, String msg) {
      super("Column " + (colIndex + 1) + ": " + msg);
    }

  }

  public List<String> headers = new ArrayList<>();
  public ByteBuffer currentField = ByteBuffer.allocate(MAX_HEADER_LEN);

  @Override
  public void startField(int index) {
    currentField.clear();
  }

  @Override
  public boolean endField() {
    String header = new String(currentField.array(), 0, currentField.position(), Charsets.UTF_8);
    header = validateSymbol(header);
    headers.add(header);
    return true;
  }

  @Override
  public boolean endEmptyField() {

    // Empty header will be rewritten to "column<n>".

    return endField();
  }

  /**
   * Validate the header name according to the SQL lexical rules.
   * @see <a href="https://drill.apache.org/docs/lexical-structure/#identifier">
   * identifier documentation</a>
   * @param header the header name to validate
   */

  // TODO: Replace with existing code, if any.
  private String validateSymbol(String header) {
    header = header.trim();

    // To avoid unnecessary query failures, just make up a column name
    // if the name is missing or all blanks.

    if (header.isEmpty()) {
      return "column_" + (headers.size() + 1);
    }
    if (! Character.isAlphabetic(header.charAt(0))) {
      return rewriteHeader(header);
    }
    for (int i = 1; i < header.length(); i++) {
      char ch = header.charAt(i);
      if (! Character.isAlphabetic(ch)  &&
          ! Character.isDigit(ch)  &&  ch != '_') {
        return rewriteHeader(header);
      }
    }
    return header;
  }

  /**
   * Given an invalid header, rewrite it to replace illegal characters
   * with valid ones. The header won't be what the user specified,
   * but it will be a valid SQL identifier. This solution avoids failing
   * queries due to corrupted of invalid header data.
   *
   * @param header the original header
   * @return the rewritten header, valid for SQL
   */

  private String rewriteHeader(String header) {
    StringBuilder buf = new StringBuilder();

    // If starts with non-alphabetic, can't map the character to
    // underscore, so just tack on a prefix.

    char ch = header.charAt(0);
    if (Character.isAlphabetic(ch)) {
      buf.append(ch);
    } else {
      if (Character.isDigit(ch)) {
          buf.append("col_");
          buf.append(ch);
      } else {

        // For the strange case of only one character, format
        // the same as an empty header.

        if (header.length() == 1) {
          return "column_" + (headers.size() + 1);
        } else {
          buf.append("col_");
        }
      }
    }

    // Convert all remaining invalid characters to underscores

    for (int i = 1; i < header.length(); i++) {
      ch = header.charAt(i);
      if (Character.isAlphabetic(ch)  ||
          Character.isDigit(ch)  ||  ch == '_') {
        buf.append(ch);
      } else {
        buf.append("_");
      }
    }
    return buf.toString();
  }

  @Override
  public void append(byte data) {
    try {
      currentField.put(data);
    } catch (BufferOverflowException e) {
      throw new HeaderError(headers.size(), "Column exceeds maximum length of " + MAX_HEADER_LEN);
    }
  }

  @Override
  public void finishRecord() {
    if (headers.isEmpty()) {
      throw new HeaderError("The file must define at least one header.");
    }

    // Force headers to be unique.

    Set<String> idents = new HashSet<String>();
    for (int i = 0; i < headers.size(); i++) {
      String header = headers.get(i);
      String key = header.toLowerCase();

      // Is the header a duplicate?

      if (idents.contains(key)) {

        // Make header unique by appending a suffix.
        // This loop must end because we have a finite
        // number of headers.
        // The original column is assumed to be "1", so
        // the first duplicate is "2", and so on.
        // Note that this will map columns of the form:
        // "col,col,col_2,col_2_2" to
        // "col", "col_2", "col_2_2", "col_2_2_2".
        // No mapping scheme is perfect...

        for (int l = 2;  ; l++) {
          String rewritten = header + "_" + l;
          key = rewritten.toLowerCase();
          if (! idents.contains(key)) {
            headers.set(i, rewritten);
            break;
          }
        }
      }
      idents.add(key);
    }
  }

  @Override
  public void startRecord() { }

  public String[] getHeaders() {

    // Just return the headers: any needed checks were done in
    // finishRecord()

    String array[] = new String[headers.size()];
    return headers.toArray(array);
  }

  // Not used.
  @Override
  public long getRecordCount() { return 0; }

  @Override
  public boolean isFull() { return false; }
}
