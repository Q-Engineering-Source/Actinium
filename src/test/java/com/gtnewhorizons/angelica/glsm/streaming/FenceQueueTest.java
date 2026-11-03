package com.gtnewhorizons.angelica.glsm.streaming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FenceQueueTest {

    @Test
    void fifoOrderAcrossWrapAndGrowth() {
        final FenceQueue q = new FenceQueue();
        for (int i = 0; i < 5; i++) q.enqueue(i, i * 10);
        for (int i = 0; i < 3; i++) {
            assertEquals(i, q.firstId());
            assertEquals(i * 10, q.firstBytes());
            q.dequeue();
        }
        for (int i = 5; i < 15; i++) q.enqueue(i, i * 10);

        for (int i = 3; i < 15; i++) {
            assertFalse(q.isEmpty());
            assertEquals(i, q.firstId());
            assertEquals(i * 10, q.firstBytes());
            q.dequeue();
        }
        assertTrue(q.isEmpty());
    }

    @Test
    void firstBytesTracksFirstId() {
        final FenceQueue q = new FenceQueue();
        q.enqueue(100L, 7);
        q.enqueue(200L, 9);
        assertEquals(100L, q.firstId());
        assertEquals(7, q.firstBytes());
        q.dequeue();
        assertEquals(200L, q.firstId());
        assertEquals(9, q.firstBytes());
    }

    @Test
    void sizeFollowsEnqueueAndDequeue() {
        final FenceQueue q = new FenceQueue();
        assertEquals(0, q.size());
        q.enqueue(1L, 4);
        q.enqueue(2L, 8);
        assertEquals(2, q.size());
        q.dequeue();
        assertEquals(1, q.size());
    }
}
