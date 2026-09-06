package com.nextgen.controlplane.task;

import com.nextgen.proto.ControlPlaneProto;

/**
 * Lifecycle of one real task, as tracked by {@link TaskRegistry}.
 *
 * <p>A domain enum rather than the generated proto enum, for the same reason {@code NodeStatus} is:
 * protobuf adds an {@code UNRECOGNIZED} constant to every proto3 enum whose {@code getNumber()}
 * throws, which is a hazard to carry through internal control flow. Conversion happens at the wire
 * boundary only, in {@link #toProto()}/{@link #fromProto(ControlPlaneProto.TaskState)}.
 */
public enum TaskStateDomain {

    /** Accepted by the server, but no node has been chosen/reachable yet. */
    QUEUED(ControlPlaneProto.TaskState.TASK_STATE_QUEUED),

    /** Sent down a node's task channel; no acknowledgement received yet. */
    DISPATCHED(ControlPlaneProto.TaskState.TASK_STATE_DISPATCHED),

    /** The node confirmed it started executing. */
    RUNNING(ControlPlaneProto.TaskState.TASK_STATE_RUNNING),

    COMPLETED(ControlPlaneProto.TaskState.TASK_STATE_COMPLETED),

    FAILED(ControlPlaneProto.TaskState.TASK_STATE_FAILED),

    /** Being proactively moved to a different node before its current node dies. */
    MIGRATING(ControlPlaneProto.TaskState.TASK_STATE_MIGRATING);

    private final ControlPlaneProto.TaskState protoValue;

    TaskStateDomain(ControlPlaneProto.TaskState protoValue) {
        this.protoValue = protoValue;
    }

    public ControlPlaneProto.TaskState toProto() {
        return protoValue;
    }

    /** True once no further state transition is expected without a new dispatch/migration. */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    /** Unknown/unset values map to {@code QUEUED} — the state closest to "nothing has happened yet". */
    public static TaskStateDomain fromProto(ControlPlaneProto.TaskState proto) {
        if (proto == null) {
            return QUEUED;
        }
        // A `default` arm is mandatory: protobuf generates UNRECOGNIZED for every proto3 enum.
        return switch (proto) {
            case TASK_STATE_DISPATCHED -> DISPATCHED;
            case TASK_STATE_RUNNING -> RUNNING;
            case TASK_STATE_COMPLETED -> COMPLETED;
            case TASK_STATE_FAILED -> FAILED;
            case TASK_STATE_MIGRATING -> MIGRATING;
            default -> QUEUED;
        };
    }
}
