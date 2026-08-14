package com.nextgen.desktop.ui.server;

import com.nextgen.desktop.ui.client.GrpcConnectionManager;
import com.nextgen.desktop.ui.profile.DesktopHistoryStore;
import com.nextgen.desktop.ui.profile.DesktopProfileStore;
import com.nextgen.desktop.ui.service.DockerResourcesMonitoringService;
import com.nextgen.desktop.ui.service.JobExecutionService;
import com.nextgen.desktop.ui.service.NodeMonitoringService;
import com.nextgen.desktop.ui.service.TaskExecutionService;
import com.nextgen.desktop.ui.service.ThemeService;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/**
 * Serves the HTML/CSS/JS frontend to the desktop app's own {@code WebView}, over loopback only.
 *
 * <p>Deliberately diverges from {@code ControlPlaneServer}'s dashboard {@code HttpServer} in two
 * ways, both security/liveness relevant rather than stylistic:
 *
 * <ul>
 *   <li><b>Loopback-only, ephemeral port.</b> The dashboard binds all interfaces on a fixed port by
 *       design — it is meant to be LAN-reachable. This server is an internal UI channel with no
 *       authentication of its own; it must never be reachable from outside this machine. Binding
 *       {@link InetAddress#getLoopbackAddress()} makes that true regardless of firewall
 *       configuration, and an ephemeral port (0) avoids clashing with a fixed port a second instance
 *       or another local service might already hold.</li>
 *   <li><b>A real thread pool, not {@code setExecutor(null)}.</b> SSE routes park a handler thread for
 *       as long as the client stays connected. The JDK's default executor (a single thread on the
 *       exchange dispatcher) would let the first SSE client starve every other route. A cached pool
 *       grows with concurrent connections and this app has, at most, one WebView client — the cost of
 *       over-provisioning is negligible.</li>
 * </ul>
 */
public class LocalUiServer {
    private static final Logger LOG = LoggerFactory.getLogger(LocalUiServer.class);
    private static final String STATIC_ROOT = "/web";

    /** How often each SSE channel re-checks its data source for changes. */
    private static final long POLL_INTERVAL_MS = 750;

    private final ThemeService themeService;
    private final NodeMonitoringService monitoringService;
    private final TaskExecutionService taskExecutionService;
    private final JobExecutionService jobExecutionService;
    private final DockerResourcesMonitoringService dockerResourcesMonitoringService;
    private final GrpcConnectionManager connectionManager;
    private final DesktopProfileStore profileStore;
    private final DesktopHistoryStore historyStore;
    private final BiConsumer<String, String> onConnected;

    private volatile String role;
    private volatile String serverId;

    private HttpServer server;
    private ExecutorService executor;
    private NodesStreamHandler nodesStream;
    private ConnectionStreamHandler connectionStream;
    private ThemeRouteHandler themeRoute;
    private RoleRouteHandler roleRoute;
    private MetricsStreamHandler metricsStream;
    private ClusterStreamHandler clusterStream;
    private TasksStreamHandler tasksStream;
    private JobsStreamHandler jobsStream;
    private ContainersStreamHandler containersStream;
    private ImagesStreamHandler imagesStream;
    private VolumesStreamHandler volumesStream;
    private NetworksStreamHandler networksStream;

    /** @param onConnected called with (role, serverId) once onboarding succeeds — see {@link RoleRouteHandler}. */
    public LocalUiServer(ThemeService themeService, NodeMonitoringService monitoringService,
                         TaskExecutionService taskExecutionService, JobExecutionService jobExecutionService,
                         DockerResourcesMonitoringService dockerResourcesMonitoringService,
                         GrpcConnectionManager connectionManager, DesktopProfileStore profileStore,
                         DesktopHistoryStore historyStore, BiConsumer<String, String> onConnected) {
        this.themeService = themeService;
        this.monitoringService = monitoringService;
        this.taskExecutionService = taskExecutionService;
        this.jobExecutionService = jobExecutionService;
        this.dockerResourcesMonitoringService = dockerResourcesMonitoringService;
        this.connectionManager = connectionManager;
        this.profileStore = profileStore;
        this.historyStore = historyStore;
        this.onConnected = onConnected;
    }

    /** The role chosen on the onboarding screen ("server"/"node"), or null before it is chosen. */
    public void setRole(String role) {
        this.role = role;
    }

    /** The server's short display id, or null until one exists. */
    public void setServerId(String serverId) {
        this.serverId = serverId;
    }

