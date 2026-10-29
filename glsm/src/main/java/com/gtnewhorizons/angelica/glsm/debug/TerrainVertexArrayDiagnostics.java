package com.gtnewhorizons.angelica.glsm.debug;

import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
import com.gtnewhorizons.angelica.glsm.context.GLContextRegistryProvider;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Captures terrain-only VAO ownership and the two existing delete-wrapper boundaries. */
public final class TerrainVertexArrayDiagnostics {
    private static final Logger LOGGER = LogManager.getLogger("ActiniumMemory");
    private static final AtomicLong TRACE_SEQUENCE = new AtomicLong();
    private static final ThreadLocal<DeleteScope> ACTIVE_DELETE_SCOPE = new ThreadLocal<>();

    private TerrainVertexArrayDiagnostics() {
    }

    /** Returns the GUI gate while keeping a diagnostic failure out of the render call path. */
    public static boolean isTerrainVaoDiagnosticsEnabled() {
        try {
            return GLSMPerfDebug.isEnabled();
        } catch (RuntimeException | Error failure) {
            logDiagnosticFailure("debug-gate", null, failure);
            return false;
        }
    }

    /** Registers one terrain VAO from the render thread without querying GL state. */
    public static TraceToken registerTerrainVertexArray(int vertexArrayId, int vertexBufferId, int elementBufferId) {
        TraceToken token = null;
        try {
            if (!isTerrainVaoDiagnosticsEnabled()) {
                return null;
            }
            GLContextIdentity owner = GLContextRegistryProvider.get().currentBinding();
            token = createTraceToken(true, vertexArrayId, vertexBufferId, elementBufferId,
                    generation(owner), shareGroup(owner));
            logEvent("created", token, token.ownerContextGeneration(), token.ownerShareGroupId(),
                    "registered", "none", "none");
        } catch (RuntimeException | Error failure) {
            logDiagnosticFailure("register", token, failure);
            return null;
        }
        return token;
    }

    /** Opens a thread-local scope only around a terrain VAO service delete. */
    public static DeleteScope beginServiceDelete(TraceToken token) {
        DeleteScope scope = null;
        try {
            if (token == null || !isTerrainVaoDiagnosticsEnabled()) {
                return null;
            }
            GLContextIdentity caller = GLContextRegistryProvider.get().currentBinding();
            scope = openDeleteScope(token, true, generation(caller), shareGroup(caller));
            logEvent("service-call-entered", token, scope.callerContextGeneration,
                    scope.callerShareGroupId, "pending", "none", "LWJGLServiceProvider.LWJGL");
            return scope;
        } catch (RuntimeException | Error failure) {
            closeAfterDiagnosticFailure(scope, "service-scope", failure);
            return null;
        }
    }

    /** Records the service-wrapper outcome without affecting the GL call result. */
    public static void finishServiceDelete(DeleteScope scope, Throwable failure) {
        if (scope == null) {
            return;
        }

        try {
            String outcome = failure == null ? "returned" : "java-failure";
            String event = failure == null ? "service-call-returned" : "service-call-java-failure";
            logEvent(event, scope.token, scope.callerContextGeneration,
                    scope.callerShareGroupId, outcome, failureClass(failure), "LWJGLServiceProvider.LWJGL");
        } catch (RuntimeException | Error diagnosticFailure) {
            logDiagnosticFailure("service-outcome", scope.token, diagnosticFailure);
        }
    }

    /** Restores any enclosing scope after the service wrapper has returned or thrown. */
    public static void closeServiceDelete(DeleteScope scope) {
        if (scope == null) {
            return;
        }

        try {
            scope.close();
        } catch (RuntimeException | Error failure) {
            logDiagnosticFailure("service-scope-close", scope.token, failure);
        }
    }

