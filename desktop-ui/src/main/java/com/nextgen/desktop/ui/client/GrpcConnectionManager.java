package com.nextgen.desktop.ui.client;

import com.nextgen.controlplane.ControlPlaneEndpoints;
import com.nextgen.controlplane.EnvConfig;
import com.nextgen.security.TlsConfig;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages gRPC channels to the ControlPlane and Predictor services.
 *
 * <p>The target address defaults from the environment ({@code CONTROL_PLANE_HOST} /
 * {@code CONTROL_PLANE_PORT}) so the desktop app can reach a control plane anywhere, and can be
 * overridden at runtime from the Settings screen. Nothing here assumes a LAN address.
 *
 * <p>Keepalive is tuned for an internet path rather than loopback: without it, a connection severed
 * by a NAT timeout or a dropped link stays "open" from the client's point of view until the next RPC
 * times out.
 */
public class GrpcConnectionManager {
    private static final Logger LOG = LoggerFactory.getLogger(GrpcConnectionManager.class);

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_CONTROL_PLANE_PORT = 50051;
    private static final int DEFAULT_PREDICTOR_PORT = 50052;

    private ManagedChannel controlPlaneChannel;
    private ManagedChannel predictorChannel;

    private ControlPlaneClient controlPlaneClient;
    private PredictorClient predictorClient;

    private String currentHost;
    private int currentControlPlanePort;
    private int currentPredictorPort;
    private volatile boolean controlPlaneUsesMutualTls;

    /** Non-null only when {@link #connectWithEndpoints} has been used — every other connect method
     * (the single-target legacy path every existing caller uses) leaves this null. */
    private ControlPlaneEndpoints endpoints;
    private final Map<ControlPlaneEndpoints.HostPort, ManagedChannel> redirectChannelCache = new ConcurrentHashMap<>();

    public GrpcConnectionManager() {
        this.currentHost = EnvConfig.stringValue("CONTROL_PLANE_HOST", DEFAULT_HOST);
        this.currentControlPlanePort = EnvConfig.intValue("CONTROL_PLANE_PORT", DEFAULT_CONTROL_PLANE_PORT);
        this.currentPredictorPort = EnvConfig.intValue("PREDICTOR_PORT", DEFAULT_PREDICTOR_PORT);
    }

    public synchronized ControlPlaneClient getControlPlaneClient() {
        if (controlPlaneClient == null) {
            connectControlPlane();
        }
        return controlPlaneClient;
    }

    /**
     * Stage J: connects in leader-redirect-following mode instead of the single-target legacy path —
     * {@link ControlPlaneClient} will follow a {@code NOT_LEADER} trailer to whichever candidate
     * {@code endpoints} points at next, retrying within one total 4s budget rather than failing
     * outright the moment the FIRST candidate happens not to be the current leader.
     *
     * <p>Channels for each candidate are built lazily and cached — a failover between two already-
     * contacted replicas doesn't pay fresh TLS/TCP setup on every redirect.
     */
    public synchronized void connectWithEndpoints(ControlPlaneEndpoints endpoints) {
        shutdown();
        this.endpoints = endpoints;
        ControlPlaneEndpoints.HostPort first = endpoints.current();
        this.currentHost = first.host();
        this.currentControlPlanePort = first.port();
        this.controlPlaneClient = new ControlPlaneClient(endpoints, this::channelFor);
        LOG.info("Connected in leader-redirect-following mode, candidates={}", endpoints.candidates());
        connectPredictor();
    }

    private ManagedChannel channelFor(ControlPlaneEndpoints.HostPort target) {
        return redirectChannelCache.computeIfAbsent(target, t -> newChannel(t.host(), t.port()));
    }

    public synchronized PredictorClient getPredictorClient() {
        if (predictorClient == null) {
            connectPredictor();
        }
        return predictorClient;
    }

    /**
     * Points the manager at a new address and rebuilds the channels immediately.
     *
     * <p>The previous implementation only stored the new host and port, leaving the caller to
     * discover much later that nothing had actually reconnected.
     */
    public synchronized void connectTo(String host, int controlPlanePort, int predictorPort) {
        shutdown();
        this.currentHost = host;
        this.currentControlPlanePort = controlPlanePort;
        this.currentPredictorPort = predictorPort;
        this.controlPlaneUsesMutualTls = false;
        LOG.info("Reconnecting to control plane {}:{} (predictor {}:{})",
                host, controlPlanePort, host, predictorPort);
        connectControlPlane();
        connectPredictor();
    }

