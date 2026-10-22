package com.gtnewhorizons.angelica.glsm.debug;

import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
import com.gtnewhorizons.angelica.glsm.context.GLContextRegistryProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.Buffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records actual native buffer storage and deletion calls for explicitly marked terrain arena buffers.
 * Markers contain only scalar identities and remain live only until native deletion, arena disposal,
 * debug tracing shutdown, or final share-group retirement.
 */
public final class TerrainBufferDiagnostics {
    private static final Logger LOGGER = LogManager.getLogger("ActiniumMemory");
    private static final Tracker TRACKER = new Tracker(GLSMPerfDebug.isEnabled());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final ArenaBufferScope NO_OP_SCOPE = new ArenaBufferScope(null, null);

    static {
        GLContextRegistryProvider.addRetirementListener(TerrainBufferDiagnostics::onContextRetired);
    }

    private TerrainBufferDiagnostics() {
    }

    /** Describes which native operation a small terrain tracing scope expects to observe. */
    public enum BufferOperation {
        /** Allocates the initial zero-capacity store owned by a new arena. */
        INITIAL_STORAGE,
        /** Allocates the replacement store while an arena is resized. */
        REPLACEMENT_STORAGE,
        /** Issues the native delete for an old or final arena store. */
        RETIRED_DELETE
    }

    /** Describes the result of the Java-to-native call, without inferring the OpenGL error state. */
    public enum Outcome {
        /** LWJGL returned from the native call; OpenGL errors were not queried. */
        NATIVE_CALL_RETURNED,
        /** LWJGL threw in Java before the wrapper returned. */
        JAVA_FAILURE
    }

    /** Describes why observation for a marked buffer lifetime stopped without a native delete event. */
    public enum TraceEndReason {
        /** The in-game performance diagnostics setting was turned off. */
        DEBUG_DISABLED,
        /** Its native context retired, or its final share group context disappeared. */
        CONTEXT_RETIRED,
        /** The owning arena ended without issuing a native delete through an observed wrapper. */
        ARENA_UNREGISTERED,
        /** A failed or abandoned replacement stopped being tracked. */
        REPLACEMENT_CANCELLED
    }

    /** Receives synchronous observations from the thread that issued the actual native call. */
    public interface Listener {
        /** Receives one event after an observed native delete returned or threw. */
        void onNativeDelete(NativeDeleteEvent event);

        /** Receives a typed marker when a diagnostic lifetime ended without a native delete claim. */
        default void onTraceEnded(TraceEndedEvent event) {
        }
    }

    /** Identifies a paired old/new arena-store replacement independently of GLuint reuse. */
    public record ResizeToken(long resizeSequence, long oldRegistrationToken,
                              long newRegistrationToken, boolean baseline) {
    }

    /** A native deletion result for one explicitly registered arena buffer. */
    public record NativeDeleteEvent(long callSequence, long startedAtNanos, long completedAtNanos,
                                    long contextGeneration, long shareGroupId, long registeredShareGroupId,
                                    long arenaId, String arenaType, int stride,
                                    int regionId, int regionX, int regionY, int regionZ,
                                    int bufferId, long registrationToken, long registeredCapacityBytes,
                                    long resizeSequence,
                                    Outcome outcome, String failureClass) {
    }

    /** Describes the end of a marked trace lifetime and is not evidence of a native delete. */
    public record TraceEndedEvent(long eventSequence, long timeNanos, TraceEndReason reason,
                                  long contextGeneration, long shareGroupId,
                                  long arenaId, String arenaType, int stride,
                                  int regionId, int regionX, int regionY, int regionZ,
                                  int bufferId, long registrationToken, long registeredCapacityBytes,
                                  long resizeSequence) {
    }

    /** Result of one native glBufferData call for a marked arena buffer. */
    record NativeBufferDataEvent(long callSequence, long startedAtNanos, long completedAtNanos,
                                 long contextGeneration, long shareGroupId, int target,
                                 boolean boundBufferKnown, int boundBufferId,
                                 boolean scopeExpectedBufferKnown, int scopeExpectedBufferId,
                                 boolean scopeBindingMismatch,
                                 long arenaId, String arenaType, int stride,
                                 int regionId, int regionX, int regionY, int regionZ,
                                 long registrationToken, long registeredShareGroupId,
                                 long registeredCapacityBytes, long resizeSequence,
                                 long requestedBytes, Outcome outcome, String failureClass) {
    }

    /** Registers one arena-owned buffer while the in-game diagnostics setting is enabled. */
    public static long registerArenaBuffer(long arenaId, String arenaType, int stride,
                                           int regionId, int regionX, int regionY, int regionZ,
                                           int bufferId, long capacityBytes) {
        if (!GLSMPerfDebug.isEnabled()) {
            return 0L;
        }

        final GLContextIdentity identity = currentIdentity();
        if (identity == null) {
            LOGGER.info("[ActiniumMemory][native-buffer] event=arena-register-skipped reason=unregistered-context arenaId={} bufferId={}",
                arenaId, bufferId);
            return 0L;
        }
        return TRACKER.registerArenaBuffer(identity, arenaId, arenaType, stride,
            regionId, regionX, regionY, regionZ, bufferId, capacityBytes);
    }

    /**
     * Marks both stores involved in a replacement. If diagnostics started after the old store was
     * created, the supplied metadata establishes an explicit baseline for this resize.
     */
    public static ResizeToken beginArenaReplacement(long arenaId, String arenaType, int stride,
                                                     int regionId, int regionX, int regionY, int regionZ,
                                                     int oldBufferId, long oldCapacityBytes,
                                                     int newBufferId, long newCapacityBytes,
                                                     long usedBytes, long requestBytes, int copySpanCount) {
        if (!GLSMPerfDebug.isEnabled()) {
            return null;
        }

        final GLContextIdentity identity = currentIdentity();
        if (identity == null) {
            LOGGER.info("[ActiniumMemory][native-buffer] event=arena-replacement-skipped reason=unregistered-context arenaId={} oldBufferId={} newBufferId={}",
                arenaId, oldBufferId, newBufferId);
            return null;
        }
        return TRACKER.beginArenaReplacement(identity, arenaId, arenaType, stride,
            regionId, regionX, regionY, regionZ, oldBufferId, oldCapacityBytes,
            newBufferId, newCapacityBytes, usedBytes, requestBytes, copySpanCount);
    }

