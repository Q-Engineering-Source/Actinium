package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.recording.CompiledDisplayList;
import com.gtnewhorizons.angelica.glsm.recording.GLCommand;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayListStateSetIntegrationTest extends GLStateManagerIntegrationFixture {
    @Test
    void compiledStateSetBracketRestoresMaskSubsetAndDriverState() {
        final int list = GLStateManager.glGenLists(1);
        final int entryDepth = GLStateManager.getAttribDepth();
        try {
            GLStateManager.glNewList(list, GL11.GL_COMPILE);
            try {
                final int token = GLStateManager.pushState(StateSet.BLEND);
                GLStateManager.enableBlend();
                GLStateManager.tryBlendFuncSeparate(
                    GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
                GLStateManager.popStateTo(token);
            } finally {
                if (DisplayListManager.isRecording()) {
                    GLStateManager.glEndList();
                }
            }

            final CompiledDisplayList compiled = DisplayListManager.getDisplayList(list);
            assertNotNull(compiled);
            assertEquals(2, compiled.getCommandCounts().getOrDefault(GLCommand.COMPLEX_REF, 0));
            assertEquals(entryDepth, GLStateManager.getAttribDepth());

            GLStateManager.tryBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO);
            GLStateManager.disableBlend();
            GLStateManager.glCallList(list);

            assertFalse(GLStateManager.getBlendMode().isEnabled());
            assertEquals(GL11.GL_ONE, GLStateManager.getBlendState().getSrcRgb());
            assertEquals(GL11.GL_ZERO, GLStateManager.getBlendState().getDstRgb());
            assertEquals(GL11.GL_ONE, GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB));
            assertEquals(GL11.GL_ZERO, GL11.glGetInteger(GL14.GL_BLEND_DST_RGB));
            assertFalse(GL11.glIsEnabled(GL11.GL_BLEND));
            assertEquals(entryDepth, GLStateManager.getAttribDepth());
        } finally {
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLStateManager.popState();
            }
            GLStateManager.glDeleteLists(list, 1);
            GLStateManager.disableBlend();
            GLStateManager.tryBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO);
        }
    }

    @Test
    void nestedCompilationKeepsIndependentStateScopes() {
        final int parent = GLStateManager.glGenLists(1);
        final int child = GLStateManager.glGenLists(1);
        final int entryDepth = GLStateManager.getAttribDepth();
        try {
            GLStateManager.enableBlend();
            GLStateManager.glEnable(GL11.GL_CULL_FACE);

            GLStateManager.glNewList(parent, GL11.GL_COMPILE);
            final int parentToken = GLStateManager.pushState(StateSet.BLEND);
            GLStateManager.glNewList(child, GL11.GL_COMPILE);
            GLStateManager.pushState(StateSet.CULL);
            GLStateManager.glDisable(GL11.GL_CULL_FACE);
            GLStateManager.glEndList();

            GLStateManager.disableBlend();
            GLStateManager.popStateTo(parentToken);
            GLStateManager.glEndList();

            assertEquals(2, DisplayListManager.getDisplayList(parent).getCommandCounts().getOrDefault(GLCommand.COMPLEX_REF, 0));
            assertEquals(2, DisplayListManager.getDisplayList(child).getCommandCounts().getOrDefault(GLCommand.COMPLEX_REF, 0));
            assertEquals(entryDepth, GLStateManager.getAttribDepth());

            GLStateManager.glCallList(parent);
            assertTrue(GLStateManager.getBlendMode().isEnabled());
            GLStateManager.glCallList(child);
            assertTrue(GLStateManager.getCullState().isEnabled());
        } finally {
            while (DisplayListManager.isRecording()) {
                GLStateManager.popStateTo(entryDepth);
                GLStateManager.glEndList();
            }
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLStateManager.popState();
            }
            GLStateManager.glDeleteLists(parent, 1);
            GLStateManager.glDeleteLists(child, 1);
            GLStateManager.disableBlend();
            GLStateManager.glDisable(GL11.GL_CULL_FACE);
        }
    }

    @Test
    void virtualDepthOverflowCleansUpSuccessfulPushes() {
        final int list = GLStateManager.glGenLists(1);
        final int other = GLStateManager.glGenLists(1);
        final int entryDepth = GLStateManager.getAttribDepth();
        try {
            GLStateManager.glNewList(list, GL11.GL_COMPILE);
            for (int i = 0; i < 32; i++) {
                GLStateManager.pushState(StateSet.CUTOUT);
            }
            assertThrows(IllegalStateException.class, () -> GLStateManager.pushState(StateSet.CUTOUT));
            GLStateManager.glEndList();

            assertEquals(64, DisplayListManager.getDisplayList(list).getCommandCounts().getOrDefault(GLCommand.COMPLEX_REF, 0));
            assertEquals(entryDepth, GLStateManager.getAttribDepth());
            assertDoesNotThrow(() -> {
                GLStateManager.glNewList(other, GL11.GL_COMPILE);
                GLStateManager.glEndList();
            });
        } finally {
            while (DisplayListManager.isRecording()) {
                GLStateManager.popStateTo(entryDepth);
                GLStateManager.glEndList();
            }
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLStateManager.popState();
            }
            GLStateManager.glDeleteLists(list, 1);
            GLStateManager.glDeleteLists(other, 1);
        }
    }

    @Test
    void compileAndExecuteAttribCommandsAreNotDuplicated() {
        final int list = GLStateManager.glGenLists(1);
        final int entryDepth = GLStateManager.getAttribDepth();
        try {
            GLStateManager.glNewList(list, GL11.GL_COMPILE_AND_EXECUTE);
            GLStateManager.glPushAttrib(GL11.GL_ENABLE_BIT);
            GLStateManager.enableBlend();
            GLStateManager.glPopAttrib();
            GLStateManager.glEndList();

            final CompiledDisplayList compiled = DisplayListManager.getDisplayList(list);
            assertNotNull(compiled);
            assertEquals(1, compiled.getCommandCounts().getOrDefault(GLCommand.PUSH_ATTRIB, 0));
            assertEquals(1, compiled.getCommandCounts().getOrDefault(GLCommand.POP_ATTRIB, 0));
            assertEquals(0, compiled.getCommandCounts().getOrDefault(GLCommand.COMPLEX_REF, 0));
            assertEquals(entryDepth, GLStateManager.getAttribDepth());
        } finally {
            while (DisplayListManager.isRecording()) {
                GLStateManager.popStateTo(entryDepth);
                GLStateManager.glEndList();
            }
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLStateManager.popState();
            }
            GLStateManager.glDeleteLists(list, 1);
            GLStateManager.disableBlend();
        }
    }

    @Test
    void compileAndExecuteChildRestoresLiveStateInsideCompileParent() {
        final int parent = GLStateManager.glGenLists(1);
        final int child = GLStateManager.glGenLists(1);
        final int entryDepth = GLStateManager.getAttribDepth();
        try {
            GLStateManager.enableBlend();
            GLStateManager.glEnable(GL11.GL_CULL_FACE);

            GLStateManager.glNewList(parent, GL11.GL_COMPILE);
            final int parentToken = GLStateManager.pushState(StateSet.BLEND);
            GLStateManager.glNewList(child, GL11.GL_COMPILE_AND_EXECUTE);
            final int childToken = GLStateManager.pushState(StateSet.CULL);
            GLStateManager.glDisable(GL11.GL_CULL_FACE);
            GLStateManager.popStateTo(childToken);
            GLStateManager.glEndList();

            assertTrue(GLStateManager.getCullState().isEnabled());
            assertTrue(GL11.glIsEnabled(GL11.GL_CULL_FACE));
            assertEquals(entryDepth, GLStateManager.getAttribDepth());

            GLStateManager.disableBlend();
            GLStateManager.popStateTo(parentToken);
            GLStateManager.glEndList();

            assertEquals(2, DisplayListManager.getDisplayList(parent).getCommandCounts().getOrDefault(GLCommand.COMPLEX_REF, 0));
            assertEquals(2, DisplayListManager.getDisplayList(child).getCommandCounts().getOrDefault(GLCommand.COMPLEX_REF, 0));
            assertEquals(entryDepth, GLStateManager.getAttribDepth());

            GLStateManager.glCallList(child);
            assertTrue(GLStateManager.getCullState().isEnabled());
            GLStateManager.glCallList(parent);
            assertTrue(GLStateManager.getBlendMode().isEnabled());
        } finally {
            while (DisplayListManager.isRecording()) {
                GLStateManager.popStateTo(entryDepth);
                GLStateManager.glEndList();
            }
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLStateManager.popState();
            }
            GLStateManager.glDeleteLists(parent, 1);
            GLStateManager.glDeleteLists(child, 1);
            GLStateManager.disableBlend();
            GLStateManager.glDisable(GL11.GL_CULL_FACE);
        }
    }

    @Test
    void compileAndExecuteEndListClosesUnbalancedLiveScope() {
        final int list = GLStateManager.glGenLists(1);
        final int entryDepth = GLStateManager.getAttribDepth();
        try {
            GLStateManager.disableBlend();
            GLStateManager.glNewList(list, GL11.GL_COMPILE_AND_EXECUTE);
            GLStateManager.pushState(StateSet.BLEND);
            GLStateManager.enableBlend();
            GLStateManager.glEndList();

            assertEquals(entryDepth, GLStateManager.getAttribDepth());
            assertFalse(GLStateManager.getBlendMode().isEnabled());
            assertFalse(GL11.glIsEnabled(GL11.GL_BLEND));
            assertEquals(2, DisplayListManager.getDisplayList(list).getCommandCounts().getOrDefault(GLCommand.COMPLEX_REF, 0));
        } finally {
            while (DisplayListManager.isRecording()) {
                GLStateManager.popStateTo(entryDepth);
                GLStateManager.glEndList();
            }
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLStateManager.popState();
            }
            GLStateManager.glDeleteLists(list, 1);
            GLStateManager.disableBlend();
        }
    }

    @Test
    void nestedCompileAndExecuteEndListRestoresParentLiveScope() {
        final int parent = GLStateManager.glGenLists(1);
        final int child = GLStateManager.glGenLists(1);
        final int entryDepth = GLStateManager.getAttribDepth();
        try {
            GLStateManager.disableBlend();
            GLStateManager.glEnable(GL11.GL_CULL_FACE);

            GLStateManager.glNewList(parent, GL11.GL_COMPILE_AND_EXECUTE);
            GLStateManager.pushState(StateSet.BLEND);
            GLStateManager.enableBlend();
            GLStateManager.glNewList(child, GL11.GL_COMPILE_AND_EXECUTE);
            GLStateManager.pushState(StateSet.CULL);
            GLStateManager.glDisable(GL11.GL_CULL_FACE);
            GLStateManager.glEndList();

            assertEquals(entryDepth + 1, GLStateManager.getAttribDepth());
            assertTrue(GLStateManager.getCullState().isEnabled());
            assertTrue(GL11.glIsEnabled(GL11.GL_CULL_FACE));

            GLStateManager.glEndList();
            assertEquals(entryDepth, GLStateManager.getAttribDepth());
            assertFalse(GLStateManager.getBlendMode().isEnabled());
            assertFalse(GL11.glIsEnabled(GL11.GL_BLEND));
            assertEquals(2, DisplayListManager.getDisplayList(parent).getCommandCounts().getOrDefault(GLCommand.COMPLEX_REF, 0));
            assertEquals(2, DisplayListManager.getDisplayList(child).getCommandCounts().getOrDefault(GLCommand.COMPLEX_REF, 0));
        } finally {
            while (DisplayListManager.isRecording()) {
                GLStateManager.popStateTo(entryDepth);
                GLStateManager.glEndList();
            }
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLStateManager.popState();
            }
            GLStateManager.glDeleteLists(parent, 1);
            GLStateManager.glDeleteLists(child, 1);
            GLStateManager.disableBlend();
            GLStateManager.glDisable(GL11.GL_CULL_FACE);
        }
    }

    @Test
    void compileAndExecuteOverflowRestoresEverySuccessfulLivePush() {
        final int list = GLStateManager.glGenLists(1);
        final int entryDepth = GLStateManager.getAttribDepth();
        try {
            GLStateManager.disableBlend();
            GLStateManager.glNewList(list, GL11.GL_COMPILE_AND_EXECUTE);
            for (int i = 0; i < 32; i++) {
                GLStateManager.pushState(StateSet.BLEND);
                if (i == 0) GLStateManager.enableBlend();
            }
            assertThrows(IllegalStateException.class, () -> GLStateManager.pushState(StateSet.BLEND));
            GLStateManager.glEndList();

            assertEquals(entryDepth, GLStateManager.getAttribDepth());
            assertFalse(GLStateManager.getBlendMode().isEnabled());
            assertFalse(GL11.glIsEnabled(GL11.GL_BLEND));
            assertEquals(64, DisplayListManager.getDisplayList(list).getCommandCounts().getOrDefault(GLCommand.COMPLEX_REF, 0));

            GLStateManager.glCallList(list);
            assertEquals(entryDepth, GLStateManager.getAttribDepth());
            assertFalse(GLStateManager.getBlendMode().isEnabled());
            assertFalse(GL11.glIsEnabled(GL11.GL_BLEND));
        } finally {
            while (DisplayListManager.isRecording()) {
                GLStateManager.popStateTo(entryDepth);
                GLStateManager.glEndList();
            }
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLStateManager.popState();
            }
            GLStateManager.glDeleteLists(list, 1);
            GLStateManager.disableBlend();
        }
    }

    @Test
    void compileChildPoppingParentScopeUpdatesOwner() {
        final int parent = GLStateManager.glGenLists(1);
        final int child = GLStateManager.glGenLists(1);
        final int entryDepth = GLStateManager.getAttribDepth();
        try {
            GLStateManager.disableBlend();
            GLStateManager.glDisable(GL11.GL_CULL_FACE);
            GLStateManager.pushState(StateSet.CULL);
            GLStateManager.glEnable(GL11.GL_CULL_FACE);

            GLStateManager.glNewList(parent, GL11.GL_COMPILE_AND_EXECUTE);
            final int parentToken = GLStateManager.pushState(StateSet.BLEND);
            GLStateManager.enableBlend();
            GLStateManager.glNewList(child, GL11.GL_COMPILE);
            GLStateManager.popStateTo(parentToken);

            assertEquals(entryDepth + 1, GLStateManager.getAttribDepth());
            assertFalse(GLStateManager.getBlendMode().isEnabled());
            assertFalse(GL11.glIsEnabled(GL11.GL_BLEND));
            assertTrue(GLStateManager.getCullState().isEnabled());
            assertTrue(GL11.glIsEnabled(GL11.GL_CULL_FACE));

            GLStateManager.glEndList();
            GLStateManager.glEndList();

            assertEquals(entryDepth + 1, GLStateManager.getAttribDepth());
            assertTrue(GLStateManager.getCullState().isEnabled());
            assertTrue(GL11.glIsEnabled(GL11.GL_CULL_FACE));
            assertFalse(GLStateManager.getBlendMode().isEnabled());
            assertFalse(GL11.glIsEnabled(GL11.GL_BLEND));

            GLStateManager.glCallList(parent);
            assertEquals(entryDepth + 1, GLStateManager.getAttribDepth());
            assertTrue(GLStateManager.getCullState().isEnabled());
            assertTrue(GL11.glIsEnabled(GL11.GL_CULL_FACE));
            assertFalse(GLStateManager.getBlendMode().isEnabled());
            assertFalse(GL11.glIsEnabled(GL11.GL_BLEND));
        } finally {
            while (DisplayListManager.isRecording()) {
                GLStateManager.popStateTo(entryDepth);
                GLStateManager.glEndList();
            }
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLStateManager.popState();
            }
            GLStateManager.glDeleteLists(parent, 1);
            GLStateManager.glDeleteLists(child, 1);
            GLStateManager.disableBlend();
            GLStateManager.glDisable(GL11.GL_CULL_FACE);
        }
    }
}
