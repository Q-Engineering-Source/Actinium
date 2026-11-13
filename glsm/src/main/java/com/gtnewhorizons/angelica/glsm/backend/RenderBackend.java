package com.gtnewhorizons.angelica.glsm.backend;

import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;
import com.gtnewhorizons.angelica.glsm.GLESFormatRemap;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.texture.TextureStaging;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;

/**
 * Abstract rendering backend.
 */
public abstract class RenderBackend {

    /** Maximum queued frames shared by presentation and transient resource pools. */
    public static final int MAX_FRAMES_IN_FLIGHT = 3;

    /** Attaches backend resources after the window exists, before render context initialization. */
    public void onPostWindowCreate(long window) {}

    public abstract void init();
    public abstract void shutdown();
    public abstract boolean isAvailable();
    public abstract String getName();

    /** Returns whether this backend has rendering resources available. Safe to call from any thread. */
    public abstract boolean hasContext();

    /** Returns whether the calling thread may issue commands to this backend. */
    public boolean hasContextOnThread() { return hasContext(); }

    /** Returns whether the calling thread owns the current rendering context. */
    public abstract boolean isCurrent();

    /** Returns whether draws must use indirect submission on this backend. */
    public boolean isIndirectRequired() { return false; }

    /** Identifies the SDL-GPU implementation for backend-specific rendering paths. */
    public boolean isSDLGPU() { return false; }

    /** Higher priority backends are preferred. */
    public int getPriority() { return 0; }

    /**
     * Window-level file drag & drop support (issue #122): the shader pack selection screen
     * enables watching while open, then drains paths via {@link #pollDroppedFiles()} each frame.
     * Backends without a window event system simply keep the no-op defaults.
     */
    public boolean supportsFileDrop() { return false; }

    /** Starts queueing dropped file paths. Must be idempotent. */
    public void startFileDrop() {}

    /** Stops queueing dropped file paths and releases any native callback. Must be idempotent. */
    public void stopFileDrop() {}

    /** Returns the file paths dropped on the window since the last call, on the calling (main) thread. */
    public List<String> pollDroppedFiles() { return Collections.emptyList(); }

    public abstract int getMinGLSLVersion();

    /** False on backends where EXT_texture_filter_anisotropic is unavailable (e.g. some GLES stacks). */
    public boolean isAnisotropicSupported() { return true; }

    /** False on backends without geometry shader support (e.g. GLES). */
    public boolean supportsGeometryShaders() { return true; }

    /** False on backends where framebuffer completeness checks are unreliable (e.g. some GLES stacks). */
    public boolean framebufferCompletenessIsMeaningful() { return true; }

    /**
     * Reports whether this backend can run the compute-driven terrain culling path. A backend that cannot dispatch
     * compute work keeps terrain on the CPU emitter, whatever the driver advertises.
     */
    public boolean supportsGpuDrivenCulling() { return RenderSystem.supportsCompute(); }

    /**
     * Opens a batch of compute dispatches. Backends that must serialize the dispatches of one frame against their own
     * command stream bracket them here; a backend that maps compute onto the driver's queue needs no bracket.
     */
    public void beginComputeDispatchBatch() {}

    /** Closes the batch opened by {@link #beginComputeDispatchBatch()}. Must stay paired with it. */
    public void endComputeDispatchBatch() {}

    /**
     * Reports a write into a persistently mapped buffer so a backend that tracks the mapping can
     * keep its own view of the buffer coherent. Backends that map through the GL driver ignore it.
     */
    public void onPersistentBufferWrite(int glId, long offset, long size) {}

    /**
     * Reports whether a buffer was created with {@code glBufferStorage} and can never be
     * re-specified, so a caller that wants to replace its contents knows re-storing is not an
     * option. Backends that map through the driver report false and never ask.
     */
    public boolean isBufferImmutable(int glId) { return false; }

    public abstract void flush();
    public abstract void finish();

    /**
     * Native blocks released by the client staging path, reused by the next request of the same
     * capacity. Entries are chosen so a full cache keeps the larger blocks.
     */
    private final ByteBuffer[] clientStagingCache = new ByteBuffer[16];
    private VSyncMode effectiveVSyncMode = VSyncMode.ON;
    private VSyncMode preferredTearFreeMode;
    private boolean vsyncHonored = true;

    /** Applies the vsync state owned by {@code GameSettings.enableVsync}. */
    public final void setVSyncEnabled(boolean enabled) {
        setVSyncMode(enabled ? preferredTearFreeMode() : VSyncMode.OFF);
    }

    /**
     * Requests a presentation mode and republishes the effective mode together with a new display generation, which
     * tells the frame pacer to discard the samples it collected under the previous mode.
     */
    public final void setVSyncMode(VSyncMode preferred) {
        setVSyncPreference(preferred);
        final VSyncMode mode = applyVSyncMode(preferred);
        vsyncHonored = applyHonored();
        publishVSyncMode(mode);
    }

    /** Records the tear-free mode the user selected so a later {@link #setVSyncEnabled(boolean)} can restore it. */
    public final void setVSyncPreference(VSyncMode preferred) {
        if (preferred.tearFree()) preferredTearFreeMode = preferred;
    }

    /** Returns the tear-free mode to restore, falling back to {@link VSyncMode#ON} when the preference is unusable. */
    public final VSyncMode preferredTearFreeMode() {
        return resolveTearFreeMode(preferredTearFreeMode, this::supportsVSyncMode);
    }

    static VSyncMode resolveTearFreeMode(VSyncMode preferred, Predicate<VSyncMode> supported) {
        if (preferred != null && preferred.tearFree() && supported.test(preferred)) return preferred;
        return VSyncMode.ON;
    }

    /**
     * Hands the requested presentation mode to the window system and returns the mode that is now in effect.
     *
     * <p>Window systems do not report when the driver policy overrides the request, so the return value describes what
     * was asked for. Whether the driver actually blocks on presentation is decided from gate samples.
     */
    protected VSyncMode applyVSyncMode(VSyncMode preferred) {
        final boolean vsync = preferred.tearFree();
        WindowBackends.get().setSwapInterval(vsync);
        return vsync ? VSyncMode.ON : VSyncMode.OFF;
    }

    /** Returns whether the window system reported that the requested swap interval took effect. */
    protected boolean applyHonored() { return true; }

    public final boolean vsyncHonored() { return vsyncHonored; }

    /** Returns whether the window system can present with the given mode. GLFW only offers {@code OFF} and {@code ON}. */
    public boolean supportsVSyncMode(VSyncMode mode) { return mode != VSyncMode.MAILBOX; }

