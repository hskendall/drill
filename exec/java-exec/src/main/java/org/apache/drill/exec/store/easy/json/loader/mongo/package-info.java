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

/**
 * Provides parsing for Mongo extended types which are generally of the form
 * <code>{ "$type": value }</code>. Supports both
 * <a href="https://docs.mongodb.com/manual/reference/mongodb-extended-json-v1/">
 * V1</a> and
 * <a href="https://docs.mongodb.com/manual/reference/mongodb-extended-json/">
 * V2</a> names. Supports both the Canonical and Relaxed formats.
 * <p>
 * Does not support all types as some appear internal to Mongo. Supported
 * types:
 * <ul>
 * <li><a href="https://docs.mongodb.com/manual/reference/mongodb-extended-json/#bson.Array>
 * Array</a></li>
 * <li><a href="https://docs.mongodb.com/manual/reference/mongodb-extended-json/#bson.Binary">
 * Binary</a><, translated to a Drill {@code BIT}./li>
 * <li><a href="https://docs.mongodb.com/manual/reference/mongodb-extended-json/#bson.Date">
 * Date</a>, translated to a Drill {@code TIMESTAMP}. However, see the caveat for Timestamp
 * below.</li>
 * <li><a href="https://docs.mongodb.com/manual/reference/mongodb-extended-json-v1/#numberdecimal">
 * Decimal (V1)</a>, translated to a Drill {@code VARDECIMAL}.</li>
 * <li><a href="https://docs.mongodb.com/manual/reference/mongodb-extended-json/#bson.Decimal128">
 * Decimal128</a>, translated to a Drill {@code VARDECIMAL}, but limited to the
 * supported DECIMAL range.</li>
 * <li><a href="https://docs.mongodb.com/manual/reference/mongodb-extended-json/#bson.Document">
 * Document</a> which is translated to a Drill {@code MAP}.</li>
 * <li><a href="https://docs.mongodb.com/manual/reference/mongodb-extended-json/#bson.Double">
 * Double, translated to a Drill {@code FLOAT8}.</a></li>
 * <li><a href="https://docs.mongodb.com/manual/reference/mongodb-extended-json/#bson.Int64">
 * Int64</a>, translated to a Drill {@code BIGINT}.</li>
 * <li><a href="https://docs.mongodb.com/manual/reference/mongodb-extended-json/#bson.Int32">
 * Int32</a>, translated to a Drill {@code INT}.</li>
 * <li><a href="https://docs.mongodb.com/manual/reference/mongodb-extended-json/#bson.Timestamp">
 * Timestamp</a>, translated to a Drill {@code TIMESTAMP}. However Drill's times are
 * in the server local time. The UTC timestamp in Mongo will be redesignated as (but not
 * converted to) a local
 * time with the same offset since the epoch. That is, times will be implicitly shifted
 * by the offset of the local timezone. This is a known limitation of Drill itself.</li>
 * </ul>
 * Unsupported types:
 * <ul>
 * <li><a href="https://docs.mongodb.com/manual/reference/mongodb-extended-json/#bson.MaxKey">
 * MaxKey</a></li>
 * <li><a href="https://docs.mongodb.com/manual/reference/mongodb-extended-json/#bson.MinKey">
 * MinKey</a></li>
 * <li><a href="https://docs.mongodb.com/manual/reference/mongodb-extended-json/#bson.Regular-Expression">
 * Regular Expression</a></li>
 * <li><a href="https://docs.mongodb.com/manual/reference/mongodb-extended-json-v1/#bson.data_ref">
 * Data Ref (V1)</a></li>
 * <li><a href="https://docs.mongodb.com/manual/reference/mongodb-extended-json-v1/#bson.data_undefined">
 * Undefined (V1)</a>, since Drill has no untyped {@code NULL} value.</li>
 * </ul>
 * <p>
 * The unsupported types appear more for commands and queries rather than data. They do
 * not represent a Drill type. If they appear in data, they will be translated to a
 * Drill map.
 * <p>
 * Question: ObjectID
 * <p>
 * Drill extends the extended types to allow null values in the usual way. Drill
 * accepts normal "un-extended" JSON in the same file, but doing so can lead to ambiguities
 * (see below.)
 * <p>
 * If used with a provided schema, then:
 * <ul>
 * <li>If the first field is in canonical format (with a type), then the extended
 * type must agree with the provided type, or an error will occur.</li>
 * <li>If the first field is in relaxed format, or is {@code null}, then the
 * provided schema will force the given type as though the data were in
 * canonical format.</li>
 * </ul>
 *
 * <h4>Ambiguities</h4>
 *
 * Extended JSON is subject to the same ambiguities as normal JSON. If Drill sees a
 * field in relaxed mode before extended mode, Drill will use its normal type inference
 * rules. Thus, if the first field presents as {@code a: "30"}, Drill will infer the
 * type as string, even if a later field presents as <code>a: { "numberInt": 30 }</code>.
 * To avoid ambiguities, either use only the canonical format, or use a provided
 * schema.
 *
 * <h4>Implementation</h4>
 *
 * Extended types disabled by default and must be enabled using the
 * {@code store.json.extended_types} system/session option (
 * {@link org.apache.drill.exec.ExecConstants#JSON_EXTENDED_TYPES_KEY}).
 * <p>
 * Extended types are implemented via a field factory. The field factory builds the
 * structure needed each time the JSON structure parser sees a new field. For extended types,
 * the field factory looks ahead to detect an extended type, specifically for the pattern
 * <code>{ "$type":</code>. If the pattern is found, and the name is one of the supported
 * type names, then the factory creates a parser to accept the enhanced type in either the
 * canonical or relaxed forms.
 * <p>
 * Each field is represented by a Mongo-specific parser along with an associated value
 * listener. The implementation does not reify the object structure; that structure is
 * consumed by the field parser itself. The value listener receives value tokens as if
 * the data were in relaxed format.
 *
 *
 * @see org.apache.drill.exec.vector.complex.fn.VectorOutput.MapVectorOutput MapVectorOutput
 * for an older implementation
*/
package org.apache.drill.exec.store.easy.json.loader.mongo;