    /**
     * Connects to the control plane over mutual TLS, using a certificate this node already holds.
     *
     * <p>The predictor connection is unaffected and stays plaintext: it's a Phase-1 stub with no TLS
     * support at all ({@code predictor_service.py} only ever binds an insecure port), and it is never
     * meant to be reached over anything but a trusted internal network — unlike the control plane, it
     * has no enrolment flow, no certificate, nothing to present.
     *
     * @throws SSLException if the supplied certificate material cannot build a valid TLS context
     */
    public synchronized void connectSecurely(String host, int controlPlanePort, int predictorPort,
                                             String caCertificatePem, X509Certificate clientCertificate,
                                             PrivateKey clientPrivateKey) throws SSLException {
        shutdown();
        this.currentHost = host;
        this.currentControlPlanePort = controlPlanePort;
        this.currentPredictorPort = predictorPort;

        controlPlaneChannel = NettyChannelBuilder.forAddress(host, controlPlanePort)
                .sslContext(TlsConfig.mutualClientContext(
                        caCertificatePem, clientCertificate, clientPrivateKey))
                .keepAliveTime(EnvConfig.longValue("GRPC_KEEPALIVE_TIME_MS", 30_000L), TimeUnit.MILLISECONDS)
                .keepAliveTimeout(EnvConfig.longValue("GRPC_KEEPALIVE_TIMEOUT_MS", 10_000L), TimeUnit.MILLISECONDS)
                .keepAliveWithoutCalls(true)
                .idleTimeout(5, TimeUnit.MINUTES)
                .build();
        controlPlaneClient = new ControlPlaneClient(controlPlaneChannel);
        controlPlaneUsesMutualTls = true;
        LOG.info("ControlPlane channel created for {}:{} (mutual TLS)", host, controlPlanePort);

        connectPredictor();
    }

    /** True when the current control-plane connection is authenticated with a client certificate. */
    public synchronized boolean isControlPlaneSecure() {
        return controlPlaneUsesMutualTls;
    }

    private void connectControlPlane() {
        try {
            controlPlaneChannel = newChannel(currentHost, currentControlPlanePort);
            controlPlaneClient = new ControlPlaneClient(controlPlaneChannel);
            LOG.info("ControlPlane channel created for {}:{}", currentHost, currentControlPlanePort);
        } catch (RuntimeException e) {
            LOG.error("Failed to create ControlPlane channel for {}:{}",
                    currentHost, currentControlPlanePort, e);
            controlPlaneChannel = null;
            controlPlaneClient = null;
        }
    }

    private void connectPredictor() {
        try {
            predictorChannel = newChannel(currentHost, currentPredictorPort);
            predictorClient = new PredictorClient(predictorChannel);
            LOG.info("Predictor channel created for {}:{}", currentHost, currentPredictorPort);
        } catch (RuntimeException e) {
            LOG.error("Failed to create Predictor channel for {}:{}",
                    currentHost, currentPredictorPort, e);
            predictorChannel = null;
            predictorClient = null;
        }
    }

    private static ManagedChannel newChannel(String host, int port) {
        return ManagedChannelBuilder
                .forAddress(host, port)
                .usePlaintext()
                // Keepalive must stay within what the server permits, or the server answers
                // GOAWAY/ENHANCE_YOUR_CALM and kills the connection. These pair with the control
                // plane's permitKeepAliveTime.
                .keepAliveTime(EnvConfig.longValue("GRPC_KEEPALIVE_TIME_MS", 30_000L),
                        TimeUnit.MILLISECONDS)
                .keepAliveTimeout(EnvConfig.longValue("GRPC_KEEPALIVE_TIMEOUT_MS", 10_000L),
                        TimeUnit.MILLISECONDS)
                .keepAliveWithoutCalls(true)
                // Without this the channel parks in IDLE between polls and every poll pays a fresh
                // connection setup on a high-latency link.
                .idleTimeout(5, TimeUnit.MINUTES)
                .maxRetryAttempts(3)
                .build();
    }

    /**
     * Whether the control-plane channel has an established transport.
     *
     * <p>Checks {@link ConnectivityState} rather than {@code !channel.isShutdown()}. The old check
     * returned true for a channel that had never reached a server at all, so the UI reported
     * "Connected" against an address with nothing listening on it.
     *
     * @param requestConnection when true, nudges an idle channel into connecting.
     */
    public synchronized boolean isControlPlaneConnected(boolean requestConnection) {
        return isChannelReady(controlPlaneChannel, requestConnection);
    }

    public boolean isControlPlaneConnected() {
        return isControlPlaneConnected(false);
    }

    public synchronized boolean isPredictorConnected() {
        return isChannelReady(predictorChannel, false);
    }

    /** The channel's live connectivity state, or null when no channel exists. */
    public synchronized ConnectivityState getControlPlaneState() {
        return controlPlaneChannel == null ? null : controlPlaneChannel.getState(false);
    }

    private static boolean isChannelReady(ManagedChannel channel, boolean requestConnection) {
        if (channel == null || channel.isShutdown()) {
            return false;
        }
        return channel.getState(requestConnection) == ConnectivityState.READY;
    }

    public synchronized void shutdown() {
        controlPlaneChannel = shutdownChannel(controlPlaneChannel);
        controlPlaneClient = null;
        controlPlaneUsesMutualTls = false;
        predictorChannel = shutdownChannel(predictorChannel);
        predictorClient = null;
        endpoints = null;
        redirectChannelCache.values().forEach(GrpcConnectionManager::shutdownChannel);
        redirectChannelCache.clear();
    }

    /** True once {@link #connectWithEndpoints} has been used — the leader-redirect-following mode is
     * active instead of the single-target legacy path. */
    public synchronized boolean isUsingEndpoints() {
        return endpoints != null;
    }

    private static ManagedChannel shutdownChannel(ManagedChannel channel) {
        if (channel != null) {
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return null;
    }

    public String getCurrentHost() {
        return currentHost;
    }

    public int getCurrentControlPlanePort() {
        return currentControlPlanePort;
    }

    public int getCurrentPredictorPort() {
        return currentPredictorPort;
    }
}
