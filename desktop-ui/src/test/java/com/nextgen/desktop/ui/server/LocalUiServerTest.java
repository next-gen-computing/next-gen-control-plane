package com.nextgen.desktop.ui.server;

import com.nextgen.desktop.ui.client.GrpcConnectionManager;
import com.nextgen.desktop.ui.profile.DesktopHistoryStore;
import com.nextgen.desktop.ui.profile.DesktopProfileStore;
import com.nextgen.desktop.ui.service.DockerResourcesMonitoringService;
import com.nextgen.desktop.ui.service.JobExecutionService;
import com.nextgen.desktop.ui.service.NodeMonitoringService;
import com.nextgen.desktop.ui.service.TaskExecutionService;
import com.nextgen.desktop.ui.service.ThemeService;
import javafx.application.Platform;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link LocalUiServer} over real HTTP rather than by inspecting a running desktop process —
 * the same reasoning {@link com.nextgen.controlplane.StaticFileHandler}-style handlers get tested this
 * way elsewhere: it is a plain {@code HttpHandler}, so a real client hitting a real loopback socket is
 * both simpler and more trustworthy than anything that pokes at JavaFX or OS process state.
 *
 * <p>The FX toolkit is started (mirroring {@code ThemeServiceTest}/{@code TaskExecutionServiceTest})
 * because {@link ThemeRouteHandler}'s POST path hands off to {@code Platform.runLater}.
 */
class LocalUiServerTest {

    @TempDir
    static Path tempDataDir;