    /** Captures the current matching terrain scope at the sole LWJGL3 GL30C delete call site. */
    public static NativeDeleteCall beginNativeDelete(int vertexArrayId) {
        try {
            if (!isTerrainVaoDiagnosticsEnabled()) {
                return null;
            }
            DeleteScope scope = ACTIVE_DELETE_SCOPE.get();
            if (scope == null || !scope.matches(vertexArrayId)) {
                return null;
            }
            GLContextIdentity caller = GLContextRegistryProvider.get().currentBinding();
            NativeDeleteCall call = enterNativeDelete(true, vertexArrayId,
                    generation(caller), shareGroup(caller));
            if (call != null) {
                logEvent("native-call-entered", call.token(), call.callerContextGeneration(),
                        call.callerShareGroupId(), "pending", "none", "Lwjgl3GLRenderBackend.GL30C");
            }
            return call;
        } catch (RuntimeException | Error failure) {
            logDiagnosticFailure("native-enter", null, failure);
            return null;
        }
    }

    /** Completes one native observation; duplicate completion callbacks are ignored. */
    public static void finishNativeDelete(NativeDeleteCall call, Throwable failure) {
        if (call == null || !call.finish()) {
            return;
        }

        try {
            String outcome = failure == null ? "returned" : "java-failure";
            String event = failure == null ? "native-call-returned" : "native-call-java-failure";
            logEvent(event, call.token(), call.callerContextGeneration(),
                    call.callerShareGroupId(), outcome, failureClass(failure), "Lwjgl3GLRenderBackend.GL30C");
        } catch (RuntimeException | Error diagnosticFailure) {
            logDiagnosticFailure("native-outcome", call.token(), diagnosticFailure);
        }
    }

    static TraceToken createTraceToken(boolean enabled, int vertexArrayId, int vertexBufferId,
                                       int elementBufferId, long ownerContextGeneration,
                                       long ownerShareGroupId) {
        if (!enabled) {
            return null;
        }
        return new TraceToken(TRACE_SEQUENCE.incrementAndGet(), vertexArrayId, ownerContextGeneration,
                ownerShareGroupId, vertexBufferId, elementBufferId);
    }

    static DeleteScope openDeleteScope(TraceToken token, boolean enabled, long callerContextGeneration,
                                       long callerShareGroupId) {
        if (!enabled || token == null) {
            return null;
        }

        DeleteScope scope = new DeleteScope(token, callerContextGeneration, callerShareGroupId,
                ACTIVE_DELETE_SCOPE.get());
        ACTIVE_DELETE_SCOPE.set(scope);
        return scope;
    }

    static NativeDeleteCall enterNativeDelete(boolean enabled, int vertexArrayId,
                                               long callerContextGeneration, long callerShareGroupId) {
        if (!enabled) {
            return null;
        }

        DeleteScope scope = ACTIVE_DELETE_SCOPE.get();
        if (scope == null || !scope.matches(vertexArrayId) || scope.nativeEntered) {
            return null;
        }

        scope.nativeEntered = true;
        return new NativeDeleteCall(scope.token, callerContextGeneration, callerShareGroupId);
    }

    private static long generation(GLContextIdentity identity) {
        return identity == null ? 0L : identity.generation();
    }

    private static long shareGroup(GLContextIdentity identity) {
        return identity == null ? 0L : identity.shareGroupId();
    }

    private static String failureClass(Throwable failure) {
        return failure == null ? "none" : failure.getClass().getName();
    }

    private static void logEvent(String event, TraceToken token, long callerContextGeneration,
                                 long callerShareGroupId, String outcome, String failureClass, String wrapper) {
        long eventTimeNanos = System.nanoTime();
        LOGGER.info("[ActiniumMemory][terrain-vao] event={} eventTimeNanos={} traceId={} vaoId={} ownerGeneration={} "
                        + "ownerShareGroup={} callerGeneration={} callerShareGroup={} ownerGenerationMatch={} "
                        + "ownerShareGroupMatch={} vertexBufferId={} elementBufferId={} outcome={} "
                        + "failureClass={} wrapper={}",
                event, eventTimeNanos, token.traceId(), token.vertexArrayId(), token.ownerContextGeneration(),
                token.ownerShareGroupId(), callerContextGeneration, callerShareGroupId,
                match(token.ownerContextGeneration(), callerContextGeneration),
                match(token.ownerShareGroupId(), callerShareGroupId), token.vertexBufferId(),
                token.elementBufferId(), outcome, failureClass, wrapper);
    }

