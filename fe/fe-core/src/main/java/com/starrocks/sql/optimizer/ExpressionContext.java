// This file is licensed under the Elastic License 2.0. Copyright 2021 StarRocks Limited.

package com.starrocks.sql.optimizer;

import com.google.common.collect.Lists;
import com.starrocks.sql.optimizer.base.ColumnRefSet;
import com.starrocks.sql.optimizer.base.LogicalProperty;
import com.starrocks.sql.optimizer.operator.Operator;
import com.starrocks.sql.optimizer.statistics.Statistics;

import java.util.List;

/**
 * ExpressionContext to convey context wherever an expression is used in a shallow
 * context, i.e. operator and the properties of its children but no
 * access to the children is needed.
 * <p>
 * Context and wrapper for {@link GroupExpression} and {@link OptExpression};
 * <p>
 * A ExpressionHandle handle is attached to
 * either an {@link GroupExpression} or a {@link OptExpression}
 */
public class ExpressionContext {
    private OptExpression expression;
    private GroupExpression groupExpression;

    private LogicalProperty rootProperty;
    private final List<LogicalProperty> childrenProperty = Lists.newArrayList();

    private Statistics statistics;
    private final List<Statistics> childrenStatistics = Lists.newArrayList();

    public ExpressionContext(OptExpression expression) {
        this.expression = expression;

        rootProperty = expression.getLogicalProperty();
        statistics = expression.getStatistics();

        // Add child property and statistics
        for (OptExpression child : expression.getInputs()) {
            childrenProperty.add(child.getLogicalProperty());
            childrenStatistics.add(child.getStatistics());
        }
    }

    public ExpressionContext(GroupExpression groupExpression) {
        this.groupExpression = groupExpression;

        rootProperty = groupExpression.getGroup().getLogicalProperty();
        statistics = groupExpression.getGroup().getStatistics();

        // Add child property and statistics
        for (Group group : groupExpression.getInputs()) {
            childrenProperty.add(group.getLogicalProperty());
            if (group.getConfidenceStatistics() != null) {
                childrenStatistics.add(group.getConfidenceStatistics());
            } else {
                childrenStatistics.add(group.getStatistics());
            }
        }
    }

    public Operator getOp() {
        if (expression != null) {
            return expression.getOp();
        }
        return groupExpression.getOp();
    }

    public int arity() {
        if (expression != null) {
            return expression.arity();
        }
        return groupExpression.arity();
    }

    public LogicalProperty getRootProperty() {
        return rootProperty;
    }

    public LogicalProperty getChildLogicalProperty(int idx) {
        return childrenProperty.get(idx);
    }

    public Statistics getChildStatistics(int idx) {
        return childrenStatistics.get(idx);
    }

    public List<Statistics> getChildrenStatistics() {
        return childrenStatistics;
    }

    public ColumnRefSet getChildOutputColumns(int index) {
        return childrenProperty.get(index).getOutputColumns();
    }

    public int getChildLeftMostScanTabletsNum(int index) {
        return childrenProperty.get(index).getLeftMostScanTabletsNum();
    }

    public boolean isExecuteInOneTablet(int index) {
        return childrenProperty.get(index).isExecuteInOneTablet();
    }

    public Operator getChildOperator(int index) {
        if (expression != null) {
            return expression.getInputs().get(index).getOp();
        } else {
            return groupExpression.getInputs().get(index).getFirstLogicalExpression().getOp();
        }
    }

    public ExpressionContext getChildContext(int index) {
        if (expression != null) {
            return new ExpressionContext(expression.getInputs().get(index));
        } else {
            return new ExpressionContext(groupExpression.getInputs().get(index).getFirstLogicalExpression());
        }
    }

    public Statistics getStatistics() {
        return statistics;
    }

    public void setStatistics(Statistics statistics) {
        this.statistics = statistics;
    }

    public void deriveLogicalProperty() {
        rootProperty = new LogicalProperty();
        rootProperty.derive(this);
    }
}
