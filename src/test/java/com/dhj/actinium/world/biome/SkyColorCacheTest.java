package com.dhj.actinium.world.biome;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkyColorCacheTest {
    @Test
    void blendsBothAxesAtNegativeCoordinatesAndAcrossTheCellEdge() {
        SkyColorCache cache = new SkyColorCache(pos -> {
            int red = pos.getX() >= 0 ? 240 : 0;
            int blue = pos.getZ() >= 0 ? 120 : 0;
            return red << 16 | 25 << 8 | blue;
        });

        assertEquals(100 << 16 | 25 << 8 | 70, cache.getColor(1, -0.75, 70, -0.25, 1));
        int edge = cache.getColor(1, -0.00001, 70, -0.25, 1);
        assertEquals(edge, cache.getColor(1, 0, 70, -0.25, 1));
    }

    @Test
    void reusesSamplesWithinTheCellAndRefreshesForHeightTickAndRadius() {
        AtomicInteger samples = new AtomicInteger();
        SkyColorCache cache = new SkyColorCache(pos -> {
            samples.incrementAndGet();
            return pos.getY() << 16;
        });

        cache.getColor(1, 0.1, 70, 0.1, 1);
        int first = samples.get();
        cache.getColor(1, 0.9, 70.9, 0.9, 1);
        assertEquals(first, samples.get());

        assertEquals(71 << 16, cache.getColor(1, 0.9, 71, 0.9, 1));
        int afterHeightChange = samples.get();
        cache.getColor(2, 0.9, 71, 0.9, 1);
        assertTrue(samples.get() > afterHeightChange);

        int afterTickChange = samples.get();
        cache.getColor(2, 0.9, 71, 0.9, 2);
        assertTrue(samples.get() > afterTickChange);
    }
}
