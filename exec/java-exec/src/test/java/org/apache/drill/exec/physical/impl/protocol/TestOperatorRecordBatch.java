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
package org.apache.drill.exec.physical.impl.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Iterator;

import org.apache.drill.common.exceptions.UserException;
import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.common.types.TypeProtos.MinorType;
import org.apache.drill.exec.physical.impl.protocol.OperatorRecordBatch;
import org.apache.drill.exec.physical.impl.protocol.OperatorRecordBatch.BatchAccessor;
import org.apache.drill.exec.physical.impl.protocol.OperatorRecordBatch.ContainerAndSv2Accessor;
import org.apache.drill.exec.physical.impl.protocol.OperatorRecordBatch.OperatorExec;
import org.apache.drill.exec.physical.impl.protocol.OperatorRecordBatch.OperatorExecServices;
import org.apache.drill.exec.physical.impl.protocol.OperatorRecordBatch.OperatorExecServicesImpl;
import org.apache.drill.exec.physical.impl.protocol.OperatorRecordBatch.VectorContainerAccessor;
import org.apache.drill.exec.proto.UserBitShared.NamePart;
import org.apache.drill.exec.record.BatchSchema;
import org.apache.drill.exec.record.BatchSchema.SelectionVectorMode;
import org.apache.drill.exec.record.RecordBatch.IterOutcome;
import org.apache.drill.exec.record.TypedFieldId;
import org.apache.drill.exec.record.VectorContainer;
import org.apache.drill.exec.record.VectorWrapper;
import org.apache.drill.exec.vector.IntVector;
import org.apache.drill.exec.vector.VarCharVector;
import org.apache.drill.test.SubOperatorTest;
import org.apache.drill.test.rowSet.RowSet.SingleRowSet;
import org.apache.drill.test.rowSet.SchemaBuilder;
import org.junit.Test;

public class TestOperatorRecordBatch extends SubOperatorTest {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(SubOperatorTest.class);

  /**
   * Mock operator executor that simply tracks each method call
   * and provides a light-weight vector container. Returns a
   * defined number of (batches) with an optional schema change.
   */

  private class MockOperatorExec implements OperatorExec {

    public boolean bindCalled;
    public boolean buildSchemaCalled;
    public int nextCalls = 1;
    public int nextCount;
    public int schemaChangeAt = -1;
    public boolean cancelCalled;
    public boolean closeCalled;
    public boolean schemaEOF;
    private final VectorContainerAccessor batchAccessor;

    public MockOperatorExec() {
      this(new VectorContainer(fixture.allocator(), new SchemaBuilder()
          .add("a", MinorType.INT)
          .build()));
      batchAccessor.getOutgoingContainer().buildSchema(SelectionVectorMode.NONE);
    }

    public MockOperatorExec(VectorContainer container) {
      batchAccessor = new VectorContainerAccessor();
      batchAccessor.setContainer(container);
    }

    public MockOperatorExec(VectorContainerAccessor accessor) {
      batchAccessor = accessor;
    }

    @Override
    public void bind(OperatorExecServices context) { bindCalled = true; }

    @Override
    public BatchAccessor batchAccessor() {
      return batchAccessor;
    }

    @Override
    public boolean buildSchema() { buildSchemaCalled = true; return ! schemaEOF; }

    @Override
    public boolean next() {
      nextCount++;
      if (nextCount > nextCalls) {
        return false;
      }
      if (nextCount == schemaChangeAt) {
        BatchSchema newSchema = new SchemaBuilder(batchAccessor.getSchema())
            .add("b", MinorType.VARCHAR)
            .build();
        VectorContainer newContainer = new VectorContainer(fixture.allocator(), newSchema);
        batchAccessor.setContainer(newContainer);
      }
      return true;
    }

    @Override
    public void cancel() { cancelCalled = true; }

    @Override
    public void close() {
      batchAccessor().getOutgoingContainer().clear();
      closeCalled = true;
    }
  }

  private OperatorRecordBatch makeOpBatch(MockOperatorExec opExec) {
    return new OperatorRecordBatch(fixture.codeGenContext(), null, opExec);
  }

  /**
   * Simulate a normal run: return some batches, encounter a schema change.
   */

