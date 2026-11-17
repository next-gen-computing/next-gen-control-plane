package com.nextgen.controlplane.api;

import com.nextgen.controlplane.NodeRegistry;
import com.nextgen.controlplane.discovery.LanDiscoveryBeacon;
import com.nextgen.security.NodeAdmissionGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * REST controller for LAN Quick Share discovery, Worldwide / WAN Cluster Explorer,
 * real socket ping checks, 3D Green Radar Scanner targets, and Zero-Trust Node Admission Gate.
 */
@RestController
@RequestMapping("/api/v1/discovery")
public class DiscoveryController {
    private static final Logger LOG = LoggerFactory.getLogger(DiscoveryController.class);

    private final NodeRegistry nodeRegistry;

    public DiscoveryController(NodeRegistry nodeRegistry) {
        this.nodeRegistry = nodeRegistry;
    }

    /**
     * GET /api/v1/discovery/beacon
     * Returns this control plane's live discovery metadata for subnet sweeps and HTTP probes.
     */
    @GetMapping("/beacon")
    public ResponseEntity<Map<String, Object>> getBeacon() {
        var beacon = LanDiscoveryBeacon.getInstance();
        beacon.updateNodeCount(nodeRegistry.snapshot().size());
        return ResponseEntity.ok(beacon.getBeaconData());
    }

    /**
     * GET /api/v1/discovery/pending-requests
     * Returns all pending join requests from remote worker machines waiting for approval.
     */
    @GetMapping("/pending-requests")
    public ResponseEntity<List<Map<String, Object>>> getPendingRequests() {
        return ResponseEntity.ok(NodeAdmissionGate.getInstance().getPendingRequestsList());
    }

    /**
     * POST /api/v1/discovery/join-request
     * Inbound worker nodes submit their hardware specs to join the cluster.
     * Held in quarantine until approved by the server operator.
     */
    @PostMapping("/join-request")
    public ResponseEntity<Map<String, Object>> submitJoinRequest(@RequestBody Map<String, Object> req) {
        String nodeId = String.valueOf(req.getOrDefault("nodeId", "node-" + UUID.randomUUID().toString().substring(0, 8)));
        String hostname = String.valueOf(req.getOrDefault("hostname", "remote-laptop"));
        String ip = String.valueOf(req.getOrDefault("ip", "unknown"));
        String os = String.valueOf(req.getOrDefault("os", "Unknown OS"));
        String osArch = String.valueOf(req.getOrDefault("osArch", "x86_64"));
        int cores = req.get("cores") instanceof Number n ? n.intValue() : 4;
        double ramGb = req.get("ramGb") instanceof Number n ? n.doubleValue() : 8.0;

        var pending = NodeAdmissionGate.getInstance().requestAdmission(nodeId, hostname, ip, os, osArch, cores, ramGb);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("status", "PENDING_APPROVAL");
        response.put("message", "Join request held in Zero-Trust quarantine pending server operator approval");
        response.put("requestId", pending.requestId());
        response.put("nodeId", nodeId);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/v1/discovery/approve-request
     * Server operator approves an inbound worker node.
     */
    @PostMapping("/approve-request")
    public ResponseEntity<Map<String, Object>> approveJoinRequest(@RequestBody Map<String, String> body) {
        String nodeId = body.get("nodeId");
        if (nodeId == null || nodeId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "nodeId is required"));
        }

