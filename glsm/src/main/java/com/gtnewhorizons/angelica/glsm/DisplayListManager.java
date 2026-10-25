package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;
import com.gtnewhorizon.gtnhlib.client.renderer.DirectTessellator;
import com.gtnewhorizon.gtnhlib.client.renderer.TessellatorManager;
import com.gtnewhorizon.gtnhlib.client.renderer.vbo.VBOManager;
import com.gtnewhorizon.gtnhlib.client.renderer.vbo.VertexBuffer;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.DefaultVertexFormat;
import com.gtnewhorizons.angelica.glsm.recording.AccumulatedDraw;
import com.gtnewhorizons.angelica.glsm.recording.ImmediateModeRecorder;
import com.gtnewhorizons.angelica.glsm.recording.CommandBuffer;
import com.gtnewhorizons.angelica.glsm.recording.CommandRecorder;
import com.gtnewhorizons.angelica.glsm.recording.CompiledDisplayList;
import com.gtnewhorizons.angelica.glsm.recording.DisplayListVBO;
import com.gtnewhorizons.angelica.glsm.recording.DisplayListVBOBuilder;
import com.gtnewhorizons.angelica.glsm.recording.GLCommand;
import com.gtnewhorizons.angelica.glsm.recording.commands.DisplayListCommand;
import com.gtnewhorizons.angelica.glsm.recording.commands.IndexedDrawBatch;
import com.gtnewhorizons.angelica.glsm.recording.commands.IndexedDrawBatchBuilder;
import com.gtnewhorizons.angelica.glsm.recording.commands.IndexedDrawCapture;
import com.gtnewhorizons.angelica.glsm.recording.commands.StateSetScopeCmd;
import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
import com.gtnewhorizons.angelica.glsm.context.GLContextRegistryProvider;
import lombok.experimental.UtilityClass;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAddress;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memGetInt;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memFree;

/**
 * VBO-based display list emulation with command recording, transform collapsing, and format-based batching.
 *
 * <p><b>Transform Collapsing:</b> Consecutive transforms collapse into single MultMatrix commands,
 * emitted at barriers (draws, CallList, exit). Vertices stay canonical in VBOs. Handles nested
 * display lists correctly and maintains Push/Pop semantics.
 *
 * <p><b>Format-Based Batching:</b> Draws with same vertex format share a VBO via {@link DisplayListVBO}.
 * Consecutive same-transform draws merge.
 * Delta transforms handled via {@link TransformOptimizer}.
 */
@UtilityClass
public class DisplayListManager {
    // -Dangelica.debugDisplayLists: disable transform collapsing and draw merging
    private static final boolean DEBUG_DISPLAY_LISTS = Boolean.getBoolean("angelica.debugDisplayLists");

    // -Dangelica.logDisplayListCompilation: log compiled display list commands
    private static final boolean LOG_DISPLAY_LIST_COMPILATION;

    static {
        LOG_DISPLAY_LIST_COMPILATION = Boolean.getBoolean("angelica.logDisplayListCompilation");
        if (LOG_DISPLAY_LIST_COMPILATION) {
            GLStateManager.LOGGER.warn("Display list compilation logging ENABLED (-Dangelica.logDisplayListCompilation=true)");
        }
    }

    private static final Matrix4f IDENTITY = new Matrix4f();

    public static boolean isIdentity(Matrix4f m) {
        return (m.properties() & Matrix4f.PROPERTY_IDENTITY) != 0 || m.equals(IDENTITY, 1e-6f);
    }

    /** Transform operation types for relative transform tracking. */
    public enum TransformOp { TRANSLATE, SCALE, ROTATE }

    /** Recording mode for display list compilation. */
    public enum RecordMode {
        NONE,               // Not recording
        COMPILE,            // GL_COMPILE: record only, don't execute
        COMPILE_AND_EXECUTE // GL_COMPILE_AND_EXECUTE: record and execute
    }

    // Compilation data lives on a native context session; a thread-local idle session covers calls outside a bound context.
    private static final Map<GLContextIdentity, DisplayListCompilationSession> compilationSessions = new ConcurrentHashMap<>();
    private static final ThreadLocal<DisplayListCompilationSession> idleSession =
        ThreadLocal.withInitial(() -> new DisplayListCompilationSession(null));
    private static final Map<CommandRecorder, DisplayListCompilationSession> recorderOwners =
        Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Set<CommandRecorder> canceledRecorders = ConcurrentHashMap.newKeySet();

    private static final DisplayListNamespaces displayListNamespaces = new DisplayListNamespaces();
    @SuppressWarnings("unused")
    private static final AutoCloseable displayListRetirementSubscription =
        GLContextRegistryProvider.addRetirementListener(DisplayListManager::onContextRetired);

    private static DisplayListCompilationSession session() {
        final GLContextIdentity currentIdentity = GLContextRegistryProvider.get().currentBinding();
        if (currentIdentity == null) {
            return idleSession.get();
        }
        return compilationSessions.computeIfAbsent(currentIdentity, DisplayListCompilationSession::new);
    }

    private static DisplayListCompilationSession sessionFor(GLContextIdentity identity) {
        return compilationSessions.computeIfAbsent(identity, DisplayListCompilationSession::new);
    }

    /** Saves a context's active compilation capture when the native binding leaves this thread. */
    public static synchronized void onContextUnbound(GLContextIdentity identity) {
        if (identity == null) {
            return;
        }
        final DisplayListCompilationSession owner = compilationSessions.get(identity);
        if (owner == null || !owner.isActive()) {
            return;
        }
        if (owner.recordingThread != null && owner.recordingThread != Thread.currentThread()) {
            throw new IllegalStateException("Cannot suspend display-list compilation owned by another thread");
        }
        if (owner.recordingThread == Thread.currentThread()) {
            for (DirectTessellator capture : owner.capturesOutermostFirst()) {
                TessellatorManager.suspend(capture);
            }
            owner.recordingThread = null;
        }
    }

    /** Restores a parked compilation capture after its native context becomes current on this thread. */
    public static synchronized void onContextBound(GLContextIdentity identity) {
        if (identity == null) {
            return;
        }
        final DisplayListCompilationSession owner = compilationSessions.get(identity);
        if (owner == null || !owner.isActive()) {
            return;
        }
        if (owner.recordingThread != null && owner.recordingThread != Thread.currentThread()) {
            throw new IllegalStateException("Display-list compilation context is already bound to another thread");
        }
        for (DirectTessellator capture : owner.capturesOutermostFirst()) {
            TessellatorManager.resume(capture);
        }
        owner.recordingThread = Thread.currentThread();
    }

