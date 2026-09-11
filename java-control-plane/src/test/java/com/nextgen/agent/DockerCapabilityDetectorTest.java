package com.nextgen.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link DockerCapabilityDetector}'s real-detection-only discipline: it must never report
 * {@code available=true} without a genuine, successful invocation of both {@code docker --version}
 * and {@code docker compose version}.
 */
class DockerCapabilityDetectorTest {

    @Test
    void aNonexistentCommandIsHonestlyReportedUnavailable() {
        DockerCapabilityDetector detector = new DockerCapabilityDetector("definitely-not-a-real-command-xyz");

        DockerCapabilityDetector.DockerCapability result = detector.detect();

        assertFalse(result.available());
        assertEquals("", result.dockerVersion());
        assertEquals("", result.composeVersion());
    }

    /** Real, not mocked — invokes this machine's actual `docker` binary. Skips (rather than failing)
     * if Docker genuinely isn't installed here, matching this project's established "self-skip and say
     * why" precedent for tests that depend on real external tooling. */
    @Test
    void aRealDockerInstallIsHonestlyDetectedWithRealVersionStrings() {
        DockerCapabilityDetector detector = new DockerCapabilityDetector();

        DockerCapabilityDetector.DockerCapability result = detector.detect();

        org.junit.jupiter.api.Assumptions.assumeTrue(result.available(),
                "Docker is not installed/available on this machine — skipping, not failing");
        assertTrue(result.dockerVersion().toLowerCase(java.util.Locale.ROOT).contains("docker"),
                result.dockerVersion());
        assertFalse(result.composeVersion().isBlank());
    }

    @Test
    void unavailableFactoryProducesEmptyNeverPlaceholderStrings() {
        var unavailable = DockerCapabilityDetector.DockerCapability.unavailable();

        assertFalse(unavailable.available());
        assertEquals("", unavailable.dockerVersion());
        assertEquals("", unavailable.composeVersion());
    }
}
