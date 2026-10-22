package dhj.embeddedt.embeddium.impl.render.chunk.region;

import com.gtnewhorizons.angelica.glsm.context.GLContextRegistryProvider;
import com.gtnewhorizons.angelica.glsm.debug.TerrainBufferDiagnostics;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks only replaced terrain arena stores. Backend callbacks enqueue scalar observations; all fence work
 * and retirement state transitions run on a render thread with a compatible share-group context current.
 */
final class TerrainArenaRetirementTracker implements TerrainBufferDiagnostics.Listener {
    static final int MAX_RECORDS = 512;
    static final int MAX_FENCE_POLLS_PER_SAMPLE = 128;
    static final int MAX_DEFERRED_CLEANUPS_PER_FRAME = 32;
    static final int MAX_QUEUED_BACKEND_EVENTS = 4096;
    static final long SAMPLE_INTERVAL_NANOS = 1_000_000_000L;

    private static final TerrainArenaRetirementTracker SHARED = createShared();

    private final Map<RetirementKey, Retirement> retirements = new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<BackendEvent> backendEvents = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<RetirementKey> lostDeleteKeys = new ConcurrentLinkedQueue<>();
    private final AtomicInteger backendEventCount = new AtomicInteger();
    private final AtomicInteger lostDeleteKeyCount = new AtomicInteger();
    private final AtomicLong backendEventOverflowCount = new AtomicLong();
    private final AtomicBoolean lostDeleteCoverageAll = new AtomicBoolean();
    private final AtomicBoolean lostTraceEndCoverage = new AtomicBoolean();
    private final Map<Long, Long> lastPollNanosByShareGroup = new HashMap<>();
    private final Map<Long, Integer> pollCursorByShareGroup = new HashMap<>();
    private final int maxRecords;
    private final int maxQueuedBackendEvents;

    private long coverageSkippedCount;
    private long coverageSkippedBytes;
    private long unsupportedFenceCount;
    private long observedSignalCount;
    private long observationEndedCount;
    private long contextRetiredWithoutGLCount;
    private long actualDeleteReturnedCount;
    private long actualDeleteFailureCount;
    private long deleteReturnedFencePendingObservedCount;
    private long unmatchedDeleteEventCount;
    private long wrongShareGroupDeleteEventCount;
    private long fencePollFailureCount;
    private long fenceDeleteFailureCount;
    private long deferredContextCount;
    private long latestSignalWindowLowerBoundNanos;
    private long latestSignalWindowUpperBoundNanos;
    private long widestSignalWindowNanos;
    private long lastPollShareGroup;
    private long lastPollNanos;
    private int lastPollCount;
    private int lastPollDeferredCount;
    private int deferredCleanupCount;
    private String lastUnsupportedReason = "none";

    private TerrainArenaRetirementTracker() {
        this(MAX_RECORDS, MAX_QUEUED_BACKEND_EVENTS);
    }

    TerrainArenaRetirementTracker(int maxRecords, int maxQueuedBackendEvents) {
        if (maxRecords <= 0 || maxQueuedBackendEvents <= 0) {
            throw new IllegalArgumentException("Terrain retirement limits must be positive");
        }
        this.maxRecords = maxRecords;
        this.maxQueuedBackendEvents = maxQueuedBackendEvents;
    }

    private static TerrainArenaRetirementTracker createShared() {
        TerrainArenaRetirementTracker tracker = new TerrainArenaRetirementTracker();
        TerrainBufferDiagnostics.addListener(tracker);
        return tracker;
    }

    static TerrainArenaRetirementTracker shared() {
        return SHARED;
    }

    /** Returns cheaply whether the render thread has any lifecycle work to service. */
    boolean hasWork() {
        return this.backendEventCount.get() > 0 || this.lostDeleteKeyCount.get() > 0
                || this.lostDeleteCoverageAll.get() || this.lostTraceEndCoverage.get()
                || !this.retirements.isEmpty();
    }

    /**
     * Receives an actual native deletion result from any thread. This method only enqueues a bounded scalar event.
     */
    @Override
    public void onNativeDelete(TerrainBufferDiagnostics.NativeDeleteEvent event) {
        this.enqueue(new NativeDeleteBackendEvent(DeleteObservation.from(event)), true);
    }

