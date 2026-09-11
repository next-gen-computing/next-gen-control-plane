package com.nextgen.desktop.ui;

import com.nextgen.desktop.ui.account.AccountService;
import com.nextgen.desktop.ui.account.AccountStore;
import com.nextgen.desktop.ui.client.GrpcConnectionManager;
import com.nextgen.desktop.ui.profile.DesktopHistoryStore;
import com.nextgen.desktop.ui.profile.DesktopProfileStore;
import com.nextgen.desktop.ui.server.LocalUiServer;
import com.nextgen.desktop.ui.service.ClusterTasksMonitoringService;
import com.nextgen.desktop.ui.service.DockerResourcesMonitoringService;
import com.nextgen.desktop.ui.service.JobExecutionService;
import com.nextgen.desktop.ui.service.NodeMonitoringService;
import com.nextgen.desktop.ui.service.TaskExecutionService;
import com.nextgen.desktop.ui.service.ThemeService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import netscape.javascript.JSObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Next-Gen Control Plane desktop application.
 *
 * <p>The entire UI — onboarding and, once connected, the dashboard — is an HTML/CSS/JS frontend
 * served by {@link LocalUiServer} and rendered in one {@link WebView} for the app's whole lifetime.
 * This class's job is narrow: start the local server, show it in a WebView, and start real-data
 * polling ({@link NodeMonitoringService#startMonitoring}) once {@code RoleRouteHandler} reports a
 * successful server launch or node join. Screen transitions after that point are the frontend's own
 * router (see {@code web/js/shell.js}) swapping content inside the same page — nothing on the Java
 * side needs to change what the WebView is showing.
 */
public class DesktopApp extends Application {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopApp.class);

    private GrpcConnectionManager connectionManager;
    private ThemeService themeService;
    private NodeMonitoringService monitoringService;
    private TaskExecutionService taskExecutionService;
    private JobExecutionService jobExecutionService;
    private DockerResourcesMonitoringService dockerResourcesMonitoringService;
    private ClusterTasksMonitoringService clusterTasksMonitoringService;
    private LocalUiServer localUiServer;

    @Override
    public void init() throws Exception {
        super.init();
        connectionManager = new GrpcConnectionManager();
        themeService = new ThemeService();

        // Local-disk state that survives an app restart: the remembered onboarding choice (so a
        // second launch can skip straight back to the dashboard) and a rolling history of past
        // task/job submissions with which cluster each ran against.
        DesktopProfileStore profileStore = new DesktopProfileStore();
        DesktopHistoryStore historyStore = new DesktopHistoryStore();
        AccountService accountService = new AccountService(new AccountStore());

        // Constructed here so LocalUiServer's HTTP routes poll the same live instances the dashboard
        // displays, not a second, independently-polling copy. Construction is harmless before any
        // connection exists — startMonitoring() is only called once onboarding actually succeeds.
        monitoringService = new NodeMonitoringService(connectionManager);
        taskExecutionService = new TaskExecutionService(connectionManager, historyStore);
        jobExecutionService = new JobExecutionService(connectionManager, historyStore);
        dockerResourcesMonitoringService = new DockerResourcesMonitoringService(connectionManager);
        clusterTasksMonitoringService = new ClusterTasksMonitoringService(connectionManager);

        localUiServer = new LocalUiServer(themeService, monitoringService, taskExecutionService,
                jobExecutionService, dockerResourcesMonitoringService, clusterTasksMonitoringService,
                connectionManager, profileStore, historyStore, accountService, this::onOnboardingComplete);
        localUiServer.start();

        LOG.info("Desktop UI initialized");
    }

    @Override
    public void start(Stage primaryStage) {
        try {
            primaryStage.setTitle("Next-Gen Control Plane");
            // Not setResizable(false) anywhere: the window is resizable by JavaFX's own default, and
            // every screen is plain responsive CSS built to reflow smaller than a full-size display.
            primaryStage.setMinWidth(960);
            primaryStage.setMinHeight(640);
            primaryStage.setMaximized(true);
            primaryStage.getIcons().addAll(loadAppIcons());

            WebView webView = new WebView();
            wireConsoleLogging(webView);
            webView.getEngine().load(localUiServer.getBaseUrl());

            Scene scene = new Scene(webView, 1400, 900);
            primaryStage.setScene(scene);
            primaryStage.show();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("Shutting down desktop UI...");
                if (connectionManager != null) {
                    connectionManager.shutdown();
                }
                if (monitoringService != null) {
                    monitoringService.shutdown();
                }
                if (taskExecutionService != null) {
                    taskExecutionService.shutdown();
                }
                if (jobExecutionService != null) {
                    jobExecutionService.shutdown();
                }
                if (dockerResourcesMonitoringService != null) {
                    dockerResourcesMonitoringService.shutdown();
                }
                if (clusterTasksMonitoringService != null) {
                    clusterTasksMonitoringService.shutdown();
                }
                if (localUiServer != null) {
                    localUiServer.stop();
                }
            }));
        } catch (Exception e) {
            LOG.error("Failed to start desktop UI", e);
            throw e;
        }
    }

    /**
     * Forwards the WebView's JS {@code console.log/warn/error} output and uncaught exceptions into
     * this app's own log. Without this, a broken script in the frontend fails silently — the WebView
     * has no visible devtools console, so nothing would ever surface that a screen didn't load.
     */
    private void wireConsoleLogging(WebView webView) {
        webView.getEngine().setOnError(event -> LOG.error("WebView error: {}", event.getMessage()));

        webView.getEngine().getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.FAILED) {
                LOG.error("WebView failed to load", webView.getEngine().getLoadWorker().getException());
                return;
            }
            if (newState != Worker.State.SUCCEEDED) {
                return;
            }
            LOG.info("WebView page loaded successfully");
            try {
                // Self-check runs as a plain expression returning a String, logged directly from Java
                // — avoids the JSObject/setMember bridge (which needs its own diagnosis) for this
                // specific check, so a problem there doesn't also hide this result.
                Object result = webView.getEngine().executeScript(
                        "(function() {"
                                + "  var required = ['NG.router','NG.sse','NG.palette','NG.format','NG.cursorFx',"
                                + "    'NG.icons','NG.helpCenter','NG.connectionBanner','NG.components.statTile',"
                                + "    'NG.components.nodeCard','NG.charts.timeSeries','NG.views.login',"
                                + "    'NG.views.roleSelection',"
                                + "    'NG.views.serverSetup','NG.views.nodeJoin','NG.views.reconnecting',"
                                + "    'NG.views.dashboard','NG.views.monitoring','NG.views.nodes','NG.views.tasks',"
                                + "    'NG.views.containers','NG.views.images','NG.views.volumes','NG.views.networks',"
                                + "    'NG.views.history','NG.views.settings','NG.shell','NG.app','Plotly'];"
                                + "  var missing = required.filter(function(path) {"
                                + "    return path.split('.').reduce(function(o, k) { return o && o[k]; }, window) === undefined;"
                                + "  });"
                                + "  return missing.length === 0 ? 'OK' : 'MISSING: ' + missing.join(', ');"
                                + "})()"
                );
                LOG.info("WebView module self-check: {}", result);

                JSObject window = (JSObject) webView.getEngine().executeScript("window");
                window.setMember("javaLog", new ConsoleBridge());
                window.setMember("javaOpenExternal", new ExternalLinkBridge(getHostServices()));
                webView.getEngine().executeScript(
                        "['log','warn','error'].forEach(function(level) {"
                                + "  var original = console[level];"
                                + "  console[level] = function() {"
                                + "    javaLog.log(level, Array.prototype.slice.call(arguments).join(' '));"
                                + "    original.apply(console, arguments);"
                                + "  };"
                                + "});"
                                + "window.onerror = function(msg, url, line) {"
                                + "  javaLog.log('error', msg + ' at ' + url + ':' + line);"
                                + "};"
                );
            } catch (Exception e) {
                LOG.error("Failed to install WebView console bridge / self-check", e);
            }
        });
    }

    /** Public so JavaFX's JS-interop reflection can call {@link #log}; only ever invoked from JS. */
    public static final class ConsoleBridge {
        public void log(String level, String message) {
            switch (level) {
                case "error" -> LOG.error("[webview] {}", message);
                case "warn" -> LOG.warn("[webview] {}", message);
                default -> LOG.info("[webview] {}", message);
            }
        }
    }

    /**
     * Opens a URL in the OS's real default browser, not the embedded {@code WebView} — required for
     * the GitHub device-flow login link specifically: the user needs their own already-logged-in
     * browser session (cookies, saved passwords, 2FA prompts they recognize), none of which the
     * WebView's separate, unauthenticated browsing context has. Public for the same JS-interop
     * reflection reason as {@link ConsoleBridge}.
     */
    public static final class ExternalLinkBridge {
        private final javafx.application.HostServices hostServices;

        ExternalLinkBridge(javafx.application.HostServices hostServices) {
            this.hostServices = hostServices;
        }

        public void open(String url) {
            LOG.info("Opening external URL in system browser: {}", url);
            hostServices.showDocument(url);
        }
    }

    /** Loads every packaged icon size so the OS can pick the sharpest fit for each context (taskbar,
     * title bar, alt-tab). Missing/unreadable icons are skipped rather than failing startup over
     * window chrome.
     *
     * <p>Each stream is explicitly closed once read: when running from a directory classpath (as
     * {@code mvn javafx:run} does, versus a packaged jar) {@code getResourceAsStream} hands back a
     * real open file handle on {@code target/classes/icons/*.png}. Left open, that handle survives
     * for the whole JVM lifetime and blocks {@code mvn clean} from deleting those exact files on
     * Windows until the process exits — not a build tool problem, a leaked stream in this method. */
    private static List<Image> loadAppIcons() {
        List<Image> icons = new ArrayList<>();
        for (int size : new int[] {16, 32, 48, 64, 128, 256, 512}) {
            String path = "/icons/app-icon-" + size + ".png";
            try (var in = DesktopApp.class.getResourceAsStream(path)) {
                if (in == null) {
                    LOG.warn("App icon missing from classpath: {}", path);
                    continue;
                }
                icons.add(new Image(in));
            } catch (java.io.IOException e) {
                LOG.warn("Could not close app icon stream for {}: {}", path, e.getMessage());
            }
        }
        return icons;
    }

    /**
     * Called by {@code RoleRouteHandler} (on one of its own worker threads, hence the hop to the FX
     * thread here) once server-launch or node-join succeeds. The frontend's own router shows the
     * dashboard shell on the same page (see {@code app.js}'s {@code enterDashboard}); this side only
     * needs to start real-data polling now that a connection exists to poll.
     */
    private void onOnboardingComplete(String role, String connectedServerId) {
        Platform.runLater(() -> {
            LOG.info("Onboarding complete (role={}, serverId={})", role, connectedServerId);
            monitoringService.startMonitoring();
            dockerResourcesMonitoringService.startMonitoring();
            clusterTasksMonitoringService.startMonitoring();
        });
    }

    @Override
    public void stop() throws Exception {
        super.stop();
        if (connectionManager != null) {
            connectionManager.shutdown();
        }
        if (monitoringService != null) {
            monitoringService.shutdown();
        }
        if (taskExecutionService != null) {
            taskExecutionService.shutdown();
        }
        if (dockerResourcesMonitoringService != null) {
            dockerResourcesMonitoringService.shutdown();
        }
        if (clusterTasksMonitoringService != null) {
            clusterTasksMonitoringService.shutdown();
        }
        if (localUiServer != null) {
            localUiServer.stop();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
