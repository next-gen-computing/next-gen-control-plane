package com.nextgen.controlplane;

import com.nextgen.controlplane.job.JobStateDomain;
import com.nextgen.controlplane.task.TaskKindDomain;
import com.nextgen.proto.ControlPlaneProto.NodeCapabilities;

import java.util.List;

/**
 * Every mutation {@link ControlPlaneServiceImpl}/{@code JobCoordinator}/{@code TaskDispatcher} make to
 * {@link NodeRegistry}/{@code TaskRegistry}/{@code JobRegistry}, expressed as one seam — how a write
 * reaches those registries (directly in a single process, or via Raft consensus when replicated)
 * becomes a choice of implementation, not something callers branch on.
 *
 * <p>Every method is {@code void}: callers re-read the registries for whatever they need afterward
 * (e.g. "was this a resumed registration", "how many sub-tasks did the job split into") rather than
 * getting a typed result back from the write itself.
 *
 * <p><b>Simplification from the original design sketch, deliberate and worth stating plainly:</b> there
 * is no separate asynchronous "leader side effects" callback mechanism here. Every write RPC this
 * interface backs is only ever reached on the current leader — {@code RaftLeaderRedirectInterceptor}
 * (Stage J) rejects a non-leader replica before {@link ControlPlaneServiceImpl}'s method bodies ever
 * run — so a {@code RaftControlPlaneWriter} method can simply <i>block</i> the calling thread until its
 * proposed command has committed AND been applied (by the same single apply thread {@code RaftNode}
 * already owns), then return normally. The original calling code then continues on that same thread,
 * re-reading the now-applied registries and running its existing follow-up logic (a channel push, a
 * retry, a job reduce) completely unchanged — exactly as if this were still one process. This is
 * simpler than threading every side effect through a callback fired from inside the apply thread, and
 * is why {@link ControlPlaneServiceImpl}, {@code JobCoordinator}, and {@code TaskDispatcher} need no
 * structural rewrite to use this seam, only their mutation calls redirected through it.
 *
 * <p>{@link DirectControlPlaneWriter} is what every existing constructor/test uses today — it calls
 * exactly what the inline code called before this seam existed, so introducing it is a zero-behavior-
 * change refactor by construction. {@code RaftControlPlaneWriter} (Stage J) is the only other
 * implementation.
 */
public interface ControlPlaneWriter {

    void registerNode(String nodeId, String ip, int port, String hostname,
                      NodeCapabilities capabilities, String agentVersion);

    void deregisterNode(String nodeId, boolean drain);

    /** Creates a standalone task (Stage A) in the {@code QUEUED} state — mirrors
     * {@code TaskRegistry.createAndQueue} exactly. Assignment is a separate {@link #dispatchTask} call,
     * matching {@code ControlPlaneServiceImpl.submitTask}'s existing two-step flow; an orphaned
     * QUEUED task from a leader dying between the two is reconciled on the next election (see the
     * plan's "queued-task reconciliation" note), not a new failure mode this seam introduces. */
    void submitTask(String taskId, TaskKindDomain kind, String payloadJson);

    /** A job's entire sub-task split, fully pre-computed (every sub-task's id and payload) before this
     * is called — one command creating all of them plus the job record together, not N+1 separate
     * commands. Dispatch of each sub-task to its chosen node is still a separate {@link #dispatchTask}
     * call per sub-task, exactly matching {@code JobCoordinator.submitJob}'s existing create-then-
     * dispatch-each structure. */
    void submitJob(String jobId, TaskKindDomain kind, List<JobSubTaskPlan> plan);

    void dispatchTask(String taskId, String nodeId);

    void markTaskRunning(String taskId, String reportingNodeId);

    void markTaskCompleted(String taskId, String reportingNodeId, String resultJson);

    void markTaskFailed(String taskId, String reportingNodeId, String error);

    void markTaskMigrating(String taskId);

    void markTaskRetried(String jobId, String taskId);

    void completeJob(String jobId, JobStateDomain finalState, String combinedResultJson);

    /** One sub-task of a job: its id and payload, exactly as {@link #submitJob} needs it. */
    record JobSubTaskPlan(String taskId, String payloadJson) {
    }
}
