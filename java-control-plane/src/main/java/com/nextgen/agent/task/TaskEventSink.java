package com.nextgen.agent.task;

/**
 * Real-time output a {@link TaskExecutor} can report while still running, before it has a final
 * result — e.g. a running container's stdout/stderr lines. Relayed to the control plane as
 * {@code TaskLogEvent}s (see {@code TaskChannelClient#sendLog}). Most executors (e.g.
 * {@link PrimeRangeCounterExecutor}) have nothing incremental to report and simply never call this;
 * that is the normal case, not an interface obligation to fill in.
 */
public interface TaskEventSink {
    void logLine(String line, boolean stderr);
}
