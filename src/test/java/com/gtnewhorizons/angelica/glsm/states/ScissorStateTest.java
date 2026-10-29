package com.gtnewhorizons.angelica.glsm.states;

import com.gtnewhorizons.angelica.glsm.stacks.ScissorStateStack;
import org.junit.jupiter.api.Test;

import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScissorStateTest {
    @Test
    void nestedScopesRestoreIndependentRectangles() {
        final ScissorStateStack state = new ScissorStateStack();
        state.setBox(-2, 3, 640, 480);
        final ScissorState original = state.copy();
        state.push();
        state.setBox(10, 20, 30, 40);
        final ScissorState intermediate = state.copy();
        state.push();
        state.setBox(1, 2, 0, 0);

        state.pop();
        assertTrue(intermediate.sameAs(state));
        assertFalse(state.isEmpty());
        state.pop();
        assertTrue(original.sameAs(state));
        assertTrue(state.isEmpty());
        assertThrows(IllegalStateException.class, state::pop);
    }

    @Test
    void queriesRespectBufferPositionWithoutAdvancingIt() {
        final ScissorState state = new ScissorState();
        state.setBox(-5, 8, 20, 30);
        final IntBuffer ints = IntBuffer.allocate(6);
        final FloatBuffer floats = FloatBuffer.allocate(6);
        final DoubleBuffer doubles = DoubleBuffer.allocate(6);
        ints.put(0, 77).position(2);
        floats.put(0, 77).position(2);
        doubles.put(0, 77).position(2);

        state.get(ints);
        state.get(floats);
        state.get(doubles);

        final int[] box = {-5, 8, 20, 30};
        for (int i = 0; i < box.length; i++) {
            assertEquals(box[i], ints.get(i + 2));
            assertEquals(box[i], floats.get(i + 2));
            assertEquals(box[i], doubles.get(i + 2));
        }
        assertEquals(77, ints.get(0));
        assertEquals(77, floats.get(0));
        assertEquals(77, doubles.get(0));
        assertEquals(2, ints.position());
        assertEquals(2, floats.position());
        assertEquals(2, doubles.position());
    }
}
