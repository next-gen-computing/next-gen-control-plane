package com.nextgen.desktop.ui.server;

import com.nextgen.desktop.ui.service.ClusterTasksMonitoringService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/** {@code GET /api/cluster-tasks/stream} — every task/sub-task anywhere on the cluster, live, straight
 * from {@code TaskRegistry} via {@code ListTasks} — see {@link ClusterTasksMonitoringService}'s Javadoc
 * for why this is a separate, cluster-wide view from {@code /api/tasks} (which only ever covers what
 * this desktop-ui instance personally submitted). List-only, no actions. */
public class ClusterTasksStreamHandler implements HttpHandler {
    private final SseChannel channel = new SseChannel("cluster-tasks");

    public ClusterTasksStreamHandler(ClusterTasksMonitoringService clusterTasksMonitoringService, long pollIntervalMs) {
        channel.startPolling(() -> JsonSupport.toJson(clusterTasksMonitoringService.getTasks()), pollIntervalMs);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        channel.register(exchange);
    }

    void stop() {
        channel.stop();
    }
}
