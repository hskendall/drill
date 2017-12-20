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

import java.util.List;
import java.util.Map;

import org.apache.drill.common.map.CaseInsensitiveMap;
import org.apache.drill.common.types.TypeProtos.DataMode;
import org.apache.drill.common.types.TypeProtos.MajorType;
import org.apache.drill.common.types.TypeProtos.MinorType;
import org.apache.drill.exec.record.metadata.ColumnMetadata;
import org.apache.drill.exec.record.metadata.ProjectionType;
import org.apache.drill.exec.store.easy.json.parser.JsonLoaderImpl.JsonElementParser;
import org.apache.drill.exec.vector.accessor.ArrayWriter;
import org.apache.drill.exec.vector.accessor.ObjectWriter;
import org.apache.drill.exec.vector.accessor.ScalarWriter;
import org.apache.drill.exec.vector.accessor.TupleWriter;

import com.fasterxml.jackson.core.JsonToken;

/**
 * Parses { name : value ... }
 * <p>
 * Creates a map of known fields. Each time a field is parsed,
 * looks up the field in the map. If not found, the value is "sniffed"
 * to determine its type, and a matching state and vector writer created.
 * Thereafter, the previous state is reused. The states ensure that the
 * correct token appears for each subsequent value, causing type errors
 * to be reported as syntax errors rather than as cryptic internal errors.
 * <p>
 * As it turns out, most of the semantic action occurs at the tuple level:
 * that is where fields are defined, types inferred, and projection is
 * computed.
 *
 * <h4>Nulls</h4>
 *
 * Much code here deals with null types, especially leading nulls, leading
 * empty arrays, and so on. The object parser creates a parser for each
 * value; a parser which "does the right thing" based on the data type.
 * For example, for a Boolean, the parser recognizes <tt>true</tt>,
 * <tt>false</tt> and <tt>null</tt>.
 * <p>
 * But what happens if the first value for a field is <tt>null</tt>? We
 * don't know what kind of parser to create because we don't have a schema.
 * Instead, we have to create a temporary placeholder parser that will consume
 * nulls, waiting for a real type to show itself. Once that type appears, the
 * null parser can replace itself with the correct form. Each vector's
 * "fill empties" logic will back-fill the newly created vector with nulls
 * for prior rows.
 * <p>
 * Two null parsers are needed: one when we see an empty list, and one for
 * when we only see <tt>null</tt>. The one for <tt>null<tt> must morph into
 * the one for empty lists if we see:<br>
 * <tt>{a: null} {a: [ ]  }</tt><br>
 * <p>
 * If we get all the way through the batch, but have still not seen a type,
 * then we have to guess. A prototype type system can tell us, otherwise we
 * guess <tt>VARCHAR</tt>. (<tt>VARCHAR</tt> is the right choice for all-text
 * mode, it is as good a guess as any for other cases.)
 *
 * <h4>Projection List Hints</h4>
 *
 * To help, we consult the projection list, if any, for a column. If the
 * projection is of the form <tt>a[0]</tt>, we know the column had better
 * be an array. Similarly, if the projection list has <tt>b.c</tt>, then
 * <tt>b</tt> had better be an object.
 *
 * <h4>Array Handling</h4>
 *
 * The code here handles arrays in two ways. JSON normally uses the
 * <tt>LIST</tt> type. But, that can be expensive if lists are
 * well-behaved. So, the code here also implements arrays using the
 * classic <tt>REPEATED</tt> types. The repeated type option is disabled
 * by default. It can be enabled, for efficiency, if Drill ever supports
 * a JSON schema. If an array is well-behaved, mark that column as able
 * to use a repeated type.
 */

class ObjectParser extends ContainerParser {

