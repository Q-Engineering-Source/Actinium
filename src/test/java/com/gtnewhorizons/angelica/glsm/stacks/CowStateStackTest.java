package com.gtnewhorizons.angelica.glsm.stacks;

import com.gtnewhorizons.angelica.glsm.StateSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CowStateStackTest {

    @Test
    void restoresTheCapturedValueAndDispatchesByProductionStackType() {
        final IntegerStateStack stack = new IntegerStateStack(4, 2);
        final int slot = stack.cowDepths().claim(1);
        stack.captureSlot(slot);
        stack.setValue(9);

        final byte kind = CowDispatch.kindOf(stack);
        assertTrue(CowDispatch.slotChanged(kind, stack, slot));
        CowDispatch.restoreSlot(kind, stack, slot);

        assertEquals(4, stack.getValue());
        assertFalse(CowDispatch.slotChanged(kind, stack, slot));
        assertEquals(StateSet.R_DEPTH, new DepthStateStack(0).restoreBit());
        assertEquals(0, stack.cowDepths().pop());
        assertTrue(stack.isEmpty());
    }

    @Test
    void directPushAndPopUseCowSnapshotStorageAndCheckUnderflow() {
        final IntegerStateStack stack = new IntegerStateStack(3, 0);

        stack.push();
        stack.setValue(6);
        assertEquals(6, stack.getValue());
        stack.pop();
        assertEquals(3, stack.getValue());
        assertThrows(IllegalStateException.class, stack::pop);
    }

    @Test
    void noMutationDoesNotCreateAnyRestoreSlot() {
        final IntegerStateStack stack = new IntegerStateStack(1, 0);
        assertEquals(-1, stack.cowDepths().claimIfNeeded(1, false));
        assertTrue(stack.isEmpty());
        assertFalse(stack.topSlotChanged());
    }
}
