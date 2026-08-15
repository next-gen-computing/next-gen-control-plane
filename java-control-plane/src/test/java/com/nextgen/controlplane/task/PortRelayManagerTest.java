package com.nextgen.controlplane.task;

import com.google.protobuf.ByteString;
import com.nextgen.proto.ControlPlaneProto.TunnelFrame;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real sockets throughout — no gRPC needed to prove the relay logic itself, only a fake
 * {@link StreamObserver} standing in for the node's real {@code TunnelPort} stream. See
 * {@code TunnelFrame}'s own proto Javadoc for the hello/multiplexing protocol this exercises.
 */
class PortRelayManagerTest {

    private static final int RANGE_START = 41000;
    private static final int RANGE_END = 41099;

    @Test
    void reservePortBindsARealSocketInTheConfiguredRange() throws Exception {
        PortRelayManager manager = new PortRelayManager(RANGE_START, RANGE_END);

        int port = manager.reservePort("proj", "svc");

        assertTrue(port >= RANGE_START && port <= RANGE_END);
        // The port is really bound — a second bind attempt on the SAME port must fail.
        try (var conflicting = new java.net.ServerSocket()) {
            assertTrue(assertThrowsBindConflict(conflicting, port));
        }
        manager.release("proj", "svc");
    }

    private static boolean assertThrowsBindConflict(java.net.ServerSocket socket, int port) {
        try {
            socket.bind(new java.net.InetSocketAddress("localhost", port));
            return false;
        } catch (IOException expected) {
            return true;
        }
    }

    @Test
    void attachStreamWithNoReservationIsHonestlyRejected() {
        PortRelayManager manager = new PortRelayManager(RANGE_START, RANGE_END);
        LinkedBlockingQueue<TunnelFrame> received = new LinkedBlockingQueue<>();

        boolean attached = manager.attachStream("proj", "never-reserved", fakeNodeStream(received));

        assertFalse(attached);
    }

    @Test
    void aRealTcpConnectionIsRelayedToTheNodeStreamAndAReplyReachesTheConsumer() throws Exception {
        PortRelayManager manager = new PortRelayManager(RANGE_START, RANGE_END);
        LinkedBlockingQueue<TunnelFrame> receivedByNode = new LinkedBlockingQueue<>();
        try {
            int port = manager.reservePort("proj", "svc");
            boolean attached = manager.attachStream("proj", "svc", fakeNodeStream(receivedByNode));
            assertTrue(attached);

            try (Socket consumer = new Socket("localhost", port)) {
                OutputStream out = consumer.getOutputStream();
                out.write("ping".getBytes(StandardCharsets.UTF_8));
                out.flush();

                TunnelFrame forwarded = receivedByNode.poll(5, TimeUnit.SECONDS);
                assertNotNull(forwarded, "the real TCP bytes must be forwarded to the node stream");
                assertEquals("ping", forwarded.getData().toStringUtf8());
                assertFalse(forwarded.getTunnelId().isEmpty());

                // Simulate the node replying on the same tunnel_id, as PortTunnelClient really would.
                manager.onFrameFromNode("proj", "svc", TunnelFrame.newBuilder()
                        .setTunnelId(forwarded.getTunnelId())
                        .setData(ByteString.copyFromUtf8("pong"))
                        .build());

                InputStream in = consumer.getInputStream();
                byte[] buffer = new byte[4];
                int totalRead = 0;
                while (totalRead < 4) {
                    int read = in.read(buffer, totalRead, 4 - totalRead);
                    assertTrue(read > 0, "the relayed reply must actually reach the consumer socket");
                    totalRead += read;
                }
                assertEquals("pong", new String(buffer, StandardCharsets.UTF_8));
            }
        } finally {
            manager.release("proj", "svc");
        }
    }

    @Test
    void closedFrameFromNodeClosesTheConsumerSocket() throws Exception {
        PortRelayManager manager = new PortRelayManager(RANGE_START, RANGE_END);
        LinkedBlockingQueue<TunnelFrame> receivedByNode = new LinkedBlockingQueue<>();
        try {
            int port = manager.reservePort("proj", "svc");
            manager.attachStream("proj", "svc", fakeNodeStream(receivedByNode));

            try (Socket consumer = new Socket("localhost", port)) {
                consumer.getOutputStream().write("hi".getBytes(StandardCharsets.UTF_8));
                consumer.getOutputStream().flush();
                TunnelFrame forwarded = receivedByNode.poll(5, TimeUnit.SECONDS);
                assertNotNull(forwarded);

                manager.onFrameFromNode("proj", "svc", TunnelFrame.newBuilder()
                        .setTunnelId(forwarded.getTunnelId())
                        .setClosed(true)
                        .build());

                // The consumer's read side must observe EOF once the node closes its half.
                long deadline = System.currentTimeMillis() + 5_000;
                int result = -2;
                while (System.currentTimeMillis() < deadline) {
                    result = consumer.getInputStream().read();
                    if (result == -1) {
                        break;
                    }
                }
                assertEquals(-1, result, "consumer socket should see EOF after a closed frame");
            }
        } finally {
            manager.release("proj", "svc");
        }
    }

