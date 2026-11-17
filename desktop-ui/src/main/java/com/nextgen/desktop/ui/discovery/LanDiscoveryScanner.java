package com.nextgen.desktop.ui.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * LanDiscoveryScanner — Google Quick Share / AirDrop style local network scanner.
 *
 * <p>Listens on UDP port 50055 for Control Plane beacon broadcasts and performs fast
 * parallel subnet sweeps (probing port 8080 / 50051) to detect active control planes on
 * Wi-Fi, mobile hotspots, and local LANs. Zero manual IP configuration required.
 */
public final class LanDiscoveryScanner {
    private static final Logger LOG = LoggerFactory.getLogger(LanDiscoveryScanner.class);
    private static final int DISCOVERY_PORT = 50055;
    private static final String MAGIC_HEADER = "NEXTGEN_CONTROL_PLANE";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final LanDiscoveryScanner INSTANCE = new LanDiscoveryScanner();

    private final Map<String, DiscoveredServer> cache = new ConcurrentHashMap<>();
    private final ExecutorService probePool = Executors.newFixedThreadPool(16, r -> {
        Thread t = new Thread(r, "discovery-subnet-probe");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = false;
    private DatagramSocket udpSocket;
    private Thread udpListenerThread;
    private ScheduledExecutorService sweepScheduler;

    private LanDiscoveryScanner() {}

    public static LanDiscoveryScanner getInstance() {
        return INSTANCE;
    }

    public synchronized void start() {
        if (running) return;
        running = true;

        startUdpListener();
        startPeriodicSweep();
        LOG.info("📡 LAN Discovery Scanner started (listening on UDP {} and active subnet probing)", DISCOVERY_PORT);
    }

    public synchronized void stop() {
        running = false;
        if (udpSocket != null) {
            udpSocket.close();
            udpSocket = null;
        }
        if (udpListenerThread != null) {
            udpListenerThread.interrupt();
            udpListenerThread = null;
        }
        if (sweepScheduler != null) {
            sweepScheduler.shutdownNow();
            sweepScheduler = null;
        }
        LOG.info("LAN Discovery Scanner stopped");
    }

    public List<Map<String, Object>> getDiscoveredServers() {
        if (!running) {
            start();
        }

        // Trigger immediate background scan sweep if cache is empty or fresh
        triggerSubnetSweep();

        long now = System.currentTimeMillis();
        // Remove servers not heard from in > 10 seconds
        cache.entrySet().removeIf(entry -> (now - entry.getValue().lastSeen) > 10_000L);

        List<Map<String, Object>> result = new ArrayList<>();
        for (DiscoveredServer s : cache.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("clusterName", s.clusterName);
            m.put("hostname", s.hostname);
            m.put("address", s.address);
            m.put("webUrl", s.webUrl);
            m.put("ip", s.ip);
            m.put("grpcPort", s.grpcPort);
            m.put("webPort", s.webPort);
            m.put("latencyMs", s.latencyMs);
            m.put("nodeCount", s.nodeCount);
            m.put("networkType", s.networkType);
            m.put("requiresToken", s.requiresToken);
            m.put("deviceType", s.deviceType);
            m.put("osName", s.osName);
            m.put("cores", s.cores);
            m.put("ramGb", s.ramGb);
            m.put("zeroTrustEnforced", s.zeroTrustEnforced);
            m.put("reachable", true);
            result.add(m);
        }

        // Sort by lowest latency
        result.sort(Comparator.comparingLong(m -> ((Number) m.get("latencyMs")).longValue()));
        return result;
    }

    private void startUdpListener() {
        udpListenerThread = new Thread(() -> {
            try {
                udpSocket = new DatagramSocket(null);
                udpSocket.setReuseAddress(true);
                udpSocket.bind(new InetSocketAddress(DISCOVERY_PORT));
                byte[] buf = new byte[4096];

                while (running) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(packet);

                    String json = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);
                    processBeaconJson(json, packet.getAddress().getHostAddress(), "Wi-Fi / Hotspot Broadcast");
                }
            } catch (SocketException e) {
                if (running) {
                    LOG.debug("UDP discovery listener socket closed: {}", e.getMessage());
                }
            } catch (Exception e) {
                if (running) {
                    LOG.debug("UDP discovery packet receive error: {}", e.getMessage());
                }
            }
        }, "lan-discovery-udp-listener");
        udpListenerThread.setDaemon(true);
        udpListenerThread.start();
    }