  /**
   * Represents a rather odd state: we have seen a value of one or more nulls,
   * but we have not yet seen a value that would give us a type. This state
   * acts as a placeholder; waiting to see the type, at which point it replaces
   * itself with the actual typed state. If a batch completes with only nulls
   * for this field, then the field becomes a Text field and all values in
   * subsequent batches will be read in "text mode" for that one field in
   * order to avoid a schema change.
   * <p>
   * Note what this state does <i>not</i> do: it does not create a nullable
   * int field per Drill's normal (if less than ideal) semantics. First, JSON
   * <b>never</b> produces an int field, so nullable int is less than ideal.
   * Second, nullable int has no basis in reality and so is a poor choice
   * on that basis.
   */

  protected static class NullTypeParser extends AbstractParser.LeafParser implements JsonLoaderImpl.NullTypeMarker {

    private final ObjectParser objectParser;

    public NullTypeParser(ObjectParser parentState, String fieldName) {
      super(parentState, fieldName);
      this.objectParser = parentState;
      loader.addNullMarker(this);
    }

    @Override
    public boolean parse() {
      JsonToken token = loader.tokenizer.requireNext();

      // If value is the null token, we still don't know the type.

      if (token == JsonToken.VALUE_NULL) {
        return true;
      }

      // Replace this parser with a typed parser.

      loader.tokenizer.unget(token);
      return resolve(objectParser.detectValueParser(key())).parse();
    }

    @Override
    public void forceResolution() {
      JsonElementParser newParser = objectParser.inferMemberFromHint(makePath());
      if (newParser != null) {
        JsonLoaderImpl.logger.info("Using hints to determine type of JSON field {}. " +
            " Found type {}",
            fullName(), newParser.schema().type().name());
      } else {
        JsonLoaderImpl.logger.warn("Ambiguous type! JSON field {}" +
            " contains all nulls. Assuming text mode.",
            fullName());
        newParser = new ScalarParser.TextParser(parent(), key(),
            objectParser.newWriter(key(), MinorType.VARCHAR, DataMode.OPTIONAL).scalar());
      }
      resolve(newParser);
    }

    private JsonElementParser resolve(JsonElementParser newParser) {
      objectParser.replaceChild(key(), newParser);
      loader.removeNullMarker(this);
      return newParser;
    }

    @Override
    public ColumnMetadata schema() { return null; }
  }

  /**
   * Parses: [] | null [ ... ]
   * <p>
   * This state remains in effect as long as the input contains empty arrays or
   * null values. However, once the array contains a non-empty array, detects the
   * type of the array based on this value and replaces this state with the
   * result array parser state.
   * <p>
   * If at the end of a batch, no non-empty array was seen, assumes that the
   * array, when seen, will be an array of scalars, and replaces this state with
   * a text array (as in all-text mode.)
   */

  protected static class NullArrayParser extends AbstractParser.LeafParser implements JsonLoaderImpl.NullTypeMarker {

    private final ObjectParser objectParser;

    public NullArrayParser(ObjectParser parentState, String key) {
      super(parentState, key);
      this.objectParser = parentState;
      loader.addNullMarker(this);
    }

    /**
     * Parse null | [] | [ some_token
     */

    @Override
    public boolean parse() {
      JsonToken startToken = loader.tokenizer.requireNext();
      if (startToken == JsonToken.VALUE_NULL) {
        return true;
      }
      if (startToken != JsonToken.START_ARRAY) {
        throw loader.syntaxError(startToken);
      }
      JsonToken valueToken = loader.tokenizer.requireNext();
      if (valueToken == JsonToken.END_ARRAY) {
        return true;
      }
      loader.tokenizer.unget(valueToken);
      JsonElementParser newState = resolve(objectParser.detectArrayParser(key()));
      loader.tokenizer.unget(startToken);
      return newState.parse();
    }

