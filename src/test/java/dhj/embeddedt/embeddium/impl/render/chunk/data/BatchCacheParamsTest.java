package dhj.embeddedt.embeddium.impl.render.chunk.data;

import dhj.embeddedt.embeddium.impl.render.chunk.data.SectionRenderDataStorage.BatchCacheParams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Cache-key behaviour of {@link SectionRenderDataStorage.BatchCacheParams}. A storage keys its two batch slots by
 * these records, so distinct views and culling flags must stay apart, and the views drawn every frame must not
 * allocate a new key per region.
 */
class BatchCacheParamsTest {
    @Test
    void theViewsDrawnEveryFrameReuseSharedKeys() {
        for (int view = 0; view < 2; view++) {
            for (boolean culling : new boolean[] { false, true }) {
                assertSame(BatchCacheParams.of(culling, view), BatchCacheParams.of(culling, view),
                        "view " + view + ", culling " + culling);
            }
        }
    }

    @Test
    void theFourSharedKeysStayDistinct() {
        BatchCacheParams[] keys = {
                BatchCacheParams.of(false, 0), BatchCacheParams.of(true, 0),
                BatchCacheParams.of(false, 1), BatchCacheParams.of(true, 1)
        };

        for (int i = 0; i < keys.length; i++) {
            for (int j = i + 1; j < keys.length; j++) {
                assertNotEquals(keys[i], keys[j], "shared keys " + i + " and " + j);
            }
        }
    }

    @Test
    void furtherViewsAreMatchedByValue() {
        // The storage keeps the key it was handed, so a later lookup with an equal, freshly built key has to hit.
        BatchCacheParams key = BatchCacheParams.of(true, 7);

        assertEquals(new BatchCacheParams(true, 7), key);
        assertNotEquals(BatchCacheParams.of(true, 6), key);
    }
}
