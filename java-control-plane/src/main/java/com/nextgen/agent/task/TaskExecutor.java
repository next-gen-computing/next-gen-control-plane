package com.nextgen.agent.task;

import com.nextgen.controlplane.task.TaskKindDomain;

/**
 * One real, executable unit of work a node knows how to run for a given {@link TaskKindDomain}.
 * {@link TaskChannelClient} looks one up by the kind named in each incoming {@code TaskDispatch};
 * a kind with no registered executor fails the task honestly rather than hanging silently forever.
 *
 * <p>One executor instance is shared across every concurrently-dispatched task of its kind (see
 * {@code TaskChannelClient}'s {@code Map<TaskKindDomain, TaskExecutor>}) — an implementation that needs
 * real per-task state (e.g. a running container's process handle, for {@link #cancel}) must key it by
 * {@code taskId} internally, never assume single-task-at-a-time use.
 */
public interface TaskExecutor {

    TaskKindDomain kind();

    /**
     * Runs the task to completion and returns its result as a JSON string. Any real failure is
     * thrown, never swallowed here — the caller reports it back to the control plane as a failed
     * {@code TaskResultEvent}.
     *
     * @param taskId     identifies this specific invocation — needed by any executor that supports
     *                   real {@link #cancel}, since one executor instance serves many concurrent tasks.
     * @param payloadJson kind-specific payload, documented at each {@code TaskKindDomain} value's
     *                    proto {@code TaskKind} enum entry.
     * @param events     real-time output sink (see {@link TaskEventSink}'s Javadoc for who actually
     *                   uses this).
     */
    String execute(String taskId, String payloadJson, TaskEventSink events) throws Exception;

    /**
     * Best-effort cooperative cancellation for a task previously started via {@link #execute} and not
     * yet returned. Default no-op — most executors (e.g. a bounded prime-counting range) have nothing
     * meaningful to cancel mid-computation, matching this project's status quo before this method
     * existed ("it will still run to completion and report its result"). A real implementation (e.g.
     * one wrapping a running container) should stop the underlying work so the blocked {@link #execute}
     * call on the caller's thread returns promptly instead of running to completion regardless.
     */
    default void cancel(String taskId) {
        // no-op by default
    }
}
