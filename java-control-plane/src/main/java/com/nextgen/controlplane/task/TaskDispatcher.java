package com.nextgen.controlplane.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.nextgen.controlplane.ControlPlaneWriter;
import com.nextgen.proto.ControlPlaneProto;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Pushes a queued task down the chosen node's open {@code TaskChannel}, or fails it honestly if that
 * node has none open — this never silently waits forever for a node that will never receive the work.
 */
public final class TaskDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(TaskDispatcher.class);

    /** Matches typical gRPC message-size comfort zones — see BuildContextStore/UploadBuildContext's
     * own Javadoc for why a build context is streamed in pieces rather than one message. */
    private static final int BUILD_CONTEXT_CHUNK_SIZE = 256 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TaskRegistry taskRegistry;
    private final NodeTaskChannelRegistry channelRegistry;
    private final LongSupplier clock;
    /** Null (every constructor below except the last two) means "mutate taskRegistry directly" —
     * today's exact behavior. Non-null routes the same mutations through {@link ControlPlaneWriter}
     * instead — used when Raft replication is enabled. See that interface's Javadoc for why this needs
     * no other structural change here: the writer either applies instantly (direct mode) or blocks
     * until a majority has durably committed it (Raft mode) before returning, and this method then
     * continues exactly the same either way. */
    private final ControlPlaneWriter writer;
    /** Null means Stage N build-context shipping is unavailable — a dispatch referencing a
     * {@code build.context_id} fails honestly rather than silently sending a {@code TaskDispatch} the
     * node can never actually build (see {@link #shipBuildContextIfNeeded}). */
    private final BuildContextStore buildContextStore;
    /** Null means Stage NN secret shipping is unavailable — a dispatch referencing a {@code secrets}
     * name fails honestly (see {@link #shipSecrets}), same discipline as {@code buildContextStore}. */
    private final SecretStore secretStore;

    public TaskDispatcher(TaskRegistry taskRegistry, NodeTaskChannelRegistry channelRegistry) {
        this(taskRegistry, channelRegistry, System::currentTimeMillis);
    }

    public TaskDispatcher(TaskRegistry taskRegistry, NodeTaskChannelRegistry channelRegistry, LongSupplier clock) {
        this(taskRegistry, channelRegistry, clock, null);
    }

    public TaskDispatcher(TaskRegistry taskRegistry, NodeTaskChannelRegistry channelRegistry,
                          LongSupplier clock, ControlPlaneWriter writer) {
        this(taskRegistry, channelRegistry, clock, writer, null);
    }

    public TaskDispatcher(TaskRegistry taskRegistry, NodeTaskChannelRegistry channelRegistry,
                          LongSupplier clock, ControlPlaneWriter writer, BuildContextStore buildContextStore) {
        this(taskRegistry, channelRegistry, clock, writer, buildContextStore, null);
    }

    public TaskDispatcher(TaskRegistry taskRegistry, NodeTaskChannelRegistry channelRegistry,
                          LongSupplier clock, ControlPlaneWriter writer, BuildContextStore buildContextStore,
                          SecretStore secretStore) {
        this.taskRegistry = taskRegistry;
        this.channelRegistry = channelRegistry;
        this.clock = clock;
        this.writer = writer;
        this.buildContextStore = buildContextStore;
        this.secretStore = secretStore;
    }

    /**
     * @return true if the dispatch was actually sent (the registry is updated to DISPATCHED and the
     *         node's channel now has the {@code TaskDispatch} in flight); false if the node has no
     *         open channel, in which case the task is marked FAILED with an honest reason rather than
     *         left QUEUED forever.
     */
    public boolean dispatch(String taskId, String nodeId) {
        var channel = channelRegistry.get(nodeId);
        if (channel.isEmpty()) {
            LOG.warn("Cannot dispatch task {} to node {}: no open task channel", taskId, nodeId);
            // A never-dispatched task still has assignedNodeId="" (see TaskRecord.queued), which is
            // exactly what markFailed's fencing check compares "" against here — no dispatch needed
            // first just to have something to fence against.
            if (writer != null) {
                writer.markTaskFailed(taskId, "", "FAILED — node has no open task channel");
            } else {
                taskRegistry.markFailed(taskId, "", "FAILED — node has no open task channel");
            }
            return false;
        }

        Optional<TaskRecord> dispatched;
        if (writer != null) {
            writer.dispatchTask(taskId, nodeId);
            dispatched = taskRegistry.get(taskId);
        } else {
            dispatched = taskRegistry.markDispatched(taskId, nodeId);
        }
        if (dispatched.isEmpty()) {
            LOG.warn("Cannot dispatch task {}: no such task in the registry", taskId);
            return false;
        }

        StreamObserver<ControlPlaneProto.ServerTaskCommand> outbound = channel.get();

        // Stage N: a spec referencing a build.context_id needs the tarball shipped down THIS same
        // channel before the TaskDispatch itself — see BuildContextStore's Javadoc for why this travels
        // operator → control plane → node rather than any direct path.
        String buildContextId = extractBuildContextId(dispatched.get().getPayloadJson());
        if (buildContextId != null && !shipBuildContext(outbound, buildContextId, taskId, nodeId)) {
            return false;
        }

        if (!shipSecrets(outbound, extractSecretNames(dispatched.get().getPayloadJson()), taskId, nodeId)) {
            return false;
        }

        ControlPlaneProto.TaskDispatch payload = ControlPlaneProto.TaskDispatch.newBuilder()
                .setTaskId(taskId)
                .setJobId(dispatched.get().getJobId())
                .setKind(dispatched.get().getKind().toProto())
                .setPayloadJson(dispatched.get().getPayloadJson())
                .setDispatchedAtEpochMillis(clock.getAsLong())
                .build();

        try {
            outbound.onNext(ControlPlaneProto.ServerTaskCommand.newBuilder().setDispatch(payload).build());
            return true;
        } catch (RuntimeException e) {
            // The channel looked open a moment ago but the write itself failed (e.g. the node
            // disconnected in the gap between the isConnected check and this call) — fail the task
            // honestly rather than leaving it stuck in DISPATCHED with nothing actually sent.
            LOG.warn("Failed to send TaskDispatch for task {} to node {}: {}", taskId, nodeId, e.getMessage());
            failDispatch(taskId, nodeId, "FAILED — task channel write failed: " + e.getMessage());
            return false;
        }
    }

    private static final long DEFAULT_CANCEL_TIMEOUT_MS = 20_000;
    private static final long CANCEL_POLL_INTERVAL_MS = 100;

    public record CancelOutcome(boolean accepted, String message) { }

    /** Stage U: a real, CONFIRMED cancel — pushes the same {@code TaskCancel} command {@code
     * ProactiveMigrator}'s best-effort push already uses, then blocks (bounded) until the task actually
     * reaches a terminal state, rather than returning as soon as the command was merely sent. Shared by
     * {@code ControlPlaneServiceImpl.cancelTask} (the client-invocable RPC {@code nx down} calls) and
     * {@code JobCoordinator.updateJob} (Stage PP's rolling update, which needs the exact same
     * "confirmed-stopped" guarantee before it's safe to dispatch a replacement replica). */
    public CancelOutcome cancelAndAwaitConfirmation(String taskId, String reason) {
        return cancelAndAwaitConfirmation(taskId, reason, DEFAULT_CANCEL_TIMEOUT_MS);
    }

    public CancelOutcome cancelAndAwaitConfirmation(String taskId, String reason, long timeoutMs) {
        Optional<TaskRecord> taskOpt = taskRegistry.get(taskId);
        if (taskOpt.isEmpty()) {
            return new CancelOutcome(false, "no such task '" + taskId + "'");
        }
        TaskRecord task = taskOpt.get();
        if (isTerminal(task.getState())) {
            return new CancelOutcome(false,
                    "task '" + taskId + "' is already " + task.getState() + " — nothing to cancel");
        }
        String nodeId = task.getAssignedNodeId();
        Optional<StreamObserver<ControlPlaneProto.ServerTaskCommand>> outbound =
                nodeId.isBlank() ? Optional.empty() : channelRegistry.get(nodeId);
        if (outbound.isEmpty()) {
            return new CancelOutcome(false, "task '" + taskId + "' has no reachable node to cancel on");
        }
        try {
            outbound.get().onNext(ControlPlaneProto.ServerTaskCommand.newBuilder()
                    .setCancel(ControlPlaneProto.TaskCancel.newBuilder()
                            .setTaskId(taskId)
                            .setReason(reason == null || reason.isBlank() ? "cancel requested" : reason)
                            .build())
                    .build());
        } catch (RuntimeException e) {
            return new CancelOutcome(false, "failed to send cancel to node: " + e.getMessage());
        }

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Optional<TaskRecord> current = taskRegistry.get(taskId);
            if (current.isEmpty() || isTerminal(current.get().getState())) {
                return new CancelOutcome(true, "task '" + taskId + "' stopped");
            }
            try {
                Thread.sleep(CANCEL_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new CancelOutcome(false, "interrupted while waiting for cancel confirmation");
            }
        }
        return new CancelOutcome(false, "cancel sent but no confirmation within " + timeoutMs + "ms");
    }

    private static boolean isTerminal(TaskStateDomain state) {
        return state == TaskStateDomain.COMPLETED || state == TaskStateDomain.FAILED;
    }

    /** @return the {@code build.context_id} from a Stage N docker-compose-service spec, or null if this
     * payload has none (either not that task kind, or an {@code image}-only spec with nothing to build
     * from source — see {@code DockerComposeServiceExecutor}'s Javadoc for the full JSON shape). Never
     * throws on malformed JSON — that is the executor's problem to fail honestly on the node side, not
     * this method's. */
    private static String extractBuildContextId(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            JsonNode spec = MAPPER.readTree(payloadJson);
            String contextId = spec.path("build").path("context_id").asText();
            return contextId.isBlank() ? null : contextId;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Streams the stored tarball for {@code contextId} down {@code outbound} as a sequence of
     * {@code BuildContextChunk} messages, immediately BEFORE the caller sends the actual
     * {@code TaskDispatch} for the same task — the node buffers these keyed by {@code context_id} and
     * only trusts the assembled result once it's verified against the SHA-256 the dispatch's own
     * payload JSON carries (see {@code DockerComposeServiceExecutor}).
     *
     * @return true if the context was fully shipped; false if it was unavailable or shipping failed, in
     *         which case the task has already been marked FAILED and the caller must not proceed to
     *         send the TaskDispatch at all.
     */
    private boolean shipBuildContext(StreamObserver<ControlPlaneProto.ServerTaskCommand> outbound,
                                     String contextId, String taskId, String nodeId) {
        if (buildContextStore == null) {
            failDispatch(taskId, nodeId, "FAILED — this control plane has no build-context store configured");
            return false;
        }
        Optional<BuildContextStore.StoredContext> stored = buildContextStore.get(contextId);
        if (stored.isEmpty()) {
            failDispatch(taskId, nodeId, "FAILED — build context '" + contextId + "' not found or expired");
            return false;
        }
        try (InputStream in = Files.newInputStream(stored.get().path())) {
            byte[] buffer = new byte[BUILD_CONTEXT_CHUNK_SIZE];
            long remaining = stored.get().sizeBytes();
            int read;
            while ((read = in.read(buffer)) != -1) {
                remaining -= read;
                ControlPlaneProto.BuildContextChunk chunk = ControlPlaneProto.BuildContextChunk.newBuilder()
                        .setContextId(contextId)
                        .setData(ByteString.copyFrom(buffer, 0, read))
                        .setLast(remaining <= 0)
                        .build();
                outbound.onNext(ControlPlaneProto.ServerTaskCommand.newBuilder()
                        .setBuildContextChunk(chunk)
                        .build());
            }
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Failed shipping build context '{}' for task {} to node {}: {}",
                    contextId, taskId, nodeId, e.getMessage());
            failDispatch(taskId, nodeId, "FAILED — failed shipping build context: " + e.getMessage());
            return false;
        }
    }

    /** @return every name in this payload's {@code "secrets"} array, or an empty list if it has none/
     * isn't that shape. Never throws on malformed JSON — same discipline as
     * {@link #extractBuildContextId}. */
    private static List<String> extractSecretNames(String payloadJson) {
        List<String> names = new ArrayList<>();
        if (payloadJson == null || payloadJson.isBlank()) {
            return names;
        }
        try {
            JsonNode secretsNode = MAPPER.readTree(payloadJson).path("secrets");
            if (secretsNode.isArray()) {
                secretsNode.forEach(n -> {
                    String name = n.asText();
                    if (!name.isBlank()) {
                        names.add(name);
                    }
                });
            }
        } catch (IOException ignored) {
            // Malformed payload JSON is the node executor's problem to fail on, not this method's.
        }
        return names;
    }

    /**
     * Decrypts and ships each referenced secret down {@code outbound} as a {@code SecretMaterial}
     * message, immediately BEFORE the caller sends the {@code TaskDispatch} that references it — the
     * node buffers these in memory, keyed by name, until the executor consumes them building its
     * {@code docker run} arguments (see {@code NodeSecretStore}). Unchunked: secrets are small, unlike a
     * build context tarball, so the {@link #shipBuildContext} chunking machinery would be needless here.
     *
     * @return true if every referenced secret was fully shipped (or none were referenced); false if a
     *         referenced secret doesn't exist server-side or shipping failed, in which case the task has
     *         already been marked FAILED and the caller must not proceed to send the TaskDispatch.
     */
    private boolean shipSecrets(StreamObserver<ControlPlaneProto.ServerTaskCommand> outbound,
                                List<String> secretNames, String taskId, String nodeId) {
        if (secretNames.isEmpty()) {
            return true;
        }
        if (secretStore == null) {
            failDispatch(taskId, nodeId, "FAILED — this control plane has no secret store configured");
            return false;
        }
        for (String name : secretNames) {
            byte[] value;
            try {
                Optional<byte[]> found = secretStore.get(name);
                if (found.isEmpty()) {
                    failDispatch(taskId, nodeId, "FAILED — secret '" + name + "' was never set (nx secret set)");
                    return false;
                }
                value = found.get();
            } catch (GeneralSecurityException | IOException e) {
                LOG.warn("Failed decrypting secret '{}' for task {} to node {}: {}",
                        name, taskId, nodeId, e.getMessage());
                failDispatch(taskId, nodeId, "FAILED — could not decrypt secret '" + name + "': " + e.getMessage());
                return false;
            }
            try {
                outbound.onNext(ControlPlaneProto.ServerTaskCommand.newBuilder()
                        .setSecretMaterial(ControlPlaneProto.SecretMaterial.newBuilder()
                                .setName(name)
                                .setValue(ByteString.copyFrom(value))
                                .build())
                        .build());
            } catch (RuntimeException e) {
                LOG.warn("Failed shipping secret '{}' for task {} to node {}: {}",
                        name, taskId, nodeId, e.getMessage());
                failDispatch(taskId, nodeId, "FAILED — failed shipping secret '" + name + "': " + e.getMessage());
                return false;
            }
        }
        return true;
    }

    private void failDispatch(String taskId, String nodeId, String reason) {
        if (writer != null) {
            writer.markTaskFailed(taskId, nodeId, reason);
        } else {
            taskRegistry.markFailed(taskId, nodeId, reason);
        }
    }
}