    /** Receives a typed trace-end marker without touching render-thread state or a GL sync object. */
    @Override
    public void onTraceEnded(TerrainBufferDiagnostics.TraceEndedEvent event) {
        this.enqueue(new TraceEndedBackendEvent(event), false);
    }

    boolean hasCapacity() {
        return this.retirements.size() < this.maxRecords;
    }

    void recordCoverageSkipped(RetirementDescriptor descriptor) {
        this.coverageSkippedCount++;
        this.coverageSkippedBytes += descriptor.oldCapacityBytes();
    }

    void recordFenceUnsupported(String reason) {
        this.unsupportedFenceCount++;
        this.lastUnsupportedReason = reason;
    }

    /** Adds one pending old store, or returns false when the documented record cap has been reached. */
    boolean recordFence(RetirementDescriptor descriptor, FenceProbe fence, long shareGroupId,
                        long contextGeneration, long submittedAtNanos, String unavailableReason) {
        if (!this.hasCapacity()) {
            return false;
        }

        RetirementKey key = new RetirementKey(descriptor.resizeSequence(), descriptor.oldRegistrationToken(),
                descriptor.oldBufferId());
        if (this.retirements.containsKey(key)) {
            throw new IllegalStateException("Duplicate terrain arena retirement identity: " + key);
        }

        Retirement retirement = new Retirement(descriptor, fence, shareGroupId, contextGeneration, submittedAtNanos);
        if (fence == null) {
            retirement.observationUnavailable = true;
            retirement.observationUnavailableReason = unavailableReason;
            this.unsupportedFenceCount++;
            this.lastUnsupportedReason = unavailableReason;
        }
        this.retirements.put(key, retirement);
        return true;
    }

    void recordDeleteRequested(long resizeSequence, long oldRegistrationToken, int oldBufferId, long requestedAtNanos) {
        Retirement retirement = this.retirements.get(new RetirementKey(resizeSequence, oldRegistrationToken, oldBufferId));
        if (retirement != null) {
            retirement.deleteRequested = true;
            retirement.deleteRequestedAtNanos = requestedAtNanos;
        }
    }

    /** Enqueues a backend delete observation through the same bounded seam used by the native listener. */
    void recordActualDelete(DeleteObservation observation) {
        this.enqueue(new NativeDeleteBackendEvent(observation), true);
    }

    /**
     * Drains callbacks, services deferred sync cleanup, and polls at most 128 fences once per share group per second.
     * Returns a shared empty list when there is no work, keeping the per-frame disabled path allocation-free.
     */
    List<Completion> tick(long nowNanos, long currentShareGroupId, boolean diagnosticsEnabled) {
        boolean eventWork = this.backendEventCount.get() > 0 || this.lostDeleteKeyCount.get() > 0
                || this.lostDeleteCoverageAll.get()
                || this.lostTraceEndCoverage.get();
        boolean endWork = !diagnosticsEnabled && !this.retirements.isEmpty();
        boolean cleanupWork = this.deferredCleanupCount > 0 && currentShareGroupId > 0L;
        boolean pollDue = diagnosticsEnabled && currentShareGroupId > 0L && this.hasPollableRetirement(currentShareGroupId)
                && this.pollDue(nowNanos, currentShareGroupId);

        if (!eventWork && !endWork && !cleanupWork && !pollDue) {
            return List.of();
        }

        List<Completion> completions = new ArrayList<>();
        if (eventWork) {
            this.drainBackendEvents(nowNanos, currentShareGroupId, completions);
        }
        if (!diagnosticsEnabled) {
            this.endAll(nowNanos, currentShareGroupId, "debug-disabled", completions);
        }
        if (this.deferredCleanupCount > 0 && currentShareGroupId > 0L) {
            this.cleanupDeferred(currentShareGroupId, MAX_DEFERRED_CLEANUPS_PER_FRAME, nowNanos, completions);
        }
        if (diagnosticsEnabled && currentShareGroupId > 0L && this.hasPollableRetirement(currentShareGroupId)
                && this.pollDue(nowNanos, currentShareGroupId)) {
            this.lastPollNanos = nowNanos;
            this.lastPollShareGroup = currentShareGroupId;
            this.lastPollNanosByShareGroup.put(currentShareGroupId, nowNanos);
            this.poll(currentShareGroupId, nowNanos, MAX_FENCE_POLLS_PER_SAMPLE, completions);
        }
        return completions;
    }

