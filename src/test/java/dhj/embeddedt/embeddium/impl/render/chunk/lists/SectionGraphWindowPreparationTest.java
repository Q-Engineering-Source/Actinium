package dhj.embeddedt.embeddium.impl.render.chunk.lists;

import dhj.embeddedt.embeddium.impl.render.chunk.occlusion.AsyncOcclusionMode;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lattice window is shared by every pass of a frame and a search reads it for its whole duration, so the window
 * may only be moved while no search is in flight. Rebasing it under a running search clears the cells that search's
 * queue still points at, and the traversal then classifies a slot whose region id is the empty-cell sentinel.
 */
class SectionGraphWindowPreparationTest {
    private static final float SEARCH_DISTANCE = 256.0F;

    @Test
    void windowCannotBePreparedWhileASearchIsInFlight() throws Exception {
        SectionGraph graph = new SectionGraph(0, 15, AsyncOcclusionMode.EVERYTHING, false, false);

        try {
            CountDownLatch searchStarted = new CountDownLatch(1);
            CountDownLatch releaseSearch = new CountDownLatch(1);

            CompletableFuture<Void> search = graph.submit(() -> {
                searchStarted.countDown();
                awaitUninterruptibly(releaseSearch);
                return null;
            }, true);

            assertTrue(searchStarted.await(10, TimeUnit.SECONDS), "the search never started");

            assertThrows(IllegalStateException.class,
                    () -> graph.prepareWindow(SEARCH_DISTANCE, new Vector3i(0, 0, 0), new Vector3i(40, 0, 40)));

            releaseSearch.countDown();
            search.join();
            graph.onSearchJoined();

            assertDoesNotThrow(
                    () -> graph.prepareWindow(SEARCH_DISTANCE, new Vector3i(0, 0, 0), new Vector3i(40, 0, 40)));
        } finally {
            graph.destroy();
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;

        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
