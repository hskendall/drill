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

public class ValueDef {

  /**
   * Description of JSON types as derived from JSON tokens.
   */
  public enum JsonType {
    OBJECT, NULL, EMPTY, BOOLEAN,
    INTEGER, FLOAT, STRING, EMBEDDED_OBJECT;

    public boolean isObject() { return this == JsonType.OBJECT; }

    public boolean isUnknown() {
      return this == JsonType.NULL || this == JsonType.EMPTY;
    }

    public boolean isScalar() {
      return !isObject() && !isUnknown();
    }
  }

  private final int arrayDims;
  private final JsonType type;

  public ValueDef(JsonType type, int dims) {
    this.type = type;
    this.arrayDims = dims;
  }

  public JsonType type() { return type; }
  public int dimensions() { return arrayDims; }
  public boolean isArray() { return arrayDims > 0; }

  @Override
  public String toString() {
    String result = type.name();
    for (int i = 0; i < arrayDims; i++) {
      result += "[]";
    }
    return result;
  }
}