    /** Ends all observations owned by one manager without claiming their old stores were reclaimed. */
    List<Completion> endOwner(long ownerId, long currentShareGroupId, long nowNanos, String reason) {
        List<Completion> completions = new ArrayList<>();
        this.drainBackendEvents(nowNanos, currentShareGroupId, completions);
        for (Retirement retirement : new ArrayList<>(this.retirements.values())) {
            if (retirement.descriptor.ownerId() == ownerId) {
                this.requestEnd(retirement, reason, currentShareGroupId, nowNanos, completions);
            }
        }
        this.cleanupDeferred(currentShareGroupId, MAX_RECORDS, nowNanos, completions);
        return completions;
    }

    /** Returns cumulative and current gauges for the periodic performance report. */
    Snapshot snapshot() {
        int pendingOldCount = 0;
        long pendingOldCapacityBytes = 0L;
        int pendingFenceCount = 0;
        int deleteRequestedNotReturnedCount = 0;
        int deleteReturnedFencePendingCount = 0;
        int deleteEventUnknownCount = 0;
        int observationUnavailableCount = 0;

        for (Retirement retirement : this.retirements.values()) {
            if (!retirement.signalObserved && !retirement.observationEnded && !retirement.deleteFailed
                    && !retirement.deleteEventUnknown) {
                pendingOldCount++;
                pendingOldCapacityBytes += retirement.descriptor.oldCapacityBytes();
            }
            if (retirement.fence != null && !retirement.signalObserved) {
                pendingFenceCount++;
            }
            if (retirement.deleteRequested && !retirement.deleteReturned && !retirement.deleteFailed
                    && !retirement.deleteEventUnknown) {
                deleteRequestedNotReturnedCount++;
            }
            if (retirement.deleteReturned && !retirement.signalObserved && !retirement.observationEnded
                    && !retirement.observationUnavailable) {
                deleteReturnedFencePendingCount++;
            }
            if (retirement.deleteEventUnknown) {
                deleteEventUnknownCount++;
            }
            if (retirement.observationUnavailable) {
                observationUnavailableCount++;
            }
        }

        return new Snapshot(this.retirements.size(), pendingOldCount, pendingOldCapacityBytes, pendingFenceCount,
                this.deferredCleanupCount, deleteRequestedNotReturnedCount, deleteReturnedFencePendingCount,
                deleteEventUnknownCount, observationUnavailableCount, this.coverageSkippedCount,
                this.coverageSkippedBytes, this.unsupportedFenceCount, this.observedSignalCount,
                this.observationEndedCount, this.contextRetiredWithoutGLCount, this.actualDeleteReturnedCount,
                this.actualDeleteFailureCount, this.deleteReturnedFencePendingObservedCount,
                this.unmatchedDeleteEventCount, this.wrongShareGroupDeleteEventCount, this.fencePollFailureCount,
                this.fenceDeleteFailureCount, this.deferredContextCount, this.backendEventOverflowCount.get(),
                this.backendEventCount.get(), this.lastPollShareGroup, this.lastPollNanos, this.lastPollCount,
                this.lastPollDeferredCount, this.lastUnsupportedReason, this.latestSignalWindowLowerBoundNanos,
                this.latestSignalWindowUpperBoundNanos, this.widestSignalWindowNanos);
    }

    private void enqueue(BackendEvent event, boolean deleteEvent) {
        int count = this.backendEventCount.incrementAndGet();
        if (count > this.maxQueuedBackendEvents) {
            this.backendEventCount.decrementAndGet();
            this.backendEventOverflowCount.incrementAndGet();
            if (deleteEvent) {
                this.recordLostDeleteCoverage((NativeDeleteBackendEvent) event);
            } else {
                this.lostTraceEndCoverage.set(true);
            }
            return;
        }
        this.backendEvents.offer(event);
    }

    private boolean pollDue(long nowNanos, long shareGroupId) {
        Long lastPoll = this.lastPollNanosByShareGroup.get(shareGroupId);
        return lastPoll == null || nowNanos - lastPoll >= SAMPLE_INTERVAL_NANOS;
    }

