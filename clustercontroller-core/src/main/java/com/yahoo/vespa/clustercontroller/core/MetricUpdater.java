// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.clustercontroller.core;

import com.yahoo.vdslib.state.ClusterState;
import com.yahoo.vdslib.state.Node;
import com.yahoo.vdslib.state.NodeState;
import com.yahoo.vdslib.state.NodeType;
import com.yahoo.vdslib.state.State;
import com.yahoo.vespa.clustercontroller.utils.util.ComponentMetricReporter;
import com.yahoo.vespa.clustercontroller.utils.util.MetricReporter;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

public class MetricUpdater {

    private final ComponentMetricReporter metricReporter;
    private final Timer timer;
    // Publishing and converging on a cluster state version is never instant nor atomic, but
    // it usually completes within a few seconds. If convergence does not happen for more than
    // 30 seconds, it's a sign something has stalled.
    private Duration stateVersionConvergenceGracePeriod = Duration.ofSeconds(30);

    public MetricUpdater(MetricReporter metricReporter, Timer timer, int controllerIndex, String clusterName) {
        this.metricReporter = new ComponentMetricReporter(metricReporter, "cluster-controller.");
        this.metricReporter.addDimension("controller-index", String.valueOf(controllerIndex));
        this.metricReporter.addDimension("cluster", clusterName);
        this.metricReporter.addDimension("clusterid", clusterName);
        this.timer = timer;
    }

    public MetricReporter.Context createContext(Map<String, String> dimensions) {
        return metricReporter.createContext(dimensions);
    }

    public void setStateVersionConvergenceGracePeriod(Duration gracePeriod) {
        stateVersionConvergenceGracePeriod = gracePeriod;
    }

    private static int nodesInAvailableState(Map<State, Integer> nodeCounts) {
        return nodeCounts.getOrDefault(State.INITIALIZING, 0)
                + nodeCounts.getOrDefault(State.RETIRED, 0)
                + nodeCounts.getOrDefault(State.UP, 0)
                // Even though technically not true, we here treat Maintenance as an available state to
                // avoid triggering false alerts when a node is taken down transiently in an orchestrated manner.
                + nodeCounts.getOrDefault(State.MAINTENANCE, 0);
    }

    public void updateClusterStateMetrics(ContentCluster cluster, ClusterState state,
                                          ResourceUsageStats resourceUsage, Instant lastStateBroadcastTimePoint) {
        Map<String, String> dimensions = new HashMap<>();
        Instant now = timer.getCurrentWallClockTime();
        // NodeInfo::getClusterStateVersionBundleAcknowledged() returns -1 if the node has not yet ACKed a
        // cluster state version. Check for this version explicitly if we've yet to publish a state. This
        // will prevent the node from being erroneously counted as divergent (can't reasonably diverge from
        // something that doesn't exist...!).
        int effectiveStateVersion = (state.getVersion() > 0) ? state.getVersion() : -1;
        boolean convergenceDeadlinePassed = lastStateBroadcastTimePoint.plus(stateVersionConvergenceGracePeriod).isBefore(now);
        for (NodeType type : NodeType.getTypes()) {
            dimensions.put("node-type", type.toString().toLowerCase());
            MetricReporter.Context context = createContext(dimensions);
            Map<State, Integer> nodeCounts = new HashMap<>();
            for (State s : State.values()) {
                nodeCounts.put(s, 0);
            }
            int nodesNotConverged = 0;
            for (Integer i : cluster.getConfiguredNodes().keySet()) {
                var node = new Node(type, i);
                NodeState s = state.getNodeState(node);
                Integer count = nodeCounts.get(s.getState());
                nodeCounts.put(s.getState(), count + 1);
                var info = cluster.getNodeInfo(node);
                if (info != null && convergenceDeadlinePassed && s.getState().oneOf("uir")) {
                    if (info.getClusterStateVersionBundleAcknowledged() != effectiveStateVersion) {
                        nodesNotConverged++;
                    }
                }
            }
            for (State s : State.values()) {
                String name = s.toString().toLowerCase() + ".count";
                metricReporter.set(name, nodeCounts.get(s), context);
            }

            final int availableNodes = nodesInAvailableState(nodeCounts);
            final int totalNodes = Math.max(cluster.getConfiguredNodes().size(), 1); // Assumes 1-1 between distributor and storage
            metricReporter.set("available-nodes.ratio", (double)availableNodes / totalNodes, context);
            metricReporter.set("nodes-not-converged", nodesNotConverged, context);
        }
        dimensions.remove("node-type");
        MetricReporter.Context context = createContext(dimensions);
        metricReporter.add("cluster-state-change", 1, context);

        metricReporter.set("resource_usage.max_disk_utilization", resourceUsage.getMaxDiskUtilization(), context);
        metricReporter.set("resource_usage.max_memory_utilization", resourceUsage.getMaxMemoryUtilization(), context);
        metricReporter.set("resource_usage.nodes_above_limit", resourceUsage.getNodesAboveLimit(), context);
        metricReporter.set("resource_usage.disk_limit", resourceUsage.getDiskLimit(), context);
        metricReporter.set("resource_usage.memory_limit", resourceUsage.getMemoryLimit(), context);
    }

    public void updateMasterElectionMetrics(Map<Integer, Integer> data) {
        Map<Integer, Integer> voteCounts = new HashMap<>();
        for(Integer i : data.values()) {
            int count = (voteCounts.get(i) == null ? 0 : voteCounts.get(i));
            voteCounts.put(i, count + 1);
        }
        SortedSet<Integer> counts = new TreeSet<>(voteCounts.values());
        if (counts.size() > 1 && counts.first() > counts.last()) {
            throw new IllegalStateException("Assumed smallest count is sorted first");
        }
        int maxCount = counts.isEmpty() ? 0 : counts.last();
        metricReporter.set("agreed-master-votes", maxCount);
    }

    public void updateMasterState(boolean isMaster) {
        metricReporter.set("is-master", isMaster ? 1 : 0);
    }

    public void updateClusterBucketsOutOfSyncRatio(double ratio) {
        metricReporter.set("cluster-buckets-out-of-sync-ratio", ratio);
    }

    public void updateClusterDocumentMetrics(long docsTotal, long bytesTotal) {
        metricReporter.set("stored-document-count", docsTotal);
        metricReporter.set("stored-document-bytes", bytesTotal);
    }

    public void addTickTime(long millis, boolean didWork) {
        if (didWork) {
            metricReporter.set("busy-tick-time-ms", millis);
        } else {
            metricReporter.set("idle-tick-time-ms", millis);
        }
    }

    public void recordNewNodeEvent() {
        // TODO(hakonhall): Replace add() with a persistent aggregate metric.
        metricReporter.add("node-event", 1);
    }

    public void updateRemoteTaskQueueSize(int size) {
        metricReporter.set("remote-task-queue.size", size);
    }

    public boolean forWork(String workId, BooleanSupplier work) {
        long startNanos = System.nanoTime();
        boolean didWork = work.getAsBoolean();
        double seconds = Duration.ofNanos(System.nanoTime() - startNanos).toMillis() / 1000.;

        MetricReporter.Context context = createContext(Map.of("didWork", Boolean.toString(didWork),
                                                              "workId", workId));
        metricReporter.set("work-ms", seconds, context);

        return didWork;
    }
}
