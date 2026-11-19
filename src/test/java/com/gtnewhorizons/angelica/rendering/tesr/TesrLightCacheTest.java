package com.gtnewhorizons.angelica.rendering.tesr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Covers the tick-scoped refresh of the tile entity light cache.
 *
 * <p>Combined light changes inside a client tick: placing or breaking a light source and the day
 * cycle both take effect on the next tick. The cache therefore has to follow the world's tick count
 * instead of keeping its first value, which would freeze the light of every tile entity that stays
 * in view.</p>
 */
class TesrLightCacheTest {

    @Test
    void reusesTheLookupWithinOneTickAndRepeatsItOnTheNext() {
        final int[] lookups = {0};
        final TesrLightCache.LightSampler sampler = (x, y, z) -> {
            lookups[0]++;
            return 0xF00000 | lookups[0];
        };

        final int first = TesrLightCache.lookup(sampler, 3, 64, 7, 100L);

        assertEquals(first, TesrLightCache.lookup(sampler, 3, 64, 7, 100L));
        assertEquals(1, lookups[0], "the same tick reads the cached value");

        final int nextTick = TesrLightCache.lookup(sampler, 3, 64, 7, 101L);

        assertNotEquals(first, nextTick, "a new tick has to refresh the cached light");
        assertEquals(2, lookups[0]);

        assertEquals(0xF00000 | 2, TesrLightCache.lookup(sampler, 3, 64, 7, 101L));
        assertEquals(2, lookups[0], "the refreshed value is reused for the rest of the tick");
    }

    @Test
    void cachesEachPositionSeparately() {
        final int[] lookups = {0};
        final TesrLightCache.LightSampler sampler = (x, y, z) -> {
            lookups[0]++;
            return x * 31 + y * 7 + z;
        };

        final int a = TesrLightCache.lookup(sampler, 1, 2, 3, 5L);
        final int b = TesrLightCache.lookup(sampler, 1, 2, 4, 5L);

        assertNotEquals(a, b);
        assertEquals(2, lookups[0]);
        assertEquals(a, TesrLightCache.lookup(sampler, 1, 2, 3, 5L));
        assertEquals(2, lookups[0]);
    }
}
