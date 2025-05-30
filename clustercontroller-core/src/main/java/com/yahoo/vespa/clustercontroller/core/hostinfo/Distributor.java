// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.clustercontroller.core.hostinfo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Class for handling Distributor part of HostInfo.
 * 
 * @author Haakon Dybdahl
 */
public class Distributor {

    @JsonProperty("global-stats")
    private DistributorGlobalStats globalStats = DistributorGlobalStats.EMPTY;
    @JsonProperty("storage-nodes")
    private List<StorageNode> storageNodes = new ArrayList<>();

    public Long documentCountTotalOrNull() { return globalStats.storedDocumentCount(); }
    public Long bytesTotalOrNull() { return globalStats.storedDocumentBytes(); }
    public List<StorageNode> getStorageNodes() { return storageNodes; }

}
