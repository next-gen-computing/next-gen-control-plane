package com.nextgen.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a documented SUBSET of the docker-compose.yml schema — {@code image}, {@code build.context}/
 * {@code dockerfile}, {@code command}, {@code environment}, {@code ports}, {@code depends_on} — into
 * the JSON shape {@code JobCoordinator.submitDockerComposeJob} expects. Not a general compose-spec
 * implementation: {@code volumes:}/{@code networks:} beyond a node-local named volume are out of scope
 * for the same file-distribution reasons that motivate build-context shipping (Stage N) — see the
 * project plan's "Explicitly out of scope" section for Stage R.
 *
 * <p>{@code depends_on} becomes each dependent service's {@code peers} list (Stage O/P's env-injection
 * input) — the CLI resolves the compose file's own service graph locally; the control plane never
 * parses YAML itself.
 */
public final class ComposeFileParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record ParsedService(String name, String image, String buildContext, String dockerfile,
                                String command, Map<String, String> environment, List<String> ports,
                                List<String> dependsOn, ResourceLimits resources, RestartPolicy restart,
                                HealthCheck healthCheck, List<String> secrets, int replicas) {
        boolean needsBuild() {
            return (image == null || image.isBlank()) && buildContext != null;
        }
    }

    /** Compose's own {@code healthcheck:} block, translated to a single shell command since that's all
     * {@code docker run --health-cmd} itself accepts (the exec ({@code CMD}) form's argv array is joined
     * with spaces rather than preserved exactly — a documented simplification, not a silent one).
     * {@code test: ["NONE", ...]} disables the health check and parses to {@link #NONE} exactly like no
     * {@code healthcheck:} block at all. Durations ({@code interval}/{@code timeout}/{@code start_period})
     * are Compose duration strings (e.g. {@code "10s"}, {@code "1m30s"}) reduced to whole seconds. */
    public record HealthCheck(String command, Integer intervalSeconds, Integer timeoutSeconds, Integer retries,
                              Integer startPeriodSeconds) {
        static final HealthCheck NONE = new HealthCheck(null, null, null, null, null);

        boolean isEmpty() {
            return command == null || command.isBlank();
        }
    }

    /** Compose's own {@code restart:} key ({@code "no"|"always"|"on-failure"|"unless-stopped"}, with the
     * {@code "on-failure:N"} shorthand for a max-attempts cap) plus the {@code deploy.restart_policy.
     * max_attempts} alternative — either form sets {@code maxAttempts}, the shorthand taking precedence
     * if both are present. {@code maxAttempts} is {@code null} when unspecified — the executor applies
     * its own default cap in that case, not this class. */
    public record RestartPolicy(String policy, Integer maxAttempts) {
        static final RestartPolicy NONE = new RestartPolicy("no", null);

        boolean restarts() {
            return policy != null && !"no".equalsIgnoreCase(policy);
        }
    }

    /** {@code deploy.resources.limits.cpus}/{@code .memory} and {@code .reservations.memory} — the
     * subset {@code docker run} itself directly supports ({@code --cpus}/{@code --memory}/
     * {@code --memory-reservation}). CPU reservation has no honest single-host {@code docker run}
     * equivalent (it's a Swarm-only concept), so it's deliberately not parsed rather than silently
     * dropped somewhere less visible. Values are passed through as the raw strings Compose/{@code docker
     * run} already both accept (e.g. {@code "512m"}, {@code "1.5"}) — no unit conversion needed. */
    public record ResourceLimits(String cpuLimit, String memoryLimit, String memoryReservation) {
        static final ResourceLimits NONE = new ResourceLimits(null, null, null);

        boolean isEmpty() {
            return cpuLimit == null && memoryLimit == null && memoryReservation == null;
        }
    }

    /** @return every service declared under {@code services:}, in the file's own declaration order —
     * callers that build sub-task ids from list index depend on this order being stable. */
    public static List<ParsedService> parse(Path composeFile) throws IOException {
        String text = Files.readString(composeFile);
        Yaml yaml = new Yaml();
        Object root = yaml.load(text);
        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new IllegalArgumentException("compose file has no top-level mapping: " + composeFile);
        }
        Object servicesObj = rootMap.get("services");
        if (!(servicesObj instanceof Map<?, ?> servicesMap) || servicesMap.isEmpty()) {
            throw new IllegalArgumentException("compose file has no non-empty 'services:' section: " + composeFile);
        }

        List<ParsedService> parsed = new ArrayList<>();
        for (Map.Entry<?, ?> entry : servicesMap.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> serviceMap)) {
                throw new IllegalArgumentException("service '" + name + "' is not a mapping");
            }
            parsed.add(parseService(name, serviceMap, composeFile.toAbsolutePath().getParent()));
        }
        return parsed;
    }

    @SuppressWarnings("unchecked")
    private static ParsedService parseService(String name, Map<?, ?> serviceMap, Path baseDir) {
        String image = asStringOrNull(serviceMap.get("image"));

        String buildContext = null;
        String dockerfile = "Dockerfile";
        Object buildObj = serviceMap.get("build");
        if (buildObj instanceof String contextPath) {
            buildContext = resolveContext(baseDir, contextPath);
        } else if (buildObj instanceof Map<?, ?> buildMap) {
            String context = asStringOrNull(buildMap.get("context"));
            buildContext = resolveContext(baseDir, context != null ? context : ".");
            String df = asStringOrNull(buildMap.get("dockerfile"));
            if (df != null && !df.isBlank()) {
                dockerfile = df;
            }
        }

        String command = null;
        Object commandObj = serviceMap.get("command");
        if (commandObj instanceof String s) {
            command = s;
        } else if (commandObj instanceof List<?> list) {
            command = String.join(" ", list.stream().map(String::valueOf).toList());
        }

        Map<String, String> environment = new LinkedHashMap<>();
        Object envObj = serviceMap.get("environment");
        if (envObj instanceof Map<?, ?> envMap) {
            envMap.forEach((k, v) -> environment.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        } else if (envObj instanceof List<?> envList) {
            for (Object item : envList) {
                String entry = String.valueOf(item);
                int eq = entry.indexOf('=');
                if (eq > 0) {
                    environment.put(entry.substring(0, eq), entry.substring(eq + 1));
                }
            }
        }

        List<String> ports = new ArrayList<>();
        Object portsObj = serviceMap.get("ports");
        if (portsObj instanceof List<?> portsList) {
            portsList.forEach(p -> ports.add(String.valueOf(p)));
        }

        List<String> dependsOn = new ArrayList<>();
        Object dependsObj = serviceMap.get("depends_on");
        if (dependsObj instanceof List<?> dependsList) {
            dependsList.forEach(d -> dependsOn.add(String.valueOf(d)));
        } else if (dependsObj instanceof Map<?, ?> dependsMap) {
            dependsOn.addAll(dependsMap.keySet().stream().map(String::valueOf).toList());
        }

        if ((image == null || image.isBlank()) && buildContext == null) {
            throw new IllegalArgumentException(
                    "service '" + name + "' has neither 'image' nor 'build' — nothing to run");
        }

        ResourceLimits resources = parseResources(serviceMap);
        RestartPolicy restart = parseRestartPolicy(serviceMap);
        HealthCheck healthCheck = parseHealthCheck(serviceMap);
        List<String> secrets = new ArrayList<>();
        if (serviceMap.get("secrets") instanceof List<?> secretsList) {
            secretsList.forEach(s -> secrets.add(String.valueOf(s)));
        }
        int replicas = parseReplicas(serviceMap);

        return new ParsedService(name, image, buildContext, dockerfile, command, environment, ports, dependsOn,
                resources, restart, healthCheck, secrets, replicas);
    }

    /** Bare {@code replicas:} (a shorthand some real-world compose files use even outside a full
     * {@code deploy:} block) takes precedence if both are somehow present; either way this is never
     * less than 1 — a declared {@code replicas: 0} would mean "don't run this service at all," a
     * different feature (conditional service inclusion) this parser doesn't support, so it's floored
     * rather than silently producing zero tasks. */
    private static int parseReplicas(Map<?, ?> serviceMap) {
        Object bare = serviceMap.get("replicas");
        if (bare != null) {
            try {
                return Math.max(1, Integer.parseInt(String.valueOf(bare)));
            } catch (NumberFormatException ignored) {
                // Fall through to the deploy.replicas form, then the default.
            }
        }
        if (serviceMap.get("deploy") instanceof Map<?, ?> deployMap && deployMap.get("replicas") != null) {
            try {
                return Math.max(1, Integer.parseInt(String.valueOf(deployMap.get("replicas"))));
            } catch (NumberFormatException ignored) {
                // Same tolerance — malformed value falls back to the default below.
            }
        }
        return 1;
    }

    private static HealthCheck parseHealthCheck(Map<?, ?> serviceMap) {
        if (!(serviceMap.get("healthcheck") instanceof Map<?, ?> hcMap)) {
            return HealthCheck.NONE;
        }
        String command = extractHealthCommand(hcMap.get("test"));
        if (command == null || command.isBlank()) {
            return HealthCheck.NONE;
        }
        Integer interval = parseDurationSeconds(asStringOrNull(hcMap.get("interval")));
        Integer timeout = parseDurationSeconds(asStringOrNull(hcMap.get("timeout")));
        Integer startPeriod = parseDurationSeconds(asStringOrNull(hcMap.get("start_period")));
        Integer retries = null;
        if (hcMap.get("retries") != null) {
            try {
                retries = Integer.parseInt(String.valueOf(hcMap.get("retries")));
            } catch (NumberFormatException ignored) {
                // Left null — the executor omits --health-retries and Docker's own default applies.
            }
        }
        return new HealthCheck(command, interval, timeout, retries, startPeriod);
    }

    /** {@code test:} as a plain string is already shell form. As a list, the first element is the form
     * marker ({@code NONE}/{@code CMD}/{@code CMD-SHELL}) per Compose's own schema; {@code CMD}'s exec
     * argv is joined with spaces since {@code --health-cmd} only accepts a single shell command — an
     * honest, documented simplification (see the {@link HealthCheck} Javadoc), not silent data loss. */
    private static String extractHealthCommand(Object testObj) {
        if (testObj instanceof String s) {
            return s;
        }
        if (testObj instanceof List<?> list && !list.isEmpty()) {
            String first = String.valueOf(list.get(0));
            if ("NONE".equalsIgnoreCase(first)) {
                return null;
            }
            if ("CMD".equalsIgnoreCase(first) || "CMD-SHELL".equalsIgnoreCase(first)) {
                return String.join(" ", list.stream().skip(1).map(String::valueOf).toList());
            }
            return String.join(" ", list.stream().map(String::valueOf).toList());
        }
        return null;
    }

    private static final Pattern DURATION_COMPONENT = Pattern.compile("(\\d+)(h|m|s)");

    /** Reduces a Compose duration string ({@code "10s"}, {@code "1m30s"}, {@code "1h"}) to whole seconds.
     * Sub-second ({@code ms}/{@code us}/{@code ns}) components are dropped — not meaningful at this
     * class's/the health-poller's second-level granularity, a documented simplification. A bare integer
     * with no unit suffix is tolerated as a plain seconds count. */
    private static Integer parseDurationSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = DURATION_COMPONENT.matcher(raw.trim());
        long totalSeconds = 0;
        boolean matchedAny = false;
        while (matcher.find()) {
            matchedAny = true;
            long value = Long.parseLong(matcher.group(1));
            totalSeconds += switch (matcher.group(2)) {
                case "h" -> value * 3600;
                case "m" -> value * 60;
                default -> value;
            };
        }
        if (!matchedAny) {
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return (int) totalSeconds;
    }

    private static RestartPolicy parseRestartPolicy(Map<?, ?> serviceMap) {
        String raw = asStringOrNull(serviceMap.get("restart"));
        String policy = "no";
        Integer maxAttempts = null;
        if (raw != null && !raw.isBlank()) {
            int colon = raw.indexOf(':');
            if (colon > 0) {
                policy = raw.substring(0, colon);
                try {
                    maxAttempts = Integer.parseInt(raw.substring(colon + 1).trim());
                } catch (NumberFormatException ignored) {
                    // Malformed "on-failure:N" shorthand — keep the plain policy name; the executor's
                    // own default attempt cap applies since maxAttempts stays null.
                }
            } else {
                policy = raw;
            }
        }
        if (maxAttempts == null && serviceMap.get("deploy") instanceof Map<?, ?> deployMap
                && deployMap.get("restart_policy") instanceof Map<?, ?> restartPolicyMap
                && restartPolicyMap.get("max_attempts") != null) {
            try {
                maxAttempts = Integer.parseInt(String.valueOf(restartPolicyMap.get("max_attempts")));
            } catch (NumberFormatException ignored) {
                // Same tolerance as the shorthand parse above.
            }
        }
        return new RestartPolicy(policy, maxAttempts);
    }

    private static ResourceLimits parseResources(Map<?, ?> serviceMap) {
        if (!(serviceMap.get("deploy") instanceof Map<?, ?> deployMap)) {
            return ResourceLimits.NONE;
        }
        if (!(deployMap.get("resources") instanceof Map<?, ?> resourcesMap)) {
            return ResourceLimits.NONE;
        }
        String cpuLimit = null;
        String memoryLimit = null;
        if (resourcesMap.get("limits") instanceof Map<?, ?> limitsMap) {
            cpuLimit = asStringOrNull(limitsMap.get("cpus"));
            memoryLimit = asStringOrNull(limitsMap.get("memory"));
        }
        String memoryReservation = null;
        if (resourcesMap.get("reservations") instanceof Map<?, ?> reservationsMap) {
            memoryReservation = asStringOrNull(reservationsMap.get("memory"));
        }
        return new ResourceLimits(cpuLimit, memoryLimit, memoryReservation);
    }

    private static String resolveContext(Path baseDir, String contextPath) {
        return baseDir.resolve(contextPath).normalize().toString();
    }

    private static String asStringOrNull(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * Builds the {@code {"project_name", "services": [...]}} payload {@code SubmitJob} expects for a
     * {@code DOCKER_COMPOSE_SERVICE} job — every already-built-from-source service's {@code build}
     * object (context_id/sha256) must already be resolved by the caller (see {@code UploadBuildContext})
     * before this runs; {@code contextIdsByService}/{@code sha256sByService} carry those results in.
     */
    public static String buildJobPayload(String projectName, List<ParsedService> services,
                                         Map<String, String> contextIdsByService,
                                         Map<String, String> sha256sByService) {
        ArrayNode servicesArray = MAPPER.createArrayNode();
        for (ParsedService service : services) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("service_name", service.name());
            if (service.needsBuild()) {
                ObjectNode build = node.putObject("build");
                build.put("context_id", contextIdsByService.getOrDefault(service.name(), ""));
                build.put("dockerfile_path", service.dockerfile());
                build.put("sha256", sha256sByService.getOrDefault(service.name(), ""));
            } else {
                node.put("image", service.image());
            }
            if (service.command() != null && !service.command().isBlank()) {
                node.put("command", service.command());
            }
            if (!service.environment().isEmpty()) {
                ObjectNode env = node.putObject("environment");
                service.environment().forEach(env::put);
            }
            if (!service.ports().isEmpty()) {
                ArrayNode ports = node.putArray("ports");
                service.ports().forEach(ports::add);
            }
            if (!service.dependsOn().isEmpty()) {
                ArrayNode peers = node.putArray("peers");
                for (String dep : service.dependsOn()) {
                    ObjectNode peer = MAPPER.createObjectNode();
                    peer.put("service_name", dep);
                    peer.put("env_prefix", dep.toUpperCase(Locale.ROOT).replace('-', '_'));
                    peers.add(peer);
                }
            }
            if (!service.resources().isEmpty()) {
                ObjectNode resources = node.putObject("resources");
                if (service.resources().cpuLimit() != null) {
                    resources.put("cpuLimit", service.resources().cpuLimit());
                }
                if (service.resources().memoryLimit() != null) {
                    resources.put("memoryLimit", service.resources().memoryLimit());
                }
                if (service.resources().memoryReservation() != null) {
                    resources.put("memoryReservation", service.resources().memoryReservation());
                }
            }
            if (service.restart().restarts()) {
                ObjectNode restart = node.putObject("restart");
                restart.put("policy", service.restart().policy());
                if (service.restart().maxAttempts() != null) {
                    restart.put("maxAttempts", service.restart().maxAttempts());
                }
            }
            if (!service.healthCheck().isEmpty()) {
                ObjectNode healthCheck = node.putObject("healthCheck");
                healthCheck.put("command", service.healthCheck().command());
                if (service.healthCheck().intervalSeconds() != null) {
                    healthCheck.put("intervalSeconds", service.healthCheck().intervalSeconds());
                }
                if (service.healthCheck().timeoutSeconds() != null) {
                    healthCheck.put("timeoutSeconds", service.healthCheck().timeoutSeconds());
                }
                if (service.healthCheck().retries() != null) {
                    healthCheck.put("retries", service.healthCheck().retries());
                }
                if (service.healthCheck().startPeriodSeconds() != null) {
                    healthCheck.put("startPeriodSeconds", service.healthCheck().startPeriodSeconds());
                }
            }
            if (!service.secrets().isEmpty()) {
                ArrayNode secrets = node.putArray("secrets");
                service.secrets().forEach(secrets::add);
            }
            if (service.replicas() > 1) {
                node.put("replicas", service.replicas());
            }
            servicesArray.add(node);
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("project_name", projectName);
        root.set("services", servicesArray);
        return root.toString();
    }
}