    @Override
    public void forceResolution() {
      ArrayParser newParser = objectParser.inferScalarArrayFromHint(makePath());
      if (newParser != null) {
        JsonLoaderImpl.logger.info("Using hints to determine type of JSON array {}. " +
            " Found type {}",
            fullName(), newParser.writer.scalar().schema().type().name());
      } else {
        JsonLoaderImpl.logger.warn("Ambiguous type! JSON array {}" +
            " contains all nulls. Assuming text mode.",
            fullName());
        ArrayWriter arrayWriter = objectParser.newWriter(key(), MinorType.VARCHAR, DataMode.REPEATED).array();
        JsonElementParser elementParser = new ScalarParser.TextParser(objectParser, key(), arrayWriter.scalar());
        newParser = new ArrayParser.ScalarArrayParser(objectParser, key(), arrayWriter, elementParser);
      }
      resolve(newParser);
    }

    private JsonElementParser resolve(JsonElementParser newParser) {
      objectParser.replaceChild(key(), newParser);
      loader.removeNullMarker(this);
      return newParser;
    }

    @Override
    public ColumnMetadata schema() { return null; }
  }

  private final TupleWriter writer;
  private final Map<String, JsonElementParser> members = CaseInsensitiveMap.newHashMap();

  public ObjectParser(JsonLoaderImpl loader, TupleWriter writer) {
    super(loader, JsonLoaderImpl.ROOT_NAME);
    this.writer = writer;
  }

  public ObjectParser(JsonElementParser parent, String fieldName, TupleWriter writer) {
    super(parent, fieldName);
    this.writer = writer;
  }

  @Override
  public boolean parse() {
    JsonToken token = loader.tokenizer.next();
    if (token == null) {
      return false; // EOF
    }
    switch (token) {
    case NOT_AVAILABLE:
      return false; // Should never occur

    case VALUE_NULL:
      return true; // Null, same as omitting the object

    case START_OBJECT:
      break; // Start the object

    default:
      throw loader.syntaxError(token); // Nothing else is valid
    }

    // Parse (field: value)* }

    for (;;) {
      token = loader.tokenizer.requireNext();
      switch (token) {
      case END_OBJECT:
        return true;

      case FIELD_NAME:
        parseMember();
        break;

      default:
        throw loader.syntaxError(token);
      }
    }
  }

  /**
   * Parse a field. Two cases. First, this is a field we've already seen. If so,
   * look up the parser for that field and use it. If this is the first time we've
   * seen the field, "sniff" tokens to determine field type, create a parser,
   * then parse.
   */

  private void parseMember() {
    final String key = loader.tokenizer.textValue().trim();
    JsonElementParser fieldState = members.get(key);
    if (fieldState == null) {
      fieldState = detectValueParser(key);
      members.put(key, fieldState);
    }
    fieldState.parse();
  }

  /**
   * If the column is not projected, create a dummy parser to "free wheel"
   * over the value. Otherwise,
   * look ahead a token or two to determine the the type of the field.
   * Then the caller will backtrack to parse the field.
   *
   * @param key name of the field
   * @return parser for the field
   */

  JsonElementParser detectValueParser(final String key) {
    if (key.isEmpty()) {
      throw loader.syntaxError("Drill does not allow empty keys in JSON key/value pairs");
    }
    ProjectionType projType = writer.projectionType(key);
    if (projType == ProjectionType.UNPROJECTED) {
      return new AbstractParser.DummyValueParser(this, key);
    }

    // For other types of projection, the projection
    // mechanism will catch conflicts.

    JsonToken token = loader.tokenizer.requireNext();
    JsonElementParser valueParser;
    switch (token) {
    case START_ARRAY:
      valueParser = detectArrayParser(key);
      break;

    case START_OBJECT:
      valueParser = objectParser(key);
      break;

    case VALUE_NULL:
      valueParser = inferParser(key, projType);

      // Use the projection type as a hint. Note that this is not a panacea,
      // and may even lead to seemingly-random behavior. In one query, we can
      // pick out arrays or array lists when confronted with a list of nulls
      // (because we use the projection hint), but in other queries, the user
      // will get a schema change exception (because we could not guess the type
      // because the projection list differed.) It is not clear if such behavior
      // is a bug or feature...

      break;

    default:
      valueParser = typedScalar(token, key);
    }
    loader.tokenizer.unget(token);
    return valueParser;
  }

