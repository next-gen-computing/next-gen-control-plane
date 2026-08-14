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