    @BeforeAll
    static void initToolkit() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException e) {
            // Toolkit already initialized
        }
        // Keeps this test's saved-profile/history writes out of the real ~/.nextgen/desktop directory.
        System.setProperty("NEXTGEN_DESKTOP_DATA_DIR", tempDataDir.toString());
    }

    @AfterAll
    static void clearDataDirOverride() {
        System.clearProperty("NEXTGEN_DESKTOP_DATA_DIR");
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private LocalUiServer server;
    private NodeMonitoringService monitoringService;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private LocalUiServer startedServer() throws Exception {
        GrpcConnectionManager connectionManager = new GrpcConnectionManager();
        monitoringService = new NodeMonitoringService(connectionManager);
        DesktopHistoryStore historyStore = new DesktopHistoryStore();
        server = new LocalUiServer(new ThemeService(),
                monitoringService,
                new TaskExecutionService(connectionManager, historyStore),
                new JobExecutionService(connectionManager, historyStore),
                new DockerResourcesMonitoringService(connectionManager),
                connectionManager,
                new DesktopProfileStore(),
                historyStore,
                (role, serverId) -> { });
        server.start();
        return server;
    }

    /**
     * The single most security-relevant assertion in this class: this server carries no
     * authentication of its own, so it must be unreachable from anywhere but this machine regardless
     * of firewall configuration.
     */
    @Test
    void bindsToLoopbackOnly() throws Exception {
        LocalUiServer s = startedServer();

        assertTrue(s.getBaseUrl().startsWith("http://127.0.0.1:"));
    }

    @Test
    void servesIndexHtmlAtRoot() throws Exception {
        LocalUiServer s = startedServer();

        HttpResponse<String> response = get(s, "/");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("<html"), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/html"));
    }

    @Test
    void servesCssFromTheClasspath() throws Exception {
        LocalUiServer s = startedServer();

        HttpResponse<String> response = get(s, "/css/tokens.css");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("--ng-bg"), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("css"));
    }

    @Test
    void servesTheVendoredPlotlyBundle() throws Exception {
        LocalUiServer s = startedServer();

        HttpResponse<String> response = get(s, "/vendor/plotly-3.7.0.min.js");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Plotly"), "expected the Plotly bundle to mention its own name");
    }

    @Test
    void unknownPathIs404NotAServerError() throws Exception {
        LocalUiServer s = startedServer();

        HttpResponse<String> response = get(s, "/does-not-exist.html");

        assertEquals(404, response.statusCode());
    }

    @Test
    void directoryTraversalOutsideTheWebRootIsRejected() throws Exception {
        LocalUiServer s = startedServer();

        // Reaches for a real file (pom.xml) that exists on disk just outside the served classpath
        // root, to prove the guard is doing real path containment, not merely returning 404 because
        // nothing happens to be there.
        HttpResponse<String> response = get(s, "/../../../../../../pom.xml");

        assertEquals(404, response.statusCode());
    }

    @Test
    void secondStartOnTheSameInstanceFails() throws Exception {
        LocalUiServer s = startedServer();

        assertThrows(IllegalStateException.class, s::start);
    }

    @Test
    void stopIsIdempotentAndSafeBeforeStart() {
        GrpcConnectionManager connectionManager = new GrpcConnectionManager();
        DesktopHistoryStore historyStore = new DesktopHistoryStore();
        LocalUiServer s = new LocalUiServer(new ThemeService(),
                new NodeMonitoringService(connectionManager),
                new TaskExecutionService(connectionManager, historyStore),
                new JobExecutionService(connectionManager, historyStore),
                new DockerResourcesMonitoringService(connectionManager),
                connectionManager,
                new DesktopProfileStore(),
                historyStore,
                (role, serverId) -> { });

        assertDoesNotThrow(s::stop);
        assertDoesNotThrow(s::stop);
    }

    // ── /api/state ──────────────────────────────────────────────────────────

    @Test
    void stateReflectsAnEmptyClusterHonestlyNotAsFakeZeros() throws Exception {
        LocalUiServer s = startedServer();

        HttpResponse<String> response = get(s, "/api/state");

        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("\"nodes\":[]"), body);
        // No node has ever reported anything measurable, so the average must be absent — never 0.
        assertTrue(body.contains("\"avgCpuUsage\":null"), body);
        assertTrue(body.contains("\"avgMemoryUsage\":null"), body);
        assertTrue(body.contains("\"tasks\":[]"), body);
        assertTrue(body.contains("\"jobs\":[]"), body);
    }

    @Test
    void stateReflectsRoleAndServerIdOnceSet() throws Exception {
        LocalUiServer s = startedServer();
        s.setRole("server");
        s.setServerId("NGX-TEST-0001");

        HttpResponse<String> response = get(s, "/api/state");

        assertTrue(response.body().contains("\"role\":\"server\""), response.body());
        assertTrue(response.body().contains("\"serverId\":\"NGX-TEST-0001\""), response.body());
    }

    // ── SSE streams ─────────────────────────────────────────────────────────

    @Test
    void nodesStreamDeliversAFrameToANewClient() throws Exception {
        LocalUiServer s = startedServer();

        // The channel always sends its current snapshot immediately on registration (see
        // SseChannel.register), so a client connecting after the first poll tick still gets a frame
        // without needing to wait for the data to change again.
        Thread.sleep(900); // let the first poll tick populate a snapshot

        String frame = getStreamFirstFrame(s, "/api/nodes/stream");

        assertTrue(frame.startsWith("data: "), frame);
        assertTrue(frame.contains("[]"), frame);
    }

    @Test
    void connectionStreamDeliversAFrame() throws Exception {
        LocalUiServer s = startedServer();
        Thread.sleep(900);

        String frame = getStreamFirstFrame(s, "/api/connection/stream");

        assertTrue(frame.contains("\"state\""), frame);
    }

    @Test
    void jobsStreamDeliversAFrame() throws Exception {
        LocalUiServer s = startedServer();
        Thread.sleep(900);

        String frame = getStreamFirstFrame(s, "/api/jobs/stream");

        assertTrue(frame.startsWith("data: "), frame);
        assertTrue(frame.contains("[]"), frame);
    }

    // ── /api/jobs ───────────────────────────────────────────────────────────

    @Test
    void submittingAJobReturnsItImmediatelyAsRunning() throws Exception {
        LocalUiServer s = startedServer();

        HttpRequest post = HttpRequest.newBuilder(URI.create(s.getBaseUrl() + "api/jobs"))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"rangeStart\":0,\"rangeEnd\":1000,\"subTaskCount\":4}"))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = http.send(post, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"rangeStart\":0"), response.body());
        assertTrue(response.body().contains("\"rangeEnd\":1000"), response.body());
        assertTrue(response.body().contains("\"status\":\"RUNNING\""), response.body());

        // The submission is real and asynchronous (a real gRPC call is in flight in the background),
        // so it must show up in the job list right away rather than only after that call resolves.
        HttpResponse<String> state = get(s, "/api/state");
        assertFalse(state.body().contains("\"jobs\":[]"), "the newly submitted job must appear in state");
    }

    @Test
    void submittingAJobWithAnInvertedRangeIsRejected() throws Exception {
        LocalUiServer s = startedServer();

        HttpRequest post = HttpRequest.newBuilder(URI.create(s.getBaseUrl() + "api/jobs"))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"rangeStart\":100,\"rangeEnd\":0,\"subTaskCount\":4}"))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = http.send(post, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
    }

    @Test
    void submittingAJobWithZeroSubTasksIsRejected() throws Exception {
        LocalUiServer s = startedServer();

        HttpRequest post = HttpRequest.newBuilder(URI.create(s.getBaseUrl() + "api/jobs"))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"rangeStart\":0,\"rangeEnd\":100,\"subTaskCount\":0}"))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = http.send(post, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
    }

    // ── /api/metrics ────────────────────────────────────────────────────────

    @Test
    void metricsHistoryIsEmptyForAFreshClusterNotFabricated() throws Exception {
        LocalUiServer s = startedServer();

        HttpResponse<String> response = get(s, "/api/metrics/history");

        assertEquals(200, response.statusCode());
        assertEquals("[]", response.body());
    }

    @Test
    void metricsStreamDeliversAFrame() throws Exception {
        LocalUiServer s = startedServer();
        Thread.sleep(900);

        String frame = getStreamFirstFrame(s, "/api/metrics/stream");

        assertTrue(frame.startsWith("data: "), frame);
    }

    @Test
    void metricsHistoryReflectsRealRecordedGapsAsNullNotZero() throws Exception {
        LocalUiServer s = startedServer();
        // Drives the real MetricsHistory instance LocalUiServer was constructed with, the same one
        // /api/metrics/history reads from — proving the wire format, not a parallel implementation.
        var history = monitoringService.getMetricsHistory();
        history.record("node-1", com.nextgen.desktop.ui.service.MetricsHistory.Metric.CPU, 1000L, 42.0, true);
        history.record("node-1", com.nextgen.desktop.ui.service.MetricsHistory.Metric.CPU, 2000L, 0.0, false);

        HttpResponse<String> response = get(s, "/api/metrics/history");

        assertTrue(response.body().contains("\"v\":42.0"), response.body());
        assertTrue(response.body().contains("\"v\":null"), response.body());
        assertFalse(response.body().contains("\"v\":0.0"), response.body());
    }

    // ── /api/theme ──────────────────────────────────────────────────────────

    @Test
    void themeGetReflectsDefaultDarkMode() throws Exception {
        LocalUiServer s = startedServer();

        HttpResponse<String> response = get(s, "/api/theme");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"dark\":true"), response.body());
    }

    @Test
    void themePostChangesTheTheme() throws Exception {
        LocalUiServer s = startedServer();

        HttpRequest post = HttpRequest.newBuilder(URI.create(s.getBaseUrl() + "api/theme"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"dark\":false}"))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = http.send(post, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());

        // The mutation is handed off to the FX thread (see ThemeRouteHandler), so confirm it actually
        // applied rather than trusting the echoed request body.
        waitUntil(() -> get(s, "/api/theme").body().contains("\"dark\":false"));
    }

    // ── /api/role/* ─────────────────────────────────────────────────────────

    @Test
    void serverInfoReflectsRealEnvironmentValues() throws Exception {
        LocalUiServer s = startedServer();

        HttpResponse<String> response = get(s, "/api/role/server-info");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"lanIp\""), response.body());
        assertTrue(response.body().contains("\"serverId\""), response.body());
    }

    @Test
    void probeOfAnUnreachableAddressReportsNotFound() throws Exception {
        LocalUiServer s = startedServer();

        // Port 1 is a reserved, always-refused port on loopback — a fast, reliable "nothing there".
        HttpResponse<String> response = get(s, "/api/role/probe?address=127.0.0.1:1");

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"reachable\":false"), response.body());
        assertTrue(response.body().contains("\"category\":\"NOT_FOUND\""), response.body());
    }

    @Test
    void nodeJoinAgainstAnUnreachableAddressFailsWithAnErrorEnvelopeNotACrash() throws Exception {
        LocalUiServer s = startedServer();

        HttpRequest post = HttpRequest.newBuilder(URI.create(s.getBaseUrl() + "api/role/node/join"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"address\":\"127.0.0.1:1\",\"enrollmentToken\":\"\"}"))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = http.send(post, HttpResponse.BodyHandlers.ofString());

        // gRPC channel construction doesn't fail eagerly on a bad address — it fails on first use,
        // which here is the registerNode call, surfaced as a 502 with a category, not a 500/crash.
        assertEquals(502, response.statusCode(), response.body());
        assertFalse(response.body().contains("<html"), "must be JSON, never an unhandled-exception page");
    }

    // ── /api/feedback ───────────────────────────────────────────────────────

    @Test
    void feedbackIsSavedToARealLocalFileNotAFakeAcknowledgement(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir)
            throws Exception {
        System.setProperty("NEXTGEN_FEEDBACK_DIR", tempDir.toString());
        try {
            LocalUiServer s = startedServer();

            HttpRequest post = HttpRequest.newBuilder(URI.create(s.getBaseUrl() + "api/feedback"))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"category\":\"bug\",\"message\":\"charts don't load\",\"contact\":\"\"}"))
                    .header("Content-Type", "application/json")
                    .build();
            HttpResponse<String> response = http.send(post, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"ok\":true"), response.body());

            // The response names a real path — assert the file is actually there with the real
            // content, not just that the server claimed success.
            try (var files = Files.list(tempDir)) {
                var saved = files.findFirst().orElseThrow(() -> new AssertionError("no feedback file written"));
                String content = Files.readString(saved);
                assertTrue(content.contains("charts don't load"), content);
                assertTrue(content.contains("Category: bug"), content);
            }
        } finally {
            System.clearProperty("NEXTGEN_FEEDBACK_DIR");
        }
    }

    @Test
    void feedbackWithNoMessageIsRejected() throws Exception {
        LocalUiServer s = startedServer();

        HttpRequest post = HttpRequest.newBuilder(URI.create(s.getBaseUrl() + "api/feedback"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"category\":\"bug\",\"message\":\"\"}"))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = http.send(post, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode());
    }

    private void waitUntil(ThrowingBooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("condition did not become true within the timeout");
    }

    @FunctionalInterface
    private interface ThrowingBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }

    private HttpResponse<String> get(LocalUiServer s, String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(s.getBaseUrl() + path.replaceFirst("^/", "")))
                    .GET().build();
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Reads just enough of an SSE stream to capture its first "data: ..." frame, then disconnects. */
    private String getStreamFirstFrame(LocalUiServer s, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(s.getBaseUrl() + path.replaceFirst("^/", "")))
                .timeout(Duration.ofSeconds(5))
                .GET().build();
        HttpResponse<java.io.InputStream> response =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());

        try (var in = response.body()) {
            byte[] buffer = new byte[4096];
            int read = in.read(buffer);
            return read > 0 ? new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8) : "";
        }
    }
}
