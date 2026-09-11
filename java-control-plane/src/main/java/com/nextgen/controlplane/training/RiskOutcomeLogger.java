package com.nextgen.controlplane.training;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextgen.controlplane.NodeHistory;
import com.nextgen.controlplane.NodeRecord;
import com.nextgen.controlplane.NodeRegistry;
import com.nextgen.proto.ControlPlaneProto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only JSONL sink for real (signal snapshot &rarr; eventual outcome) training examples — the
 * dataset a future learned risk model trains on, not synthetic or fabricated data.
 *
 * <p>Fires exactly once per {@code ALIVE -> SUSPECTED_DEAD} transition, called from
 * {@link com.nextgen.controlplane.HeartbeatMonitor#checkHeartbeats()}. Never throws: a logging
 * failure must not take down liveness detection, so every I/O error is caught and logged instead of
 * propagated.
 */
public final class RiskOutcomeLogger {
    private static final Logger LOG = LoggerFactory.getLogger(RiskOutcomeLogger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path outputFile;

    public RiskOutcomeLogger(Path outputFile) {
        this.outputFile = outputFile;
        try {
            Path parent = outputFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            LOG.warn("Could not create parent directory for {}: {}", outputFile, e.getMessage());
        }
    }

    /**
     * Logs one real outcome record for an {@code ALIVE -> SUSPECTED_DEAD} transition.
     *
     * <p>{@code wasAtRisk} reflects {@link NodeRecord#isAtRisk()} at the moment the transition was
     * observed — an honest proxy for "had this node already been flagged as risky," not a literal
     * audit of whether a migration actually fired. No such audit trail exists elsewhere in the system
     * today; adding one is out of this logger's scope.
     */
    public synchronized void logSuspectedDeath(NodeRegistry.StatusTransition transition,
                                               NodeRecord nodeAtTransition,
                                               List<NodeHistory.Sample> recentHistory) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("nodeId", transition.nodeId());
        fields.put("transitionAtMillis", System.currentTimeMillis());
        fields.put("fromStatus", transition.from().wireName());
        fields.put("toStatus", transition.to().wireName());
        fields.put("lastRiskScore", nodeAtTransition.getRiskScore());
        fields.put("wasAtRisk", nodeAtTransition.isAtRisk());
        fields.put("riskReasons", nodeAtTransition.getRiskReasons());
        fields.put("riskAssessedAtMillis", nodeAtTransition.getRiskAssessedAtMillis());
        fields.put("lastHeartbeatMillis", nodeAtTransition.getLastHeartbeatMillis());
        fields.put("heartbeatSequence", nodeAtTransition.getHeartbeatSequence());
        fields.put("cpuUsage", nodeAtTransition.getCpuUsage());
        fields.put("cpuStale", nodeAtTransition.isCpuStale());
        fields.put("memoryUsage", nodeAtTransition.getMemoryUsage());
        fields.put("memoryStale", nodeAtTransition.isMemoryStale());
        fields.put("capabilities", projectCapabilities(nodeAtTransition.getCapabilities()));
        fields.put("recentHistory", recentHistory.stream().map(RiskOutcomeLogger::projectSample).toList());
        appendJsonLine(fields);
    }

    /**
     * Protobuf {@code Message} objects serialize poorly through a bare Jackson {@code ObjectMapper}
     * (no {@code jackson-datatype-protobuf} module is in the pom) — project only the fields this
     * dataset actually needs into a plain map instead.
     */
    private static Map<String, Object> projectCapabilities(ControlPlaneProto.NodeCapabilities capabilities) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cpuCores", capabilities.getCpuCores());
        out.put("totalMemoryBytes", capabilities.getTotalMemoryBytes());
        out.put("totalDiskBytes", capabilities.getTotalDiskBytes());
        out.put("osName", capabilities.getOsName());
        out.put("osArch", capabilities.getOsArch());
        return out;
    }

    private static Map<String, Object> projectSample(NodeHistory.Sample sample) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recordedAtMillis", sample.recordedAtMillis());
        out.put("cpuPercent", sample.cpuPercent());
        out.put("cpuAvailable", sample.cpuAvailable());
        out.put("memoryPercent", sample.memoryPercent());
        out.put("memoryAvailable", sample.memoryAvailable());
        out.put("batteryPercent", sample.batteryPercent());
        out.put("batteryAvailable", sample.batteryAvailable());
        out.put("charging", sample.charging());
        out.put("chargingKnown", sample.chargingKnown());
        out.put("onAcPower", sample.onAcPower());
        out.put("onAcPowerKnown", sample.onAcPowerKnown());
        out.put("previousRttSeconds", sample.previousRttSeconds());
        out.put("previousRttAvailable", sample.previousRttAvailable());
        return out;
    }

    private void appendJsonLine(Map<String, Object> fields) {
        try {
            String line = MAPPER.writeValueAsString(fields) + System.lineSeparator();
            Files.writeString(outputFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warn("Failed to append risk outcome record: {}", e.getMessage());
        }
    }
}
