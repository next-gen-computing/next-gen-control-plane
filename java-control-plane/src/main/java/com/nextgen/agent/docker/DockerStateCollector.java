package com.nextgen.agent.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextgen.proto.ControlPlaneProto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads this node's REAL Docker inventory — {@code docker ps -a}/{@code images}/{@code volume ls}/
 * {@code network ls}, each via {@code --format {{json .}}} so every row is real, structured JSON from
 * Docker itself, never parsed out of column-aligned text. Matches {@link
 * com.nextgen.agent.DockerCapabilityDetector}'s own discipline exactly: bounded-timeout real CLI
 * invocations, and any failure (missing binary, daemon down, timeout) returns an empty result — never a
 * guessed or partial-looking one. Also the one place that issues real {@code docker start/stop/restart/
 * rm} for container control.
 *
 * <p>Only ever constructed/polled on a node that {@link com.nextgen.agent.DockerCapabilityDetector} has
 * already confirmed has a reachable daemon — this class does not re-check that itself.
 */
public final class DockerStateCollector {
    private static final Logger LOG = LoggerFactory.getLogger(DockerStateCollector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long LIST_TIMEOUT_SECONDS = 10;
    private static final long CONTROL_TIMEOUT_SECONDS = 20;

    /** Docker's default `docker ps`/`images`/`network ls` CreatedAt format, e.g.
     * "2026-08-13 10:15:23 +0000 UTC". Best-effort only — a format Docker changes across versions is a
     * cosmetic loss (falls back to 0, never a fabricated timestamp), not a functional one. */
    private static final DateTimeFormatter DOCKER_CREATED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xx 'UTC'");

    private static final Pattern SIZE_PATTERN =
            Pattern.compile("([0-9.]+)\\s*([kKmMgGtT]?i?)[bB]?");

    /** Stage MM: Docker embeds a health-check's result as a parenthetical inside its own `Status` string
     * (e.g. "Up 5 minutes (healthy)", "Up 2 minutes (health: starting)") — no separate `docker ps --format`
     * placeholder exposes it directly, and calling `docker inspect` per-container here would multiply
     * this collector's real process-spawn cost by however many containers exist. Extracting from data
     * already being read into {@code state_text} is free. A container with no HEALTHCHECK at all has no
     * parenthetical, correctly yielding an empty (never fabricated) health_status. */
    private static final Pattern HEALTH_STATUS_PATTERN =
            Pattern.compile("\\((healthy|unhealthy|health: starting)\\)");

    public ControlPlaneProto.DockerStateReport collect() {
        return ControlPlaneProto.DockerStateReport.newBuilder()
                .addAllContainers(listContainers())
                .addAllImages(listImages())
                .addAllVolumes(listVolumes())
                .addAllNetworks(listNetworks())
                .setReportedAtEpochMillis(System.currentTimeMillis())
                .build();
    }

    public List<ControlPlaneProto.DockerContainerInfo> listContainers() {
        List<ControlPlaneProto.DockerContainerInfo> result = new ArrayList<>();
        for (JsonNode row : runFormatJson("docker", "ps", "-a", "--format", "{{json .}}")) {
            String statusText = text(row, "Status");
            result.add(ControlPlaneProto.DockerContainerInfo.newBuilder()
                    .setContainerId(text(row, "ID"))
                    .setName(text(row, "Names"))
                    .setImage(text(row, "Image"))
                    .setStatus(text(row, "State"))
                    .setStateText(statusText)
                    .addAllPorts(splitPorts(text(row, "Ports")))
                    .setCreatedAtEpochMillis(parseDockerCreatedAt(text(row, "CreatedAt")))
                    .setCommand(text(row, "Command"))
                    .setHealthStatus(extractHealthStatus(statusText))
                    .build());
        }
        return result;
    }

    /** @return "healthy"/"unhealthy"/"health: starting" extracted from Docker's own {@code Status}
     * string, or "" when the container declares no HEALTHCHECK at all — see {@link
     * #HEALTH_STATUS_PATTERN}'s Javadoc for why this is a regex extraction rather than a second
     * {@code docker inspect} call. */
    private static String extractHealthStatus(String statusText) {
        if (statusText == null || statusText.isBlank()) {
            return "";
        }
        Matcher matcher = HEALTH_STATUS_PATTERN.matcher(statusText);
        return matcher.find() ? matcher.group(1) : "";
    }

    public List<ControlPlaneProto.DockerImageInfo> listImages() {
        List<ControlPlaneProto.DockerImageInfo> result = new ArrayList<>();
        for (JsonNode row : runFormatJson("docker", "images", "--format", "{{json .}}")) {
            result.add(ControlPlaneProto.DockerImageInfo.newBuilder()
                    .setImageId(text(row, "ID"))
                    .setRepository(text(row, "Repository"))
                    .setTag(text(row, "Tag"))
                    .setSizeBytes(parseDockerSize(text(row, "Size")))
                    .setCreatedAtEpochMillis(parseDockerCreatedAt(text(row, "CreatedAt")))
                    .build());
        }
        return result;
    }

    public List<ControlPlaneProto.DockerVolumeInfo> listVolumes() {
        List<ControlPlaneProto.DockerVolumeInfo> result = new ArrayList<>();
        for (JsonNode row : runFormatJson("docker", "volume", "ls", "--format", "{{json .}}")) {
            result.add(ControlPlaneProto.DockerVolumeInfo.newBuilder()
                    .setName(text(row, "Name"))
                    .setDriver(text(row, "Driver"))
                    .setMountpoint(text(row, "Mountpoint"))
                    .build());
        }
        return result;
    }

    public List<ControlPlaneProto.DockerNetworkInfo> listNetworks() {
        List<ControlPlaneProto.DockerNetworkInfo> result = new ArrayList<>();
        for (JsonNode row : runFormatJson("docker", "network", "ls", "--format", "{{json .}}")) {
            result.add(ControlPlaneProto.DockerNetworkInfo.newBuilder()
                    .setNetworkId(text(row, "ID"))
                    .setName(text(row, "Name"))
                    .setDriver(text(row, "Driver"))
                    .setScope(text(row, "Scope"))
                    .build());
        }
        return result;
    }

    public record ControlResult(boolean ok, String message) {
    }

    public ControlResult start(String containerId) {
        return runControlCommand("docker", "start", containerId);
    }

    public ControlResult stop(String containerId) {
        return runControlCommand("docker", "stop", "--time", "10", containerId);
    }

    public ControlResult restart(String containerId) {
        return runControlCommand("docker", "restart", "--time", "10", containerId);
    }

    /** Force-removes — matches real Docker Desktop's own "Delete" action, which stops-then-removes a
     * still-running container rather than requiring it to already be stopped. */
    public ControlResult remove(String containerId) {
        return runControlCommand("docker", "rm", "-f", containerId);
    }

    // ── Stage FF: image/volume/network write actions ──────────────────────
    // Extends the above container-only start/stop/restart/rm coverage to the three resource types
    // Stage T shipped list-only. Same real-CLI-invocation, honest-result discipline throughout.

    public ControlResult pullImage(String reference) {
        return runControlCommand("docker", "pull", reference);
    }

    public ControlResult tagImage(String sourceReference, String targetReference) {
        return runControlCommand("docker", "tag", sourceReference, targetReference);
    }

    public ControlResult removeImage(String reference) {
        return runControlCommand("docker", "rmi", "-f", reference);
    }

    public ControlResult createVolume(String name) {
        return runControlCommand("docker", "volume", "create", name);
    }

    public ControlResult removeVolume(String name) {
        return runControlCommand("docker", "volume", "rm", "-f", name);
    }

    public ControlResult createNetwork(String name) {
        return runControlCommand("docker", "network", "create", name);
    }

    public ControlResult removeNetwork(String name) {
        return runControlCommand("docker", "network", "rm", name);
    }

    private ControlResult runControlCommand(String... command) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            return new ControlResult(false, "Could not start '" + String.join(" ", command) + "': " + e.getMessage());
        }

