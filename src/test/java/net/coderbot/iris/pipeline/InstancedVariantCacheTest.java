package net.coderbot.iris.pipeline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstancedVariantCacheTest {

    private final List<String> destroyed = new ArrayList<>();
    private final InstancedVariantCache<String> cache = new InstancedVariantCache<>(destroyed::add);

    @Test
    void oneBuildServesEveryQueryForTheSameKey() {
        final AtomicInteger builds = new AtomicInteger();

        final String first = cache.get("entities|template", key -> {
            builds.incrementAndGet();
            return "program:" + key;
        });
        final String second = cache.get("entities|template", key -> {
            builds.incrementAndGet();
            return "other:" + key;
        });

        assertSame(first, second);
        assertEquals(1, builds.get(), "a pass that asks twice must not link the variant twice");
        assertEquals("program:entities|template", first);
    }

    @Test
    void aKeyThatBuiltNothingIsNotRetried() {
        final AtomicInteger builds = new AtomicInteger();

        assertNull(cache.get("entities|cube", key -> {
            builds.incrementAndGet();
            return null;
        }));
        assertNull(cache.get("entities|cube", key -> {
            builds.incrementAndGet();
            return "late:" + key;
        }));

        assertEquals(1, builds.get(), "a source without a transform must not be relinked on every query");
        assertTrue(cache.contains("entities|cube"));
    }

    @Test
    void availabilityIsPartOfTheKey() {
        final AtomicInteger builds = new AtomicInteger();
        final java.util.function.Function<String, String> factory = key -> {
            builds.incrementAndGet();
            return "program:" + key;
        };

        cache.get("entities|tex+lm|template", factory);
        cache.get("entities|lm|template", factory);
        cache.get("entities|tex+lm|cube", factory);

        assertEquals(3, builds.get(), "each source, availability and layout combination is its own variant");
        assertEquals(3, cache.size());
    }

    @Test
    void destroyReleasesEveryBuiltProgramOnceAndForgetsTheKeys() {
        cache.get("a", key -> "program:a");
        cache.get("b", key -> "program:b");
        cache.get("c", key -> null);

        cache.destroyAll();

        assertEquals(List.of("program:a", "program:b"), destroyed);
        assertEquals(0, cache.size());
        assertTrue(!cache.contains("a"), "a destroyed key must be rebuilt if it is asked for again");
    }
}