    /** Returns the list currently replaying on this thread, or {@code -1} outside list replay. */
    public static int getCurrentRenderingList() {
        return session().currentRenderingList;
    }

    public static RecordMode getRecordMode() {
        if (!isRecording()) {
            return RecordMode.NONE;
        }
        return session().listMode == GL11.GL_COMPILE_AND_EXECUTE ? RecordMode.COMPILE_AND_EXECUTE : RecordMode.COMPILE;
    }

    /** True only for the context and thread that started recording. */
    public static boolean isRecording() {
        final DisplayListCompilationSession current = session();
        return current.recorder != null && Thread.currentThread() == current.recordingThread;
    }

    public static boolean isCompileAndExecute() {
        return isRecording() && session().listMode == GL11.GL_COMPILE_AND_EXECUTE;
    }

    public static synchronized CommandRecorder pauseRecording() {
        final DisplayListCompilationSession current = session();
        final CommandRecorder r = current.recorder;
        if (r != null) {
            current.recorder = null;
            current.pausedRecorders.add(r);
        }
        return r;
    }

    public static synchronized void resumeRecording(CommandRecorder r) {
        if (r == null || canceledRecorders.remove(r)) {
            return;
        }
        final DisplayListCompilationSession owner;
        synchronized (recorderOwners) {
            owner = recorderOwners.get(r);
        }
        if (owner == null) {
            throw new IllegalStateException("Cannot resume an unknown display-list recorder");
        }
        if (!owner.identity.equals(GLContextRegistryProvider.get().currentBinding())
            || owner.recordingThread != Thread.currentThread()) {
            throw new IllegalStateException("Cannot resume a display-list recorder outside its bound context session");
        }
        if (!owner.pausedRecorders.remove(r) || owner.recorder != null) {
            throw new IllegalStateException("Display-list recorder is not paused or another recorder is active");
        }
        owner.recorder = r;
    }

    /** Releases the calling context's complete nested compilation session. */
    public static synchronized void abortCompilation() {
        final DisplayListCompilationSession current = session();
        if (current.identity == null || !compilationSessions.containsKey(current.identity)) {
            current.virtualStateScopes.clear();
            return;
        }
        abortSession(current);
    }

    /** Aborts one context's compilation before native destruction, preserving every other context session. */
    public static synchronized boolean abortCompilationForContext(GLContextIdentity retiringIdentity) {
        if (retiringIdentity == null) {
            return false;
        }
        final DisplayListCompilationSession owner = compilationSessions.get(retiringIdentity);
        if (owner == null || !owner.isActive()) {
            return false;
        }
        if (owner.recordingThread != null && Thread.currentThread() != owner.recordingThread) {
            throw new IllegalStateException(
                "Cannot destroy OpenGL context generation " + retiringIdentity.generation()
                    + " while its display-list compilation is owned by thread "
                    + owner.recordingThread.getName());
        }
        abortSession(owner);
        compilationSessions.remove(retiringIdentity, owner);
        return true;
    }

