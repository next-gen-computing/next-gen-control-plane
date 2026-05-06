package com.nextgen.desktop.ui.util;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * Encodes/decodes a LAN IP address + port into a compact, human-readable Server ID.
 * 
 * Format: NGX-XXXX-XXXX
 *   - First 4 hex chars: upper 16 bits (IP octets 1 & 2)
 *   - Last 4 hex chars: lower 16 bits (IP octets 3 & 4)
 * 
 * Example: 192.168.1.100 → NGX-C0A8-0164
 * 
 * Port defaults to 50051 (standard gRPC port), but can be overridden.
 */
public class ServerIdCodec {

    public static final String PREFIX = "NGX";
    public static final int DEFAULT_PORT = 50051;

    private ServerIdCodec() {}

    /**
     * Encode an IP address into a Server ID string.
     * @param ip IPv4 address string (e.g. "192.168.1.100")
     * @return Server ID like "NGX-C0A8-0164"
     */
    public static String encode(String ip) {
        String[] octets = ip.split("\\.");
        if (octets.length != 4) {
            throw new IllegalArgumentException("Invalid IPv4 address: " + ip);
        }

        int o1 = Integer.parseInt(octets[0]);
        int o2 = Integer.parseInt(octets[1]);
        int o3 = Integer.parseInt(octets[2]);
        int o4 = Integer.parseInt(octets[3]);

        String upper = String.format("%02X%02X", o1, o2);
        String lower = String.format("%02X%02X", o3, o4);

        return PREFIX + "-" + upper + "-" + lower;
    }

    /**
     * Decode a Server ID back into a ServerAddress (IP + default port).
     * @param serverId Server ID like "NGX-C0A8-0164"
     * @return ServerAddress with decoded IP and default port
     */
    public static ServerAddress decode(String serverId) {
        String cleaned = serverId.toUpperCase().trim();

        if (!cleaned.startsWith(PREFIX + "-")) {
            throw new IllegalArgumentException("Invalid Server ID format. Expected NGX-XXXX-XXXX, got: " + serverId);
        }

        String[] parts = cleaned.split("-");
        if (parts.length != 3 || parts[1].length() != 4 || parts[2].length() != 4) {
            throw new IllegalArgumentException("Invalid Server ID format. Expected NGX-XXXX-XXXX, got: " + serverId);
        }

        try {
            int upper = Integer.parseInt(parts[1], 16);
            int lower = Integer.parseInt(parts[2], 16);

            int o1 = (upper >> 8) & 0xFF;
            int o2 = upper & 0xFF;
            int o3 = (lower >> 8) & 0xFF;
            int o4 = lower & 0xFF;

            String ip = o1 + "." + o2 + "." + o3 + "." + o4;
            return new ServerAddress(ip, DEFAULT_PORT);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid hex in Server ID: " + serverId, e);
        }
    }

    /**
     * Detect the best LAN IP address for this machine.
     * Prefers non-loopback, site-local (192.168.x.x, 10.x.x.x, 172.16-31.x.x) addresses.
     */
    public static String detectLanIp() {
        try {
            // Best method: use the OS routing table to find the interface that
            // would reach the internet. This automatically skips virtual NICs.
            try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
                socket.connect(InetAddress.getByName("8.8.8.8"), 80);
                String ip = socket.getLocalAddress().getHostAddress();
                if (ip != null && !ip.equals("0.0.0.0") && !ip.startsWith("127.")) {
                    return ip;
                }
            }

            // Fallback: scan interfaces, but skip virtual/Docker/WSL adapters
            String bestIp = null;
            int bestScore = -1;

            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp() || ni.isVirtual()) continue;

                String displayName = ni.getDisplayName().toLowerCase();
                String name = ni.getName().toLowerCase();

                // Skip known virtual adapters (Docker, WSL, Hyper-V, VirtualBox, VMware)
                if (displayName.contains("docker") || displayName.contains("wsl") ||
                    displayName.contains("hyper-v") || displayName.contains("vethernet") ||
                    displayName.contains("virtualbox") || displayName.contains("vmware") ||
                    displayName.contains("vmnet") || displayName.contains("virbr") ||
                    displayName.contains("vbox") || displayName.contains("virtual")) {
                    continue;
                }

                // Score interfaces: prefer real Wi-Fi and Ethernet
                int score = 0;
                if (name.contains("wlan") || name.contains("wifi") || displayName.contains("wi-fi") ||
                    displayName.contains("wireless")) {
                    score = 10; // Wi-Fi
                } else if (name.contains("eth") || displayName.contains("ethernet") ||
                           displayName.contains("realtek") || displayName.contains("intel")) {
                    score = 9; // Ethernet
                } else {
                    score = 1; // Unknown but not virtual
                }

                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof java.net.Inet4Address && addr.isSiteLocalAddress()) {
                        if (score > bestScore) {
                            bestScore = score;
                            bestIp = addr.getHostAddress();
                        }
                    }
                }
            }

            if (bestIp != null) return bestIp;

        } catch (Exception e) {
            // Fall through
        }
        return "127.0.0.1";
    }

    /**
     * Generate the Server ID for the current machine.
     */
    public static String generateForThisMachine() {
        return encode(detectLanIp());
    }

    /**
     * Validate whether a string looks like a valid Server ID.
     */
    public static boolean isValid(String serverId) {
        if (serverId == null || serverId.isBlank()) return false;
        try {
            decode(serverId);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Format a partial input into the NGX-XXXX-XXXX pattern.
     * Strips non-hex characters and inserts dashes at the right positions.
     */
    public static String autoFormat(String input) {
        String clean = input.toUpperCase().replaceAll("[^A-F0-9]", "");
        if (clean.length() > 8) clean = clean.substring(0, 8);

        StringBuilder sb = new StringBuilder("NGX");
        if (clean.length() > 0) {
            sb.append("-");
            sb.append(clean, 0, Math.min(clean.length(), 4));
        }
        if (clean.length() > 4) {
            sb.append("-");
            sb.append(clean, 4, Math.min(clean.length(), 8));
        }
        return sb.toString();
    }

    /**
     * Holds a decoded server address.
     */
    public static class ServerAddress {
        private final String ip;
        private final int port;

        public ServerAddress(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }

        public String getIp() { return ip; }
        public int getPort() { return port; }

        @Override
        public String toString() {
            return ip + ":" + port;
        }
    }
}
