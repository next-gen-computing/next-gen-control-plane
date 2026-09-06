package com.nextgen.controlplane;

import com.nextgen.controlplane.raft.RaftLog;
import com.nextgen.controlplane.raft.RaftNode;
import com.nextgen.controlplane.raft.RaftPeer;
import com.nextgen.controlplane.raft.RaftTimings;
import com.nextgen.controlplane.raft.RaftTransport;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DashboardApiHandler — verifies the JSON API response.
 */
class DashboardApiHandlerTest {

    private static ConcurrentHashMap<String, NodeRecord> mapWith(NodeRecord... records) {
        ConcurrentHashMap<String, NodeRecord> map = new ConcurrentHashMap<>();
        for (NodeRecord record : records) {
            map.put(record.getNodeId(), record);
        }
        return map;
    }

    private static String responseFor(ConcurrentHashMap<String, NodeRecord> registry) throws IOException {
        DashboardApiHandler handler = new DashboardApiHandler(registry);

        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestMethod()).thenReturn("GET");

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(outputStream);

        handler.handle(exchange);
        return outputStream.toString(StandardCharsets.UTF_8);
    }

    @Test
    void testEmptyRegistryReturnsEmptyNodes() throws IOException {
        String response = responseFor(new ConcurrentHashMap<>());

        assertTrue(response.contains("\"nodes\":[]"));
        assertTrue(response.contains("\"totalNodes\":0"));
        assertTrue(response.contains("\"aliveNodes\":0"));
        assertTrue(response.contains("\"deadNodes\":0"));
    }

    @Test
    void testEmptyRegistryReportsNullAveragesNotZero() throws IOException {
        String response = responseFor(new ConcurrentHashMap<>());

        // With nothing to measure, the average is unknown — not 0.00. A consumer must be able to tell
        // "no data" apart from "measured zero".
        assertTrue(response.contains("\"avgCpu\":null"), response);
        assertTrue(response.contains("\"avgMemory\":null"), response);
    }

    @Test
    void testSingleNodeInRegistry() throws IOException {
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1")
                .withHeartbeat(45.5f, true, 78.3f, true, System.currentTimeMillis(), 1);

        String response = responseFor(mapWith(record));

        assertTrue(response.contains("\"nodeId\":\"node1\""));
        assertTrue(response.contains("\"ip\":\"192.168.1.1\""));
        assertTrue(response.contains("\"hostname\":\"host1\""));
        assertTrue(response.contains("\"cpuUsage\":45.50"));
        assertTrue(response.contains("\"memoryUsage\":78.30"));
        assertTrue(response.contains("\"totalNodes\":1"));
        assertTrue(response.contains("\"aliveNodes\":1"));
    }

    @Test
    void testStaleReadingIsReportedAsNullNotAsANumber() throws IOException {
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1")
                .withHeartbeat(0f, false, 50.0f, true, System.currentTimeMillis(), 1);

        String response = responseFor(mapWith(record));

        assertTrue(response.contains("\"cpuUsage\":null"), response);
        assertTrue(response.contains("\"cpuStale\":true"), response);
        assertTrue(response.contains("\"memoryUsage\":50.00"), response);
        assertTrue(response.contains("\"memoryStale\":false"), response);
    }

    @Test
    void testStaleReadingIsExcludedFromTheAverage() throws IOException {
        NodeRecord measured = new NodeRecord("node1", "1.1.1.1", 1, "h1")
                .withHeartbeat(80.0f, true, 0f, true, System.currentTimeMillis(), 1);
        NodeRecord unmeasured = new NodeRecord("node2", "1.1.1.2", 1, "h2")
                .withHeartbeat(0f, false, 0f, true, System.currentTimeMillis(), 1);

        String response = responseFor(mapWith(measured, unmeasured));

        // Averaging a node we could not measure would drag the cluster figure toward a value that
        // was never observed. Only real samples count.
        assertTrue(response.contains("\"avgCpu\":80.00"), response);
    }

    @Test
    void testNumbersUseRootLocale() throws IOException {
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host1")
                .withHeartbeat(45.5f, true, 78.3f, true, System.currentTimeMillis(), 1);

        String response = responseFor(mapWith(record));

        // Under a comma-decimal default locale the previous implementation emitted
        // "cpuUsage":45,50 — structurally invalid JSON. The value must always use a dot.
        assertFalse(response.contains("\"cpuUsage\":45,50"), response);
        assertTrue(response.contains("\"cpuUsage\":45.50"), response);
    }

    @Test
    void testCorsHeadersSet() throws IOException {
        DashboardApiHandler handler = new DashboardApiHandler(new ConcurrentHashMap<String, NodeRecord>());

        HttpExchange exchange = mock(HttpExchange.class);
        Headers headers = new Headers();
        when(exchange.getResponseHeaders()).thenReturn(headers);
        when(exchange.getRequestMethod()).thenReturn("GET");
        when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());

        handler.handle(exchange);

        assertEquals("*", headers.getFirst("Access-Control-Allow-Origin"));
        assertEquals("GET, OPTIONS", headers.getFirst("Access-Control-Allow-Methods"));
        assertTrue(headers.containsKey("Content-Type"));
    }

    @Test
    void testOptionsRequestReturns204() throws IOException {
        DashboardApiHandler handler = new DashboardApiHandler(new ConcurrentHashMap<String, NodeRecord>());

        HttpExchange exchange = mock(HttpExchange.class);
        Headers headers = new Headers();
        when(exchange.getResponseHeaders()).thenReturn(headers);
        when(exchange.getRequestMethod()).thenReturn("OPTIONS");

        handler.handle(exchange);

        verify(exchange).sendResponseHeaders(204, -1);
    }

    @Test
    void testDeadNodeCountedCorrectly() throws IOException {
        NodeRecord alive = new NodeRecord("node1", "192.168.1.1", 50051, "host1");
        NodeRecord dead = new NodeRecord("node2", "192.168.1.2", 50051, "host2")
                .withStatus(NodeStatus.SUSPECTED_DEAD);

        String response = responseFor(mapWith(alive, dead));

        assertTrue(response.contains("\"totalNodes\":2"));
        assertTrue(response.contains("\"aliveNodes\":1"));
        assertTrue(response.contains("\"deadNodes\":1"));
        assertTrue(response.contains("\"status\":\"SUSPECTED_DEAD\""));
    }

    @Test
    void testJsonEscaping() throws IOException {
        NodeRecord record = new NodeRecord("node1", "192.168.1.1", 50051, "host\"with\\quotes");

        String response = responseFor(mapWith(record));

        assertTrue(response.contains("host\\\"with\\\\quotes"));
    }

    /**
     * Under Raft, a follower's registry receives zero heartbeats (heartbeats are deliberately
     * leader-local, never replicated) — serving {@code /api/nodes} there would render every node
     * falsely stale. A freshly-constructed, never-started {@link RaftNode} defaults to
     * {@code FOLLOWER} with no election needed, which is enough to exercise the gating without
     * standing up a real cluster (that's {@code ReplicatedControlPlaneIntegrationTest}'s job).
     */
    @Test
    void testNonLeaderRaftNodeReturns503WithLeaderHintInsteadOfNodeJson(@TempDir Path tempDir) throws IOException {
        RaftNode raftNode = new RaftNode("self", "localhost:1",
                List.of(new RaftPeer("self", "localhost", 1)),
                new RaftLog(tempDir), (index, command) -> { }, noopTransport(), RaftTimings.defaults());

        DashboardApiHandler handler = new DashboardApiHandler(
                new NodeRegistry(new ConcurrentHashMap<>(), System::currentTimeMillis), raftNode);

        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getRequestMethod()).thenReturn("GET");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(outputStream);

        handler.handle(exchange);

        verify(exchange).sendResponseHeaders(eq(503), anyLong());
        String response = outputStream.toString(StandardCharsets.UTF_8);
        assertTrue(response.contains("\"error\":\"not_leader\""), response);
    }

    private static RaftTransport noopTransport() {
        return new RaftTransport() {
            @Override
            public RequestVoteResult requestVote(RaftPeer peer, RequestVoteArgs args) {
                throw new UnsupportedOperationException("never called — the RaftNode under test is never started");
            }

            @Override
            public AppendEntriesResult appendEntries(RaftPeer peer, AppendEntriesArgs args) {
                throw new UnsupportedOperationException("never called — the RaftNode under test is never started");
            }
        };
    }
}
