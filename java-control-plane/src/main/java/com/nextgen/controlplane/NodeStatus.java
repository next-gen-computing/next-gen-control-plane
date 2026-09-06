package com.nextgen.controlplane;

import com.nextgen.proto.ControlPlaneProto;

/**
 * Liveness of a registered node, as tracked by {@link HeartbeatMonitor}.
 *
 * <p>This is a domain enum rather than the generated proto enum on purpose: protobuf adds an
 * {@code UNRECOGNIZED} constant to every proto3 enum whose {@code getNumber()} throws, which is a
 * hazard to carry through internal control-flow. Conversion happens at the wire boundary only.
 *
 * <p>The wire names are the same strings the pre-enum implementation stored in a {@code String}
 * field, so the dashboard JSON contract is unchanged.
 */
public enum NodeStatus {

    /** Heartbeat received within the timeout window. Eligible for scheduling. */
    ALIVE("ALIVE", ControlPlaneProto.NodeStatus.NODE_STATUS_ALIVE),

    /** No heartbeat within the timeout window. Excluded from scheduling. */
    SUSPECTED_DEAD("SUSPECTED_DEAD", ControlPlaneProto.NodeStatus.NODE_STATUS_SUSPECTED_DEAD),

    /** Administratively draining: still heartbeating, but excluded from new work. */
    DRAINING("DRAINING", ControlPlaneProto.NodeStatus.NODE_STATUS_DRAINING),

    /** Deliberately removed. Never resurrected by a sweep; only by an explicit re-registration. */
    DEREGISTERED("DEREGISTERED", ControlPlaneProto.NodeStatus.NODE_STATUS_DEREGISTERED);

    private final String wireName;
    private final ControlPlaneProto.NodeStatus protoValue;

    NodeStatus(String wireName, ControlPlaneProto.NodeStatus protoValue) {
        this.wireName = wireName;
        this.protoValue = protoValue;
    }

    /** The legacy string form used in the dashboard JSON payload. */
    public String wireName() {
        return wireName;
    }

    public ControlPlaneProto.NodeStatus toProto() {
        return protoValue;
    }

    /** True when a node in this state should receive newly submitted tasks. */
    public boolean isSchedulable() {
        return this == ALIVE;
    }

    /**
     * Maps a proto value back to the domain enum. Unknown or unset values map to
     * {@link #SUSPECTED_DEAD} — the conservative choice, since treating an unreadable status as
     * healthy would route work to a node we know nothing about.
     */
    public static NodeStatus fromProto(ControlPlaneProto.NodeStatus proto) {
        if (proto == null) {
            return SUSPECTED_DEAD;
        }
        // A `default` arm is mandatory here: protobuf generates UNRECOGNIZED for every proto3 enum.
        return switch (proto) {
            case NODE_STATUS_ALIVE -> ALIVE;
            case NODE_STATUS_DRAINING -> DRAINING;
            case NODE_STATUS_DEREGISTERED -> DEREGISTERED;
            default -> SUSPECTED_DEAD;
        };
    }

    /** Parses a legacy wire string. Unrecognised input maps to {@link #SUSPECTED_DEAD}. */
    public static NodeStatus fromWireName(String name) {
        if (name == null) {
            return SUSPECTED_DEAD;
        }
        for (NodeStatus status : values()) {
            if (status.wireName.equals(name)) {
                return status;
            }
        }
        return SUSPECTED_DEAD;
    }
}
