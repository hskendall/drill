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
package org.apache.drill.exec.store.easy.json.loader.mongo;

import org.apache.drill.exec.store.easy.json.parser.ErrorFactory;
import org.apache.drill.exec.store.easy.json.parser.TokenIterator;

import com.fasterxml.jackson.core.JsonToken;

/**
 * Parsers a Mongo extended type of the form:<pre><code>
 * { $type: value }</code></pre>
 */
public class MongoSimpleValueParser extends BaseMongoValueParser {

  private final MongoValueListener listener;
  private final String typeName;

  public MongoSimpleValueParser(String typeName, MongoValueListener listener, ErrorFactory errorFactory) {
    super(errorFactory);
    this.listener = listener;
    this.typeName = typeName;
  }

  @Override
  protected String typeName() { return typeName; }

  @Override
  public void parse(TokenIterator tokenizer) {

    JsonToken token = tokenizer.requireNext();

    // Null: assume the value is null
    // (Extension to extended types)
    if (token == JsonToken.VALUE_NULL) {
      listener.onValue(token, tokenizer);
      return;
    }

    // Value is a scalar, assume "Relaxed format"
    // (Extension to extended types: allow strings.)
    if (token.isScalarValue()) {
      listener.onValue(token, tokenizer);
      return;
    }

    // Must be an object
    if (token != JsonToken.START_OBJECT) {
      throw syntaxError("^{\"%s\": scalar}");
    }

    // Field name must be correct
    token = tokenizer.requireNext();
    if (token != JsonToken.FIELD_NAME ||
        !tokenizer.textValue().equals(typeName)) {
      throw syntaxError("{ ^\"%s\": scalar}");
    }

    // Value must be a scalar
    token = tokenizer.requireNext();
    if (!token.isScalarValue()) {
      throw syntaxError("{\"%s\": ^scalar }");
    }
    listener.onValue(token, tokenizer);

    // Must be no other fields
    token = tokenizer.requireNext();
    if (token != JsonToken.END_OBJECT) {
      throw syntaxError("{\"%s\": scalar ^}");
    }
  }
}