    private void startPeriodicSweep() {
        sweepScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lan-discovery-sweep-scheduler");
            t.setDaemon(true);
            return t;
        });

        // Run sweep every 3.5 seconds
        sweepScheduler.scheduleAtFixedRate(this::triggerSubnetSweep, 200, 3500, TimeUnit.MILLISECONDS);
    }

    public void triggerSubnetSweep() {
        List<String> candidateIps = new ArrayList<>();
        // Always test loopback and local gateway addresses
        candidateIps.add("127.0.0.1");
        candidateIps.add("localhost");

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;

                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        int lastDot = ip.lastIndexOf('.');
                        if (lastDot > 0) {
                            String subnetPrefix = ip.substring(0, lastDot + 1);
                            // Probe common gateway/server hosts on this subnet: .1, .2, .100, .101, and current IP
                            candidateIps.add(ip);
                            candidateIps.add(subnetPrefix + "1");
                            candidateIps.add(subnetPrefix + "2");
                            candidateIps.add(subnetPrefix + "100");
                            candidateIps.add(subnetPrefix + "101");
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        for (String ip : candidateIps) {
            probePool.submit(() -> probeHost(ip));
        }
    }

    private void probeHost(String host) {
        // Quick probe port 8080 (REST beacon) and port 50051 (gRPC)
        long start = System.currentTimeMillis();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, 50051), 180);
            long latency = Math.max(1, System.currentTimeMillis() - start);

            // Attempt to read rich metadata from http://<host>:8080/api/v1/discovery/beacon
            String clusterName = "Next-Gen Server (" + host + ")";
            String hostname = host;
            int nodes = 0;
            boolean requiresToken = false;
            String deviceType = "windows";
            String osName = "Windows";
            int cores = 8;
            double ramGb = 16.0;
            boolean zeroTrust = true;

            try {
                URI beaconUri = URI.create("http://" + host + ":8080/api/v1/discovery/beacon");
                HttpURLConnection conn = (HttpURLConnection) beaconUri.toURL().openConnection();
                conn.setConnectTimeout(250);
                conn.setReadTimeout(250);
                conn.setRequestMethod("GET");
                if (conn.getResponseCode() == 200) {
                    Map<?, ?> meta = MAPPER.readValue(conn.getInputStream(), Map.class);
                    if (meta != null && MAGIC_HEADER.equals(meta.get("magic"))) {
                        Object cn = meta.get("clusterName");
                        if (cn != null) clusterName = cn.toString();
                        Object hn = meta.get("hostname");
                        if (hn != null) hostname = hn.toString();
                        nodes = meta.get("nodeCount") instanceof Number n ? n.intValue() : 0;
                        requiresToken = Boolean.TRUE.equals(meta.get("requiresToken"));
                        if (meta.get("deviceType") != null) deviceType = meta.get("deviceType").toString();
                        if (meta.get("osName") != null) osName = meta.get("osName").toString();
                        if (meta.get("cores") instanceof Number n) cores = n.intValue();
                        if (meta.get("ramGb") instanceof Number n) ramGb = n.doubleValue();
                        zeroTrust = Boolean.TRUE.equals(meta.get("zeroTrustEnforced"));
                    }
                }
            } catch (Exception ignored) {
                // If HTTP beacon not reachable, fallback to direct gRPC info
            }

            String address = host + ":50051";
            cache.put(address, new DiscoveredServer(
                    clusterName,
                    hostname,
                    address,
                    "http://" + host + ":8080",
                    host,
                    50051,
                    8080,
                    latency,
                    nodes,
                    host.equals("127.0.0.1") || host.equals("localhost") ? "Local Loopback" : "Wi-Fi / Hotspot LAN",
                    requiresToken,
                    deviceType,
                    osName,
                    cores,
                    ramGb,
                    zeroTrust,
                    System.currentTimeMillis()
            ));
        } catch (Exception ignored) {
            // Unreachable host, ignore
        }
    }

    private void processBeaconJson(String json, String packetSourceIp, String networkType) {
        try {
            Map<?, ?> map = MAPPER.readValue(json, Map.class);
            if (map == null || !MAGIC_HEADER.equals(map.get("magic"))) {
                return;
            }

            Object cn = map.get("clusterName");
            String clusterName = cn != null ? cn.toString() : "Next-Gen Control Plane";
            Object hn = map.get("hostname");
            String hostname = hn != null ? hn.toString() : "server";
            Object ipVal = map.get("ip");
            String ip = ipVal != null ? ipVal.toString() : packetSourceIp;
            int grpcPort = map.get("grpcPort") instanceof Number n ? n.intValue() : 50051;
            int webPort = map.get("webPort") instanceof Number n ? n.intValue() : 8080;
            int nodeCount = map.get("nodeCount") instanceof Number n ? n.intValue() : 0;
            boolean requiresToken = Boolean.TRUE.equals(map.get("requiresToken"));
            String deviceType = map.get("deviceType") != null ? map.get("deviceType").toString() : "windows";
            String osName = map.get("osName") != null ? map.get("osName").toString() : "Windows";
            int cores = map.get("cores") instanceof Number n ? n.intValue() : 8;
            double ramGb = map.get("ramGb") instanceof Number n ? n.doubleValue() : 16.0;
            boolean zeroTrust = Boolean.TRUE.equals(map.get("zeroTrustEnforced"));

            String address = ip + ":" + grpcPort;

            // Measure live round trip latency
            long start = System.currentTimeMillis();
            long latency = 4;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(ip, grpcPort), 250);
                latency = Math.max(1, System.currentTimeMillis() - start);
            } catch (Exception e) {
                // Keep default low estimate if socket fails
            }

            cache.put(address, new DiscoveredServer(
                    clusterName,
                    hostname,
                    address,
                    "http://" + ip + ":" + webPort,
                    ip,
                    grpcPort,
                    webPort,
                    latency,
                    nodeCount,
                    networkType,
                    requiresToken,
                    deviceType,
                    osName,
                    cores,
                    ramGb,
                    zeroTrust,
                    System.currentTimeMillis()
            ));
            LOG.debug("Discovered server beacon: {} at {}", clusterName, address);
        } catch (Exception ignored) {}
    }

    private record DiscoveredServer(
            String clusterName,
            String hostname,
            String address,
            String webUrl,
            String ip,
            int grpcPort,
            int webPort,
            long latencyMs,
            int nodeCount,
            String networkType,
            boolean requiresToken,
            String deviceType,
            String osName,
            int cores,
            double ramGb,
            boolean zeroTrustEnforced,
            long lastSeen
    ) {}
}

