package com.nextgen.desktop.ui.server;

import com.nextgen.desktop.ui.server.dto.TaskDto;
import com.nextgen.desktop.ui.service.TaskExecutionService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/** {@code GET /api/tasks/stream} — every submitted task's live status, newest first. */
public class TasksStreamHandler implements HttpHandler {
    private final SseChannel channel = new SseChannel("tasks");

    public TasksStreamHandler(TaskExecutionService taskExecutionService, long pollIntervalMs) {
        channel.startPolling(() -> JsonSupport.toJson(snapshot(taskExecutionService)), pollIntervalMs);
    }

    // Submission order in, oldest-first — newest-first display is a frontend concern (see tasks.js).
    private static java.util.List<TaskDto> snapshot(TaskExecutionService taskExecutionService) {
        return taskExecutionService.getTasks().stream().map(TaskDto::from).toList();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        channel.register(exchange);
    }

    void stop() {
        channel.stop();
    }
}