  @Test
  public void testNormalLifeCycle() {
    MockOperatorExec opExec = new MockOperatorExec();
    opExec.nextCalls = 2;
    opExec.schemaChangeAt = 2;
    try (OperatorRecordBatch opBatch = makeOpBatch(opExec)) {

      assertSame(fixture.codeGenContext(), opBatch.getExecContext());
      assertNull(opBatch.getContext());

      // First call to next() builds schema

      assertEquals(IterOutcome.OK_NEW_SCHEMA, opBatch.next());
      assertTrue(opExec.bindCalled);
      assertTrue(opExec.buildSchemaCalled);
      assertEquals(0, opExec.nextCount);

      // Second call returns the first batch

      assertEquals(IterOutcome.OK, opBatch.next());
      assertEquals(1, opExec.nextCount);

      // Third call causes a schema change

      assertEquals(IterOutcome.OK_NEW_SCHEMA, opBatch.next());
      assertEquals(2, opExec.nextCount);

      // Fourth call reaches EOF

      assertEquals(IterOutcome.NONE, opBatch.next());
      assertEquals(3, opExec.nextCount);

      // Close
    } catch (Exception e) {
      fail();
    }

    assertTrue(opExec.closeCalled);
    assertFalse(opExec.cancelCalled);
  }

  /**
   * Simulate a truncated life cycle: next() is never called. Not a valid part
   * of the protocol; but should be ready anyway.
   */

  @Test
  public void testTruncatedLifeCycle() {
    MockOperatorExec opExec = new MockOperatorExec();
    opExec.schemaEOF = true;

    try (OperatorRecordBatch opBatch = makeOpBatch(opExec)) {
    } catch (Exception e) {
      fail();
    }
    assertTrue(opExec.bindCalled);
    assertTrue(opExec.closeCalled);
  }

  /**
   * Simulate reaching EOF when trying to create the schema.
   */

  @Test
  public void testSchemaEOF() {
    MockOperatorExec opExec = new MockOperatorExec();
    opExec.schemaEOF = true;

    try (OperatorRecordBatch opBatch = makeOpBatch(opExec)) {
      assertEquals(IterOutcome.NONE, opBatch.next());
      assertTrue(opExec.buildSchemaCalled);
    } catch (Exception e) {
      fail();
    }
    assertTrue(opExec.closeCalled);
  }

  /**
   * Simulate reaching EOF on the first batch. This simulated data source
   * discovered a schema, but had no data.
   */

  @Test
  public void testFirstBatchEOF() {
    MockOperatorExec opExec = new MockOperatorExec();
    opExec.nextCalls = 0;

    try (OperatorRecordBatch opBatch = makeOpBatch(opExec)) {
      assertEquals(IterOutcome.OK_NEW_SCHEMA, opBatch.next());
      assertTrue(opExec.buildSchemaCalled);
      assertEquals(IterOutcome.NONE, opBatch.next());
      assertEquals(1, opExec.nextCount);
    } catch (Exception e) {
      fail();
    }
    assertTrue(opExec.closeCalled);
  }

  /**
   * Simulate the caller failing the operator before getting the schema.
   */

  @Test
  public void testFailEarly() {
    MockOperatorExec opExec = new MockOperatorExec();
    opExec.nextCalls = 2;

    try (OperatorRecordBatch opBatch = makeOpBatch(opExec)) {
      opBatch.kill(false);
      assertFalse(opExec.buildSchemaCalled);
      assertEquals(0, opExec.nextCount);
      assertFalse(opExec.cancelCalled);
    } catch (Exception e) {
      fail();
    }
    assertTrue(opExec.closeCalled);
  }

  /**
   * Simulate the caller failing the operator before EOF.
   */

  @Test
  public void testFailWhileReading() {
    MockOperatorExec opExec = new MockOperatorExec();
    opExec.nextCalls = 2;

    try (OperatorRecordBatch opBatch = makeOpBatch(opExec)) {
      assertEquals(IterOutcome.OK_NEW_SCHEMA, opBatch.next());
      assertEquals(IterOutcome.OK, opBatch.next());
      opBatch.kill(false);
      assertTrue(opExec.cancelCalled);
    } catch (Exception e) {
      fail();
    }
    assertTrue(opExec.closeCalled);
  }

