package com.gtnewhorizons.angelica.sdlgpu.device;

import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresentModeSupportCacheTest {

    @Test
    void missQueriesOnceAndCachesTheResult() {
        final Map<VSyncMode, Boolean> cache = new EnumMap<>(VSyncMode.class);
        final AtomicInteger queries = new AtomicInteger();
        assertTrue(Device.cachedPresentModeSupport(cache, VSyncMode.ON, () -> {
            queries.incrementAndGet();
            return true;
        }));
        assertEquals(1, queries.get());
        assertTrue(Device.cachedPresentModeSupport(cache, VSyncMode.ON, () -> {
            queries.incrementAndGet();
            return true;
        }));
        assertEquals(1, queries.get());
    }

    @Test
    void negativeResultsAreCachedToo() {
        final Map<VSyncMode, Boolean> cache = new EnumMap<>(VSyncMode.class);
        final AtomicInteger queries = new AtomicInteger();
        assertFalse(Device.cachedPresentModeSupport(cache, VSyncMode.MAILBOX, () -> {
            queries.incrementAndGet();
            return false;
        }));
        assertFalse(Device.cachedPresentModeSupport(cache, VSyncMode.MAILBOX, () -> {
            queries.incrementAndGet();
            return true;
        }));
        assertEquals(1, queries.get());
    }

    @Test
    void modesAreCachedIndependently() {
        final Map<VSyncMode, Boolean> cache = new EnumMap<>(VSyncMode.class);
        final AtomicInteger queries = new AtomicInteger();
        Device.cachedPresentModeSupport(cache, VSyncMode.ON, () -> {
            queries.incrementAndGet();
            return true;
        });
        Device.cachedPresentModeSupport(cache, VSyncMode.OFF, () -> {
            queries.incrementAndGet();
            return false;
        });
        assertEquals(2, queries.get());
        assertTrue(cache.get(VSyncMode.ON));
        assertFalse(cache.get(VSyncMode.OFF));
    }
}
