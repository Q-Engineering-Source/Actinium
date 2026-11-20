package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.recording.CompiledDisplayList;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayListIdLifecycleIntegrationTest extends GLStateManagerIntegrationFixture {
    @Test
    void compiledExplicitNameIsSkippedAndDeletingItMakesItReusable() {
        final int range = 1 << 22;
        final int base = GLSMDraws.glGenLists(range);
        final int explicit = base + range;
        final int following = explicit + 1;
        int generated = 0;
        int reused = 0;
        try {
            GLSMDraws.glNewList(explicit, GL11.GL_COMPILE);
            assertFalse(GLSMDraws.glIsList(explicit));
            GLSMDraws.glEndList();
            assertTrue(GLSMDraws.glIsList(explicit));
            assertNotNull(GLSMDraws.getDisplayList(explicit));

            generated = GLSMDraws.glGenLists(2);
            assertEquals(following, generated);
            GLSMDraws.glDeleteLists(explicit, 1);
            assertNull(GLSMDraws.getDisplayList(explicit));
            assertFalse(GLSMDraws.glIsList(explicit));
            reused = GLSMDraws.glGenLists(1);
            assertEquals(explicit, reused);
        } finally {
            if (DisplayListManager.isRecording()) DisplayListManager.abortCompilation();
            GLSMDraws.glDeleteLists(base, range);
            GLSMDraws.glDeleteLists(explicit, 1);
            GLSMDraws.glDeleteLists(generated, 2);
            GLSMDraws.glDeleteLists(reused, 1);
        }
    }

    @Test
    void abortKeepsTheOldListAndDoesNotReserveANewName() {
        final int existing = Integer.MAX_VALUE - 1;
        final int unfinished = Integer.MAX_VALUE;
        try {
            GLSMDraws.glNewList(existing, GL11.GL_COMPILE);
            GLSMMatrices.glTranslatef(1, 0, 0);
            GLSMDraws.glEndList();
            final var original = GLSMDraws.getDisplayList(existing);
            assertNotNull(original);

            GLSMDraws.glNewList(existing, GL11.GL_COMPILE);
            GLSMDraws.glNewList(unfinished, GL11.GL_COMPILE);
            DisplayListManager.abortCompilation();
            assertSame(original, GLSMDraws.getDisplayList(existing));
            assertTrue(GLSMDraws.glIsList(existing));
            assertFalse(GLSMDraws.glIsList(unfinished));
            assertNull(GLSMDraws.getDisplayList(unfinished));

            GLSMDraws.glNewList(existing, GL11.GL_COMPILE);
            GLSMDraws.glEndList();
            assertSame(CompiledDisplayList.EMPTY, GLSMDraws.getDisplayList(existing));
            assertTrue(GLSMDraws.glIsList(existing));

            GLSMDraws.glNewList(unfinished, GL11.GL_COMPILE);
            GLSMDraws.glEndList();
            assertTrue(GLSMDraws.glIsList(unfinished));
            GLSMDraws.glDeleteLists(existing, 3);
            assertFalse(GLSMDraws.glIsList(existing));
            assertFalse(GLSMDraws.glIsList(unfinished));
            assertNull(GLSMDraws.getDisplayList(existing));
            assertNull(GLSMDraws.getDisplayList(unfinished));
        } finally {
            if (DisplayListManager.isRecording()) DisplayListManager.abortCompilation();
            GLSMDraws.glDeleteLists(existing, 2);
        }
    }

}
