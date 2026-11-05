package com.dhj.actinium.render.terrain;

import dhj.embeddedt.embeddium.impl.render.chunk.map.ChunkStatus;
import dhj.embeddedt.embeddium.impl.render.chunk.map.ChunkTracker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CubeStatusTrackerTest {
    @Test
    void keepsTheInheritedColumnStateEmpty() {
        CubeStatusTracker tracker = new CubeStatusTracker();

        tracker.onChunkStatusAdded(0, 0, ChunkStatus.FLAG_ALL);
        tracker.onChunkStatusAdded(1, 0, ChunkStatus.FLAG_ALL);

        assertInstanceOf(ChunkTracker.class, tracker);
        assertTrue(tracker.getReadyChunks().isEmpty());
    }

    @Test
    void publishesNoColumnEventsToSubscribers() {
        CubeStatusTracker tracker = new CubeStatusTracker();
        ChunkTracker.Subscription subscription = tracker.subscribe();
        int[] events = new int[1];

        tracker.onChunkStatusAdded(0, 0, ChunkStatus.FLAG_ALL);
        subscription.forEachEvent((x, z) -> events[0]++, (x, z) -> events[0]++);

        assertEquals(0, events[0]);
    }

    @Test
    void requiresEveryNeighbourBeforeACubeCountsAsLoaded() {
        CubeStatusTracker tracker = new CubeStatusTracker();

        tracker.onCubeLoaded(0, 0, 0);

        assertEquals(0, tracker.readyCubeCount());
    }

    @Test
    void publishesACubeOnceItsNeighbourhoodIsLoaded() {
        CubeStatusTracker tracker = new CubeStatusTracker();

        for (int y = -1; y <= 1; y++) {
            for (int z = -1; z <= 1; z++) {
                for (int x = -1; x <= 1; x++) {
                    tracker.onCubeLoaded(x, y, z);
                }
            }
        }

        assertEquals(1, tracker.readyCubeCount());
    }

    @Test
    void fastModeSkipsTheNeighbourCheck() {
        CubeStatusTracker tracker = new CubeStatusTracker();
        tracker.setFastMode(true);

        tracker.onCubeLoaded(0, 0, 0);

        assertEquals(1, tracker.readyCubeCount());
    }
}
