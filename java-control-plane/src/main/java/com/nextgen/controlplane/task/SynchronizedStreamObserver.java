package com.nextgen.controlplane.task;

import io.grpc.stub.StreamObserver;

/**
 * Wraps a {@code StreamObserver} so its methods are safe to call concurrently from multiple threads.
 *
 * <p>gRPC's {@code StreamObserver} contract requires {@code onNext}/{@code onError}/
 * {@code onCompleted} to never be called concurrently from more than one thread unless the caller
 * synchronizes externally. This node's one {@code TaskChannel} stream is shared by every task
 * currently dispatched to it — a job with N sub-tasks landing on the same node means N concurrent
 * {@link TaskDispatcher#dispatch} calls can race to write to the same observer. Without this wrapper,
 * two threads' writes interleave on the wire and the far end sees a corrupted, unparseable protobuf
 * byte sequence — not a clean error, a silent stall of everything dispatched to that node afterward.
 */
public final class SynchronizedStreamObserver<T> implements StreamObserver<T> {

    private final StreamObserver<T> delegate;
    private final Object lock = new Object();

    public SynchronizedStreamObserver(StreamObserver<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onNext(T value) {
        synchronized (lock) {
            delegate.onNext(value);
        }
    }

    @Override
    public void onError(Throwable t) {
        synchronized (lock) {
            delegate.onError(t);
        }
    }

    @Override
    public void onCompleted() {
        synchronized (lock) {
            delegate.onCompleted();
        }
    }
}
