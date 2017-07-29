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

<@pp.dropOutputFile />
<@pp.changeOutputFile name="/org/apache/drill/exec/vector/accessor/ColumnAccessors.java" />
<#include "/@includes/license.ftl" />
<#macro getType drillType label>
    @Override
    public ValueType valueType() {
  <#if label == "Int">
      return ValueType.INTEGER;
  <#elseif drillType == "VarChar" || drillType == "Var16Char">
      return ValueType.STRING;
  <#else>
      return ValueType.${label?upper_case};
  </#if>
    }
</#macro>
<#macro bindReader vectorPrefix drillType isArray >
  <#if drillType = "Decimal9" || drillType == "Decimal18">
    private MajorType type;
  </#if>
    private ${vectorPrefix}${drillType}Vector.Accessor accessor;

    @Override
    public void bindVector(ValueVector vector) {
  <#if drillType = "Decimal9" || drillType == "Decimal18">
      type = vector.getField().getType();
  </#if>
      accessor = ((${vectorPrefix}${drillType}Vector) vector).getAccessor();
    }

  <#if drillType = "Decimal9" || drillType == "Decimal18">
    @Override
    public void bindVector(MajorType type, VectorAccessor va) {
      super.bindVector(type, va);
      this.type = type;
    }

 </#if>
    private ${vectorPrefix}${drillType}Vector.Accessor accessor() {
      if (vectorAccessor == null) {
        return accessor;
      } else {
        return ((${vectorPrefix}${drillType}Vector) vectorAccessor.vector()).getAccessor();
      }
    }
</#macro>
<#macro get drillType accessorType label isArray>
    @Override
    public ${accessorType} get${label}(<#if isArray>int index</#if>) {
    <#assign getObject ="getObject"/>
  <#if isArray>
    <#assign indexVar = "index"/>
  <#else>
    <#assign indexVar = ""/>
  </#if>
  <#if drillType == "VarChar" || drillType == "Var16Char" || drillType == "VarBinary">
      return accessor().get(vectorIndex.vectorIndex(${indexVar}));
  <#elseif drillType == "Decimal9" || drillType == "Decimal18">
      return DecimalUtility.getBigDecimalFromPrimitiveTypes(
                accessor().get(vectorIndex.vectorIndex(${indexVar})),
                type.getScale(),
                type.getPrecision());
  <#elseif accessorType == "BigDecimal" || accessorType == "Period">
      return accessor().${getObject}(vectorIndex.vectorIndex(${indexVar}));
  <#else>
      return accessor().get(vectorIndex.vectorIndex(${indexVar}));
  </#if>
    }
  <#if drillType == "VarChar">

    @Override
    public String getString(<#if isArray>int index</#if>) {
      return new String(getBytes(${indexVar}), Charsets.UTF_8);
    }
  <#elseif drillType == "Var16Char">

    @Override
    public String getString(<#if isArray>int index</#if>) {
      return new String(getBytes(${indexVar}), Charsets.UTF_16);
    }
  </#if>
</#macro>
<#macro build types vectorType accessorType>
  <#if vectorType == "Repeated">
    <#assign fnPrefix = "Array" />
    <#assign classType = "Element" />
  <#else>
    <#assign fnPrefix = vectorType />
    <#assign classType = "Scalar" />
  </#if>
  <#if vectorType == "Required">
    <#assign vectorPrefix = "" />
  <#else>
    <#assign vectorPrefix = vectorType />
  </#if>
  public static void define${fnPrefix}${accessorType}s(
      Class<? extends Base${classType}${accessorType}> ${accessorType?lower_case}s[]) {
  <#list types as type>
  <#list type.minor as minor>
    <#assign drillType=minor.class>
    <#assign notyet=minor.accessorDisabled!type.accessorDisabled!false>
    <#if ! notyet>
    <#assign typeEnum=drillType?upper_case>
    ${accessorType?lower_case}s[MinorType.${typeEnum}.ordinal()] = ${vectorPrefix}${drillType}Column${accessorType}.class;
    </#if>
  </#list>
  </#list>
  }
</#macro>

package org.apache.drill.exec.vector.accessor;

import java.math.BigDecimal;

import org.apache.drill.common.types.TypeProtos.MajorType;
import org.apache.drill.common.types.TypeProtos.MinorType;
import org.apache.drill.exec.memory.BaseAllocator;
import org.apache.drill.exec.vector.*;
import org.apache.drill.exec.util.DecimalUtility;
import org.apache.drill.exec.vector.accessor.reader.BaseScalarReader;
import org.apache.drill.exec.vector.accessor.reader.BaseElementReader;
import org.apache.drill.exec.vector.accessor.reader.VectorAccessor;
import org.apache.drill.exec.vector.accessor.writer.BaseScalarWriter;

