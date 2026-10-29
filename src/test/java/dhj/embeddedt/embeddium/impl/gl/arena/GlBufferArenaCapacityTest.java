package dhj.embeddedt.embeddium.impl.gl.arena;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GlBufferArenaCapacityTest {
    @Test
    void doublesSmallArenaElementCapacity() {
        assertEquals(2048, GlBufferArena.growthTargetFor(1024, 4, 0, 512));
    }

    @Test
    void growsLargeArenaByHalfOfItsCurrentElementCapacity() {
        int currentElements = 2 * 1024 * 1024;
        assertEquals(3 * 1024 * 1024,
                GlBufferArena.growthTargetFor(currentElements, 4, currentElements, 0));
    }

    @Test
    void honorsMinimumArenaCapacity() {
        assertEquals(1024, GlBufferArena.growthTargetFor(100, 4, 0, 1024));
    }

    @Test
    void rejectsARequestBeyondTheArenaElementLimit() {
        assertThrows(OutOfMemoryError.class,
                () -> GlBufferArena.growthTargetFor((long) Integer.MAX_VALUE + 1L, 4, 0, 0));
    }
}
