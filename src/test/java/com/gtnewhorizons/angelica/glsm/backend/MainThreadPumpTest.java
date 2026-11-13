package com.gtnewhorizons.angelica.glsm.backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class MainThreadPumpTest {

    private static final class DeferredExecutor implements Executor {
        final Deque<Runnable> tasks = new ConcurrentLinkedDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runNext() {
            tasks.removeFirst().run();
        }

        void awaitTask() {
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (tasks.isEmpty()) {
                assertTrue(System.nanoTime() < deadline, "window task was never submitted");
                Thread.onSpinWait();
            }
        }
    }

    private static final class InlineExecutor implements Executor {
        final List<Runnable> submitted = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void execute(Runnable command) {
            submitted.add(command);
            command.run();
        }
    }

    private static void awaitWaiting(Thread t) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (t.getState() != Thread.State.WAITING && t.getState() != Thread.State.TIMED_WAITING) {
            assertTrue(t.isAlive(), "thread died instead of blocking");
            assertTrue(System.nanoTime() < deadline, "caller never waited for the window thread");
            Thread.onSpinWait();
        }
    }

    private static MainThreadPump pump(Executor executor, Runnable pumpBody, Runnable pollBody) {
        return new MainThreadPump(executor, () -> false, pumpBody, pollBody);
    }

    @Test
    void pumpBlocksUntilTheMainThreadRunsTheTask() throws Exception {
        final DeferredExecutor executor = new DeferredExecutor();
        final AtomicInteger polls = new AtomicInteger();
        final MainThreadPump p = pump(executor, () -> {}, polls::incrementAndGet);

        final Thread caller = new Thread(p::pumpMessages, "pump-caller");
        caller.setDaemon(true);
        caller.start();
        awaitWaiting(caller);
        executor.awaitTask();
        assertEquals(1, executor.tasks.size());
        assertEquals(0, polls.get());

        executor.runNext();
        caller.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(caller.isAlive(), "caller remained blocked after the pump completed");
        assertEquals(1, polls.get());
    }

    @Test
    void aThrowingBodyPropagatesToTheCallerAndDoesNotLeaveItParked() {
        final InlineExecutor executor = new InlineExecutor();
        final AtomicInteger polls = new AtomicInteger();
        final AtomicInteger pumps = new AtomicInteger();
        final MainThreadPump p = pump(executor, () -> {
            if (pumps.incrementAndGet() == 1) throw new IllegalStateException("boom");
        }, polls::incrementAndGet);

        final IllegalStateException thrown = assertThrows(IllegalStateException.class, p::pumpMessages);
        assertEquals("boom", thrown.getMessage());
        assertEquals(0, polls.get(), "poll must not run when the pump body failed");

        p.pumpMessages();
        assertEquals(1, polls.get(), "a failed pump must not poison the next one");
    }

    @Test
    void mainThreadCallersRunInlineWithoutTheExecutor() {
        final InlineExecutor executor = new InlineExecutor();
        final AtomicInteger pumps = new AtomicInteger();
        final AtomicInteger polls = new AtomicInteger();
        final MainThreadPump p = new MainThreadPump(executor, () -> true, pumps::incrementAndGet,
            polls::incrementAndGet);

        p.pumpMessages();

        assertTrue(executor.submitted.isEmpty(), "the main thread must not hop through the executor");
        assertEquals(1, pumps.get());
        assertEquals(1, polls.get());
    }

    @Test
    void anInterruptedCallerCompletesAndKeepsItsInterruptFlag() throws Exception {
        final DeferredExecutor executor = new DeferredExecutor();
        final MainThreadPump p = pump(executor, () -> {}, () -> {});
        final AtomicInteger stillInterrupted = new AtomicInteger(-1);

        final Thread caller = new Thread(() -> {
            p.pumpMessages();
            stillInterrupted.set(Thread.currentThread().isInterrupted() ? 1 : 0);
        }, "pump-interrupted-caller");
        caller.setDaemon(true);
        caller.start();
        awaitWaiting(caller);
        executor.awaitTask();
        caller.interrupt();
        assertFalse(executor.tasks.isEmpty());

        executor.runNext();
        caller.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(caller.isAlive(), "caller remained blocked after the pump completed");
        assertEquals(1, stillInterrupted.get());
    }

    @Test
    void aClientPumpWaitsForAPendingPresentPumpWithoutEnqueueing() throws Exception {
        final DeferredExecutor executor = new DeferredExecutor();
        final AtomicInteger pumps = new AtomicInteger();
        final AtomicInteger polls = new AtomicInteger();
        final MainThreadPump p = pump(executor, pumps::incrementAndGet, polls::incrementAndGet);

        p.beginPresent();
        final Thread caller = new Thread(p::pumpMessages, "pump-caller");
        caller.setDaemon(true);
        caller.start();
        awaitWaiting(caller);
        assertTrue(executor.tasks.isEmpty(), "no pump may queue behind an in-flight present");

        p.runPresentPump();
        caller.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(caller.isAlive(), "caller remained blocked after the pump completed");
        assertEquals(1, pumps.get());
        assertEquals(1, polls.get());
        assertTrue(executor.tasks.isEmpty());
    }

    @Test
    void aClientPumpDuringAPresentAfterItsPumpReturnsImmediatelyAndStillPolls() {
        final DeferredExecutor executor = new DeferredExecutor();
        final AtomicInteger pumps = new AtomicInteger();
        final AtomicInteger polls = new AtomicInteger();
        final MainThreadPump p = pump(executor, pumps::incrementAndGet, polls::incrementAndGet);

        p.beginPresent();
        p.runPresentPump();
        p.pumpMessages();
        p.pumpMessages();

        assertTrue(executor.tasks.isEmpty());
        assertEquals(1, pumps.get());
        assertEquals(2, polls.get());
    }

    @Test
    void afterEndPresentAClientPumpUsesTheMainThreadAgain() {
        final InlineExecutor executor = new InlineExecutor();
        final AtomicInteger pumps = new AtomicInteger();
        final MainThreadPump p = pump(executor, pumps::incrementAndGet, () -> {});

        p.beginPresent();
        p.runPresentPump();
        p.endPresent();
        p.pumpMessages();

        assertEquals(1, executor.submitted.size());
        assertEquals(2, pumps.get());
    }

    @Test
    void aPresentPumpFailureIsThrownToTheNextClientPumpExactlyOnce() {
        final InlineExecutor executor = new InlineExecutor();
        final AtomicInteger pumps = new AtomicInteger();
        final MainThreadPump p = pump(executor, () -> {
            if (pumps.incrementAndGet() == 1) throw new IllegalStateException("present boom");
        }, () -> {});

        p.beginPresent();
        p.runPresentPump();
        final IllegalStateException thrown = assertThrows(IllegalStateException.class, p::pumpMessages);
        assertEquals("present boom", thrown.getMessage());

        p.pumpMessages();
        p.endPresent();
        p.pumpMessages();
        assertEquals(2, pumps.get());
    }

    @Test
    void endingAPresentBeforeItsPumpReleasesTheClientToSubmitItsOwnTask() throws Exception {
        final DeferredExecutor executor = new DeferredExecutor();
        final AtomicInteger polls = new AtomicInteger();
        final MainThreadPump p = pump(executor, () -> {}, polls::incrementAndGet);

        p.beginPresent();
        final Thread caller = new Thread(p::pumpMessages, "cancelled-present-pump-caller");
        caller.setDaemon(true);
        caller.start();
        awaitWaiting(caller);
        assertTrue(executor.tasks.isEmpty());

        p.endPresent();
        executor.awaitTask();
        executor.runNext();
        caller.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(caller.isAlive(), "client remained blocked after presentation was cancelled");
        assertEquals(1, polls.get());
    }

    @Test
    void eventPumpUsesTheCallerLoaderAndRestoresTheWindowThreadLoaderOnFailure() throws Exception {
        final DeferredExecutor executor = new DeferredExecutor();
        final ClassLoader windowLoader = Thread.currentThread().getContextClassLoader();
        final ClassLoader callerLoader = new ClassLoader(windowLoader) {};
        final IllegalStateException failure = new IllegalStateException("event failure");
        final AtomicReference<ClassLoader> observedLoader = new AtomicReference<>();
        final AtomicReference<Throwable> observedFailure = new AtomicReference<>();
        final MainThreadPump p = pump(executor, () -> {
            observedLoader.set(Thread.currentThread().getContextClassLoader());
            throw failure;
        }, () -> {});

        final Thread caller = new Thread(() -> {
            observedFailure.set(assertThrows(IllegalStateException.class, p::pumpMessages));
        }, "loader-pump-caller");
        caller.setContextClassLoader(callerLoader);
        caller.setDaemon(true);
        caller.start();
        executor.awaitTask();
        executor.runNext();
        caller.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(caller.isAlive());
        assertSame(callerLoader, observedLoader.get());
        assertSame(windowLoader, Thread.currentThread().getContextClassLoader());
        assertSame(failure, observedFailure.get());
    }

    @Test
    void presentPumpUsesThePresentingThreadLoaderAndRestoresTheWindowThreadLoader() {
        final ClassLoader windowLoader = Thread.currentThread().getContextClassLoader();
        final ClassLoader presentLoader = new ClassLoader(windowLoader) {};
        final AtomicReference<ClassLoader> observedLoader = new AtomicReference<>();
        final MainThreadPump p = pump(Runnable::run,
            () -> observedLoader.set(Thread.currentThread().getContextClassLoader()), () -> {});

        try {
            Thread.currentThread().setContextClassLoader(presentLoader);
            p.beginPresent();
        } finally {
            Thread.currentThread().setContextClassLoader(windowLoader);
        }
        p.runPresentPump();
        p.endPresent();

        assertSame(presentLoader, observedLoader.get());
        assertSame(windowLoader, Thread.currentThread().getContextClassLoader());
    }

}
