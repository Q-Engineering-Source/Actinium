package com.gtnewhorizons.angelica.sdlgpu.frame;

import com.gtnewhorizons.angelica.glsm.backend.MainThreadPump;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class PresenterTest {
    private static MainThreadPump pump(Runnable events) {
        return new MainThreadPump(Runnable::run, () -> true, events, () -> {});
    }

    @Test
    void consecutiveWindowThreadRequestsClaimTheirQueuedWorkAndRetainEachRequest() {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final List<String> draws = new ArrayList<>();
        final Presenter presenter = new Presenter((texture, width, height, flip) -> draws.add(texture + ":" + width + ":" + height + ":" + flip),
            queue::add, pump(() -> {}), () -> true);
        presenter.requestPresent(11L, 17, 19, 3);
        presenter.requestPresent(23L, 29, 31, 5);
        presenter.drain();
        while (!queue.isEmpty()) queue.removeFirst().run();
        assertEquals(List.of("11:17:19:3", "23:29:31:5"), draws);
    }

    @Test
    void windowThreadDrainExecutesWorkerRequestAndQueuedCopyDoesNothing() throws InterruptedException {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final AtomicInteger draws = new AtomicInteger();
        final Thread windowThread = Thread.currentThread();
        final Presenter presenter = new Presenter((texture, width, height, flip) -> draws.incrementAndGet(), queue::add,
            pump(() -> {}), () -> Thread.currentThread() == windowThread);
        final Thread worker = new Thread(() -> presenter.requestPresent(1L, 8, 8, 0));
        worker.start();
        worker.join();
        assertEquals(0, draws.get());
        presenter.drain();
        assertEquals(1, draws.get());
        queue.removeFirst().run();
        assertEquals(1, draws.get());
    }

    @Test
    void pumpReentryCannotClaimTheSameTaskTwice() {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final AtomicInteger draws = new AtomicInteger();
        final Presenter presenter = new Presenter((texture, width, height, flip) -> draws.incrementAndGet(), queue::add,
            pump(() -> { if (!queue.isEmpty()) queue.removeFirst().run(); }), () -> true);
        presenter.requestPresent(1L, 8, 8, 0);
        presenter.drain();
        assertEquals(1, draws.get());
        assertTrue(queue.isEmpty());
        assertTrue(presenter.tryRequestPresent(2L, 8, 8, 0));
        presenter.drain();
        assertEquals(2, draws.get());
    }

    @Test
    void rejectionReleasesTheSlotForTheNextRequest() {
        final AtomicBoolean reject = new AtomicBoolean(true);
        final AtomicInteger draws = new AtomicInteger();
        final Presenter presenter = new Presenter((texture, width, height, flip) -> draws.incrementAndGet(), command -> {
            if (reject.getAndSet(false)) throw new RejectedExecutionException("test");
            command.run();
        }, pump(() -> {}), () -> true);
        assertThrows(RejectedExecutionException.class, () -> presenter.requestPresent(1L, 8, 8, 0));
        assertTrue(presenter.tryRequestPresent(2L, 8, 8, 0));
        assertEquals(1, draws.get());
        presenter.drain();
    }

    @Test
    void recursiveDrainReturnsAndRecursiveSubmissionFailsBeforeReplacingTheTask() {
        final Presenter[] ref = new Presenter[1];
        final AtomicInteger draws = new AtomicInteger();
        ref[0] = new Presenter((texture, width, height, flip) -> {
            ref[0].drain();
            assertThrows(IllegalStateException.class, () -> ref[0].requestPresent(2L, 8, 8, 0));
            assertFalse(ref[0].tryRequestPresent(2L, 8, 8, 0));
            draws.incrementAndGet();
        }, Runnable::run, pump(() -> {}), () -> true);
        ref[0].requestPresent(1L, 8, 8, 0);
        ref[0].drain();
        assertEquals(1, draws.get());
    }

    @Test
    void failedPresentationReleasesItsSlotExactlyOnce() {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final AtomicBoolean fail = new AtomicBoolean(true);
        final AtomicInteger draws = new AtomicInteger();
        final Presenter presenter = new Presenter((texture, width, height, flip) -> {
            if (fail.getAndSet(false)) throw new IllegalStateException("test");
            draws.incrementAndGet();
        }, queue::add, pump(() -> {}), () -> true);
        presenter.requestPresent(1L, 8, 8, 0);
        assertThrows(IllegalStateException.class, presenter::drain);
        queue.removeFirst().run();
        assertTrue(presenter.tryRequestPresent(2L, 8, 8, 0));
        assertFalse(presenter.tryRequestPresent(3L, 8, 8, 0));
        presenter.drain();
        assertEquals(1, draws.get());
    }

    @Test
    void inlineExecutionFailureDoesNotReleaseTheSlotTwice() {
        final AtomicBoolean fail = new AtomicBoolean(true);
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        final Presenter presenter = new Presenter((texture, width, height, flip) -> {
            if (fail.getAndSet(false)) throw new IllegalStateException("test");
        }, command -> {
            if (fail.get()) command.run();
            else queue.add(command);
        }, pump(() -> {}), () -> true);
        assertThrows(IllegalStateException.class, () -> presenter.requestPresent(1L, 8, 8, 0));
        assertTrue(presenter.tryRequestPresent(2L, 8, 8, 0));
        assertFalse(presenter.tryRequestPresent(3L, 8, 8, 0));
        presenter.drain();
    }
}
