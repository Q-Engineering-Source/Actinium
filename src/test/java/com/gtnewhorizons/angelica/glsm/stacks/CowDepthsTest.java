package com.gtnewhorizons.angelica.glsm.stacks;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CowDepthsTest {

    @Test
    void claimsAtFirstMutationOnlyWhenTheActiveMaskSavesTheStack() {
        final CowDepths depths = new CowDepths();

        assertEquals(-1, depths.claimIfNeeded(0, true), "the base state has no attrib scope to snapshot");
        assertEquals(-1, depths.claimIfNeeded(1, false), "a state omitted by the mask must not be saved");
        assertTrue(depths.isEmpty());

        assertEquals(0, depths.claimIfNeeded(1, true));
        assertEquals(-1, depths.claimIfNeeded(1, true), "repeated mutation at one depth reuses its first snapshot");
        assertEquals(1, depths.claimIfNeeded(2, true), "a nested saving scope owns a separate snapshot");
    }

    @Test
    void promotesAnInnerSnapshotWhenTheOuterScopeMustStillRestoreIt() {
        final CowDepths depths = new CowDepths();
        assertEquals(0, depths.claim(2));

        assertEquals(0, depths.discard(2));
        assertTrue(depths.claimedAt(1));
        assertEquals(0, depths.pop());
        assertEquals(-1, depths.pop());
        assertTrue(depths.isEmpty());
    }

    @Test
    void dropsAnInnerSnapshotWhenItsParentAlreadyHasAnOlderOne() {
        final CowDepths depths = new CowDepths();
        assertEquals(0, depths.claim(1));
        assertEquals(1, depths.claim(2));

        assertEquals(-1, depths.discard(2));
        assertTrue(depths.claimedAt(1));
        assertEquals(0, depths.pop());
        assertTrue(depths.isEmpty());
    }

    @Test
    void rejectsSnapshotOverflowInsteadOfWritingPastItsSlots() {
        final CowDepths depths = new CowDepths();
        for (int i = 0; i < GLStateManager.MAX_ATTRIB_STACK_DEPTH; i++) {
            depths.claimUnconditional();
        }

        assertThrows(IllegalStateException.class, depths::claimUnconditional);
        assertEquals(GLStateManager.MAX_ATTRIB_STACK_DEPTH, depths.top() + 1);
        assertFalse(depths.isEmpty());
    }
}