  private ObjectParser objectParser(String key) {
    return new ObjectParser(this, key,
        newWriter(key, MinorType.MAP, DataMode.REQUIRED).tuple());
  }

  private JsonElementParser typedScalar(JsonToken token, String key) {
    MinorType type = typeForToken(token);
    ScalarWriter scalarWriter = newWriter(key, type, DataMode.OPTIONAL).scalar();
    return scalarParserForToken(token, key, scalarWriter);
  }

  protected ArrayParser objectArrayParser(String key) {
    ArrayWriter arrayWriter = newWriter(key, MinorType.MAP, DataMode.REPEATED).array();
    return new ArrayParser.ObjectArrayParser(this, key, arrayWriter,
        new ObjectParser(this, key, arrayWriter.tuple()));
  }

  /**
   * Detect the type of an array member by "sniffing" the first element.
   * Creates a simple repeated type if requested and possible. Else, creates
   * an array depending on the array contents. May have to look ahead
   * multiple tokens if the array is multi-dimensional.
   * <p>
   * Note that repeated types can only appear directly inside maps; they
   * cannot be used inside a list.
   *
   * @param parent the object parser that will hold the array element
   * @param key field name
   * @return the parse state for this array
   */

  protected JsonElementParser detectArrayParser(String key) {

    if (! loader.options.useArrayTypes) {
      return listParser(key);
    }

    // The client would prefer to create a repeated type for JSON arrays.
    // Detect the type of that array. Or, detect that the the first value
    // dictates that a list be used because this is a nested list, contains
    // nulls, etc.

    JsonToken token = loader.tokenizer.peek();
    switch (token) {
    case START_ARRAY:
    case VALUE_NULL:

      // Can't use an array, must use a list since this is nested
      // or null.

      return listParser(key);

    case END_ARRAY:

      // Don't know what this is. Defer judgment until later.

      return new ObjectParser.NullArrayParser(this, key);

    case START_OBJECT:
      return objectArrayParser(key);

    default:
      return detectScalarArrayParser(token, key);
    }
  }

  private JsonElementParser listParser(String key) {
    return new ListParser(this, key, newWriter(key, MinorType.LIST, DataMode.OPTIONAL).array());
  }

  /**
   * Create a parser for a scalar array implemented as a repeated type.
   *
   * @param parent
   * @param token
   * @param key
   * @return
   */

  private JsonElementParser detectScalarArrayParser(JsonToken token, String key) {
    MinorType type = typeForToken(token);
    ArrayWriter arrayWriter = newWriter(key, type, DataMode.REPEATED).array();
    JsonElementParser elementState = scalarParserForToken(token, key, arrayWriter.scalar());
    return new ArrayParser.ScalarArrayParser(this, key, arrayWriter, elementState);
  }

  /**
   * The field type has been determined. Build a writer for that field given
   * the field name, type and mode (optional or repeated). The result set loader
   * that backs this JSON loader will handle field projection, returning a dummy
   * parser if the field is not projected.
   *
   * @param key name of the field
   * @param type Drill data type
   * @param mode cardinality: either Optional (for map fields) or Repeated
   * (for array members). (JSON does not allow Required fields)
   * @return the object writer for the field, which may be a tuple, scalar
   * or array writer, depending on type
   */

  protected ObjectWriter newWriter(String key,
        MinorType type, DataMode mode) {
    int index = writer.addColumn(schemaFor(key, type, mode));
    return writer.column(index);
  }

  @Override
  protected void replaceChild(String key, JsonElementParser newParser) {
    assert members.containsKey(key);
    members.put(key, newParser);
  }