import com.google.common.base.Charsets;

import antlr.collections.impl.Vector;
import io.netty.util.internal.PlatformDependent;

import org.joda.time.Period;

/**
 * Basic accessors for most Drill vector types and modes. These are bare-bones
 * accessors: they do only the most rudimentary type conversions. For all,
 * there is only one way to get/set values; they don't convert from, say,
 * a double to an int or visa-versa.
 * <p>
 * Writers work only with single vectors. Readers work with either single
 * vectors or a "hyper vector": a collection of vectors indexed together.
 * The details are hidden behind the {@link RowIndex} interface. If the reader
 * accesses a single vector, then the mutator is cached at bind time. However,
 * if the reader works with a hyper vector, then the vector is null at bind
 * time and must be retrieved for each row (since the vector differs row-by-
 * row.)
 */

// This class is generated using freemarker and the ${.template_name} template.

public class ColumnAccessors {

  public static class OffsetWriterIndex implements ColumnWriterIndex {
    private ColumnWriterIndex baseIndex;

    private OffsetWriterIndex(ColumnWriterIndex baseIndex) {
      this.baseIndex = baseIndex;
    }

    @Override public int vectorIndex() { return baseIndex.vectorIndex() + 1; }
    @Override public void overflowed() { baseIndex.overflowed(); }
    @Override public boolean legal() { return baseIndex.legal(); }
    @Override public void nextElement() { }
  }

<#list vv.types as type>
  <#list type.minor as minor>
    <#assign drillType=minor.class>
    <#assign javaType=minor.javaType!type.javaType>
    <#assign accessorType=minor.accessorType!type.accessorType!minor.friendlyType!javaType>
    <#assign label=minor.accessorLabel!type.accessorLabel!accessorType?capitalize>
    <#assign notyet=minor.accessorDisabled!type.accessorDisabled!false>
    <#assign cast=minor.accessorCast!minor.accessorCast!type.accessorCast!"none">
    <#assign friendlyType=minor.friendlyType!"">
    <#if accessorType=="BigDecimal">
      <#assign label="Decimal">
    </#if>
    <#if drillType == "VarChar" || drillType == "Var16Char">
      <#assign accessorType = "byte[]">
      <#assign label = "Bytes">
    </#if>
    <#if ! notyet>
  //------------------------------------------------------------------------
  // ${drillType} readers and writers

  public static class ${drillType}ColumnReader extends BaseScalarReader {

    <@bindReader "" drillType false />

    <@getType drillType label />

    <@get drillType accessorType label false/>
  }

  public static class Nullable${drillType}ColumnReader extends BaseScalarReader {

    <@bindReader "Nullable" drillType false />

    <@getType drillType label />

    @Override
    public boolean isNull() {
      return accessor().isNull(vectorIndex.vectorIndex());
    }

    <@get drillType accessorType label false />
  }

  public static class Repeated${drillType}ColumnReader extends BaseElementReader {

    <@bindReader "" drillType true />

    <@getType drillType label />

    <@get drillType accessorType label true />
  }

  public static class ${drillType}ColumnWriter extends BaseScalarWriter {
      <#if drillType = "Decimal9" || drillType == "Decimal18" ||
           drillType == "Decimal28Sparse" || drillType == "Decimal38Sparse">
    private MajorType type;
      </#if>
      <#if drillType == "VarChar" || drillType == "Var16Char" || drillType == "VarBinary">
    private UInt4ColumnWriter offsetsWriter = new UInt4ColumnWriter();
    private int writeOffset;
      <#else>
    private final int VALUE_WIDTH = ${drillType}Vector.VALUE_WIDTH;
      </#if>
    private ${drillType}Vector vector;

    @Override
    public void bindVector(ValueVector vector) {
      <#if drillType = "Decimal9" || drillType == "Decimal18" ||
           drillType == "Decimal28Sparse" || drillType == "Decimal38Sparse">
      type = vector.getField().getType();
      </#if>
      this.vector = (${drillType}Vector) vector;
      setAddr(this.vector.getBuffer());
      <#if drillType == "VarChar" || drillType == "Var16Char" || drillType == "VarBinary">
      offsetsWriter.bindVector(this.vector.getOffsetVector());
      writeOffset = 0;
      </#if>
      lastWriteIndex = 0;
    }

      <#if drillType == "VarChar" || drillType == "Var16Char" || drillType == "VarBinary">
    @Override
    public void bindIndex(ColumnWriterIndex index) {
      offsetsWriter.bindIndex(new OffsetWriterIndex(index));
      super.bindIndex(index);
    }