    private void recordLostDeleteCoverage(NativeDeleteBackendEvent event) {
        DeleteObservation observation = event.observation();
        RetirementKey key = new RetirementKey(observation.resizeSequence(), observation.registrationToken(),
                observation.bufferId());
        int count = this.lostDeleteKeyCount.incrementAndGet();
        if (count > MAX_RECORDS) {
            this.lostDeleteKeyCount.decrementAndGet();
            this.lostDeleteCoverageAll.set(true);
            return;
        }
        this.lostDeleteKeys.offer(key);
    }

    private boolean hasPollableRetirement(long shareGroupId) {
        for (Retirement retirement : this.retirements.values()) {
            if (!retirement.endRequested && retirement.shareGroupId == shareGroupId && retirement.fence != null
                    && !retirement.signalObserved && !retirement.observationUnavailable) {
                return true;
            }
        }
        return false;
    }

    private void poll(long shareGroupId, long nowNanos, int limit, List<Completion> completions) {
        List<Retirement> values = new ArrayList<>(this.retirements.values());
        int size = values.size();
        if (size == 0) {
            this.lastPollCount = 0;
            this.lastPollDeferredCount = 0;
            this.pollCursorByShareGroup.put(shareGroupId, 0);
            return;
        }

        int start = Math.floorMod(this.pollCursorByShareGroup.getOrDefault(shareGroupId, 0), size);
        int visited = 0;
        int polled = 0;
        while (visited < size && polled < limit) {
            Retirement retirement = values.get((start + visited) % size);
            visited++;
            if (retirement.endRequested || retirement.shareGroupId != shareGroupId || retirement.fence == null
                    || retirement.signalObserved || retirement.observationUnavailable) {
                continue;
            }

            polled++;
            try {
                if (retirement.fence.isCompleted()) {
                    retirement.signalObserved = true;
                    retirement.observedSignalAtNanos = nowNanos;
                    long lowerBound = retirement.lastUnsignaledAtNanos == 0L
                            ? retirement.submittedAtNanos
                            : retirement.lastUnsignaledAtNanos;
                    this.latestSignalWindowLowerBoundNanos = Math.max(0L, lowerBound - retirement.submittedAtNanos);
                    this.latestSignalWindowUpperBoundNanos = Math.max(0L, nowNanos - retirement.submittedAtNanos);
                    this.widestSignalWindowNanos = Math.max(this.widestSignalWindowNanos,
                            this.latestSignalWindowUpperBoundNanos - this.latestSignalWindowLowerBoundNanos);
                    this.observedSignalCount++;
                    completions.add(retirement.completion("fence-signal-observed", nowNanos));
                    this.deleteFence(retirement);
                    this.finishIfObserved(retirement, nowNanos, completions);
                } else {
                    retirement.lastUnsignaledAtNanos = nowNanos;
                    if (retirement.deleteReturned && !retirement.deleteReturnedFencePendingObserved) {
                        retirement.deleteReturnedFencePendingObserved = true;
                        this.deleteReturnedFencePendingObservedCount++;
                    }
                }
            } catch (RuntimeException exception) {
                this.fencePollFailureCount++;
                retirement.observationUnavailable = true;
                retirement.observationUnavailableReason = exception.getClass().getName();
                retirement.endRequested = true;
                retirement.endReason = "fence-poll-failed";
                this.incrementDeferredCleanupCount(retirement);
                this.deleteFence(retirement);
                if (retirement.fence == null) {
                    this.markObservationEnded(retirement, nowNanos, "fence-poll-failed", completions);
                    this.finalizeEndedIfReady(retirement, nowNanos, completions);
                }
            }
        }

        this.pollCursorByShareGroup.put(shareGroupId, (start + visited) % size);
        this.lastPollCount = polled;
        this.lastPollDeferredCount = Math.max(0, size - visited);
    }

