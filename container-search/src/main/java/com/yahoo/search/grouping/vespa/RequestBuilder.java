// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.search.grouping.vespa;

import com.yahoo.processing.IllegalInputException;
import com.yahoo.search.grouping.Continuation;
import com.yahoo.search.grouping.GroupingRequest;
import com.yahoo.search.grouping.request.AllOperation;
import com.yahoo.search.grouping.request.EachOperation;
import com.yahoo.search.grouping.request.GroupingExpression;
import com.yahoo.search.grouping.request.GroupingOperation;
import com.yahoo.search.grouping.request.NegFunction;
import com.yahoo.searchlib.aggregation.AggregationResult;
import com.yahoo.searchlib.aggregation.ExpressionCountAggregationResult;
import com.yahoo.searchlib.aggregation.Group;
import com.yahoo.searchlib.aggregation.Grouping;
import com.yahoo.searchlib.aggregation.GroupingLevel;
import com.yahoo.searchlib.aggregation.HitsAggregationResult;
import com.yahoo.searchlib.expression.ExpressionNode;
import com.yahoo.searchlib.expression.FilterExpressionNode;
import com.yahoo.searchlib.expression.RangeBucketPreDefFunctionNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Deque;
import java.util.TimeZone;

/**
 * This class implements the necessary logic to build a list of {@link Grouping} objects from an instance of {@link
 * GroupingOperation}. It is used by the {@link GroupingExecutor}.
 *
 * @author Simon Thoresen Hult
 */
class RequestBuilder {

    private static final int LOOKAHEAD = 1;
    private final ExpressionConverter converter = new ExpressionConverter();
    private final List<Grouping> requestList = new LinkedList<>();
    private final GroupingTransform transform;
    private GroupingOperation root;
    private int tag = 0;
    private int defaultMaxHits = -1;
    private int defaultMaxGroups = -1;
    private long globalMaxGroups = -1;
    private long totalGroupsAndSummaries = -1;
    private double defaultPrecisionFactor = -1;

    /**
     * Constructs a new instance of this class.
     *
     * @param requestId The id of the corresponding {@link GroupingRequest}.
     */
    public RequestBuilder(int requestId) {
        this.transform = new GroupingTransform(requestId);
    }

    /**
     * Sets the abstract syntax tree of the request whose back-end queries to create.
     *
     * @param root The grouping request to convert.
     * @return This, to allow chaining.
     */
    public RequestBuilder setRootOperation(GroupingOperation root) {
        Objects.requireNonNull(root, "Root must be non-null");
        this.root = root;
        return this;
    }

    /**
     * Sets the time zone to build the request for. This information is propagated to the time-based grouping
     * expressions so that the produced groups are reasonable for the given zone.
     *
     * @param timeZone The time zone to set.
     * @return This, to allow chaining.
     */
    public RequestBuilder setTimeZone(TimeZone timeZone) {
        converter.setTimeOffset(timeZone != null ? timeZone.getOffset(System.currentTimeMillis())
                                                 : ExpressionConverter.DEFAULT_TIME_OFFSET);
        return this;
    }

    /**
     * Sets the name of the summary class to use if a {@link com.yahoo.search.grouping.request.SummaryValue} has none.
     *
     * @param summaryName The summary class name to set.
     * @return This, to allow chaining.
     */
    public RequestBuilder setDefaultSummaryName(String summaryName) {
        converter.setDefaultSummaryName(summaryName != null ? summaryName
                                                            : ExpressionConverter.DEFAULT_SUMMARY_NAME);
        return this;
    }

    /**
     * Returns the transform that was created when {@link #build()} was called.
     *
     * @return The grouping transform that was built.
     */
    public GroupingTransform getTransform() {
        return transform;
    }

    /**
     * Returns the list of grouping objects that were created when {@link #build()} was called.
     *
     * @return The list of built grouping objects.
     */
    public List<Grouping> getRequestList() {
        return requestList;
    }

    /**
     * Constructs a set of Vespa specific grouping request that corresponds to the parameters given to this builder.
     * This method might fail due to unsupported constructs in the request, in which case an exception is thrown.
     *
     * @throws IllegalStateException         If this method is called more than once.
     * @throws IllegalInputException If the grouping request contains unsupported constructs.
     */
    public void build() {
        if (tag != 0) {
            throw new IllegalStateException();
        }
        root.resolveLevel(1);

        Grouping grouping = new Grouping();
        grouping.getRoot().setTag(++tag);
        grouping.setForceSinglePass(root.getForceSinglePass() || root.containsHint("singlepass"));
        Deque<BuildFrame> stack = new ArrayDeque<>();
        stack.push(new BuildFrame(grouping, new BuildState(), root));
        while (!stack.isEmpty()) {
            BuildFrame frame = stack.pop();
            processRequestNode(frame);
            List<GroupingOperation> children = frame.astNode.getChildren();
            if (children.isEmpty()) {
                requestList.add(frame.grouping);
            } else {
                for (int i = children.size(); --i >= 0; ) {
                    Grouping childGrouping = (i == 0) ? frame.grouping : frame.grouping.clone();
                    BuildState childState = (i == 0) ? frame.state : new BuildState(frame.state);
                    BuildFrame child = new BuildFrame(childGrouping, childState, children.get(i));
                    stack.push(child);
                }
            }
        }
        pruneRequests();
        validateGlobalMax();
    }

