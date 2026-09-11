package com.nextgen.controlplane.task;

import com.nextgen.proto.ControlPlaneProto.TaskEvent;
import com.nextgen.proto.ControlPlaneProto.TaskProgressEvent;
import com.nextgen.proto.ControlPlaneProto.TaskState;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobEventBroadcasterTest {

    private static StreamObserver<TaskEvent> recordingObserver(List<TaskEvent> sink) {
        return new StreamObserver<>() {
            @Override public void onNext(TaskEvent value) { sink.add(value); }
            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        };
    }

    private static TaskEvent progressEvent(String taskId) {
        return TaskEvent.newBuilder()
                .setTaskId(taskId)
                .setProgress(TaskProgressEvent.newBuilder().setTaskId(taskId)
                        .setState(TaskState.TASK_STATE_RUNNING))
                .build();
    }

    @Test
    void publishReachesEverySubscriberForThatJob() {
        JobEventBroadcaster broadcaster = new JobEventBroadcaster();
        List<TaskEvent> received1 = new CopyOnWriteArrayList<>();
        List<TaskEvent> received2 = new CopyOnWriteArrayList<>();
        broadcaster.subscribe("job1", recordingObserver(received1));
        broadcaster.subscribe("job1", recordingObserver(received2));

        broadcaster.publish("job1", progressEvent("t1"));

        assertEquals(1, received1.size());
        assertEquals(1, received2.size());
    }

    @Test
    void publishToAnUnsubscribedJobIsANoOp() {
        JobEventBroadcaster broadcaster = new JobEventBroadcaster();
        broadcaster.publish("never-subscribed", progressEvent("t1")); // must not throw
    }

    /** Stage W: the real leak fix — unsubscribing the LAST subscriber for a job must remove the outer
     * map entry too, not just empty the inner list. */
    @Test
    void unsubscribingTheLastSubscriberRemovesTheMapEntryEntirely() {
        JobEventBroadcaster broadcaster = new JobEventBroadcaster();
        List<TaskEvent> received = new CopyOnWriteArrayList<>();
        StreamObserver<TaskEvent> observer = recordingObserver(received);
        broadcaster.subscribe("leaky-job", observer);
        assertEquals(1, broadcaster.subscriberMapSize());

        broadcaster.unsubscribe("leaky-job", observer);

        assertEquals(0, broadcaster.subscriberMapSize(),
                "the now-empty subscriber list must not linger as a leaked map entry");
    }

    @Test
    void unsubscribingOneOfTwoSubscribersKeepsTheOtherReceiving() {
        JobEventBroadcaster broadcaster = new JobEventBroadcaster();
        List<TaskEvent> received1 = new CopyOnWriteArrayList<>();
        List<TaskEvent> received2 = new CopyOnWriteArrayList<>();
        StreamObserver<TaskEvent> observer1 = recordingObserver(received1);
        broadcaster.subscribe("job2", observer1);
        broadcaster.subscribe("job2", recordingObserver(received2));

        broadcaster.unsubscribe("job2", observer1);
        broadcaster.publish("job2", progressEvent("t1"));

        assertTrue(received1.isEmpty());
        assertEquals(1, received2.size());
        assertEquals(1, broadcaster.subscriberMapSize(), "the job's map entry must survive while a subscriber remains");
    }

    @Test
    void unsubscribingFromAJobThatWasNeverSubscribedIsANoOp() {
        JobEventBroadcaster broadcaster = new JobEventBroadcaster();
        broadcaster.unsubscribe("never-subscribed", recordingObserver(new CopyOnWriteArrayList<>())); // must not throw
        assertEquals(0, broadcaster.subscriberMapSize());
    }

    // ── Stage FF: bounded history replay ──────────────────────────────

    @Test
    void aNewSubscriberReceivesHistoryPublishedBeforeItConnected() {
        JobEventBroadcaster broadcaster = new JobEventBroadcaster();
        broadcaster.publish("job3", progressEvent("t1"));
        broadcaster.publish("job3", progressEvent("t2"));
        List<TaskEvent> received = new CopyOnWriteArrayList<>();

        broadcaster.subscribe("job3", recordingObserver(received));

        assertEquals(2, received.size(), "a late subscriber must still see events published before it connected");
    }

    @Test
    void replayHistoryOnlyDeliversHistoryWithoutRegisteringALiveSubscriber() {
        JobEventBroadcaster broadcaster = new JobEventBroadcaster();
        broadcaster.publish("job4", progressEvent("t1"));
        List<TaskEvent> received = new CopyOnWriteArrayList<>();

        broadcaster.replayHistoryOnly("job4", recordingObserver(received));
        assertEquals(1, received.size());

        // A subsequent live publish must NOT reach the one-shot observer — it was never registered.
        broadcaster.publish("job4", progressEvent("t2"));
        assertEquals(1, received.size(), "a one-shot replay observer must not receive later live events");
        assertEquals(0, broadcaster.subscriberMapSize(), "replayHistoryOnly must never register a live subscriber");
    }

    @Test
    void historyIsBoundedAndDropsTheOldestEntriesFirst() {
        JobEventBroadcaster broadcaster = new JobEventBroadcaster();
        int totalPublished = JobEventBroadcaster.MAX_HISTORY_PER_JOB + 50;
        for (int i = 0; i < totalPublished; i++) {
            broadcaster.publish("job5", progressEvent("t" + i));
        }

        assertEquals(JobEventBroadcaster.MAX_HISTORY_PER_JOB, broadcaster.historySize("job5"));

        List<TaskEvent> received = new CopyOnWriteArrayList<>();
        broadcaster.replayHistoryOnly("job5", recordingObserver(received));

        assertEquals(JobEventBroadcaster.MAX_HISTORY_PER_JOB, received.size());
        // The oldest entries (the first 50 published) must have been dropped — the surviving window
        // starts at t50, not t0.
        assertEquals("t50", received.get(0).getTaskId());
    }

    @Test
    void replayHistoryOnlyOnAJobWithNoHistoryIsANoOp() {
        JobEventBroadcaster broadcaster = new JobEventBroadcaster();
        List<TaskEvent> received = new CopyOnWriteArrayList<>();

        broadcaster.replayHistoryOnly("no-history-job", recordingObserver(received)); // must not throw

        assertTrue(received.isEmpty());
    }
}
