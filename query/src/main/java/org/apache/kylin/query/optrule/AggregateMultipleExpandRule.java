package org.apache.kylin.query.optrule;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptRuleOperand;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.ImmutableBitSet;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Supoort grouping query. Expand the non-simple aggregate to more than one simple aggregates.
 * Add project on expanded simple aggregate to add indicators of origin aggregate.
 * All projects on aggregate added into one union, which replace the origin aggregate.
 * The new aggregates will be transformed by {@link org.apache.kylin.query.optrule.AggregateProjectReduceRule}, to reduce rolled up dimensions.
 * In case to scan other cuboid data without the rolled up dimensions.
 *
 * <p>Examples:
 * <p>  Origin Aggregate:   {@code group by grouping sets ((dim A, dim B, dim C), (dim A, dim C), (dim B, dim C))}
 * <p>  Transformed Union:
 *     {@code select dim A, dim B, dim C, 0, 0, 0
 *            union all
 *            select dim A, null, dim C, 0, 1, 0
 *            union all
 *            select null, dim B, dim C, 1, 0, 0
 *     }
 */
public class AggregateMultipleExpandRule extends RelOptRule {
    public static final AggregateMultipleExpandRule INSTANCE = new AggregateMultipleExpandRule(//
            operand(LogicalAggregate.class, null, new Predicate<Aggregate>() {
                @Override
                public boolean apply(@Nullable Aggregate input) {
                    return input.getGroupType() != Aggregate.Group.SIMPLE;
                }
            }, operand(RelNode.class, any())), "AggregateMultipleExpandRule");

    private AggregateMultipleExpandRule(RelOptRuleOperand operand, String description) {
        super(operand, description);
    }

    private static List<ImmutableBitSet> asList(ImmutableBitSet groupSet) {
        ArrayList<ImmutableBitSet> l = new ArrayList<>(1);
        l.add(groupSet);
        return l;
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        LogicalAggregate aggr = (LogicalAggregate) call.getRelList().get(0);
        RelNode input = aggr.getInput();
        RelBuilder relBuilder = call.builder();
        RexBuilder rexBuilder = aggr.getCluster().getRexBuilder();

        for (ImmutableBitSet groupSet : aggr.getGroupSets()) {
            // push the simple aggregate with one group set
            relBuilder.push(aggr.copy(aggr.getTraitSet(), input, false, groupSet, asList(groupSet), aggr.getAggCallList()));

            ImmutableList.Builder<RexNode> rexNodes = new ImmutableList.Builder<>();
            int index = 0;
            Iterator<Integer> groupSetIter = aggr.getGroupSet().iterator();
            Iterator<RelDataTypeField> typeIterator = aggr.getRowType().getFieldList().iterator();
            Iterator<Integer> groupKeyIter = groupSet.iterator();
            int groupKey = groupKeyIter.next();

            // iterate the group keys, fill with null if the key is rolled up
            while (groupSetIter.hasNext()) {
                Integer aggrGroupKey = groupSetIter.next();
                RelDataType type = typeIterator.next().getType();
                if (groupKey == aggrGroupKey) {
                    rexNodes.add(rexBuilder.makeInputRef(type, index++));
                    groupKey = groupKeyIter.next();
                } else {
                    rexNodes.add(rexBuilder.makeNullLiteral(type.getSqlTypeName()));
                }
            }

            // fill indicators if need, false when key is present and true if key is rolled up
            if (aggr.indicator) {
                groupSetIter = aggr.getGroupSet().iterator();
                groupKeyIter = groupSet.iterator();
                groupKey = groupKeyIter.next();
                while (groupSetIter.hasNext()) {
                    Integer aggrGroupKey = groupSetIter.next();
                    RelDataType type = typeIterator.next().getType();
                    if (groupKey == aggrGroupKey) {
                        rexNodes.add(rexBuilder.makeLiteral(false, type, true));
                        groupKey = groupKeyIter.next();
                    } else {
                        rexNodes.add(rexBuilder.makeLiteral(true, type, true));
                    }
                }
            }

            // fill aggr calls input ref
            while (typeIterator.hasNext()) {
                RelDataType type = typeIterator.next().getType();
                rexNodes.add(rexBuilder.makeInputRef(type, index++));
            }
            relBuilder.project(rexNodes.build());
        }
        RelNode unionAggr = relBuilder.union(true, aggr.getGroupSets().size()).build();

        call.transformTo(unionAggr);
    }
}
