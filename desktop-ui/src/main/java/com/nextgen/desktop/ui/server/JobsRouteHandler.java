package com.nextgen.desktop.ui.server;

import com.nextgen.desktop.ui.client.ErrorCategory;
import com.nextgen.desktop.ui.model.JobModel;
import com.nextgen.desktop.ui.server.dto.ErrorDto;
import com.nextgen.desktop.ui.server.dto.JobDto;
import com.nextgen.desktop.ui.server.dto.JobSubmitRequestDto;
import com.nextgen.desktop.ui.service.JobExecutionService;
import com.nextgen.desktop.ui.service.NodeMonitoringService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * {@code POST /api/jobs} — submits a job (N real sub-tasks split from one range). Mirrors
 * {@link TasksRouteHandler} exactly; registered separately from {@link JobsStreamHandler}
 * ({@code /api/jobs/stream}), which reports every job's live N/M sub-task progress.
 */
public class JobsRouteHandler implements HttpHandler {
    private final JobExecutionService jobExecutionService;
    private final NodeMonitoringService monitoringService;

    public JobsRouteHandler(JobExecutionService jobExecutionService, NodeMonitoringService monitoringService) {
        this.jobExecutionService = jobExecutionService;
        this.monitoringService = monitoringService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        JobSubmitRequestDto request;
        try {
            request = JsonSupport.MAPPER.readValue(exchange.getRequestBody(), JobSubmitRequestDto.class);
            if (request.rangeEnd() < request.rangeStart()) {
                throw new IllegalArgumentException("rangeEnd must be >= rangeStart");
            }
            if (request.subTaskCount() < 1) {
                throw new IllegalArgumentException("subTaskCount must be >= 1");
            }
        } catch (Exception e) {
            JsonSupport.sendJson(exchange, 400, ErrorDto.of(ErrorCategory.UNKNOWN, "Invalid job request"));
            return;
        }

        // Better to say so up front than to submit into a cluster with nowhere to run it — same
        // pre-check TasksRouteHandler makes before calling submitTask.
        boolean noHealthyNode = monitoringService.getClusterSummary().getHealthyNodes() == 0;

        JobModel job = jobExecutionService.submitJob(request.rangeStart(), request.rangeEnd(), request.subTaskCount());

        JsonSupport.sendJson(exchange, 200, new JobSubmitResultDto(JobDto.from(job), noHealthyNode));
    }

    /** Wraps the created job with the same "no healthy node" warning the task form shows inline. */
    private record JobSubmitResultDto(JobDto job, boolean noHealthyNodeWarning) {
    }
}