    /**
     * Stops tracking a replacement after a Java-side allocation or copy failure. This is a trace
     * cancellation only; it does not claim that either OpenGL buffer was deleted.
     */
    public static boolean cancelArenaReplacement(ResizeToken token) {
        if (token == null) {
            return false;
        }
        final TraceEndedEvent event = TRACKER.cancelArenaReplacement(token);
        if (event == null) {
            return false;
        }
        emitTraceEnded(event);
        return true;
    }

    /**
     * Opens a scope around one arena GL operation. The scope carries scalar IDs only and helps
     * report an unknown observed binding without presenting the expected ID as an actual binding.
     */
    public static ArenaBufferScope openArenaBufferOperation(long arenaId, int bufferId,
                                                             long registrationToken, long resizeSequence,
                                                             BufferOperation operation) {
        if (!GLSMPerfDebug.isEnabled() || registrationToken == 0L) {
            return NO_OP_SCOPE;
        }
        Objects.requireNonNull(operation, "operation");
        final ArenaBufferScope scope = TRACKER.openArenaBufferOperation(
            arenaId, bufferId, registrationToken, resizeSequence, operation);
        return scope == null ? NO_OP_SCOPE : scope;
    }

    /** Removes a marker when an arena lifetime ends without issuing an observed native delete. */
    public static void unregisterArenaBuffer(long arenaId, int bufferId) {
        if (!GLSMPerfDebug.isEnabled()) {
            return;
        }
        for (TraceEndedEvent event : TRACKER.unregisterArenaBuffer(arenaId, bufferId)) {
            emitTraceEnded(event);
        }
    }

    /** Adds a listener for actual native deletion outcomes and trace-end markers. */
    public static void addListener(Listener listener) {
        LISTENERS.addIfAbsent(Objects.requireNonNull(listener, "listener"));
    }

    /** Removes a previously added native deletion listener. */
    public static boolean removeListener(Listener listener) {
        return LISTENERS.remove(Objects.requireNonNull(listener, "listener"));
    }

    /** Records a binding after its GL15C or GL30C call returned, without querying OpenGL state. */
    public static void observeBufferBinding(int target, int bufferId) {
        if (!GLSMPerfDebug.isEnabled()) {
            return;
        }
        try {
            TRACKER.observeBufferBinding(currentIdentity(), target, bufferId);
        } catch (RuntimeException | Error failure) {
            logObserverFailure("buffer-binding", failure);
        }
    }

    /** Begins observation immediately before the native glBufferData invocation. */
    public static NativeBufferDataCall beginNativeBufferData(int target, long requestedBytes) {
        if (!GLSMPerfDebug.isEnabled()) {
            return null;
        }
        return beginNativeBufferDataEnabled(target, requestedBytes);
    }

    /** Begins observation for the remaining elements in a typed native upload buffer. */
    public static NativeBufferDataCall beginNativeBufferData(int target, Buffer data, int elementBytes) {
        if (!GLSMPerfDebug.isEnabled()) {
            return null;
        }
        return beginNativeBufferDataEnabled(target,
            data == null ? -1L : data.remaining() * (long) elementBytes);
    }

    /** Begins observation for the byte span of one typed native upload array. */
    public static NativeBufferDataCall beginNativeBufferData(int target, int[] data) {
        if (!GLSMPerfDebug.isEnabled()) {
            return null;
        }
        return beginNativeBufferDataEnabled(target, data == null ? -1L : data.length * (long) Integer.BYTES);
    }

    /** Begins observation for the byte span of one typed native upload array. */
    public static NativeBufferDataCall beginNativeBufferData(int target, float[] data) {
        if (!GLSMPerfDebug.isEnabled()) {
            return null;
        }
        return beginNativeBufferDataEnabled(target, data == null ? -1L : data.length * (long) Float.BYTES);
    }

    private static NativeBufferDataCall beginNativeBufferDataEnabled(int target, long requestedBytes) {
        final NativeBufferDataCall call;
        try {
            call = TRACKER.beginNativeBufferData(currentIdentity(), target, requestedBytes);
        } catch (RuntimeException | Error failure) {
            logObserverFailure("buffer-data-begin", failure);
            return null;
        }
        if (call != null) {
            try {
                logBufferDataStart(call.event);
            } catch (RuntimeException | Error failure) {
                logObserverFailure("buffer-data-start-log", failure);
            }
        }
        return call;
    }

    /** Completes an observed glBufferData call and logs either its Java return or Java failure. */
    public static void finishNativeBufferData(NativeBufferDataCall call, Throwable failure) {
        if (call == null) {
            return;
        }
        final NativeBufferDataEvent event;
        try {
            event = TRACKER.finishNativeBufferData(call, failure);
        } catch (RuntimeException | Error observerFailure) {
            logObserverFailure("buffer-data-finish", observerFailure);
            return;
        }
        try {
            logBufferDataFinish(event);
        } catch (RuntimeException | Error observerFailure) {
            logObserverFailure("buffer-data-finish-log", observerFailure);
        }
    }

    /** Begins observation immediately before the native single-buffer delete invocation. */
    public static NativeDeleteCall beginNativeDelete(int bufferId) {
        if (!GLSMPerfDebug.isEnabled()) {
            return null;
        }
        final NativeDeleteCall call;
        try {
            call = TRACKER.beginNativeDelete(currentIdentity(), bufferId, currentScope());
        } catch (RuntimeException | Error failure) {
            logObserverFailure("buffer-delete-begin", failure);
            return null;
        }
        try {
            logNativeDeleteStart(call);
        } catch (RuntimeException | Error failure) {
            logObserverFailure("buffer-delete-start-log", failure);
        }
        return call;
    }

    /** Begins observation for the IDs in an IntBuffer without changing its position or limit. */
    public static NativeDeleteCall beginNativeDelete(IntBuffer bufferIds) {
        if (!GLSMPerfDebug.isEnabled()) {
            return null;
        }
        final NativeDeleteCall call;
        try {
            call = TRACKER.beginNativeDelete(currentIdentity(), bufferIds, currentScope());
        } catch (RuntimeException | Error failure) {
            logObserverFailure("buffer-delete-buffer-begin", failure);
            return null;
        }
        try {
            logNativeDeleteStart(call);
        } catch (RuntimeException | Error failure) {
            logObserverFailure("buffer-delete-buffer-start-log", failure);
        }
        return call;
    }

