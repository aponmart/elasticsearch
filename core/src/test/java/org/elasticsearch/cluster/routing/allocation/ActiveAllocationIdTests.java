package org.elasticsearch.cluster.routing.allocation;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.test.ESAllocationTestCase;

import java.util.Arrays;
import java.util.HashSet;

import static org.elasticsearch.cluster.routing.ShardRoutingState.INITIALIZING;
import static org.elasticsearch.cluster.routing.ShardRoutingState.STARTED;
import static org.elasticsearch.cluster.routing.ShardRoutingState.UNASSIGNED;
import static org.hamcrest.Matchers.equalTo;

public class ActiveAllocationIdTests extends ESAllocationTestCase {

    public void testActiveAllocationIdsUpdated() {
        AllocationService allocation = createAllocationService();

        logger.info("creating an index with 1 shard, 2 replicas");
        MetaData metaData = MetaData.builder()
                .put(IndexMetaData.builder("test").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(2))
                // add index metadata where we have no routing nodes to check that allocation ids are not removed
                .put(IndexMetaData.builder("test-old").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(2)
                        .putActiveAllocationIds(0, new HashSet<>(Arrays.asList("x", "y"))))
                .build();
        RoutingTable routingTable = RoutingTable.builder()
                .addAsNew(metaData.index("test"))
                .build();
        ClusterState clusterState = ClusterState.builder(org.elasticsearch.cluster.ClusterName.DEFAULT).metaData(metaData).routingTable(routingTable).build();

        logger.info("adding three nodes and performing rerouting");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder().put(
                newNode("node1")).put(newNode("node2")).put(newNode("node3"))).build();
        RoutingAllocation.Result rerouteResult = allocation.reroute(clusterState, "reroute");
        clusterState = ClusterState.builder(clusterState).routingResult(rerouteResult).build();

        assertThat(clusterState.metaData().index("test").activeAllocationIds(0).size(), equalTo(0));
        assertThat(clusterState.metaData().index("test-old").activeAllocationIds(0), equalTo(new HashSet<>(Arrays.asList("x", "y"))));

        logger.info("start primary shard");
        rerouteResult = allocation.applyStartedShards(clusterState, clusterState.getRoutingNodes().shardsWithState(INITIALIZING));
        clusterState = ClusterState.builder(clusterState).routingResult(rerouteResult).build();

        assertThat(clusterState.getRoutingTable().shardsWithState(STARTED).size(), equalTo(1));
        assertThat(clusterState.metaData().index("test").activeAllocationIds(0).size(), equalTo(1));
        assertThat(clusterState.getRoutingTable().shardsWithState(STARTED).get(0).allocationId().getId(),
                equalTo(clusterState.metaData().index("test").activeAllocationIds(0).iterator().next()));
        assertThat(clusterState.metaData().index("test-old").activeAllocationIds(0), equalTo(new HashSet<>(Arrays.asList("x", "y"))));

        logger.info("start replica shards");
        rerouteResult = allocation.applyStartedShards(clusterState, clusterState.getRoutingNodes().shardsWithState(INITIALIZING));
        clusterState = ClusterState.builder(clusterState).routingResult(rerouteResult).build();

        assertThat(clusterState.metaData().index("test").activeAllocationIds(0).size(), equalTo(3));

        logger.info("remove a node");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .remove("node1"))
                .build();
        rerouteResult = allocation.reroute(clusterState, "reroute");
        clusterState = ClusterState.builder(clusterState).routingResult(rerouteResult).build();

        assertThat(clusterState.metaData().index("test").activeAllocationIds(0).size(), equalTo(2));

        logger.info("remove all remaining nodes");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes())
                .remove("node2").remove("node3"))
                .build();
        rerouteResult = allocation.reroute(clusterState, "reroute");
        clusterState = ClusterState.builder(clusterState).routingResult(rerouteResult).build();

        // active allocation ids should not be updated
        assertThat(clusterState.getRoutingTable().shardsWithState(UNASSIGNED).size(), equalTo(3));
        assertThat(clusterState.metaData().index("test").activeAllocationIds(0).size(), equalTo(2));
    }
}
