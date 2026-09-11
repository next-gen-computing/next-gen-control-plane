package com.nextgen.agent.task;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Node-side receiving half of Stage NN secret delivery — buffers {@code SecretMaterial} messages
 * arriving on this node's {@code TaskChannel} (see {@link TaskChannelClient}) in memory, keyed by
 * name, until {@link DockerComposeServiceExecutor} consumes them building its {@code docker run}
 * arguments. Never written to disk by this class — {@link DockerComposeServiceExecutor} is the one
 * that writes a short-lived, owner-only-permission temp file for the actual bind mount, and deletes it
 * once the container reaches a terminal state.
 *
 * <p>Same ordering guarantee as {@link NodeBuildContextStore}: the control plane always finishes
 * sending every secret a dispatch references before sending the {@code TaskDispatch} itself, over the
 * same ordered stream (see {@code TaskDispatcher.shipSecrets}), and {@link TaskChannelClient} handles
 * every non-dispatch command synchronously on the gRPC callback thread — so every referenced secret is
 * already buffered here by the time the dispatch is even submitted for execution.
 */
public final class NodeSecretStore {
    private final Map<String, byte[]> secrets = new ConcurrentHashMap<>();

    public void put(String name, byte[] value) {
        secrets.put(name, value);
    }

    /** Get-and-remove — a secret is consumed exactly once per dispatch that references it; the control
     * plane re-ships it fresh before every dispatch that needs it (see {@code TaskDispatcher}), so
     * nothing is lost by not caching past first use, and plaintext doesn't linger in memory longer than
     * necessary. */
    public Optional<byte[]> consume(String name) {
        return Optional.ofNullable(secrets.remove(name));
    }
}
