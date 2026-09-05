package com.nextgen.desktop.ui.client;

import com.nextgen.controlplane.ControlPlaneEndpoints;
import com.nextgen.controlplane.raft.RaftLeaderRedirectInterceptor;
import com.nextgen.proto.ControlPlaneProto;
import com.nextgen.proto.ControlPlaneServiceGrpc;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * gRPC client for ControlPlaneService.
 *
 * <p>Every method propagates failure as {@link ControlPlaneUnavailableException} rather than
 * returning a success-shaped placeholder. That distinction is the whole point: an empty node list
 * must mean "the cluster has no nodes", never "we could not ask".
 *
 * <p>All calls carry a deadline so a hung control plane cannot block the polling thread forever.
 *
 * <p><b>Two modes.</b> The single-{@link ManagedChannel} constructor is what every existing caller
 * and test uses — one fixed target, exactly today's behavior, no redirect-following. The
 * {@link ControlPlaneEndpoints} constructor (Stage J) instead follows a leader-redirect trailer to a
 * different candidate address, retrying within the SAME total 4s budget rather than resetting the
 * clock on each hop — {@link Deadline} is created once per call and threaded through every attempt, so
 * the existing "no call blocks the UI thread more than 4s" guarantee holds exactly as before.
 */
public class ControlPlaneClient {
    private static final Logger LOG = LoggerFactory.getLogger(ControlPlaneClient.class);

    private static final long CALL_DEADLINE_MS = 4_000;
    /** A redirect chases the leader at most this many hops before giving up — bounds a pathological
     * redirect loop (e.g. two replicas each pointing at the other) to a handful of attempts rather than
     * spinning until the deadline regardless. */
    private static final int MAX_REDIRECTS = 3;

    private final ControlPlaneServiceGrpc.ControlPlaneServiceBlockingStub blockingStub;
    private final ControlPlaneEndpoints endpoints;
    private final Function<ControlPlaneEndpoints.HostPort, ManagedChannel> channelResolver;

    public ControlPlaneClient(ManagedChannel channel) {
        this.blockingStub = ControlPlaneServiceGrpc.newBlockingStub(channel);
        this.endpoints = null;
        this.channelResolver = null;
    }

    /**
     * Redirect-following mode (Stage J): {@code channelResolver} builds (or returns an already-open)
     * channel for a given candidate address — typically backed by {@link GrpcConnectionManager}'s own
     * channel cache, so repeated calls don't pay connection setup on every hop.
     */
    public ControlPlaneClient(ControlPlaneEndpoints endpoints,
                              Function<ControlPlaneEndpoints.HostPort, ManagedChannel> channelResolver) {
        this.blockingStub = null;
        this.endpoints = endpoints;
        this.channelResolver = channelResolver;
    }