    protected final void publishVSyncMode(VSyncMode mode) {
        effectiveVSyncMode = mode;
        bumpDisplayGeneration();
    }

    public final VSyncMode getEffectiveVSyncMode() { return effectiveVSyncMode; }

    private int displayGeneration;

    /** Increments whenever the presentation mode or the measured refresh period changes. */
    public final int displayGeneration() { return displayGeneration; }

    protected final void bumpDisplayGeneration() { displayGeneration++; }

    private static final long REFRESH_POLL_NANOS = 1_000_000_000L;
    private static final long MIN_PLAUSIBLE_PERIOD_NANOS = 1_000_000L;
    private static final long MAX_PLAUSIBLE_PERIOD_NANOS = 50_000_000L;

    private long refreshPollNanos;
    private long cachedRefreshPeriodNanos;

    /**
     * Returns the refresh period of the presenting display in nanoseconds, refreshed once per second, or zero when the
     * window system cannot report a plausible value. A zero period disables refresh-aligned pacing.
     */
    public final long refreshPeriodNanos() {
        final long now = System.nanoTime();
        if (refreshPollNanos == 0L || now - refreshPollNanos >= REFRESH_POLL_NANOS) {
            refreshPollNanos = now;
            final long fresh = plausiblePeriodNanos(queryRefreshPeriodNanos());
            if (fresh != cachedRefreshPeriodNanos) bumpDisplayGeneration();
            cachedRefreshPeriodNanos = fresh;
        }
        return cachedRefreshPeriodNanos;
    }

    protected long queryRefreshPeriodNanos() { return WindowBackends.get().refreshPeriodNanos(); }

    static long plausiblePeriodNanos(long periodNanos) {
        return (periodNanos >= MIN_PLAUSIBLE_PERIOD_NANOS && periodNanos <= MAX_PLAUSIBLE_PERIOD_NANOS) ? periodNanos : 0L;
    }

    public static long periodFromRational(int numerator, int denominator, float fallbackHz) {
        if (numerator > 0 && denominator > 0) {
            final long scaledDenominator = 1_000_000_000L * denominator;
            return (scaledDenominator + numerator / 2) / numerator;
        }
        return fallbackHz > 0.0f ? Math.round(1_000_000_000.0 / fallbackHz) : 0L;
    }

    private static final int[] TRUNCATED_NTSC_HZ = {23, 29, 59, 71, 119, 143, 239};

    private static boolean isTruncatedNtscRate(int hz) {
        for (int candidate : TRUNCATED_NTSC_HZ) {
            if (candidate == hz) return true;
        }
        return false;
    }

    /**
     * Converts a whole-Hz refresh rate into a period. Rates reported truncated from a 1000/1001 NTSC base are expanded
     * to the real period, so a 59Hz report becomes 16.683ms instead of 16.949ms and stays aligned with the vblank.
     */
    public static long periodFromIntegerHz(int hz) {
        if (hz <= 0) return 0L;
        if (isTruncatedNtscRate(hz)) {
            final long denominator = (hz + 1) * 1000L;
            return Math.round(1_000_000_000.0 * 1001 / denominator);
        }
        return Math.round(1_000_000_000.0 / hz);
    }

    public static int hzFromPeriod(long periodNanos) {
        return periodNanos <= 0L ? 0 : (int) Math.round(1_000_000_000.0 / periodNanos);
    }

    /** Returns whether the backend's swapchain throttles presentation on its own. Only the SDL-GPU backend does. */
    public boolean hasSwapchainBackpressure() { return false; }

    /** One frame's presentation sample: how long the swap call blocked, when it returned, and GPU wait time. */
    public static final class GateSample {
        public long durationNanos;
        public long endNanos;
        public long gpuWaitNanos;
    }

    /**
     * Keeps the longest gate of a frame, because a frame can present more than once when a shader pack renders to
     * intermediate targets, and only the longest block reflects the driver's vblank alignment. The recorded end time
     * belongs to that longest block so the pacer can anchor the next frame on a real presentation timestamp.
     */
    static final class GateRecorder {

        private long gateDurationNanos;
        private long gateEndNanos;
        private long gpuWaitNanos;

        synchronized void recordGate(long durationNanos, long endNanos) {
            if (gateEndNanos != 0L && gateDurationNanos >= durationNanos) return;
            gateDurationNanos = durationNanos;
            gateEndNanos = endNanos;
        }

        synchronized void recordGpuWait(long nanos) {
            gpuWaitNanos += nanos;
        }

        synchronized void readGateSample(GateSample out) {
            final long end = gateEndNanos;
            out.durationNanos = end == 0L ? 0L : gateDurationNanos;
            out.endNanos = end;
            out.gpuWaitNanos = gpuWaitNanos;
            gpuWaitNanos = 0L;
            gateEndNanos = 0L;
        }
    }

    private final GateRecorder gateRecorder = new GateRecorder();

    /** Returns whether the backend wants presentation timing attached to display updates. */
    public boolean wantsDisplayUpdateGateTiming() { return true; }

    /** Reports one presentation block. The longest block of the frame wins, see {@link GateRecorder}. */
    public final void recordGate(long durationNanos, long endNanos) {
        gateRecorder.recordGate(durationNanos, endNanos);
    }

    /** Reports time the main thread spent waiting for the GPU outside presentation. */
    public final void recordGpuWait(long nanos) {
        gateRecorder.recordGpuWait(nanos);
    }

    public final void readGateSample(GateSample out) {
        gateRecorder.readGateSample(out);
    }

    /** Blocks until the next presentation slot when the backend has a way to wait for one. */
    public void awaitPresent() {}

    /** Sleeps the calling thread for the requested duration. Window systems with a better primitive override this. */
    public void parkNanos(long nanos) {
        LockSupport.parkNanos(nanos);
    }

    /** Processes pending window events without presenting, so input stays responsive while the pacer waits. */
    public void pumpDisplayMessages() {
        WindowBackends.get().pumpMessages();
    }

    private boolean presentSuppressed;

    /** Prevents presentation while intermediate passes still need the current frame. */
    public final void setPresentSuppressed(boolean suppressed) { presentSuppressed = suppressed; }

    /** Returns whether the current frame must remain available for further rendering. */
    public final boolean isPresentSuppressed() { return presentSuppressed; }

