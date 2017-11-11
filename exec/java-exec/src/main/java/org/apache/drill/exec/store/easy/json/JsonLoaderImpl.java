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
package org.apache.drill.exec.store.easy.json;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.drill.common.exceptions.UserException;
import org.apache.drill.common.types.TypeProtos.DataMode;
import org.apache.drill.common.types.TypeProtos.MajorType;
import org.apache.drill.common.types.TypeProtos.MinorType;
import org.apache.drill.exec.physical.rowSet.RowSetLoader;
import org.apache.drill.exec.record.MaterializedField;
import org.apache.drill.exec.vector.accessor.ArrayWriter;
import org.apache.drill.exec.vector.accessor.ObjectWriter;
import org.apache.drill.exec.vector.accessor.ScalarWriter;
import org.apache.drill.exec.vector.accessor.TupleWriter;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Revised JSON loader that is based on the
 * {@link ResultSetLoader} abstraction. Represents the JSON parse as a
 * set of parse states, each of which represents some point in the traversal
 * of the JSON syntax. Parse nodes also handle options such as all text mode
 * vs. type-specific parsing.
 * <p>
 * Actual parsing is handled by the Jackson parser class. The input source is
 * represented as an {@link InputStream} so that this mechanism can parse files
 * or strings.
 * <p>
 * Fields are discovered on the fly. Types are inferred from the first JSON token
 * for a field. Type inference is less than perfect: it cannot handle type changes
 * such as first seeing 10, then 12.5, or first seeing "100", then 200.
 *
 * <h4>Comparison to Original JSON Reader</h4>
 *
 * This class replaces the {@link JsonReader} class used in Drill versions 1.12
 * and before. Compared with the previous version, this implementation:
 * <ul>
 * <li>Materializes parse states as classes rather than as methods and
 * boolean flags as in the prior version.</li>
 * <li>Reports errors as {@link UserException} objects, complete with context
 * information, rather than as generic Java exception as in the prior version.</li>
 * <li>Moves parse options into a separate {@link JsonOptions} class.</li>
 * <li>Iteration protocol is simpler: simply call {@link #next()} until it returns
 * <tt>false</tt>. Errors are reported out-of-band via an exception.</li>
 * <li>The result set loader abstraction is perfectly happy with an empty schema.
 * For this reason, this version (unlike the original) does not make up a dummy
 * column if the schema would otherwise be empty.</li>
 * <li>Projection pushdown is handled by the {@link ResultSetLoader} rather than
 * the JSON loader. This class always creates a vector writer, but the result set
 * loader will return a dummy (no-op) writer for non-projected columns.</li>
 * <li>The result of the above change is that this parser will validate JSON more
 * carefully than the previous version did.</li>
 * <li>Writes boolean values as SmallInt values, rather than as bits in the
 * prior version.</li>
 * <li>Runs of null values result in a "deferred null state" that patiently
 * waits for an actual value token to appear, and only then "realizes" a parse
 * state for that type.</li>
 * </ul>
 */

public class JsonLoaderImpl implements JsonLoader {

  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(JsonLoaderImpl.class);

  public static class JsonOptions {
    public String context;
    public boolean allTextMode;
    public boolean extended = true;
    public boolean readNumbersAsDouble;

    /**
     * Allow Infinity and NaN for float values.
     */

    public boolean allowNanInf;

    /**
     * Describes whether or not this reader can unwrap a single root array record
     * and treat it like a set of distinct records.
     */
    public boolean skipOuterList = true;
  }

  interface ParseState {
    boolean parse();
  }

  interface NullTypeMarker {
    void realize();
  }

  /**
   * Parses [ value, value ... ]<br>
   * Where value is a scalar. The states for each value ensure that the
   * types are consistent (Drill does not support hetrogenous arrays.)
   */

  protected class ScalarArrayState implements ParseState {

    @SuppressWarnings("unused")
    private final ArrayWriter writer;
    private final ParseState scalarState;

    public ScalarArrayState(ArrayWriter writer,
        ParseState scalarState) {
      this.writer = writer;
      this.scalarState = scalarState;
    }

    @Override
    public boolean parse() {
      JsonToken token = tokenizer.requireNext();
      switch (token) {
      case VALUE_NULL:
        return true;
      case START_ARRAY:
        break;
      default:
        throw syntaxError(token);
      }

      for (;;) {
        token = tokenizer.requireNext();
        switch (token) {
        case END_ARRAY:
          return true;

        case VALUE_NULL:
          throw UserException
            .unsupportedError()
            .message("Drill does not support nulls in a JSON scalar array")
            .addContext("Location", tokenizer.context())
            .build(logger);

        default:
          tokenizer.unget(token);
          scalarState.parse();
          break;
        }
      }
    }
  }

  /**
   * Parses [{ ... }, {...} ...]
   */

  protected class TupleArrayState implements ParseState {

    private final ArrayWriter writer;
    private final TupleState tupleState;

    public TupleArrayState(ArrayWriter writer, TupleState tupleState) {
      this.writer = writer;
      this.tupleState = tupleState;
    }

    @Override
    public boolean parse() {
      JsonToken token = tokenizer.requireNext();
      switch (token) {
      case VALUE_NULL:
        return true;
      case START_ARRAY:
        break;
      default:
        throw syntaxError(token);
      }

      for (;;) {
        token = tokenizer.requireNext();
        switch (token) {
        case END_ARRAY:
          return true;

        case START_OBJECT:
          tokenizer.unget(token);
          tupleState.parse();
          writer.save();
          break;

        default:
          throw syntaxError(token);
        }
      }
    }
  }

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

  protected class NullTypeState implements ParseState, NullTypeMarker {

    private final TupleState parentState;
    private final String fieldName;

    public NullTypeState(TupleState parentState, String fieldName) {
      this.parentState = parentState;
      this.fieldName = fieldName;
      nullStates.add(this);
    }

    @Override
    public boolean parse() {
      JsonToken token = tokenizer.requireNext();

      // If value is the null token, we still don't know the type.

      if (token == JsonToken.VALUE_NULL) {
        return true;
      }

      // Replace ourself with a typed reader.

      tokenizer.unget(token);
      ParseState newState = parentState.detectFieldState(fieldName);
      parentState.replaceState(fieldName, newState);
      nullStates.remove(this);
      return newState.parse();
    }

    @Override
    public void realize() {
      logger.warn("JSON field " + fieldName + " contains all nulls. Assuming text scalar.");
      ParseState newState = new TextState(
          parentState.newWriter(fieldName, MinorType.VARCHAR, DataMode.OPTIONAL).scalar(),
          fieldName);
      parentState.replaceState(fieldName, newState);
      nullStates.remove(this);
    }
  }

  protected class NullArrayState implements ParseState {

    public NullArrayState(TupleWriter writer, String fieldName) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean parse() {
      throw new UnsupportedOperationException();
    }
  }

  /**
   * Parses { name : value ... }
   * <p>
   * Creates a map of known fields. Each time a field is parsed,
   * looks up the field in the map. If not found, the value is "sniffed"
   * to determine its type, and a matching state and vector writer created.
   * Thereafter, the previous state is reused. The states ensure that the
   * correct token appears for each subsequent value, causing type errors
   * to be reported as syntax errors rather than as cryptic internal errors.
   */

  protected class TupleState implements ParseState {

    private final TupleWriter writer;
    private final Map<String, ParseState> members = new HashMap<>();

    public TupleState(TupleWriter writer) {
      this.writer = writer;
    }

    @Override
    public boolean parse() {
      JsonToken token = tokenizer.next();
      if (token == null) {
        return false;
      }
      switch (token) {
      case VALUE_NULL:
        return true;
      case START_OBJECT:
        break;
      default:
        throw syntaxError(token);
      }

      for (;;) {
        token = tokenizer.requireNext();
        switch (token) {
        case END_OBJECT:
          return true;

        case FIELD_NAME:
          parseField();
          break;

        default:
          throw syntaxError(token);
        }
      }
    }

    private void parseField() {
      final String fieldName = tokenizer.getText();
      ParseState fieldState = members.get(fieldName);
      if (fieldState == null) {
        fieldState = detectFieldState(fieldName);
        members.put(fieldName, fieldState);
      }
      fieldState.parse();
    }

    private ParseState detectFieldState(final String fieldName) {
      JsonToken token = tokenizer.requireNext();
      ParseState state;
      switch (token) {
      case START_ARRAY:
        state = detectArrayState(fieldName, 1);
        break;

      case START_OBJECT:
        state = new TupleState(
            newWriter(fieldName, MinorType.MAP, DataMode.REQUIRED).tuple());
        break;

      case VALUE_NULL:
        state = new NullTypeState(this, fieldName);
        break;

      default:
        if (options.allTextMode) {
          state = allTextScalar(token, fieldName);
        } else {
          state = typedScalar(token, fieldName);
        }
      }
      tokenizer.unget(token);
      return state;
    }

    private ParseState allTextScalar(JsonToken token, String fieldName) {
      switch (token) {
      case VALUE_EMBEDDED_OBJECT:
      case VALUE_FALSE:
      case VALUE_TRUE:
      case VALUE_NUMBER_FLOAT:
      case VALUE_NUMBER_INT:
      case VALUE_STRING:
        return new TextState(
            newWriter(fieldName, MinorType.VARCHAR, DataMode.OPTIONAL).scalar(),
            fieldName);

      default:
        throw syntaxError(token);
      }
    }

    private ParseState typedScalar(JsonToken token, String fieldName) {
      switch (token) {
      case VALUE_FALSE:
      case VALUE_TRUE:
        return new BooleanState(
            newWriter(fieldName, MinorType.TINYINT, DataMode.OPTIONAL).scalar(),
            fieldName);

      case VALUE_NULL:
        return new NullTypeState(this, fieldName);

      case VALUE_NUMBER_INT:
        if (! options.readNumbersAsDouble) {
          return new IntState(
              newWriter(fieldName, MinorType.BIGINT, DataMode.OPTIONAL).scalar(),
              fieldName);
        } // else fall through

      case VALUE_NUMBER_FLOAT:
        return new FloatState(
            newWriter(fieldName, MinorType.FLOAT8, DataMode.OPTIONAL).scalar(),
            fieldName);

      case VALUE_STRING:
        return new StringState(
            newWriter(fieldName, MinorType.VARCHAR, DataMode.OPTIONAL).scalar(),
            fieldName);

      default:
        throw syntaxError(token);
      }
    }

    private ParseState detectArrayState(String fieldName, int depth) {
      if (depth > 1) {
        throw new UnsupportedOperationException("Lists not yet supported");
      }
      JsonToken token = tokenizer.requireNext();
      ArrayWriter arrayWriter = null;
      ParseState arrayState = null;
      switch (token) {
      case START_ARRAY:
        arrayState = detectArrayState(fieldName, depth + 1);
        break;

      case START_OBJECT:
        arrayWriter = newWriter(fieldName, MinorType.MAP, DataMode.REPEATED).array();
        arrayState = new TupleArrayState(arrayWriter,
            new TupleState(arrayWriter.tuple()));
        break;

      case VALUE_NULL:
        arrayState = new NullArrayState(writer, fieldName);
        break;

      default:
        if (options.allTextMode) {
          arrayState = textElementState(token, fieldName);
        } else {
          arrayState = scalarElementState(token, fieldName);
        }
      }
      tokenizer.unget(token);
      return arrayState;
    }

    private ParseState scalarElementState(JsonToken token, String fieldName) {
      String context = fieldName + "[]";
      ArrayWriter arrayWriter = null;
      ParseState elementState = null;
      switch (token) {
      case VALUE_FALSE:
      case VALUE_TRUE:
        arrayWriter = newWriter(fieldName, MinorType.TINYINT, DataMode.REPEATED).array();
        elementState = new BooleanState(arrayWriter.scalar(), context);
        break;

      case VALUE_NUMBER_INT:
        if (! options.readNumbersAsDouble) {
          arrayWriter = newWriter(fieldName, MinorType.BIGINT, DataMode.REPEATED).array();
          elementState = new IntState(arrayWriter.scalar(), context);
          break;
        } // else fall through

      case VALUE_NUMBER_FLOAT:
        arrayWriter = newWriter(fieldName, MinorType.FLOAT8, DataMode.REPEATED).array();
        elementState = new FloatState(arrayWriter.scalar(), context);
        break;

      case VALUE_STRING:
        arrayWriter = newWriter(fieldName, MinorType.VARCHAR, DataMode.REPEATED).array();
        elementState = new StringState(arrayWriter.scalar(), context);
        break;

      default:
        throw syntaxError(token);
      }
      return new ScalarArrayState(arrayWriter, elementState);
    }

    private ParseState textElementState(JsonToken token, String fieldName) {
      String context = fieldName + "[]";
      ArrayWriter arrayWriter = null;
      ParseState elementState = null;
      switch (token) {
      case VALUE_EMBEDDED_OBJECT:
      case VALUE_FALSE:
      case VALUE_TRUE:
      case VALUE_NUMBER_FLOAT:
      case VALUE_NUMBER_INT:
      case VALUE_STRING:
        arrayWriter = newWriter(fieldName, MinorType.VARCHAR, DataMode.REPEATED).array();
        elementState = new TextState(arrayWriter.scalar(), context);
        break;

      default:
        throw syntaxError(token);
      }
      return new ScalarArrayState(arrayWriter, elementState);
    }

    private ObjectWriter newWriter(String fieldName,
          MinorType type, DataMode mode) {
      MaterializedField field = MaterializedField.create(fieldName,
          MajorType.newBuilder()
            .setMinorType(type)
            .setMode(mode)
            .build());
      int index = writer.addColumn(field);
      return writer.column(index);
    }

    private void replaceState(String fieldName, ParseState newState) {
      assert members.containsKey(fieldName);
      members.put(fieldName, newState);
    }
  }

  protected abstract class ScalarState implements ParseState {
    protected final ScalarWriter writer;
    protected final String context;

    public ScalarState(ScalarWriter writer, String context) {
      this.writer = writer;
      this.context = context;
    }
  }

  /**
   * Parses true | false | null
   */

  public class BooleanState extends ScalarState {

    public BooleanState(ScalarWriter writer, String context) {
      super(writer, context);
    }

    @Override
    public boolean parse() {
      JsonToken token = tokenizer.requireNext();
      switch (token) {
      case VALUE_NULL:
        writer.setNull();
        break;
      case VALUE_TRUE:
        writer.setInt(1);
        break;
      case VALUE_FALSE:
        writer.setInt(0);
        break;
      default:
        throw syntaxError(token, context, "Boolean");
      }
      return true;
    }
  }

  /**
   * Parses integer | null
   */

  public class IntState extends ScalarState {

    public IntState(ScalarWriter writer, String context) {
      super(writer, context);
    }

    @Override
    public boolean parse() {
      JsonToken token = tokenizer.requireNext();
      switch (token) {
      case VALUE_NULL:
        writer.setNull();
        break;
      case VALUE_NUMBER_INT:
        try {
          writer.setLong(parser.getLongValue());
        } catch (IOException e) {
          throw ioException(e);
        }
        break;
      default:
        throw syntaxError(token, context, "Integer");
      }
      return true;
    }
  }

  /**
   * Parses float | integer | null
   * <p>
   * The integer value is allowed only after seeing a float value which
   * sets the type.
   */

  public class FloatState extends ScalarState {

    public FloatState(ScalarWriter writer, String context) {
      super(writer, context);
    }

    @Override
    public boolean parse() {
      JsonToken token = tokenizer.requireNext();
      switch (token) {
      case VALUE_NULL:
        writer.setNull();
        break;
      case VALUE_NUMBER_FLOAT:
      case VALUE_NUMBER_INT:
        try {
          writer.setDouble(parser.getValueAsDouble());
        } catch (IOException e) {
          throw ioException(e);
        }
        break;
      default:
        throw syntaxError(token, context, "Float");
      }
      return true;
    }
  }

  /**
   * Parses "str" | null
   */

  public class StringState extends ScalarState {

    public StringState(ScalarWriter writer, String context) {
      super(writer, context);
    }

    @Override
    public boolean parse() {
      JsonToken token = tokenizer.requireNext();
      switch (token) {
      case VALUE_NULL:
        writer.setNull();
        break;
      case VALUE_STRING:
        try {
          writer.setString(parser.getValueAsString());
        } catch (IOException e) {
          throw ioException(e);
        }
        break;
      default:
        throw syntaxError(token, context, "String");
      }
      return true;
    }
  }

  /**
   * Parses "str" | true | false | integer | float
   * <p>
   * Returns the result as a string.
   */

  public class TextState extends ScalarState {

    public TextState(ScalarWriter writer, String context) {
      super(writer, context);
    }

    @Override
    public boolean parse() {
      JsonToken token = tokenizer.requireNext();
      switch (token) {
      case VALUE_NULL:
        writer.setNull();
        break;
      case VALUE_EMBEDDED_OBJECT:
      case VALUE_FALSE:
      case VALUE_TRUE:
      case VALUE_NUMBER_FLOAT:
      case VALUE_NUMBER_INT:
      case VALUE_STRING:
        try {
          writer.setString(parser.getText());
        } catch (IOException e) {
          throw ioException(e);
        }
        break;
      default:
        throw syntaxError(token, context, "Any value");
      }
      return true;
    }
  }

  /**
   * Parses ^[ ... ]$
   */

  protected class RootArrayState implements ParseState {

    private RowSetLoader rootWriter;
    private TupleState rootTuple;

    public RootArrayState(RowSetLoader rootWriter) {
      this.rootWriter = rootWriter;
      rootTuple = new TupleState(rootWriter);
    }

    @Override
    public boolean parse() {
      rootWriter.start();
      JsonToken token = tokenizer.requireNext();
      if (token == JsonToken.END_ARRAY) {
        return false;
      }
      tokenizer.unget(token);
      rootTuple.parse();
      rootWriter.save();
      return true;
    }
  }

  /**
   * Parses:
   * <ul>
   * <li>^{ ... }$</li>
   * <li>^{ ... } { ... } ...$</li>
   * </ul>
   */

  protected class RootTupleState extends TupleState {

    private final RowSetLoader rootWriter;

    public RootTupleState(RowSetLoader rootWriter) {
      super(rootWriter);
      this.rootWriter = rootWriter;
    }

    @Override
    public boolean parse() {
      if (! rootWriter.start()) {
        throw new IllegalStateException("Caller must check isFull()");
      }
      if (! super.parse()) {
        return false;
      }
      rootWriter.save();
      return true;
    }
  }

  public static final int MAX_LOOKAHEAD = 30;

  public class TokenIterator {
    private JsonToken[] lookahead = new JsonToken[MAX_LOOKAHEAD];
    private int count;

    public JsonToken next() {
      if (count > 0) {
        return lookahead[--count];
      }
      try {
        return parser.nextToken();
      } catch (JsonParseException e) {
        throw UserException
          .dataReadError(e)
          .addContext("Location", context())
          .build(logger);
      } catch (IOException e) {
        throw ioException(e);
      }
    }

    public String context() {
      JsonLocation location = parser.getCurrentLocation();
      if (location == null) {
        return options.context;
      }
      String token;
      try {
        token = parser.getText();
      } catch (IOException e) {
        token = "<unknown>";
      }
      return new StringBuilder()
          .append(options.context)
          .append(", line ")
          .append(location.getLineNr())
          .append(", column ")
          .append(location.getColumnNr())
          .append(", near token \"")
          .append(token)
          .append("\"")
          .toString();
    }

    public JsonToken requireNext() {
      JsonToken token = next();
      if (token == null) {
        throw UserException
          .dataReadError()
          .message("Premature EOF of JSON file")
          .addContext("Location", tokenizer.context())
          .build(logger);
      }
      return token;
    }

    public void unget(JsonToken token) {
      if (count == lookahead.length) {
        throw UserException
          .dataReadError()
          .message("Excessive JSON array nesting")
          .addContext("Max allowed", lookahead.length)
          .addContext("Location", tokenizer.context())
          .build(logger);
      }
      lookahead[count++] = token;
    }

    public String getText() {
      try {
        return parser.getText();
      } catch (IOException e) {
        throw ioException(e);
      }
    }
  }

  private final JsonParser parser;
  private final RowSetLoader rootWriter;
  private final JsonOptions options;
  private final TokenIterator tokenizer;

  // Using a simple list. Won't perform well if we have hundreds of
  // null fields; but then we've never seen such a pathologically bad
  // case... Usually just one or two fields have deferred nulls.

  private final List<NullTypeMarker> nullStates = new ArrayList<>();
  private ParseState rootState;

  public JsonLoaderImpl(InputStream stream, RowSetLoader rootWriter, JsonOptions options) {
    try {
      ObjectMapper mapper = new ObjectMapper()
          .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
          .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
      if (options.allowNanInf) {
        mapper.configure(JsonParser.Feature.ALLOW_NON_NUMERIC_NUMBERS, true);
      }

      parser = mapper.getFactory().createParser(stream);
    } catch (JsonParseException e) {
      throw UserException
          .internalError(e)
          .addContext("Failed to create the JSON parser")
          .addContext("Source", options.context)
          .build(logger);
    } catch (IOException e) {
      throw ioException(e);
    }
    this.rootWriter = rootWriter;
    this.options = options;
    tokenizer = new TokenIterator();
    rootState = makeRootState();
  }

  private ParseState makeRootState() {
    JsonToken token = tokenizer.next();
    if (token == null) {
      return null;
    }
    switch (token) {

    // File contains an array of records.

    case START_ARRAY:
      if (options.skipOuterList) {
        return new RootArrayState(rootWriter);
      } else {
        throw UserException
          .dataReadError()
          .message("JSON includes an outer array, but outer array support is not enabled")
          .addContext("Location", tokenizer.context())
          .build(logger);
      }

    // File contains a sequence of one or more records,
    // presumably sequentially.

    case START_OBJECT:
      tokenizer.unget(token);
      return new RootTupleState(rootWriter);

    // Not a valid JSON file for Drill.

    default:
      throw syntaxError(token);
    }
  }

  @Override
  public boolean next() {
    if (rootState == null) {
      return false;
    }

    // From original code.
    // Does this ever actually occur?

    if (parser.isClosed()) {
      rootState = null;
      return false;
    }
    return rootState.parse();
  }

  @Override
  public void endBatch() {
    List<NullTypeMarker> copy = new ArrayList<>();
    copy.addAll(nullStates);
    for (NullTypeMarker state : copy) {
      state.realize();
    }
    assert nullStates.isEmpty();
  }

  private UserException syntaxError(JsonToken token, String context, String expected) {
    return UserException
        .dataReadError()
        .message("JSON encountered a value of the wrong type")
        .message("Field", context)
        .message("Expected type", expected)
        .message("Actual token", token.toString())
        .addContext("Location", tokenizer.context())
        .build(logger);
  }

  private UserException syntaxError(JsonToken token) {
    return UserException
        .dataReadError()
        .message("JSON syntax error.")
        .addContext("Current token", token.toString())
        .addContext("Location", tokenizer.context())
        .build(logger);
  }

  private UserException ioException(IOException e) {
    return UserException
        .dataReadError(e)
        .addContext("I/O error reading JSON")
        .addContext("Location", parser == null ? options.context : tokenizer.context())
        .build(logger);
  }

  @Override
  public void close() {
    try {
      parser.close();
    } catch (IOException e) {
      logger.warn("Ignored failure when closing JSON source " + options.context, e);
    }
  }
}