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
                                List<String> dependsOn) {
        boolean needsBuild() {
            return (image == null || image.isBlank()) && buildContext != null;
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

        return new ParsedService(name, image, buildContext, dockerfile, command, environment, ports, dependsOn);
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
            servicesArray.add(node);
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("project_name", projectName);
        root.set("services", servicesArray);
        return root.toString();
    }
}
