package com.nextgen.controlplane.training;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextgen.controlplane.NodeHistory;
import com.nextgen.controlplane.NodeRecord;
import com.nextgen.controlplane.risk.RiskScorer;
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
 * Append-only JSONL sink for real risk snapshots — every sweep's assessment for every alive node, not
 * just the ones that later die. This is what closes the negative-example gap {@link RiskOutcomeLogger}
 * cannot close on its own: every record there is "this node died," with nothing to contrast against, so
 * a classifier trained on that file alone would be trained on 100% positive-class data.
 * {@code train_risk_model.py} labels each snapshot {@code 1} if that node transitioned to
 * {@code SUSPECTED_DEAD} within a configurable horizon afterward, else {@code 0} — a standard
 * predictive-maintenance labeling approach built entirely from real observed data.
 *
 * <p><b>Opt-in, deliberately.</b> Unlike {@code RiskOutcomeLogger}'s rare failure events, continuous
 * per-sweep logging of every node's telemetry has a real, ongoing disk-growth cost. This logger is only
 * ever constructed and wired in when {@code RISK_SNAPSHOT_LOGGING_ENABLED} is explicitly set — see
 * {@code ControlPlaneServer.start()} — and {@link com.nextgen.controlplane.RiskMonitor}'s legacy
 * constructor never references it at all.
 */
public final class RiskSnapshotLogger {
    private static final Logger LOG = LoggerFactory.getLogger(RiskSnapshotLogger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path outputFile;

    public RiskSnapshotLogger(Path outputFile) {
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

    /** Logs one real snapshot record. Never throws — a logging failure must not disrupt risk sweeps. */
    public synchronized void logSnapshot(NodeRecord node, RiskScorer.RiskAssessment assessment,
                                         List<NodeHistory.Sample> recentHistory) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("nodeId", node.getNodeId());
        fields.put("snapshotAtMillis", System.currentTimeMillis());
        fields.put("riskScore", assessment.riskScore());
        fields.put("atRisk", assessment.atRisk());
        fields.put("reasons", assessment.reasons());
        fields.put("status", node.getStatus().wireName());
        fields.put("lastHeartbeatMillis", node.getLastHeartbeatMillis());
        fields.put("cpuUsage", node.getCpuUsage());
        fields.put("cpuStale", node.isCpuStale());
        fields.put("memoryUsage", node.getMemoryUsage());
        fields.put("memoryStale", node.isMemoryStale());
        fields.put("capabilities", projectCapabilities(node.getCapabilities()));
        fields.put("recentHistory", recentHistory.stream().map(RiskSnapshotLogger::projectSample).toList());
        appendJsonLine(fields);
    }

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
            LOG.warn("Failed to append risk snapshot record: {}", e.getMessage());
        }
    }
}