    private static void abortSession(DisplayListCompilationSession owner) {
        if (owner.recordingThread != null && owner.recordingThread != Thread.currentThread()) {
            throw new IllegalStateException("Display-list compilation can only be aborted by its owning context thread");
        }

        Throwable failure = null;
        for (DirectTessellator capture : owner.capturesOutermostFirst()) {
            try {
                TessellatorManager.cancel(capture);
            } catch (RuntimeException | Error cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else if (failure != cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
        final Set<CommandRecorder> recorders = Collections.newSetFromMap(new IdentityHashMap<>());
        if (owner.recorder != null) {
            recorders.add(owner.recorder);
        }
        recorders.addAll(owner.pausedRecorders);
        for (DisplayListCompilationSession.Frame parent : owner.parents) {
            if (parent.recorder() != null) {
                recorders.add(parent.recorder());
            }
            freeAccumulatedDraws(parent.draws());
        }
        freeAccumulatedDraws(owner.accumulatedDraws);
        for (CommandRecorder recorder : recorders) {
            try {
                final boolean paused = owner.pausedRecorders.contains(recorder);
                synchronized (recorderOwners) {
                    recorderOwners.remove(recorder);
                }
                if (paused) {
                    canceledRecorders.add(recorder);
                }
                abortRecorder(recorder);
            } catch (RuntimeException | Error cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else if (failure != cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
        owner.clear();
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static void abortRecorder(CommandRecorder recorder) {
        for (Object object : recorder.getBuffer().getComplexObjects()) {
            if (object instanceof DisplayListCommand command) {
                command.delete();
            }
        }
        recorder.free();
    }

    private static void freeAccumulatedDraws(List<AccumulatedDraw> draws) {
        if (draws == null) {
            return;
        }
        for (AccumulatedDraw draw : draws) {
            for (ByteBuffer buffer : draw.drawBuffers) {
                memFree(buffer);
            }
        }
    }

    /** Records one internal COW StateSet scope and advances its virtual compile-time depth. */
    public static void recordPushState(StateSet stateSet) {
        if (session().recorder == null || Thread.currentThread() != session().recordingThread) {
            return;
        }
        if (session().virtualStateScopes.depth() >= GLStateManager.MAX_ATTRIB_STACK_DEPTH) {
            throw new IllegalStateException("Display-list StateSet stack overflow at depth " + session().virtualStateScopes.depth());
        }
        if (session().identity == null || !session().identity.equals(GLStateManager.currentContextIdentity())) {
            throw new IllegalStateException("A recorded StateSet scope must stay on its compilation context");
        }
        session().recorder.recordComplexCommand(StateSetScopeCmd.push(stateSet));
        session().virtualStateScopes.push();
    }

    /** Records a pop for a pending internal COW scope; returns false when no virtual scope is open. */
    public static boolean recordPopStateIfPending() {
        if (session().recorder == null || Thread.currentThread() != session().recordingThread || session().virtualStateScopes.depth() == 0) {
            return false;
        }
        session().recorder.recordComplexCommand(StateSetScopeCmd.pop());
        session().virtualStateScopes.popIfPending();
        return true;
    }

    /**
     * Emit accumulated transform as MultMatrix if non-identity, then reset.
     */
    public static void flushMatrix() {
        flushMatrix(session());
    }

    private static void flushMatrix(DisplayListCompilationSession owner) {
        if (owner.relativeTransform == null || isIdentity(owner.relativeTransform)) {
            // Clear pending ops even if we don't emit - they were no-ops (identity)
            if (owner.pendingTransformOps != null) {
                owner.pendingTransformOps.clear();
            }
            return;
        }

        // Save pending transform ops for logging (before we clear them)
        if (owner.matrixSources != null && owner.pendingTransformOps != null) {
            if (owner.pendingTransformOps.isEmpty()) {
                GLStateManager.LOGGER.warn("flushMatrix: non-identity transform with no tracked ops");
                owner.matrixSources.add(Collections.singletonList("(unknown source)"));
            } else {
                owner.matrixSources.add(new ArrayList<>(owner.pendingTransformOps));
            }
            owner.pendingTransformOps.clear();
        }

        if (owner.recorder != null) {
            owner.recorder.recordMultMatrix(owner.relativeTransform);
            owner.stateGeneration++;
        }

        // Reset to identity - we're now synchronized with GL
        owner.relativeTransform.identity();

    }

    public static void matrixBarrier() {
        matrixBarrier(session());
    }

    private static void matrixBarrier(DisplayListCompilationSession owner) {
        try {
            flushMatrix(owner);
        } catch (RuntimeException | Error exception) {
            if (owner.identity != null && owner.identity.equals(GLContextRegistryProvider.get().currentBinding())) {
                abortCompilation();
            } else {
                abortCompilationForContext(owner.identity);
            }
            throw exception;
        }
    }

    public static int getRecordingListId() {
        return session().listId;
    }

    public static int getListMode() {
        return isRecording() ? session().listMode : 0;
    }

    public static int getCommandCount() {
        return session().recorder != null ? session().recorder.getCommandCount() : 0;
    }

    public static void trackDrawRangeSource(String source) {
        if (session().drawSources != null) {
            session().drawSources.add(source);
        }
    }

    // Draw barriers: state commands that prevent draw merging
    static void drawBarrier() {
        session().stateGeneration++;
    }

    public static void recordEnable(int cap) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordEnable(cap);
    }

    public static void recordDisable(int cap) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordDisable(cap);
    }

    // Clear commands don't affect subsequent draws, just clear buffers.

    public static void recordClear(int mask) {
        if (session().recorder == null) return;
        session().recorder.recordClear(mask);
    }

    public static void recordClearColor(float r, float g, float b, float a) {
        if (session().recorder == null) return;
        session().recorder.recordClearColor(r, g, b, a);
    }

    public static void recordClearDepth(double depth) {
        if (session().recorder == null) return;
        session().recorder.recordClearDepth(depth);
    }

    public static void recordClearStencil(int s) {
        if (session().recorder == null) return;
        session().recorder.recordClearStencil(s);
    }

    public static void recordBlendColor(float r, float g, float b, float a) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordBlendColor(r, g, b, a);
    }

    public static void recordColor(float r, float g, float b, float a) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordColor(r, g, b, a);
        if (ImmediateModeRecorder.isDrawing()) {
            ImmediateModeRecorder.setColor(r, g, b, a);
        }
    }

    public static void recordSecondaryColor(float r, float g, float b) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordSecondaryColor(r, g, b);
    }

    public static void recordColorMask(boolean r, boolean g, boolean b, boolean a) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordColorMask(r, g, b, a);
    }

    public static void recordDepthMask(boolean flag) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordDepthMask(flag);
    }

