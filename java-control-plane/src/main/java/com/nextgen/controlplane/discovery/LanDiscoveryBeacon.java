package com.nextgen.controlplane.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * LanDiscoveryBeacon — High-speed local network discovery broadcaster.
 *
 * <p>Periodically broadcasts a lightweight UDP beacon to 255.255.255.255 and all local
 * subnet broadcast addresses on port {@value #DISCOVERY_PORT}. Any nearby laptop, tablet,
 * or desktop running the Next-Gen Control Plane client can detect this server instantly
 * (Google Quick Share / AirDrop style) without requiring manual IP typing or port configuration.
 */
public final class LanDiscoveryBeacon {
    private static final Logger LOG = LoggerFactory.getLogger(LanDiscoveryBeacon.class);

    public static final int DISCOVERY_PORT = 50055;
    public static final String MAGIC_HEADER = "NEXTGEN_CONTROL_PLANE";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LanDiscoveryBeacon INSTANCE = new LanDiscoveryBeacon();

    private volatile boolean running = false;
    private ScheduledExecutorService scheduler;
    private DatagramSocket broadcastSocket;

    private volatile String clusterId = "NGX-CLUSTER";
    private volatile String clusterName = "Next-Gen Control Plane";
    private volatile String hostname = "localhost";
    private volatile String primaryIp = "127.0.0.1";
    private volatile List<String> allIps = List.of("127.0.0.1");
    private volatile int grpcPort = 50051;
    private volatile int webPort = 8080;
    private volatile int nodeCount = 0;
    private volatile String status = "READY_FOR_NODES";
    private volatile boolean requiresToken = false;
    private volatile String osName = "Windows";
    private volatile String osArch = "x86_64";
    private volatile String deviceType = "windows";
    private volatile int cores = 8;
    private volatile double ramGb = 16.0;
    private volatile boolean zeroTrustEnforced = true;

    private LanDiscoveryBeacon() {
        resolveLocalIdentity();
    }

    public static LanDiscoveryBeacon getInstance() {
        return INSTANCE;
    }

    public synchronized void start(int grpcPort, int webPort, String clusterName) {
        if (running) {
            this.grpcPort = grpcPort;
            this.webPort = webPort;
            if (clusterName != null && !clusterName.isBlank()) {
                this.clusterName = clusterName;
            }
            return;
        }

        this.grpcPort = grpcPort;
        this.webPort = webPort;
        if (clusterName != null && !clusterName.isBlank()) {
            this.clusterName = clusterName;
        }
        resolveLocalIdentity();

        try {
            this.broadcastSocket = new DatagramSocket();
            this.broadcastSocket.setBroadcast(true);
        } catch (SocketException e) {
            LOG.warn("Could not create UDP broadcast socket for LAN discovery: {}", e.getMessage());
        }

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lan-discovery-beacon");
            t.setDaemon(true);
            return t;
        });

        // Broadcast every 2.0 seconds
        this.scheduler.scheduleAtFixedRate(this::broadcastBeacon, 500, 2000, TimeUnit.MILLISECONDS);
        this.running = true;
        LOG.info("📡 LAN Discovery Beacon started on UDP port {} (Announcing {}:{})", DISCOVERY_PORT, primaryIp, grpcPort);
    }

    public synchronized void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (broadcastSocket != null) {
            broadcastSocket.close();
            broadcastSocket = null;
        }
        LOG.info("LAN Discovery Beacon stopped");
    }

    public void updateNodeCount(int count) {
        this.nodeCount = count;
    }

    public void setRequiresToken(boolean requiresToken) {
        this.requiresToken = requiresToken;
    }

    public Map<String, Object> getBeaconData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("magic", MAGIC_HEADER);
        data.put("version", 1);
        data.put("clusterId", clusterId);
        data.put("clusterName", clusterName);
        data.put("hostname", hostname);
        data.put("ip", primaryIp);
        data.put("allIps", allIps);
        data.put("grpcPort", grpcPort);
        data.put("webPort", webPort);
        data.put("nodeCount", nodeCount);
        data.put("status", status);
        data.put("requiresToken", requiresToken);
        data.put("osName", osName);
        data.put("osArch", osArch);
        data.put("deviceType", deviceType);
        data.put("cores", cores);
        data.put("ramGb", ramGb);
        data.put("zeroTrustEnforced", zeroTrustEnforced);
        data.put("timestamp", System.currentTimeMillis());
        return data;
    }

    private void broadcastBeacon() {
        if (broadcastSocket == null || broadcastSocket.isClosed()) {
            return;
        }

        try {
            Map<String, Object> data = getBeaconData();
            String json = MAPPER.writeValueAsString(data);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

            // 1. Broadcast to 255.255.255.255
            try {
                DatagramPacket packet = new DatagramPacket(bytes, bytes.length,
                        InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);
                broadcastSocket.send(packet);
            } catch (Exception e) {
                // Silently ignore individual broadcast failures
            }

            // 2. Broadcast to specific interface broadcast addresses (for multi-homed/hotspots)
            for (InetAddress bcast : getInterfaceBroadcasts()) {
                try {
                    DatagramPacket packet = new DatagramPacket(bytes, bytes.length, bcast, DISCOVERY_PORT);
                    broadcastSocket.send(packet);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            LOG.debug("LAN beacon broadcast tick exception: {}", e.getMessage());
        }
    }

    private List<InetAddress> getInterfaceBroadcasts() {
        List<InetAddress> broadcasts = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress bcast = ia.getBroadcast();
                    if (bcast != null) {
                        broadcasts.add(bcast);
                    }
                }
            }
        } catch (Exception ignored) {}
        return broadcasts;
    }

    private void resolveLocalIdentity() {
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            this.hostname = localHost.getHostName();
        } catch (Exception e) {
            this.hostname = "control-plane-host";
        }

        List<String> detected = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        detected.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {}

        if (!detected.isEmpty()) {
            this.allIps = detected;
            this.primaryIp = detected.get(0);
        } else {
            this.allIps = List.of("127.0.0.1");
            this.primaryIp = "127.0.0.1";
        }

        // Generate compact cluster identifier e.g. NGX-C0A8-012A
        try {
            String[] parts = primaryIp.split("\\.");
            if (parts.length == 4) {
                int upper = (Integer.parseInt(parts[0]) << 8) | Integer.parseInt(parts[1]);
                int lower = (Integer.parseInt(parts[2]) << 8) | Integer.parseInt(parts[3]);
                this.clusterId = String.format("NGX-%04X-%04X", upper, lower);
            }
        } catch (Exception e) {
            this.clusterId = "NGX-CLUSTER";
        }

        this.osName = System.getProperty("os.name", "Windows");
        this.osArch = System.getProperty("os.arch", "x86_64");
        String lowerOs = this.osName.toLowerCase(Locale.ROOT);
        if (lowerOs.contains("mac") || lowerOs.contains("darwin") || lowerOs.contains("apple")) {
            this.deviceType = "mac";
        } else if (lowerOs.contains("nux") || lowerOs.contains("nix") || lowerOs.contains("aix")) {
            this.deviceType = "linux";
        } else {
            this.deviceType = "windows";
        }
        this.cores = Runtime.getRuntime().availableProcessors();
        long mem = 0;
        try {
            java.lang.management.OperatingSystemMXBean osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                mem = sunBean.getTotalMemorySize();
            }
        } catch (Throwable ignored) {}
        if (mem <= 0) {
            mem = Runtime.getRuntime().maxMemory();
        }
        this.ramGb = Math.round((mem / (1024.0 * 1024.0 * 1024.0)) * 10.0) / 10.0;
    }

    public String getDeviceType() { return deviceType; }
    public String getOsName() { return osName; }
    public String getOsArch() { return osArch; }
    public int getCores() { return cores; }
    public double getRamGb() { return ramGb; }
}

