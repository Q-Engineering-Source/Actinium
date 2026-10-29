package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayListCompilationOwnershipTest {

    @Test
    void activeCompilerOwnerMatchesRetirement() {
        final GLContextIdentity active = new GLContextIdentity(1L, 11L, 4L);

        assertTrue(DisplayListCompilationOwnership.includes(active, List.of(), active));
    }

    @Test
    void retiredNestedParentStillOwnsTheWholeCompilationTree() {
        final GLContextIdentity parent = new GLContextIdentity(1L, 11L, 4L);
        final GLContextIdentity active = new GLContextIdentity(2L, 12L, 4L);

        assertTrue(DisplayListCompilationOwnership.includes(active, List.of(parent), parent));
        assertTrue(DisplayListCompilationOwnership.includes(active, List.of(parent), active));
    }

    @Test
    void unrelatedContextInTheSameShareGroupDoesNotOwnTheCompilation() {
        final GLContextIdentity active = new GLContextIdentity(1L, 11L, 4L);
        final GLContextIdentity unrelated = new GLContextIdentity(3L, 13L, 4L);

        assertFalse(DisplayListCompilationOwnership.includes(active, List.of(), unrelated));
    }

    @Test
    void contextFromAnotherShareGroupDoesNotOwnTheCompilation() {
        final GLContextIdentity active = new GLContextIdentity(1L, 11L, 4L);
        final GLContextIdentity unrelated = new GLContextIdentity(2L, 21L, 5L);

        assertFalse(DisplayListCompilationOwnership.includes(active, List.of(), unrelated));
    }

    @Test
    void onlyTheSameContextLifetimeCanNestIntoAnActiveCompilation() {
        final GLContextIdentity active = new GLContextIdentity(1L, 11L, 4L);
        final GLContextIdentity rebound = new GLContextIdentity(1L, 11L, 4L);
        final GLContextIdentity sharedSibling = new GLContextIdentity(2L, 12L, 4L);

        assertTrue(DisplayListCompilationOwnership.mayNest(active, rebound),
            "releasing and rebinding the same native context keeps its compile state");
        assertFalse(DisplayListCompilationOwnership.mayNest(active, sharedSibling),
            "a different context in the share group cannot inherit another context's compiler state");
    }
}
