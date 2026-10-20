package net.coderbot.iris.gl.program;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the retained-program pool through its production entry points: what a destroyed pipeline hands
 * over, what the next pipeline takes, when a batch is retired and when a program is deleted.
 *
 * <p>The pool only deletes GL names through {@link ProgramDeleter}, so a recording double stands in for the
 * driver and every deletion is observable without a context.</p>
 */
class RetainedProgramsTest {

    /** Mirrors the documented bound of one {@link RetainedPrograms.Sources} key per batch. */
    private static final int MAX_PER_SOURCES = 4;
    /** Mirrors the documented bound of the whole pool. */
    private static final int MAX_RETAINED = 512;

    private RecordingDeleter deleter;

    @BeforeEach
    void resetPool() {
        deleter = new RecordingDeleter();
        RetainedPrograms.setDeleterForTesting(deleter);
        RetainedPrograms.useConfiguration(null);
        deleter.deleted.clear();
    }

    @AfterEach
    void releasePool() {
        RetainedPrograms.useConfiguration(null);
        RetainedPrograms.setDeleterForTesting(null);
    }

    @Test
    void aRetainedProgramIsHandedOutOnce() {
        final RetainedPrograms.Sources sources = graphics("vertex", "fragment");
        RetainedPrograms.retain(sources, 42);

        assertEquals(42, RetainedPrograms.take(sources));
        assertEquals(0, RetainedPrograms.take(sources), "the taker owns the program name, so it is not reused");
        assertTrue(deleter.deleted.isEmpty(), "taking a program must not delete it");
    }

    @Test
    void onlyProgramsWithTheSameStagesAreReused() {
        RetainedPrograms.retain(graphics("vertex", "fragment"), 42);

        assertEquals(0, RetainedPrograms.take(graphics("vertex", "another fragment")));
        assertEquals(42, RetainedPrograms.take(graphics("vertex", "fragment")));
    }

    @Test
    void theNewestBatchIsTakenBeforeThePromotedOne() {
        final RetainedPrograms.Sources sources = graphics("vertex", "fragment");
        RetainedPrograms.retain(sources, 1);
        RetainedPrograms.afterPipelineBuilt();

        RetainedPrograms.retain(sources, 2);

        assertEquals(2, RetainedPrograms.take(sources));
        assertEquals(1, RetainedPrograms.take(sources), "a promoted batch is still takeable");
    }

    @Test
    void aPromotedBatchIsKeptUntilTheNextPipelineHasBeenBuilt() {
        final RetainedPrograms.Sources sources = graphics("vertex", "fragment");
        RetainedPrograms.retain(sources, 1);

        RetainedPrograms.afterPipelineBuilt();

        assertTrue(deleter.deleted.isEmpty(), "promoting the newest batch must not delete it");
        assertEquals(1, RetainedPrograms.take(sources));
    }

    @Test
    void theBatchBeforeTheCurrentOneIsDeleted() {
        RetainedPrograms.retain(graphics("vertex-a", "fragment-a"), 1);
        RetainedPrograms.afterPipelineBuilt();
        RetainedPrograms.retain(graphics("vertex-b", "fragment-b"), 2);

        RetainedPrograms.afterPipelineBuilt();

        assertEquals(List.of(1), deleter.deleted, "the program only the previous batch still kept is deleted");
        assertEquals(2, RetainedPrograms.take(graphics("vertex-b", "fragment-b")));
        assertEquals(0, RetainedPrograms.take(graphics("vertex-a", "fragment-a")));
    }

    @Test
    void oneSourcesKeyKeepsABoundedNumberOfPrograms() {
        final RetainedPrograms.Sources sources = graphics("vertex", "fragment");
        for (int program = 1; program <= MAX_PER_SOURCES + 1; program++) {
            RetainedPrograms.retain(sources, program);
        }

        assertEquals(List.of(1), deleter.deleted, "the program kept first is the first one out");
        for (int program = 2; program <= MAX_PER_SOURCES + 1; program++) {
            assertEquals(program, RetainedPrograms.take(sources));
        }
        assertEquals(0, RetainedPrograms.take(sources));
    }

    @Test
    void theWholePoolKeepsABoundedNumberOfPrograms() {
        for (int program = 1; program <= MAX_RETAINED; program++) {
            RetainedPrograms.retain(graphics("vertex-" + program, "fragment"), program);
        }
        assertTrue(deleter.deleted.isEmpty(), "the pool holds its documented number of programs");

        RetainedPrograms.afterPipelineBuilt();
        RetainedPrograms.retain(graphics("overflow", "fragment"), MAX_RETAINED + 1);

        assertEquals(List.of(1), deleter.deleted, "the oldest kept program is the one that goes");
        assertFalse(deleter.deleted.contains(MAX_RETAINED + 1), "the program just kept is not the one evicted");
    }

    @Test
    void aChangedConfigurationRetiresEveryKeptProgram() {
        RetainedPrograms.useConfiguration("pack-a|shaders");
        RetainedPrograms.retain(graphics("vertex-a", "fragment"), 1);
        RetainedPrograms.afterPipelineBuilt();
        RetainedPrograms.retain(graphics("vertex-b", "fragment"), 2);

        RetainedPrograms.useConfiguration("pack-b|shaders");

        assertEquals(2, deleter.deleted.size(), "both batches are dropped when the pack configuration changes");
        assertTrue(deleter.deleted.containsAll(List.of(1, 2)), deleter.deleted.toString());
        assertEquals(0, RetainedPrograms.take(graphics("vertex-a", "fragment")));
        assertEquals(0, RetainedPrograms.take(graphics("vertex-b", "fragment")));
    }

    @Test
    void theSameConfigurationKeepsItsPrograms() {
        RetainedPrograms.useConfiguration("pack|shaders");
        RetainedPrograms.retain(graphics("vertex", "fragment"), 7);

        RetainedPrograms.useConfiguration("pack|shaders");

        assertTrue(deleter.deleted.isEmpty(), "reloading the same pack keeps what the last pipeline linked");
        assertEquals(7, RetainedPrograms.take(graphics("vertex", "fragment")));
    }

    @Test
    void selectingNoConfigurationRetiresEverything() {
        RetainedPrograms.useConfiguration("pack|shaders");
        RetainedPrograms.retain(graphics("vertex", "fragment"), 7);

        RetainedPrograms.useConfiguration(null);

        assertEquals(List.of(7), deleter.deleted);
        assertEquals(0, RetainedPrograms.take(graphics("vertex", "fragment")));
    }

    @Test
    void aComputeProgramIsOnlyMatchedByAComputeKey() {
        final RetainedPrograms.Sources compute = new RetainedPrograms.Sources(null, null, null, null, null, "compute");
        RetainedPrograms.retain(compute, 5);

        assertEquals(0, RetainedPrograms.take(graphics("vertex", "fragment")));
        assertEquals(5, RetainedPrograms.take(compute));
    }

    private static RetainedPrograms.Sources graphics(String vertex, String fragment) {
        return new RetainedPrograms.Sources(vertex, null, null, null, fragment, null);
    }

    /** Records every program the pool deletes instead of calling into a GL context. */
    private static final class RecordingDeleter implements ProgramDeleter {
        private final List<Integer> deleted = new ArrayList<>();

        @Override
        public void deleteProgram(int program) {
            deleted.add(program);
        }
    }
}