      </#if>
    <@getType drillType label />

      <#if accessorType == "byte[]">
        <#assign args = ", int len">
      <#else>
        <#assign args = "">
      </#if>
      <#if javaType == "char">
        <#assign putType = "short" />
        <#assign doCast = true />
      <#else>
        <#assign putType = javaType />
        <#assign doCast = (cast == "set") />
      </#if>
    <#-- This is performance critical code; every operation counts.
         Please thoughtful when changing the code.
         Generated per class in the belief that the JVM will optimize the
         code path for each value width. Also, the reallocRaw() and
         setFoo() methods are type specific. (reallocRaw() could be virtual,
         but the PlatformDependent.setFoo() cannot be. -->
      <#if drillType == "VarChar" || drillType == "Var16Char" || drillType == "VarBinary">
        <#assign width = "width" />
        <#assign varWidth = true />
    private int writeOffset(int width) {
      <#else>
        <#assign width = "VALUE_WIDTH" />
        <#assign varWidth = false />
    private int writeOffset() {
      </#if>
      <#-- Check if write is legal, but only if assertions are enabled. -->
      assert vectorIndex.legal();
      <#if ! varWidth>
      int writeIndex = vectorIndex.vectorIndex();
      int writeOffset = writeIndex * VALUE_WIDTH;
      </#if>
      <#-- The normal case is that the data fits. This is the only bounds check
           we want to do for the entire set operation.
           Otherwise, we must grow the buffer. Now is the time to check the absolute vector
           limit. That means we check this limit infrequently. -->
      final int nextOffset = writeOffset + ${width};
      if (nextOffset > capacity) {
        <#-- Two cases: grow this vector or allocate a new one. -->
        if (nextOffset > ValueVector.MAX_BUFFER_SIZE) {
          <#-- Allocate a new vector, or throw an exception if overflow is not supported.
               If overflow is supported, the callback will call finish(), which will
               fill empties, so no need to do that here. The call to finish() will
               also set the final writer index for the current vector. Then, bindVector() will
               be called to provide the new vector. The write index changes with
               the new vector. -->
          vectorIndex.overflowed();
      <#if varWidth>
          writeOffset = 0;
      <#else>
          writeIndex = vectorIndex.vectorIndex();
          writeOffset = writeIndex * VALUE_WIDTH;
      </#if>
        } else {
          <#-- Optimized form of reAlloc() which does not zero memory, does not do bounds
               checks (since they were already done above) and which returns
               the new buffer to save a method call. The write index and offset
               remain unchanged. -->
          setAddr(vector.reallocRaw(BaseAllocator.nextPowerOfTwo(nextOffset)));
        }
      }
      <#-- Fill empties. This is required because the allocated memory is not
           zero-filled. -->
      <#if ! varWidth>
      while (++lastWriteIndex < writeIndex) {
        <#if drillType == "Decimal9">
        PlatformDependent.putInt(bufAddr + lastWriteIndex * VALUE_WIDTH, 0);
        <#elseif drillType == "Decimal18">
        PlatformDependent.putLong(bufAddr + lastWriteIndex * VALUE_WIDTH, 0);
        <#elseif drillType == "Decimal28Sparse" || drillType == "Decimal38Sparse">
        long addr = bufAddr + lastWriteIndex * VALUE_WIDTH;
        for (int i = 0; i < VALUE_WIDTH / 4; i++, addr += VALUE_WIDTH) {
          PlatformDependent.putInt(addr, 0);
        }
        <#elseif drillType == "IntervalYear">
        PlatformDependent.putInt(bufAddr + lastWriteIndex * VALUE_WIDTH, 0);
        <#elseif drillType == "IntervalDay">
        final long addr = bufAddr + lastWriteIndex * VALUE_WIDTH;
        PlatformDependent.putInt(addr,     0);
        PlatformDependent.putInt(addr + 4, 0);
        <#elseif drillType == "Interval">
        final long addr = bufAddr + lastWriteIndex * VALUE_WIDTH;
        PlatformDependent.putInt(addr,     0);
        PlatformDependent.putInt(addr + 4, 0);
        PlatformDependent.putInt(addr + 8, 0);
        <#elseif drillType == "Float4">
        PlatformDependent.putInt(bufAddr + lastWriteIndex * VALUE_WIDTH, 0);
        <#elseif drillType == "Float8">
        PlatformDependent.putLong(bufAddr + lastWriteIndex * VALUE_WIDTH, 0);
        <#else>
        PlatformDependent.put${putType?cap_first}(bufAddr + lastWriteIndex * VALUE_WIDTH, <#if doCast>(${putType}) </#if>0);
        </#if>
      }
      </#if>
      <#-- Return the direct memory buffer address. OK because, by the time we
           get here, the address will remain fixed for the rest of the set operation. -->
      return writeOffset;
    }

    @Override
    public void set${label}(${accessorType} value${args}) {
      <#-- Must compute the write offset first; can't be inline because the
           writeOffset() function has a side effect of possibly changing the buffer
           address (bufAddr). -->
      <#if drillType == "VarChar" || drillType == "Var16Char" || drillType == "VarBinary">
      final int offset = writeOffset(len);
      <#else>
      final int offset = writeOffset();
      </#if>
      <#if drillType == "VarChar" || drillType == "Var16Char" || drillType == "VarBinary">
      PlatformDependent.copyMemory(value, 0, bufAddr + offset, len);
      writeOffset += len;
      offsetsWriter.setInt(writeOffset);
      <#elseif drillType == "Decimal9">
      PlatformDependent.putInt(bufAddr + offset,
          DecimalUtility.getDecimal9FromBigDecimal(value,
                type.getScale(), type.getPrecision()));
      <#elseif drillType == "Decimal18">
      PlatformDependent.putLong(bufAddr + offset,
          DecimalUtility.getDecimal18FromBigDecimal(value,
                type.getScale(), type.getPrecision()));
      <#elseif drillType == "Decimal38Sparse">
      <#-- Hard to optimize this case. Just use the available tools. -->
      DecimalUtility.getSparseFromBigDecimal(value, vector.getBuffer(), offset,
               type.getScale(), type.getPrecision(), 6);
      <#elseif drillType == "Decimal28Sparse">
      <#-- Hard to optimize this case. Just use the available tools. -->
      DecimalUtility.getSparseFromBigDecimal(value, vector.getBuffer(), offset,
               type.getScale(), type.getPrecision(), 5);
      <#elseif drillType == "IntervalYear">
      PlatformDependent.putInt(bufAddr + offset,
                value.getYears() * 12 + value.getMonths());
      <#elseif drillType == "IntervalDay">
      final long addr = bufAddr + offset;
      PlatformDependent.putInt(addr,     value.getDays());
      PlatformDependent.putInt(addr + 4, periodToMillis(value));
      <#elseif drillType == "Interval">
      final long addr = bufAddr + offset;
      PlatformDependent.putInt(addr,     value.getYears() * 12 + value.getMonths());
      PlatformDependent.putInt(addr + 4, value.getDays());
      PlatformDependent.putInt(addr + 8, periodToMillis(value));
      <#elseif drillType == "Float4">
      PlatformDependent.putInt(bufAddr + offset, Float.floatToRawIntBits((float) value));
      <#elseif drillType == "Float8">
      PlatformDependent.putLong(bufAddr + offset, Double.doubleToRawLongBits(value));
      <#else>
      PlatformDependent.put${putType?cap_first}(bufAddr + offset, <#if doCast>(${putType}) </#if>value);
      </#if>
      vectorIndex.nextElement();
    }
    <#if drillType == "VarChar">

    @Override
    public void setString(String value) {
      final byte bytes[] = value.getBytes(Charsets.UTF_8);
      setBytes(bytes, bytes.length);
    }
    <#elseif drillType == "Var16Char">

    @Override
    public void setString(String value) {
      final byte bytes[] = value.getBytes(Charsets.UTF_8);
      setBytes(bytes, bytes.length);
    }
    </#if>

    @Override
    public void finish() {
      <#if varWidth>
      offsetsWriter.finish();
      </#if>
      <#-- Done this way to avoid another drill buf access in value set path.
           Though this calls writeOffset(), which handles vector overflow,
           such overflow should never occur because here we are simply
           finalizing a position already set. However, the vector size may
           grow and the "missing" values may be zero-filled. Note that, in
           odd cases, the call to writeOffset() might cause the vector to
           resize (as part of filling empties), so grab the buffer AFTER
           the call to writeOffset(). -->
      final int finalIndex = writeOffset(<#if varWidth>0</#if>);
      vector.getBuffer().writerIndex(finalIndex);
    }
  }

    </#if>
  </#list>
</#list>
  public static int periodToMillis(Period value) {
    return ((value.getHours() * 60 +
             value.getMinutes()) * 60 +
             value.getSeconds()) * 1000 +
           value.getMillis();
  }
<@build vv.types "Required" "Reader" />

<@build vv.types "Nullable" "Reader" />

<@build vv.types "Repeated" "Reader" />

<@build vv.types "Required" "Writer" />
}
