// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.clustercontroller.core.hostinfo;

import com.yahoo.vespa.clustercontroller.core.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Class used to create a StorageNodeStatsContainer from HostInfo.
 *
 * @author hakonhall
 */
public class StorageNodeStatsBridge {

    private StorageNodeStatsBridge() { }

    public static ContentClusterStats generate(Distributor distributor) {
        Map<Integer, ContentNodeStats> mapToNodeStats = new HashMap<>();
        for (StorageNode storageNode : distributor.getStorageNodes()) {
            mapToNodeStats.put(storageNode.getIndex(), new ContentNodeStats(storageNode));
        }
        long docsTotal  = Optional.ofNullable(distributor.documentCountTotalOrNull()).orElse(0L);
        long bytesTotal = Optional.ofNullable(distributor.bytesTotalOrNull()).orElse(0L);
        return new ContentClusterStats(docsTotal, bytesTotal, mapToNodeStats);
    }

}
