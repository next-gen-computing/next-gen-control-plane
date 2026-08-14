package com.nextgen.controlplane.raft;

import com.google.protobuf.ByteString;

/**
 * One entry in a {@link RaftLog}: the term it was proposed in, its 1-based index, and an opaque
 * command payload. A no-op entry ({@link ByteString#EMPTY}, appended by a new leader per Raft §5.4.2 so
 * that {@code commitIndex} can advance past a previous term's tail promptly) is deliberately never a
 * parsed {@code StateCommand} — this keeps {@code RaftStateMachine.apply()} from having to special-case
 * it, and {@link #isNoOp()} is how callers recognize one.
 */
public record LogEntry(long index, long term, ByteString command) {

    public boolean isNoOp() {
        return command.isEmpty();
    }
}
