package com.nextgen.desktop.ui.profile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextgen.controlplane.EnvConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists a rolling history of submitted tasks/jobs, keyed by (kind, id), so a task's record can be
 * upserted first at submission (PENDING) and again on completion — the same entry, not two. Backed by
 * one plain JSON file, matching {@link DesktopProfileStore}'s idiom; rewritten in full on every change,
 * which is fine at this volume (bounded to {@link #MAX_ENTRIES}) and keeps the file trivially
 * inspectable by hand.
 */
public class DesktopHistoryStore {
    private static final Logger LOG = LoggerFactory.getLogger(DesktopHistoryStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final int MAX_ENTRIES = 300;

    private final Path file;
    private final Map<String, HistoryEntry> entries = new LinkedHashMap<>();

    public DesktopHistoryStore() {
        this(defaultDataDir());
    }

    DesktopHistoryStore(Path dataDir) {
        this.file = dataDir.resolve("history.json");
        load();
    }

    private static Path defaultDataDir() {
        return Paths.get(EnvConfig.stringValue("NEXTGEN_DESKTOP_DATA_DIR",
                Paths.get(System.getProperty("user.home", "."), ".nextgen", "desktop").toString()));
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<HistoryEntry> loaded = MAPPER.readValue(file.toFile(), new TypeReference<List<HistoryEntry>>() {
            });
            for (HistoryEntry entry : loaded) {
                entries.put(entry.key(), entry);
            }
            LOG.info("Loaded {} history entries from {}", entries.size(), file);
        } catch (IOException e) {
            LOG.warn("Could not read task/job history at {} — starting with empty history: {}",
                    file, e.getMessage());
        }
    }

    public synchronized void upsert(HistoryEntry entry) {
        entries.put(entry.key(), entry);
        if (entries.size() > MAX_ENTRIES) {
            entries.values().stream()
                    .min(Comparator.comparingLong(HistoryEntry::submittedAtEpochMillis))
                    .ifPresent(oldest -> entries.remove(oldest.key()));
        }
        persist();
    }

    /** Newest submission first. */
    public synchronized List<HistoryEntry> list() {
        List<HistoryEntry> result = new ArrayList<>(entries.values());
        result.sort(Comparator.comparingLong(HistoryEntry::submittedAtEpochMillis).reversed());
        return result;
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(list()));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Best-effort — a failed write just means this one entry doesn't survive a restart.
            LOG.warn("Could not persist task/job history to {}: {}", file, e.getMessage());
        }
    }
}