    /** Completes an actual native delete and synchronously notifies listeners of its outcome. */
    public static void finishNativeDelete(NativeDeleteCall call, Throwable failure) {
        if (call == null) {
            return;
        }
        final List<NativeDeleteEvent> events;
        try {
            events = TRACKER.finishNativeDelete(call, failure);
        } catch (RuntimeException | Error observerFailure) {
            logObserverFailure("buffer-delete-finish", observerFailure);
            return;
        }
        for (NativeDeleteEvent event : events) {
            try {
                logNativeDelete(event);
            } catch (RuntimeException | Error observerFailure) {
                logObserverFailure("buffer-delete-finish-log", observerFailure);
            }
            for (Listener listener : LISTENERS) {
                try {
                    listener.onNativeDelete(event);
                } catch (Throwable listenerFailure) {
                    logObserverFailure("delete-listener-" + listener.getClass().getName(), listenerFailure);
                }
            }
        }
    }

    /** Updates marker and binding lifetimes when the game-internal diagnostics toggle changes. */
    static void onDebugEnabledChanged(boolean enabled) {
        final TraceEndResult result;
        try {
            result = TRACKER.setEnabled(enabled, currentIdentity());
        } catch (RuntimeException | Error failure) {
            logObserverFailure("debug-toggle", failure);
            return;
        }
        if (result == null) {
            return;
        }
        if (!enabled) {
            try {
                logTraceSummary(TraceEndReason.DEBUG_DISABLED, result.counters);
            } catch (RuntimeException | Error failure) {
                logObserverFailure("debug-toggle-summary-log", failure);
            }
        }
        for (TraceEndedEvent event : result.events) {
            emitTraceEnded(event);
        }
    }

    private static GLContextIdentity currentIdentity() {
        return GLContextRegistryProvider.get().currentBinding();
    }

    private static ArenaBufferScopeData currentScope() {
        return TRACKER.currentScope();
    }

    private static void onContextRetired(GLContextIdentity identity) {
        if (!GLSMPerfDebug.isEnabled()) {
            return;
        }
        try {
            final boolean finalShareGroupContext = GLContextRegistryProvider.get()
                .liveContextsInShareGroup(identity.shareGroupId()).isEmpty();
            final List<TraceEndedEvent> events = TRACKER.onContextRetired(identity, finalShareGroupContext);
            for (TraceEndedEvent event : events) {
                emitTraceEnded(event);
            }
            if (finalShareGroupContext && !events.isEmpty()) {
                logTraceSummary(TraceEndReason.CONTEXT_RETIRED, TRACKER.counters());
            }
        } catch (RuntimeException | Error failure) {
            logObserverFailure("context-retired", failure);
        }
    }

    private static void emitTraceEnded(TraceEndedEvent event) {
        try {
            LOGGER.info("[ActiniumMemory][native-buffer] event=trace-ended reason={} eventSequence={} timeNanos={} contextGeneration={} shareGroup={} arenaId={} arenaType={} stride={} regionId={} regionX={} regionY={} regionZ={} bufferId={} registrationToken={} registeredCapacityBytes={} resizeSequence={} nativeDelete=false",
                event.reason(), event.eventSequence(), event.timeNanos(), known(event.contextGeneration()),
                known(event.shareGroupId()), known(event.arenaId()), event.arenaType(), event.stride(),
                event.regionId(), event.regionX(), event.regionY(), event.regionZ(),
                known(event.bufferId()), known(event.registrationToken()), event.registeredCapacityBytes(),
                known(event.resizeSequence()));
        } catch (RuntimeException | Error failure) {
            logObserverFailure("trace-end-log", failure);
        }
        for (Listener listener : LISTENERS) {
            try {
                listener.onTraceEnded(event);
            } catch (Throwable listenerFailure) {
                logObserverFailure("trace-end-listener-" + listener.getClass().getName(), listenerFailure);
            }
        }
    }

    private static void logObserverFailure(String operation, Throwable failure) {
        try {
            LOGGER.warn("[ActiniumMemory][native-buffer] event=observer-failure operation={} reason={}",
                operation, failure.getClass().getName(), failure);
        } catch (RuntimeException | Error loggingFailure) {
            failure.addSuppressed(loggingFailure);
        }
    }

    private static void logBufferDataStart(NativeBufferDataEvent event) {
        LOGGER.info("[ActiniumMemory][native-buffer] event=glBufferData phase=call-entered callSequence={} startedAtNanos={} contextGeneration={} shareGroup={} registeredShareGroup={} target=0x{} boundBufferId={} scopeExpectedBufferId={} scopeBindingMismatch={} arenaId={} arenaType={} stride={} regionId={} regionX={} regionY={} regionZ={} registrationToken={} registeredCapacityBytes={} resizeSequence={} requestedBytes={} outcome=pending glError=not-queried",
            event.callSequence(), event.startedAtNanos(), known(event.contextGeneration()), known(event.shareGroupId()),
            known(event.registeredShareGroupId()), Integer.toHexString(event.target()),
            bufferId(event.boundBufferKnown(), event.boundBufferId()),
            bufferId(event.scopeExpectedBufferKnown(), event.scopeExpectedBufferId()), event.scopeBindingMismatch(),
            event.arenaId(), event.arenaType(), event.stride(), event.regionId(), event.regionX(), event.regionY(),
            event.regionZ(), event.registrationToken(), event.registeredCapacityBytes(),
            known(event.resizeSequence()), event.requestedBytes());
    }

    private static void logBufferDataFinish(NativeBufferDataEvent event) {
        LOGGER.info("[ActiniumMemory][native-buffer] event=glBufferData phase=call-finished callSequence={} completedAtNanos={} elapsedNanos={} contextGeneration={} shareGroup={} registeredShareGroup={} target=0x{} boundBufferId={} scopeExpectedBufferId={} scopeBindingMismatch={} arenaId={} arenaType={} stride={} regionId={} regionX={} regionY={} regionZ={} registrationToken={} registeredCapacityBytes={} resizeSequence={} requestedBytes={} outcome={} failureClass={} glError=not-queried",
            event.callSequence(), event.completedAtNanos(), event.completedAtNanos() - event.startedAtNanos(),
            known(event.contextGeneration()), known(event.shareGroupId()), known(event.registeredShareGroupId()),
            Integer.toHexString(event.target()), bufferId(event.boundBufferKnown(), event.boundBufferId()),
            bufferId(event.scopeExpectedBufferKnown(), event.scopeExpectedBufferId()), event.scopeBindingMismatch(),
            event.arenaId(), event.arenaType(), event.stride(), event.regionId(), event.regionX(), event.regionY(),
            event.regionZ(), event.registrationToken(), event.registeredCapacityBytes(),
            known(event.resizeSequence()), event.requestedBytes(),
            event.outcome(), event.failureClass() == null ? "none" : event.failureClass());
    }

