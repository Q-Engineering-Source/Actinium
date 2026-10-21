package com.gtnewhorizons.angelica.glsm;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CowStateSetTest {

    @Test
    void storesStackIdsAcrossMultipleBitsetWords() {
        final long[] members = new long[3];
        members[0] = 1L << 7;
        members[1] = 1L << 0;
        members[2] = 1L << 5;

        final StateSet set = new StateSet(GL11.GL_DEPTH_BUFFER_BIT, members, StateSet.R_DEPTH,
                StateSet.B_TRANSFORM, false);

        assertTrue(set.contains(7));
        assertTrue(set.contains(64));
        assertTrue(set.contains(133));
        assertFalse(set.contains(-1));
        assertFalse(set.contains(132));
        assertEquals(StateSet.R_DEPTH, set.restore);
        assertEquals(StateSet.B_TRANSFORM, set.bump);
        assertFalse(set.blendSnapshot);
    }

    @Test
    void colorBufferMaskCarriesTheBackendAndGenerationContract() {
        final StateSet set = new StateSet(GL11.GL_COLOR_BUFFER_BIT, new long[0],
                StateSet.R_BLEND | StateSet.R_COLOR_MASK | StateSet.R_CLEAR_COLOR | StateSet.R_DRAW_BUFFER | StateSet.R_LOGIC_OP,
                StateSet.B_FRAG_COLORBUF, true);

        assertTrue((set.restore & StateSet.R_BLEND) != 0);
        assertTrue((set.restore & StateSet.R_COLOR_MASK) != 0);
        assertTrue((set.restore & StateSet.R_CLEAR_COLOR) != 0);
        assertEquals(StateSet.B_FRAG_COLORBUF, set.bump);
        assertTrue(set.blendSnapshot);
    }
}
