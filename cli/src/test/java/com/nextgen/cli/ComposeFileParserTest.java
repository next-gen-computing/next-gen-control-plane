package com.nextgen.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextgen.cli.ComposeFileParser.ParsedService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real YAML parsing throughout (SnakeYAML, no mocking) — covers the documented subset this class
 * supports: {@code image}, {@code build.context}/{@code dockerfile}, {@code command}, both
 * {@code environment} forms (map and list), {@code ports}, and {@code depends_on} → {@code peers}.
 */
class ComposeFileParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Path writeCompose(Path dir, String yaml) throws Exception {
        Path file = dir.resolve("docker-compose.yml");
        Files.writeString(file, yaml);
        return file;
    }

    @Test
    void parsesAnImageOnlyService(@TempDir Path dir) throws Exception {
        Path file = writeCompose(dir, """
                services:
                  web:
                    image: myapp/web:latest
                    command: ["node", "server.js"]
                    ports:
                      - "8080:8080"
                    environment:
                      PORT: "8080"
                """);

        List<ParsedService> services = ComposeFileParser.parse(file);

        assertEquals(1, services.size());
        ParsedService web = services.get(0);
        assertEquals("web", web.name());
        assertEquals("myapp/web:latest", web.image());
        assertFalse(web.needsBuild());
        assertEquals("node server.js", web.command());
        assertEquals(List.of("8080:8080"), web.ports());
        assertEquals("8080", web.environment().get("PORT"));
    }

    @Test
    void parsesABuildContextServiceWithACustomDockerfile(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("web"));
        Path file = writeCompose(dir, """
                services:
                  web:
                    build:
                      context: ./web
                      dockerfile: Dockerfile.prod
                """);

        List<ParsedService> services = ComposeFileParser.parse(file);

        ParsedService web = services.get(0);
        assertTrue(web.needsBuild());
        assertEquals("Dockerfile.prod", web.dockerfile());
        assertTrue(web.buildContext().endsWith("web"), web.buildContext());
    }

    @Test
    void parsesShorthandBuildAsAPlainContextString(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("app"));
        Path file = writeCompose(dir, """
                services:
                  app:
                    build: ./app
                """);

        ParsedService app = ComposeFileParser.parse(file).get(0);
        assertTrue(app.needsBuild());
        assertEquals("Dockerfile", app.dockerfile());
    }

    @Test
    void environmentAsAListOfKeyEqualsValueIsParsedTheSameAsAMap(@TempDir Path dir) throws Exception {
        Path file = writeCompose(dir, """
                services:
                  web:
                    image: busybox
                    environment:
                      - "FOO=bar"
                      - "BAZ=qux"
                """);

        Map<String, String> env = ComposeFileParser.parse(file).get(0).environment();
        assertEquals("bar", env.get("FOO"));
        assertEquals("qux", env.get("BAZ"));
    }

    @Test
    void dependsOnBecomesTheServicesPeersListInTheJobPayload(@TempDir Path dir) throws Exception {
        Path file = writeCompose(dir, """
                services:
                  web:
                    image: busybox
                    depends_on:
                      - database
                  database:
                    image: postgres
                """);

        List<ParsedService> services = ComposeFileParser.parse(file);
        String payload = ComposeFileParser.buildJobPayload("proj", services, Map.of(), Map.of());

        JsonNode root = MAPPER.readTree(payload);
        JsonNode webSpec = findService(root, "web");
        assertTrue(webSpec.has("peers"));
        assertEquals("database", webSpec.get("peers").get(0).get("service_name").asText());
        assertEquals("DATABASE", webSpec.get("peers").get(0).get("env_prefix").asText());
    }

    @Test
    void aServiceWithNeitherImageNorBuildIsRejected(@TempDir Path dir) throws Exception {
        Path file = writeCompose(dir, """
                services:
                  broken: {}
                """);

        assertThrows(IllegalArgumentException.class, () -> ComposeFileParser.parse(file));
    }

    @Test
    void parsesDeployResourcesLimitsAndReservations(@TempDir Path dir) throws Exception {
        Path file = writeCompose(dir, """
                services:
                  web:
                    image: busybox
                    deploy:
                      resources:
                        limits:
                          cpus: "1.5"
                          memory: "512m"
                        reservations:
                          memory: "256m"
                """);

        ParsedService web = ComposeFileParser.parse(file).get(0);

        assertFalse(web.resources().isEmpty());
        assertEquals("1.5", web.resources().cpuLimit());
        assertEquals("512m", web.resources().memoryLimit());
        assertEquals("256m", web.resources().memoryReservation());
    }

    @Test
    void aServiceWithNoDeployBlockHasEmptyResources(@TempDir Path dir) throws Exception {
        Path file = writeCompose(dir, """
                services:
                  web:
                    image: busybox
                """);

        ParsedService web = ComposeFileParser.parse(file).get(0);

        assertTrue(web.resources().isEmpty());
    }

    @Test
    void resourcesAppearInTheJobPayloadOnlyWhenDeclared(@TempDir Path dir) throws Exception {
        Path file = writeCompose(dir, """
                services:
                  web:
                    image: busybox
                    deploy:
                      resources:
                        limits:
                          cpus: "0.5"
                  plain:
                    image: busybox
                """);
        List<ParsedService> services = ComposeFileParser.parse(file);

        String payload = ComposeFileParser.buildJobPayload("proj", services, Map.of(), Map.of());

        JsonNode webSpec = findService(MAPPER.readTree(payload), "web");
        assertEquals("0.5", webSpec.get("resources").get("cpuLimit").asText());
        assertFalse(webSpec.get("resources").has("memoryLimit"));

        JsonNode plainSpec = findService(MAPPER.readTree(payload), "plain");
        assertFalse(plainSpec.has("resources"));
    }

    @Test
    void parsesPlainRestartPolicy(@TempDir Path dir) throws Exception {
        Path file = writeCompose(dir, """
                services:
                  web:
                    image: busybox
                    restart: on-failure
                """);

        ParsedService web = ComposeFileParser.parse(file).get(0);

        assertTrue(web.restart().restarts());
        assertEquals("on-failure", web.restart().policy());
        assertNull(web.restart().maxAttempts());
    }

    @Test
    void parsesOnFailureShorthandWithAMaxAttemptsCount(@TempDir Path dir) throws Exception {
        Path file = writeCompose(dir, """
                services:
                  web:
                    image: busybox
                    restart: "on-failure:3"
                """);

        ParsedService web = ComposeFileParser.parse(file).get(0);

        assertEquals("on-failure", web.restart().policy());
        assertEquals(3, web.restart().maxAttempts());
    }

    @Test
    void parsesDeployRestartPolicyMaxAttemptsAsAFallback(@TempDir Path dir) throws Exception {
        Path file = writeCompose(dir, """
                services:
                  web:
                    image: busybox
                    restart: always
                    deploy:
                      restart_policy:
                        max_attempts: 7
                """);

        ParsedService web = ComposeFileParser.parse(file).get(0);

        assertEquals("always", web.restart().policy());
        assertEquals(7, web.restart().maxAttempts());
    }

    @Test
    void aServiceWithNoRestartKeyDefaultsToNoAndIsOmittedFromThePayload(@TempDir Path dir) throws Exception {
        Path file = writeCompose(dir, """
                services:
                  web:
                    image: busybox
                """);
        List<ParsedService> services = ComposeFileParser.parse(file);

        ParsedService web = services.get(0);
        assertFalse(web.restart().restarts());

        String payload = ComposeFileParser.buildJobPayload("proj", services, Map.of(), Map.of());
        assertFalse(findService(MAPPER.readTree(payload), "web").has("restart"));
    }

    @Test
    void parsesAShellFormHealthcheck(@TempDir Path dir) throws Exception {
        Path file = writeCompose(dir, """
                services:
                  web:
                    image: busybox
                    healthcheck:
                      test: "curl -f http://localhost/ || exit 1"
                      interval: 10s
                      timeout: 5s
                      retries: 3
                      start_period: 1m30s
                """);

        ParsedService web = ComposeFileParser.parse(file).get(0);

        assertFalse(web.healthCheck().isEmpty());
        assertEquals("curl -f http://localhost/ || exit 1", web.healthCheck().command());
        assertEquals(10, web.healthCheck().intervalSeconds());
        assertEquals(5, web.healthCheck().timeoutSeconds());
        assertEquals(3, web.healthCheck().retries());
        assertEquals(90, web.healthCheck().startPeriodSeconds());
    }

    @Test
    void parsesACmdShellFormHealthcheckList(@TempDir Path dir) throws Exception {
        Path file = writeCompose(dir, """
                services:
                  web:
                    image: busybox
                    healthcheck:
                      test: ["CMD-SHELL", "curl -f http://localhost/ || exit 1"]
                """);

        ParsedService web = ComposeFileParser.parse(file).get(0);
        assertEquals("curl -f http://localhost/ || exit 1", web.healthCheck().command());
    }

    @Test
    void parsesACmdFormHealthcheckListByJoiningTheArgv(@TempDir Path dir) throws Exception {
        Path file = writeCompose(dir, """
                services:
                  web:
                    image: busybox
                    healthcheck:
                      test: ["CMD", "curl", "-f", "http://localhost/"]
                """);

        ParsedService web = ComposeFileParser.parse(file).get(0);
        assertEquals("curl -f http://localhost/", web.healthCheck().command());
    }

    @Test
    void aNoneHealthcheckParsesToEmptyJustLikeNoHealthcheckAtAll(@TempDir Path dir) throws Exception {
        Path file = writeCompose(dir, """
                services:
                  web:
                    image: busybox
                    healthcheck:
                      test: ["NONE"]
                """);

        ParsedService web = ComposeFileParser.parse(file).get(0);
        assertTrue(web.healthCheck().isEmpty());
    }

    @Test
    void aServiceWithNoHealthcheckKeyIsOmittedFromThePayload(@TempDir Path dir) throws Exception {
        Path file = writeCompose(dir, """
                services:
                  web:
                    image: busybox
                """);
        List<ParsedService> services = ComposeFileParser.parse(file);

        String payload = ComposeFileParser.buildJobPayload("proj", services, Map.of(), Map.of());
        assertFalse(findService(MAPPER.readTree(payload), "web").has("healthCheck"));
    }

    @Test
    void parsesAServicesSecretsListIntoThePayload(@TempDir Path dir) throws Exception {
        Path file = writeCompose(dir, """
                services:
                  web:
                    image: busybox
                    secrets:
                      - db-password
                      - api-key
                """);
        List<ParsedService> services = ComposeFileParser.parse(file);

        ParsedService web = services.get(0);
        assertEquals(List.of("db-password", "api-key"), web.secrets());

        String payload = ComposeFileParser.buildJobPayload("proj", services, Map.of(), Map.of());
        JsonNode webSpec = findService(MAPPER.readTree(payload), "web");
        assertEquals("db-password", webSpec.get("secrets").get(0).asText());
        assertEquals("api-key", webSpec.get("secrets").get(1).asText());
    }

    @Test
    void aServiceWithNoSecretsKeyIsOmittedFromThePayload(@TempDir Path dir) throws Exception {
        Path file = writeCompose(dir, """
                services:
                  web:
                    image: busybox
                """);
        List<ParsedService> services = ComposeFileParser.parse(file);

        assertTrue(services.get(0).secrets().isEmpty());
        String payload = ComposeFileParser.buildJobPayload("proj", services, Map.of(), Map.of());
        assertFalse(findService(MAPPER.readTree(payload), "web").has("secrets"));
    }

    @Test
    void parsesBareReplicasShorthand(@TempDir Path dir) throws Exception {
        Path file = writeCompose(dir, """
                services:
                  web:
                    image: busybox
                    replicas: 3
                """);

        ParsedService web = ComposeFileParser.parse(file).get(0);

        assertEquals(3, web.replicas());
        String payload = ComposeFileParser.buildJobPayload("proj", List.of(web), Map.of(), Map.of());
        assertEquals(3, findService(MAPPER.readTree(payload), "web").get("replicas").asInt());
    }

    @Test
    void parsesDeployReplicas(@TempDir Path dir) throws Exception {
        Path file = writeCompose(dir, """
                services:
                  web:
                    image: busybox
                    deploy:
                      replicas: 5
                """);

        ParsedService web = ComposeFileParser.parse(file).get(0);
        assertEquals(5, web.replicas());
    }

    @Test
    void aServiceWithNoReplicasKeyDefaultsToOneAndIsOmittedFromThePayload(@TempDir Path dir) throws Exception {
        Path file = writeCompose(dir, """
                services:
                  web:
                    image: busybox
                """);
        List<ParsedService> services = ComposeFileParser.parse(file);

        assertEquals(1, services.get(0).replicas());
        String payload = ComposeFileParser.buildJobPayload("proj", services, Map.of(), Map.of());
        assertFalse(findService(MAPPER.readTree(payload), "web").has("replicas"));
    }

    @Test
    void buildJobPayloadOmitsImageFieldForAServiceThatNeedsBuilding(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("web"));
        Path file = writeCompose(dir, """
                services:
                  web:
                    build: ./web
                """);
        List<ParsedService> services = ComposeFileParser.parse(file);

        String payload = ComposeFileParser.buildJobPayload("proj", services,
                Map.of("web", "ctx-123"), Map.of("web", "abc123"));

        JsonNode webSpec = findService(MAPPER.readTree(payload), "web");
        assertFalse(webSpec.has("image"));
        assertEquals("ctx-123", webSpec.get("build").get("context_id").asText());
        assertEquals("abc123", webSpec.get("build").get("sha256").asText());
    }

    private static JsonNode findService(JsonNode root, String name) {
        for (JsonNode service : root.get("services")) {
            if (service.get("service_name").asText().equals(name)) {
                return service;
            }
        }
        throw new AssertionError("service '" + name + "' not found in payload: " + root);
    }
}
