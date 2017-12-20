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
package org.apache.drill.exec.store.easy.json.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.drill.common.types.TypeProtos.DataMode;
import org.apache.drill.common.types.TypeProtos.MajorType;
import org.apache.drill.common.types.TypeProtos.MinorType;
import org.apache.drill.exec.record.MaterializedField;
import org.apache.drill.exec.record.metadata.ColumnMetadata;
import org.apache.drill.exec.store.easy.json.JsonLoader;
import org.apache.drill.exec.store.easy.json.parser.JsonLoaderImpl.JsonElementParser;

import com.fasterxml.jackson.core.JsonToken;

abstract class AbstractParser implements JsonElementParser {
  public static abstract class LeafParser extends AbstractParser {

    public LeafParser(JsonLoaderImpl.JsonElementParser parent, String fieldName) {
      super(parent, fieldName);
    }
  }

  /**
   * Parse and ignore an unprojected value. The parsing just "free wheels", we
   * care only about matching brackets, but not about other details.
   */

  protected static class DummyValueParser extends LeafParser {

    public DummyValueParser(JsonLoaderImpl.JsonElementParser parent, String fieldName) {
      super(parent, fieldName);
    }

    @Override
    public boolean parse() {
      JsonToken token = loader.tokenizer.requireNext();
      switch (token) {
      case START_ARRAY:
      case START_OBJECT:
        parseTail();
        break;

      case VALUE_NULL:
      case VALUE_EMBEDDED_OBJECT:
      case VALUE_FALSE:
      case VALUE_TRUE:
      case VALUE_NUMBER_FLOAT:
      case VALUE_NUMBER_INT:
      case VALUE_STRING:
        break;

      default:
        throw loader.syntaxError(token);
      }
      return true;
    }

    public void parseTail() {

      // Parse (field: value)* }

      for (;;) {
        JsonToken token = loader.tokenizer.requireNext();
        switch (token) {

        // Not exactly precise, but the JSON parser handles the
        // details.

        case END_OBJECT:
        case END_ARRAY:
          return;

        case START_OBJECT:
        case START_ARRAY:
          parseTail(); // Recursively ignore objects
          break;

        default:
          break; // Ignore all else
        }
      }
    }

    @Override
    public ColumnMetadata schema() { return null; }
  }

  protected final JsonLoaderImpl loader;
  private final JsonElementParser parent;
  private final String key;

  public AbstractParser(JsonLoaderImpl loader, String fieldName) {
    this.loader = loader;
    this.parent = null;
    this.key = fieldName;
  }

  public AbstractParser(JsonElementParser parent, String fieldName) {
    this.parent = parent;
    this.loader = (JsonLoaderImpl) parent.loader();
    this.key = fieldName;
  }

  @Override
  public String key() { return key; }

  @Override
  public JsonElementParser parent() { return parent; }

  @Override
  public JsonLoader loader() { return loader; }

  @Override
  public boolean isAnonymous() { return false; }

  protected MaterializedField schemaFor(String key,
      MinorType type, DataMode mode) {
    return MaterializedField.create(key,
        MajorType.newBuilder()
          .setMinorType(type)
          .setMode(mode)
          .build());
  }

  protected List<String> makePath() {
    JsonElementParser parser = this;
    List<String> path = new ArrayList<>();
    while (parser != null) {
      if (! parser.isAnonymous()) {
        path.add(parser.key());
      }
      parser = parser.parent();
    }
    Collections.reverse(path);
    return path;
  }

  public String fullName() {
    StringBuilder buf = new StringBuilder();
    int count = 0;
    for (String seg : makePath()) {
      if (count > 0) {
        buf.append(".");
      }
      buf.append("`");
      buf.append(seg);
      buf.append("`");
      count++;
    }
    return buf.toString();
  }

  @Override
  public String toString() {
    StringBuilder buf = new StringBuilder()
      .append("[")
      .append(getClass().getSimpleName())
      .append(" name=")
      .append(fullName());
    if (schema() != null) {
      buf.append(", schema=")
        .append(schema().toString());
    }
    return buf.append("]").toString();
  }
}