  /**
   * Simulate the caller failing the operator after EOF but before close.
   * This is a silly time to fail, but have to handle it anyway.
   */

  @Test
  public void testFailBeforeClose() {
    MockOperatorExec opExec = new MockOperatorExec();
    opExec.nextCalls = 2;

    try (OperatorRecordBatch opBatch = makeOpBatch(opExec)) {
      assertEquals(IterOutcome.OK_NEW_SCHEMA, opBatch.next());
      assertEquals(IterOutcome.OK, opBatch.next());
      assertEquals(IterOutcome.OK, opBatch.next());
      assertEquals(IterOutcome.NONE, opBatch.next());
      opBatch.kill(false);

      // Already hit EOF, so fail won't be passed along.

      assertFalse(opExec.cancelCalled);
    } catch (Exception e) {
      fail();
    }
    assertTrue(opExec.closeCalled);
  }

  /**
   * Simulate the caller failing the operator after close.
   * This is violates the operator protocol, but have to handle it anyway.
   */

  @Test
  public void testFailAfterClose() {
    MockOperatorExec opExec = new MockOperatorExec();
    opExec.nextCalls = 2;

    @SuppressWarnings("resource")
    OperatorRecordBatch opBatch = makeOpBatch(opExec);
    assertEquals(IterOutcome.OK_NEW_SCHEMA, opBatch.next());
    assertEquals(IterOutcome.OK, opBatch.next());
    assertEquals(IterOutcome.OK, opBatch.next());
    assertEquals(IterOutcome.NONE, opBatch.next());
    try {
      opBatch.close();
    } catch (Exception e) {
      fail();
    }
    assertTrue(opExec.closeCalled);
    opBatch.kill(false);
    assertFalse(opExec.cancelCalled);
  }

  /**
   * The record batch abstraction has a bunch of methods to work with a vector container.
   * Rather than simply exposing the container itself, the batch instead exposes various
   * container operations. Probably an artifact of its history. In any event, make
   * sure those methods are passed through to the container accessor.
   */

  @Test
  public void testBatchAccessor() {
    BatchSchema schema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .build();
    SingleRowSet rs = fixture.rowSetBuilder(schema)
        .add(10, "fred")
        .add(20, "wilma")
        .build();
    MockOperatorExec opExec = new MockOperatorExec(rs.container());
    opExec.nextCalls = 1;

    try (OperatorRecordBatch opBatch = makeOpBatch(opExec)) {
      assertEquals(IterOutcome.OK_NEW_SCHEMA, opBatch.next());
      assertEquals(schema, opBatch.getSchema());
      assertEquals(2, opBatch.getRecordCount());
      assertSame(rs.container(), opBatch.getOutgoingContainer());

      Iterator<VectorWrapper<?>> iter = opBatch.iterator();
      assertEquals("a", iter.next().getValueVector().getField().getName());
      assertEquals("b", iter.next().getValueVector().getField().getName());

      // Not a full test of the schema path; just make sure that the
      // pass-through to the Vector Container works.

      SchemaPath path = SchemaPath.create(NamePart.newBuilder().setName("a").build());
      TypedFieldId id = opBatch.getValueVectorId(path);
      assertEquals(MinorType.INT, id.getFinalType().getMinorType());
      assertEquals(1, id.getFieldIds().length);
      assertEquals(0, id.getFieldIds()[0]);

      path = SchemaPath.create(NamePart.newBuilder().setName("b").build());
      id = opBatch.getValueVectorId(path);
      assertEquals(MinorType.VARCHAR, id.getFinalType().getMinorType());
      assertEquals(1, id.getFieldIds().length);
      assertEquals(1, id.getFieldIds()[0]);

      // Sanity check of getValueAccessorById()

      VectorWrapper<?> w = opBatch.getValueAccessorById(IntVector.class, 0);
      assertNotNull(w);
      assertEquals("a", w.getValueVector().getField().getName());
      w = opBatch.getValueAccessorById(VarCharVector.class, 1);
      assertNotNull(w);
      assertEquals("b", w.getValueVector().getField().getName());

      // getWritableBatch() ?

      // No selection vectors

      try {
        opBatch.getSelectionVector2();
        fail();
      } catch (UnsupportedOperationException e) {
        // Expected
      }
      try {
        opBatch.getSelectionVector4();
        fail();
      } catch (UnsupportedOperationException e) {
        // Expected
      }

    } catch (Exception e) {
      fail(e.getMessage());
    }
    assertTrue(opExec.closeCalled);
  }

