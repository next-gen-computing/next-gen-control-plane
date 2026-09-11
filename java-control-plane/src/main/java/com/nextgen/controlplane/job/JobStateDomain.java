package com.nextgen.controlplane.job;

import com.nextgen.proto.ControlPlaneProto;

/**
 * Lifecycle of a job, as tracked by {@link JobRegistry}. Domain enum for the same
 * {@code UNRECOGNIZED}-avoidance reason as {@code TaskStateDomain} — see its Javadoc.
 */
public enum JobStateDomain {

    /** At least one sub-task is not yet terminal. */
    RUNNING(ControlPlaneProto.JobState.JOB_STATE_RUNNING),

    /** Every sub-task succeeded. */
    COMPLETED(ControlPlaneProto.JobState.JOB_STATE_COMPLETED),

    /** Some sub-tasks succeeded, some failed even after their one retry. */
    PARTIAL_FAILURE(ControlPlaneProto.JobState.JOB_STATE_PARTIAL_FAILURE),

    /** Every sub-task failed even after retry — nothing usable came back. */
    FAILED(ControlPlaneProto.JobState.JOB_STATE_FAILED);

    private final ControlPlaneProto.JobState protoValue;

    JobStateDomain(ControlPlaneProto.JobState protoValue) {
        this.protoValue = protoValue;
    }

    public ControlPlaneProto.JobState toProto() {
        return protoValue;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == PARTIAL_FAILURE || this == FAILED;
    }

    /** Unknown/unset values map to {@code RUNNING} — the state closest to "not yet resolved". */
    public static JobStateDomain fromProto(ControlPlaneProto.JobState proto) {
        if (proto == null) {
            return RUNNING;
        }
        // A `default` arm is mandatory: protobuf generates UNRECOGNIZED for every proto3 enum.
        return switch (proto) {
            case JOB_STATE_COMPLETED -> COMPLETED;
            case JOB_STATE_PARTIAL_FAILURE -> PARTIAL_FAILURE;
            case JOB_STATE_FAILED -> FAILED;
            default -> RUNNING;
        };
    }
}
