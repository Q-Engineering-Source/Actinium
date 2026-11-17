package net.coderbot.iris.pipeline;

import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.hooks.PendingProgramSelection;
import com.gtnewhorizons.angelica.glsm.recording.CommandRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeferredProgramMatchTest {
    private final FakeDisplayLists displayLists = new FakeDisplayLists();
    private final List<String> matches = new ArrayList<>();
    private final PendingProgramSelection owner = () -> {};
    private final DeferredProgramMatch match = new DeferredProgramMatch(
        this.displayLists,
        this.owner,
        () -> this.deferred,
        () -> this.matches.add("match"));

    private boolean deferred;
    private PendingProgramSelection previousSelection = GLSMHooks.pendingProgramSelection;

    @AfterEach
    void restoreSelectionSlot() {
        GLSMHooks.pendingProgramSelection = this.previousSelection;
    }

    @Test
    void compileDoesNotRunOrRecordAProgramMatch() {
        this.displayLists.compiling = true;
        this.displayLists.recording = true;

        this.match.request();

        assertEquals(List.of(), this.matches);
        assertFalse(this.match.isPending());
    }

    @Test
    void passOverrideChangesCoalesceIntoOneMatch() {
        this.deferred = true;

        this.match.request();
        this.match.request();
        this.match.request();

        assertEquals(List.of(), this.matches);
        assertTrue(this.match.isPending());
        assertSame(this.owner, GLSMHooks.pendingProgramSelection, "the deferred request is published for the host");

        this.match.resolve();
        assertEquals(List.of(), this.matches, "a batch still owns the program");

        this.deferred = false;
        this.match.resolve();

        assertEquals(List.of("match"), this.matches);
        assertFalse(this.match.isPending());
        assertNull(GLSMHooks.pendingProgramSelection, "running the match releases the slot");
    }

    @Test
    void clearingDoesNotDropAnotherPipelinesSelection() {
        final PendingProgramSelection other = () -> {};
        GLSMHooks.pendingProgramSelection = other;

        this.match.clear();

        assertSame(other, GLSMHooks.pendingProgramSelection, "only the publishing pipeline clears the slot");
    }

    @Test
    void passChangesOutsideABatchMatchImmediately() {
        this.match.request();
        this.match.request();

        assertEquals(List.of("match", "match"), this.matches);
        assertFalse(this.match.isPending());
    }

    @Test
    void explicitMatchDropsThePendingSelection() {
        this.deferred = true;
        this.match.request();
        this.deferred = false;

        this.match.clear();
        this.match.resolve();

        assertEquals(List.of(), this.matches);
        assertFalse(this.match.isPending());
        assertNull(GLSMHooks.pendingProgramSelection);
    }

    @Test
    void resolvingWithoutAPendingSelectionDoesNothing() {
        this.match.resolve();

        assertEquals(List.of(), this.matches);
    }

    @Test
    void directMatchIsSkippedWhileCompiling() {
        this.displayLists.compiling = true;
        this.displayLists.recording = true;

        this.match.matchNow();

        assertEquals(List.of(), this.matches);
    }

    @Test
    void directMatchRunsImmediatelyOtherwise() {
        this.match.matchNow();

        assertEquals(List.of("match"), this.matches);
        assertFalse(this.match.isPending());
    }

    @Test
    void programTransitionIsSkippedWhileCompiling() {
        this.displayLists.compiling = true;
        this.displayLists.recording = true;
        List<String> events = new ArrayList<>();

        this.match.runUnrecorded(() -> events.add("bound"));

        assertEquals(List.of(), events);
        assertEquals(List.of(), this.displayLists.events);
    }

    @Test
    void programTransitionIsNotRecordedWhileRecording() {
        this.displayLists.recording = true;
        List<String> events = new ArrayList<>();

        this.match.runUnrecorded(() -> events.add("recording=" + this.displayLists.recording));

        assertEquals(List.of("recording=false"), events);
        assertEquals(List.of("pause", "resume"), this.displayLists.events);
    }

    @Test
    void programTransitionRunsDirectlyWhenNotRecording() {
        List<String> events = new ArrayList<>();

        this.match.runUnrecorded(() -> events.add("bound"));

        assertEquals(List.of("bound"), events);
        assertEquals(List.of(), this.displayLists.events);
    }

    private static final class FakeDisplayLists implements DeferredProgramMatch.DisplayListAccess {
        private final List<String> events = new ArrayList<>();
        private boolean compiling;
        private boolean recording;

        @Override
        public boolean isCompiling() {
            return this.compiling;
        }

        @Override
        public boolean isRecording() {
            return this.recording;
        }

        @Override
        public CommandRecorder pause() {
            this.events.add("pause");
            this.recording = false;
            return null;
        }

        @Override
        public void resume(CommandRecorder recorder) {
            this.events.add("resume");
            this.recording = true;
        }
    }
}