  /**
   * Test that an SV2 is properly handled by the proper container accessor.
   */

  @Test
  public void testSv2() {
    BatchSchema schema = new SchemaBuilder()
        .add("a", MinorType.INT)
        .add("b", MinorType.VARCHAR)
        .build();
    SingleRowSet rs = fixture.rowSetBuilder(schema)
        .add(10, "fred")
        .add(20, "wilma")
        .withSv2()
        .build();

    ContainerAndSv2Accessor accessor = new ContainerAndSv2Accessor();
    accessor.setContainer(rs.container());
    accessor.setSelectionVector(rs.getSv2());

    MockOperatorExec opExec = new MockOperatorExec(accessor);
    opExec.nextCalls = 1;

    try (OperatorRecordBatch opBatch = makeOpBatch(opExec)) {
      assertEquals(IterOutcome.OK_NEW_SCHEMA, opBatch.next());
      assertSame(rs.getSv2(), opBatch.getSelectionVector2());

    } catch (Exception e) {
      fail();
    }
    assertTrue(opExec.closeCalled);

    // Must release SV2

    rs.clear();
  }

  //-----------------------------------------------------------------------
  // Exception error cases
  //
  // Assumes that any of the operator executor methods could throw an
  // exception. A wise implementation will throw a user exception that the
  // operator just passes along. A lazy implementation will throw any old
  // unchecked exception. Validate both cases.

  public static final String ERROR_MSG = "My Bad!";

  /**
   * Failure on the bind method.
   */

  @Test
  public void testWrappedExceptionOnBind() {
    MockOperatorExec opExec = new MockOperatorExec() {
      @Override
      public void bind(OperatorExecServices context) {
         throw new IllegalStateException(ERROR_MSG);
      }
    };
    try (OperatorRecordBatch opBatch = makeOpBatch(opExec)) {
      fail();
    } catch (UserException e) {
      assertTrue(e.getMessage().contains(ERROR_MSG));
      assertTrue(e.getCause() instanceof IllegalStateException);
    } catch (Throwable t) {
      fail();
    }
    assertFalse(opExec.cancelCalled); // Cancel not called: too early in life
    assertFalse(opExec.closeCalled); // Same with close
  }

  @Test
  public void testUserExceptionOnBind() {
    MockOperatorExec opExec = new MockOperatorExec() {
      @Override
      public void bind(OperatorExecServices context) {
         throw UserException.connectionError()
           .message(ERROR_MSG)
           .build(logger);
      }
    };
    try (OperatorRecordBatch opBatch = makeOpBatch(opExec)) {
      opBatch.next();
      fail();
    } catch (UserException e) {
      assertTrue(e.getMessage().contains(ERROR_MSG));
      assertNull(e.getCause());
    } catch (Throwable t) {
      fail();
    }
    assertFalse(opExec.cancelCalled); // Cancel not called: too early in life
    assertFalse(opExec.closeCalled); // Same with close
  }

  /**
   * Failure when building the schema (first call to next()).
   */
  @Test
  public void testWrappedExceptionOnBuildSchema() {
    MockOperatorExec opExec = new MockOperatorExec() {
      @Override
      public boolean buildSchema() {
         throw new IllegalStateException(ERROR_MSG);
      }
    };
    try (OperatorRecordBatch opBatch = makeOpBatch(opExec)) {
      opBatch.next();
      fail();
    } catch (UserException e) {
      assertTrue(e.getMessage().contains(ERROR_MSG));
      assertTrue(e.getCause() instanceof IllegalStateException);
    } catch (Throwable t) {
      fail();
    }
    assertTrue(opExec.cancelCalled);
    assertTrue(opExec.closeCalled);
  }

