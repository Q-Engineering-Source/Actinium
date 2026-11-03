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
        final int list = GLSMDraws.glGenLists(1);
        final int entryDepth = GLStateManager.getAttribDepth();
        try {
            GLSMDraws.glNewList(list, GL11.GL_COMPILE);
            try {
                final int token = GLStateManager.pushState(StateSet.BLEND);
                GLStateManager.enableBlend();
                GLStateManager.tryBlendFuncSeparate(
                    GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
                GLStateManager.popStateTo(token);
            } finally {
                if (DisplayListManager.isRecording()) {
                    GLSMDraws.glEndList();
                }
            }

            final CompiledDisplayList compiled = DisplayListManager.getDisplayList(list);
            assertNotNull(compiled);
            assertEquals(2, compiled.getCommandCounts().getOrDefault(GLCommand.COMPLEX_REF, 0));
            assertEquals(entryDepth, GLStateManager.getAttribDepth());

            GLStateManager.tryBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO);
            GLStateManager.disableBlend();
            GLSMDraws.glCallList(list);

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
            GLSMDraws.glDeleteLists(list, 1);
            GLStateManager.disableBlend();
            GLStateManager.tryBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO);
        }
    }

    @Test
    void nestedCompilationKeepsIndependentStateScopes() {
        final int parent = GLSMDraws.glGenLists(1);
        final int child = GLSMDraws.glGenLists(1);
        final int entryDepth = GLStateManager.getAttribDepth();
        try {
            GLStateManager.enableBlend();
            GLSMEnable.glEnable(GL11.GL_CULL_FACE);

            GLSMDraws.glNewList(parent, GL11.GL_COMPILE);
            final int parentToken = GLStateManager.pushState(StateSet.BLEND);
            GLSMDraws.glNewList(child, GL11.GL_COMPILE);
            GLStateManager.pushState(StateSet.CULL);
            GLSMEnable.glDisable(GL11.GL_CULL_FACE);
            GLSMDraws.glEndList();

            GLStateManager.disableBlend();
            GLStateManager.popStateTo(parentToken);
            GLSMDraws.glEndList();

            assertEquals(2, DisplayListManager.getDisplayList(parent).getCommandCounts().getOrDefault(GLCommand.COMPLEX_REF, 0));
            assertEquals(2, DisplayListManager.getDisplayList(child).getCommandCounts().getOrDefault(GLCommand.COMPLEX_REF, 0));
            assertEquals(entryDepth, GLStateManager.getAttribDepth());

            GLSMDraws.glCallList(parent);
            assertTrue(GLStateManager.getBlendMode().isEnabled());
            GLSMDraws.glCallList(child);
            assertTrue(GLStateManager.getCullState().isEnabled());
        } finally {
            while (DisplayListManager.isRecording()) {
                GLStateManager.popStateTo(entryDepth);
                GLSMDraws.glEndList();
            }
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLStateManager.popState();
            }
            GLSMDraws.glDeleteLists(parent, 1);
            GLSMDraws.glDeleteLists(child, 1);
            GLStateManager.disableBlend();
            GLSMEnable.glDisable(GL11.GL_CULL_FACE);
        }
    }

    @Test
    void virtualDepthOverflowCleansUpSuccessfulPushes() {
        final int list = GLSMDraws.glGenLists(1);
        final int other = GLSMDraws.glGenLists(1);
        final int entryDepth = GLStateManager.getAttribDepth();
        try {
            GLSMDraws.glNewList(list, GL11.GL_COMPILE);
            for (int i = 0; i < 32; i++) {
                GLStateManager.pushState(StateSet.CUTOUT);
            }
            assertThrows(IllegalStateException.class, () -> GLStateManager.pushState(StateSet.CUTOUT));
            GLSMDraws.glEndList();

            assertEquals(64, DisplayListManager.getDisplayList(list).getCommandCounts().getOrDefault(GLCommand.COMPLEX_REF, 0));
            assertEquals(entryDepth, GLStateManager.getAttribDepth());
            assertDoesNotThrow(() -> {
                GLSMDraws.glNewList(other, GL11.GL_COMPILE);
                GLSMDraws.glEndList();
            });
        } finally {
            while (DisplayListManager.isRecording()) {
                GLStateManager.popStateTo(entryDepth);
                GLSMDraws.glEndList();
            }
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLStateManager.popState();
            }
            GLSMDraws.glDeleteLists(list, 1);
            GLSMDraws.glDeleteLists(other, 1);
        }
    }

    @Test
    void compileAndExecuteAttribCommandsAreNotDuplicated() {
        final int list = GLSMDraws.glGenLists(1);
        final int entryDepth = GLStateManager.getAttribDepth();
        try {
            GLSMDraws.glNewList(list, GL11.GL_COMPILE_AND_EXECUTE);
            GLSMAttribStack.glPushAttrib(GL11.GL_ENABLE_BIT);
            GLStateManager.enableBlend();
            GLSMAttribStack.glPopAttrib();
            GLSMDraws.glEndList();

            final CompiledDisplayList compiled = DisplayListManager.getDisplayList(list);
            assertNotNull(compiled);
            assertEquals(1, compiled.getCommandCounts().getOrDefault(GLCommand.PUSH_ATTRIB, 0));
            assertEquals(1, compiled.getCommandCounts().getOrDefault(GLCommand.POP_ATTRIB, 0));
            assertEquals(0, compiled.getCommandCounts().getOrDefault(GLCommand.COMPLEX_REF, 0));
            assertEquals(entryDepth, GLStateManager.getAttribDepth());
        } finally {
            while (DisplayListManager.isRecording()) {
                GLStateManager.popStateTo(entryDepth);
                GLSMDraws.glEndList();
            }
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLStateManager.popState();
            }
            GLSMDraws.glDeleteLists(list, 1);
            GLStateManager.disableBlend();
        }
    }

    @Test
    void compileAndExecuteChildRestoresLiveStateInsideCompileParent() {
        final int parent = GLSMDraws.glGenLists(1);
        final int child = GLSMDraws.glGenLists(1);
        final int entryDepth = GLStateManager.getAttribDepth();
        try {
            GLStateManager.enableBlend();
            GLSMEnable.glEnable(GL11.GL_CULL_FACE);

            GLSMDraws.glNewList(parent, GL11.GL_COMPILE);
            final int parentToken = GLStateManager.pushState(StateSet.BLEND);
            GLSMDraws.glNewList(child, GL11.GL_COMPILE_AND_EXECUTE);
            final int childToken = GLStateManager.pushState(StateSet.CULL);
            GLSMEnable.glDisable(GL11.GL_CULL_FACE);
            GLStateManager.popStateTo(childToken);
            GLSMDraws.glEndList();

            assertTrue(GLStateManager.getCullState().isEnabled());
            assertTrue(GL11.glIsEnabled(GL11.GL_CULL_FACE));
            assertEquals(entryDepth, GLStateManager.getAttribDepth());

            GLStateManager.disableBlend();
            GLStateManager.popStateTo(parentToken);
            GLSMDraws.glEndList();

            assertEquals(2, DisplayListManager.getDisplayList(parent).getCommandCounts().getOrDefault(GLCommand.COMPLEX_REF, 0));
            assertEquals(2, DisplayListManager.getDisplayList(child).getCommandCounts().getOrDefault(GLCommand.COMPLEX_REF, 0));
            assertEquals(entryDepth, GLStateManager.getAttribDepth());

            GLSMDraws.glCallList(child);
            assertTrue(GLStateManager.getCullState().isEnabled());
            GLSMDraws.glCallList(parent);
            assertTrue(GLStateManager.getBlendMode().isEnabled());
        } finally {
            while (DisplayListManager.isRecording()) {
                GLStateManager.popStateTo(entryDepth);
                GLSMDraws.glEndList();
            }
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLStateManager.popState();
            }
            GLSMDraws.glDeleteLists(parent, 1);
            GLSMDraws.glDeleteLists(child, 1);
            GLStateManager.disableBlend();
            GLSMEnable.glDisable(GL11.GL_CULL_FACE);
        }
    }

    @Test
    void compileAndExecuteEndListClosesUnbalancedLiveScope() {
        final int list = GLSMDraws.glGenLists(1);
        final int entryDepth = GLStateManager.getAttribDepth();
        try {
            GLStateManager.disableBlend();
            GLSMDraws.glNewList(list, GL11.GL_COMPILE_AND_EXECUTE);
            GLStateManager.pushState(StateSet.BLEND);
            GLStateManager.enableBlend();
            GLSMDraws.glEndList();

            assertEquals(entryDepth, GLStateManager.getAttribDepth());
            assertFalse(GLStateManager.getBlendMode().isEnabled());
            assertFalse(GL11.glIsEnabled(GL11.GL_BLEND));
            assertEquals(2, DisplayListManager.getDisplayList(list).getCommandCounts().getOrDefault(GLCommand.COMPLEX_REF, 0));
        } finally {
            while (DisplayListManager.isRecording()) {
                GLStateManager.popStateTo(entryDepth);
                GLSMDraws.glEndList();
            }
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLStateManager.popState();
            }
            GLSMDraws.glDeleteLists(list, 1);
            GLStateManager.disableBlend();
        }
    }

    @Test
    void nestedCompileAndExecuteEndListRestoresParentLiveScope() {
        final int parent = GLSMDraws.glGenLists(1);
        final int child = GLSMDraws.glGenLists(1);
        final int entryDepth = GLStateManager.getAttribDepth();
        try {
            GLStateManager.disableBlend();
            GLSMEnable.glEnable(GL11.GL_CULL_FACE);

            GLSMDraws.glNewList(parent, GL11.GL_COMPILE_AND_EXECUTE);
            GLStateManager.pushState(StateSet.BLEND);
            GLStateManager.enableBlend();
            GLSMDraws.glNewList(child, GL11.GL_COMPILE_AND_EXECUTE);
            GLStateManager.pushState(StateSet.CULL);
            GLSMEnable.glDisable(GL11.GL_CULL_FACE);
            GLSMDraws.glEndList();

            assertEquals(entryDepth + 1, GLStateManager.getAttribDepth());
            assertTrue(GLStateManager.getCullState().isEnabled());
            assertTrue(GL11.glIsEnabled(GL11.GL_CULL_FACE));

            GLSMDraws.glEndList();
            assertEquals(entryDepth, GLStateManager.getAttribDepth());
            assertFalse(GLStateManager.getBlendMode().isEnabled());
            assertFalse(GL11.glIsEnabled(GL11.GL_BLEND));
            assertEquals(2, DisplayListManager.getDisplayList(parent).getCommandCounts().getOrDefault(GLCommand.COMPLEX_REF, 0));
            assertEquals(2, DisplayListManager.getDisplayList(child).getCommandCounts().getOrDefault(GLCommand.COMPLEX_REF, 0));
        } finally {
            while (DisplayListManager.isRecording()) {
                GLStateManager.popStateTo(entryDepth);
                GLSMDraws.glEndList();
            }
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLStateManager.popState();
            }
            GLSMDraws.glDeleteLists(parent, 1);
            GLSMDraws.glDeleteLists(child, 1);
            GLStateManager.disableBlend();
            GLSMEnable.glDisable(GL11.GL_CULL_FACE);
        }
    }

    @Test
    void compileAndExecuteOverflowRestoresEverySuccessfulLivePush() {
        final int list = GLSMDraws.glGenLists(1);
        final int entryDepth = GLStateManager.getAttribDepth();
        try {
            GLStateManager.disableBlend();
            GLSMDraws.glNewList(list, GL11.GL_COMPILE_AND_EXECUTE);
            for (int i = 0; i < 32; i++) {
                GLStateManager.pushState(StateSet.BLEND);
                if (i == 0) GLStateManager.enableBlend();
            }
            assertThrows(IllegalStateException.class, () -> GLStateManager.pushState(StateSet.BLEND));
            GLSMDraws.glEndList();

            assertEquals(entryDepth, GLStateManager.getAttribDepth());
            assertFalse(GLStateManager.getBlendMode().isEnabled());
            assertFalse(GL11.glIsEnabled(GL11.GL_BLEND));
            assertEquals(64, DisplayListManager.getDisplayList(list).getCommandCounts().getOrDefault(GLCommand.COMPLEX_REF, 0));

            GLSMDraws.glCallList(list);
            assertEquals(entryDepth, GLStateManager.getAttribDepth());
            assertFalse(GLStateManager.getBlendMode().isEnabled());
            assertFalse(GL11.glIsEnabled(GL11.GL_BLEND));
        } finally {
            while (DisplayListManager.isRecording()) {
                GLStateManager.popStateTo(entryDepth);
                GLSMDraws.glEndList();
            }
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLStateManager.popState();
            }
            GLSMDraws.glDeleteLists(list, 1);
            GLStateManager.disableBlend();
        }
    }

    @Test
    void compileChildPoppingParentScopeUpdatesOwner() {
        final int parent = GLSMDraws.glGenLists(1);
        final int child = GLSMDraws.glGenLists(1);
        final int entryDepth = GLStateManager.getAttribDepth();
        try {
            GLStateManager.disableBlend();
            GLSMEnable.glDisable(GL11.GL_CULL_FACE);
            GLStateManager.pushState(StateSet.CULL);
            GLSMEnable.glEnable(GL11.GL_CULL_FACE);

            GLSMDraws.glNewList(parent, GL11.GL_COMPILE_AND_EXECUTE);
            final int parentToken = GLStateManager.pushState(StateSet.BLEND);
            GLStateManager.enableBlend();
            GLSMDraws.glNewList(child, GL11.GL_COMPILE);
            GLStateManager.popStateTo(parentToken);

            assertEquals(entryDepth + 1, GLStateManager.getAttribDepth());
            assertFalse(GLStateManager.getBlendMode().isEnabled());
            assertFalse(GL11.glIsEnabled(GL11.GL_BLEND));
            assertTrue(GLStateManager.getCullState().isEnabled());
            assertTrue(GL11.glIsEnabled(GL11.GL_CULL_FACE));

            GLSMDraws.glEndList();
            GLSMDraws.glEndList();

            assertEquals(entryDepth + 1, GLStateManager.getAttribDepth());
            assertTrue(GLStateManager.getCullState().isEnabled());
            assertTrue(GL11.glIsEnabled(GL11.GL_CULL_FACE));
            assertFalse(GLStateManager.getBlendMode().isEnabled());
            assertFalse(GL11.glIsEnabled(GL11.GL_BLEND));

            GLSMDraws.glCallList(parent);
            assertEquals(entryDepth + 1, GLStateManager.getAttribDepth());
            assertTrue(GLStateManager.getCullState().isEnabled());
            assertTrue(GL11.glIsEnabled(GL11.GL_CULL_FACE));
            assertFalse(GLStateManager.getBlendMode().isEnabled());
            assertFalse(GL11.glIsEnabled(GL11.GL_BLEND));
        } finally {
            while (DisplayListManager.isRecording()) {
                GLStateManager.popStateTo(entryDepth);
                GLSMDraws.glEndList();
            }
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLStateManager.popState();
            }
            GLSMDraws.glDeleteLists(parent, 1);
            GLSMDraws.glDeleteLists(child, 1);
            GLStateManager.disableBlend();
            GLSMEnable.glDisable(GL11.GL_CULL_FACE);
        }
    }
}