    /**
     * Called by the frame loop before the first GL work of a frame. Consumes the splash state
     * seed: after the splash screen finishes, the game loop inherits a context whose driver
     * state predates GLSM caching, so the full cached state is replayed once.
     */
    public void onFrameBegin() {
        if (GLStateManager.takeStateSeedPending()) {
            GLStateManager.replayStateToBackend();
        }
    }
    public void onFrameEnd() {}

    /** Discards a backend mirror when cached GLSM state will be replayed. */
    public void invalidateStateMirror() {}

    /** Invalidates pacing samples before the window system recreates the swapchain. */
    public void onPreSwapchainInvalidatingChange(Object change) { bumpDisplayGeneration(); }

    /** Returns whether this backend acquired a logical context without the drawable's native GL call. */
    public boolean handleMakeCurrent(Object drawable) { return false; }

    /** Returns whether this backend released a logical context without the drawable's native GL call. */
    public boolean handleReleaseContext(Object drawable) { return false; }

    /** Returns whether this backend presented the frame without the window system's native GL swap. */
    public boolean handleSwapBuffers() { return false; }

    /** Releases command and context resources when a rendering thread relinquishes ownership. */
    public void onRenderThreadReleased(Thread thread) {}

    /** Presents from a worker and routes event processing through the backend's window thread. */
    public void updateDisplayFromWorkerThread(boolean processMessages) {
        GLStateManager.swapBuffers();
        if (processMessages) pumpDisplayMessages();
    }

    /** Returns backend transfer statistics for the debug overlay, or null when unavailable. */
    public String getTransferDebugInfo() { return null; }

    /** Returns a native voxelization pass handle, or zero to keep using individual compute dispatches. */
    public long beginVoxelizationBatch(int ssboBinding) { return 0L; }

    /** Emits one region into a pass returned by {@link #beginVoxelizationBatch(int)}. */
    public void voxelizeRegion(long pass, boolean rebindVertexBuffer, float x, float y, float z,
                              int rangeBase, int rangeCount, int vertexTotal) {}

    /** Submits a native voxelization pass; OpenGL uses the individual dispatch path. */
    public void endVoxelizationBatch(long pass) {}

    /** Installs compute replay metadata where the native backend cannot execute vertex-stage image writes. */
    public void setVertexWriteReplay(int graphicsProgram, VertexWriteReplaySetup setup) {}

    /** OpenGL supports image atomics directly when the caller's compute path is available. */
    public boolean supportsComputeImageAtomics() { return true; }

    /** Checks image dimensions on backends whose atomic support depends on the resource size. */
    public boolean supportsComputeImageAtomics(int width, int height, int depth) {
        return supportsComputeImageAtomics();
    }

