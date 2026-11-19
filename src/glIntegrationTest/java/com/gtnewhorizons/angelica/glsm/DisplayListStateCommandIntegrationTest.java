package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.recording.commands.DisplayListCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayListStateCommandIntegrationTest extends GLStateManagerIntegrationFixture {

    private record TestCommand(int id) implements DisplayListCommand {
        @Override
        public void execute() {}
    }

    @AfterEach
    void cleanup() {
        DisplayListManager.abortIfLeaked();
    }

    @Test
    void recordStateCommandAppendsOneCommand() {
        final int list = GLSMDraws.glGenLists(1);
        try {
            GLSMDraws.glNewList(list, GL11.GL_COMPILE);

            DisplayListManager.recordStateCommand(new TestCommand(1));

            assertEquals(1, DisplayListManager.getCommandCount());
            assertTrue(DisplayListManager.isRecording());
            GLSMDraws.glEndList();
        } finally {
            GLSMDraws.glDeleteLists(list, 1);
        }
    }

    @Test
    void recordStateCommandIfChangedSkipsAnEqualTailCommand() {
        final int list = GLSMDraws.glGenLists(1);
        try {
            GLSMDraws.glNewList(list, GL11.GL_COMPILE);
            DisplayListManager.recordStateCommand(new TestCommand(1));

            DisplayListManager.recordStateCommandIfChanged(new TestCommand(1));

            assertEquals(1, DisplayListManager.getCommandCount(), "an equal command at the tail must not be recorded twice");
            GLSMDraws.glEndList();
        } finally {
            GLSMDraws.glDeleteLists(list, 1);
        }
    }

    @Test
    void recordStateCommandIfChangedRecordsAfterAnyOtherCommand() {
        final int list = GLSMDraws.glGenLists(1);
        try {
            GLSMDraws.glNewList(list, GL11.GL_COMPILE);
            DisplayListManager.recordStateCommand(new TestCommand(1));

            DisplayListManager.recordEnable(GL11.GL_BLEND);
            DisplayListManager.recordStateCommandIfChanged(new TestCommand(1));

            assertEquals(3, DisplayListManager.getCommandCount(), "the command is no longer at the tail and must be recorded again");
            GLSMDraws.glEndList();
        } finally {
            GLSMDraws.glDeleteLists(list, 1);
        }
    }

    @Test
    void recordStateCommandIfChangedRecordsADifferentTailCommand() {
        final int list = GLSMDraws.glGenLists(1);
        try {
            GLSMDraws.glNewList(list, GL11.GL_COMPILE);
            DisplayListManager.recordStateCommand(new TestCommand(1));

            DisplayListManager.recordStateCommandIfChanged(new TestCommand(2));

            assertEquals(2, DisplayListManager.getCommandCount());
            GLSMDraws.glEndList();
        } finally {
            GLSMDraws.glDeleteLists(list, 1);
        }
    }

    @Test
    void abortIfLeakedClosesALeakedList() {
        final int list = GLSMDraws.glGenLists(1);
        try {
            GLSMDraws.glNewList(list, GL11.GL_COMPILE);
            assertTrue(DisplayListManager.isRecording());

            DisplayListManager.abortIfLeaked();

            assertFalse(DisplayListManager.isRecording(), "frame-begin recovery must end the leaked list");
            assertEquals(DisplayListManager.RecordMode.NONE, DisplayListManager.getRecordMode());
            assertEquals(-1, DisplayListManager.getRecordingListId());
        } finally {
            GLSMDraws.glDeleteLists(list, 1);
        }
    }

    @Test
    void abortIfLeakedReleasesEveryNestedCapture() {
        final int outer = GLSMDraws.glGenLists(1);
        final int inner = GLSMDraws.glGenLists(1);
        final int reused = GLSMDraws.glGenLists(1);
        try {
            GLSMDraws.glNewList(outer, GL11.GL_COMPILE);
            GLSMDraws.glNewList(inner, GL11.GL_COMPILE);

            DisplayListManager.abortIfLeaked();

            assertFalse(DisplayListManager.isRecording());
            assertDoesNotThrow(() -> {
                GLSMDraws.glNewList(reused, GL11.GL_COMPILE);
                DisplayListManager.abortIfLeaked();
            }, "the capture stack must be balanced again after aborting nested lists");
            assertFalse(DisplayListManager.isRecording());
        } finally {
            GLSMDraws.glDeleteLists(outer, 1);
            GLSMDraws.glDeleteLists(inner, 1);
            GLSMDraws.glDeleteLists(reused, 1);
        }
    }

    @Test
    void abortIfLeakedPopsStatePushedByOuterCompileAndExecuteLists() {
        final int outer = GLSMDraws.glGenLists(1);
        final int inner = GLSMDraws.glGenLists(1);
        final int entryDepth = GLStateManager.getAttribDepth();
        try {
            GLSMDraws.glNewList(outer, GL11.GL_COMPILE_AND_EXECUTE);
            GLStateManager.pushState(StateSet.BLEND);
            GLSMDraws.glNewList(inner, GL11.GL_COMPILE_AND_EXECUTE);
            GLStateManager.pushState(StateSet.CULL);

            DisplayListManager.abortIfLeaked();

            assertFalse(DisplayListManager.isRecording());
            assertEquals(entryDepth, GLStateManager.getAttribDepth(),
                "every aborted level must pop the state it pushed, including the outer list");
        } finally {
            while (GLStateManager.getAttribDepth() > entryDepth) {
                GLStateManager.popState();
            }
            GLSMDraws.glDeleteLists(outer, 1);
            GLSMDraws.glDeleteLists(inner, 1);
        }
    }

    @Test
    void abortIfLeakedIsANoOpWhileNotRecording() {
        assertFalse(DisplayListManager.isRecording());
        assertDoesNotThrow(DisplayListManager::abortIfLeaked);
        assertFalse(DisplayListManager.isRecording());
    }
}
