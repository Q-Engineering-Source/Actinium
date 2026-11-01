package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.stacks.CowStateStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerContextStackIdIntegrationTest extends GLStateManagerIntegrationFixture {
    private static final int THREADS = 12;
    private static final int CONTEXTS_PER_THREAD = 20;

    @Test
    void concurrentWorkerCreationKeepsIdsAlignedAndStateIndependent() throws Exception {
        GLContextState primary = GLStateManager.ctx();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Void>> results = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                Callable<Void> task = () -> {
                    ready.countDown();
                    start.await();
                    for (int j = 0; j < CONTEXTS_PER_THREAD; j++) {
                        verifyWorkerContext(primary);
                    }
                    return null;
                };
                results.add(pool.submit(task));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<Void> result : results) {
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static void verifyWorkerContext(GLContextState primary) {
        GLContextState worker = GLStateManager.enterWorkerContext();
        try {
            assertNotSame(primary, worker);
            assertSame(worker, GLStateManager.ctx());
            assertNotSame(primary.depthState, worker.depthState);
            assertEquals(primary.allStacks.length, worker.allStacks.length);
            for (int i = 0; i < primary.allStacks.length; i++) {
                CowStateStack<?> expected = (CowStateStack<?>) primary.allStacks[i];
                CowStateStack<?> actual = (CowStateStack<?>) worker.allStacks[i];
                int id = actual.stackId();
                assertEquals(expected.stackId(), id);
                assertSame(actual, worker.stackById[id]);
                assertSame(actual.cowDepths(), worker.depthsById[id]);
            }
        } finally {
            GLStateManager.exitWorkerContext();
        }
    }
}
