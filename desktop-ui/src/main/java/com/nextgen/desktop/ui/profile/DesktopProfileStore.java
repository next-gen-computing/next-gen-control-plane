package com.nextgen.desktop.ui.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextgen.controlplane.EnvConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Persists the device's remembered onboarding choice to a small local JSON file — the same
 * "one plain file under {@code ~/.nextgen}" idiom {@code AgentCredentials} already uses for node
 * certificates, just for onboarding state instead of PKI material. Never throws: a missing or
 * corrupt file just means "no remembered setup," the same as a first-ever launch.
 */
public class DesktopProfileStore {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopProfileStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;

    public DesktopProfileStore() {
        this(defaultDataDir());
    }

    DesktopProfileStore(Path dataDir) {
        this.file = dataDir.resolve("profile.json");
    }

    private static Path defaultDataDir() {
        return Paths.get(EnvConfig.stringValue("NEXTGEN_DESKTOP_DATA_DIR",
                Paths.get(System.getProperty("user.home", "."), ".nextgen", "desktop").toString()));
    }

    public Optional<DesktopProfile> load() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(MAPPER.readValue(file.toFile(), DesktopProfile.class));
        } catch (IOException e) {
            LOG.warn("Could not read saved device setup at {} — treating as first launch: {}",
                    file, e.getMessage());
            return Optional.empty();
        }
    }

    public void save(DesktopProfile profile) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(profile));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            LOG.info("Saved device setup (role={}) to {}", profile.role(), file);
        } catch (IOException e) {
            // Best-effort: a failed save just means the next launch asks again — never crash onboarding
            // success over a local-disk write problem.
            LOG.warn("Could not save device setup to {}: {}", file, e.getMessage());
        }
    }

    public void clear() {
        try {
            Files.deleteIfExists(file);
            LOG.info("Cleared saved device setup at {}", file);
        } catch (IOException e) {
            LOG.warn("Could not clear saved device setup at {}: {}", file, e.getMessage());
        }
    }
}
