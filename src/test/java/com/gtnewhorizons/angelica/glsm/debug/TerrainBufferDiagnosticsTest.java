package com.gtnewhorizons.angelica.glsm.debug;

import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the native-buffer event accountant without creating or querying an OpenGL context. */
class TerrainBufferDiagnosticsTest {
    private static final int ARRAY_BUFFER = 0x8892;
    private static final GLContextIdentity FIRST_CONTEXT = new GLContextIdentity(1L, 101L, 11L);
    private static final GLContextIdentity SHARED_CONTEXT = new GLContextIdentity(2L, 102L, 11L);
    private static final GLContextIdentity OTHER_SHARE_GROUP = new GLContextIdentity(3L, 103L, 22L);

    @Test
    void scopedStorageRecordsUnknownBindingAsExpectedRatherThanVerified() {
        final TerrainBufferDiagnostics.Tracker tracker = new TerrainBufferDiagnostics.Tracker(true);
        final long registration = tracker.registerArenaBuffer(
            FIRST_CONTEXT, 7L, "geometry", 44, 9, 2, 3, 4, 31, 0L);

        try (TerrainBufferDiagnostics.ArenaBufferScope ignored = tracker.openArenaBufferOperation(
            7L, 31, registration, 0L, TerrainBufferDiagnostics.BufferOperation.INITIAL_STORAGE)) {
            final TerrainBufferDiagnostics.NativeBufferDataCall call = tracker.beginNativeBufferData(
                FIRST_CONTEXT, ARRAY_BUFFER, 0L);

            assertNotNull(call);
            assertFalse(call.event().boundBufferKnown());
            assertTrue(call.event().scopeExpectedBufferKnown());
            assertEquals(31, call.event().scopeExpectedBufferId());
            assertFalse(call.event().scopeBindingMismatch());

            final TerrainBufferDiagnostics.NativeBufferDataEvent returned = tracker.finishNativeBufferData(call, null);
            assertEquals(TerrainBufferDiagnostics.Outcome.NATIVE_CALL_RETURNED, returned.outcome());
            assertEquals(FIRST_CONTEXT.generation(), returned.contextGeneration());
            assertEquals(FIRST_CONTEXT.shareGroupId(), returned.registeredShareGroupId());
        }
    }

    @Test
    void failedDeleteKeepsTheMarkerAndReturnedDeleteRemovesIt() {
        final TerrainBufferDiagnostics.Tracker tracker = new TerrainBufferDiagnostics.Tracker(true);
        final long token = tracker.registerArenaBuffer(
            FIRST_CONTEXT, 8L, "geometry", 44, 1, 0, 0, 0, 41, 256L);

        final TerrainBufferDiagnostics.NativeDeleteCall failedCall = tracker.beginNativeDelete(FIRST_CONTEXT, 41);
        final TerrainBufferDiagnostics.NativeDeleteEvent failed = tracker.finishNativeDelete(
            failedCall, new IllegalStateException("fake wrapper failure")).get(0);
        assertEquals(TerrainBufferDiagnostics.Outcome.JAVA_FAILURE, failed.outcome());
        assertEquals(IllegalStateException.class.getName(), failed.failureClass());
        assertTrue(tracker.hasRegistration(FIRST_CONTEXT, 41, token));

        final TerrainBufferDiagnostics.NativeDeleteCall returnedCall = tracker.beginNativeDelete(FIRST_CONTEXT, 41);
        final TerrainBufferDiagnostics.NativeDeleteEvent returned = tracker.finishNativeDelete(returnedCall, null).get(0);
        assertEquals(TerrainBufferDiagnostics.Outcome.NATIVE_CALL_RETURNED, returned.outcome());
        assertFalse(tracker.hasRegistration(FIRST_CONTEXT, 41, token));
    }

    @Test
    void staleDeleteReturnCannotRemoveANewerRegistrationForReusedId() {
        final TerrainBufferDiagnostics.Tracker tracker = new TerrainBufferDiagnostics.Tracker(true);
        tracker.registerArenaBuffer(FIRST_CONTEXT, 9L, "geometry", 44, 1, 0, 0, 0, 51, 512L);
        final TerrainBufferDiagnostics.NativeDeleteCall oldDelete = tracker.beginNativeDelete(FIRST_CONTEXT, 51);
        final long newToken = tracker.registerArenaBuffer(
            FIRST_CONTEXT, 10L, "geometry", 44, 2, 0, 0, 0, 51, 1024L);

        final List<TerrainBufferDiagnostics.NativeDeleteEvent> events = tracker.finishNativeDelete(oldDelete, null);

        assertEquals(1, events.size());
        assertTrue(tracker.hasRegistration(FIRST_CONTEXT, 51, newToken));
        assertNotEquals(events.get(0).registrationToken(), newToken);
    }

