package com.gtnewhorizons.angelica.glsm.threading;

import com.gtnewhorizons.angelica.glsm.hooks.GLSMConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Provides a bounded shared worker pool for shader, resource, and other background client work. */
public final class AngelicaWorkers {
    /** Separates worker lifecycle messages from rendering logs. */
    private static final Logger LOGGER = LogManager.getLogger("Angelica");
    /** Shuts the shared pool down after two minutes without work. */
    private static final long IDLE_TIMEOUT_SECONDS = 120;
    /** Protects executor state, thread configuration, and idle shutdown hooks. */
    private static final Object LOCK = new Object();
    /** Keeps callbacks to run after the worker pool has gone idle. */
    private static final List<Runnable> idleShutdownHooks = new ArrayList<>();
    /** Current lazily-created background executor. */
    private static ExecutorService executor;
    /** Timer that checks whether the current worker pool can be shut down. */
    private static ScheduledExecutorService scheduler;
    /** Monotonic timestamp of the most recently submitted or completed task. */
    private static volatile long lastActivityTime;
    /** Counts queued and running tasks so idle shutdown cannot race with pending work. */
    private static final AtomicInteger inFlight = new AtomicInteger();
    /** Indicates that a timer check is already registered for the current pool. */
    private static boolean idleCheckScheduled;
    /** Resolved thread count for the current process. */
    private static int resolvedThreads;

    /** Prevents constructing this static worker utility. */
    private AngelicaWorkers() {}

    /** Identifies pool threads without relying on thread names at call sites. */
    private static final class Worker extends Thread {
        /** Creates a daemon worker with a stable diagnostic name. */
        private Worker(Runnable work, String name) {
            super(work, name);
            setDaemon(true);
        }
    }

    /** Reports whether the current thread belongs to this worker pool. */
    public static boolean isWorkerThread() {
        return Thread.currentThread() instanceof Worker;
    }

    /** Registers work that should run after the current pool has shut down for idleness. */
    public static void onIdleShutdown(Runnable hook) {
        Objects.requireNonNull(hook, "hook");
        synchronized (LOCK) {
            idleShutdownHooks.add(hook);
        }
    }

    /** Creates the worker pool early and starts its core threads. */
    public static void prestart() {
        executor();
    }

    /** Returns the configured or automatically selected worker count. */
    public static int threads() {
        synchronized (LOCK) {
            resolveThreads();
            return resolvedThreads;
        }
    }

    /** Resolves the host-provided worker count once, falling back to a bounded CPU-based value. */
    private static void resolveThreads() {
        if (resolvedThreads == 0) {
            final int configured = GLSMConfig.workerThreadCount;
            resolvedThreads = configured > 0
                ? configured
                : Math.max(2, Math.min(12, Runtime.getRuntime().availableProcessors() / 2));
        }
    }

    /** Updates the idle timeout reference after activity. */
    private static void noteActivity() {
        lastActivityTime = System.nanoTime();
    }

