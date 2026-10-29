package net.coderbot.iris.gl.program;

import com.dhj.actinium.startup.ProgramCacheContextReset;
import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
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
    private TestProgramContextProvider contextProvider;

    @BeforeEach
    void resetPool() {
        contextProvider = new TestProgramContextProvider();
        RetainedPrograms.setContextProviderForTesting(contextProvider);
        RetainedPrograms.discardAll();
        deleter = new RecordingDeleter();
        RetainedPrograms.setDeleterForTesting(deleter);
        RetainedPrograms.useConfiguration(null);
        deleter.deleted.clear();
    }

    @AfterEach
    void releasePool() {
        RetainedPrograms.useConfiguration(null);
        RetainedPrograms.discardAll();
        RetainedPrograms.setDeleterForTesting(null);
        RetainedPrograms.setContextProviderForTesting(null);
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

    @Test
    void discardingEveryProgramForgetsItWithoutDeletingIt() {
        RetainedPrograms.useConfiguration("pack|shaders");
        RetainedPrograms.retain(graphics("vertex-a", "fragment"), 1);
        RetainedPrograms.afterPipelineBuilt();
        RetainedPrograms.retain(graphics("vertex-b", "fragment"), 2);

        assertEquals(2, RetainedPrograms.discardAll(), "the caller is told how many names it gives up");

        assertTrue(deleter.deleted.isEmpty(),
            "the names were created on a context that is gone; deleting them could free its successor's program");
        assertEquals(0, RetainedPrograms.take(graphics("vertex-a", "fragment")), "both batches are emptied");
        assertEquals(0, RetainedPrograms.take(graphics("vertex-b", "fragment")));
        assertEquals(0, RetainedPrograms.discardAll(), "the count is reset instead of staying at the discarded total");
    }

    @Test
    void identicalSourcesAreOnlyReusedInsideTheirOwnerShareGroup() {
        final RetainedPrograms.Sources sources = graphics("vertex", "fragment");
        RetainedPrograms.retain(sources, 31);

        contextProvider.makeCurrent(2L);
        RetainedPrograms.retain(sources, 32);
        assertEquals(32, RetainedPrograms.take(sources));
        assertEquals(0, RetainedPrograms.take(sources));

        contextProvider.makeCurrent(1L);
        assertEquals(31, RetainedPrograms.take(sources));
    }

    @Test
    void deletionFromAnotherShareGroupWaitsForAnOwnerGroupContext() {
        contextProvider.makeCurrent(2L);

        RetainedPrograms.deleteProgram(1L, 71);

        assertTrue(deleter.deleted.isEmpty(), "a different share group cannot delete this name");
        contextProvider.makeCurrent(1L);
        assertEquals(0, RetainedPrograms.take(graphics("not present", "not present")));
        assertEquals(List.of(71), deleter.deleted, "the owner group drains its pending deletes before reuse");
    }

    @Test
    void retiringTheLastOwnerContextDiscardsCachedAndQueuedNames() {
        final RetainedPrograms.Sources sources = graphics("vertex", "fragment");
        RetainedPrograms.retain(sources, 81);
        contextProvider.makeCurrent(2L);
        RetainedPrograms.deleteProgram(1L, 82);

        contextProvider.retireShareGroup(1L);
        RetainedPrograms.onContextRetired(new GLContextIdentity(1L, 101L, 1L));

        assertTrue(deleter.deleted.isEmpty(), "native context retirement already released these names");
        assertEquals(0, RetainedPrograms.discardAll(), "retired-group names are no longer held by the pool");
    }

    @Test
    void aWrapperFromAnOlderConfigurationCannotReturnToThePool() {
        final RetainedPrograms.Sources sources = graphics("vertex", "fragment");
        final long oldEpoch = RetainedPrograms.configurationEpoch();
        RetainedPrograms.useConfiguration("pack|shader-state");

        RetainedPrograms.retain(1L, sources, 91, oldEpoch);

        assertEquals(List.of(91), deleter.deleted);
        assertEquals(0, RetainedPrograms.take(sources));
    }

    @Test
    void configurationChangeQueuesOtherShareGroupsProgramsUntilTheirOwnerIsCurrent() {
        final RetainedPrograms.Sources sources = graphics("vertex", "fragment");
        RetainedPrograms.retain(sources, 101);
        contextProvider.makeCurrent(2L);

        RetainedPrograms.useConfiguration("new-pack|new-options");

        assertTrue(deleter.deleted.isEmpty(), "configuration invalidation must not delete another group's name here");
        contextProvider.makeCurrent(1L);
        assertEquals(0, RetainedPrograms.take(sources));
        assertEquals(List.of(101), deleter.deleted);
    }

    @Test
    void displayContextChangePreservesEveryLiveShareGroupAndDrainsOnlyTheCurrentGroup() {
        final RetainedPrograms.Sources groupOneSources = graphics("vertex-one", "fragment");
        final RetainedPrograms.Sources groupTwoSources = graphics("vertex-two", "fragment");
        RetainedPrograms.retain(groupOneSources, 111);

        contextProvider.makeCurrent(2L);
        RetainedPrograms.retain(groupTwoSources, 222);
        RetainedPrograms.deleteProgram(1L, 301);

        contextProvider.makeCurrent(1L);
        RetainedPrograms.deleteProgram(2L, 302);
        ProgramCacheContextReset.onDisplayContextChange();

        assertEquals(List.of(301), deleter.deleted,
            "only deletes queued for the current share group are drained during migration");
        assertEquals(111, RetainedPrograms.take(groupOneSources),
            "the current live group's cached program remains reusable");

        contextProvider.makeCurrent(2L);
        ProgramCacheContextReset.onDisplayContextChange();
        assertEquals(List.of(301, 302), deleter.deleted,
            "pending deletes drain when their own group becomes current");
        assertEquals(222, RetainedPrograms.take(groupTwoSources),
            "a second live share group's cached program survives the same callback");
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
