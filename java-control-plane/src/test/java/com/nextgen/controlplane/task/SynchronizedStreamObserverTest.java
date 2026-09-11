package com.nextgen.controlplane.task;

import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bug this class exists to prevent was real, not hypothetical: a job with N sub-tasks landing on
 * one node made TaskChannelClient (and, symmetrically, the server's TaskDispatcher) call
 * {@code onNext} on one shared stream from several threads at once, and the two interleaved writes
 * corrupted the wire — observed live as "Protocol message contained an invalid tag (zero)" and every
 * sub-task on that node silently stuck forever. This proves the wrapper actually serializes access.
 */
class SynchronizedStreamObserverTest {

    @Test
    void delegatesOnNextOnErrorOnCompleted() {
        List<Object> received = new CopyOnWriteArrayList<>();
        StreamObserver<String> delegate = new StreamObserver<>() {
            @Override public void onNext(String value) { received.add(value); }
            @Override public void onError(Throwable t) { received.add(t); }
            @Override public void onCompleted() { received.add("completed"); }
        };
        SynchronizedStreamObserver<String> wrapped = new SynchronizedStreamObserver<>(delegate);

        wrapped.onNext("a");
        RuntimeException boom = new RuntimeException("boom");
        wrapped.onError(boom);
        wrapped.onCompleted();

        assertEquals(List.of("a", boom, "completed"), received);
    }

    @RepeatedTest(5)
    void serializesConcurrentOnNextCallsWithoutLosingOrCorruptingAny() throws InterruptedException {
        int threadCount = 16;
        int callsPerThread = 200;
        AtomicInteger receivedCount = new AtomicInteger();

        // A delegate that would itself detect overlapping calls: a non-atomic increment guarded by a
        // "busy" flag. If SynchronizedStreamObserver ever let two threads inside onNext at once, this
        // delegate would observe busy==true on entry and record a corruption.
        AtomicInteger busy = new AtomicInteger(0);
        AtomicInteger corruptions = new AtomicInteger(0);
        StreamObserver<Integer> delegate = new StreamObserver<>() {
            @Override
            public void onNext(Integer value) {
                if (busy.getAndIncrement() != 0) {
                    corruptions.incrementAndGet();
                }
                receivedCount.incrementAndGet();
                Thread.yield(); // widen the window a concurrent caller could exploit, if unsynchronized
                busy.decrementAndGet();
            }

            @Override public void onError(Throwable t) { }
            @Override public void onCompleted() { }
        };
        SynchronizedStreamObserver<Integer> wrapped = new SynchronizedStreamObserver<>(delegate);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        try {
            for (int t = 0; t < threadCount; t++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < callsPerThread; i++) {
                        wrapped.onNext(i);
                    }
                });
            }
            ready.await();
            go.countDown(); // release every thread at once, to maximise contention
            pool.shutdown();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threadCount * callsPerThread, receivedCount.get());
        assertEquals(0, corruptions.get(), "onNext must never run concurrently on the delegate");
    }
}