    public RequestBuilder addContinuations(Iterable<Continuation> continuations) {
        for (Continuation continuation : continuations) {
            if (continuation == null) {
                continue;
            }
            transform.addContinuation(continuation);
        }
        return this;
    }

    public RequestBuilder setDefaultMaxGroups(int v) { this.defaultMaxGroups = v; return this; }

    public RequestBuilder setDefaultMaxHits(int v) { this.defaultMaxHits = v; return this; }

    public RequestBuilder setGlobalMaxGroups(long v) { this.globalMaxGroups = v; return this; }

    public RequestBuilder setDefaultPrecisionFactor(double v) { this.defaultPrecisionFactor = v; return this; }

    OptionalLong totalGroupsAndSummaries() {
        return totalGroupsAndSummaries != -1 ? OptionalLong.of(totalGroupsAndSummaries) : OptionalLong.empty();
    }

    private void processRequestNode(BuildFrame frame) {
        int level = frame.astNode.getLevel();
        if (level > 2) {
            throw new IllegalInputException("Can not operate on " +
                                                    GroupingOperation.getLevelDesc(level) + ".");
        }
        if (frame.astNode instanceof EachOperation) {
            resolveEach(frame);
        } else {
            resolveOutput(frame);
        }
        resolveState(frame);
        injectGroupByToExpressionCountAggregator(frame);
    }

    private void injectGroupByToExpressionCountAggregator(BuildFrame frame) {
        Group group = getLeafGroup(frame);
        // The ExpressionCountAggregationResult uses the group-by expression to simulate aggregation of list of groups.
        group.getAggregationResults().stream()
                .filter(aggr -> aggr instanceof ExpressionCountAggregationResult)
                .forEach(aggr -> aggr.setExpression(frame.state.groupBy.clone()));
    }

    private void resolveEach(BuildFrame frame) {
        int parentTag = getLeafGroup(frame).getTag();
        if (frame.state.groupBy != null) {
            GroupingLevel grpLevel = new GroupingLevel();
            grpLevel.getGroupPrototype().setTag(++tag);
            grpLevel.setExpression(frame.state.groupBy);
            frame.state.groupBy = null;
            int offset = transform.getOffset(tag);
            if (frame.state.precision != null) {
                grpLevel.setPrecision(frame.state.precision + offset);
                frame.state.precision = null;
            }
            if (frame.state.max != null) {
                transform.putMax(tag, frame.state.max, "group list");
                grpLevel.setMaxGroups(LOOKAHEAD + frame.state.max + offset);
                frame.state.max = null;
            }

            if (frame.state.filterBy != null) {
                grpLevel.setFilter(frame.state.filterBy);
                frame.state.filterBy = null;
            }

            frame.grouping.getLevels().add(grpLevel);
        }
        String label = frame.astNode.getLabel();
        if (label != null) {
            frame.state.label = label;
        }
        if (frame.astNode.getLevel() > 0) {
            transform.putLabel(parentTag, getLeafGroup(frame).getTag(), frame.state.label, "group list");
        }
        resolveOutput(frame);
        if (!frame.state.orderByExp.isEmpty()) {
            GroupingLevel grpLevel = getLeafGroupingLevel(frame);
            for (int i = 0, len = frame.state.orderByExp.size(); i < len; ++i) {
                grpLevel.getGroupPrototype().addOrderBy(frame.state.orderByExp.get(i),
                                                        frame.state.orderByAsc.get(i));
            }
            frame.state.orderByExp.clear();
            frame.state.orderByAsc.clear();
        }
    }

    private void resolveState(BuildFrame frame) {
        resolveGroupBy(frame);
        resolveFilterBy(frame);
        resolveMax(frame);
        resolveOrderBy(frame);
        resolvePrecision(frame);
        resolveWhere(frame);
    }

    private void resolveGroupBy(BuildFrame frame) {
        GroupingExpression exp = frame.astNode.getGroupBy();
        if (exp != null) {
            if (frame.state.groupBy != null) {
                throw new IllegalInputException("Can not group list of groups.");
            }
            frame.state.groupBy = converter.toExpressionNode(exp);
            frame.state.label = exp.toString(); // label for next each()

        } else {
            int level = frame.astNode.getLevel();
            if (level == 0) {
                // no next each()
            } else if (level == 1) {
                frame.state.label = "hits"; // next each() is hitlist
            } else {
                throw new IllegalInputException("Can not create anonymous " +
                                                        GroupingOperation.getLevelDesc(level) + ".");
            }
        }
    }

