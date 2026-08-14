package com.nextgen.desktop.ui.server;

import com.nextgen.desktop.ui.client.GrpcConnectionManager;
import com.nextgen.desktop.ui.server.dto.ClusterSummaryDto;
import com.nextgen.desktop.ui.server.dto.ConnectionStateDto;
import com.nextgen.desktop.ui.server.dto.JobDto;
import com.nextgen.desktop.ui.server.dto.NodeDto;
import com.nextgen.desktop.ui.server.dto.StateDto;
import com.nextgen.desktop.ui.server.dto.TaskDto;
import com.nextgen.desktop.ui.service.JobExecutionService;
import com.nextgen.desktop.ui.service.NodeMonitoringService;
import com.nextgen.desktop.ui.service.TaskExecutionService;
import com.nextgen.desktop.ui.service.ThemeService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code GET /api/state} — a one-shot snapshot of everything needed for first paint, so the frontend
 * does not have to wait on every SSE channel's first event before it can render anything.
 */
public class StateRouteHandler implements HttpHandler {
    private final Supplier<String> roleSupplier;
    private final Supplier<String> serverIdSupplier;
    private final ThemeService themeService;
    private final NodeMonitoringService monitoringService;
    private final TaskExecutionService taskExecutionService;
    private final JobExecutionService jobExecutionService;
    private final GrpcConnectionManager connectionManager;

    public StateRouteHandler(Supplier<String> roleSupplier, Supplier<String> serverIdSupplier,
                             ThemeService themeService, NodeMonitoringService monitoringService,
                             TaskExecutionService taskExecutionService, JobExecutionService jobExecutionService,
                             GrpcConnectionManager connectionManager) {
        this.roleSupplier = roleSupplier;
        this.serverIdSupplier = serverIdSupplier;
        this.themeService = themeService;
        this.monitoringService = monitoringService;
        this.taskExecutionService = taskExecutionService;
        this.jobExecutionService = jobExecutionService;
        this.connectionManager = connectionManager;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        List<NodeDto> nodes = NodesStreamHandler.snapshot(monitoringService);
        List<TaskDto> tasks = taskExecutionService.getTasks().stream().map(TaskDto::from).toList();
        List<JobDto> jobs = jobExecutionService.getJobs().stream().map(JobDto::from).toList();

        StateDto state = new StateDto(
                roleSupplier.get(),
                serverIdSupplier.get(),
                themeService.isDarkMode(),
                ConnectionStateDto.from(monitoringService.getConnectionState()),
                ClusterSummaryDto.from(monitoringService.getClusterSummary()),
                nodes,
                tasks,
                jobs,
                connectionManager.getCurrentHost(),
                connectionManager.getCurrentControlPlanePort(),
                connectionManager.getCurrentPredictorPort(),
                monitoringService.getRefreshInterval()
        );

        JsonSupport.sendJson(exchange, 200, state);
    }
}