    private void drainBackendEvents(long nowNanos, long currentShareGroupId, List<Completion> completions) {
        Set<RetirementKey> terminalDeleteEvents = null;
        BackendEvent event;
        int processedBackendEvents = 0;
        while (processedBackendEvents < MAX_QUEUED_BACKEND_EVENTS
                && (event = this.backendEvents.poll()) != null) {
            processedBackendEvents++;
            this.backendEventCount.decrementAndGet();
            if (event instanceof NativeDeleteBackendEvent deleteEvent) {
                DeleteObservation observation = deleteEvent.observation();
                RetirementKey key = new RetirementKey(observation.resizeSequence(),
                        observation.registrationToken(), observation.bufferId());
                if (terminalDeleteEvents == null) {
                    terminalDeleteEvents = new HashSet<>();
                }
                if (!terminalDeleteEvents.contains(key)
                        && this.handleNativeDelete(observation, nowNanos, currentShareGroupId, completions)) {
                    terminalDeleteEvents.add(key);
                }
            } else if (event instanceof TraceEndedBackendEvent traceEvent) {
                this.handleTraceEnded(traceEvent.event(), nowNanos, currentShareGroupId, completions);
            }
        }

        RetirementKey lostDeleteKey;
        while ((lostDeleteKey = this.lostDeleteKeys.poll()) != null) {
            this.lostDeleteKeyCount.decrementAndGet();
            Retirement retirement = this.retirements.get(lostDeleteKey);
            if (retirement != null) {
                retirement.deleteEventUnknown = true;
                this.finalizeEndedIfReady(retirement, nowNanos, completions);
            }
        }
        if (this.lostDeleteCoverageAll.getAndSet(false)) {
            for (Retirement retirement : new ArrayList<>(this.retirements.values())) {
                retirement.deleteEventUnknown = true;
                this.finalizeEndedIfReady(retirement, nowNanos, completions);
            }
        }
        if (this.lostTraceEndCoverage.getAndSet(false)) {
            for (Retirement retirement : new ArrayList<>(this.retirements.values())) {
                retirement.deleteEventUnknown = true;
                this.requestEnd(retirement, "trace-end-event-overflow", currentShareGroupId, nowNanos, completions);
            }
        }
    }

    private boolean handleNativeDelete(DeleteObservation event, long nowNanos, long currentShareGroupId,
                                       List<Completion> completions) {
        Retirement retirement = this.retirements.get(new RetirementKey(event.resizeSequence(),
                event.registrationToken(), event.bufferId()));
        if (retirement == null) {
            this.unmatchedDeleteEventCount++;
            return false;
        }
        if (event.contextShareGroupId() == 0L) {
            retirement.deleteEventUnknown = true;
            this.finalizeEndedIfReady(retirement, nowNanos, completions);
            return false;
        }
        if (retirement.shareGroupId == 0L || retirement.shareGroupId != event.contextShareGroupId()
                || retirement.shareGroupId != event.registeredShareGroupId()) {
            this.wrongShareGroupDeleteEventCount++;
            retirement.deleteEventUnknown = true;
            this.finalizeEndedIfReady(retirement, nowNanos, completions);
            return false;
        }
        // Redirect wrappers can report the same delete again while an enclosing call unwinds. Keep the first
        // valid terminal callback for this retirement identity; it is the innermost wrapper's actual return.
        if (retirement.deleteReturned || retirement.deleteFailed) {
            return true;
        }
        retirement.deleteEventUnknown = false;

        if (event.returned()) {
            retirement.deleteReturned = true;
            retirement.deleteReturnedAtNanos = event.completedAtNanos();
            this.actualDeleteReturnedCount++;
            if (retirement.observationEnded || retirement.observationUnavailable) {
                this.finalizeEndedIfReady(retirement, nowNanos, completions);
            } else {
                this.finishIfObserved(retirement, nowNanos, completions);
            }
        } else {
            retirement.deleteFailed = true;
            retirement.lastDeleteFailureClass = event.failureClass();
            this.actualDeleteFailureCount++;
            this.requestEnd(retirement, "native-delete-failed", currentShareGroupId, nowNanos, completions);
        }
        return true;
    }

