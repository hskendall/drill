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

import org.apache.drill.exec.store.easy.json.parser.ElementParser;
import org.apache.drill.exec.store.easy.json.parser.ErrorFactory;

public abstract class BaseMongoValueParser implements ElementParser {

  private final ErrorFactory errorFactory;

  public BaseMongoValueParser(ErrorFactory errorFactory) {
    this.errorFactory = errorFactory;
  }

  protected abstract String typeName();

  protected RuntimeException syntaxError(String syntax) {
    return errorFactory.structureError(
        String.format("Expected <%s> for extended type %s.",
            String.format(syntax, typeName()), typeName()));
  }
}
