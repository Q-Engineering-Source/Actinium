package com.dhj.actinium.texture.atlas;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AtlasLoadLifecycleTest {

    @Test
    void runsEveryCleanupAndKeepsTheLoadFailurePrimary() {
        final RuntimeException loadFailure = new RuntimeException("load failed");
        final RuntimeException closeFailure = new RuntimeException("close failed");
        final AtomicBoolean laterCleanupRan = new AtomicBoolean();

        final Throwable result = AtlasLoadLifecycle.runCleanup(loadFailure,
                () -> { throw closeFailure; },
                () -> laterCleanupRan.set(true));

        assertSame(loadFailure, result);
        assertSame(closeFailure, loadFailure.getSuppressed()[0]);
        assertTrue(laterCleanupRan.get(), "a failed close must not skip remaining cleanup");
    }

    @Test
    void reportsCleanupFailureWhenThereWasNoLoadFailure() {
        final RuntimeException closeFailure = new RuntimeException("close failed");
        final RuntimeException statsFailure = new RuntimeException("stats failed");
        final AtomicBoolean laterCleanupRan = new AtomicBoolean();

        final Throwable result = AtlasLoadLifecycle.runCleanup(null,
                () -> { throw closeFailure; },
                () -> { laterCleanupRan.set(true); throw statsFailure; });

        assertSame(closeFailure, result);
        assertSame(statsFailure, closeFailure.getSuppressed()[0]);
        assertTrue(laterCleanupRan.get());
        assertSame(closeFailure, assertThrows(RuntimeException.class,
                () -> AtlasLoadLifecycle.rethrow(result)));
    }

    @Test
    void waitsForEveryMipmapEvenWhenAnEarlierWorkerFailed() throws Exception {
        final RuntimeException workerFailure = new RuntimeException("mipmap failed");
        final CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(workerFailure);

        final CountDownLatch secondWaitStarted = new CountDownLatch(1);
        final CompletableFuture<Void> pending = new CompletableFuture<>() {
            @Override
            public Void get() throws InterruptedException, ExecutionException {
                secondWaitStarted.countDown();
                return super.get();
            }
        };

        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final Future<IllegalStateException> join = executor.submit(() -> assertThrows(
                    IllegalStateException.class,
                    () -> AtlasLoadSession.awaitMipmaps(new ArrayList<>(java.util.List.of(failed, pending)))));

            assertTrue(secondWaitStarted.await(2, TimeUnit.SECONDS), "join must wait for the later mipmap");
            assertFalse(join.isDone(), "a prior failure must not let the caller leave workers in flight");
            pending.complete(null);
            final IllegalStateException thrown = join.get(2, TimeUnit.SECONDS);
            assertSame(workerFailure, thrown.getCause());
        } finally {
            executor.shutdownNow();
        }
    }
}
