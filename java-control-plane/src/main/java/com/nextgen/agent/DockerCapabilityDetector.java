package com.nextgen.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Detects whether this node actually has a working Docker installation, by really invoking
 * {@code docker --version} / {@code docker compose version} — never assumed, matching every other
 * capability signal this project reports (see {@link SystemMetricsReader}/{@link PowerMetricsReader}).
 * A node that fails detection remains a fully ordinary, non-Docker worker for every other task kind;
 * it is never treated as Docker-capable on a guess.
 */
public class DockerCapabilityDetector {
    private static final Logger LOG = LoggerFactory.getLogger(DockerCapabilityDetector.class);

    private static final long DETECT_TIMEOUT_SECONDS = 5;

    private final String dockerCommand;

    public DockerCapabilityDetector() {
        this("docker");
    }

    /** @param dockerCommand the executable name/path to invoke — overridable so a test can point this
     * at a deliberately nonexistent command to exercise the unavailable path deterministically,
     * regardless of whether Docker actually happens to be installed on the machine running the test. */
    public DockerCapabilityDetector(String dockerCommand) {
        this.dockerCommand = dockerCommand;
    }

    /** Real detection result. {@code available=false} means both version strings are meaningless and
     * must be reported as empty, never a placeholder. */
    public record DockerCapability(boolean available, String dockerVersion, String composeVersion) {
        public static DockerCapability unavailable() {
            return new DockerCapability(false, "", "");
        }
    }

    /** Runs the real checks. Safe to call at agent startup — bounded by {@link #DETECT_TIMEOUT_SECONDS}
     * per command, so a hung/missing binary cannot stall registration. */
    public DockerCapability detect() {
        String dockerVersion = runVersionCommand(dockerCommand, "--version");
        if (dockerVersion == null) {
            return DockerCapability.unavailable();
        }
        if (!daemonReachable()) {
            // `docker --version`/`docker compose version` are pure client-side checks — both succeed
            // even when the daemon itself isn't running (confirmed empirically: `docker info` fails to
            // connect to the daemon on a machine where both of the above still print a version). A node
            // in that state cannot actually run a container, so it must not be reported as available.
            LOG.debug("Docker CLI '{}' found but the daemon is not reachable — reporting unavailable", dockerVersion);
            return DockerCapability.unavailable();
        }
        String composeVersion = runVersionCommand(dockerCommand, "compose", "version");
        if (composeVersion == null) {
            // Docker itself works but Compose doesn't — still not usable for this project's
            // docker-compose-style execution, so this honestly reports unavailable rather than a
            // half-true "docker_available=true with no compose version" state nothing downstream
            // expects.
            return DockerCapability.unavailable();
        }
        return new DockerCapability(true, dockerVersion, composeVersion);
    }

    /** A real daemon round-trip — not just proof the CLI binary exists — so {@code available=true} only
     * ever means "a container could actually be run right now." */
    private boolean daemonReachable() {
        return runVersionCommand(dockerCommand, "info", "--format", "{{.ServerVersion}}") != null;
    }

    /** @return the trimmed first line of stdout on a clean exit, or {@code null} on any failure
     * (non-zero exit, timeout, missing binary) — never a guessed/plausible-looking string. */
    private static String runVersionCommand(String... command) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            // The overwhelmingly common real case: the binary isn't installed/on PATH at all.
            LOG.debug("Docker capability check '{}' could not start: {}", String.join(" ", command), e.getMessage());
            return null;
        }

        String output;
        try {
            try (var reader = process.inputReader()) {
                output = reader.readLine();
            }
            boolean finished = process.waitFor(DETECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOG.debug("Docker capability check '{}' timed out after {}s", String.join(" ", command),
                        DETECT_TIMEOUT_SECONDS);
                return null;
            }
        } catch (IOException e) {
            LOG.debug("Docker capability check '{}' failed reading output: {}", String.join(" ", command),
                    e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }

        if (process.exitValue() != 0 || output == null || output.isBlank()) {
            return null;
        }
        return output.trim();
    }
}
