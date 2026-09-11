package com.nextgen.controlplane.alert;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real SMTP: a hand-rolled minimal-but-real SMTP server (plain {@code ServerSocket}, no TLS/AUTH —
 * deliberately out of scope for this test) receiving a real message from a real {@code
 * EmailAlertNotifier}/Angus Mail client — no mocked mail client, matching {@code
 * WebhookAlertNotifierTest}'s identical "real over mocked" discipline for the webhook channel.
 */
class EmailAlertNotifierTest {

    private ServerSocket serverSocket;
    private Thread serverThread;

    @AfterEach
    void tearDown() throws IOException {
        if (serverSocket != null) {
            serverSocket.close();
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
    }

    /** Speaks just enough SMTP to accept one message and hand its full DATA content (headers + body,
     * dot-unstuffed) to the returned queue. */
    private LinkedBlockingQueue<String> startCapturingServer() throws IOException {
        serverSocket = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
        LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
        serverThread = new Thread(() -> {
            try (Socket client = serverSocket.accept()) {
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                OutputStream out = client.getOutputStream();
                reply(out, "220 localhost ESMTP test server");
                String line;
                while ((line = in.readLine()) != null) {
                    String upper = line.toUpperCase(java.util.Locale.ROOT);
                    if (upper.startsWith("EHLO") || upper.startsWith("HELO")) {
                        reply(out, "250 localhost");
                    } else if (upper.startsWith("MAIL FROM")) {
                        reply(out, "250 OK");
                    } else if (upper.startsWith("RCPT TO")) {
                        reply(out, "250 OK");
                    } else if (upper.startsWith("DATA")) {
                        reply(out, "354 End data with <CR><LF>.<CR><LF>");
                        StringBuilder data = new StringBuilder();
                        String dataLine;
                        while ((dataLine = in.readLine()) != null && !dataLine.equals(".")) {
                            data.append(dataLine.startsWith("..") ? dataLine.substring(1) : dataLine)
                                    .append("\n");
                        }
                        received.add(data.toString());
                        reply(out, "250 OK: queued");
                    } else if (upper.startsWith("QUIT")) {
                        reply(out, "221 Bye");
                        return;
                    } else {
                        reply(out, "250 OK");
                    }
                }
            } catch (IOException ignored) {
                // Socket closed by tearDown() — expected once the test using it has finished.
            }
        }, "fake-smtp-server");
        serverThread.setDaemon(true);
        serverThread.start();
        return received;
    }

    private void reply(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private EmailAlertNotifier notifierFor(String toAddress) {
        return new EmailAlertNotifier("127.0.0.1", serverSocket.getLocalPort(), "", "", false,
                "alerts@nextgen.local", List.of(toAddress));
    }

    @Test
    void notifyNodeDownSendsARealEmailOverRealSmtp() throws Exception {
        LinkedBlockingQueue<String> received = startCapturingServer();
        EmailAlertNotifier notifier = notifierFor("operator@example.com");

        notifier.notifyNodeDown("node-7", "no heartbeat for over 6000ms");

        String message = received.poll(10, TimeUnit.SECONDS);
        assertNotNull(message, "the fake SMTP server never received a real DATA payload");
        assertTrue(message.contains("node-7"), "message did not contain the node id");
        assertTrue(message.contains("no heartbeat for over 6000ms"), "message did not contain the reason");
        assertTrue(message.toLowerCase(java.util.Locale.ROOT).contains("subject:"),
                "message had no Subject header");
    }

    @Test
    void notifyNodeAtRiskSendsARealEmailIncludingTheRiskScore() throws Exception {
        LinkedBlockingQueue<String> received = startCapturingServer();
        EmailAlertNotifier notifier = notifierFor("operator@example.com");

        notifier.notifyNodeAtRisk("node-3", 0.87, "rising RTT; low battery");

        String message = received.poll(10, TimeUnit.SECONDS);
        assertNotNull(message, "the fake SMTP server never received a real DATA payload");
        assertTrue(message.contains("node-3"), "message did not contain the node id");
        assertTrue(message.contains("0.87"), "message did not contain the risk score");
        assertTrue(message.contains("rising RTT; low battery"), "message did not contain the reason");
    }

    /** Same fire-and-forget guarantee as WebhookAlertNotifier: an unreachable SMTP host must never
     * throw back into the caller (a monitor's sweep thread). */
    @Test
    void anUnreachableSmtpHostNeverThrowsBackToTheCaller() {
        EmailAlertNotifier notifier = new EmailAlertNotifier("127.0.0.1", 1, "", "", false,
                "alerts@nextgen.local", List.of("operator@example.com"));

        assertDoesNotThrow(() -> notifier.notifyNodeDown("node-x", "unreachable smtp test"));
    }
}