        String token = NodeAdmissionGate.getInstance().approveNode(nodeId);
        LOG.info("✅ Operator approved node join request for {}", nodeId);

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "nodeId", nodeId,
                "token", token,
                "grpcPort", 50051,
                "status", "APPROVED"
        ));
    }

    /**
     * POST /api/v1/discovery/reject-request
     * Server operator rejects an inbound worker node.
     */
    @PostMapping("/reject-request")
    public ResponseEntity<Map<String, Object>> rejectJoinRequest(@RequestBody Map<String, String> body) {
        String nodeId = body.get("nodeId");
        if (nodeId != null) {
            NodeAdmissionGate.getInstance().rejectNode(nodeId);
            LOG.info("❌ Operator rejected node join request for {}", nodeId);
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * GET /api/v1/discovery/join-status
     * Polled by worker machines to check their admission ticket status.
     */
    @GetMapping("/join-status")
    public ResponseEntity<Map<String, Object>> getJoinStatus(@RequestParam("nodeId") String nodeId) {
        return ResponseEntity.ok(NodeAdmissionGate.getInstance().getAdmissionStatus(nodeId));
    }

    /**
     * GET /api/v1/discovery/radar-targets
     * Returns all nearby detected systems & nodes for the 3D Green Radar Scanner HUD.
     */
    @GetMapping("/radar-targets")
    public ResponseEntity<List<Map<String, Object>>> getRadarTargets() {
        List<Map<String, Object>> targets = new ArrayList<>();
        var beacon = LanDiscoveryBeacon.getInstance();
        Map<String, Object> beaconData = beacon.getBeaconData();

        // 1. Center Radar Station (Host Controller)
        Map<String, Object> hostTarget = new LinkedHashMap<>();
        hostTarget.put("nodeId", "control-plane-host");
        hostTarget.put("hostname", beaconData.get("hostname"));
        hostTarget.put("ip", beaconData.get("ip"));
        hostTarget.put("port", beaconData.get("grpcPort"));
        hostTarget.put("deviceType", beaconData.get("deviceType"));
        hostTarget.put("os", beaconData.get("osName"));
        hostTarget.put("osArch", beaconData.get("osArch"));
        hostTarget.put("cores", beaconData.get("cores"));
        hostTarget.put("ramGb", beaconData.get("ramGb"));
        hostTarget.put("status", "HOST_CONTROLLER");
        hostTarget.put("isApproved", true);
        hostTarget.put("latencyMs", 1);
        hostTarget.put("isLocal", true);
        targets.add(hostTarget);

        // 2. Active Cluster Nodes from NodeRegistry
        for (var node : nodeRegistry.snapshot()) {
            if (node.getNodeId().equals("control-plane-host")) continue;
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("nodeId", node.getNodeId());
            t.put("hostname", node.getHostname());
            t.put("ip", node.getIp());
            t.put("port", node.getPort());

            String os = "";
            String arch = "";
            int cores = 4;
            double ram = 8.0;
            String devType = "windows";
            if (node.getCapabilities() != null) {
                os = node.getCapabilities().getOsName();
                arch = node.getCapabilities().getOsArch();
                cores = node.getCapabilities().getCpuCores() > 0 ? node.getCapabilities().getCpuCores() : 4;
                if (node.getCapabilities().getTotalMemoryBytes() > 0) {
                    ram = Math.round((node.getCapabilities().getTotalMemoryBytes() / (1024.0 * 1024.0 * 1024.0)) * 10.0) / 10.0;
                }
            }
            String lowerOs = (os + " " + node.getHostname()).toLowerCase(Locale.ROOT);
            if (lowerOs.contains("mac") || lowerOs.contains("darwin") || lowerOs.contains("apple")) {
                devType = "mac";
            } else if (lowerOs.contains("nux") || lowerOs.contains("nix") || lowerOs.contains("ubuntu")) {
                devType = "linux";
            }
            t.put("deviceType", devType);
            t.put("os", os.isEmpty() ? "Verified Node OS" : os);
            t.put("osArch", arch);
            t.put("cores", cores);
            t.put("ramGb", ram);
            t.put("status", node.getStatus().name());
            t.put("isApproved", true);
            t.put("latencyMs", 3);
            targets.add(t);
        }

        // 3. Quarantined Pending Inbound Join Requests
        for (var req : NodeAdmissionGate.getInstance().getPendingRequestsList()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("nodeId", req.get("nodeId"));
            t.put("hostname", req.get("hostname"));
            t.put("ip", req.get("ip"));
            t.put("port", 50051);
            t.put("deviceType", req.get("deviceType"));
            t.put("os", req.get("os"));
            t.put("osArch", req.get("osArch"));
            t.put("cores", req.get("cores"));
            t.put("ramGb", req.get("ramGb"));
            t.put("status", "PENDING_APPROVAL");
            t.put("isApproved", false);
            t.put("latencyMs", 5);
            targets.add(t);
        }

        return ResponseEntity.ok(targets);
    }

    /**
     * GET /api/v1/discovery/wan-servers
     * Returns worldwide / WAN cluster seed directory with real live socket latency measurements.
     */
    @GetMapping("/wan-servers")
    public ResponseEntity<List<Map<String, Object>>> getWanServers() {
        var beacon = LanDiscoveryBeacon.getInstance();
        Map<String, Object> localData = beacon.getBeaconData();

        List<Map<String, Object>> directory = new ArrayList<>();

        // 1. Current Local Server / LAN Master
        long localPing = measureSocketLatency("127.0.0.1", 50051, 1000);
        Map<String, Object> selfEntry = new LinkedHashMap<>();
        selfEntry.put("clusterName", localData.get("clusterName") + " (Local Gateway)");
        selfEntry.put("hostname", localData.get("hostname"));
        selfEntry.put("address", localData.get("ip") + ":" + localData.get("grpcPort"));
        selfEntry.put("webUrl", "http://" + localData.get("ip") + ":" + localData.get("webPort"));
        selfEntry.put("region", "Local Network / Hotspot");
        selfEntry.put("latencyMs", localPing >= 0 ? localPing : 1);
        selfEntry.put("reachable", localPing >= 0);
        selfEntry.put("nodeCount", nodeRegistry.snapshot().size());
        selfEntry.put("isLocal", true);
        directory.add(selfEntry);

        // 2. Default Public/WAN Gateway targets (e.g. localhost fallback & public seeds)
        String[] wanSeeds = {
                "localhost:50051",
                "127.0.0.1:50051"
        };

        for (String seed : wanSeeds) {
            if (seed.startsWith((String) localData.get("ip"))) continue;
            String[] parts = seed.split(":");
            String host = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 50051;
            long ping = measureSocketLatency(host, port, 1200);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("clusterName", "NextGen Edge Gateway [" + host + "]");
            entry.put("hostname", host);
            entry.put("address", seed);
            entry.put("webUrl", "http://" + host + ":8080");
            entry.put("region", "Edge Backbone");
            entry.put("latencyMs", ping);
            entry.put("reachable", ping >= 0);
            entry.put("nodeCount", ping >= 0 ? nodeRegistry.snapshot().size() : 0);
            entry.put("isLocal", host.equals("127.0.0.1") || host.equals("localhost"));
            directory.add(entry);
        }

        return ResponseEntity.ok(directory);
    }

    /**
     * POST /api/v1/discovery/ping
     * Real socket connect probe to measure exact network latency to any IP:Port.
     */
    @PostMapping("/ping")
    public ResponseEntity<Map<String, Object>> pingHost(@RequestBody Map<String, String> body) {
        String address = body.getOrDefault("address", "127.0.0.1:50051");
        String host = address;
        int port = 50051;
        if (address.contains(":")) {
            String[] parts = address.split(":");
            host = parts[0];
            try {
                port = Integer.parseInt(parts[1]);
            } catch (Exception ignored) {}
        }

        long latency = measureSocketLatency(host, port, 3000);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("address", address);
        result.put("host", host);
        result.put("port", port);
        result.put("reachable", latency >= 0);
        result.put("latencyMs", latency);
        if (latency < 0) {
            result.put("error", "Connection timed out or refused by host/firewall");
        }
        return ResponseEntity.ok(result);
    }

    private static long measureSocketLatency(String host, int port, int timeoutMs) {
        long start = System.currentTimeMillis();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return Math.max(1, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return -1; // Unreachable
        }
    }
}

