package com.nextgen.agent.task;

import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Node-side receiving half of Stage N build-context delivery — buffers {@code BuildContextChunk}
 * messages arriving on this node's {@code TaskChannel} (see {@link TaskChannelClient}) to a local temp
 * file keyed by {@code context_id}, so {@link DockerComposeServiceExecutor} can find the assembled
 * tarball once the {@code TaskDispatch} referencing it arrives.
 *
 * <p>Ordering makes this safe with no extra synchronization: the control plane always finishes sending
 * every chunk for a context before sending the {@code TaskDispatch} that references it, over the SAME
 * ordered stream (see {@code TaskDispatcher.shipBuildContext}), and {@link TaskChannelClient} handles
 * every non-dispatch command synchronously on the gRPC callback thread — so by the time a dispatch is
 * even submitted for execution, every chunk that precedes it on the wire has already been written here.
 */
public final class NodeBuildContextStore {
    private static final Logger LOG = LoggerFactory.getLogger(NodeBuildContextStore.class);

    private final Path storageDir;
    private final ConcurrentHashMap<String, OutputStream> inProgress = new ConcurrentHashMap<>();

    public NodeBuildContextStore() {
        this(Path.of(System.getProperty("java.io.tmpdir", "."), "nextgen-build-contexts"));
    }

    public NodeBuildContextStore(Path storageDir) {
        this.storageDir = storageDir;
    }

    public Path pathFor(String contextId) {
        return storageDir.resolve(contextId + ".tar.gz");
    }

    /** Appends one chunk's bytes, opening the backing file on the first chunk for this context id and
     * closing it once {@code last} is true. Never throws on a bookkeeping-only problem (e.g. a
     * duplicate {@code last} chunk) — only a real I/O failure propagates, since that means the
     * assembled file cannot be trusted at all. */
    public void appendChunk(String contextId, ByteString data, boolean last) throws IOException {
        OutputStream out;
        try {
            out = inProgress.computeIfAbsent(contextId, id -> {
                try {
                    Files.createDirectories(storageDir);
                    return Files.newOutputStream(pathFor(id), StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                } catch (IOException e) {
                    throw new UncheckedIOExceptionForChunk(e);
                }
            });
        } catch (UncheckedIOExceptionForChunk wrapped) {
            throw wrapped.cause;
        }
        data.writeTo(out);
        if (last) {
            inProgress.remove(contextId);
            out.close();
            LOG.debug("📦 Build context '{}' fully received", contextId);
        }
    }

    /** Wraps an {@link IOException} raised inside {@code computeIfAbsent}'s lambda (which cannot declare
     * checked exceptions) so {@link #appendChunk} can unwrap and rethrow it as the checked exception its
     * own signature promises. */
    private static final class UncheckedIOExceptionForChunk extends RuntimeException {
        private final IOException cause;

        UncheckedIOExceptionForChunk(IOException cause) {
            super(cause);
            this.cause = cause;
        }
    }
}
