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
package org.apache.drill.exec.physical.impl.xsort.managed;

import java.util.LinkedList;
import java.util.List;

import org.apache.calcite.rel.RelFieldCollation.Direction;
import org.apache.drill.common.exceptions.UserException;
import org.apache.drill.common.expression.ErrorCollector;
import org.apache.drill.common.expression.ErrorCollectorImpl;
import org.apache.drill.common.expression.LogicalExpression;
import org.apache.drill.common.logical.data.Order.Ordering;
import org.apache.drill.exec.compile.sig.MappingSet;
import org.apache.drill.exec.exception.SchemaChangeException;
import org.apache.drill.exec.expr.ClassGenerator;
import org.apache.drill.exec.expr.ClassGenerator.HoldingContainer;
import org.apache.drill.exec.expr.CodeGenerator;
import org.apache.drill.exec.expr.ExpressionTreeMaterializer;
import org.apache.drill.exec.expr.fn.FunctionGenerationHelper;
import org.apache.drill.exec.ops.OperExecContext;
import org.apache.drill.exec.physical.config.Sort;
import org.apache.drill.exec.physical.impl.sort.RecordBatchData;
import org.apache.drill.exec.physical.impl.sort.SortRecordBatchBuilder;
import org.apache.drill.exec.physical.impl.xsort.managed.SortImpl.SortResults;
import org.apache.drill.exec.record.BatchSchema.SelectionVectorMode;
import org.apache.drill.exec.record.VectorAccessible;
import org.apache.drill.exec.record.VectorContainer;
import org.apache.drill.exec.record.selection.SelectionVector4;

import com.sun.codemodel.JConditional;
import com.sun.codemodel.JExpr;

/**
 * Wrapper around the "MSorter" (in memory merge sorter). As batches have
 * arrived to the sort, they have been individually sorted and buffered
 * in memory. At the completion of the sort, we detect that no batches
 * were spilled to disk. In this case, we can merge the in-memory batches
 * using an efficient memory-based approach implemented here.
 * <p>
 * Since all batches are in memory, we don't want to use the usual merge
 * algorithm as that makes a copy of the original batches (which were read
 * from a spill file) to produce an output batch. Instead, we want to use
 * the in-memory batches as-is. To do this, we use a selection vector 4
 * (SV4) as a global index into the collection of batches. The SV4 uses
 * the upper two bytes as the batch index, and the lower two as an offset
 * of a record within the batch.
 * <p>
 * The merger ("M Sorter") populates the SV4 by scanning the set of
 * in-memory batches, searching for the one with the lowest value of the
 * sort key. The batch number and offset are placed into the SV4. The process
 * continues until all records from all batches have an entry in the SV4.
 * <p>
 * The actual implementation uses an iterative merge to perform the above
 * efficiently.
 * <p>
 * A sort can only do a single merge. So, we do not attempt to share the
 * generated class; we just generate it internally and discard it at
 * completion of the merge.
 */

public class MergeSortWrapper extends BaseSortWrapper implements SortResults {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(MergeSortWrapper.class);

  private SortRecordBatchBuilder builder;
  private MSorter mSorter;
  private SelectionVector4 sv4;
  private int batchCount;

  public MergeSortWrapper(OperExecContext opContext) {
    super(opContext);
  }

  /**
   * Merge the set of in-memory batches to produce a single logical output in the given
   * destination container, indexed by an SV4.
   *
   * @param batchGroups the complete set of in-memory batches
   * @param batch the record batch (operator) for the sort operator
   * @param destContainer the vector container for the sort operator
   * @return the sv4 for this operator
   */

  public void merge(List<BatchGroup.InputBatch> batchGroups, VectorAccessible batch,
                                VectorContainer destContainer) {

    // Add the buffered batches to a collection that MSorter can use.
    // The builder takes ownership of the batches and will release them if
    // an error occurs.

    builder = new SortRecordBatchBuilder(context.getAllocator());
    for (BatchGroup.InputBatch group : batchGroups) {
      RecordBatchData rbd = new RecordBatchData(group.getContainer(), context.getAllocator());
      rbd.setSv2(group.getSv2());
      builder.add(rbd);
    }
    batchGroups.clear();

    // Generate the msorter.

    try {
      builder.build(destContainer);
      sv4 = builder.getSv4();
      mSorter = createNewMSorter(batch);
      mSorter.setup(context, context.getAllocator(), sv4, destContainer, sv4.getCount());
    } catch (SchemaChangeException e) {
      throw UserException.unsupportedError(e)
            .message("Unexpected schema change - likely code error.")
            .build(logger);
    }

    // For testing memory-leaks, inject exception after mSorter finishes setup
    ExternalSortBatch.injector.injectUnchecked(context.getExecutionControls(), ExternalSortBatch.INTERRUPTION_AFTER_SETUP);
    mSorter.sort(destContainer);

    // For testing memory-leak purpose, inject exception after mSorter finishes sorting
    ExternalSortBatch.injector.injectUnchecked(context.getExecutionControls(), ExternalSortBatch.INTERRUPTION_AFTER_SORT);
    sv4 = mSorter.getSV4();

    destContainer.buildSchema(SelectionVectorMode.FOUR_BYTE);
  }