    private void handleTraceEnded(TerrainBufferDiagnostics.TraceEndedEvent event, long nowNanos,
                                  long currentShareGroupId, List<Completion> completions) {
        switch (event.reason()) {
            case DEBUG_DISABLED -> {
                for (Retirement retirement : new ArrayList<>(this.retirements.values())) {
                    retirement.deleteEventUnknown = true;
                    this.requestEnd(retirement, "debug-disabled", currentShareGroupId, nowNanos, completions);
                }
            }
            case ARENA_UNREGISTERED -> {
                for (Retirement retirement : new ArrayList<>(this.retirements.values())) {
                    if (retirement.descriptor.arenaId() == event.arenaId()) {
                        retirement.deleteEventUnknown = true;
                        this.requestEnd(retirement, "arena-unregistered", currentShareGroupId, nowNanos, completions);
                    }
                }
            }
            case REPLACEMENT_CANCELLED -> {
                for (Retirement retirement : new ArrayList<>(this.retirements.values())) {
                    if (retirement.descriptor.resizeSequence() == event.resizeSequence()
                            && retirement.descriptor.oldRegistrationToken() == event.registrationToken()) {
                        retirement.deleteEventUnknown = true;
                        this.requestEnd(retirement, "replacement-cancelled", currentShareGroupId, nowNanos, completions);
                    }
                }
            }
            case CONTEXT_RETIRED -> this.contextRetired(event.shareGroupId(), nowNanos, completions);
        }
    }

    private void contextRetired(long shareGroupId, long nowNanos, List<Completion> completions) {
        if (shareGroupId <= 0L) {
            this.deferredContextCount++;
            return;
        }
        if (!GLContextRegistryProvider.get().liveContextsInShareGroup(shareGroupId).isEmpty()) {
            return;
        }

        Iterator<Retirement> iterator = this.retirements.values().iterator();
        while (iterator.hasNext()) {
            Retirement retirement = iterator.next();
            if (retirement.shareGroupId == shareGroupId) {
                retirement.fence = null;
                retirement.observationUnavailable = true;
                retirement.observationUnavailableReason = "final-share-group-context-retired";
                retirement.deleteEventUnknown = true;
                retirement.observationEnded = true;
                retirement.observationEndedAtNanos = nowNanos;
                retirement.endReason = "final-share-group-context-retired";
                this.observationEndedCount++;
                this.contextRetiredWithoutGLCount++;
                completions.add(retirement.completion("observation-ended-context-retired", nowNanos));
                iterator.remove();
            }
        }
    }

    private void endAll(long nowNanos, long currentShareGroupId, String reason, List<Completion> completions) {
        for (Retirement retirement : new ArrayList<>(this.retirements.values())) {
            this.requestEnd(retirement, reason, currentShareGroupId, nowNanos, completions);
        }
    }

    private void requestEnd(Retirement retirement, String reason, long currentShareGroupId, long nowNanos,
                            List<Completion> completions) {
        if (!retirement.endRequested) {
            retirement.endRequested = true;
            retirement.endReason = reason;
            this.deferredCleanupCount++;
            retirement.deferredCleanupCounted = true;
        }
        if (currentShareGroupId == retirement.shareGroupId) {
            this.deleteFence(retirement);
            if (retirement.fence == null) {
                this.markObservationEnded(retirement, nowNanos, reason, completions);
                this.finalizeEndedIfReady(retirement, nowNanos, completions);
            }
        } else {
            this.deferredContextCount++;
        }
    }

    private void cleanupDeferred(long currentShareGroupId, int limit, long nowNanos, List<Completion> completions) {
        if (currentShareGroupId <= 0L) {
            return;
        }

        int cleaned = 0;
        for (Retirement retirement : new ArrayList<>(this.retirements.values())) {
            if (cleaned >= limit) {
                break;
            }
            if (!retirement.endRequested || retirement.shareGroupId != currentShareGroupId) {
                continue;
            }

            this.deleteFence(retirement);
            if (retirement.fence == null) {
                this.markObservationEnded(retirement, nowNanos, retirement.endReason, completions);
                this.finalizeEndedIfReady(retirement, nowNanos, completions);
                cleaned++;
            }
        }
    }

    private void deleteFence(Retirement retirement) {
        if (retirement.fence == null) {
            return;
        }
        try {
            retirement.fence.delete();
            retirement.fence = null;
        } catch (RuntimeException exception) {
            retirement.lastFenceFailureClass = exception.getClass().getName();
            this.fenceDeleteFailureCount++;
        }
    }