    private ControlPlaneServiceGrpc.ControlPlaneServiceBlockingStub stub() {
        return blockingStub.withDeadlineAfter(CALL_DEADLINE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Runs {@code invocation} against the right stub for whichever mode this client is in. In
     * redirect-following mode, a leader-hint trailer moves {@code endpoints} to the hinted address and
     * retries immediately (a redirect is a successful discovery, not a failure — it does not consume a
     * transport-failure retry slot); a transport-level failure (UNAVAILABLE/DEADLINE_EXCEEDED with no
     * hint) advances to the next configured candidate; anything else is a real application error and is
     * thrown immediately, never retried.
     */
    private <RespT> RespT call(String opName,
                               Function<ControlPlaneServiceGrpc.ControlPlaneServiceBlockingStub, RespT> invocation) {
        if (endpoints == null) {
            try {
                return invocation.apply(stub());
            } catch (StatusRuntimeException e) {
                throw new ControlPlaneUnavailableException(opName, e);
            }
        }

        Deadline budget = Deadline.after(CALL_DEADLINE_MS, TimeUnit.MILLISECONDS);
        StatusRuntimeException lastFailure = null;
        for (int attempt = 0; attempt <= MAX_REDIRECTS; attempt++) {
            if (budget.isExpired()) {
                break;
            }
            ControlPlaneEndpoints.HostPort target = endpoints.current();
            ManagedChannel channel = channelResolver.apply(target);
            var stub = ControlPlaneServiceGrpc.newBlockingStub(channel).withDeadline(budget);
            try {
                return invocation.apply(stub);
            } catch (StatusRuntimeException e) {
                lastFailure = e;
                String hint = e.getTrailers() == null ? null
                        : e.getTrailers().get(RaftLeaderRedirectInterceptor.LEADER_HINT);
                if (hint != null && !hint.isBlank()) {
                    LOG.debug("{} redirected from {} to leader hint '{}'", opName, target, hint);
                    endpoints.onLeaderHint(hint);
                    continue;
                }
                if (isTransportFailure(e)) {
                    LOG.debug("{} failed against {} ({}) — trying the next candidate",
                            opName, target, e.getStatus().getCode());
                    endpoints.onFailure();
                    continue;
                }
                throw new ControlPlaneUnavailableException(opName, e); // a real application error — do not retry
            }
        }
        throw new ControlPlaneUnavailableException(opName, lastFailure);
    }

    private static boolean isTransportFailure(StatusRuntimeException e) {
        return switch (e.getStatus().getCode()) {
            case UNAVAILABLE, DEADLINE_EXCEEDED -> true;
            default -> false;
        };
    }

    public ControlPlaneProto.RegisterResponse registerNode(String nodeId, String ip, int port,
                                                           String hostname) {
        return registerNode(nodeId, ip, port, hostname,
                ControlPlaneProto.NodeCapabilities.getDefaultInstance(), "");
    }

    public ControlPlaneProto.RegisterResponse registerNode(
            String nodeId, String ip, int port, String hostname,
            ControlPlaneProto.NodeCapabilities capabilities, String agentVersion) {
        ControlPlaneProto.NodeInfo request = ControlPlaneProto.NodeInfo.newBuilder()
                .setNodeId(nodeId)
                .setIp(ip)
                .setPort(port)
                .setHostname(hostname)
                .setCapabilities(capabilities)
                .setAgentVersion(agentVersion)
                .build();
        ControlPlaneProto.RegisterResponse response = call("registerNode", s -> s.registerNode(request));
        LOG.info("Node registered: status={}, assigned_id={}, resumed={}",
                response.getStatus(), response.getAssignedId(), response.getResumedExisting());
        return response;
    }

    /**
     * Sends a heartbeat.
     *
     * <p>The availability flags are mandatory. A caller that cannot read a metric must pass
     * {@code false} and let the control plane record the reading as unavailable, rather than sending
     * a zero that would be stored as a genuine measurement.
     */
    public ControlPlaneProto.HeartbeatResponse sendHeartbeat(String nodeId,
                                                             float cpu, boolean cpuAvailable,
                                                             float memory, boolean memoryAvailable) {
        ControlPlaneProto.HeartbeatRequest request = ControlPlaneProto.HeartbeatRequest.newBuilder()
                .setNodeId(nodeId)
                .setCpu(cpu)
                .setCpuAvailable(cpuAvailable)
                .setMemory(memory)
                .setMemoryAvailable(memoryAvailable)
                .setClientSendEpochMillis(System.currentTimeMillis())
                .build();
        return call("sendHeartbeat", s -> s.sendHeartbeat(request));
    }

    /**
     * Submits a real, executable task. {@code payload} must match {@code kind}'s expected JSON shape
     * — for {@code TASK_KIND_PRIME_COUNT_RANGE}, {@code {"range_start": N, "range_end": M}}.
     *
     * <p>The response is an ACCEPTANCE, not a final result — this call returns as soon as the control
     * plane has placed (or failed to place) the task, well before a real task finishes executing.
     * Poll {@link #getTaskStatus} for the real outcome.
     */
    public ControlPlaneProto.TaskResponse submitTask(String taskId, ControlPlaneProto.TaskKind kind,
                                                      String payload) {
        ControlPlaneProto.TaskRequest request = ControlPlaneProto.TaskRequest.newBuilder()
                .setTaskId(taskId)
                .setPayload(payload)
                .setKind(kind)
                .build();
        return call("submitTask", s -> s.submitTask(request));
    }

    public ControlPlaneProto.TaskStatusResponse getTaskStatus(String taskId) {
        ControlPlaneProto.TaskStatusRequest request = ControlPlaneProto.TaskStatusRequest.newBuilder()
                .setTaskId(taskId)
                .build();
        return call("getTaskStatus", s -> s.getTaskStatus(request));
    }

    /**
     * Submits a job — {@code subTaskCount} real sub-tasks split from {@code payload}, reduced
     * server-side once every sub-task is terminal. Async-ack, exactly like {@link #submitTask}: poll
     * {@link #getJobStatus} for the real, combined outcome.
     */
    public ControlPlaneProto.JobSubmitResponse submitJob(String jobId, ControlPlaneProto.TaskKind kind,
                                                          String payload, int subTaskCount) {
        ControlPlaneProto.JobRequest request = ControlPlaneProto.JobRequest.newBuilder()
                .setJobId(jobId)
                .setKind(kind)
                .setPayloadJson(payload)
                .setSubTaskCount(subTaskCount)
                .build();
        return call("submitJob", s -> s.submitJob(request));
    }

    public ControlPlaneProto.JobStatusResponse getJobStatus(String jobId) {
        ControlPlaneProto.JobStatusRequest request = ControlPlaneProto.JobStatusRequest.newBuilder()
                .setJobId(jobId)
                .build();
        return call("getJobStatus", s -> s.getJobStatus(request));
    }

    public List<ControlPlaneProto.JobStatusResponse> listJobs() {
        return call("listJobs", s -> s.listJobs(ControlPlaneProto.Empty.getDefaultInstance()).getJobsList());
    }

    public List<ControlPlaneProto.NodeInfo> getNodes() {
        // Deliberately never List.of() on failure — call()/ControlPlaneUnavailableException always
        // throws instead. An empty list here would be indistinguishable from a healthy but empty
        // cluster, and the dashboard would render "0 nodes / 100% healthy" while the control plane
        // was down.
        return call("getNodes", s -> s.getNodes(ControlPlaneProto.Empty.getDefaultInstance()).getNodesList());
    }

    public ControlPlaneProto.DeregisterResponse deregisterNode(String nodeId, String reason,
                                                               boolean drain) {
        ControlPlaneProto.DeregisterRequest request = ControlPlaneProto.DeregisterRequest.newBuilder()
                .setNodeId(nodeId)
                .setReason(reason == null ? "" : reason)
                .setDrain(drain)
                .build();
        return call("deregisterNode", s -> s.deregisterNode(request));
    }

    /** Answerable by any replica, including a follower — see {@code ControlPlaneServiceImpl.getClusterStatus}. */
    public ControlPlaneProto.ClusterStatus getClusterStatus() {
        return call("getClusterStatus", s -> s.getClusterStatus(ControlPlaneProto.Empty.getDefaultInstance()));
    }

    /** Stage RR: every task/sub-task on the whole cluster, from {@code TaskRegistry} directly —
     * cluster-wide, unlike {@code TaskExecutionService}'s own list, which only ever tracks tasks this
     * desktop-ui instance personally submitted. Deliberately never empty-on-failure, same reasoning as
     * {@link #getNodes()}. */
    public List<ControlPlaneProto.TaskStatusResponse> listAllTasks() {
        return call("listAllTasks", s -> s.listTasks(ControlPlaneProto.Empty.getDefaultInstance()).getTasksList());
    }

    /** Stage T: every currently-connected Docker-capable node's latest reported real container/image/
     * volume/network inventory. A node absent from the result has either never reported or isn't
     * Docker-capable — never presented as an empty-but-present entry. */
    public ControlPlaneProto.DockerResourcesSnapshot getDockerResources() {
        return call("getDockerResources",
                s -> s.getDockerResources(ControlPlaneProto.Empty.getDefaultInstance()));
    }

    /** Real start/stop/restart/rm — blocks until the target node's actual {@code docker} invocation
     * finishes (or the request times out), returning its real outcome, never a synthetic "accepted". */
    public ControlPlaneProto.DockerControlResult controlDockerContainer(
            String nodeId, String containerId, ControlPlaneProto.DockerControlAction action) {
        ControlPlaneProto.DockerControlCommand request = ControlPlaneProto.DockerControlCommand.newBuilder()
                .setNodeId(nodeId)
                .setContainerId(containerId)
                .setAction(action)
                .build();
        return call("controlDockerContainer", s -> s.controlDockerContainer(request));
    }
}
