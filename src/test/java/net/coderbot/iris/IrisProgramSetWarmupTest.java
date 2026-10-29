package net.coderbot.iris;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the handshake between the transform warmup that runs after a pack loads and the pipeline
 * creation that consumes what it cached.
 */
class IrisProgramSetWarmupTest {

    @AfterEach
    void clearPendingWarmup() {
        Iris.programSetWarmup = null;
    }

    @Test
    void awaitingWithNoWarmupIsANoOp() {
        Iris.programSetWarmup = null;
        Iris.awaitProgramSetWarmup();
        assertNull(Iris.programSetWarmup);
    }

    @Test
    void awaitingBlocksUntilTheWarmupCompletes() throws Exception {
        final CompletableFuture<Void> warmup = new CompletableFuture<>();
        Iris.programSetWarmup = warmup;
        final CountDownLatch awaited = new CountDownLatch(1);
        final Thread joiner = new Thread(() -> {
            Iris.awaitProgramSetWarmup();
            awaited.countDown();
        });
        joiner.start();
        assertFalse(awaited.await(200, TimeUnit.MILLISECONDS), "a waiter must not proceed while the warmup is still running");
        warmup.complete(null);
        assertTrue(awaited.await(5, TimeUnit.SECONDS));
        joiner.join();
        assertNull(Iris.programSetWarmup, "a joined warmup must not be joined again");
    }

    @Test
    void aFailedWarmupIsSwallowedAndCleared() {
        final CompletableFuture<Void> warmup = new CompletableFuture<>();
        warmup.completeExceptionally(new IllegalStateException("boom"));
        Iris.programSetWarmup = warmup;

        Iris.awaitProgramSetWarmup();
        assertNull(Iris.programSetWarmup, "the transforms run again at pipeline creation, so the failure must not propagate");
    }
}