  public MSorter createNewMSorter(VectorAccessible batch) {
    Sort popConfig = context.getOperatorDefn();
    return createNewMSorter(popConfig.getOrderings(), batch, MAIN_MAPPING, LEFT_MAPPING, RIGHT_MAPPING);
  }

  private MSorter createNewMSorter(List<Ordering> orderings, VectorAccessible batch, MappingSet mainMapping, MappingSet leftMapping, MappingSet rightMapping) {
    CodeGenerator<MSorter> cg = CodeGenerator.get(MSorter.TEMPLATE_DEFINITION, context.getFunctionRegistry(), context.getOptionSet());
    cg.plainJavaCapable(true);

    // Uncomment out this line to debug the generated code.
//  cg.saveCodeForDebugging(true);
    ClassGenerator<MSorter> g = cg.getRoot();
    g.setMappingSet(mainMapping);

    for (Ordering od : orderings) {
      // first, we rewrite the evaluation stack for each side of the comparison.
      ErrorCollector collector = new ErrorCollectorImpl();
      final LogicalExpression expr = ExpressionTreeMaterializer.materialize(od.getExpr(), batch, collector, context.getFunctionRegistry());
      if (collector.hasErrors()) {
        throw UserException.unsupportedError()
              .message("Failure while materializing expression. " + collector.toErrorString())
              .build(logger);
      }
      g.setMappingSet(leftMapping);
      HoldingContainer left = g.addExpr(expr, ClassGenerator.BlkCreateMode.FALSE);
      g.setMappingSet(rightMapping);
      HoldingContainer right = g.addExpr(expr, ClassGenerator.BlkCreateMode.FALSE);
      g.setMappingSet(mainMapping);

      // next we wrap the two comparison sides and add the expression block for the comparison.
      LogicalExpression fh =
          FunctionGenerationHelper.getOrderingComparator(od.nullsSortHigh(), left, right,
                                                         context.getFunctionRegistry());
      HoldingContainer out = g.addExpr(fh, ClassGenerator.BlkCreateMode.FALSE);
      JConditional jc = g.getEvalBlock()._if(out.getValue().ne(JExpr.lit(0)));

      if (od.getDirection() == Direction.ASCENDING) {
        jc._then()._return(out.getValue());
      }else{
        jc._then()._return(out.getValue().minus());
      }
      g.rotateBlock();
    }

    g.rotateBlock();
    g.getEvalBlock()._return(JExpr.lit(0));

    return getInstance(cg, logger);
  }

  /**
   * The SV4 provides a built-in iterator that returns a virtual set of record
   * batches so that the downstream operator need not consume the entire set
   * of accumulated batches in a single step.
   */

  @Override
  public boolean next() {
    boolean more = sv4.next();
    if (more) { batchCount++; }
    return more;
  }

  @Override
  public void close() {
    RuntimeException ex = null;
    try {
      if (builder != null) {
        builder.clear();
        builder.close();
        builder = null;
      }
    } catch (RuntimeException e) {
      ex = (ex == null) ? e : ex;
    }
    try {
      if (mSorter != null) {
        mSorter.clear();
        mSorter = null;
      }
    } catch (RuntimeException e) {
      ex = (ex == null) ? e : ex;
    }
    try {
      if (sv4 != null) {
        sv4.clear();
      }
    } catch (RuntimeException e) {
      ex = (ex == null) ? e : ex;
    }
    if (ex != null) {
      throw ex;
    }
  }

  @Override
  public int getBatchCount() {
    return batchCount;
  }

  @Override
  public int getRecordCount() {
    return sv4.getTotalCount();
  }

  @Override
  public SelectionVector4 getSv4() {
    return sv4;
  }
}
