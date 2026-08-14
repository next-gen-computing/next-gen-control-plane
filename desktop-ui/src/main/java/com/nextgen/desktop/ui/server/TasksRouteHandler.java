package com.nextgen.desktop.ui.server;

import com.nextgen.desktop.ui.model.TaskModel;
import com.nextgen.desktop.ui.server.dto.ErrorDto;
import com.nextgen.desktop.ui.server.dto.TaskDto;
import com.nextgen.desktop.ui.server.dto.TaskSubmitRequestDto;
import com.nextgen.desktop.ui.client.ErrorCategory;
import com.nextgen.desktop.ui.service.NodeMonitoringService;
import com.nextgen.desktop.ui.service.TaskExecutionService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * {@code POST /api/tasks} — submits a real prime-count-range task, ported from
 * {@code TaskSubmissionView}'s submit handler. Registered separately from {@link TasksStreamHandler}
 * ({@code /api/tasks/stream}), which reports every submission's real progress as
 * {@code TaskExecutionService} polls the control plane for it.
 */
public class TasksRouteHandler implements HttpHandler {
    private final TaskExecutionService taskExecutionService;
    private final NodeMonitoringService monitoringService;

    public TasksRouteHandler(TaskExecutionService taskExecutionService, NodeMonitoringService monitoringService) {
        this.taskExecutionService = taskExecutionService;
        this.monitoringService = monitoringService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        TaskSubmitRequestDto request;
        try {
            request = JsonSupport.MAPPER.readValue(exchange.getRequestBody(), TaskSubmitRequestDto.class);
            if (request.rangeEnd() < request.rangeStart()) {
                throw new IllegalArgumentException("rangeEnd must be >= rangeStart");
            }
        } catch (Exception e) {
            JsonSupport.sendJson(exchange, 400, ErrorDto.of(ErrorCategory.UNKNOWN, "Invalid task range"));
            return;
        }

        // Better to say so up front than to submit into a cluster with nowhere to run it — same
        // pre-check TaskSubmissionView makes before calling submitTask.
        boolean noHealthyNode = monitoringService.getClusterSummary().getHealthyNodes() == 0;

        TaskModel task = taskExecutionService.submitTask(request.rangeStart(), request.rangeEnd());

        JsonSupport.sendJson(exchange, 200, new TaskSubmitResultDto(TaskDto.from(task), noHealthyNode));
    }

    /** Wraps the created task with the same "no healthy node" warning the JavaFX form shows inline. */
    private record TaskSubmitResultDto(TaskDto task, boolean noHealthyNodeWarning) {
    }
}
