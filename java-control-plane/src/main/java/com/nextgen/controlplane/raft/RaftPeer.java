package com.nextgen.controlplane.raft;

import java.util.ArrayList;
import java.util.List;

/**
 * One member of the Raft cluster: its id and the host:port other replicas dial to reach its
 * {@code RaftConsensus} service (a separate port from the client-facing {@code ControlPlaneService} —
 * see {@code RAFT_PORT}/{@code RAFT_ADVERTISED_ADDRESS} in {@code ControlPlaneServer}).
 */
public record RaftPeer(String id, String host, int port) {

    public String address() {
        return host + ":" + port;
    }

    /**
     * Parses the {@code RAFT_PEERS} format: {@code "id1=host1:port1,id2=host2:port2,..."}. The
     * returned list <b>includes self</b> — callers select self out by {@code RAFT_NODE_ID} rather than
     * relying on a "peers excludes self" convention, which is more error-prone in a hand-rolled
     * implementation (every majority computation would need a {@code +1} you can forget in one of
     * several places).
     */
    public static List<RaftPeer> parse(String raftPeers) {
        List<RaftPeer> peers = new ArrayList<>();
        if (raftPeers == null || raftPeers.isBlank()) {
            return peers;
        }
        for (String entry : raftPeers.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException(
                        "Malformed RAFT_PEERS entry (expected id=host:port): " + trimmed);
            }
            String id = trimmed.substring(0, eq);
            String hostPort = trimmed.substring(eq + 1);
            int colon = hostPort.lastIndexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException(
                        "Malformed RAFT_PEERS entry (expected id=host:port): " + trimmed);
            }
            String host = hostPort.substring(0, colon);
            int port;
            try {
                port = Integer.parseInt(hostPort.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Malformed RAFT_PEERS entry (non-numeric port): " + trimmed, e);
            }
            peers.add(new RaftPeer(id, host, port));
        }
        return peers;
    }
}