        String output;
        try {
            output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (!process.waitFor(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new ControlResult(false, "Timed out after " + CONTROL_TIMEOUT_SECONDS + "s");
            }
        } catch (IOException e) {
            return new ControlResult(false, "Failed reading output: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ControlResult(false, "Interrupted");
        }

        if (process.exitValue() != 0) {
            return new ControlResult(false, output.isBlank() ? "docker exited " + process.exitValue() : output.trim());
        }
        return new ControlResult(true, output.trim());
    }

    /** Runs a `docker ... --format {{json .}}` listing command and parses each output line as one JSON
     * object — Docker's own `{{json .}}` template emits exactly one self-contained JSON row per line,
     * never a wrapping array. Any failure (missing binary, daemon unreachable, timeout, malformed line)
     * is logged and treated as "nothing to report" — never a partial or guessed list. */
    private static List<JsonNode> runFormatJson(String... command) {
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            LOG.debug("Docker state collection '{}' could not start: {}", String.join(" ", command), e.getMessage());
            return List.of();
        }

        String output;
        try {
            output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (!process.waitFor(LIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                LOG.debug("Docker state collection '{}' timed out after {}s", String.join(" ", command), LIST_TIMEOUT_SECONDS);
                return List.of();
            }
        } catch (IOException e) {
            LOG.debug("Docker state collection '{}' failed reading output: {}", String.join(" ", command), e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }

        if (process.exitValue() != 0) {
            LOG.debug("Docker state collection '{}' exited {}", String.join(" ", command), process.exitValue());
            return List.of();
        }

        List<JsonNode> rows = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                rows.add(MAPPER.readTree(line));
            } catch (IOException e) {
                LOG.debug("Skipping unparseable docker JSON line: {}", line);
            }
        }
        return rows;
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    /** Docker's `Ports` column is one comma-separated string (e.g.
     * "0.0.0.0:8080->80/tcp, :::8080->80/tcp") when set, empty when the container publishes nothing. */
    private static List<String> splitPorts(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> ports = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                ports.add(trimmed);
            }
        }
        return ports;
    }

    /** @return real epoch millis, or 0 if Docker's CreatedAt string didn't match the expected format —
     * an honest "unknown," never a fabricated timestamp. */
    private static long parseDockerCreatedAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Instant.from(DOCKER_CREATED_AT_FORMAT.parse(raw.trim())).toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0L;
        }
    }

    /** @return real byte count parsed from Docker's human-readable size (e.g. "125MB", "1.2GB"), or 0
     * if it didn't match the expected shape — never a fabricated size. */
    private static long parseDockerSize(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        Matcher matcher = SIZE_PATTERN.matcher(raw.trim());
        if (!matcher.matches()) {
            return 0L;
        }
        double value;
        try {
            value = Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            return 0L;
        }
        long multiplier = switch (matcher.group(2).toLowerCase(java.util.Locale.ROOT)) {
            case "k", "ki" -> 1_000L;
            case "m", "mi" -> 1_000_000L;
            case "g", "gi" -> 1_000_000_000L;
            case "t", "ti" -> 1_000_000_000_000L;
            default -> 1L;
        };
        return Math.round(value * multiplier);
    }
}
