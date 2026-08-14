package com.nextgen.controlplane.task;

import com.nextgen.proto.ControlPlaneProto;

/**
 * What kind of real, executable work a task carries. Domain enum for the same
 * {@code UNRECOGNIZED}-avoidance reason as {@link TaskStateDomain} — see its Javadoc.
 *
 * <p>Each value needs a matching executor registered node-side
 * ({@code com.nextgen.agent.task.TaskExecutor}) before a node can actually run it.
 */
public enum TaskKindDomain {

    /** Counts primes in a half-open integer range {@code [start, end)}. Real, CPU-bound, checkable. */
    PRIME_COUNT_RANGE(ControlPlaneProto.TaskKind.TASK_KIND_PRIME_COUNT_RANGE),

    /** One service from a distributed docker-compose-style project — see
     * {@code com.nextgen.agent.task.DockerComposeServiceExecutor} for the payload JSON shape. */
    DOCKER_COMPOSE_SERVICE(ControlPlaneProto.TaskKind.TASK_KIND_DOCKER_COMPOSE_SERVICE);

    private final ControlPlaneProto.TaskKind protoValue;

    TaskKindDomain(ControlPlaneProto.TaskKind protoValue) {
        this.protoValue = protoValue;
    }

    public ControlPlaneProto.TaskKind toProto() {
        return protoValue;
    }

    /**
     * Maps a proto value to the domain enum, or {@code null} if it names a kind this build doesn't
     * recognise — callers must treat that as a real error (fail the task honestly), not silently
     * substitute a default kind and run the wrong code.
     */
    public static TaskKindDomain fromProto(ControlPlaneProto.TaskKind proto) {
        if (proto == null) {
            return null;
        }
        // A `default` arm is mandatory: protobuf generates UNRECOGNIZED for every proto3 enum.
        return switch (proto) {
            case TASK_KIND_PRIME_COUNT_RANGE -> PRIME_COUNT_RANGE;
            case TASK_KIND_DOCKER_COMPOSE_SERVICE -> DOCKER_COMPOSE_SERVICE;
            default -> null;
        };
    }
}