    private static void logNativeDelete(NativeDeleteEvent event) {
        LOGGER.info("[ActiniumMemory][native-buffer] event=glDeleteBuffers phase=call-finished callSequence={} startedAtNanos={} completedAtNanos={} elapsedNanos={} contextGeneration={} shareGroup={} registeredShareGroup={} arenaId={} arenaType={} stride={} regionId={} regionX={} regionY={} regionZ={} bufferId={} registrationToken={} registeredCapacityBytes={} resizeSequence={} outcome={} failureClass={} glError=not-queried",
            event.callSequence(), event.startedAtNanos(), event.completedAtNanos(),
            event.completedAtNanos() - event.startedAtNanos(), known(event.contextGeneration()),
            known(event.shareGroupId()), known(event.registeredShareGroupId()), known(event.arenaId()),
            event.arenaType(), event.stride(), event.regionId(), event.regionX(),
            event.regionY(), event.regionZ(), event.bufferId(), event.registrationToken(),
            event.registeredCapacityBytes(), known(event.resizeSequence()), event.outcome(),
            event.failureClass() == null ? "none" : event.failureClass());
    }

    private static void logNativeDeleteStart(NativeDeleteCall call) {
        if (call == null) {
            return;
        }
        final DeleteCallSnapshot snapshot = call.snapshot;
        for (DeleteEntry entry : snapshot.entries) {
            final Registration registration = entry.registration;
            LOGGER.info("[ActiniumMemory][native-buffer] event=glDeleteBuffers phase=call-entered callSequence={} startedAtNanos={} contextGeneration={} shareGroup={} registeredShareGroup={} arenaId={} arenaType={} stride={} regionId={} regionX={} regionY={} regionZ={} bufferId={} registrationToken={} registeredCapacityBytes={} resizeSequence={} outcome=pending glError=not-queried",
                snapshot.callSequence, snapshot.startedAtNanos, known(snapshot.contextGeneration),
                known(snapshot.shareGroupId), known(registration.shareGroupId), known(registration.arenaId),
                registration.arenaType, known(registration.stride), known(registration.regionId),
                known(registration.regionX), known(registration.regionY), known(registration.regionZ),
                entry.bufferId, registration.registrationToken, registration.capacityBytes,
                known(registration.resizeSequence));
        }
    }

    private static void logTraceSummary(TraceEndReason reason, TraceCounters counters) {
        LOGGER.info("[ActiniumMemory][native-buffer] event=trace-summary reason={} bufferDataCallsStarted={} bufferDataCallsReturned={} bufferDataJavaFailures={} deleteCallsStarted={} deleteCallsReturned={} deleteCallsJavaFailures={} trackedDeleteIdsStarted={} trackedDeleteIdsReturned={} trackedDeleteIdsJavaFailures={}",
            reason, counters.bufferDataCallsStarted, counters.bufferDataCallsReturned,
            counters.bufferDataJavaFailures, counters.deleteCallsStarted,
            counters.deleteCallsReturned, counters.deleteCallsJavaFailures,
            counters.trackedDeleteIdsStarted, counters.trackedDeleteIdsReturned,
            counters.trackedDeleteIdsJavaFailures);
    }

    private static String known(long value) {
        return value == 0L ? "unknown" : Long.toString(value);
    }

    private static String bufferId(boolean known, int value) {
        return known ? Integer.toString(value) : "unknown";
    }

    /** A closeable scope that associates one arena operation with actual native calls. */
    public static final class ArenaBufferScope implements AutoCloseable {
        private final Tracker tracker;
        private final ArenaBufferScopeData data;
        private boolean closed;

        private ArenaBufferScope(Tracker tracker, ArenaBufferScopeData data) {
            this.tracker = tracker;
            this.data = data;
        }

        /** Pops this operation from the current thread's diagnostic scope stack. */
        @Override
        public void close() {
            if (this.data == null || this.closed) {
                return;
            }
            this.tracker.closeScope(this.data);
            this.closed = true;
        }
    }

    /** Opaque token captured immediately before one native glBufferData call. */
    public static final class NativeBufferDataCall {
        private final NativeBufferDataEvent event;

        private NativeBufferDataCall(NativeBufferDataEvent event) {
            this.event = event;
        }

        NativeBufferDataEvent event() {
            return this.event;
        }
    }

    /** Opaque token captured immediately before one native glDeleteBuffers call. */
    public static final class NativeDeleteCall {
        private final DeleteCallSnapshot snapshot;

        private NativeDeleteCall(DeleteCallSnapshot snapshot) {
            this.snapshot = snapshot;
        }
    }

    static final class Tracker {
        private final Map<BufferKey, Registration> registrations = new HashMap<>();
        private final Map<Long, PendingReplacement> pendingReplacements = new HashMap<>();
        private final Map<Long, Map<Integer, Integer>> bindingsByContext = new HashMap<>();
        private final ThreadLocal<ArrayDeque<ArenaBufferScopeData>> operationScopes = ThreadLocal.withInitial(ArrayDeque::new);
        private long nextRegistrationToken = 1L;
        private long nextResizeSequence = 1L;
        private long nextEventSequence = 1L;
        private boolean enabled;
        private TraceCounters counters = TraceCounters.ZERO;

        Tracker(boolean enabled) {
            this.enabled = enabled;
        }