  @Test
  public void testUserExceptionOnBuildSchema() {
    MockOperatorExec opExec = new MockOperatorExec() {
      @Override
      public boolean buildSchema() {
        throw UserException.dataReadError()
            .message(ERROR_MSG)
            .build(logger);
      }
    };
    try (OperatorRecordBatch opBatch = makeOpBatch(opExec)) {
      opBatch.next();
      fail();
    } catch (UserException e) {
      assertTrue(e.getMessage().contains(ERROR_MSG));
      assertNull(e.getCause());
    } catch (Throwable t) {
      fail();
    }
    assertTrue(opExec.cancelCalled);
    assertTrue(opExec.closeCalled);
  }

  /**
   * Failure on the second or subsequent calls to next(), when actually
   * fetching a record batch.
   */

  @Test
  public void testWrappedExceptionOnNext() {
    MockOperatorExec opExec = new MockOperatorExec() {
      @Override
      public boolean next() {
         throw new IllegalStateException(ERROR_MSG);
      }
    };
    try (OperatorRecordBatch opBatch = makeOpBatch(opExec)) {
      assertEquals(IterOutcome.OK_NEW_SCHEMA, opBatch.next());
      opBatch.next();
      fail();
    } catch (UserException e) {
      assertTrue(e.getMessage().contains(ERROR_MSG));
      assertTrue(e.getCause() instanceof IllegalStateException);
    } catch (Throwable t) {
      fail();
    }
    assertTrue(opExec.cancelCalled);
    assertTrue(opExec.closeCalled);
  }

  @Test
  public void testUserExceptionOnNext() {
    MockOperatorExec opExec = new MockOperatorExec() {
      @Override
      public boolean next() {
        throw UserException.dataReadError()
              .message(ERROR_MSG)
              .build(logger);
      }
    };
    try (OperatorRecordBatch opBatch = makeOpBatch(opExec)) {
      assertEquals(IterOutcome.OK_NEW_SCHEMA, opBatch.next());
      opBatch.next();
      fail();
    } catch (UserException e) {
      assertTrue(e.getMessage().contains(ERROR_MSG));
      assertNull(e.getCause());
    } catch (Throwable t) {
      fail();
    }
    assertTrue(opExec.cancelCalled);
    assertTrue(opExec.closeCalled);
  }

  /**
   * Failure when closing the operator implementation.
   */

  @Test
  public void testWrappedExceptionOnClose() {
    MockOperatorExec opExec = new MockOperatorExec() {
      @Override
      public void close() {
        // Release memory
        super.close();
        // Then fail
        throw new IllegalStateException(ERROR_MSG);
      }
    };
    opExec.nextCalls = 1;
    try (OperatorRecordBatch opBatch = makeOpBatch(opExec)) {
      assertEquals(IterOutcome.OK_NEW_SCHEMA, opBatch.next());
      assertEquals(IterOutcome.OK, opBatch.next());
      assertEquals(IterOutcome.NONE, opBatch.next());
    } catch (UserException e) {
      assertTrue(e.getMessage().contains(ERROR_MSG));
      assertTrue(e.getCause() instanceof IllegalStateException);
    } catch (Throwable t) {
      fail();
    }
    assertFalse(opExec.cancelCalled);
    assertTrue(opExec.closeCalled);
  }

  @Test
  public void testUserExceptionOnClose() {
    MockOperatorExec opExec = new MockOperatorExec() {
      @Override
      public void close() {
        // Release memory
        super.close();
        // Then fail
        throw UserException.dataReadError()
              .message(ERROR_MSG)
              .build(logger);
      }
    };
    try (OperatorRecordBatch opBatch = makeOpBatch(opExec)) {
      assertEquals(IterOutcome.OK_NEW_SCHEMA, opBatch.next());
      assertEquals(IterOutcome.OK, opBatch.next());
      assertEquals(IterOutcome.NONE, opBatch.next());
    } catch (UserException e) {
      assertTrue(e.getMessage().contains(ERROR_MSG));
      assertNull(e.getCause());
    } catch (Throwable t) {
      fail();
    }
    assertFalse(opExec.cancelCalled);
    assertTrue(opExec.closeCalled);
  }
}
