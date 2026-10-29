package dhj.embeddedt.embeddium.impl.render.chunk.region;

import com.gtnewhorizons.angelica.glsm.debug.TerrainBufferDiagnostics;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerrainArenaRetirementTrackerTest {
    @Test
    void observesFenceSignalOnlyAfterAnUnsignaledPollAndNativeDeleteReturn() {
        TerrainArenaRetirementTracker tracker = new TerrainArenaRetirementTracker(8, 8);
        FakeFence fence = new FakeFence();
        TerrainArenaRetirementTracker.RetirementDescriptor descriptor = descriptor(41L, 301L, 17, 1L, 2L, 8192L);
        assertTrue(tracker.recordFence(descriptor, fence, 7L, 11L, 100L, "none"));
        tracker.recordDeleteRequested(41L, 1L, 17, 150L);

        tracker.tick(1_000_000_100L, 7L, true);
        assertEquals(1, tracker.snapshot().pendingFenceCount());
        assertEquals(1_000_000_100L, tracker.snapshot().trackedRecordCount() == 1
                ? tracker.snapshot().lastPollNanos() : -1L);

        fence.signaled = true;
        tracker.recordActualDelete(delete(41L, 1L, 17, 7L, 1_500_000_000L, true));
        List<TerrainArenaRetirementTracker.Completion> completions = tracker.tick(2_000_000_100L, 7L, true);

        assertTrue(completions.stream().anyMatch(completion -> completion.status().equals("fence-signal-observed")));
        assertTrue(completions.stream().anyMatch(completion -> completion.status()
                .equals("delete-returned-and-fence-signal-observed")));
        TerrainArenaRetirementTracker.Completion completed = completions.get(completions.size() - 1);
        assertEquals(1_000_000_100L, completed.lastUnsignaledAtNanos());
        assertEquals(2_000_000_100L, completed.observedSignalAtNanos());
        assertEquals(1_000_000_000L, completed.signalWindowLowerBoundNanos());
        assertEquals(2_000_000_000L, completed.signalWindowUpperBoundNanos());
        assertTrue(fence.deleted);
        assertEquals(0, tracker.snapshot().trackedRecordCount());
    }

    @Test
    void keepsDeleteRequestSeparateFromActualNativeReturn() {
        TerrainArenaRetirementTracker tracker = new TerrainArenaRetirementTracker(8, 8);
        FakeFence fence = new FakeFence();
        TerrainArenaRetirementTracker.RetirementDescriptor descriptor = descriptor(42L, 302L, 18, 3L, 4L, 4096L);
        assertTrue(tracker.recordFence(descriptor, fence, 7L, 11L, 200L, "none"));
        tracker.recordDeleteRequested(42L, 3L, 18, 250L);

        TerrainArenaRetirementTracker.Snapshot requested = tracker.snapshot();
        assertEquals(1, requested.deleteRequestedNotReturnedCount());
        assertEquals(0L, requested.actualDeleteReturnedCount());

        tracker.recordActualDelete(delete(42L, 3L, 18, 7L, 1_500_000_000L, true));
        tracker.tick(1_000_000_200L, 7L, true);

        TerrainArenaRetirementTracker.Snapshot returned = tracker.snapshot();
        assertEquals(0, returned.deleteRequestedNotReturnedCount());
        assertEquals(1, returned.deleteReturnedFencePendingCount());
        assertEquals(1L, returned.actualDeleteReturnedCount());
        assertFalse(fence.deleted);
    }

    @Test
    void nestedDeleteReturnsAfterFenceSignalCountOnceAndKeepTheFirstReturnTime() {
        TerrainArenaRetirementTracker tracker = new TerrainArenaRetirementTracker(8, 8);
        FakeFence fence = new FakeFence();
        assertTrue(tracker.recordFence(descriptor(43L, 303L, 19, 5L, 6L, 4096L),
                fence, 7L, 11L, 100L, "none"));
        tracker.recordDeleteRequested(43L, 5L, 19, 150L);

        fence.signaled = true;
        tracker.tick(1_000_000_000L, 7L, true);
        assertEquals(1L, tracker.snapshot().observedSignalCount());
        assertEquals(1, tracker.snapshot().trackedRecordCount());

        tracker.recordActualDelete(delete(43L, 5L, 19, 7L, 500L, true)); // Inner native wrapper returned.
        tracker.recordActualDelete(delete(43L, 5L, 19, 7L, 550L, true)); // Enclosing redirect returned later.
        List<TerrainArenaRetirementTracker.Completion> completions = tracker.tick(2_000_000_000L, 7L, true);

        assertEquals(1L, tracker.snapshot().actualDeleteReturnedCount());
        assertEquals(0L, tracker.snapshot().unmatchedDeleteEventCount());
        assertEquals(0, tracker.snapshot().deleteEventUnknownCount());
        assertTrue(fence.deleted);
        TerrainArenaRetirementTracker.Completion completed = completions.stream()
                .filter(completion -> completion.status().equals("delete-returned-and-fence-signal-observed"))
                .findFirst().orElseThrow();

        assertEquals(500L, completed.deleteReturnedAtNanos());
        assertEquals(1L, tracker.snapshot().actualDeleteReturnedCount());
        assertEquals(0, tracker.snapshot().trackedRecordCount());
    }

    @Test
    void nestedDeleteFailuresCountOnceAndDoNotBecomeSuccess() {
        TerrainArenaRetirementTracker tracker = new TerrainArenaRetirementTracker(8, 8);
        FakeFence fence = new FakeFence();
        assertTrue(tracker.recordFence(descriptor(44L, 304L, 20, 7L, 8L, 4096L),
                fence, 7L, 11L, 100L, "none"));
        tracker.recordDeleteRequested(44L, 7L, 20, 150L);

        tracker.recordActualDelete(delete(44L, 7L, 20, 7L, 600L, false)); // Inner wrapper threw.
        tracker.recordActualDelete(delete(44L, 7L, 20, 7L, 650L, false)); // Enclosing wrapper propagated it.
        tracker.tick(1_000_000_000L, 8L, true);

        assertEquals(0L, tracker.snapshot().actualDeleteReturnedCount());
        assertEquals(1L, tracker.snapshot().actualDeleteFailureCount());
        assertEquals(1, tracker.snapshot().trackedRecordCount());
        assertFalse(fence.deleted);

        List<TerrainArenaRetirementTracker.Completion> completions = tracker.tick(1_000_000_100L, 7L, true);
        TerrainArenaRetirementTracker.Completion failed = completions.stream()
                .filter(completion -> completion.status().equals("observation-ended-without-reclaim-claim"))
                .findFirst().orElseThrow();

        assertTrue(failed.deleteFailed());
        assertFalse(failed.deleteReturned());
        assertEquals("java.lang.IllegalStateException", failed.deleteFailureClass());
        assertEquals(1L, tracker.snapshot().actualDeleteFailureCount());
        assertEquals(0L, tracker.snapshot().actualDeleteReturnedCount());
    }

    @Test
    void firstDeleteFailureCannotBeReplacedByLaterSuccessForTheSameIdentity() {
        TerrainArenaRetirementTracker tracker = new TerrainArenaRetirementTracker(8, 8);
        FakeFence fence = new FakeFence();
        assertTrue(tracker.recordFence(descriptor(45L, 305L, 21, 9L, 10L, 2048L),
                fence, 7L, 11L, 100L, "none"));

        tracker.recordActualDelete(delete(45L, 9L, 21, 7L, 700L, false));
        tracker.recordActualDelete(delete(45L, 9L, 21, 7L, 750L, true));
        List<TerrainArenaRetirementTracker.Completion> completions = tracker.tick(1_000_000_000L, 8L, true);

        assertEquals(0L, tracker.snapshot().actualDeleteReturnedCount());
        assertEquals(1L, tracker.snapshot().actualDeleteFailureCount());
        assertTrue(completions.isEmpty());

        List<TerrainArenaRetirementTracker.Completion> ended = tracker.tick(1_000_000_100L, 7L, true);
        TerrainArenaRetirementTracker.Completion failed = ended.stream()
                .filter(completion -> completion.status().equals("observation-ended-without-reclaim-claim"))
                .findFirst().orElseThrow();
        assertTrue(failed.deleteFailed());
        assertFalse(failed.deleteReturned());
        assertEquals(1L, tracker.snapshot().actualDeleteFailureCount());
        assertEquals(0L, tracker.snapshot().actualDeleteReturnedCount());
    }

    @Test
    void pairsReusedBufferNamesByResizeSequenceAndRegistrationToken() {
        TerrainArenaRetirementTracker tracker = new TerrainArenaRetirementTracker(8, 8);
        FakeFence firstFence = new FakeFence();
        FakeFence secondFence = new FakeFence();
        assertTrue(tracker.recordFence(descriptor(51L, 401L, 77, 101L, 102L, 1024L), firstFence, 7L, 11L, 10L, "none"));
        assertTrue(tracker.recordFence(descriptor(52L, 402L, 77, 201L, 202L, 2048L), secondFence, 7L, 11L, 20L, "none"));
        tracker.recordDeleteRequested(51L, 101L, 77, 30L);
        tracker.recordDeleteRequested(52L, 201L, 77, 40L);

        tracker.recordActualDelete(delete(52L, 201L, 77, 7L, 50L, true));
        tracker.tick(1_000_000_000L, 7L, true);
        assertEquals(1L, tracker.snapshot().actualDeleteReturnedCount());
        assertEquals(1, tracker.snapshot().deleteRequestedNotReturnedCount());
        assertEquals(2, tracker.snapshot().trackedRecordCount());

        tracker.recordActualDelete(delete(51L, 101L, 77, 7L, 60L, true));
        firstFence.signaled = true;
        secondFence.signaled = true;
        tracker.tick(2_000_000_000L, 7L, true);

        assertEquals(0, tracker.snapshot().trackedRecordCount());
        assertEquals(2L, tracker.snapshot().actualDeleteReturnedCount());
    }

    @Test
    void debugDisableAndManagerDestroyEndObservationWithoutClaimingCompletion() {
        TerrainArenaRetirementTracker disabledTracker = new TerrainArenaRetirementTracker(8, 8);
        FakeFence disabledFence = new FakeFence();
        assertTrue(disabledTracker.recordFence(descriptor(61L, 501L, 31, 1L, 2L, 8192L),
                disabledFence, 9L, 12L, 100L, "none"));
        disabledTracker.tick(1_000_000_000L, 9L, true);
        List<TerrainArenaRetirementTracker.Completion> disabled = disabledTracker.tick(1_000_000_100L, 9L, false);
        assertTrue(disabledFence.deleted);
        assertTrue(disabled.stream().anyMatch(completion -> completion.status().equals("observation-ended")));
        assertEquals(0L, disabledTracker.snapshot().observedSignalCount());
        disabledTracker.onTraceEnded(new TerrainBufferDiagnostics.TraceEndedEvent(1L, 1_000_000_200L,
                TerrainBufferDiagnostics.TraceEndReason.DEBUG_DISABLED, 12L, 9L,
                501L, "geometry", 32, 4, 2, 3, 5, 31, 1L, 8192L, 61L));
        disabledTracker.tick(1_000_000_200L, 9L, false);
        assertEquals(0, disabledTracker.snapshot().trackedRecordCount());

        TerrainArenaRetirementTracker destroyedTracker = new TerrainArenaRetirementTracker(8, 8);
        FakeFence destroyedFence = new FakeFence();
        assertTrue(destroyedTracker.recordFence(descriptor(62L, 502L, 32, 3L, 4L, 4096L),
                destroyedFence, 10L, 13L, 200L, "none"));
        destroyedTracker.endOwner(1L, 11L, 300L, "region-manager-destroyed");
        assertFalse(destroyedFence.deleted);
        assertEquals(1, destroyedTracker.snapshot().deferredCleanupCount());

        List<TerrainArenaRetirementTracker.Completion> cleanup = destroyedTracker.tick(400L, 10L, true);
        assertTrue(destroyedFence.deleted);
        assertTrue(cleanup.stream().anyMatch(completion -> completion.status().equals("observation-ended")));
        assertEquals(0, destroyedTracker.snapshot().deferredCleanupCount());
        assertEquals(0L, destroyedTracker.snapshot().observedSignalCount());
    }

    @Test
    void backendQueueOverflowMarksTheDroppedDeleteIdentityUnknown() {
        TerrainArenaRetirementTracker tracker = new TerrainArenaRetirementTracker(8, 1);
        FakeFence firstFence = new FakeFence();
        FakeFence droppedFence = new FakeFence();
        assertTrue(tracker.recordFence(descriptor(71L, 601L, 91, 1L, 2L, 1024L), firstFence, 7L, 11L, 100L, "none"));
        assertTrue(tracker.recordFence(descriptor(72L, 602L, 92, 3L, 4L, 2048L), droppedFence, 7L, 11L, 200L, "none"));

        tracker.recordActualDelete(delete(71L, 1L, 91, 7L, 300L, true));
        tracker.recordActualDelete(delete(72L, 3L, 92, 7L, 400L, true));
        tracker.tick(1_000_000_000L, 7L, true);

        TerrainArenaRetirementTracker.Snapshot snapshot = tracker.snapshot();
        assertEquals(1L, snapshot.backendEventOverflowCount());
        assertEquals(1, snapshot.deleteEventUnknownCount());
        assertEquals(1L, snapshot.actualDeleteReturnedCount());
        assertEquals(1, snapshot.deleteReturnedFencePendingCount());
        assertEquals(1, snapshot.pendingOldCount());
        assertEquals(2, snapshot.trackedRecordCount());
    }

    @Test
    void failedFencePollBecomesUnavailableAndDoesNotWaitForGpuRecovery() {
        TerrainArenaRetirementTracker tracker = new TerrainArenaRetirementTracker(8, 8);
        FakeFence fence = new FakeFence();
        fence.failPoll = true;
        assertTrue(tracker.recordFence(descriptor(81L, 701L, 101, 1L, 2L, 512L),
                fence, 7L, 11L, 100L, "none"));

        List<TerrainArenaRetirementTracker.Completion> pollFailure = tracker.tick(1_000_000_000L, 7L, true);
        assertTrue(pollFailure.stream().anyMatch(completion -> completion.observationUnavailable()
                && completion.observationUnavailableReason().equals("java.lang.IllegalStateException")));
        assertTrue(fence.deleted);
        assertEquals(1, tracker.snapshot().observationUnavailableCount());

        tracker.recordActualDelete(delete(81L, 1L, 101, 7L, 1_000_000_050L, true));
        List<TerrainArenaRetirementTracker.Completion> deleteOutcome = tracker.tick(1_000_000_100L, 7L, true);
        assertTrue(deleteOutcome.stream().anyMatch(completion -> completion.status()
                .equals("delete-outcome-observation-unavailable")));
        assertEquals(0, tracker.snapshot().trackedRecordCount());
    }

    @Test
    void recordsAnUnsupportedFenceAndStillPairsTheNativeDelete() {
        TerrainArenaRetirementTracker tracker = new TerrainArenaRetirementTracker(8, 8);
        TerrainArenaRetirementTracker.RetirementDescriptor descriptor = descriptor(91L, 801L, 111,
                1L, 2L, 2048L);
        assertTrue(tracker.recordFence(descriptor, null, 7L, 11L, 100L, "glFenceSync-returned-zero"));
        tracker.recordDeleteRequested(91L, 1L, 111, 150L);

        TerrainArenaRetirementTracker.Snapshot unavailable = tracker.snapshot();
        assertEquals(1L, unavailable.unsupportedFenceCount());
        assertEquals(1, unavailable.observationUnavailableCount());
        assertEquals(0, unavailable.pendingFenceCount());

        tracker.recordActualDelete(delete(91L, 1L, 111, 7L, 200L, true));
        List<TerrainArenaRetirementTracker.Completion> completions = tracker.tick(300L, 7L, true);
        assertTrue(completions.stream().anyMatch(completion -> completion.status()
                .equals("delete-outcome-observation-unavailable")));
        assertEquals(0, tracker.snapshot().trackedRecordCount());
    }

    @Test
    void unknownOrMismatchedShareGroupsNeverConfirmNativeDelete() {
        TerrainArenaRetirementTracker unknownContextTracker = new TerrainArenaRetirementTracker(8, 8);
        FakeFence unknownContextFence = new FakeFence();
        assertTrue(unknownContextTracker.recordFence(
                descriptor(92L, 802L, 112, 3L, 4L, 2048L), unknownContextFence, 7L, 11L, 100L, "none"));
        unknownContextTracker.recordActualDelete(delete(92L, 3L, 112, 0L, 7L, 200L, true));
        unknownContextTracker.tick(1_000_000_000L, 7L, true);
        assertEquals(1, unknownContextTracker.snapshot().deleteEventUnknownCount());
        assertEquals(0L, unknownContextTracker.snapshot().actualDeleteReturnedCount());
        unknownContextFence.signaled = true;
        List<TerrainArenaRetirementTracker.Completion> unknownContextCompletions =
                unknownContextTracker.tick(2_000_000_000L, 7L, true);
        assertTrue(unknownContextCompletions.stream().anyMatch(completion ->
                completion.status().equals("fence-signal-delete-event-unknown")
                        && completion.deleteEventUnknown() && !completion.deleteReturned()));

        TerrainArenaRetirementTracker mismatchedTracker = new TerrainArenaRetirementTracker(8, 8);
        FakeFence mismatchedFence = new FakeFence();
        assertTrue(mismatchedTracker.recordFence(
                descriptor(93L, 803L, 113, 5L, 6L, 4096L), mismatchedFence, 7L, 11L, 300L, "none"));
        mismatchedTracker.recordActualDelete(delete(93L, 5L, 113, 7L, 8L, 400L, true));
        mismatchedTracker.tick(1_000_000_000L, 7L, true);
        assertEquals(1, mismatchedTracker.snapshot().deleteEventUnknownCount());
        assertEquals(1L, mismatchedTracker.snapshot().wrongShareGroupDeleteEventCount());
        assertEquals(0L, mismatchedTracker.snapshot().actualDeleteReturnedCount());
    }

    @Test
    void limitsStatusQueriesToOneHundredTwentyEightPerShareGroupSample() {
        TerrainArenaRetirementTracker tracker = new TerrainArenaRetirementTracker(256, 256);
        List<FakeFence> fences = new ArrayList<>();
        for (int index = 0; index < 130; index++) {
            FakeFence fence = new FakeFence();
            fences.add(fence);
            assertTrue(tracker.recordFence(descriptor(100L + index, 900L + index, 1200 + index,
                    2000L + index, 3000L + index, 1024L), fence, 7L, 11L, 100L + index, "none"));
        }

        tracker.tick(1_000_000_000L, 7L, true);
        assertEquals(128, fences.stream().mapToInt(fence -> fence.pollCount).sum());
        assertEquals(128, tracker.snapshot().lastPollCount());

        tracker.tick(1_500_000_000L, 7L, true);
        assertEquals(128, fences.stream().mapToInt(fence -> fence.pollCount).sum());

        tracker.tick(2_000_000_000L, 7L, true);
        assertEquals(256, fences.stream().mapToInt(fence -> fence.pollCount).sum());
        assertEquals(128, tracker.snapshot().lastPollCount());
        assertTrue(fences.stream().allMatch(fence -> fence.pollCount > 0));
    }

    private static TerrainArenaRetirementTracker.RetirementDescriptor descriptor(
            long resizeSequence, long arenaId, int oldBufferId, long oldToken, long newToken, long oldCapacityBytes) {
        return new TerrainArenaRetirementTracker.RetirementDescriptor(1L, arenaId, "geometry", 32,
                4, 2, 3, 5, resizeSequence, oldToken, newToken, oldBufferId, oldBufferId + 1000,
                oldCapacityBytes, oldCapacityBytes * 2, oldCapacityBytes / 2, 64L, 2, false);
    }

    private static TerrainArenaRetirementTracker.DeleteObservation delete(
            long resizeSequence, long registrationToken, int bufferId, long shareGroupId,
            long completedAtNanos, boolean returned) {
        return delete(resizeSequence, registrationToken, bufferId, shareGroupId, shareGroupId,
                completedAtNanos, returned);
    }

    private static TerrainArenaRetirementTracker.DeleteObservation delete(
            long resizeSequence, long registrationToken, int bufferId, long contextShareGroupId,
            long registeredShareGroupId, long completedAtNanos, boolean returned) {
        return new TerrainArenaRetirementTracker.DeleteObservation(resizeSequence, registrationToken, bufferId,
                contextShareGroupId, registeredShareGroupId, completedAtNanos, returned,
                returned ? null : "java.lang.IllegalStateException");
    }

    private static final class FakeFence implements TerrainArenaRetirementTracker.FenceProbe {
        private boolean signaled;
        private boolean deleted;
        private boolean failPoll;
        private int pollCount;

        @Override
        public boolean isCompleted() {
            this.pollCount++;
            if (this.failPoll) {
                throw new IllegalStateException("simulated nonblocking status failure");
            }
            return this.signaled;
        }

        @Override
        public void delete() {
            this.deleted = true;
        }
    }
}
