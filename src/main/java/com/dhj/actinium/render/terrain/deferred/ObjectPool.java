package com.dhj.actinium.render.terrain.deferred;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Bounded LIFO pool for the large per-task and per-frame objects of the deferred meshing path
 * (build buffers, world slices, handoff captures). Reuse keeps those objects out of the young
 * generation, and the disposer guarantees that retained native resources are freed even when an
 * object is dropped instead of being recycled.
 *
 * <p>Threading: {@link #acquire()} and {@link #release(Object)} may be called from any thread; the
 * free deque and both counters are atomic. {@link #trim(long, int)} and {@link #close()} belong to
 * the thread that owns the pool's lifecycle (the render thread) and synchronize with release so a
 * closed pool never retains an object.
 *
 * <p>Ownership: an acquired object belongs to the caller until it is released. {@link #close()}
 * disposes only the idle objects it still holds; objects that are still in flight stay owned by
 * their tasks and are disposed when those tasks release them.
 *
 * @param <T> pooled resource type
 */
public final class ObjectPool<T> implements AutoCloseable {
    /**
     * Length of one trim window. Trimming drops only the objects beyond what the peak usage of the
     * last two windows can need again, so a burst stays amortized over a minute of quiet frames.
     */
    public static final long TRIM_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final ConcurrentLinkedDeque<T> free = new ConcurrentLinkedDeque<>();
    private final AtomicInteger freeCount = new AtomicInteger();
    private final AtomicInteger inUse = new AtomicInteger();
    private final Supplier<T> factory;
    private final Consumer<T> disposer;
    private final int maxFree;

    private volatile boolean closed;

    private boolean started;
    private long windowStart;
    private int windowPeak;
    private int previousPeak;

    /**
     * @param factory creates a resource when the pool has no idle object left
     * @param disposer frees a resource that leaves the pool (evicted, over the free limit, or
     *                 released into a closed pool); must be idempotent per object
     * @param maxFree maximum number of idle objects retained; releasing more disposes the excess
     */
    public ObjectPool(Supplier<T> factory, Consumer<T> disposer, int maxFree) {
        if (maxFree < 0) {
            throw new IllegalArgumentException("maxFree must not be negative: " + maxFree);
        }
        this.factory = Objects.requireNonNull(factory, "factory");
        this.disposer = Objects.requireNonNull(disposer, "disposer");
        this.maxFree = maxFree;
    }

    /**
     * Takes an idle object or creates one. The most recently released object is handed out first so
     * a hot object stays hot.
     *
     * @throws IllegalStateException when the pool was closed
     */
    public T acquire() {
        if (this.closed) {
            throw new IllegalStateException("Pool is closed");
        }

        this.inUse.incrementAndGet();

        T value = this.free.pollFirst();

        if (value != null) {
            this.freeCount.decrementAndGet();
            return value;
        }

        return this.factory.get();
    }

    /**
     * Returns an object to the pool, or disposes it when the pool is closed or already holds
     * {@code maxFree} idle objects.
     */
    public void release(T value) {
        Objects.requireNonNull(value, "value");

        if (this.inUse.decrementAndGet() < 0) {
            this.inUse.incrementAndGet();
            throw new IllegalStateException("Object returned to the pool more times than it was acquired");
        }

        synchronized (this) {
            if (!this.closed && this.freeCount.incrementAndGet() <= this.maxFree) {
                this.free.offerFirst(value);
                return;
            }

            if (!this.closed) {
                this.freeCount.decrementAndGet();
            }
        }

        this.disposer.accept(value);
    }

    /** Idle objects currently available for reuse. */
    public int freeCount() {
        return this.freeCount.get();
    }

    /** Objects currently owned by callers and therefore not available for reuse. */
    public int inUseCount() {
        return this.inUse.get();
    }

    /**
     * Drops idle objects the recent usage peak cannot need again. Never runs more than one eviction
     * round per {@link #TRIM_WINDOW_NANOS window}; the round halves the excess above the demand
     * implied by the peak of the previous two windows, and always keeps {@code minFree} idle objects.
     *
     * @param nowNanos monotonic timestamp of this call
     * @param minFree lower bound of the idle objects kept regardless of the computed demand
     */
    public synchronized void trim(long nowNanos, int minFree) {
        int used = this.inUse.get();

        if (used > this.windowPeak) {
            this.windowPeak = used;
        }

        if (!this.started) {
            this.started = true;
            this.windowStart = nowNanos;
            return;
        }

        if (nowNanos - this.windowStart < TRIM_WINDOW_NANOS) {
            return;
        }

        int peak = Math.max(this.windowPeak, this.previousPeak);

        this.previousPeak = this.windowPeak;
        this.windowPeak = used;
        this.windowStart = nowNanos;

        int demand = peak + (peak >> 1) - used;
        int excess = this.freeCount.get() - Math.max(minFree, demand);

        for (int remaining = (excess + 1) >> 1; remaining > 0; remaining--) {
            T value = this.free.pollLast();

            if (value == null) {
                return;
            }

            this.freeCount.decrementAndGet();
            this.disposer.accept(value);
        }
    }

    /**
     * Closes the pool and disposes its idle objects. In-flight objects are untouched: their tasks
     * still own them, and the release into this closed pool disposes them.
     */
    @Override
    public void close() {
        synchronized (this) {
            if (this.closed) {
                return;
            }

            this.closed = true;
        }

        T value = this.free.pollFirst();

        while (value != null) {
            this.freeCount.decrementAndGet();
            this.disposer.accept(value);
            value = this.free.pollFirst();
        }
    }
}
