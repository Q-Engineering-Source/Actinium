package com.gtnewhorizons.angelica.glsm.states;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.nio.DoubleBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipPlaneStateStackTest {

    private static double[] eyePlane(ClipPlaneState state, int index) {
        final DoubleBuffer buf = DoubleBuffer.allocate(4);
        state.getEyePlane(index, buf);
        return new double[] {buf.get(0), buf.get(1), buf.get(2), buf.get(3)};
    }

    @Test
    void restoresTheAlreadyTransformedEyePlaneEquation() {
        final ClipPlaneState state = new ClipPlaneState(0);
        final int slot = state.cowDepths().claimUnconditional();
        state.captureSlot(slot);

        state.setPlane(0, 1.0, 2.0, 3.0, 4.0, new Matrix4f().translation(5.0f, 6.0f, 7.0f));
        assertTrue(state.slotChanged(slot));
        state.restoreSlot(slot);

        final DoubleBuffer equation = DoubleBuffer.allocate(4);
        state.getEyePlane(0, equation);
        assertEquals(0.0, equation.get(0));
        assertEquals(0.0, equation.get(1));
        assertEquals(0.0, equation.get(2));
        assertEquals(0.0, equation.get(3));
        assertFalse(state.slotChanged(slot));
        assertEquals(slot, state.cowDepths().pop());
    }

    @Test
    void aScopeWithoutClipPlaneStateDoesNotClaimASnapshot() {
        final ClipPlaneState state = new ClipPlaneState(0);

        assertEquals(-1, state.cowDepths().claimIfNeeded(1, false));
        assertTrue(state.cowDepths().isEmpty());
    }

    @Test
    void interleavedClipPlaneUpdatesKeepTheirModelViewTransformsSeparate() {
        final ClipPlaneState firstContext = new ClipPlaneState(0);
        final ClipPlaneState secondContext = new ClipPlaneState(1);

        firstContext.setPlane(0, 1.0, 0.0, 0.0, -5.0, new Matrix4f().translation(2.0f, 0.0f, 0.0f));
        secondContext.setPlane(1, 0.0, 1.0, 0.0, -3.0, new Matrix4f().translation(0.0f, -4.0f, 0.0f));
        firstContext.setPlane(2, 0.0, 0.0, 1.0, -2.0, new Matrix4f().translation(0.0f, 0.0f, 5.0f));

        final double[] firstX = eyePlane(firstContext, 0);
        final double[] firstZ = eyePlane(firstContext, 2);
        final double[] secondY = eyePlane(secondContext, 1);
        assertEquals(-7.0, firstX[3], 1.0e-6);
        assertEquals(-7.0, firstZ[3], 1.0e-6);
        assertEquals(1.0, secondY[3], 1.0e-6);
    }
}
