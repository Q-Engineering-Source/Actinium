package dhj.embeddedt.embeddium.impl.render.chunk.data;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchBufferRecyclerTest {
    private final List<String> deleted = new ArrayList<>();

    @Test
    void takeHandsTheMostRecentlyRetiredBufferBack() {
        var recycler = new BatchBufferRecycler<String>();

        recycler.retire("a", this.deleted::add);

        assertSame("a", recycler.take());
        assertTrue(this.deleted.isEmpty());
        assertNull(recycler.take());
    }

    @Test
    void reuseIsLastInFirstOut() {
        var recycler = new BatchBufferRecycler<String>();

        recycler.retire("a", this.deleted::add);
        recycler.retire("b", this.deleted::add);

        assertSame("b", recycler.take());
        assertSame("a", recycler.take());
        assertTrue(this.deleted.isEmpty());
    }

    @Test
    void twoBuffersAreHeldAndAFurtherOneIsDeletedOnRetirement() {
        var recycler = new BatchBufferRecycler<String>();

        recycler.retire("a", this.deleted::add);
        recycler.retire("b", this.deleted::add);

        assertTrue(this.deleted.isEmpty(), "both spare slots accept a buffer");

        recycler.retire("c", this.deleted::add);

        assertEquals(List.of("c"), this.deleted, "a full recycler deletes the surplus buffer immediately");
        assertSame("b", recycler.take());
        assertSame("a", recycler.take());
    }

    @Test
    void drainDeletesEverythingHeldAndLeavesNoSpare() {
        var recycler = new BatchBufferRecycler<String>();
        recycler.retire("a", this.deleted::add);
        recycler.retire("b", this.deleted::add);

        recycler.drain(this.deleted::add);

        assertEquals(List.of("b", "a"), this.deleted);
        assertNull(recycler.take());

        recycler.retire("c", this.deleted::add);
        assertSame("c", recycler.take(), "the recycler is reusable after a drain");
        assertEquals(List.of("b", "a"), this.deleted);
    }

    @Test
    void nullIsIgnored() {
        var recycler = new BatchBufferRecycler<String>();

        recycler.retire(null, this.deleted::add);

        assertNull(recycler.take());
        assertTrue(this.deleted.isEmpty());
    }

    /**
     * Every retired buffer is handed out, deleted or still held, and never lands in two of those states.
     */
    @Test
    void everyBufferEndsUpExactlyOnce() {
        var random = new Random(20261021L);

        for (int run = 0; run < 200; run++) {
            var recycler = new BatchBufferRecycler<String>();
            Set<String> retired = new HashSet<>();
            Set<String> taken = new HashSet<>();
            Set<String> gone = new HashSet<>();
            int next = 0;

            for (int step = 0; step < 100; step++) {
                switch (random.nextInt(3)) {
                    case 0, 1 -> {
                        String buffer = "r" + run + "-" + next++;
                        retired.add(buffer);
                        recycler.retire(buffer, b -> assertTrue(gone.add(b), "deleted twice: " + b));
                    }
                    default -> {
                        String buffer = recycler.take();
                        if (buffer != null) {
                            assertFalse(gone.contains(buffer), "handed out a deleted buffer: " + buffer);
                            assertTrue(taken.add(buffer), "handed out twice: " + buffer);
                        }
                    }
                }

                assertEquals(retired.size(), taken.size() + gone.size() + heldCount(recycler),
                        "a buffer leaked or was counted twice");
            }

            recycler.drain(b -> assertTrue(gone.add(b), "deleted twice: " + b));
            assertEquals(retired.size(), taken.size() + gone.size(), "a buffer leaked across the drain");
        }
    }

    /**
     * Counts the buffers still held by draining them and putting them back in their original order, so the
     * invariant is checked without an accessor the production code would not use.
     */
    private int heldCount(BatchBufferRecycler<String> recycler) {
        var held = new ArrayList<String>();
        recycler.drain(held::add);

        for (int i = held.size() - 1; i >= 0; i--) {
            recycler.retire(held.get(i), this.deleted::add);
        }

        return held.size();
    }
}