    /** Lazily creates the fixed-size worker pool and its idle timer. */
    private static ExecutorService executor() {
        synchronized (LOCK) {
            noteActivity();
            if (executor != null && !executor.isShutdown()) return executor;

            resolveThreads();
            final AtomicInteger threadCounter = new AtomicInteger();
            final ThreadFactory factory = work -> new Worker(work, "Angelica-Worker-" + threadCounter.getAndIncrement());
            final ThreadPoolExecutor pool = new ThreadPoolExecutor(resolvedThreads, resolvedThreads, 0L,
                TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), factory);
            pool.prestartAllCoreThreads();
            executor = pool;
            LOGGER.debug("Created Angelica worker pool with {} prestarted threads", resolvedThreads);

            if (scheduler == null || scheduler.isShutdown()) {
                scheduler = Executors.newSingleThreadScheduledExecutor(work -> {
                    final Thread thread = new Thread(work, "Angelica-Worker-Scheduler");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            if (!idleCheckScheduled) {
                idleCheckScheduled = true;
                scheduleIdleCheck(IDLE_TIMEOUT_SECONDS);
            }
            return executor;
        }
    }

    /** Schedules the next idle check and reports scheduler failures. */
    private static void scheduleIdleCheck(long delaySeconds) {
        try {
            scheduler.schedule(AngelicaWorkers::checkIdleShutdown, delaySeconds, TimeUnit.SECONDS);
        } catch (RuntimeException exception) {
            idleCheckScheduled = false;
            LOGGER.warn("Failed to schedule Angelica worker idle check", exception);
        }
    }

    /** Submits a value-producing task to the shared worker pool. */
    public static <T> CompletableFuture<T> submit(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        noteActivity();
        inFlight.incrementAndGet();
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return work.get();
                } finally {
                    noteActivity();
                    inFlight.decrementAndGet();
                }
            }, executor());
        } catch (RuntimeException | Error exception) {
            inFlight.decrementAndGet();
            throw exception;
        }
    }

    /** Submits a no-result task to the shared worker pool. */
    public static CompletableFuture<Void> run(Runnable work) {
        Objects.requireNonNull(work, "work");
        noteActivity();
        inFlight.incrementAndGet();
        try {
            return CompletableFuture.runAsync(() -> {
                try {
                    work.run();
                } finally {
                    noteActivity();
                    inFlight.decrementAndGet();
                }
            }, executor());
        } catch (RuntimeException | Error exception) {
            inFlight.decrementAndGet();
            throw exception;
        }
    }

    /** Runs all tasks and returns their results in the original task order. */
    public static <T> List<T> invokeAll(List<? extends Supplier<? extends T>> tasks) {
        Objects.requireNonNull(tasks, "tasks");
        final int count = tasks.size();
        if (count == 0) return new ArrayList<>();
        final Object[] results = new Object[count];
        final AtomicInteger next = new AtomicInteger();
        final CountDownLatch finished = new CountDownLatch(count);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Runnable drain = () -> {
            int index;
            while ((index = next.getAndIncrement()) < count) {
                try {
                    results[index] = tasks.get(index).get();
                } catch (Throwable exception) {
                    failure.compareAndSet(null, exception);
                } finally {
                    finished.countDown();
                }
            }
        };

        final int helpers = Math.min(threads(), count - 1);
        for (int helper = 0; helper < helpers; helper++) run(drain);
        drain.run();
        try {
            finished.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for worker tasks", exception);
        }

        final Throwable taskFailure = failure.get();
        if (taskFailure instanceof RuntimeException runtimeException) throw runtimeException;
        if (taskFailure instanceof Error error) throw error;
        if (taskFailure != null) throw new IllegalStateException(taskFailure);

        final List<T> values = new ArrayList<>(count);
        for (Object result : results) {
            @SuppressWarnings("unchecked") final T value = (T) result;
            values.add(value);
        }
        return values;
    }

    /** Shuts down the pool after idleness and runs each registered cleanup callback. */
    private static void checkIdleShutdown() {
        final List<Runnable> hooksToRun;
        synchronized (LOCK) {
            final ExecutorService current = executor;
            if (current == null || current.isShutdown()) {
                shutdownScheduler();
                idleCheckScheduled = false;
                return;
            }

            final long idleSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - lastActivityTime);
            if (idleSeconds >= IDLE_TIMEOUT_SECONDS && inFlight.get() == 0) {
                LOGGER.debug("Shutting down idle Angelica worker pool after {} seconds", idleSeconds);
                current.shutdown();
                executor = null;
                shutdownScheduler();
                idleCheckScheduled = false;
                hooksToRun = new ArrayList<>(idleShutdownHooks);
            } else {
                scheduleIdleCheck(Math.max(1, IDLE_TIMEOUT_SECONDS - idleSeconds + 1));
                return;
            }
        }

        for (Runnable hook : hooksToRun) {
            try {
                hook.run();
            } catch (RuntimeException exception) {
                LOGGER.warn("Angelica worker idle shutdown hook failed", exception);
            }
        }
    }

    /** Stops and clears the idle timer after the worker pool has been shut down. */
    private static void shutdownScheduler() {
        if (scheduler == null) return;
        scheduler.shutdown();
        scheduler = null;
    }
}
