package com.gtnewhorizons.angelica.glsm.threading;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the worker facade executes tasks on its pool and preserves bulk result order. */
class AngelicaWorkersTest {
    /** Confirms worker identity and asynchronous task completion through public entry points. */
    @Test
    void submitsRunsAndInvokesTasksInOrder() {
        assertTrue(AngelicaWorkers.threads() > 0);
        assertTrue(AngelicaWorkers.submit(AngelicaWorkers::isWorkerThread).join());
        assertFalse(AngelicaWorkers.isWorkerThread());

        final List<Integer> values = AngelicaWorkers.invokeAll(List.of(
            () -> 3,
            () -> 1,
            () -> 2
        ));

        assertEquals(List.of(3, 1, 2), values);
        final AtomicBoolean runUsedWorker = new AtomicBoolean();
        AngelicaWorkers.run(() -> runUsedWorker.set(AngelicaWorkers.isWorkerThread())).join();
        assertTrue(runUsedWorker.get());
    }
}