        synchronized long registerArenaBuffer(GLContextIdentity identity, long arenaId, String arenaType,
                                                     int stride, int regionId, int regionX, int regionY, int regionZ,
                                                     int bufferId, long capacityBytes) {
            validateArenaBuffer(arenaId, arenaType, stride, bufferId, capacityBytes);
            final BufferKey key = new BufferKey(identity.shareGroupId(), bufferId);
            final Registration existing = this.registrations.get(key);
            if (existing != null && existing.arenaId == arenaId && existing.stride == stride
                && existing.regionId == regionId && existing.regionX == regionX && existing.regionY == regionY
                && existing.regionZ == regionZ && existing.arenaType.equals(arenaType)) {
                this.registrations.put(key, existing.withCapacity(capacityBytes));
                return existing.registrationToken;
            }

            final Registration registration = new Registration(
                identity.generation(), identity.shareGroupId(), arenaId, arenaType, stride,
                regionId, regionX, regionY, regionZ, bufferId, this.nextRegistrationToken++,
                0L, capacityBytes, 0L, 0L, 0, false, BufferOperation.INITIAL_STORAGE);
            this.registrations.put(key, registration);
            return registration.registrationToken;
        }

        synchronized ResizeToken beginArenaReplacement(GLContextIdentity identity,
                                                                 long arenaId, String arenaType, int stride,
                                                                 int regionId, int regionX, int regionY, int regionZ,
                                                                 int oldBufferId, long oldCapacityBytes,
                                                                 int newBufferId, long newCapacityBytes,
                                                                 long usedBytes, long requestBytes, int copySpanCount) {
            validateArenaBuffer(arenaId, arenaType, stride, oldBufferId, oldCapacityBytes);
            validateArenaBuffer(arenaId, arenaType, stride, newBufferId, newCapacityBytes);
            if (oldBufferId == newBufferId) {
                throw new IllegalArgumentException("A replacement must use distinct old and new buffer IDs");
            }
            if (usedBytes < 0L || requestBytes < 0L || copySpanCount < 0) {
                throw new IllegalArgumentException("Arena replacement byte counts and copy spans must not be negative");
            }

            final long resizeSequence = this.nextResizeSequence++;
            final BufferKey oldKey = new BufferKey(identity.shareGroupId(), oldBufferId);
            final BufferKey newKey = new BufferKey(identity.shareGroupId(), newBufferId);
            final Registration previousOld = this.registrations.get(oldKey);
            final boolean baseline = previousOld == null || previousOld.arenaId != arenaId;
            final long oldToken = baseline ? this.nextRegistrationToken++ : previousOld.registrationToken;
            final long newToken = this.nextRegistrationToken++;

            final Registration oldRegistration = new Registration(
                identity.generation(), identity.shareGroupId(), arenaId, arenaType, stride,
                regionId, regionX, regionY, regionZ, oldBufferId, oldToken, resizeSequence,
                oldCapacityBytes, usedBytes, requestBytes, copySpanCount, baseline, BufferOperation.RETIRED_DELETE);
            final Registration newRegistration = new Registration(
                identity.generation(), identity.shareGroupId(), arenaId, arenaType, stride,
                regionId, regionX, regionY, regionZ, newBufferId, newToken, resizeSequence,
                newCapacityBytes, usedBytes, requestBytes, copySpanCount, baseline, BufferOperation.REPLACEMENT_STORAGE);

            this.registrations.put(oldKey, oldRegistration);
            this.registrations.put(newKey, newRegistration);
            this.pendingReplacements.put(resizeSequence,
                new PendingReplacement(oldKey, newKey, previousOld, oldRegistration, newRegistration));
            return new ResizeToken(resizeSequence, oldToken, newToken, baseline);
        }

        synchronized TraceEndedEvent cancelArenaReplacement(ResizeToken token) {
            final PendingReplacement pending = this.pendingReplacements.get(token.resizeSequence());
            if (pending == null || pending.oldRegistration.registrationToken != token.oldRegistrationToken()
                || pending.newRegistration.registrationToken != token.newRegistrationToken()) {
                return null;
            }
            this.pendingReplacements.remove(token.resizeSequence());
            final Registration currentNew = this.registrations.get(pending.newKey);
            if (currentNew != null && currentNew.registrationToken == token.newRegistrationToken()) {
                this.registrations.remove(pending.newKey);
            }
            final Registration restoredOld = pending.previousOld == null
                ? pending.oldRegistration.asActive(0L, pending.oldRegistration.capacityBytes)
                : pending.previousOld;
            this.registrations.put(pending.oldKey, restoredOld);
            return traceEnded(TraceEndReason.REPLACEMENT_CANCELLED, restoredOld,
                pending.newRegistration.bufferId, pending.newRegistration.registrationToken,
                pending.newRegistration.resizeSequence, 0L, pending.newRegistration.shareGroupId);
        }

        ArenaBufferScope openArenaBufferOperation(long arenaId, int bufferId,
                                                   long registrationToken, long resizeSequence,
                                                   BufferOperation operation) {
            final Registration registration;
            synchronized (this) {
                registration = this.findRegistration(registrationToken);
            }
            if (registration == null || registration.arenaId != arenaId || registration.bufferId != bufferId
                || registration.resizeSequence != resizeSequence || registration.operation != operation) {
                return null;
            }
            final ArenaBufferScopeData data = new ArenaBufferScopeData(
                Thread.currentThread(), registration, resizeSequence, operation);
            this.operationScopes.get().addLast(data);
            return new ArenaBufferScope(this, data);
        }

        private ArenaBufferScopeData currentScope() {
            final ArrayDeque<ArenaBufferScopeData> scopes = this.operationScopes.get();
            return scopes.peekLast();
        }

        private void closeScope(ArenaBufferScopeData data) {
            if (data.ownerThread != Thread.currentThread()) {
                throw new IllegalStateException("Terrain buffer diagnostic scopes must close on their opening thread");
            }
            final ArrayDeque<ArenaBufferScopeData> scopes = this.operationScopes.get();
            if (scopes.peekLast() != data) {
                throw new IllegalStateException("Terrain buffer diagnostic scopes must close in reverse order");
            }
            scopes.removeLast();
            if (scopes.isEmpty()) {
                this.operationScopes.remove();
            }
        }

        private synchronized List<TraceEndedEvent> unregisterArenaBuffer(long arenaId, int bufferId) {
            final List<TraceEndedEvent> events = new ArrayList<>();
            final List<BufferKey> keys = new ArrayList<>();
            for (Map.Entry<BufferKey, Registration> entry : this.registrations.entrySet()) {
                if (entry.getValue().arenaId == arenaId && entry.getValue().bufferId == bufferId) {
                    keys.add(entry.getKey());
                }
            }
            for (BufferKey key : keys) {
                final Registration registration = this.registrations.remove(key);
                if (registration != null) {
                    events.add(traceEnded(TraceEndReason.ARENA_UNREGISTERED, registration,
                        registration.bufferId, registration.registrationToken, registration.resizeSequence,
                        registration.contextGeneration, registration.shareGroupId));
                    this.removePendingFor(registration.registrationToken);
                }
            }
            return events;
        }