    private void markObservationEnded(Retirement retirement, long nowNanos, String reason,
                                      List<Completion> completions) {
        if (retirement.observationEnded) {
            return;
        }
        retirement.observationEnded = true;
        retirement.observationEndedAtNanos = nowNanos;
        retirement.endReason = reason;
        if (retirement.deferredCleanupCounted) {
            this.deferredCleanupCount--;
            retirement.deferredCleanupCounted = false;
        }
        this.observationEndedCount++;
        completions.add(retirement.completion("observation-ended", nowNanos));
    }

    private void finalizeEndedIfReady(Retirement retirement, long nowNanos, List<Completion> completions) {
        if (retirement.fence != null) {
            return;
        }
        if (retirement.signalObserved && retirement.deleteReturned) {
            completions.add(retirement.completion("delete-returned-after-fence-signal", nowNanos));
            this.remove(retirement);
        } else if (retirement.observationUnavailable && (retirement.deleteReturned || retirement.deleteFailed)) {
            completions.add(retirement.completion("delete-outcome-observation-unavailable", nowNanos));
            this.remove(retirement);
        } else if (retirement.observationUnavailable && retirement.deleteEventUnknown) {
            completions.add(retirement.completion("delete-event-coverage-unknown", nowNanos));
            this.remove(retirement);
        } else if (retirement.observationEnded
                && (retirement.deleteReturned || retirement.deleteFailed || retirement.deleteEventUnknown)) {
            completions.add(retirement.completion("observation-ended-without-reclaim-claim", nowNanos));
            this.remove(retirement);
        } else if (retirement.deleteEventUnknown && retirement.signalObserved) {
            completions.add(retirement.completion("fence-signal-delete-event-unknown", nowNanos));
            this.remove(retirement);
        }
    }

    private void finishIfObserved(Retirement retirement, long nowNanos, List<Completion> completions) {
        if (retirement.signalObserved && retirement.deleteReturned && retirement.fence == null) {
            completions.add(retirement.completion("delete-returned-and-fence-signal-observed", nowNanos));
            this.remove(retirement);
        } else if (retirement.signalObserved && retirement.deleteEventUnknown && retirement.fence == null) {
            completions.add(retirement.completion("fence-signal-delete-event-unknown", nowNanos));
            this.remove(retirement);
        }
    }

    private void incrementDeferredCleanupCount(Retirement retirement) {
        if (!retirement.endRequested) {
            retirement.endRequested = true;
            this.deferredCleanupCount++;
            retirement.deferredCleanupCounted = true;
        }
    }

    private void remove(Retirement retirement) {
        if (retirement.deferredCleanupCounted) {
            this.deferredCleanupCount--;
            retirement.deferredCleanupCounted = false;
        }
        this.retirements.remove(new RetirementKey(retirement.descriptor.resizeSequence(),
                retirement.descriptor.oldRegistrationToken(), retirement.descriptor.oldBufferId()));
    }

    interface FenceProbe {
        boolean isCompleted();

        void delete();
    }

    record RetirementDescriptor(long ownerId, long arenaId, String arenaType, int stride, int regionId,
                                int regionX, int regionY, int regionZ, long resizeSequence,
                                long oldRegistrationToken, long newRegistrationToken, int oldBufferId,
                                int newBufferId, long oldCapacityBytes, long newCapacityBytes, long usedBytes,
                                long requestBytes, int copySpanCount, boolean baseline) {
    }

    record DeleteObservation(long resizeSequence, long registrationToken, int bufferId,
                             long contextShareGroupId, long registeredShareGroupId,
                             long completedAtNanos, boolean returned, String failureClass) {
        static DeleteObservation from(TerrainBufferDiagnostics.NativeDeleteEvent event) {
            return new DeleteObservation(event.resizeSequence(), event.registrationToken(), event.bufferId(),
                    event.shareGroupId(), event.registeredShareGroupId(), event.completedAtNanos(),
                    event.outcome() == TerrainBufferDiagnostics.Outcome.NATIVE_CALL_RETURNED,
                    event.failureClass());
        }
    }

