package com.dhj.actinium.render.terrain.deferred;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the lifecycle of {@link ObjectPool}: reuse order, the free-capacity limit, the idle trim
 * windows that follow the observed in-use peak, and the ownership split between
 * {@link ObjectPool#close()} (idle objects only) and the tasks still holding objects.
 *
 * <p>Every assertion drives real pool objects whose factory and disposer count themselves, so the
 * numbers are observed behaviour instead of restated constants.</p>
 */
class ObjectPoolTest {
    private static final long WINDOW_NANOS = ObjectPool.TRIM_WINDOW_NANOS;

    private final List<Resource> disposed = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger created = new AtomicInteger();

    @Test
    void acquireReturnsTheMostRecentlyReleasedObjectFirst() {
        ObjectPool<Resource> pool = newPool(8);

        Resource first = pool.acquire();
        Resource second = pool.acquire();

        pool.release(first);
        pool.release(second);

        assertSame(second, pool.acquire());
        assertSame(first, pool.acquire());
        assertEquals(2, pool.inUseCount());
        assertEquals(0, pool.freeCount());
    }

    @Test
    void releaseBeyondMaxFreeDiscardsAndDisposesTheRest() {
        ObjectPool<Resource> pool = newPool(2);

        List<Resource> acquired = acquireMany(pool, 5);

        for (Resource resource : acquired) {
            pool.release(resource);
        }

        assertEquals(2, pool.freeCount());
        assertEquals(3, this.disposed.size());
        assertSame(acquired.get(2), this.disposed.get(0));
        assertSame(acquired.get(3), this.disposed.get(1));
        assertSame(acquired.get(4), this.disposed.get(2));

        assertSame(acquired.get(1), pool.acquire());
        assertSame(acquired.get(0), pool.acquire());
    }

    @Test
    void trimEvictsHalfTheExcessOnceTheWindowElapsed() {
        ObjectPool<Resource> pool = newPool(16);

        List<Resource> acquired = acquireMany(pool, 8);

        for (Resource resource : acquired) {
            pool.release(resource);
        }

        pool.trim(0L, 0);
        assertEquals(8, pool.freeCount());

        pool.trim(WINDOW_NANOS, 0);
        assertEquals(4, pool.freeCount());
        assertEquals(4, this.disposed.size());

        pool.trim(2 * WINDOW_NANOS, 0);
        assertEquals(2, pool.freeCount());
        assertEquals(6, this.disposed.size());
    }

    @Test
    void trimKeepsObjectsThatTheWindowPeakMayNeedAgain() {
        ObjectPool<Resource> pool = newPool(64);

        List<Resource> acquired = acquireMany(pool, 20);
        pool.trim(0L, 0);

        for (Resource resource : acquired) {
            pool.release(resource);
        }

        pool.trim(WINDOW_NANOS, 0);

        assertEquals(20, pool.freeCount());
        assertEquals(0, this.disposed.size());
    }

    @Test
    void trimUsesThePreviousWindowPeakAfterABusyWindow() {
        ObjectPool<Resource> pool = newPool(64);

        List<Resource> acquired = acquireMany(pool, 20);
        pool.trim(0L, 0);

        for (Resource resource : acquired) {
            pool.release(resource);
        }

        // Closes the busy window: the peak of 20 moves into previousPeak.
        pool.trim(WINDOW_NANOS, 0);

        // Quiet window: without previousPeak the threshold would be zero and half of the free
        // objects would be evicted here.
        pool.trim(2 * WINDOW_NANOS, 0);

        assertEquals(20, pool.freeCount());
        assertEquals(0, this.disposed.size());
    }

    @Test
    void trimNeverGoesBelowMinFree() {
        ObjectPool<Resource> pool = newPool(64);

        List<Resource> acquired = acquireMany(pool, 8);

        for (Resource resource : acquired) {
            pool.release(resource);
        }

        pool.trim(0L, 6);
        pool.trim(WINDOW_NANOS, 6);
        assertEquals(7, pool.freeCount());

        pool.trim(2 * WINDOW_NANOS, 6);
        assertEquals(6, pool.freeCount());

        pool.trim(3 * WINDOW_NANOS, 6);
        assertEquals(6, pool.freeCount());
    }

    @Test
    void trimBetweenWindowsDoesNotEvict() {
        ObjectPool<Resource> pool = newPool(16);

        List<Resource> acquired = acquireMany(pool, 8);

        for (Resource resource : acquired) {
            pool.release(resource);
        }

        pool.trim(0L, 0);
        pool.trim(WINDOW_NANOS - 1, 0);

        assertEquals(8, pool.freeCount());
        assertEquals(0, this.disposed.size());
    }

    @Test
    void closeDisposesIdleObjectsAndLeavesInFlightOnesToTheirTasks() {
        ObjectPool<Resource> pool = newPool(8);

        Resource inFlightA = pool.acquire();
        Resource inFlightB = pool.acquire();
        Resource idleA = pool.acquire();
        Resource idleB = pool.acquire();

        pool.release(idleA);
        pool.release(idleB);

        pool.close();

        assertEquals(0, pool.freeCount());
        assertEquals(2, pool.inUseCount());
        assertEquals(2, this.disposed.size());
        assertSame(idleB, this.disposed.get(0));
        assertSame(idleA, this.disposed.get(1));

        pool.release(inFlightA);
        pool.release(inFlightB);

        assertEquals(0, pool.freeCount());
        assertEquals(0, pool.inUseCount());
        assertEquals(4, this.disposed.size());
        assertSame(inFlightA, this.disposed.get(2));
        assertSame(inFlightB, this.disposed.get(3));
    }

    @Test
    void acquireAfterCloseFails() {
        ObjectPool<Resource> pool = newPool(4);
        pool.close();

        assertThrows(IllegalStateException.class, pool::acquire);
    }

    @Test
    void closedPoolStopsCreatingObjects() {
        ObjectPool<Resource> pool = newPool(4);

        Resource first = pool.acquire();
        pool.release(first);
        pool.close();

        assertEquals(1, this.created.get());
        assertEquals(1, this.disposed.size());
    }

    @Test
    void disposeIsCalledForEveryEvictedObject() {
        ObjectPool<Resource> pool = newPool(2);

        Resource first = pool.acquire();
        Resource second = pool.acquire();
        Resource third = pool.acquire();

        assertNotSame(first, second);
        assertNotSame(second, third);

        pool.release(first);
        pool.release(second);
        pool.release(third);

        assertEquals(1, this.disposed.size());
        assertSame(third, this.disposed.get(0));
        assertEquals(2, pool.freeCount());
    }

    @Test
    void returningAnObjectMoreTimesThanItWasAcquiredIsRejected() {
        ObjectPool<Resource> pool = newPool(2);
        Resource value = pool.acquire();
        pool.release(value);

        assertThrows(IllegalStateException.class, () -> pool.release(value));
        assertEquals(0, pool.inUseCount());
        assertEquals(1, pool.freeCount());
        assertSame(value, pool.acquire());
    }

    private ObjectPool<Resource> newPool(int maxFree) {
        return new ObjectPool<>(() -> new Resource(this.created.incrementAndGet()), this.disposed::add, maxFree);
    }

    private static List<Resource> acquireMany(ObjectPool<Resource> pool, int count) {
        List<Resource> resources = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            resources.add(pool.acquire());
        }

        return resources;
    }

    private static final class Resource {
        private final int id;

        private Resource(int id) {
            this.id = id;
        }

        @Override
        public String toString() {
            return "Resource[" + this.id + "]";
        }
    }
}
