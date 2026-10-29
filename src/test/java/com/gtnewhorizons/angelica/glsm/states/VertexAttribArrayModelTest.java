package com.gtnewhorizons.angelica.glsm.states;

import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VertexAttribArrayModelTest {

    @Test
    void newlyConstructedModelAcceptsConventionalClientState() {
        final VertexAttribState state = new VertexAttribState(76L, new BufferGenerationTracker(76L));

        state.setConventionalPointer(2, 3, GL11.GL_FLOAT, false, 12, ByteBuffer.allocateDirect(48), 0);
        state.setConventionalEnabledForContext(2, true);

        assertTrue(state.getConventionalAttrib(2).enabled);
        assertTrue(state.getAttrib(2).enabled);
    }

    @Test
    void resetKeepsTheActiveVertexArrayModelUsable() {
        final VertexAttribState state = new VertexAttribState(75L, new BufferGenerationTracker(75L));
        state.initForContext(5);
        state.setConventionalEnabledForContext(2, true);

        state.resetState();
        state.setConventionalEnabledForContext(2, true);

        assertTrue(state.getConventionalAttrib(2).enabled);
        assertTrue(state.getAttrib(2).enabled);
    }

    @Test
    void replacingTheDefaultVertexArrayPreservesEarlyConventionalState() {
        final VertexAttribState state = new VertexAttribState(74L, new BufferGenerationTracker(74L));
        final ByteBuffer pointer = ByteBuffer.allocateDirect(96);
        state.setConventionalPointer(3, 4, GL11.GL_FLOAT, false, 16, pointer, 0);
        state.setConventionalEnabledForContext(3, true);

        state.initForContext(12);

        assertEquals(4, state.getConventionalAttrib(3).size);
        assertTrue(state.getConventionalAttrib(3).enabled);
        assertEquals(96, state.getConventionalAttrib(3).clientPointer.capacity());
        assertEquals(4, state.getAttrib(3).size);
        state.bindVertexArray(0);
        assertTrue(state.getConventionalAttrib(3).enabled);
        state.bindVertexArray(12);
        assertTrue(state.getConventionalAttrib(3).enabled);
    }

    @Test
    void replacingTheShareGroupClearsStateAndKeepsTheModelUsable() {
        final VertexAttribState state = new VertexAttribState(73L, new BufferGenerationTracker(73L));
        state.setConventionalEnabledForContext(2, true);

        state.resetForShareGroup(72L, new BufferGenerationTracker(72L));
        state.setConventionalEnabledForContext(2, true);

        assertTrue(state.getConventionalAttrib(2).enabled);
        assertTrue(state.getAttrib(2).enabled);
    }

    @Test
    void genericConventionalAndEffectivePointersRemainIndependent() {
        final VertexAttribState state = new VertexAttribState(77L, new BufferGenerationTracker(77L));
        state.initForContext(1);
        final ByteBuffer genericPointer = ByteBuffer.allocateDirect(128);
        final ByteBuffer conventionalPointer = ByteBuffer.allocateDirect(128);

        state.setPointer(2, 3, GL11.GL_FLOAT, false, 12, genericPointer, 0);
        state.setEnabledForContext(2, true);
        state.setConventionalPointer(2, 4, GL11.GL_UNSIGNED_BYTE, true, 16, conventionalPointer, 0);
        state.setConventionalEnabledForContext(2, true);

        assertEquals(3, state.getGenericAttrib(2).size);
        assertEquals(GL11.GL_FLOAT, state.getGenericAttrib(2).type);
        assertTrue(state.getGenericAttrib(2).enabled);
        assertEquals(4, state.getConventionalAttrib(2).size);
        assertEquals(GL11.GL_UNSIGNED_BYTE, state.getConventionalAttrib(2).type);
        assertTrue(state.getConventionalAttrib(2).enabled);
        assertEquals(4, state.getAttrib(2).size);
        assertEquals(GL11.GL_UNSIGNED_BYTE, state.getAttrib(2).type);

        state.setConventionalEnabledForContext(2, false);
        assertTrue(state.getGenericAttrib(2).enabled);
        assertFalse(state.getConventionalAttrib(2).enabled);
        assertFalse(state.getAttrib(2).enabled);

        final VertexAttribState.Snapshot snapshot = state.snapshot(
            new GLContextIdentity(1L, 0xA11CEL, 77L), 1, 0, 0, 0);
        state.setPointer(2, 1, GL11.GL_BYTE, false, 1, ByteBuffer.allocateDirect(32), 0);
        state.restoreModel(snapshot, false);
        assertEquals(3, state.getGenericAttrib(2).size);
        assertEquals(4, state.getConventionalAttrib(2).size);
        assertEquals(4, state.getAttrib(2).size);
    }

    @Test
    void integerFormatBindingAndBothDivisorFormsAreCaptured() {
        final VertexAttribState state = new VertexAttribState(78L, new BufferGenerationTracker(78L));
        state.initForContext(4);
        state.setIntegerPointerForContext(5, 2, GL11.GL_INT, 8, 0L, 0, ByteBuffer.allocateDirect(96));
        state.setDivisorForContext(5, 3);
        state.bindVertexBufferForContext(2, 17, 64L, 24);
        state.setVertexBindingDivisorForContext(2, 7);
        state.setFormatForContext(6, 3, GL11.GL_FLOAT, false, false, 12);
        state.setBindingForContext(6, 2);

        final VertexAttribState.Attrib integer = state.getGenericAttrib(5);
        assertTrue(integer.integer);
        assertEquals(3, integer.divisor);
        final VertexAttribState.Attrib bound = state.getAttrib(6);
        assertEquals(17, bound.vboId);
        assertEquals(76L, bound.offset);
        assertEquals(24, bound.stride);
        assertEquals(7, bound.divisor);

        state.setDivisorForContext(6, 5);
        assertEquals(5, state.getAttrib(6).divisor);
        assertEquals(5, state.getGenericAttrib(6).divisor);

        final GLContextIdentity identity = new GLContextIdentity(1L, 0xA11CEL, 78L);
        final VertexAttribState.Snapshot snapshot = state.snapshot(identity, 4, 0, 0, 0);
        assertTrue(snapshot.hasVertexBindingApi());
    }
}
