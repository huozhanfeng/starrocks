// This file is licensed under the Elastic License 2.0. Copyright 2021 StarRocks Limited.

package com.starrocks.sql.optimizer.operator.physical;

import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptExpressionVisitor;
import com.starrocks.sql.optimizer.base.ColumnRefSet;
import com.starrocks.sql.optimizer.operator.AggType;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.OperatorVisitor;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PhysicalHashAggregateOperator extends PhysicalOperator {
    private final AggType type;
    private final List<ColumnRefOperator> groupBys;
    // For normal aggregate function, partitionByColumns are same with groupingKeys
    // but for single distinct function, partitionByColumns are not same with groupingKeys
    private final List<ColumnRefOperator> partitionByColumns;
    private Map<ColumnRefOperator, CallOperator> aggregations;

    // When generate plan fragment, we need this info.
    // For select count(distinct id_bigint), sum(id_int) from test_basic;
    // In the distinct local (update serialize) agg stage:
    // |   5:AGGREGATE (update serialize)                                                      |
    //|   |  output: count(<slot 13>), sum(<slot 16>)                                         |
    //|   |  group by:                                                                        |
    // count function is update function, but sum is merge function
    // if singleDistinctFunctionPos is -1, means no single distinct function
    private final int singleDistinctFunctionPos;

    // The flag for this aggregate operator has split to
    // two stage aggregate or three stage aggregate
    private boolean isSplit;

    public PhysicalHashAggregateOperator(AggType type,
                                         List<ColumnRefOperator> groupBys,
                                         List<ColumnRefOperator> partitionByColumns,
                                         Map<ColumnRefOperator, CallOperator> aggregations,
                                         int singleDistinctFunctionPos,
                                         boolean isSplit) {
        super(OperatorType.PHYSICAL_HASH_AGG);
        this.type = type;
        this.groupBys = groupBys;
        this.partitionByColumns = partitionByColumns;
        this.aggregations = aggregations;
        this.singleDistinctFunctionPos = singleDistinctFunctionPos;
        this.isSplit = isSplit;
    }

    public List<ColumnRefOperator> getGroupBys() {
        return groupBys;
    }

    public Map<ColumnRefOperator, CallOperator> getAggregations() {
        return aggregations;
    }

    public AggType getType() {
        return type;
    }

    public List<ColumnRefOperator> getPartitionByColumns() {
        return partitionByColumns;
    }

    public boolean hasSingleDistinct() {
        return singleDistinctFunctionPos > -1;
    }

    public int getSingleDistinctFunctionPos() {
        return singleDistinctFunctionPos;
    }

    public boolean isSplit() {
        return isSplit;
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, groupBys, aggregations.keySet());
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof PhysicalHashAggregateOperator)) {
            return false;
        }

        PhysicalHashAggregateOperator rhs = (PhysicalHashAggregateOperator) obj;
        if (this == rhs) {
            return true;
        }

        return type.equals(rhs.type) &&
                groupBys.equals(rhs.groupBys) &&
                aggregations.keySet().equals(rhs.aggregations.keySet());
    }

    @Override
    public String toString() {
        return "PhysicalHashAggregate" + " type " + type.toString();
    }

    @Override
    public <R, C> R accept(OperatorVisitor<R, C> visitor, C context) {
        return visitor.visitPhysicalHashAggregate(this, context);
    }

    @Override
    public <R, C> R accept(OptExpressionVisitor<R, C> visitor, OptExpression optExpression, C context) {
        return visitor.visitPhysicalHashAggregate(optExpression, context);
    }

    @Override
    public ColumnRefSet getUsedColumns() {
        ColumnRefSet set = super.getUsedColumns();
        groupBys.forEach(set::union);
        partitionByColumns.forEach(set::union);
        aggregations.values().forEach(d -> set.union(d.getUsedColumns()));
        return set;
    }
}
