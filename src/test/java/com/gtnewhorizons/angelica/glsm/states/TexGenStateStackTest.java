package com.gtnewhorizons.angelica.glsm.states;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TexGenStateStackTest {

    @Test
    void snapshotsAndRestoresModesAndBothPlaneSpaces() {
        final TexGenState state = new TexGenState(0, 0);
        final int slot = state.cowDepths().claimUnconditional();
        state.captureSlot(slot);

        state.setMode(GL11.GL_S, GL11.GL_EYE_LINEAR);
        state.setObjectPlane(GL11.GL_T, new float[] {2.0f, 3.0f, 4.0f, 5.0f});
        state.setEyePlane(GL11.GL_Q, new float[] {1.0f, 2.0f, 3.0f, 4.0f}, new Matrix4f().identity());

        assertTrue(state.slotChanged(slot));
        state.restoreSlot(slot);

        assertEquals(0, state.getMode(GL11.GL_S));
        assertArrayEquals(new float[] {0.0f, 1.0f, 0.0f, 0.0f}, state.getObjectPlane(GL11.GL_T), 0.0f);
        assertArrayEquals(new float[] {0.0f, 0.0f, 0.0f, 0.0f}, state.getEyePlane(GL11.GL_Q), 0.0f);
        assertFalse(state.slotChanged(slot));
        assertEquals(slot, state.cowDepths().pop());
    }

    @Test
    void changedBackTexgenStateDoesNotNeedAValueRestore() {
        final TexGenState state = new TexGenState(0, 0);
        final int slot = state.cowDepths().claimUnconditional();
        state.captureSlot(slot);

        state.setMode(GL11.GL_R, GL11.GL_OBJECT_LINEAR);
        state.setMode(GL11.GL_R, 0);

        assertFalse(state.slotChanged(slot));
        assertEquals(slot, state.cowDepths().pop());
    }

    @Test
    void interleavedEyePlaneUpdatesUseEachTextureUnitsOwnTransformScratch() {
        final TexGenState firstUnit = new TexGenState(0, 0);
        final TexGenState secondUnit = new TexGenState(1, 1);

        firstUnit.setEyePlane(GL11.GL_S, new float[] {1.0f, 0.0f, 0.0f, -5.0f}, new Matrix4f().translation(2.0f, 0.0f, 0.0f));
        secondUnit.setEyePlane(GL11.GL_T, new float[] {0.0f, 1.0f, 0.0f, -3.0f}, new Matrix4f().translation(0.0f, -4.0f, 0.0f));
        firstUnit.setEyePlane(GL11.GL_Q, new float[] {0.0f, 0.0f, 1.0f, -2.0f}, new Matrix4f().translation(0.0f, 0.0f, 5.0f));

        assertArrayEquals(new float[] {1.0f, 0.0f, 0.0f, -7.0f}, firstUnit.getEyePlane(GL11.GL_S), 0.0f);
        assertArrayEquals(new float[] {0.0f, 0.0f, 1.0f, -7.0f}, firstUnit.getEyePlane(GL11.GL_Q), 0.0f);
        assertArrayEquals(new float[] {0.0f, 1.0f, 0.0f, 1.0f}, secondUnit.getEyePlane(GL11.GL_T), 0.0f);
    }
}