    public abstract void enable(int cap);
    public abstract void enablei(int cap, int index);
    public abstract void disable(int cap);
    public abstract void disablei(int cap, int index);
    /** Queries the state of one indexed capability such as a draw buffer's blend enable. */
    public boolean isEnabledIndexed(int cap, int index) {
        return getIntegerIndexed(cap, index) != 0;
    }
    public abstract void blendFuncSeparatei(int buf, int srcRGB, int dstRGB, int srcAlpha, int dstAlpha);
    public abstract void blendFunc(int sfactor, int dfactor);
    public abstract void blendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha);
    public abstract void blendEquation(int mode);
    public abstract void blendEquationSeparate(int modeRGB, int modeAlpha);
    public abstract void blendColor(float red, float green, float blue, float alpha);
    public abstract void depthFunc(int func);
    public abstract void depthMask(boolean flag);
    public abstract void colorMask(boolean red, boolean green, boolean blue, boolean alpha);
    public abstract void cullFace(int mode);
    public abstract void frontFace(int mode);
    public abstract void polygonMode(int face, int mode);
    public abstract void polygonOffset(float factor, float units);
    public void polygonOffsetClamp(float factor, float units, float clamp) { throw new UnsupportedOperationException(getName() + ": polygon offset clamp unsupported"); }
    public abstract void stencilFunc(int func, int ref, int mask);
    public abstract void stencilOp(int sfail, int dpfail, int dppass);
    public abstract void stencilMask(int mask);
    public abstract void stencilFuncSeparate(int face, int func, int ref, int mask);
    public abstract void stencilOpSeparate(int face, int sfail, int dpfail, int dppass);
    public abstract void stencilMaskSeparate(int face, int mask);
    public abstract void viewport(int x, int y, int width, int height);
    public abstract void depthRange(double nearVal, double farVal);
    public abstract void scissor(int x, int y, int width, int height);
    public abstract void clearColor(float red, float green, float blue, float alpha);
    public abstract void clearDepth(double depth);
    public abstract void clearStencil(int s);
    public abstract void clear(int mask);
    public abstract void lineWidth(float width);
    public abstract void pointSize(float size);
    public abstract void logicOp(int opcode);
    public abstract void hint(int target, int mode);

    public abstract void drawArrays(int mode, int first, int count);
    public abstract void drawElements(int mode, int count, int type, long indices);
    public void drawElements(int mode, ByteBuffer indices) {}
    public void drawElements(int mode, IntBuffer indices) {}
    public void drawElements(int mode, ShortBuffer indices) {}
    public void drawElements(int mode, int count, int type, ByteBuffer indices) {}
    public abstract void multiDrawElementsIndirect(int mode, int type, long indirect, int drawcount, int stride);
    public abstract void multiDrawArraysIndirect(int mode, long indirect, int drawcount, int stride);
    public void multiDrawArraysIndirectCount(int mode, long indirect, long drawcount, int maxdrawcount, int stride) { throw new UnsupportedOperationException(getName() + ": indirect draw-count buffers unsupported"); }
    public void multiDrawElementsIndirectCount(int mode, int type, long indirect, long drawcount, int maxdrawcount, int stride) { throw new UnsupportedOperationException(getName() + ": indirect draw-count buffers unsupported"); }
    public abstract void copyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size);
    public abstract void drawElementsInstanced(int mode, int count, int type, long indices, int primcount);
    public void drawArraysInstanced(int mode, int first, int count, int primcount) {
        throw new UnsupportedOperationException("drawArraysInstanced is only supported by the LWJGL3 backend");
    }
    /**
     * Sets the provoking vertex convention for flat varyings (used by line-stipple
     * emulation: v_LineStart must come from each segment's first vertex).
     * No-op on backends without GL 3.2.
     */
    public void provokingVertex(int provokeMode) {}
    public abstract void drawElementsBaseVertex(int mode, int count, int type, long indices, int baseVertex);
    public abstract void multiDrawElementsBaseVertex(int mode, long pCount, int type, long pIndices, int drawcount, long pBaseVertex);
    public void drawRangeElements(int mode, int start, int end, int count, int type, long indices) {
        drawElements(mode, count, type, indices);
    }
    public void drawRangeElementsBaseVertex(int mode, int start, int end, int count, int type, long indices, int baseVertex) {
        drawElementsBaseVertex(mode, count, type, indices, baseVertex);
    }
    public abstract void multiDrawArrays(int mode, IntBuffer firsts, IntBuffer counts);
    public void multiDrawArrays(int mode, int[] firsts, int[] counts) { multiDrawArrays(mode, IntBuffer.wrap(firsts), IntBuffer.wrap(counts)); }
    public abstract void primitiveRestartIndex(int index);
    public abstract void pointParameterf(int pname, float param);
    public abstract void pointParameteri(int pname, int param);
    public abstract void drawBuffer(int mode);
    public abstract void dispatchCompute(int numGroupsX, int numGroupsY, int numGroupsZ);
    public abstract void dispatchComputeIndirect(long offset);

    public abstract int genTextures();
    public abstract void genTextures(IntBuffer textures);
    public abstract void deleteTextures(int texture);
    public abstract void bindTexture(int target, int texture);
    public abstract void activeTexture(int texture);
    public abstract void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, ByteBuffer pixels);
    public abstract void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, DoubleBuffer pixels);
    public abstract void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, FloatBuffer pixels);
    public abstract void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, IntBuffer pixels);
    public abstract void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, long pixelBufferOffset);
    public abstract void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels);
    public abstract void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, IntBuffer pixels);
    public abstract void copyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height);
    public void texImage1D(int target, int level, int internalFormat, int width, int border, int format, int type, ByteBuffer pixels) {}
    public void texImage3D(int target, int level, int internalFormat, int width, int height, int depth, int border, int format, int type, ByteBuffer pixels) {}
    public void texImage3D(int target, int level, int internalFormat, int width, int height, int depth, int border, int format, int type, IntBuffer pixels) {}
    public void texSubImage1D(int target, int level, int xoffset, int width, int format, int type, ByteBuffer pixels) {}
    public void texSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, long pboOffset) {}

    /**
     * Hands out a native block for one {@code width} x {@code height} BGRA region of mip level
     * {@code level}, located at {@code (x, y)}, so the caller can fill the pixels without a GL call.
     * The returned block stays owned by the caller until it is released exactly once through
     * {@link #commitTextureStaging} or {@link #abandonTextureStaging}.
     */
    public TextureStaging beginTextureStaging(int level, int x, int y, int width, int height) {
        final int size = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        ByteBuffer buffer = null;
        for (int i = 0; i < clientStagingCache.length; i++) {
            final ByteBuffer cached = clientStagingCache[i];
            if (cached != null && cached.capacity() == size) {
                clientStagingCache[i] = null;
                buffer = cached;
                break;
            }
        }
        if (buffer == null) buffer = MemoryUtilities.memAlloc(size);
        buffer.clear();
        return new ClientTextureStaging(this, buffer, level, x, y, width, height);
    }

    /**
     * Uploads a block from {@link #beginTextureStaging} as BGRA pixels over its whole capacity and
     * returns the memory to the cache. The staging buffer position is reset first, so a producer that
     * filled the block with relative writes still uploads from its first byte.
     *
     * @return whether the upload happened; the client path reports {@code true} once the pixels reached
     *         the texture
     */
    public boolean commitTextureStaging(TextureStaging staging) {
        final ClientTextureStaging client = claimStaging(staging);
        final ByteBuffer upload = client.buffer;
        try {
            upload.clear();
            texSubImage2D(GL11.GL_TEXTURE_2D, client.level, client.x, client.y, client.width, client.height, GL12.GL_BGRA, stagingBgraPixelType(), upload);
        } finally {
            recycleClientStaging(upload);
        }
        return true;
    }

    /**
     * Pixel type the client staging upload declares for its BGRA bytes. A GLES driver rejects
     * {@code GL_UNSIGNED_INT_8_8_8_8_REV} and takes the bytes through {@code GL_UNSIGNED_BYTE}.
     */
    protected int stagingBgraPixelType() {
        return RenderSystem.isGLES() ? GLESFormatRemap.remapPixelType(GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV) : GL12.GL_UNSIGNED_INT_8_8_8_8_REV;
    }

    /**
     * Drops a block from {@link #beginTextureStaging} whose pixels are no longer wanted, keeping its
     * memory for a later request.
     */
    public void abandonTextureStaging(TextureStaging staging) {
        recycleClientStaging(claimStaging(staging).buffer);
    }

    /**
     * Frees every block already returned to the cache. Blocks a caller still holds are untouched and
     * must be released through {@link #commitTextureStaging} or {@link #abandonTextureStaging}.
     */
    public void trimTextureStaging() {
        for (int i = 0; i < clientStagingCache.length; i++) {
            MemoryUtilities.memFree(clientStagingCache[i]);
            clientStagingCache[i] = null;
        }
    }

    /**
     * Rejects a block that this backend did not issue, was already released, or came from another
     * backend. A successful claim marks the block released, so the memory can never be cached twice or
     * freed while a caller still holds it.
     */
    private ClientTextureStaging claimStaging(TextureStaging staging) {
        if (!(staging instanceof ClientTextureStaging client)) {
            throw new IllegalArgumentException("Texture staging block is not backed by client memory: " + staging);
        }
        if (client.owner != this) {
            throw new IllegalArgumentException("Texture staging block belongs to another render backend: " + staging);
        }
        if (client.released) {
            throw new IllegalStateException("Texture staging block was already released: " + staging);
        }
        client.released = true;
        return client;
    }

    /** Client-side block returned by {@link #beginTextureStaging}; {@code released} guards the single release. */
    private static final class ClientTextureStaging implements TextureStaging {
        private final RenderBackend owner;
        private final ByteBuffer buffer;
        private final int level;
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private boolean released;

        ClientTextureStaging(RenderBackend owner, ByteBuffer buffer, int level, int x, int y, int width, int height) {
            this.owner = owner;
            this.buffer = buffer;
            this.level = level;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        @Override
        public ByteBuffer buffer() {
            return buffer;
        }

        @Override
        public boolean bgra() {
            return true;
        }

        @Override
        public int level() {
            return level;
        }

        @Override
        public String toString() {
            return "client staging block level " + level + " at (" + x + ", " + y + ") " + width + "x" + height;
        }
    }

    /**
     * Keeps the incoming block in a free slot first, then in place of the smallest cached block when
     * that one is smaller; otherwise the incoming block is freed.
     */
    private void recycleClientStaging(ByteBuffer buffer) {
        int victim = -1;
        for (int i = 0; i < clientStagingCache.length; i++) {
            final ByteBuffer cached = clientStagingCache[i];
            if (cached == null) {
                clientStagingCache[i] = buffer;
                return;
            }
            if (cached.capacity() < buffer.capacity() && (victim < 0 || cached.capacity() < clientStagingCache[victim].capacity())) {
                victim = i;
            }
        }
        if (victim < 0) {
            MemoryUtilities.memFree(buffer);
            return;
        }
        MemoryUtilities.memFree(clientStagingCache[victim]);
        clientStagingCache[victim] = buffer;
    }

    public void texSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth, int format, int type, ByteBuffer pixels) {}
    public void copyTexImage1D(int target, int level, int internalFormat, int x, int y, int width, int border) {}
    public void copyTexImage2D(int target, int level, int internalFormat, int x, int y, int width, int height, int border) {}
    public void copyTexSubImage1D(int target, int level, int xoffset, int x, int y, int width) {}
    public void copyTexSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset, int x, int y, int width, int height) {}
    public abstract void compressedTexImage2D(int target, int level, int internalFormat, int width, int height, int border, ByteBuffer pixels);
    public abstract void compressedTexImage2D(int target, int level, int internalFormat, int width, int height, int border, int imageSize, long data);
    public abstract void compressedTexImage3D(int target, int level, int internalFormat, int width, int height, int depth, int border, ByteBuffer pixels);
    public abstract void compressedTexSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth, int format, ByteBuffer pixels);
    public abstract void getCompressedTexImage(int target, int level, ByteBuffer pixels);
    public abstract void texParameteri(int target, int pname, int param);
    public abstract void texParameterf(int target, int pname, float param);
    public abstract void texParameteriv(int target, int pname, IntBuffer params);
    public abstract void texParameterfv(int target, int pname, FloatBuffer params);
    public abstract int getTexParameteri(int target, int pname);
    public abstract float getTexParameterf(int target, int pname);
    public abstract int getTexLevelParameteri(int target, int level, int pname);
    public abstract void generateMipmap(int target);
    public abstract void pixelStorei(int pname, int param);
    public void pixelStoref(int pname, float param) {}
    public void sampleCoverage(float value, boolean invert) {}


    public abstract int genSamplers();
    public abstract void deleteSamplers(int sampler);
    public abstract void bindSampler(int unit, int sampler);
    public abstract void samplerParameteri(int sampler, int pname, int param);
    public abstract void samplerParameterf(int sampler, int pname, float param);

    public abstract int genFramebuffers();
    public abstract void deleteFramebuffers(int framebuffer);
    public abstract void bindFramebuffer(int target, int framebuffer);
    public abstract void framebufferTexture2D(int target, int attachment, int textarget, int texture, int level);
    public void framebufferTexture1D(int target, int attachment, int textarget, int texture, int level) {
        throw new UnsupportedOperationException(getName() + ": 1D framebuffer attachments unsupported");
    }
    public void framebufferTexture3D(int target, int attachment, int textarget, int texture, int level, int zoffset) {
        throw new UnsupportedOperationException(getName() + ": 3D framebuffer attachments unsupported");
    }
    public void framebufferTextureLayer(int target, int attachment, int texture, int level, int layer) {
        throw new UnsupportedOperationException(getName() + ": layered framebuffer attachments unsupported");
    }
    public abstract void framebufferTexture(int target, int attachment, int texture, int level);
    public abstract int genRenderbuffers();
    public abstract void deleteRenderbuffers(int renderbuffer);
    public abstract void bindRenderbuffer(int target, int renderbuffer);
    public abstract void renderbufferStorage(int target, int internalformat, int width, int height);
    public abstract void renderbufferStorageMultisample(int target, int samples, int internalformat, int width, int height);
    public abstract void framebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer);
    public abstract int checkFramebufferStatus(int target);
    public abstract void blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter);
    public abstract void drawBuffers(int buffer);
    public abstract void drawBuffers(IntBuffer bufs);
    public void drawBuffers(int[] bufs) { drawBuffers(IntBuffer.wrap(bufs)); }
    public abstract void readBuffer(int mode);
    public abstract void readPixels(int x, int y, int width, int height, int format, int type, ByteBuffer pixels);
    public abstract void readPixels(int x, int y, int width, int height, int format, int type, FloatBuffer pixels);
    public abstract void readPixels(int x, int y, int width, int height, int format, int type, IntBuffer pixels);
    /** Reads pixels into the currently bound pixel-pack buffer at a byte offset. */
    public void readPixels(int x, int y, int width, int height, int format, int type, long pixelBufferOffset) {
        throw new UnsupportedOperationException(getName() + ": pixel-pack-buffer readback unsupported");
    }
    public abstract void getTexImage(int target, int level, int format, int type, ByteBuffer pixels);
    public abstract void getTexImage(int target, int level, int format, int type, IntBuffer pixels);
    public abstract void getTexImage(int target, int level, int format, int type, long pixelBufferOffset);
    public abstract int getFramebufferAttachmentParameteri(int target, int attachment, int pname);

    public abstract int createShader(int type);
    public abstract void deleteShader(int shader);
    public abstract void shaderSource(int shader, CharSequence source);
    public abstract void compileShader(int shader);
    public void specializeShader(int shader, CharSequence entryPoint, IntBuffer constantIndex, IntBuffer constantValue) { throw new UnsupportedOperationException(getName() + ": externally supplied SPIR-V binaries unsupported"); }
    public abstract int createProgram();
    public abstract void deleteProgram(int program);
    public abstract void attachShader(int program, int shader);
    public abstract void detachShader(int program, int shader);
    public abstract void linkProgram(int program);
    public abstract void useProgram(int program);
    public abstract String getShaderInfoLog(int shader, int maxLength);
    public String getShaderInfoLog(int shader) {
        return getShaderInfoLog(shader, getShaderi(shader, GL20.GL_INFO_LOG_LENGTH));
    }
    public abstract void getShaderInfoLog(int shader, IntBuffer length, ByteBuffer infoLog);
    public void getShaderInfoLog(int shader, int[] length, ByteBuffer infoLog) {
        getShaderInfoLog(shader, length == null ? null : IntBuffer.wrap(length), infoLog);
    }
    public abstract String getProgramInfoLog(int program, int maxLength);
    public String getProgramInfoLog(int program) {
        return getProgramInfoLog(program, getProgrami(program, GL20.GL_INFO_LOG_LENGTH));
    }
    public abstract void getProgramInfoLog(int program, IntBuffer length, ByteBuffer infoLog);
    public void getProgramInfoLog(int program, int[] length, ByteBuffer infoLog) {
        getProgramInfoLog(program, length == null ? null : IntBuffer.wrap(length), infoLog);
    }
    public abstract int getShaderi(int shader, int pname);
    public abstract int getProgrami(int program, int pname);
    public abstract void getProgramiv(int program, int pname, IntBuffer params);
    public abstract String getActiveUniform(int program, int index, int maxLength, IntBuffer sizeType);
    public abstract void getActiveUniform(int program, int index, IntBuffer length, IntBuffer size, IntBuffer type, ByteBuffer name);
    public void getActiveUniform(int program, int index, int[] length, int[] size, int[] type, ByteBuffer name) {
        getActiveUniform(program, index, length == null ? null : IntBuffer.wrap(length), size == null ? null : IntBuffer.wrap(size), type == null ? null : IntBuffer.wrap(type), name);
    }
    public abstract void getActiveAttrib(int program, int index, IntBuffer length, IntBuffer size, IntBuffer type, ByteBuffer name);
    public void getActiveAttrib(int program, int index, int[] length, int[] size, int[] type, ByteBuffer name) {
        getActiveAttrib(program, index, length == null ? null : IntBuffer.wrap(length), size == null ? null : IntBuffer.wrap(size), type == null ? null : IntBuffer.wrap(type), name);
    }
    public abstract String getActiveAttrib(int program, int index, int maxLength, IntBuffer sizeType);
    public abstract void bindAttribLocation(int program, int index, CharSequence name);
    public void bindFragDataLocation(int program, int colorNumber, CharSequence name) {}
    public abstract int getAttribLocation(int program, CharSequence name);
    public abstract int getAttribLocation(int program, ByteBuffer name);
    public abstract int getUniformLocation(int program, CharSequence name);
    public abstract int getUniformLocation(int program, ByteBuffer name);
    public boolean isShader(int obj) { return false; }
    public boolean isProgram(int obj) { return false; }
    public boolean supportsProgramBinary() { return false; }
    public void programParameteri(int program, int pname, int value) {}
    public void getProgramBinary(int program, IntBuffer length, IntBuffer binaryFormat, ByteBuffer binary) {}
    public void programBinary(int program, int binaryFormat, ByteBuffer binary) {}
    public void validateProgram(int program) {}
    public void getAttachedShaders(int program, IntBuffer count, IntBuffer shaders) {
        if (count != null) count.put(0, 0);
    }
    public void getAttachedShaders(int program, int[] count, int[] shaders) {
        getAttachedShaders(program, count == null ? null : IntBuffer.wrap(count), shaders == null ? null : IntBuffer.wrap(shaders));
    }
    public String getShaderSource(int shader, int maxLength) { return ""; }
    public abstract void getShaderSource(int shader, IntBuffer length, ByteBuffer source);
    public void getShaderSource(int shader, int[] length, ByteBuffer source) {
        getShaderSource(shader, length == null ? null : IntBuffer.wrap(length), source);
    }

    public abstract void uniform1i(int location, int v0);
    public abstract void uniform1ui(int location, int v0);
    public abstract void uniform2ui(int location, int v0, int v1);
    public abstract void uniform3ui(int location, int v0, int v1, int v2);
    public abstract void uniform4ui(int location, int v0, int v1, int v2, int v3);
    /** GL40 double uniform. */
    public abstract void uniform1d(int location, double v0);
    public abstract void uniform1f(int location, float v0);
    public void uniform1fv(int location, FloatBuffer values) {}
    public void uniform1fv(int location, float[] values) {}
    public abstract void uniform2f(int location, float v0, float v1);
    public abstract void uniform2i(int location, int v0, int v1);
    public abstract void uniform3f(int location, float v0, float v1, float v2);
    public abstract void uniform3i(int location, int v0, int v1, int v2);
    public abstract void uniform4f(int location, float v0, float v1, float v2, float v3);
    public abstract void uniform4i(int location, int v0, int v1, int v2, int v3);
    public void uniform2(int location, FloatBuffer value) {
        throw new UnsupportedOperationException("uniform2 is only supported by the LWJGL3 backend");
    }
    public abstract void uniform3(int location, FloatBuffer value);
    public abstract void uniform4(int location, FloatBuffer value);
    public void uniform1iv(int location, IntBuffer value) {
        throw new UnsupportedOperationException("uniform1iv is only supported by the LWJGL3 backend");
    }
    public void uniform2iv(int location, IntBuffer value) {
        throw new UnsupportedOperationException("uniform2iv is only supported by the LWJGL3 backend");
    }
    public void uniform3iv(int location, IntBuffer value) {
        throw new UnsupportedOperationException("uniform3iv is only supported by the LWJGL3 backend");
    }
    public void uniform4iv(int location, IntBuffer value) {
        throw new UnsupportedOperationException("uniform4iv is only supported by the LWJGL3 backend");
    }
    public void uniform3fv(int location, float[] values) {}
    public void uniform4fv(int location, float[] values) {}
    public abstract void uniformMatrix3(int location, boolean transpose, FloatBuffer value);
    public abstract void uniformMatrix4(int location, boolean transpose, FloatBuffer value);
    public void uniformMatrix2(int location, boolean transpose, FloatBuffer matrices) {}
    public void uniformMatrix3(int location, boolean transpose, float[] matrices) {}
    public void uniformMatrix4(int location, boolean transpose, float[] matrices) {}
    public void getUniformfv(int program, int location, FloatBuffer params) {}
    public void getUniformiv(int program, int location, IntBuffer params) {}

    public abstract void vertexAttrib2f(int index, float v0, float v1);
    public abstract void vertexAttrib3f(int index, float v0, float v1, float v2);
    public abstract void vertexAttrib4f(int index, float v0, float v1, float v2, float v3);

    public abstract int genBuffers();
    public abstract void deleteBuffers(int buffer);
    public abstract void deleteBuffers(IntBuffer buffers);
    public abstract void bindBuffer(int target, int buffer);
    public abstract void bindBufferBase(int target, int index, int buffer);
    public abstract void bindBufferRange(int target, int index, int buffer, long offset, long size);
    public abstract int getUniformBlockIndex(int program, CharSequence name);
    public int getUniformBlockIndex(int program, ByteBuffer name) {
        return getUniformBlockIndex(program, StandardCharsets.UTF_8.decode(name.slice()).toString().trim());
    }
    public abstract void uniformBlockBinding(int program, int blockIndex, int binding);
    public abstract int getIndexedBufferBinding(int target, int index);
    public abstract void bufferData(int target, long size, int usage);
    public abstract void bufferData(int target, ByteBuffer data, int usage);
    public abstract void bufferData(int target, FloatBuffer data, int usage);
    public abstract void bufferData(int target, ShortBuffer data, int usage);
    public abstract void bufferData(int target, IntBuffer data, int usage);
    public abstract void bufferData(int target, DoubleBuffer data, int usage);
    public abstract void bufferData(int target, int[] data, int usage);
    public abstract void bufferData(int target, float[] data, int usage);
    public abstract void bufferSubData(int target, long offset, ByteBuffer data);
    public abstract void bufferSubData(int target, long offset, ShortBuffer data);
    public abstract void bufferSubData(int target, long offset, IntBuffer data);
    public abstract void bufferSubData(int target, long offset, FloatBuffer data);
    public abstract void bufferSubData(int target, long offset, DoubleBuffer data);
    public abstract ByteBuffer mapBuffer(int target, int access);
    public abstract ByteBuffer mapBuffer(int target, int access, long length, ByteBuffer old_buffer);
    public abstract boolean unmapBuffer(int target);
    public abstract void bufferStorage(int target, ByteBuffer data, int flags);
    public abstract void bufferStorage(int target, long size, int flags);
    public abstract void getBufferSubData(int target, long offset, ByteBuffer data);
    public abstract void getBufferSubData(int target, long offset, ShortBuffer data);
    public abstract void getBufferSubData(int target, long offset, IntBuffer data);
    public abstract void getBufferSubData(int target, long offset, FloatBuffer data);
    public abstract void getBufferSubData(int target, long offset, DoubleBuffer data);
    public abstract int getBufferParameteri(int target, int pname);
    public abstract boolean isBuffer(int buffer);
    public abstract boolean isTexture(int texture);
    public abstract boolean isFramebuffer(int framebuffer);
    public abstract boolean isRenderbuffer(int renderbuffer);
    public abstract boolean isSampler(int sampler);
    public abstract boolean isQuery(int query);
    public abstract ByteBuffer mapBufferRange(int target, long offset, long length, int access);
    public abstract void flushMappedBufferRange(int target, long offset, long length);

    /**
     * Maps a buffer range and returns its base address, or {@code 0L} when the driver failed the mapping.
     * Callers copying through raw addresses must treat {@code 0L} as "mapping unavailable" — feeding a null
     * mapping into {@code memAddress0} crashes the JVM with an access violation instead of raising an exception.
     */
    public long mapBufferRangeAddress(int target, long offset, long length, int access) {
        final ByteBuffer buf = mapBufferRange(target, offset, length, access);
        return buf == null ? 0L : MemoryUtilities.memAddress0(buf);
    }

    public abstract int genVertexArrays();

    /**
     * Opaque handle of the GL context current on the calling thread, used to detect display
     * context migrations (issue #150). Constant 0 on backends without a queryable handle
     * (the test stub), which never report a migration.
     */
    public abstract long getContextHandle();
    public abstract void deleteVertexArrays(int array);
    public abstract void bindVertexArray(int array);
    public boolean isVertexArray(int array) {
        throw new UnsupportedOperationException("isVertexArray is only supported by the LWJGL3 backend");
    }
    public abstract void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer);
    public void vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, ByteBuffer pointer) {
        vertexAttribPointer(index, size, type, normalized, stride, MemoryUtilities.memAddress0(pointer));
    }
    public void vertexAttribIPointer(int index, int size, int type, int stride, ByteBuffer pointer) {
        vertexAttribIPointer(index, size, type, stride, MemoryUtilities.memAddress0(pointer));
    }
    public abstract void vertexAttribIPointer(int index, int size, int type, int stride, long pointer);
    public abstract void enableVertexAttribArray(int index);
    public abstract void disableVertexAttribArray(int index);
    public abstract void getVertexAttribi(int index, int pname, IntBuffer params);
    public abstract void vertexAttribDivisor(int index, int divisor);
    public abstract void bindVertexBuffer(int bindingindex, int buffer, long offset, int stride);
    public abstract void vertexAttribFormat(int attribindex, int size, int type, boolean normalized, int relativeoffset);
    public abstract void vertexAttribIFormat(int attribindex, int size, int type, int relativeoffset);
    public abstract void vertexAttribBinding(int attribindex, int bindingindex);

    public abstract int createTextures(int target);
    public abstract void bindTextureUnit(int unit, int texture);
    public abstract void textureParameteri(int texture, int target, int pname, int param);
    public abstract void textureParameterf(int texture, int target, int pname, float param);
    public abstract void textureParameteriv(int texture, int target, int pname, IntBuffer params);
    public abstract void texStorage1D(int target, int levels, int internalFormat, int width);
    public abstract void texStorage2D(int target, int levels, int internalFormat, int width, int height);
    public abstract void texStorage3D(int target, int levels, int internalFormat, int width, int height, int depth);
    public abstract void textureStorage1D(int texture, int levels, int internalFormat, int width);
    public abstract void textureStorage2D(int texture, int levels, int internalFormat, int width, int height);
    public abstract void textureStorage3D(int texture, int levels, int internalFormat, int width, int height, int depth);
    public abstract void generateTextureMipmap(int texture);
    public abstract void textureImage2DEXT(int texture, int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels);
    public abstract void textureImage2DEXT(int texture, int target, int level, int internalformat, int width, int height, int border, int format, int type, IntBuffer pixels);
    public abstract void textureSubImage2D(int texture, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels);
    public abstract void textureSubImage2D(int texture, int level, int xoffset, int yoffset, int width, int height, int format, int type, IntBuffer pixels);
    public abstract int createFramebuffers();
    public abstract void namedFramebufferTexture(int framebuffer, int attachment, int texture, int level);
    public void namedFramebufferTextureLayer(int framebuffer, int attachment, int texture, int level, int layer) {
        throw new UnsupportedOperationException(getName() + ": named layered framebuffer attachments unsupported");
    }
    public void namedFramebufferRenderbuffer(int framebuffer, int attachment, int renderbuffertarget, int renderbuffer) {
        throw new UnsupportedOperationException(getName() + ": named framebuffer renderbuffer attachments unsupported");
    }
    public abstract void namedFramebufferReadBuffer(int framebuffer, int mode);
    public abstract void namedFramebufferDrawBuffers(int framebuffer, IntBuffer bufs);
    public abstract void blitNamedFramebuffer(int readFramebuffer, int drawFramebuffer, int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter);
    public abstract int createBuffers();
    public abstract void namedBufferData(int buffer, ByteBuffer data, int usage);
    public abstract void namedBufferData(int buffer, FloatBuffer data, int usage);
    public abstract void namedBufferData(int buffer, long size, int usage);
    public abstract void namedBufferSubData(int buffer, long offset, ByteBuffer data);
    public abstract void copyTextureSubImage2D(int texture, int target, int level, int xoffset, int yoffset, int x, int y, int width, int height);
    public abstract int getTextureParameteri(int texture, int target, int pname);
    public abstract float getTextureParameterf(int texture, int target, int pname);
    public abstract int getTextureLevelParameteri(int texture, int level, int pname);

    public abstract void textureSubImage3D(int texture, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth, int format, int type, ByteBuffer pixels);
    public abstract void compressedTextureSubImage3D(int texture, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth, int format, ByteBuffer pixels);
    public abstract void copyTextureSubImage3D(int texture, int level, int xoffset, int yoffset, int zoffset, int x, int y, int width, int height);
    /** {@code data} holds a single clear pixel in {@code format}/{@code type}, or is null for zero. */
    public abstract void clearTexSubImage(int texture, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth, int format, int type, ByteBuffer data);
    public abstract void textureParameterfv(int texture, int target, int pname, FloatBuffer params);
    public abstract void texParameterIiv(int target, int pname, IntBuffer params);
    public abstract void texParameterIuiv(int target, int pname, IntBuffer params);
    public abstract void textureParameterIiv(int texture, int target, int pname, IntBuffer params);
    public abstract void textureParameterIuiv(int texture, int target, int pname, IntBuffer params);
    public abstract void getTextureImage(int texture, int level, int format, int type, ByteBuffer pixels);
    public abstract void getCompressedTextureImage(int texture, int level, ByteBuffer pixels);

    public abstract int getInteger(int pname);
    public abstract void getInteger(int pname, IntBuffer params);
    public abstract int getIntegerIndexed(int pname, int index);
    /** Queries indexed byte offsets and lengths without truncating them to 32 bits. */
    public long getInteger64Indexed(int pname, int index) { return getIntegerIndexed(pname, index); }
    public abstract float getFloat(int pname);
    public abstract void getFloat(int pname, FloatBuffer params);
    public double getDouble(int pname) { return getFloat(pname); }
    public void getDouble(int pname, DoubleBuffer params) { if (params.remaining() > 0) params.put(params.position(), getDouble(pname)); }
    public abstract boolean getBoolean(int pname);
    public abstract void getBoolean(int pname, ByteBuffer params);
    public abstract String getString(int pname);
    public abstract String getStringi(int name, int index);
    public abstract int getError();

    public abstract long fenceSync(int condition, int flags);
    public abstract int clientWaitSync(long sync, int flags, long timeout);
    public abstract void deleteSync(long sync);
    public void waitSync(long sync, int flags, long timeout) { throw new UnsupportedOperationException("sync objects unsupported"); }
    public int getSynci(long sync, int pname, IntBuffer length) { throw new UnsupportedOperationException("sync objects unsupported"); }

    public void genQueries(IntBuffer ids) { throw new UnsupportedOperationException("occlusion queries unsupported"); }
    public int genQueries() { throw new UnsupportedOperationException("occlusion queries unsupported"); }
    public void deleteQueries(int id) { throw new UnsupportedOperationException("occlusion queries unsupported"); }
    public void beginQuery(int target, int id) { throw new UnsupportedOperationException("occlusion queries unsupported"); }
    public void endQuery(int target) { throw new UnsupportedOperationException("occlusion queries unsupported"); }
    public void getQueryObjectui(int id, int pname, IntBuffer params) { throw new UnsupportedOperationException("occlusion queries unsupported"); }
    public int getQueryObjectui(int id, int pname) { throw new UnsupportedOperationException("occlusion queries unsupported"); }
    public int getQueryObjecti(int id, int pname) { throw new UnsupportedOperationException("occlusion queries unsupported"); }
    public void getQueryObjecti(int id, int pname, IntBuffer params) { throw new UnsupportedOperationException("occlusion queries unsupported"); }
    public void queryCounter(int id, int target) { throw new UnsupportedOperationException("timer queries unsupported"); }
    public long getQueryObjectui64(int id, int pname) { throw new UnsupportedOperationException("timer queries unsupported"); }

    public abstract void clearBufferSubData(int target, int internalFormat, long offset, long size, int format, int type, ByteBuffer data);
    public abstract void clearBufferData(int target, int internalformat, int format, int type, ByteBuffer data);
    public abstract void clearTexImage(int texture, int level, int format, int type);

    public abstract void bindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access, int format);
    public abstract void memoryBarrier(int barriers);

    public abstract void copyImageSubData(int srcName, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ,
                                           int dstName, int dstTarget, int dstLevel, int dstX, int dstY, int dstZ,
                                           int srcWidth, int srcHeight, int srcDepth);

    // Debug output
    public boolean supportsDebugOutput() { return false; }

    /**
     * Installs a GL-style debug message callback.
     *
     * @return 1 = installed on a debug context, 2 = installed without the debug flag, 3 = the
     * backend has no GL debug channel (not an error), anything else = installation failed
     */
    public int setupDebugOutput(DebugMessageHandler handler) { return 0; }
    public int disableDebugOutput() { return 0; }

    // Object labeling
    public void objectLabel(int identifier, int name, CharSequence label) {}
    public String getObjectLabel(int identifier, int name) { return ""; }

    // Debug groups
    public void pushDebugGroup(int source, int id, CharSequence message) {}
    public void popDebugGroup() {}

    // Debug message insertion
    public void debugMessageInsert(int source, int type, int id, int severity, CharSequence message) {}

    // Debug message filtering
    public void debugMessageControl(int source, int type, int severity, IntBuffer ids, boolean enabled) {}

    public void debugMessageCallback(GLDebugMessageListener listener, long userParam) {}

    // Debug log retrieval; returns the number of messages written
    public int getDebugMessageLog(int count, IntBuffer sources, IntBuffer types, IntBuffer ids, IntBuffer severities, IntBuffer lengths, ByteBuffer messageLog) { return 0; }

}