  /**
   * The JSON itself provides a null value, so we can't determine the column
   * type. Use some additional inference methods. (These are experimental. What
   * is really needed is an up-front schema.)
   *
   * @param key
   * @param projType
   * @return
   */

  private JsonElementParser inferParser(String key, ProjectionType projType) {
    JsonElementParser valueParser = inferFromType(key);
    if (valueParser == null) {
      valueParser = inferFromProjection(key, projType);
    }
    if (valueParser == null) {
      valueParser = new ObjectParser.NullTypeParser(this, key);
    }
    return valueParser;
  }

  private JsonElementParser inferFromProjection(String key, ProjectionType projType) {
    switch (projType) {
    case ARRAY:
      return new ObjectParser.NullArrayParser(this, key);

    case TUPLE:
      return objectParser(key);

    case TUPLE_ARRAY:

      // Note: Drill syntax does not support this
      // case yet.

      return objectArrayParser(key);

    default:
      return null;
    }
  }

  /**
   * Experimental feature. If a type hint mechanism is provided, use that
   * to resolve the type of null columns. Really, this should be done earlier,
   * and we should ensure that the suggested type matches the actual JSON
   * type. But, for now, we consult the hint only if we'd otherwise not know
   * the type.
   *
   * @param key
   * @return
   */

  private JsonElementParser inferFromType(String key) {
    if (! loader.options.detectTypeEarly) {
      return null;
    }
    List<String> path = makePath();
    path.add(key);
    return inferMemberFromHint(path);
  }

  private JsonElementParser inferMemberFromHint(List<String> path) {
    if (loader.options.typeNegotiator == null) {
      return null;
    }
    MajorType type = loader.options.typeNegotiator.typeOf(path);
    if (type == null) {
      return null;
    }
    DataMode mode = type.getMode();
    String key = path.get(path.size() - 1);
    MinorType dataType = type.getMinorType();
    switch (dataType) {
    case MAP:
      if (mode == DataMode.REPEATED) {
        return objectArrayParser(key);
      } else {
        return objectParser(key);
      }
    case LIST:
      return listParser(key);

    default:
    }
    if (mode == DataMode.REQUIRED) {
      JsonLoaderImpl.logger.warn("Cardinality conflict! JSON source {}, column {} " +
          "has a hint of cardinality {}, but JSON requires OPTIONAL. " +
          "Hint ignored.",
          loader.options.context, fullName(), mode.name());
      mode = DataMode.OPTIONAL;
    }
    ObjectWriter memberWriter = newWriter(key, dataType, mode);
    if (mode == DataMode.OPTIONAL) {
      return scalarParserForType(dataType, key, memberWriter.scalar());
    } else {
      ArrayWriter arrayWriter = memberWriter.array();
      JsonElementParser elementParser = scalarParserForType(type.getMinorType(), key, arrayWriter.scalar());
      return new ArrayParser.ScalarArrayParser(this, key, arrayWriter, elementParser);
    }
  }

  private ArrayParser inferScalarArrayFromHint(List<String> path) {
    if (loader.options.typeNegotiator == null) {
      return null;
    }
    MajorType type = loader.options.typeNegotiator.typeOf(path);
    if (type == null) {
      return null;
    }
    if (type.getMode() != DataMode.REPEATED) {
      JsonLoaderImpl.logger.warn("Cardinality conflict! JSON source {}, array {} " +
          "has a hint of cardinality {}, but JSON requires REPEATED. " +
          "Hint ignored.",
          loader.options.context, fullName(), type.getMode().name());
    }
    String key = path.get(path.size() - 1);
    ArrayWriter arrayWriter = newWriter(key, type.getMinorType(), DataMode.REPEATED).array();
    JsonElementParser elementState = scalarParserForType(type.getMinorType(), key, arrayWriter.scalar());
    return new ArrayParser.ScalarArrayParser(this, key, arrayWriter, elementState);
  }

  @Override
  public ColumnMetadata schema() { return writer.schema(); }
}