    /** Stage OO: two backends attached to the SAME (project, service) key, several consumer connections
     * opened in sequence, must distribute across BOTH backends — not just the first one attached. */
    @Test
    void multipleAttachedBackendsBothReceiveTraffic() throws Exception {
        PortRelayManager manager = new PortRelayManager(RANGE_START, RANGE_END);
        LinkedBlockingQueue<TunnelFrame> receivedByFirst = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<TunnelFrame> receivedBySecond = new LinkedBlockingQueue<>();
        try {
            int port = manager.reservePort("proj", "replicated");
            assertTrue(manager.attachStream("proj", "replicated", fakeNodeStream(receivedByFirst)));
            assertTrue(manager.attachStream("proj", "replicated", fakeNodeStream(receivedBySecond)));

            // Open several real connections — round-robin means alternating backends, so more than one
            // connection is needed to observe both being used.
            for (int i = 0; i < 4; i++) {
                try (Socket consumer = new Socket("localhost", port)) {
                    consumer.getOutputStream().write(("req" + i).getBytes(StandardCharsets.UTF_8));
                    consumer.getOutputStream().flush();
                }
            }

            long deadline = System.currentTimeMillis() + 5_000;
            while ((receivedByFirst.isEmpty() || receivedBySecond.isEmpty())
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertFalse(receivedByFirst.isEmpty(), "the first backend must have received at least one request");
            assertFalse(receivedBySecond.isEmpty(), "the second backend must have received at least one request");
        } finally {
            manager.release("proj", "replicated");
        }
    }

    /** Stage OO: one of two backends disconnecting must not tear down the listener — new connections
     * keep being routed (to whichever backend remains), and the port stays bound. */
    @Test
    void detachingOneOfTwoBackendsLeavesTheListenerAndTheOtherBackendRunning() throws Exception {
        PortRelayManager manager = new PortRelayManager(RANGE_START, RANGE_END);
        LinkedBlockingQueue<TunnelFrame> receivedByFirst = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<TunnelFrame> receivedBySecond = new LinkedBlockingQueue<>();
        StreamObserver<TunnelFrame> firstBackend = fakeNodeStream(receivedByFirst);
        try {
            int port = manager.reservePort("proj", "replicated2");
            manager.attachStream("proj", "replicated2", firstBackend);
            manager.attachStream("proj", "replicated2", fakeNodeStream(receivedBySecond));

            manager.detachStream("proj", "replicated2", firstBackend);

            // The listener must still be up and routing — to the one remaining backend.
            try (Socket consumer = new Socket("localhost", port)) {
                consumer.getOutputStream().write("after-detach".getBytes(StandardCharsets.UTF_8));
                consumer.getOutputStream().flush();
                TunnelFrame forwarded = receivedBySecond.poll(5, TimeUnit.SECONDS);
                assertNotNull(forwarded, "the remaining backend must still receive new connections");
                assertEquals("after-detach", forwarded.getData().toStringUtf8());
            }
            assertTrue(receivedByFirst.isEmpty(), "the detached backend must receive nothing new");
        } finally {
            manager.release("proj", "replicated2");
        }
    }

    /** Stage OO: detaching the LAST backend must fully close the entry — the port becomes free again,
     * exactly like an explicit {@link PortRelayManager#release}. */
    @Test
    void detachingTheLastBackendFullyClosesTheListener() throws Exception {
        PortRelayManager manager = new PortRelayManager(RANGE_START, RANGE_END);
        LinkedBlockingQueue<TunnelFrame> received = new LinkedBlockingQueue<>();
        StreamObserver<TunnelFrame> onlyBackend = fakeNodeStream(received);

        int port = manager.reservePort("proj", "solo");
        manager.attachStream("proj", "solo", onlyBackend);

        manager.detachStream("proj", "solo", onlyBackend);

        // The listener socket must actually be released — a fresh bind on the same port must succeed.
        long deadline = System.currentTimeMillis() + 5_000;
        boolean rebound = false;
        while (!rebound && System.currentTimeMillis() < deadline) {
            try (var freshSocket = new java.net.ServerSocket()) {
                freshSocket.bind(new java.net.InetSocketAddress("localhost", port));
                rebound = true;
            } catch (IOException notYetFree) {
                Thread.sleep(50);
            }
        }
        assertTrue(rebound, "the relay port must be fully released once the last backend detaches");
    }

    private static StreamObserver<TunnelFrame> fakeNodeStream(LinkedBlockingQueue<TunnelFrame> sink) {
        return new StreamObserver<>() {
            @Override public void onNext(TunnelFrame value) { sink.add(value); }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        };
    }
}
