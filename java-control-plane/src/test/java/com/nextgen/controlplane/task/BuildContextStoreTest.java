package com.nextgen.controlplane.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No Docker needed — pure file I/O and hashing, the same real-not-mocked discipline as every other
 * store in this project, just without an external process dependency.
 */
class BuildContextStoreTest {

    @Test
    void anUploadIsAssembledAndHashedCorrectly(@TempDir Path tempDir) throws Exception {
        BuildContextStore store = new BuildContextStore(tempDir, 60_000, 60_000);
        byte[] part1 = "hello ".getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "world".getBytes(StandardCharsets.UTF_8);

        BuildContextStore.Upload upload = store.beginUpload();
        upload.write(part1);
        upload.write(part2);
        BuildContextStore.StoredContext stored = upload.finish();

        assertEquals(part1.length + part2.length, stored.sizeBytes());
        assertEquals(sha256Hex("hello world"), stored.sha256Hex());
        assertTrue(Files.exists(stored.path()));
        assertEquals("hello world", Files.readString(stored.path()));

        var found = store.get(stored.contextId());
        assertTrue(found.isPresent());
        assertEquals(stored, found.get());
    }

    @Test
    void anUnknownContextIdIsHonestlyAbsent(@TempDir Path tempDir) {
        BuildContextStore store = new BuildContextStore(tempDir, 60_000, 60_000);

        assertTrue(store.get("does-not-exist").isEmpty());
    }

    @Test
    void finishCannotBeCalledTwice(@TempDir Path tempDir) throws Exception {
        BuildContextStore store = new BuildContextStore(tempDir, 60_000, 60_000);
        BuildContextStore.Upload upload = store.beginUpload();
        upload.write("data".getBytes(StandardCharsets.UTF_8));
        upload.finish();

        assertThrows(IllegalStateException.class, upload::finish);
    }

    @Test
    void abortRemovesThePartialFileWithoutRecordingIt(@TempDir Path tempDir) throws Exception {
        BuildContextStore store = new BuildContextStore(tempDir, 60_000, 60_000);
        BuildContextStore.Upload upload = store.beginUpload();
        upload.write("partial".getBytes(StandardCharsets.UTF_8));
        String contextId = upload.contextId();

        upload.abort();

        assertTrue(store.get(contextId).isEmpty());
        try (var files = Files.list(tempDir.resolve("build-contexts"))) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().contains(contextId)),
                    "aborted upload left a file behind");
        }
    }

    @Test
    void sweepExpiredEvictsOnlyContextsOlderThanTheTtl(@TempDir Path tempDir) throws Exception {
        AtomicLong now = new AtomicLong(0);
        BuildContextStore store = new BuildContextStore(tempDir, 1_000, 60_000, now::get);

        BuildContextStore.Upload oldUpload = store.beginUpload();
        oldUpload.write("old".getBytes(StandardCharsets.UTF_8));
        BuildContextStore.StoredContext old = oldUpload.finish();

        now.set(2_000);
        BuildContextStore.Upload freshUpload = store.beginUpload();
        freshUpload.write("fresh".getBytes(StandardCharsets.UTF_8));
        BuildContextStore.StoredContext fresh = freshUpload.finish();

        now.set(2_500); // old is 2500ms stale (>1000ms TTL); fresh is 500ms stale (<1000ms TTL)
        store.sweepExpired();

        assertTrue(store.get(old.contextId()).isEmpty(), "expired context should have been evicted");
        assertFalse(Files.exists(old.path()), "expired context's file should have been deleted");
        assertTrue(store.get(fresh.contextId()).isPresent(), "non-expired context should survive the sweep");
        assertTrue(Files.exists(fresh.path()));
    }

    private static String sha256Hex(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