    record Completion(RetirementDescriptor descriptor, String status, long submittedAtNanos,
                      long lastUnsignaledAtNanos, long observedSignalAtNanos, boolean deleteRequested,
                      long deleteRequestedAtNanos, boolean deleteReturned, long deleteReturnedAtNanos,
                      boolean deleteFailed, String deleteFailureClass, long shareGroupId, long contextGeneration,
                      boolean deleteEventUnknown, boolean observationUnavailable,
                      String observationUnavailableReason, long observationEndedAtNanos,
                      String observationEndReason, long signalWindowLowerBoundNanos,
                      long signalWindowUpperBoundNanos) {
    }

    record Snapshot(int trackedRecordCount, int pendingOldCount, long pendingOldCapacityBytes,
                    int pendingFenceCount, int deferredCleanupCount, int deleteRequestedNotReturnedCount,
                    int deleteReturnedFencePendingCount, int deleteEventUnknownCount,
                    int observationUnavailableCount, long coverageSkippedCount, long coverageSkippedBytes,
                    long unsupportedFenceCount, long observedSignalCount, long observationEndedCount,
                    long contextRetiredWithoutGLCount, long actualDeleteReturnedCount,
                    long actualDeleteFailureCount, long deleteReturnedFencePendingObservedCount,
                    long unmatchedDeleteEventCount, long wrongShareGroupDeleteEventCount,
                    long fencePollFailureCount, long fenceDeleteFailureCount, long deferredContextCount,
                    long backendEventOverflowCount, int backendEventQueueDepth, long lastPollShareGroup,
                    long lastPollNanos, int lastPollCount, int lastPollDeferredCount,
                    String lastUnsupportedReason, long latestSignalWindowLowerBoundNanos,
                    long latestSignalWindowUpperBoundNanos, long widestSignalWindowNanos) {
    }

    private record RetirementKey(long resizeSequence, long registrationToken, int bufferId) {
    }

    private interface BackendEvent {
    }

    private record NativeDeleteBackendEvent(DeleteObservation observation) implements BackendEvent {
    }

    private record TraceEndedBackendEvent(TerrainBufferDiagnostics.TraceEndedEvent event) implements BackendEvent {
    }

    private static final class Retirement {
        private final RetirementDescriptor descriptor;
        private final long shareGroupId;
        private final long contextGeneration;
        private final long submittedAtNanos;
        private FenceProbe fence;
        private long lastUnsignaledAtNanos;
        private long observedSignalAtNanos;
        private long deleteRequestedAtNanos;
        private long deleteReturnedAtNanos;
        private long observationEndedAtNanos;
        private boolean signalObserved;
        private boolean deleteRequested;
        private boolean deleteReturned;
        private boolean deleteFailed;
        private boolean deleteReturnedFencePendingObserved;
        private boolean deleteEventUnknown;
        private boolean observationUnavailable;
        private boolean observationEnded;
        private boolean endRequested;
        private boolean deferredCleanupCounted;
        private String endReason = "none";
        private String lastDeleteFailureClass = "none";
        private String lastFenceFailureClass = "none";
        private String observationUnavailableReason = "none";

        private Retirement(RetirementDescriptor descriptor, FenceProbe fence, long shareGroupId,
                           long contextGeneration, long submittedAtNanos) {
            this.descriptor = descriptor;
            this.fence = fence;
            this.shareGroupId = shareGroupId;
            this.contextGeneration = contextGeneration;
            this.submittedAtNanos = submittedAtNanos;
        }

        private Completion completion(String status, long nowNanos) {
            long lower = this.lastUnsignaledAtNanos == 0L ? this.submittedAtNanos : this.lastUnsignaledAtNanos;
            return new Completion(this.descriptor, status, this.submittedAtNanos, this.lastUnsignaledAtNanos,
                    this.observedSignalAtNanos, this.deleteRequested, this.deleteRequestedAtNanos,
                    this.deleteReturned, this.deleteReturnedAtNanos, this.deleteFailed,
                    this.lastDeleteFailureClass, this.shareGroupId, this.contextGeneration,
                    this.deleteEventUnknown, this.observationUnavailable, this.observationUnavailableReason,
                    this.observationEndedAtNanos, this.endRequested ? this.endReason : "none",
                    Math.max(0L, lower - this.submittedAtNanos),
                    Math.max(0L, this.observedSignalAtNanos - this.submittedAtNanos));
        }
    }
}