        synchronized void observeBufferBinding(GLContextIdentity identity, int target, int bufferId) {
            if (!this.enabled || identity == null) {
                return;
            }
            this.bindingsByContext.computeIfAbsent(identity.generation(), ignored -> new HashMap<>())
                .put(target, bufferId);
        }

        synchronized NativeBufferDataCall beginNativeBufferData(GLContextIdentity identity,
                                                                         int target, long requestedBytes) {
            if (!this.enabled) {
                return null;
            }
            final ArenaBufferScopeData scope = this.currentScope();
            final Map<Integer, Integer> bindings = identity == null ? null : this.bindingsByContext.get(identity.generation());
            final Integer boundBuffer = bindings == null ? null : bindings.get(target);
            final Registration boundRegistration = boundBuffer == null || identity == null
                ? null : this.registrations.get(new BufferKey(identity.shareGroupId(), boundBuffer));
            final boolean expectedKnown = scope != null && scope.operation != BufferOperation.RETIRED_DELETE
                && (identity == null || identity.shareGroupId() == scope.registration.shareGroupId);
            Registration registration = boundRegistration;
            if (registration == null && expectedKnown && boundBuffer == null) {
                registration = this.findRegistration(scope.registration.registrationToken);
            }
            if (registration == null) {
                return null;
            }

            final boolean scopeMismatch = expectedKnown && boundBuffer != null
                && (boundBuffer != scope.registration.bufferId
                    || registration.registrationToken != scope.registration.registrationToken);
            final long callSequence = this.nextEventSequence++;
            final long startedAtNanos = System.nanoTime();
            this.counters = this.counters.withBufferDataStarted();
            final NativeBufferDataEvent event = new NativeBufferDataEvent(
                callSequence, startedAtNanos, 0L,
                identity == null ? 0L : identity.generation(), identity == null ? 0L : identity.shareGroupId(),
                target, boundBuffer != null, boundBuffer == null ? 0 : boundBuffer,
                expectedKnown, expectedKnown ? scope.registration.bufferId : 0,
                scopeMismatch,
                registration.arenaId, registration.arenaType, registration.stride,
                registration.regionId, registration.regionX, registration.regionY, registration.regionZ,
                registration.registrationToken, registration.shareGroupId, registration.capacityBytes,
                registration.resizeSequence,
                requestedBytes, null, null);
            return new NativeBufferDataCall(event);
        }

        synchronized NativeBufferDataEvent finishNativeBufferData(NativeBufferDataCall call, Throwable failure) {
            final Outcome outcome = failure == null ? Outcome.NATIVE_CALL_RETURNED : Outcome.JAVA_FAILURE;
            this.counters = failure == null ? this.counters.withBufferDataReturned() : this.counters.withBufferDataFailed();
            final NativeBufferDataEvent original = call.event;
            return new NativeBufferDataEvent(original.callSequence(), original.startedAtNanos(), System.nanoTime(),
                original.contextGeneration(), original.shareGroupId(), original.target(),
                original.boundBufferKnown(), original.boundBufferId(),
                original.scopeExpectedBufferKnown(), original.scopeExpectedBufferId(),
                original.scopeBindingMismatch(),
                original.arenaId(), original.arenaType(), original.stride(), original.regionId(),
                original.regionX(), original.regionY(), original.regionZ(), original.registrationToken(),
                original.registeredShareGroupId(), original.registeredCapacityBytes(),
                original.resizeSequence(), original.requestedBytes(), outcome,
                failure == null ? null : failure.getClass().getName());
        }

        synchronized NativeDeleteCall beginNativeDelete(GLContextIdentity identity, int bufferId) {
            return this.beginNativeDelete(identity, bufferId, null);
        }

        private synchronized NativeDeleteCall beginNativeDelete(GLContextIdentity identity,
                                                                 int bufferId, ArenaBufferScopeData scope) {
            Registration registration = null;
            if (bufferId != 0) {
                registration = identity == null ? null
                    : this.registrations.get(new BufferKey(identity.shareGroupId(), bufferId));
                if (registration == null && scope != null && scope.operation == BufferOperation.RETIRED_DELETE
                    && scope.registration.bufferId == bufferId
                    && (identity == null || identity.shareGroupId() == scope.registration.shareGroupId)) {
                    registration = scope.registration;
                }
            }
            if (registration == null) {
                return null;
            }
            return this.createDeleteCall(identity, List.of(new DeleteEntry(bufferId, registration)));
        }

        synchronized NativeDeleteCall beginNativeDelete(GLContextIdentity identity,
                                                                 IntBuffer bufferIds, ArenaBufferScopeData scope) {
            Map<Integer, Registration> matched = null;
            for (int index = bufferIds.position(); index < bufferIds.limit(); index++) {
                final int bufferId = bufferIds.get(index);
                if (bufferId == 0 || matched != null && matched.containsKey(bufferId)) {
                    continue;
                }
                Registration registration = identity == null ? null
                    : this.registrations.get(new BufferKey(identity.shareGroupId(), bufferId));
                if (registration == null && scope != null && scope.operation == BufferOperation.RETIRED_DELETE
                    && scope.registration.bufferId == bufferId
                    && (identity == null || identity.shareGroupId() == scope.registration.shareGroupId)) {
                    registration = scope.registration;
                }
                if (registration != null) {
                    if (matched == null) {
                        matched = new LinkedHashMap<>();
                    }
                    matched.put(bufferId, registration);
                }
            }
            if (matched == null) {
                return null;
            }
            final List<DeleteEntry> entries = new ArrayList<>(matched.size());
            for (Map.Entry<Integer, Registration> entry : matched.entrySet()) {
                entries.add(new DeleteEntry(entry.getKey(), entry.getValue()));
            }
            return this.createDeleteCall(identity, entries);
        }

