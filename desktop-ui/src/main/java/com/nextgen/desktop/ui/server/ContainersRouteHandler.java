package com.nextgen.desktop.ui.server;

import com.nextgen.desktop.ui.server.dto.DockerControlRequestDto;
import com.nextgen.desktop.ui.server.dto.DockerControlResultDto;
import com.nextgen.desktop.ui.server.dto.ErrorDto;
import com.nextgen.desktop.ui.client.ErrorCategory;
import com.nextgen.desktop.ui.service.DockerResourcesMonitoringService;
import com.nextgen.proto.ControlPlaneProto;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/** {@code POST /api/containers/action} — real start/stop/restart/rm against whichever node the target
 * container is actually running on. Read-only listing is {@link ContainersStreamHandler}, registered
 * separately (matching {@code TasksRouteHandler}/{@code TasksStreamHandler}'s existing split). */
public class ContainersRouteHandler implements HttpHandler {
    private final DockerResourcesMonitoringService dockerResourcesMonitoringService;

    public ContainersRouteHandler(DockerResourcesMonitoringService dockerResourcesMonitoringService) {
        this.dockerResourcesMonitoringService = dockerResourcesMonitoringService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        DockerControlRequestDto request;
        try {
            request = JsonSupport.MAPPER.readValue(exchange.getRequestBody(), DockerControlRequestDto.class);
        } catch (Exception e) {
            JsonSupport.sendJson(exchange, 400, ErrorDto.of(ErrorCategory.UNKNOWN, "Invalid request body"));
            return;
        }

        ControlPlaneProto.DockerControlAction action = request.toProtoAction();
        if (request.nodeId() == null || request.nodeId().isBlank()
                || request.containerId() == null || request.containerId().isBlank() || action == null) {
            JsonSupport.sendJson(exchange, 400, ErrorDto.of(ErrorCategory.UNKNOWN,
                    "nodeId, containerId and a valid action (start/stop/restart/remove) are required"));
            return;
        }

        ControlPlaneProto.DockerControlResult result =
                dockerResourcesMonitoringService.controlContainer(request.nodeId(), request.containerId(), action);
        JsonSupport.sendJson(exchange, result.getOk() ? 200 : 502, DockerControlResultDto.from(result));
    }
}
