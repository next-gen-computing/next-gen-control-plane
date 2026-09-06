package com.nextgen.desktop.ui.server;

import com.nextgen.desktop.ui.server.dto.JobDto;
import com.nextgen.desktop.ui.service.JobExecutionService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.List;

/** {@code GET /api/jobs/stream} — every submitted job's live status, newest first. Mirrors {@link TasksStreamHandler}. */
public class JobsStreamHandler implements HttpHandler {
    private final SseChannel channel = new SseChannel("jobs");

    public JobsStreamHandler(JobExecutionService jobExecutionService, long pollIntervalMs) {
        channel.startPolling(() -> JsonSupport.toJson(snapshot(jobExecutionService)), pollIntervalMs);
    }

    // Submission order in, oldest-first — newest-first display is a frontend concern (see tasks.js).
    private static List<JobDto> snapshot(JobExecutionService jobExecutionService) {
        return jobExecutionService.getJobs().stream().map(JobDto::from).toList();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        channel.register(exchange);
    }

    void stop() {
        channel.stop();
    }
}