        private NativeDeleteCall createDeleteCall(GLContextIdentity identity, List<DeleteEntry> entries) {
            final long callSequence = this.nextEventSequence++;
            final long startedAtNanos = System.nanoTime();
            this.counters = this.counters.withDeleteStarted(entries.size());
            return new NativeDeleteCall(new DeleteCallSnapshot(
                callSequence, startedAtNanos, identity == null ? 0L : identity.generation(),
                identity == null ? 0L : identity.shareGroupId(), List.copyOf(entries)));
        }

        synchronized List<NativeDeleteEvent> finishNativeDelete(NativeDeleteCall call, Throwable failure) {
            final DeleteCallSnapshot snapshot = call.snapshot;
            final boolean returned = failure == null;
            if (returned) {
                this.counters = this.counters.withDeleteReturned(snapshot.entries.size());
            } else {
                this.counters = this.counters.withDeleteFailed(snapshot.entries.size());
            }

            final long completedAtNanos = System.nanoTime();
            final Outcome outcome = returned ? Outcome.NATIVE_CALL_RETURNED : Outcome.JAVA_FAILURE;
            final String failureClass = failure == null ? null : failure.getClass().getName();
            final List<NativeDeleteEvent> events = new ArrayList<>(snapshot.entries.size());
            for (DeleteEntry entry : snapshot.entries) {
                final Registration registration = entry.registration;
                if (returned) {
                    final BufferKey key = new BufferKey(registration.shareGroupId, entry.bufferId);
                    final Registration current = this.registrations.get(key);
                    if (current != null && current.registrationToken == registration.registrationToken) {
                        this.registrations.remove(key);
                        this.removePendingFor(registration.registrationToken);
                    }
                    this.updateBindingAfterDelete(snapshot.contextGeneration, entry.bufferId,
                        current == null || current.registrationToken == registration.registrationToken);
                }
                events.add(new NativeDeleteEvent(
                    snapshot.callSequence, snapshot.startedAtNanos, completedAtNanos,
                    snapshot.contextGeneration, snapshot.shareGroupId, registration.shareGroupId,
                    registration.arenaId, registration.arenaType, registration.stride,
                    registration.regionId, registration.regionX, registration.regionY, registration.regionZ,
                    entry.bufferId, registration.registrationToken, registration.capacityBytes,
                    registration.resizeSequence,
                    outcome, failureClass));
            }
            return events;
        }

        private void updateBindingAfterDelete(long contextGeneration, int bufferId, boolean generationMatched) {
            final Map<Integer, Integer> bindings = this.bindingsByContext.get(contextGeneration);
            if (bindings == null) {
                return;
            }
            final var iterator = bindings.entrySet().iterator();
            while (iterator.hasNext()) {
                final Map.Entry<Integer, Integer> binding = iterator.next();
                if (binding.getValue() == bufferId) {
                    if (generationMatched) {
                        binding.setValue(0);
                    } else {
                        iterator.remove();
                    }
                }
            }
        }

        synchronized TraceEndResult setEnabled(boolean enabled, GLContextIdentity currentIdentity) {
            if (this.enabled == enabled) {
                return null;
            }
            final List<TraceEndedEvent> events = new ArrayList<>();
            if (!enabled) {
                for (Registration registration : this.registrations.values()) {
                    events.add(traceEnded(TraceEndReason.DEBUG_DISABLED, registration,
                        registration.bufferId, registration.registrationToken, registration.resizeSequence,
                        registration.contextGeneration, registration.shareGroupId));
                }
                if (events.isEmpty()) {
                    events.add(traceEnded(TraceEndReason.DEBUG_DISABLED, null, 0, 0L, 0L,
                        currentIdentity == null ? 0L : currentIdentity.generation(),
                        currentIdentity == null ? 0L : currentIdentity.shareGroupId()));
                }
                this.registrations.clear();
                this.pendingReplacements.clear();
            }
            this.bindingsByContext.clear();
            final TraceCounters endedCounters = this.counters;
            this.counters = TraceCounters.ZERO;
            this.enabled = enabled;
            return new TraceEndResult(List.copyOf(events), endedCounters);
        }

        private synchronized List<TraceEndedEvent> onContextRetired(GLContextIdentity identity,
                                                                     boolean finalShareGroupContext) {
            this.bindingsByContext.remove(identity.generation());
            final List<TraceEndedEvent> events = new ArrayList<>();
            if (finalShareGroupContext) {
                final List<BufferKey> keys = new ArrayList<>();
                for (Map.Entry<BufferKey, Registration> entry : this.registrations.entrySet()) {
                    if (entry.getKey().shareGroupId == identity.shareGroupId()) {
                        keys.add(entry.getKey());
                    }
                }
                for (BufferKey key : keys) {
                    final Registration registration = this.registrations.remove(key);
                    if (registration != null) {
                        events.add(traceEnded(TraceEndReason.CONTEXT_RETIRED, registration,
                            registration.bufferId, registration.registrationToken, registration.resizeSequence,
                            identity.generation(), identity.shareGroupId()));
                        this.removePendingFor(registration.registrationToken);
                    }
                }
            } else {
                events.add(traceEnded(TraceEndReason.CONTEXT_RETIRED, null, 0, 0L, 0L,
                    identity.generation(), identity.shareGroupId()));
            }
            return events;
        }

        private synchronized TraceCounters counters() {
            return this.counters;
        }

        synchronized boolean hasRegistration(GLContextIdentity identity, int bufferId, long token) {
            if (identity == null) {
                return false;
            }
            final Registration registration = this.registrations.get(new BufferKey(identity.shareGroupId(), bufferId));
            return registration != null && registration.registrationToken == token;
        }

        private synchronized void removePendingFor(long registrationToken) {
            this.pendingReplacements.entrySet().removeIf(entry ->
                entry.getValue().oldRegistration.registrationToken == registrationToken
                    || entry.getValue().newRegistration.registrationToken == registrationToken);
        }

        private Registration findRegistration(long registrationToken) {
            if (registrationToken == 0L) {
                return null;
            }
            for (Registration registration : this.registrations.values()) {
                if (registration.registrationToken == registrationToken) {
                    return registration;
                }
            }
            return null;
        }