    public static void recordFrontFace(int mode) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordFrontFace(mode);
    }

    public static void recordDepthFunc(int func) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordDepthFunc(func);
    }

    public static void recordBlendFunc(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordBlendFunc(srcRgb, dstRgb, srcAlpha, dstAlpha);
    }

    public static void recordAlphaFunc(int func, float ref) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordAlphaFunc(func, ref);
    }

    public static void recordCullFace(int mode) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordCullFace(mode);
    }

    public static void recordShadeModel(int mode) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordShadeModel(mode);
    }

    public static void recordBindTexture(int target, int texture) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordBindTexture(target, texture);
    }

    public static void recordTexParameteri(int target, int pname, int param) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordTexParameteri(target, pname, param);
    }

    public static void recordTexParameterf(int target, int pname, float param) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordTexParameterf(target, pname, param);
    }

    public static void recordMatrixMode(int mode) {
        if (session().recorder == null) return;
        matrixBarrier();  // Matrix barrier: flush and reset
        session().recorder.recordMatrixMode(mode);
    }

    public static void recordPushMatrix() {
        if (session().recorder == null) return;
        flushMatrix();
        session().recorder.recordPushMatrix();
    }

    public static void recordPopMatrix() {
        if (session().recorder == null) return;
        flushMatrix();
        session().recorder.recordPopMatrix();
    }

    public static void recordViewport(int x, int y, int width, int height) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordViewport(x, y, width, height);
    }

    public static void recordScissor(int x, int y, int width, int height) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordScissor(x, y, width, height);
    }

    public static void recordPointSize(float size) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordPointSize(size);
    }

    public static void recordLineWidth(float width) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordLineWidth(width);
    }

    public static void recordLineStipple(int factor, int pattern) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordLineStipple(factor, pattern);
    }

    public static void recordPolygonOffset(float factor, float units) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordPolygonOffset(factor, units);
    }

    public static void recordPolygonMode(int face, int mode) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordPolygonMode(face, mode);
    }

    public static void recordColorMaterial(int face, int mode) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordColorMaterial(face, mode);
    }

    public static void recordLogicOp(int opcode) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordLogicOp(opcode);
    }

    public static void recordActiveTexture(int texture) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordActiveTexture(texture);
    }

    public static void recordUseProgram(int program) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordUseProgram(program);
    }

    // PushAttrib saves state but doesn't change it - not a draw barrier
    public static void recordPushAttrib(int mask) {
        if (session().recorder == null) return;
        session().recorder.recordPushAttrib(mask);
    }

    // PopAttrib restores potentially any state - draw barrier
    public static void recordPopAttrib() {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordPopAttrib();
    }

    public static void recordFogf(int pname, float param) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordFogf(pname, param);
    }

    public static void recordFogi(int pname, int param) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordFogi(pname, param);
    }

    public static void recordHint(int target, int mode) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordHint(target, mode);
    }

    public static void recordFog(int pname, java.nio.FloatBuffer params) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordFog(pname, params);
    }

    public static void recordLightf(int light, int pname, float param) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordLightf(light, pname, param);
    }

    public static void recordLighti(int light, int pname, int param) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordLighti(light, pname, param);
    }

    public static void recordLight(int light, int pname, java.nio.FloatBuffer params) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordLight(light, pname, params);
    }

    public static void recordLightModelf(int pname, float param) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordLightModelf(pname, param);
    }

    public static void recordLightModeli(int pname, int param) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordLightModeli(pname, param);
    }

    public static void recordLightModel(int pname, java.nio.FloatBuffer params) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordLightModel(pname, params);
    }

    public static void recordMaterialf(int face, int pname, float val) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordMaterialf(face, pname, val);
    }

    public static void recordMaterial(int face, int pname, java.nio.FloatBuffer params) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordMaterial(face, pname, params);
    }

    public static void recordClipPlane(int plane, double a, double b, double c, double d) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordClipPlane(plane, a, b, c, d);
    }

    public static void recordStencilFunc(int func, int ref, int mask) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordStencilFunc(func, ref, mask);
    }

    public static void recordStencilFuncSeparate(int face, int func, int ref, int mask) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordStencilFuncSeparate(face, func, ref, mask);
    }

    public static void recordStencilOp(int fail, int zfail, int zpass) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordStencilOp(fail, zfail, zpass);
    }

    public static void recordStencilOpSeparate(int face, int sfail, int dpfail, int dppass) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordStencilOpSeparate(face, sfail, dpfail, dppass);
    }

    public static void recordStencilMask(int mask) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordStencilMask(mask);
    }

    public static void recordStencilMaskSeparate(int face, int mask) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordStencilMaskSeparate(face, mask);
    }

    public static void recordCallList(int listId) {
        if (!isRecording()) return;
        matrixBarrier();  // Matrix barrier: nested list has own transforms
        session().recorder.recordCallList(listId);
    }

    public static void recordDrawBuffer(int mode) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordDrawBuffer(mode);
    }

    public static void recordDrawBuffers(int count, int buf) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordDrawBuffers(count, buf);
    }

    public static void recordDrawBuffers(int count, java.nio.IntBuffer bufs) {
        if (session().recorder == null) return;
        drawBarrier();
        session().recorder.recordDrawBuffers(count, bufs);
    }

    // Complex commands are texture uploads - not draw barriers
    public static void recordComplexCommand(DisplayListCommand cmd) {
        if (session().recorder == null) return;
        session().recorder.recordComplexCommand(cmd);
    }

    public static void recordLoadMatrix(Matrix4f matrix) {
        if (session().recorder != null) session().recorder.recordLoadMatrix(matrix);
    }

    public static void recordLoadIdentity() {
        if (session().recorder != null) session().recorder.recordLoadIdentity();
    }

    public static void recordDrawArrays(int mode, int start, int count) {
        if (session().recorder != null) session().recorder.recordDrawArrays(mode, start, count);
    }

    /**
     * Record a baked indexed draw. The capture already owns the vertex/index data read
     * back at record time; only its placeholder enters the command stream, so replay
     * never touches the source VBO/EBO/VAO again. Acts as a draw barrier and flushes
     * any pending transform so the draw replays at the right matrix state.
     */
    public static void recordIndexedDrawCapture(IndexedDrawCapture capture) {
        if (session().recorder == null) return;
        drawBarrier();
        matrixBarrier();
        session().recorder.recordIndexedDrawCapture(capture);
    }

    public static void recordBindVBO(int vbo) { if (session().recorder != null) session().recorder.recordBindVBO(vbo); }
    public static void recordBindVAO(int vao) {
        if (session().recorder == null) {
            return;
        }
        if (vao != 0) {
            final GLContextIdentity currentIdentity = GLStateManager.currentContextIdentity();
            if (session().identity == null || !session().identity.equals(currentIdentity)) {
                throw new IllegalStateException("A raw display-list VAO bind must belong to the compilation context");
            }
        }
        session().recorder.recordBindVAO(vao);
    }

    // recordOrtho/recordFrustum removed - these now accumulate into session().relativeTransform

    /**
     * Add an immediate mode draw to the current display list compilation.
     * Called by GLStateManager.glEnd() when immediate mode geometry is ready.
     *
     * <p>This method captures the current transform and command position, creating
     * an AccumulatedDraw that will be interleaved correctly with other commands
     * during display list playback.
     *
     */
    public static void addImmediateModeDraw(DirectTessellator tessellator) {
        final DisplayListCompilationSession owner = session();
        if (!tessellator.isEmpty() && owner.recorder != null) {
            addAccumulatedDraw(owner, tessellator, tessellator.getVertexFormat() != DefaultVertexFormat.POSITION);
        }
        tessellator.reset();
    }

    private static void applyTransformOp(Matrix4f m, float x, float y, float z, TransformOp op, Vector3f axis) {
        switch (op) {
            case TRANSLATE -> m.translate(x, y, z);
            case SCALE -> m.scale(x, y, z);
            case ROTATE -> { if (axis != null) m.rotate((float) Math.toRadians(x), axis); }
        }
    }

    public static void updateRelativeTransform(float x, float y, float z, TransformOp op, Vector3f rotationAxis) {
        if (session().relativeTransform == null) return;

        if (DEBUG_DISPLAY_LISTS) {
            final Matrix4f singleTransform = new Matrix4f();
            applyTransformOp(singleTransform, x, y, z, op, rotationAxis);
            if (session().recorder != null) session().recorder.recordMultMatrix(singleTransform);
            return;
        }

        applyTransformOp(session().relativeTransform, x, y, z, op, rotationAxis);

        if (session().pendingTransformOps != null) {
            final String log = switch (op) {
                case TRANSLATE -> String.format("glTranslatef(%.4f, %.4f, %.4f)", x, y, z);
                case SCALE -> String.format("glScalef(%.4f, %.4f, %.4f)", x, y, z);
                case ROTATE -> rotationAxis != null ? String.format("glRotatef(%.4f, %.4f, %.4f, %.4f)", x, rotationAxis.x, rotationAxis.y, rotationAxis.z) : null;
            };
            if (log != null) session().pendingTransformOps.add(log);
        }
    }

    /**
     * Update the relative transform with a matrix multiplication during display list compilation.
     * Called by GLStateManager.glMultMatrix().
     *
     * <p>In normal mode: accumulates transforms, emits collapsed MultMatrix at barriers.
     * <p>In debug mode: emits MultMatrix immediately (no collapsing).
     *
     * @param matrix The matrix to multiply
     */
    public static void updateRelativeTransform(Matrix4f matrix) {
        if (session().relativeTransform == null) {
            return;
        }

        if (DEBUG_DISPLAY_LISTS) {
            if (session().recorder != null) {
                session().recorder.recordMultMatrix(matrix);
            }
            return;
        }

        session().relativeTransform.mul(matrix);

        if (session().pendingTransformOps != null) {
            session().pendingTransformOps.add("glMultMatrixf(...)");
        }
    }

    // Scratch is thread-local because independent native contexts may compile on different threads.
    private static final ThreadLocal<Matrix4f> orthoFrustumTemp = ThreadLocal.withInitial(Matrix4f::new);

    /** Accumulate ortho projection into session().relativeTransform. */
    public static void updateRelativeTransformOrtho(double left, double right, double bottom, double top, double zNear, double zFar) {
        if (session().relativeTransform == null) return;

        final Matrix4f transform = orthoFrustumTemp.get()
            .identity().ortho((float) left, (float) right, (float) bottom, (float) top, (float) zNear, (float) zFar);

        if (DEBUG_DISPLAY_LISTS) {
            if (session().recorder != null) session().recorder.recordMultMatrix(transform);
            if (session().listMode == GL11.GL_COMPILE_AND_EXECUTE) {
                GLStateManager.getMatrixStack().mul(transform);
            }
            return;
        }

        session().relativeTransform.mul(transform);
        if (session().pendingTransformOps != null) {
            session().pendingTransformOps.add(String.format("glOrtho(%.4f, %.4f, %.4f, %.4f, %.4f, %.4f)", left, right, bottom, top, zNear, zFar));
        }
    }

    /** Accumulate frustum projection into session().relativeTransform. */
    public static void updateRelativeTransformFrustum(double left, double right, double bottom, double top, double zNear, double zFar) {
        if (session().relativeTransform == null) return;

        final Matrix4f transform = orthoFrustumTemp.get()
            .identity().frustum((float) left, (float) right, (float) bottom, (float) top, (float) zNear, (float) zFar);

        if (DEBUG_DISPLAY_LISTS) {
            if (session().recorder != null) session().recorder.recordMultMatrix(transform);
            if (session().listMode == GL11.GL_COMPILE_AND_EXECUTE) {
                GLStateManager.getMatrixStack().mul(transform);
            }
            return;
        }

        session().relativeTransform.mul(transform);
        if (session().pendingTransformOps != null) {
            session().pendingTransformOps.add(String.format("glFrustum(%.4f, %.4f, %.4f, %.4f, %.4f, %.4f)", left, right, bottom, top, zNear, zFar));
        }
    }

    /**
     * Reset the relative transform to identity during display list compilation.
     * Called by GLStateManager.glLoadMatrix() - after loading an absolute matrix,
     * subsequent transforms are relative to that loaded matrix (i.e., start from identity).
     */
    public static void resetRelativeTransform() {
        if (session().relativeTransform == null) {
            return;
        }
        session().relativeTransform.identity();
    }

    /**
     * Check if a display list exists (has been compiled and stored).
     * Checks both DisplayListManager's cache and VBOManager for GTNHLib compatibility.
     *
     * @param list The display list ID to check
     * @return true if the display list exists, false otherwise
     */
    public static boolean displayListExists(int list) {
        // Check VBOManager for GTNHLib compatibility (negative IDs)
        if (list < -1) {
            return VBOManager.get(list) != null;
        }
        return currentNamespace().contains(list);
    }

    /**
     * Get a compiled display list from the cache.
     *
     * @param list The display list ID
     * @return The CompiledDisplayList, or null if not found
     */
    public static CompiledDisplayList getDisplayList(int list) {
        return currentNamespace().get(list);
    }

    /** Allocates names in the current context's shared display-list namespace. */
    public static int glGenLists(int range) {
        return currentNamespace().allocateRange(range);
    }

    /** Reports only lists that have been compiled in the current share group. */
    public static boolean glIsList(int list) {
        return currentNamespace().contains(list);
    }

    /** Deletes compiled lists and reserved names from the current share group only. */
    public static void glDeleteLists(int list, int range) {
        deleteCompiledLists(currentNamespace().deleteRange(list, range));
    }

    private static DisplayListNamespace currentNamespace() {
        final GLContextIdentity identity = GLStateManager.currentContextIdentity();
        if (identity == null) {
            throw new IllegalStateException("Display-list operations require a registered current context");
        }
        return displayListNamespaces.forShareGroup(identity.shareGroupId());
    }

    private static DisplayListNamespace namespaceFor(GLContextIdentity identity) {
        if (identity == null) {
            throw new IllegalStateException("Display-list compilation requires a registered current context");
        }
        return displayListNamespaces.forShareGroup(identity.shareGroupId());
    }

    /** Drops CPU and GL resource owners only after the final context in a share group has retired. */
    private static void onContextRetired(GLContextIdentity identity) {
        abortCompilationForContext(identity);
        compilationSessions.remove(identity);
        if (!GLContextRegistryProvider.get().liveContextsInShareGroup(identity.shareGroupId()).isEmpty()) {
            return;
        }
        deleteCompiledLists(displayListNamespaces.retireShareGroup(identity.shareGroupId()));
    }

    private static void deleteCompiledLists(List<CompiledDisplayList> compiledLists) {
        Throwable failure = null;
        for (CompiledDisplayList compiled : compiledLists) {
            if (compiled == CompiledDisplayList.EMPTY) {
                continue;
            }
            try {
                compiled.delete();
            } catch (RuntimeException | Error cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else if (failure != cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    /**
     * Start display list compilation.
     * Supports nested glNewList() calls (spec-violating but needed for mod compatibility).
     *   GL_COMPILE: Commands are recorded only (not executed), GLSM cache unchanged
     *   GL_COMPILE_AND_EXECUTE: Commands are recorded AND executed (GLSM cache updated)
     */
    public static synchronized void glNewList(int list, int mode) {
        final GLContextIdentity currentIdentity = GLStateManager.currentContextIdentity();
        if (currentIdentity == null) {
            throw new IllegalStateException("Display-list compilation requires a registered current context");
        }
        sessionFor(currentIdentity);
        final boolean isNested = session().listMode > 0;

        if (isNested) {
            if (session().recorder == null || Thread.currentThread() != session().recordingThread
                || !session().identity.equals(currentIdentity)) {
                throw new IllegalStateException(
                    "Another context already owns an active display-list compilation");
            }
            // Nested display list compilation violates OpenGL spec, but some of our optimizations require it
            final DisplayListCompilationSession.Frame parentContext = new DisplayListCompilationSession.Frame(
                session().listId, session().listMode, session().virtualStateScopes.depth(), session().captureActive,
                session().capture, session().recorder, session().accumulatedDraws, session().relativeTransform,
                session().stateGeneration, session().stackTrace, session().pendingTransformOps,
                session().matrixSources, session().drawSources
            );
            session().parents.push(parentContext);
        }

        session().listId = list;
        session().listMode = mode;
        session().virtualStateScopes.clear();
        session().captureActive = false;
        session().recordingThread = Thread.currentThread();  // Track which thread is recording
        session().recorder = new CommandRecorder();  // Create command recorder
        synchronized (recorderOwners) {
            recorderOwners.put(session().recorder, session());
        }
        session().accumulatedDraws = new ArrayList<>(16);   // Fewer draws than commands typically
        session().relativeTransform = new Matrix4fStack(GLStateManager.MAX_MODELVIEW_STACK_DEPTH);
        session().stateGeneration = 0;  // Reset state generation for fresh list
        session().stackTrace = LOG_DISPLAY_LIST_COMPILATION ? Thread.currentThread().getStackTrace() : null;

        if (LOG_DISPLAY_LIST_COMPILATION) {
            session().pendingTransformOps = new ArrayList<>();
            session().matrixSources = new ArrayList<>();
            session().drawSources = new ArrayList<>();
        } else {
            session().pendingTransformOps = null;
            session().matrixSources = null;
            session().drawSources = null;
        }

        try {
            final DisplayListCompilationSession compiling = session();
            compiling.capture = TessellatorManager.startOwned((tessellator) -> {
                if (!tessellator.isEmpty()) {
                    addAccumulatedDraw(compiling, tessellator, false);
                }
                return true;
            });
            compiling.captureActive = true;
        } catch (RuntimeException | Error exception) {
            abortCompilation();
            throw exception;
        }
    }

    /**
     * End display list compilation and build optimized/unoptimized versions.
     */
    public static synchronized void glEndList() {
        if (session().listMode == 0) {
            throw new RuntimeException("glEndList called outside of a display list!");
        }
        final GLContextIdentity compiledIdentity = session().identity;
        if (compiledIdentity == null || !compiledIdentity.equals(GLStateManager.currentContextIdentity())) {
            throw new IllegalStateException("Display-list compilation context changed before glEndList");
        }
        if (session().virtualStateScopes.depth() != 0) {
            final int unclosedDepth = session().virtualStateScopes.depth();
            abortCompilation();
            throw new IllegalStateException("Display-list compilation ended with " + unclosedDepth + " open StateSet scopes");
        }
        flushMatrix();
        final boolean isNested = !session().parents.isEmpty();

        if (!session().captureActive) {
            abortCompilation();
            throw new IllegalStateException("Display-list compilation lost its tessellator capture");
        }
        TessellatorManager.stop(session().capture);
        session().captureActive = false;
        session().capture = null;

        CompiledDisplayList compiled;
        DisplayListVBO compiledBuffers = null;
        CommandBuffer finalBuffer = null;
        List<IndexedDrawBatch> indexedBatches = Collections.emptyList();
        try {
            // Create CompiledDisplayList with both unoptimized and optimized versions.
            final CommandBuffer rawCommandBuffer = session().recorder.getBuffer();
            final List<AccumulatedDraw> recordedDraws = session().accumulatedDraws;
            final boolean hasCommands = !rawCommandBuffer.isEmpty();
            final boolean hasDraws = recordedDraws != null && !recordedDraws.isEmpty();

            if (hasCommands || hasDraws) {
                try {
                    // The builder owns and frees every CPU draw buffer on success and failure.
                    compiledBuffers = new DisplayListVBOBuilder().addDraws(recordedDraws).build();
                } finally {
                    session().accumulatedDraws = null;
                }

                finalBuffer = new CommandBuffer();
                CommandBufferBuilder.buildFromRawBuffer(rawCommandBuffer, recordedDraws, finalBuffer);

                final IndexedDrawBatchBuilder indexedBuilder = session().recorder.getIndexedDraws();
                if (!indexedBuilder.isEmpty()) {
                    final CommandRecorder paused = pauseRecording();
                    try {
                        indexedBatches = indexedBuilder.build();
                    } finally {
                        resumeRecording(paused);
                    }
                }

                compiled = new CompiledDisplayList(
                    finalBuffer.toBuffer(), finalBuffer.getComplexObjects(), compiledBuffers, indexedBatches, compiledIdentity);
            } else {
                compiled = CompiledDisplayList.EMPTY;
            }

            synchronized (recorderOwners) {
                recorderOwners.remove(session().recorder);
            }
            session().recorder.free();
            session().recorder = null;
        } catch (RuntimeException | Error exception) {
            if (finalBuffer != null) {
                try {
                    finalBuffer.free();
                } catch (RuntimeException | Error cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            if (compiledBuffers != null) {
                try {
                    compiledBuffers.delete();
                } catch (RuntimeException | Error cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            for (IndexedDrawBatch batch : indexedBatches) {
                try {
                    batch.delete();
                } catch (RuntimeException | Error cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            try {
                abortCompilation();
            } catch (RuntimeException | Error cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
        // Store the compiled list (even if empty - an empty list is still a valid list).
        // Re-recording an existing id replaces it; free the previous list's GPU resources.
        final DisplayListNamespace namespace = namespaceFor(compiledIdentity);
        final CompiledDisplayList previous = namespace.put(session().listId, compiled);
        if (previous != null && previous != compiled) {
            deleteCompiledLists(List.of(previous));
        }

        // Log compilation details if enabled (before context restoration changes session().listId)
        if (LOG_DISPLAY_LIST_COMPILATION) {
            logCompiledDisplayList(session().listId, compiled, session().stackTrace);
        }

        if (isNested) {
            final DisplayListCompilationSession.Frame parentContext = session().parents.pop();

            session().listId = parentContext.listId();
            session().listMode = parentContext.listMode();
            session().virtualStateScopes.restore(parentContext.virtualStateDepth());
            session().captureActive = parentContext.captureActive();
            session().capture = parentContext.capture();
            session().recorder = parentContext.recorder();
            session().accumulatedDraws = parentContext.draws();
            session().relativeTransform = parentContext.transform();
            session().stateGeneration = parentContext.stateGeneration();
            session().stackTrace = parentContext.stackTrace();
            session().pendingTransformOps = parentContext.pendingOps();
            session().matrixSources = parentContext.matrixSources();
            session().drawSources = parentContext.drawSources();

            // Note: TessellatorManager callback stack was popped by stopCompiling()
            // Parent's COMPILING callback is now active again
        } else {
            session().recorder = null;
            session().recordingThread = null;
            session().virtualStateScopes.clear();
            session().captureActive = false;
            session().accumulatedDraws = null;
            session().relativeTransform = null;
            session().stackTrace = null;
            session().pendingTransformOps = null;
            session().matrixSources = null;
            session().drawSources = null;
            session().listId = -1;
            session().listMode = 0;
            session().clear();
        }
    }

    private static void addAccumulatedDraw(
        DisplayListCompilationSession owner,
        DirectTessellator tessellator,
        boolean copyLast
    ) {
        final int cmdIndex = owner.recorder == null ? 0 : owner.recorder.getCommandCount();
        matrixBarrier(owner);

        if (owner.accumulatedDraws.isEmpty()) {
            owner.accumulatedDraws.add(
                new AccumulatedDraw(
                    tessellator, cmdIndex, owner.stateGeneration, copyLast
                )
            );
            return;
        }

        final AccumulatedDraw previous = owner.accumulatedDraws.get(owner.accumulatedDraws.size() - 1);
        if (previous.format == tessellator.getVertexFormat() && previous.stateGeneration == owner.stateGeneration) {
            previous.mergeDraw(tessellator, copyLast);
            return;
        }

        owner.accumulatedDraws.add(
            new AccumulatedDraw(tessellator, cmdIndex, owner.stateGeneration, copyLast)
        );
    }

    /**
     * Execute a compiled display list.
     */
    public static void glCallList(int list) {
        if (isRecording()) {
            recordCallList(list);

            if (isCompileAndExecute()) {
                // Don't record the GL calls, we already recorded glCallList
                final CommandRecorder paused = pauseRecording();
                try {
                    executeDisplayList(list);
                } finally {
                    resumeRecording(paused);
                }
            }
            return;
        }

        executeDisplayList(list);
    }

    private static void executeDisplayList(int list) {
        final CompiledDisplayList compiled = currentNamespace().get(list);
        if (compiled != null) {
            final int prevList = session().currentRenderingList;
            session().currentRenderingList = list;
            compiled.render();
            session().currentRenderingList = prevList;
            return;
        }

        // Fall back to VBOManager or legacy display list
        if (list < 0) {
            // Negative IDs are VBOManager space (entity models, etc.)
            final VertexBuffer vbo = VBOManager.get(list);
            if (vbo != null) {
                vbo.render();
            }
            // Per OpenGL spec: if list is undefined, glCallList has no effect
        }
        // An uncached positive-ID list = no-op (per OpenGL spec)
    }

    public static void logCompiledDisplayList(int listId, CompiledDisplayList compiled, StackTraceElement[] stackTrace) {
        GLStateManager.LOGGER.debug(getCompiledDisplayListString(listId, compiled, stackTrace));
    }

    public static String getCompiledDisplayListString(int listId, CompiledDisplayList compiled, StackTraceElement[] stackTrace) {
        final StringBuilder sb = new StringBuilder();
        sb.append("\n========== Display List Compiled: ID=").append(listId).append(" ==========\n");

        sb.append("Source: ").append(identifySourceFromStackTrace(stackTrace)).append("\n");

        if (compiled == CompiledDisplayList.EMPTY) {
            sb.append("Contents: EMPTY (no commands or draws)\n");
        } else {
            final ByteBuffer buffer = compiled.getCommandBuffer();
            if (buffer == null || buffer.limit() == 0) {
                sb.append("Contents: No commands\n");
            } else {
                sb.append("Commands (").append(buffer.limit()).append(" bytes):\n");
                dumpCommandBuffer(buffer, compiled.getComplexObjects(), sb);
            }
        }

        sb.append("\nFull Stack Trace:\n");
        if (stackTrace != null) {
            for (StackTraceElement element : stackTrace) {
                sb.append("  at ").append(element.toString()).append("\n");
            }
        } else {
            sb.append("  (not captured)\n");
        }

        sb.append("========== End Display List ").append(listId).append(" ==========\n");
        return sb.toString();
    }

    private static String identifySourceFromStackTrace(StackTraceElement[] stackTrace) {
        if (stackTrace == null || stackTrace.length == 0) {
            return "Unknown (no stack trace)";
        }

        for (StackTraceElement element : stackTrace) {
            final String className = element.getClassName();

            // Skip Java/LWJGL/Angelica/Minecraft internals
            if (className.startsWith("java.") || className.startsWith("sun.") ||
                className.startsWith("jdk.") ||
                className.startsWith("org.lwjgl.") ||
                className.startsWith("com.gtnewhorizons.angelica.") ||
                className.startsWith("com.gtnewhorizon.gtnhlib.") ||
                className.startsWith("net.minecraft.") ||
                className.startsWith("net.minecraftforge.") ||
                className.contains("GLStateManager") ||
                className.contains("GradleStart") ||
                className.contains("launchwrapper") ||
                className.contains("retrofuturabootstrap")) {
                continue;
            }

            final String modGuess = guessModFromClassName(className);
            return modGuess + " (" + element.getClassName() + "." + element.getMethodName() +
                   ":" + element.getLineNumber() + ")";
        }

        // Fallback: return the first non-internal frame with class info
        for (StackTraceElement element : stackTrace) {
            final String className = element.getClassName();
            if (!className.startsWith("java.") && !className.startsWith("sun.") && !className.startsWith("jdk.")) {
                return guessModFromClassName(className) + " (" + element + ")";
            }
        }
        return "Unknown";
    }

    private static String guessModFromClassName(String className) {
        final String lc = className.toLowerCase();

        // Common mod package patterns (use lowercase for matching)
        if (lc.contains("buildcraft")) return "BuildCraft";
        if (lc.contains("cofh")) return "CoFH/ThermalExpansion";
        if (lc.contains(".ic2.") || lc.startsWith("ic2.")) return "IndustrialCraft2";
        if (lc.contains("gregtech")) return "GregTech";
        if (lc.contains("thaumcraft")) return "Thaumcraft";
        if (lc.contains("forestry")) return "Forestry";
        if (lc.contains("appeng") || lc.contains(".ae2.")) return "Applied Energistics 2";
        if (lc.contains("enderio") || lc.contains("crazypants.ender")) return "EnderIO";
        if (lc.contains("mekanism")) return "Mekanism";
        if (lc.contains("tconstruct")) return "Tinkers Construct";
        if (lc.contains("chisel")) return "Chisel";
        if (lc.contains("opencomputers") || lc.contains("li.cil.oc")) return "OpenComputers";
        if (lc.contains("computercraft") || lc.contains("dan200.computer")) return "ComputerCraft";
        if (lc.contains("railcraft")) return "Railcraft";
        if (lc.contains("projectred") || lc.contains("mrtjpcore")) return "Project Red";
        if (lc.contains("botania")) return "Botania";
        if (lc.contains("extrautil")) return "ExtraUtilities";
        if (lc.contains("openblocks")) return "OpenBlocks";
        if (lc.contains("carpenters")) return "Carpenters Blocks";
        if (lc.contains("biomesoplenty")) return "Biomes O Plenty";
        if (lc.contains("natura")) return "Natura";
        if (lc.contains("twilightforest")) return "Twilight Forest";
        if (lc.contains("immersive")) return "Immersive Engineering";
        if (lc.contains("galacticraft")) return "Galacticraft";
        if (lc.contains("draconicevolution")) return "Draconic Evolution";
        if (lc.contains("net.minecraft")) return "Minecraft";

        // Extract first 2 package segments as fallback
        final String[] parts = className.split("\\.");
        if (parts.length >= 2) {
            return parts[0] + "." + parts[1];
        }
        return className;
    }

    private static void dumpCommandBuffer(ByteBuffer buffer, Object[] complexObjects, StringBuilder sb) {
        if (buffer == null) return;

        final long basePtr = memAddress(buffer);
        long ptr = basePtr;
        final long end = basePtr + buffer.limit();
        int cmdNum = 0;
        int drawRangeIdx = 0;   // Index into session().drawSources for source tracking

        while (ptr < end) {
            final int opcode = memGetInt(ptr);
            final String cmdName = GLCommand.getName(opcode);
            sb.append("  ").append(cmdNum++).append(": ").append(cmdName);

            switch (opcode) {
                case GLCommand.ENABLE, GLCommand.DISABLE -> {
                    final int cap = memGetInt(ptr + 4);
                    sb.append("(").append(GLDebug.getCapabilityName(cap)).append(")");
                }
                case GLCommand.BIND_TEXTURE -> {
                    final int target = memGetInt(ptr + 4);
                    final int texture = memGetInt(ptr + 8);
                    sb.append("(target=").append(target).append(", texture=").append(texture).append(")");
                }
                case GLCommand.DRAW_RANGE -> {
                    final int vboIdx = memGetInt(ptr + 4);
                    sb.append("(vbo=").append(vboIdx).append(")");
                    if (session().drawSources != null && drawRangeIdx < session().drawSources.size()) {
                        sb.append(" [from: ").append(session().drawSources.get(drawRangeIdx)).append("]");
                    }
                    drawRangeIdx++;
                }
                case GLCommand.DRAW_RANGE_RESTORE -> {
                    final int vboIdx = memGetInt(ptr + 4);
                    final int start = memGetInt(ptr + 8);
                    final int count = memGetInt(ptr + 12);
                    final int brightness = memGetInt(ptr + 16);
                    sb.append("(vbo=").append(vboIdx).append(", start=").append(start)
                        .append(", count=").append(count).append(", brightness=").append(brightness != 0).append(")");
                    if (session().drawSources != null && drawRangeIdx < session().drawSources.size()) {
                        sb.append(" [from: ").append(session().drawSources.get(drawRangeIdx)).append("]");
                    }
                    drawRangeIdx++;
                }
                case GLCommand.MULT_MATRIX, GLCommand.LOAD_MATRIX -> {
                    sb.append("(");
                    for (int i = 0; i < 16; i++) {
                        float value = MemoryUtilities.memGetFloat(ptr + 4 + i * 4);
                        sb.append(i == 0 ? value : ", " + value);
                    }
                    sb.append(")");
                }
                case GLCommand.COLOR -> {
                    final float r = Float.intBitsToFloat(memGetInt(ptr + 4));
                    final float g = Float.intBitsToFloat(memGetInt(ptr + 8));
                    final float b = Float.intBitsToFloat(memGetInt(ptr + 12));
                    final float a = Float.intBitsToFloat(memGetInt(ptr + 16));
                    sb.append("(").append(r).append(", ").append(g).append(", ").append(b).append(", ").append(a).append(")");
                }
                case GLCommand.DEPTH_MASK -> {
                    final int flag = memGetInt(ptr + 4);
                    sb.append("(").append(flag != 0).append(")");
                }
                case GLCommand.BLEND_FUNC -> {
                    final int srcRgb = memGetInt(ptr + 4);
                    final int dstRgb = memGetInt(ptr + 8);
                    final int srcAlpha = memGetInt(ptr + 12);
                    final int dstAlpha = memGetInt(ptr + 16);
                    sb.append("(srcRgb=").append(srcRgb).append(", dstRgb=").append(dstRgb)
                      .append(", srcAlpha=").append(srcAlpha).append(", dstAlpha=").append(dstAlpha).append(")");
                }
                case GLCommand.CALL_LIST -> {
                    final int calledList = memGetInt(ptr + 4);
                    sb.append("(").append(calledList).append(")");
                }
                case GLCommand.MATRIX_MODE -> {
                    final int mode = memGetInt(ptr + 4);
                    sb.append("(").append(mode == GL11.GL_MODELVIEW ? "MODELVIEW" : mode == GL11.GL_PROJECTION ? "PROJECTION" : mode).append(")");
                }
                case GLCommand.COMPLEX_REF -> {
                    final int idx = memGetInt(ptr + 4);
                    final Object obj = complexObjects != null && idx < complexObjects.length ? complexObjects[idx] : null;
                    sb.append("(idx=").append(idx).append(", type=").append(obj != null ? obj.getClass().getSimpleName() : "null").append(")");
                }
            }
            ptr += GLCommand.getCommandSize(opcode, ptr);
            sb.append("\n");
        }
    }

}