    private void resolveFilterBy(BuildFrame frame) {
        if (frame.astNode.getFilterBy() != null) {
            frame.state.filterBy = converter.toFilterExpressionNode(frame.astNode.getFilterBy());
        }
    }

    private long computeNewTopN(long oldMax, long newMax) {
        return (oldMax < 0) ? newMax : Math.min(oldMax, newMax);
    }
    private void resolveMax(BuildFrame frame) {
        if (isTopNAllowed(frame)) {
            if (frame.astNode.hasMax() && !frame.astNode.hasUnlimitedMax()) {
                frame.grouping.setTopN(computeNewTopN(frame.grouping.getTopN(), frame.astNode.getMax()));
            }
        } else {
            if (frame.astNode.hasUnlimitedMax()) {
                frame.state.max = null;
            } else if (frame.astNode.hasMax()) {
                frame.state.max = frame.astNode.getMax();
            } else if (frame.state.groupBy != null && defaultMaxGroups != -1) {
                frame.state.max = defaultMaxGroups;
            } else if (frame.state.groupBy == null && defaultMaxHits != -1) {
                frame.state.max = defaultMaxHits;
            }
        }
    }

    private void resolveOrderBy(BuildFrame frame) {
        List<GroupingExpression> lst = frame.astNode.getOrderBy();
        if (lst == null || lst.isEmpty()) {
            return;
        }
        int reqLevel = frame.astNode.getLevel();
        if (reqLevel != 2) {
            throw new IllegalInputException(
                    "Can not order " + GroupingOperation.getLevelDesc(reqLevel) + " content.");
        }
        for (GroupingExpression exp : lst) {
            boolean asc = true;
            if (exp instanceof NegFunction) {
                asc = false;
                exp = ((NegFunction)exp).getArg(0);
            }
            frame.state.orderByExp.add(converter.toExpressionNode(exp));
            frame.state.orderByAsc.add(asc);
        }
    }

    private void resolveOutput(BuildFrame frame) {
        List<GroupingExpression> lst = frame.astNode.getOutputs();
        if (lst == null || lst.isEmpty()) {
            return;
        }
        Group group = getLeafGroup(frame);
        for (GroupingExpression exp : lst) {
            group.addAggregationResult(toAggregationResult(exp, group, frame));
        }
    }

    private AggregationResult toAggregationResult(GroupingExpression exp, Group group, BuildFrame frame) {
        AggregationResult result = converter.toAggregationResult(exp);
        result.setTag(++tag);

        String label = exp.getLabel();
        if (result instanceof HitsAggregationResult hits) {
            if (label != null) {
                throw new IllegalInputException("Can not label expression '" + exp + "'.");
            }
            if (frame.state.max != null) {
                transform.putMax(tag, frame.state.max, "hit list");
                int offset = transform.getOffset(tag);
                hits.setMaxHits(LOOKAHEAD + frame.state.max + offset);
                frame.state.max = null;
            }
            transform.putLabel(group.getTag(), tag, frame.state.label, "hit list");
        } else {
            transform.putLabel(group.getTag(), tag, label != null ? label : exp.toString(), "output");
        }
        return result;
    }

    private void resolvePrecision(BuildFrame frame) {
        int precision = frame.astNode.getPrecision();
        if (precision > 0) {
            frame.state.precision = precision;
        } else if (frame.state.max != null && defaultPrecisionFactor > 0) {
            frame.state.precision = Math.max(1, (int) Math.ceil(frame.state.max * defaultPrecisionFactor));
        }
    }

    private void resolveWhere(BuildFrame frame) {
        String where = frame.astNode.getWhere();
        if (where != null) {
            if (!isRootOperation(frame)) {
                throw new IllegalInputException("Can not apply 'where' to non-root group.");
            }
            switch (where) {
            case "true":
                frame.grouping.setAll(true);
                break;
            case "$query":
                // ignore
                break;
            default:
                throw new IllegalInputException("Operation 'where' does not support '" + where + "'.");
            }
        }
    }

    private boolean isRootOperation(BuildFrame frame) {
        return frame.astNode == root && frame.state.groupBy == null;
    }

    private boolean isTopNAllowed(BuildFrame frame) {
        return (frame.astNode instanceof AllOperation) && (frame.state.groupBy == null);
    }

    private GroupingLevel getLeafGroupingLevel(BuildFrame frame) {
        if (frame.grouping.getLevels().isEmpty()) {
            return null;
        }
        return frame.grouping.getLevels().get(frame.grouping.getLevels().size() - 1);
    }

