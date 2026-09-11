package com.nextgen.controlplane.docker;

import com.nextgen.proto.ControlPlaneProto;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NodeDockerCommandRegistryTest {

    private static StreamObserver<ControlPlaneProto.ServerDockerCommand> noopObserver() {
        return new StreamObserver<>() {
            @Override public void onNext(ControlPlaneProto.ServerDockerCommand value) { }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        };
    }

    @Test
    void getIsEmptyForANodeThatNeverRegistered() {
        NodeDockerCommandRegistry registry = new NodeDockerCommandRegistry();
        assertTrue(registry.get("ghost").isEmpty());
    }

    @Test
    void registerMakesTheChannelFindable() {
        NodeDockerCommandRegistry registry = new NodeDockerCommandRegistry();
        var observer = noopObserver();

        registry.register("node1", observer);

        assertSame(observer, registry.get("node1").orElseThrow());
    }

    @Test
    void unregisterOnlyRemovesIfStillTheCurrentlyRegisteredObserver() {
        NodeDockerCommandRegistry registry = new NodeDockerCommandRegistry();
        var oldObserver = noopObserver();
        var newObserver = noopObserver();

        registry.register("node1", oldObserver);
        registry.register("node1", newObserver); // a reconnect replaces it

        // A slow-closing old stream must not unregister the newer reconnect's channel.
        registry.unregister("node1", oldObserver);
        assertTrue(registry.get("node1").isPresent());
        assertSame(newObserver, registry.get("node1").orElseThrow());

        registry.unregister("node1", newObserver);
        assertTrue(registry.get("node1").isEmpty());
    }
}