    /** Starts the server on an OS-assigned loopback port. Safe to call once per instance. */
    public synchronized void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("LocalUiServer already started");
        }

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "local-ui-server");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);

        server.createContext("/", new ClasspathStaticHandler(STATIC_ROOT));
        server.createContext("/api/state", new StateRouteHandler(
                () -> role, () -> serverId, themeService, monitoringService,
                taskExecutionService, jobExecutionService, connectionManager));
        server.createContext("/api/feedback", new FeedbackRouteHandler());

        nodesStream = new NodesStreamHandler(monitoringService, POLL_INTERVAL_MS);
        server.createContext("/api/nodes/stream", nodesStream);

        clusterStream = new ClusterStreamHandler(monitoringService, POLL_INTERVAL_MS);
        server.createContext("/api/cluster/stream", clusterStream);

        connectionStream = new ConnectionStreamHandler(monitoringService.getConnectionState(), POLL_INTERVAL_MS);
        server.createContext("/api/connection/stream", connectionStream);

        server.createContext("/api/metrics/history", new MetricsHistoryRouteHandler(monitoringService));
        metricsStream = new MetricsStreamHandler(monitoringService, POLL_INTERVAL_MS);
        server.createContext("/api/metrics/stream", metricsStream);

        themeRoute = new ThemeRouteHandler(themeService, POLL_INTERVAL_MS);
        server.createContext("/api/theme", themeRoute);
        server.createContext("/api/theme/stream", themeRoute);

        roleRoute = new RoleRouteHandler(connectionManager, profileStore, (connectedRole, connectedServerId) -> {
            setRole(connectedRole);
            setServerId(connectedServerId);
            onConnected.accept(connectedRole, connectedServerId);
        });
        server.createContext("/api/role/", roleRoute);

        server.createContext("/api/history", new HistoryRouteHandler(historyStore));

        server.createContext("/api/tasks", new TasksRouteHandler(taskExecutionService, monitoringService));
        tasksStream = new TasksStreamHandler(taskExecutionService, POLL_INTERVAL_MS);
        server.createContext("/api/tasks/stream", tasksStream);

        server.createContext("/api/jobs", new JobsRouteHandler(jobExecutionService, monitoringService));
        jobsStream = new JobsStreamHandler(jobExecutionService, POLL_INTERVAL_MS);
        server.createContext("/api/jobs/stream", jobsStream);

        server.createContext("/api/containers/action", new ContainersRouteHandler(dockerResourcesMonitoringService));
        containersStream = new ContainersStreamHandler(dockerResourcesMonitoringService, POLL_INTERVAL_MS);
        server.createContext("/api/containers/stream", containersStream);

        imagesStream = new ImagesStreamHandler(dockerResourcesMonitoringService, POLL_INTERVAL_MS);
        server.createContext("/api/images/stream", imagesStream);

        volumesStream = new VolumesStreamHandler(dockerResourcesMonitoringService, POLL_INTERVAL_MS);
        server.createContext("/api/volumes/stream", volumesStream);

        networksStream = new NetworksStreamHandler(dockerResourcesMonitoringService, POLL_INTERVAL_MS);
        server.createContext("/api/networks/stream", networksStream);

        server.createContext("/api/certificates", new CertificateRouteHandler());

        ConnectionSettingsRouteHandler connectionSettings =
                new ConnectionSettingsRouteHandler(connectionManager, monitoringService);
        server.createContext("/api/connection/apply", connectionSettings);
        server.createContext("/api/connection/refresh-interval", connectionSettings);

        server.start();
        LOG.info("Local UI server listening on {}", getBaseUrl());
    }

    public synchronized void stop() {
        if (nodesStream != null) {
            nodesStream.stop();
        }
        if (connectionStream != null) {
            connectionStream.stop();
        }
        if (themeRoute != null) {
            themeRoute.stop();
        }
        if (roleRoute != null) {
            roleRoute.stop();
        }
        if (metricsStream != null) {
            metricsStream.stop();
        }
        if (clusterStream != null) {
            clusterStream.stop();
        }
        if (tasksStream != null) {
            tasksStream.stop();
        }
        if (jobsStream != null) {
            jobsStream.stop();
        }
        if (containersStream != null) {
            containersStream.stop();
        }
        if (imagesStream != null) {
            imagesStream.stop();
        }
        if (volumesStream != null) {
            volumesStream.stop();
        }
        if (networksStream != null) {
            networksStream.stop();
        }
        if (server != null) {
            server.stop(1);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public synchronized int getPort() {
        if (server == null) {
            throw new IllegalStateException("LocalUiServer is not running");
        }
        return server.getAddress().getPort();
    }

    public String getBaseUrl() {
        return "http://127.0.0.1:" + getPort() + "/";
    }
}