    private Group getLeafGroup(BuildFrame frame) {
        if (frame.grouping.getLevels().isEmpty()) {
            return frame.grouping.getRoot();
        } else {
            GroupingLevel grpLevel = getLeafGroupingLevel(frame);
            return grpLevel != null ? grpLevel.getGroupPrototype() : null;
        }
    }

    private void pruneRequests() {
        for (int reqIdx = requestList.size(); --reqIdx >= 0; ) {
            Grouping request = requestList.get(reqIdx);
            List<GroupingLevel> lst = request.getLevels();
            for (int lvlIdx = lst.size(); --lvlIdx >= 0; ) {
                if (!lst.get(lvlIdx).getGroupPrototype().getAggregationResults().isEmpty()) {
                    break;
                }
                lst.remove(lvlIdx);
            }
            if (lst.isEmpty() && request.getRoot().getAggregationResults().isEmpty()) {
                requestList.remove(reqIdx);
            }
        }
    }

    private void validateGlobalMax() {
        if (globalMaxGroups < 0) return;

        this.totalGroupsAndSummaries = -1;
        int totalGroupsAndSummaries = 0;
        for (Grouping grp : requestList) {
            int levelMultiplier = 1;
            for (GroupingLevel lvl : grp.getLevels()) {
                totalGroupsAndSummaries += (levelMultiplier *= validateGroupMax(lvl));
                var hars = hitsAggregationResult(lvl);
                for (HitsAggregationResult har : hars) {
                    totalGroupsAndSummaries += (levelMultiplier * validateSummaryMax(har));
                }
            }
        }
        if (totalGroupsAndSummaries > globalMaxGroups)
            throw new IllegalInputException(String.format(
                    "The theoretical total number of groups and summaries in grouping query exceeds " +
                            "'grouping.globalMaxGroups' ( %d > %d ). " +
                            "Either restrict group/summary counts with max() or disable 'grouping.globalMaxGroups'. " +
                            "See https://docs.vespa.ai/en/grouping.html for details.",
                    totalGroupsAndSummaries, globalMaxGroups));
        this.totalGroupsAndSummaries = totalGroupsAndSummaries;
    }

    private int validateGroupMax(GroupingLevel lvl) {
        int max = transform.getMax(lvl.getGroupPrototype().getTag());
        if (lvl.getExpression() instanceof RangeBucketPreDefFunctionNode) {
            int maxBuckets = ((RangeBucketPreDefFunctionNode) lvl.getExpression()).getBucketList().size() + 1; // +1 for "null" bucket
            if (maxBuckets < max || max <= 0) max = maxBuckets;
        }
        if (max <= 0) throw new IllegalInputException(
                "Cannot return unbounded number of groups when 'grouping.globalMaxGroups' is enabled. " +
                        "Either restrict group count with max() or disable 'grouping.globalMaxGroups'. " +
                        "See https://docs.vespa.ai/en/grouping.html for details.");
        return max;
    }

    private int validateSummaryMax(HitsAggregationResult res) {
        int max = transform.getMax(res.getTag());
        if (max <= 0) throw new IllegalInputException(
                "Cannot return unbounded number of summaries when 'grouping.globalMaxGroups' is enabled. " +
                        "Either restrict summary count with max() or disable 'grouping.globalMaxGroups'. " +
                        "See https://docs.vespa.ai/en/grouping.html for details.");
        return max;
    }

    private List<HitsAggregationResult> hitsAggregationResult(GroupingLevel lvl) {
        return lvl.getGroupPrototype().getAggregationResults().stream()
                .filter(ar -> ar instanceof HitsAggregationResult)
                .map(ar -> (HitsAggregationResult) ar)
                .toList();
    }

    private static class BuildFrame {

        final Grouping grouping;
        final BuildState state;
        final GroupingOperation astNode;

        BuildFrame(Grouping grouping, BuildState state, GroupingOperation astNode) {
            this.grouping = grouping;
            this.state = state;
            this.astNode = astNode;
        }
    }

    private static class BuildState {

        final List<ExpressionNode> orderByExp = new ArrayList<>();
        final List<Boolean> orderByAsc = new ArrayList<>();
        ExpressionNode groupBy = null;
        FilterExpressionNode filterBy = null;
        String label = null;
        Integer max = null;
        Integer precision = null;

        BuildState() {
            // empty
        }

        BuildState(BuildState obj) {
            for (ExpressionNode e : obj.orderByExp) {
                orderByExp.add(e.clone());
            }
            orderByAsc.addAll(obj.orderByAsc);
            groupBy = obj.groupBy;
            filterBy = obj.filterBy;
            label = obj.label;
            max = obj.max;
            precision = obj.precision;
        }
    }
}