        private TraceEndedEvent traceEnded(TraceEndReason reason, Registration registration,
                                           int bufferId, long registrationToken, long resizeSequence,
                                           long contextGeneration, long shareGroupId) {
            return new TraceEndedEvent(this.nextEventSequence++, System.nanoTime(), reason,
                contextGeneration, shareGroupId,
                registration == null ? 0L : registration.arenaId,
                registration == null ? null : registration.arenaType,
                registration == null ? 0 : registration.stride,
                registration == null ? 0 : registration.regionId,
                registration == null ? 0 : registration.regionX,
                registration == null ? 0 : registration.regionY,
                registration == null ? 0 : registration.regionZ,
                bufferId, registrationToken, registration == null ? 0L : registration.capacityBytes,
                resizeSequence);
        }
    }

    private static void validateArenaBuffer(long arenaId, String arenaType, int stride,
                                            int bufferId, long capacityBytes) {
        if (arenaId <= 0L) {
            throw new IllegalArgumentException("Arena id must be positive: " + arenaId);
        }
        Objects.requireNonNull(arenaType, "arenaType");
        if (stride <= 0) {
            throw new IllegalArgumentException("Arena stride must be positive: " + stride);
        }
        if (bufferId <= 0) {
            throw new IllegalArgumentException("Arena buffer ID must be positive: " + bufferId);
        }
        if (capacityBytes < 0L) {
            throw new IllegalArgumentException("Arena capacity must not be negative: " + capacityBytes);
        }
    }

    private record BufferKey(long shareGroupId, int bufferId) {
    }

    private record Registration(long contextGeneration, long shareGroupId,
                               long arenaId, String arenaType, int stride,
                               int regionId, int regionX, int regionY, int regionZ,
                               int bufferId, long registrationToken, long resizeSequence,
                               long capacityBytes, long usedBytes, long requestBytes, int copySpanCount,
                               boolean baseline, BufferOperation operation) {
        private Registration withCapacity(long capacityBytes) {
            return new Registration(this.contextGeneration, this.shareGroupId, this.arenaId,
                this.arenaType, this.stride, this.regionId, this.regionX, this.regionY, this.regionZ,
                this.bufferId, this.registrationToken, this.resizeSequence, capacityBytes,
                this.usedBytes, this.requestBytes, this.copySpanCount, this.baseline, this.operation);
        }

        private Registration asActive(long resizeSequence, long capacityBytes) {
            return new Registration(this.contextGeneration, this.shareGroupId, this.arenaId,
                this.arenaType, this.stride, this.regionId, this.regionX, this.regionY, this.regionZ,
                this.bufferId, this.registrationToken, resizeSequence, capacityBytes,
                this.usedBytes, this.requestBytes, this.copySpanCount, this.baseline,
                BufferOperation.INITIAL_STORAGE);
        }
    }

    private record PendingReplacement(BufferKey oldKey, BufferKey newKey, Registration previousOld,
                                     Registration oldRegistration, Registration newRegistration) {
    }

    private record ArenaBufferScopeData(Thread ownerThread, Registration registration,
                                        long resizeSequence, BufferOperation operation) {
    }

    private record DeleteEntry(int bufferId, Registration registration) {
    }

    private record DeleteCallSnapshot(long callSequence, long startedAtNanos,
                                      long contextGeneration, long shareGroupId,
                                      List<DeleteEntry> entries) {
    }

    private record TraceCounters(long bufferDataCallsStarted, long bufferDataCallsReturned,
                                 long bufferDataJavaFailures, long deleteCallsStarted,
                                 long deleteCallsReturned, long deleteCallsJavaFailures,
                                 long trackedDeleteIdsStarted, long trackedDeleteIdsReturned,
                                 long trackedDeleteIdsJavaFailures) {
        private static final TraceCounters ZERO = new TraceCounters(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);

        private TraceCounters withBufferDataStarted() {
            return new TraceCounters(this.bufferDataCallsStarted + 1L, this.bufferDataCallsReturned,
                this.bufferDataJavaFailures, this.deleteCallsStarted, this.deleteCallsReturned,
                this.deleteCallsJavaFailures, this.trackedDeleteIdsStarted,
                this.trackedDeleteIdsReturned, this.trackedDeleteIdsJavaFailures);
        }

        private TraceCounters withBufferDataReturned() {
            return new TraceCounters(this.bufferDataCallsStarted, this.bufferDataCallsReturned + 1L,
                this.bufferDataJavaFailures, this.deleteCallsStarted, this.deleteCallsReturned,
                this.deleteCallsJavaFailures, this.trackedDeleteIdsStarted,
                this.trackedDeleteIdsReturned, this.trackedDeleteIdsJavaFailures);
        }

        private TraceCounters withBufferDataFailed() {
            return new TraceCounters(this.bufferDataCallsStarted, this.bufferDataCallsReturned,
                this.bufferDataJavaFailures + 1L, this.deleteCallsStarted, this.deleteCallsReturned,
                this.deleteCallsJavaFailures, this.trackedDeleteIdsStarted,
                this.trackedDeleteIdsReturned, this.trackedDeleteIdsJavaFailures);
        }

        private TraceCounters withDeleteStarted(int ids) {
            return new TraceCounters(this.bufferDataCallsStarted, this.bufferDataCallsReturned,
                this.bufferDataJavaFailures, this.deleteCallsStarted + 1L, this.deleteCallsReturned,
                this.deleteCallsJavaFailures, this.trackedDeleteIdsStarted + ids,
                this.trackedDeleteIdsReturned, this.trackedDeleteIdsJavaFailures);
        }

        private TraceCounters withDeleteReturned(int ids) {
            return new TraceCounters(this.bufferDataCallsStarted, this.bufferDataCallsReturned,
                this.bufferDataJavaFailures, this.deleteCallsStarted, this.deleteCallsReturned + 1L,
                this.deleteCallsJavaFailures, this.trackedDeleteIdsStarted,
                this.trackedDeleteIdsReturned + ids, this.trackedDeleteIdsJavaFailures);
        }

        private TraceCounters withDeleteFailed(int ids) {
            return new TraceCounters(this.bufferDataCallsStarted, this.bufferDataCallsReturned,
                this.bufferDataJavaFailures, this.deleteCallsStarted, this.deleteCallsReturned,
                this.deleteCallsJavaFailures + 1L, this.trackedDeleteIdsStarted,
                this.trackedDeleteIdsReturned, this.trackedDeleteIdsJavaFailures + ids);
        }
    }

    record TraceEndResult(List<TraceEndedEvent> events, TraceCounters counters) {
    }
}
