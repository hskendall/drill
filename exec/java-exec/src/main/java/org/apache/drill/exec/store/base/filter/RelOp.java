package org.apache.drill.exec.store.base.filter;

import org.apache.drill.exec.store.base.PlanStringBuilder;
import org.apache.drill.shaded.guava.com.google.common.base.Preconditions;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Semanticized form of a Calcite relational operator. Abstracts
 * out the Drill implementation details to capture just the
 * column name, operator and value. Supports only expressions
 * of the form:<br>
 * <code>&lt;column> &lt;relop> &lt;const></code><br>
 * Where the column is a simple name (not an array or map reference),
 * the relop is one of a defined set, and the constant is one
 * of the defined Drill types.
 * <p>
 * (The driver will convert expressions of the form:<br>
 * <code>&lt;const></code> &lt;relop> <code>&lt;column></code><br>
 * into the normalized form represented here.
 */

@JsonInclude(Include.NON_NULL)
public class RelOp {

  public enum Op {
    EQ, NE, LT, LE, GT, GE, IS_NULL, IS_NOT_NULL;

    /**
     * Return the result of flipping the sides of an
     * expression:</br>
     * a op b &rarr; b op.invert() a
     */
    public RelOp.Op invert() {
      switch(this) {
      case LT:
        return GT;
      case LE:
        return GE;
      case GT:
        return LT;
      case GE:
        return LE;
      default:
        return this;
      }
    }

    public int argCount() {
      switch (this) {
      case IS_NULL:
      case IS_NOT_NULL:
        return 1;
      default:
        return 2;
      }
    }
  }

  public final RelOp.Op op;
  public final String colName;
  public final ConstantHolder value;

  public RelOp(RelOp.Op op, String colName, ConstantHolder value) {
    Preconditions.checkArgument(op.argCount() == 1 || value != null);
    this.op = op;
    this.colName = colName;
    this.value = value;
  }

  /**
   * Rewrite the RelOp with a normalized value.
   *
   * @param from the original RelOp
   * @param value the new value with a different type and matching
   * value
   */

  public RelOp(RelOp from, ConstantHolder value) {
    Preconditions.checkArgument(from.op.argCount() == 2);
    this.op = from.op;
    this.colName = from.colName;
    this.value = value;
  }

  /**
   * Return a new RelOp with the normalized value. Will be the same relop
   * if the normalized value is the same as the unnormalized value.
   */

  public RelOp normalize(ConstantHolder normalizedValue) {
    if (value == normalizedValue) {
      return this;
    }
    return new RelOp(this, normalizedValue);
  }

  public RelOp rewrite(String newName, ConstantHolder newValue) {
    if (value == newValue && colName.equals(newName)) {
      return this;
    }
    return new RelOp(op, newName, newValue);
  }

  @Override
  public String toString() {
    PlanStringBuilder builder = new PlanStringBuilder(this)
      .field("op", op.name())
      .field("colName", colName);
    if (value != null) {
      builder.field("type", value.type.name())
             .field("value", value.value);
    }
    return builder.toString();
  }
}
