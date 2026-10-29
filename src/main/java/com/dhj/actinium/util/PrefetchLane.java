package com.dhj.actinium.util;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Prefetches values ahead of the consumer, keeping only a fixed window of work in flight.
 *
 * <p>The consumer walks the lane in submission order. Slots it skips are discarded, and asking for a key the
 * cursor has already passed marks the lane desynchronised, which tells the caller to finish the rest itself.
 */
public final class PrefetchLane<K, V> {

    private final Function<Supplier<V>, CompletableFuture<V>> submitter;
    private final int window;
    private final Consumer<V> sink;
    private final ArrayList<Slot<K, V>> slots = new ArrayList<>();
    private final ArrayList<CompletableFuture<Void>> inflight = new ArrayList<>();
    private int pruneAt;
    private int cursor;
    private int next;
    private int ahead;
    private boolean started;
    private boolean closed;
    private boolean desynced;
    private int submitted;
    private int failures;
    private int cancellations;
    private Throwable firstFailure;

    public PrefetchLane(Function<Supplier<V>, CompletableFuture<V>> submitter, int window, Consumer<? super V> disposer) {
        if (window < 1) {
            throw new IllegalArgumentException("window must be positive: " + window);
        }
        this.submitter = submitter;
        this.window = window;
        this.pruneAt = window;
        this.sink = value -> {
            if (value != null) {
                disposer.accept(value);
            }
        };
    }

    public void add(K key, Callable<? extends V> task) {
        if (this.closed) {
            throw new IllegalStateException("PrefetchLane is closed");
        }
        this.slots.add(new Slot<>(key, task));
    }

    public void start() {
        if (this.started || this.closed) {
            return;
        }
        this.started = true;
        fill();
    }

    public V take(K key) {
        if (this.closed) {
            return null;
        }
        final int size = this.slots.size();
        for (int i = this.cursor; i < size; i++) {
            final Slot<K, V> slot = this.slots.get(i);
            if (slot.key != key) {
                continue;
            }
            for (int j = this.cursor; j < i; j++) {
                release(this.slots.set(j, null));
            }
            this.slots.set(i, null);
            this.cursor = i + 1;
            if (slot.future != null) {
                this.ahead--;
            }
            fill();
            return join(slot);
        }
        this.desynced = true;
        close();
        return null;
    }

    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        final int size = this.slots.size();
        for (int i = this.cursor; i < size; i++) {
            release(this.slots.get(i));
        }
        this.slots.clear();
        this.cursor = 0;
        this.next = 0;
        this.ahead = 0;
    }

    public void awaitQuiescence() {
        final int size = this.inflight.size();
        for (int i = 0; i < size; i++) {
            this.inflight.get(i).join();
        }
        this.inflight.clear();
        this.pruneAt = this.window;
    }

    public int submitted() {
        return this.submitted;
    }

    public synchronized int failures() {
        return this.failures;
    }

    public synchronized int cancellations() {
        return this.cancellations;
    }

    public synchronized Throwable firstFailure() {
        return this.firstFailure;
    }

    public boolean isDesynced() {
        return this.desynced;
    }

    private void fill() {
        if (!this.started || this.closed) {
            return;
        }
        if (this.next < this.cursor) {
            this.next = this.cursor;
        }
        final int size = this.slots.size();
        while (this.ahead < this.window && this.next < size) {
            final Slot<K, V> slot = this.slots.get(this.next++);
            if (slot.task == null) {
                continue;
            }
            try {
                slot.future = this.submitter.apply(slot);
            } catch (RuntimeException e) {
                fail(e);
                continue;
            }
            this.inflight.add(slot.future.<Void>handle((value, failure) -> {
                if (failure != null) {
                    recordTerminal(slot, failure);
                }
                return null;
            }));
            pruneInflight();
            this.submitted++;
            this.ahead++;
        }
    }

    private void pruneInflight() {
        if (this.inflight.size() < this.pruneAt) {
            return;
        }
        this.inflight.removeIf(CompletableFuture::isDone);
        this.pruneAt = Math.max(this.window, (int) Math.min(Integer.MAX_VALUE, 2L * this.inflight.size()));
    }

    private void release(Slot<K, V> slot) {
        slot.discarded = true;
        if (slot.future != null) {
            this.ahead--;
            final CompletableFuture<Void> disposal = slot.future.thenAccept(this.sink);
            this.inflight.add(disposal.<Void>handle((value, failure) -> {
                if (failure != null) {
                    recordTerminal(slot, failure);
                }
                return null;
            }));
        }
    }

    private V join(Slot<K, V> slot) {
        if (slot.future == null) {
            return null;
        }
        try {
            return slot.future.join();
        } catch (CompletionException e) {
            recordTerminal(slot, e);
        } catch (CancellationException e) {
            recordTerminal(slot, e);
        }
        return null;
    }

    private synchronized void fail(Throwable t) {
        this.failures++;
        if (this.firstFailure == null) {
            this.firstFailure = unwrap(t);
        }
    }

    private void recordTerminal(Slot<K, V> slot, Throwable failure) {
        final Throwable cause = unwrap(failure);
        if (!slot.terminalRecorded.compareAndSet(false, true)) {
            return;
        }
        if (cause instanceof CancellationException) {
            synchronized (this) {
                this.cancellations++;
            }
        } else {
            fail(cause);
        }
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static final class Slot<K, V> implements Supplier<V> {

        final K key;
        final Callable<? extends V> task;
        CompletableFuture<V> future;
        volatile boolean discarded;
        final AtomicBoolean terminalRecorded = new AtomicBoolean();

        Slot(K key, Callable<? extends V> task) {
            this.key = key;
            this.task = task;
        }

        @Override
        public V get() {
            if (this.discarded) {
                return null;
            }
            try {
                return this.task.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }
    }
}
