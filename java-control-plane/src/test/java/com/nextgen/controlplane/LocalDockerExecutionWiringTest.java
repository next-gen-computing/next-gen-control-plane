package com.nextgen.controlplane;

import com.nextgen.proto.ControlPlaneProto.ComposeCommand;
import com.nextgen.proto.ControlPlaneProto.ComposeEvent;
import com.nextgen.proto.ControlPlaneProto.ComposeUpRequest;
import com.nextgen.proto.LocalDockerExecutionGrpc;
import com.nextgen.security.CertificateAuthority;
import com.nextgen.security.EnrollmentTokenStore;
import com.nextgen.security.PkiPaths;
import com.nextgen.security.RateLimitPolicy;
import com.nextgen.security.SecurityPolicy;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Stage Q's own verification requirement: {@code LOCAL_DOCKER_EXEC_ENABLED=false} (the default) must
 * leave {@code LocalDockerExecution} entirely unregistered, confirmed by the RPC failing with
 * {@code UNIMPLEMENTED} — never a silent no-op. Uses a real gRPC server (TLS off, for simplicity —
 * this is about service registration, not the mTLS policy {@code MutualTlsEndToEndTest} already
 * covers), not a mock.
 */
class LocalDockerExecutionWiringTest {

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty("LOCAL_DOCKER_EXEC_ENABLED");
        if (channel != null) {
            channel.shutdownNow();
            channel.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.shutdownNow();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private int startServer(Path pkiDir) throws Exception {
        SecurityPolicy policy = new SecurityPolicy(false, false,
                List.of("localhost", "127.0.0.1"), Duration.ofMinutes(60), RateLimitPolicy.defaults());
        CertificateAuthority ca = new CertificateAuthority(new PkiPaths(pkiDir), Clock.systemUTC());
        EnrollmentTokenStore tokens = new EnrollmentTokenStore(Clock.systemUTC(), Duration.ofMinutes(60));
        NodeRegistry registry = new NodeRegistry(new ConcurrentHashMap<>(), System::currentTimeMillis);

        server = ControlPlaneServer.buildGrpcServer(0, registry, policy, ca, tokens).start();
        return server.getPort();
    }

    @Test
    void disabledByDefaultLeavesTheServiceUnregisteredAndUnimplemented(@TempDir Path pkiDir) throws Exception {
        int port = startServer(pkiDir);
        channel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
        LocalDockerExecutionGrpc.LocalDockerExecutionStub stub = LocalDockerExecutionGrpc.newStub(channel);

        LinkedBlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        StreamObserver<ComposeCommand> outbound = stub.runCompose(new StreamObserver<>() {
            @Override public void onNext(ComposeEvent value) { }
            @Override public void onError(Throwable t) { errors.add(t); }
            @Override public void onCompleted() { }
        });
        outbound.onNext(ComposeCommand.newBuilder()
                .setUp(ComposeUpRequest.newBuilder().setProjectName("p").setComposeYaml("services: {}"))
                .build());

        Throwable error = errors.poll(5, TimeUnit.SECONDS);
        assertNotNull(error, "the RPC must fail, not hang or silently succeed, when the service is unregistered");
        StatusRuntimeException sre = (StatusRuntimeException) error;
        assertEquals(Status.Code.UNIMPLEMENTED, sre.getStatus().getCode());
    }

    @Test
    void enabledButNoDockerReachableAlsoLeavesTheServiceUnregistered(@TempDir Path pkiDir) throws Exception {
        // Exercises the OTHER honest-refusal branch, distinct from the default-off case above: opting
        // in without a real reachable daemon must still not register the service. Only reproducible
        // when Docker's daemon genuinely isn't reachable right now — self-skips otherwise (e.g. a
        // machine actively running a live container demo) rather than asserting a false regression.
        org.junit.jupiter.api.Assumptions.assumeFalse(new com.nextgen.agent.DockerCapabilityDetector().detect().available(),
                "Docker daemon IS reachable on this machine right now — this branch isn't reproducible here");
        System.setProperty("LOCAL_DOCKER_EXEC_ENABLED", "true");
        int port = startServer(pkiDir);
        channel = ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
        LocalDockerExecutionGrpc.LocalDockerExecutionStub stub = LocalDockerExecutionGrpc.newStub(channel);

        LinkedBlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        StreamObserver<ComposeCommand> outbound = stub.runCompose(new StreamObserver<>() {
            @Override public void onNext(ComposeEvent value) { }
            @Override public void onError(Throwable t) { errors.add(t); }
            @Override public void onCompleted() { }
        });
        outbound.onNext(ComposeCommand.newBuilder()
                .setUp(ComposeUpRequest.newBuilder().setProjectName("p").setComposeYaml("services: {}"))
                .build());

        Throwable error = errors.poll(5, TimeUnit.SECONDS);
        assertNotNull(error);
        StatusRuntimeException sre = (StatusRuntimeException) error;
        assertEquals(Status.Code.UNIMPLEMENTED, sre.getStatus().getCode());
    }
}
