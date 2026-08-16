package com.nextgen.controlplane.task;

import com.nextgen.proto.ControlPlaneProto.TaskEvent;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Live fan-out for {@code StreamJobEvents} — relays every {@code TaskProgressEvent}/
 * {@code TaskResultEvent}/{@code TaskLogEvent} a node reports over its {@code TaskChannel} out to
 * whichever external callers (the {@code nx} CLI's {@code up}/{@code logs}) are currently subscribed to
 * that event's job.
 *
 * <p>Stage FF: also keeps a small, bounded per-job history buffer ({@link #MAX_HISTORY_PER_JOB} events),
 * mirroring {@code NodeHistory}'s own copy-on-write {@code compute()} idiom — this is what lets
 * {@code nx logs <job-id>} (without {@code --follow}) show real historical lines instead of nothing, the
 * previously-named "no server-side log history buffer" scope cut. Deliberately NOT unbounded
 * persistence/pagination (see the project plan) — a fixed recent-history window per job, not a message
 * queue or a durable log.
 */
public final class JobEventBroadcaster {
    private static final Logger LOG = LoggerFactory.getLogger(JobEventBroadcaster.class);

    /** Caps memory per job regardless of how long it runs or how chatty its services' logs are — a job
     * that finishes and is never looked at again still holds at most this many events' worth of memory. */
    static final int MAX_HISTORY_PER_JOB = 500;

    private final Map<String, List<StreamObserver<TaskEvent>>> subscribersByJobId = new ConcurrentHashMap<>();
    private final Map<String, List<TaskEvent>> historyByJobId = new ConcurrentHashMap<>();

    /** Registers a live subscriber and immediately replays whatever history this job already has —
     * a subscriber that connects mid-job (e.g. {@code nx logs --follow} on an already-running job)
     * sees the same lines an earlier-connected subscriber already saw, not just what happens next. */
    public void subscribe(String jobId, StreamObserver<TaskEvent> observer) {
        subscribersByJobId.computeIfAbsent(jobId, id -> new CopyOnWriteArrayList<>()).add(observer);
        replayHistoryTo(jobId, observer);
    }

    /** One-shot: sends only the currently-buffered history to {@code observer}, then returns — never
     * registers it as a live subscriber. Backs {@code StreamJobEvents}' {@code follow=false} path
     * ({@code nx logs} without {@code --follow}): show what's there and close, matching docker/kubectl's
     * own {@code logs} default rather than an indefinite tail. */
    public void replayHistoryOnly(String jobId, StreamObserver<TaskEvent> observer) {
        replayHistoryTo(jobId, observer);
    }

    private void replayHistoryTo(String jobId, StreamObserver<TaskEvent> observer) {
        for (TaskEvent event : historyByJobId.getOrDefault(jobId, List.of())) {
            try {
                observer.onNext(event);
            } catch (RuntimeException e) {
                LOG.debug("Dropping a dead StreamJobEvents subscriber while replaying history for job '{}': {}",
                        jobId, e.getMessage());
                return;
            }
        }
    }

    /** Stage W: removes the now-empty list entry too, via the atomic {@code computeIfPresent} check-
     * and-remove — without this, every distinct {@code job_id} ever subscribed to (valid, typo'd, or
     * adversarial) leaked one {@code ConcurrentHashMap} entry for the life of the process, since a list
     * emptied by {@code remove} was never itself removed from the outer map. */
    public void unsubscribe(String jobId, StreamObserver<TaskEvent> observer) {
        subscribersByJobId.computeIfPresent(jobId, (id, subscribers) -> {
            subscribers.remove(observer);
            return subscribers.isEmpty() ? null : subscribers;
        });
    }

    /** Best-effort: a subscriber whose stream has gone bad is dropped silently rather than failing the
     * task/job machinery that's publishing — exactly the same discipline {@code TaskChannelClient}'s
     * own {@code sendLog} already applies to a lost log line. Always records into the bounded history
     * buffer FIRST, regardless of whether anyone is currently subscribed — a job nobody is watching
     * live still needs its history available for a LATER {@code nx logs} call. */
    public void publish(String jobId, TaskEvent event) {
        recordHistory(jobId, event);

        List<StreamObserver<TaskEvent>> subscribers = subscribersByJobId.get(jobId);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        for (StreamObserver<TaskEvent> subscriber : subscribers) {
            try {
                subscriber.onNext(event);
            } catch (RuntimeException e) {
                LOG.debug("Dropping a dead StreamJobEvents subscriber for job '{}': {}", jobId, e.getMessage());
                subscribers.remove(subscriber);
            }
        }
    }

    /** Appends to the job's bounded history, trimming the oldest entries once {@link
     * #MAX_HISTORY_PER_JOB} is exceeded — same copy-on-write {@code compute()} idiom as {@code
     * NodeHistory.record}. */
    private void recordHistory(String jobId, TaskEvent event) {
        historyByJobId.compute(jobId, (id, existing) -> {
            List<TaskEvent> current = existing == null ? List.of() : existing;
            List<TaskEvent> updated = new ArrayList<>(current);
            updated.add(event);
            int excess = updated.size() - MAX_HISTORY_PER_JOB;
            if (excess > 0) {
                updated = new ArrayList<>(updated.subList(excess, updated.size()));
            }
            return List.copyOf(updated);
        });
    }

    /** Test-only visibility into the leak Stage W fixes — real callers never need this. */
    int subscriberMapSize() {
        return subscribersByJobId.size();
    }

    /** Test-only visibility into the Stage FF history buffer. */
    int historySize(String jobId) {
        return historyByJobId.getOrDefault(jobId, List.of()).size();
    }
}