    private static String match(long owner, long caller) {
        if (owner <= 0L || caller <= 0L) {
            return "unknown";
        }
        return Boolean.toString(owner == caller);
    }

    private static void closeAfterDiagnosticFailure(DeleteScope scope, String operation, Throwable failure) {
        if (scope != null) {
            try {
                scope.close();
            } catch (RuntimeException | Error closeFailure) {
                logDiagnosticFailure("scope-close-after-" + operation, scope.token, closeFailure);
            }
            logDiagnosticFailure(operation, scope.token, failure);
        } else {
            logDiagnosticFailure(operation, null, failure);
        }
    }

    private static void logDiagnosticFailure(String operation, TraceToken token, Throwable failure) {
        try {
            LOGGER.error("[ActiniumMemory][terrain-vao] diagnosticFailure operation={} traceId={} vaoId={} "
                            + "failureClass={}",
                    operation, token == null ? 0L : token.traceId(), token == null ? 0 : token.vertexArrayId(),
                    failure.getClass().getName(), failure);
        } catch (RuntimeException | Error loggingFailure) {
            System.err.println("[ActiniumMemory][terrain-vao] diagnosticFailure operation=" + operation
                    + " failureClass=" + failure.getClass().getName()
                    + " loggingFailure=" + loggingFailure.getClass().getName());
        }
    }

    public record TraceToken(long traceId, int vertexArrayId, long ownerContextGeneration,
                             long ownerShareGroupId, int vertexBufferId, int elementBufferId) {
    }

    public static final class DeleteScope {
        private final TraceToken token;
        private final long callerContextGeneration;
        private final long callerShareGroupId;
        private final DeleteScope previous;
        private boolean active = true;
        private boolean nativeEntered;

        private DeleteScope(TraceToken token, long callerContextGeneration, long callerShareGroupId,
                            DeleteScope previous) {
            this.token = token;
            this.callerContextGeneration = callerContextGeneration;
            this.callerShareGroupId = callerShareGroupId;
            this.previous = previous;
        }

        private boolean matches(int vertexArrayId) {
            return this.active && this.token.vertexArrayId() == vertexArrayId;
        }

        private void close() {
            if (!this.active) {
                return;
            }
            this.active = false;
            if (ACTIVE_DELETE_SCOPE.get() == this) {
                DeleteScope restore = this.previous;
                while (restore != null && !restore.active) {
                    restore = restore.previous;
                }
                if (restore == null) {
                    ACTIVE_DELETE_SCOPE.remove();
                } else {
                    ACTIVE_DELETE_SCOPE.set(restore);
                }
            }
        }
    }

    public static final class NativeDeleteCall {
        private final TraceToken token;
        private final long callerContextGeneration;
        private final long callerShareGroupId;
        private boolean completed;

        private NativeDeleteCall(TraceToken token, long callerContextGeneration, long callerShareGroupId) {
            this.token = token;
            this.callerContextGeneration = callerContextGeneration;
            this.callerShareGroupId = callerShareGroupId;
        }

        private boolean finish() {
            if (this.completed) {
                return false;
            }
            this.completed = true;
            return true;
        }

        private TraceToken token() {
            return this.token;
        }

        String ownerGenerationMatch() {
            return match(this.token.ownerContextGeneration(), this.callerContextGeneration);
        }

        String ownerShareGroupMatch() {
            return match(this.token.ownerShareGroupId(), this.callerShareGroupId);
        }

        boolean ownerContextMatched() {
            return this.token.ownerContextGeneration() > 0L && this.callerContextGeneration > 0L
                    && this.token.ownerContextGeneration() == this.callerContextGeneration;
        }

        long traceId() {
            return this.token.traceId();
        }

        private long callerContextGeneration() {
            return this.callerContextGeneration;
        }

        private long callerShareGroupId() {
            return this.callerShareGroupId;
        }
    }
}