    @Test
    void sameBufferIdInAnotherShareGroupHasAnIndependentRegistration() {
        final TerrainBufferDiagnostics.Tracker tracker = new TerrainBufferDiagnostics.Tracker(true);
        final long firstToken = tracker.registerArenaBuffer(
            FIRST_CONTEXT, 11L, "geometry", 44, 1, 0, 0, 0, 61, 256L);
        final long otherGroupToken = tracker.registerArenaBuffer(
            OTHER_SHARE_GROUP, 12L, "geometry", 44, 2, 0, 0, 0, 61, 512L);

        final TerrainBufferDiagnostics.NativeDeleteCall call = tracker.beginNativeDelete(OTHER_SHARE_GROUP, 61);
        final TerrainBufferDiagnostics.NativeDeleteEvent event = tracker.finishNativeDelete(call, null).get(0);

        assertEquals(OTHER_SHARE_GROUP.shareGroupId(), event.registeredShareGroupId());
        assertTrue(tracker.hasRegistration(FIRST_CONTEXT, 61, firstToken));
        assertFalse(tracker.hasRegistration(OTHER_SHARE_GROUP, 61, otherGroupToken));
    }

    @Test
    void knownDifferentBindingIsReportedAsMismatchAndKeepsActualBufferIdentity() {
        final TerrainBufferDiagnostics.Tracker tracker = new TerrainBufferDiagnostics.Tracker(true);
        final long boundToken = tracker.registerArenaBuffer(
            FIRST_CONTEXT, 13L, "geometry", 44, 1, 0, 0, 0, 71, 128L);
        final long expectedToken = tracker.registerArenaBuffer(
            FIRST_CONTEXT, 14L, "geometry", 44, 2, 0, 0, 0, 72, 256L);
        tracker.observeBufferBinding(FIRST_CONTEXT, ARRAY_BUFFER, 71);

        try (TerrainBufferDiagnostics.ArenaBufferScope ignored = tracker.openArenaBufferOperation(
            14L, 72, expectedToken, 0L, TerrainBufferDiagnostics.BufferOperation.INITIAL_STORAGE)) {
            final TerrainBufferDiagnostics.NativeBufferDataCall call = tracker.beginNativeBufferData(
                FIRST_CONTEXT, ARRAY_BUFFER, 256L);

            assertNotNull(call);
            assertEquals(71, call.event().boundBufferId());
            assertEquals(72, call.event().scopeExpectedBufferId());
            assertTrue(call.event().scopeBindingMismatch());
            assertEquals(13L, call.event().arenaId());
            tracker.finishNativeBufferData(call, null);
        }
        assertTrue(tracker.hasRegistration(FIRST_CONTEXT, 71, boundToken));
    }

    @Test
    void reenabledTracingStartsReplacementWithAnExplicitBaseline() {
        final TerrainBufferDiagnostics.Tracker tracker = new TerrainBufferDiagnostics.Tracker(true);
        final long firstToken = tracker.registerArenaBuffer(
            FIRST_CONTEXT, 15L, "geometry", 44, 3, 0, 0, 0, 81, 1024L);

        final TerrainBufferDiagnostics.TraceEndResult ended = tracker.setEnabled(false, FIRST_CONTEXT);
        assertEquals(TerrainBufferDiagnostics.TraceEndReason.DEBUG_DISABLED, ended.events().get(0).reason());
        assertFalse(tracker.hasRegistration(FIRST_CONTEXT, 81, firstToken));
        tracker.setEnabled(true, FIRST_CONTEXT);

        final TerrainBufferDiagnostics.ResizeToken resize = tracker.beginArenaReplacement(
            FIRST_CONTEXT, 15L, "geometry", 44, 3, 0, 0, 0,
            81, 1024L, 82, 2048L, 768L, 1280L, 2);
        assertTrue(resize.baseline());
        assertNotEquals(firstToken, resize.oldRegistrationToken());
        assertTrue(tracker.hasRegistration(FIRST_CONTEXT, 81, resize.oldRegistrationToken()));

        final TerrainBufferDiagnostics.TraceEndedEvent cancelled = tracker.cancelArenaReplacement(resize);
        assertEquals(TerrainBufferDiagnostics.TraceEndReason.REPLACEMENT_CANCELLED, cancelled.reason());
        assertTrue(tracker.hasRegistration(FIRST_CONTEXT, 81, resize.oldRegistrationToken()));
        assertFalse(tracker.hasRegistration(FIRST_CONTEXT, 82, resize.newRegistrationToken()));
    }
}
