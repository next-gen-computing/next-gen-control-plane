package com.nextgen.controlplane.task;

import com.nextgen.proto.ControlPlaneProto.TaskEvent;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Live fan-out for {@code StreamJobEvents} — relays every {@code TaskProgressEvent}/
 * {@code TaskResultEvent}/{@code TaskLogEvent} a node reports over its {@code TaskChannel} out to
 * whichever external callers (the {@code nx} CLI's {@code up}/{@code logs}) are currently subscribed to
 * that event's job. Nothing here is persisted — a subscriber that connects after an event already fired
 * never sees it (see the project plan's explicit "no server-side log history buffer" scope cut); this
 * is live-only fan-out, not a message queue.
 */
public final class JobEventBroadcaster {
    private static final Logger LOG = LoggerFactory.getLogger(JobEventBroadcaster.class);

    private final Map<String, List<StreamObserver<TaskEvent>>> subscribersByJobId = new ConcurrentHashMap<>();

    public void subscribe(String jobId, StreamObserver<TaskEvent> observer) {
        subscribersByJobId.computeIfAbsent(jobId, id -> new CopyOnWriteArrayList<>()).add(observer);
    }

    public void unsubscribe(String jobId, StreamObserver<TaskEvent> observer) {
        List<StreamObserver<TaskEvent>> subscribers = subscribersByJobId.get(jobId);
        if (subscribers != null) {
            subscribers.remove(observer);
        }
    }

    /** Best-effort: a subscriber whose stream has gone bad is dropped silently rather than failing the
     * task/job machinery that's publishing — exactly the same discipline {@code TaskChannelClient}'s
     * own {@code sendLog} already applies to a lost log line. */
    public void publish(String jobId, TaskEvent event) {
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
}
