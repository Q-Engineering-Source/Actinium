package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;
import com.gtnewhorizon.gtnhlib.bytebuf.Pointer;
import com.gtnewhorizon.gtnhlib.client.renderer.stacks.IStateStack;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement.Usage;
import com.gtnewhorizons.angelica.glsm.DisplayListManager.RecordMode;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.GLDebugMessageListener;
import com.gtnewhorizons.angelica.glsm.backend.RenderBackend;
import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;
import com.gtnewhorizons.angelica.glsm.debug.GpuCheckpointTracker;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import com.gtnewhorizons.angelica.glsm.hooks.DeferredAlphaHandler;
import com.gtnewhorizons.angelica.glsm.hooks.DeferredBlendHandler;
import com.gtnewhorizons.angelica.glsm.hooks.DeferredDepthColorHandler;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMConfig;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMInitConfig;
import com.gtnewhorizons.angelica.glsm.hooks.GpuCommandRecorder;
import com.gtnewhorizons.angelica.glsm.hooks.GpuCommandPhase;
import com.gtnewhorizons.angelica.glsm.hooks.GpuCommandType;
import com.gtnewhorizons.angelica.glsm.recording.CompiledDisplayList;
import com.gtnewhorizons.angelica.glsm.recording.ImmediateModeRecorder;
import com.gtnewhorizons.angelica.glsm.stacks.AlphaStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.BlendStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.BooleanStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.Color4Stack;
import com.gtnewhorizons.angelica.glsm.stacks.ColorMaskStack;
import com.gtnewhorizons.angelica.glsm.stacks.CowDispatch;
import com.gtnewhorizons.angelica.glsm.stacks.CowStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.DepthStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.FogStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.IntegerStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.LightModelStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.LightStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.LineStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.MaterialStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.MatrixModeStack;
import com.gtnewhorizons.angelica.glsm.stacks.PointStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.PolygonStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.RetainedState;
import com.gtnewhorizons.angelica.glsm.stacks.ScissorStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.StackIdAllocator;
import com.gtnewhorizons.angelica.glsm.stacks.StencilStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.TextureUnitBooleanStateStack;
import com.gtnewhorizons.angelica.glsm.stacks.Vec3fStack;
import com.gtnewhorizons.angelica.glsm.stacks.Vec4fStack;
import com.gtnewhorizons.angelica.glsm.stacks.ViewPortStateStack;
import com.gtnewhorizons.angelica.glsm.states.AlphaState;
import com.gtnewhorizons.angelica.glsm.states.BlendState;
import com.gtnewhorizons.angelica.glsm.states.ClipPlaneState;
import com.gtnewhorizons.angelica.glsm.states.Color4;
import com.gtnewhorizons.angelica.glsm.states.ImageUnitArray;
import com.gtnewhorizons.angelica.glsm.states.ImageUnitBinding;
import com.gtnewhorizons.angelica.glsm.states.PixelStoreState;
import com.gtnewhorizons.angelica.glsm.states.TextureBinding;
import com.gtnewhorizons.angelica.glsm.states.TextureUnitArray;
import com.gtnewhorizons.angelica.glsm.ffp.VAOManager;
import com.gtnewhorizons.angelica.glsm.ffp.VertexKey;
import com.gtnewhorizons.angelica.glsm.streaming.StreamingVaos;
import com.gtnewhorizons.angelica.glsm.texture.TextureInfo;
import com.gtnewhorizons.angelica.glsm.texture.TextureInfoCache;
import net.minecraft.client.renderer.GlStateManager;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Matrix4fStack;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.LWJGLException;

import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.Drawable;
import org.lwjgl.opengl.DrawableGL;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.opengl.OpenGLException;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GLContext;
import org.lwjgl.opengl.KHRDebugCallback;
import org.lwjgl.system.Callback;
import org.lwjgl.util.glu.GLU;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.io.PrintStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntSupplier;

import static com.gtnewhorizons.angelica.glsm.Vendor.AMD;
import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;
import static com.gtnewhorizons.angelica.glsm.Vendor.INTEL;
import static com.gtnewhorizons.angelica.glsm.Vendor.MESA;
import static com.gtnewhorizons.angelica.glsm.Vendor.NVIDIA;

/**
 * OpenGL State Manager - Provides cached state tracking and management for Backend Renderer Actions
 *   IMPORTANT NOTE: Do NOT call open gl directly, route everything through {@link BackendManager#RENDER_BACKEND}
 *
 * <p><b>IMPORTANT INITIALIZATION ORDER:</b></p>
 * <ul>
 *   <li>This class performs GL queries in static initializers (MAX_CLIP_PLANES, MAX_TEXTURE_UNITS, etc.)</li>
 *   <li>The class MUST NOT be loaded until after the GL context has been created and made current</li>
 *   <li>Violating this requirement will cause crashes with "No current context" or return invalid values</li>
 *   <li>Call {@link #initialize(GLSMInitConfig)} after GL context creation to initialize runtime state</li>
 * </ul>
 */
@SuppressWarnings("unused") // Used in ASM
public class GLStateManager {

    public static final Logger LOGGER = LogManager.getLogger("GLSM");
    private static final Set<String> WARN_ONCE = ConcurrentHashMap.newKeySet();

    public static void warnOnce(String key, String fmt, Object... args) {
        if (WARN_ONCE.add(key)) LOGGER.warn(fmt, args);
    }
    static final boolean DEBUG_DRAW_LOGS = Boolean.getBoolean("actinium.glsm.verboseDrawLogs");

    // Thread Checking - must be early in static init order so isMainThread() works for state initialization
    @Getter private static final Thread MainThread = Thread.currentThread();

    public static boolean isMainThread() {
        return Thread.currentThread() == MainThread;
    }

    @Getter static GLSMInitConfig initConfig;

    public static ContextCapabilities capabilities;

    private static final CopyOnWriteArrayList<GLContextState> registeredContexts = new CopyOnWriteArrayList<>();
    private static volatile GLContextState primaryContext;
    private static GLContextState mainCtx;
    private static final ThreadLocal<GLContextState> tlWorkerContext = new ThreadLocal<>();
    private static final ThreadLocal<Long> tlBoundContextHandle = new ThreadLocal<>();
    private static final IdentityHashMap<Object, GLContextState> drawableContexts = new IdentityHashMap<>();
    private static volatile boolean workerContextsActive;
    private static int workerContextCount;

    public static GLContextState ctx() {
        if (Thread.currentThread() == MainThread) {
            final GLContextState current = mainCtx;
            return current != null ? current : (mainCtx = primaryContext());
        }
        if (!workerContextsActive) return primaryContext();
        final GLContextState current = tlWorkerContext.get();
        return current != null ? current : primaryContext();
    }

    private static GLContextState primaryContext() {
        GLContextState primary = primaryContext;
        if (primary != null) return primary;
        synchronized (GLStateManager.class) {
            primary = primaryContext;
            if (primary == null) {
                StackIdAllocator.beginContext();
                try {
                    primary = new GLContextState();
                    primaryContext = primary;
                    registeredContexts.add(primary);
                    primary.init();
                } finally {
                    StackIdAllocator.endContext();
                }
            }
        }
        return primary;
    }

    private static GLContextState newContext() {
        synchronized (GLStateManager.class) {
            StackIdAllocator.beginContext();
            try {
                final GLContextState created = new GLContextState();
                registeredContexts.add(created);
                created.init();
                return created;
            } finally {
                StackIdAllocator.endContext();
            }
        }
    }

    private static boolean bindThreadContext(GLContextState target) {
        final GLContextState previous = tlWorkerContext.get();
        if (Thread.currentThread() == MainThread) mainCtx = target;
        if (previous == target) return false;
        synchronized (GLStateManager.class) {
            if (previous == null) workerContextCount++;
            if (target == null) workerContextCount--;
            workerContextsActive = workerContextCount != 0;
        }
        if (target == null) tlWorkerContext.remove();
        else tlWorkerContext.set(target);
        return true;
    }

    static GLContextState enterWorkerContext() {
        if (tlWorkerContext.get() != null) {
            throw new IllegalStateException("Thread " + Thread.currentThread() + " already has a GL context");
        }
        final GLContextState created = newContext();
        if (RENDER_BACKEND.hasContext()) initializeDrawableResources(created);
        bindThreadContext(created);
        return created;
    }

    static void exitWorkerContext() {
        final GLContextState current = tlWorkerContext.get();
        if (current != null) {
            registeredContexts.remove(current);
            bindThreadContext(null);
        }
    }

    private static GLContextState contextFor(Object drawable) {
        synchronized (GLStateManager.class) {
            GLContextState state = drawableContexts.get(drawable);
            if (state == null) {
                state = newContext();
                drawableContexts.put(drawable, state);
            }
            return state;
        }
    }

    public static boolean BYPASS_CACHE = Boolean.parseBoolean(System.getProperty("angelica.disableGlCache", "false"));
    // Software stack depths for FFP emulation
    public static final int MAX_ATTRIB_STACK_DEPTH = 32;
    public static final int RETAINED_SLOT = MAX_ATTRIB_STACK_DEPTH;
    public static final int STATE_SLOTS = MAX_ATTRIB_STACK_DEPTH + 1;
    public static final int MAX_MODELVIEW_STACK_DEPTH = 32 + 2;
    public static final int MAX_PROJECTION_STACK_DEPTH = 4;
    public static final int MAX_TEXTURE_STACK_DEPTH = 4;
    public static final int MAX_COLOR_STACK_DEPTH = 4;
    public static final int MAX_CLIP_PLANES = 8;
    public static final int MAX_TEXTURE_UNITS = RENDER_BACKEND.getInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS);
    /** Fixed-function texture units the FFP shader consumes; larger unit counts fall back to the core profile. */
    public static final int MAX_LEGACY_TEXTURE_UNITS = Math.min(VertexKey.MAX_UNITS, MAX_TEXTURE_UNITS);
    /** Client-side texture coordinate units: every accepted unit owns a conventional texture coordinate slot. */
    public static final int MAX_TEXTURE_COORDS = Math.min(VAOManager.TEX_COORD_UNITS, MAX_TEXTURE_UNITS);

    public static final GLFeatureSet HAS_MULTIPLE_SET = new GLFeatureSet();

    /** Widest GL state query GLSM answers into a caller-provided array (a 4x4 matrix). */
    static final int ARRAY_QUERY_SCRATCH_SIZE = 16;

    /**
     * Direct-memory scratch for the array query overloads. The backend hands the buffer straight to the
     * driver, so these must not be heap buffers: a heap buffer has no stable native address, and the
     * driver then writes through a null pointer.
     */
    private static final IntBuffer ARRAY_QUERY_INT_SCRATCH = BufferUtils.createIntBuffer(ARRAY_QUERY_SCRATCH_SIZE);
    static final FloatBuffer ARRAY_QUERY_FLOAT_SCRATCH = BufferUtils.createFloatBuffer(ARRAY_QUERY_SCRATCH_SIZE);

    // Generation counters for FFP uniform dirty tracking. Bumped when the corresponding GLSM state changes.
    // Per-matrix-mode generation counters — avoids re-uploading all matrices when only one mode changed

    // Deferred vertex attribute upload flags — set when state changes, flushed before draw

    /** Flush deferred vertex attribute uploads. Called before draw to ensure default attrib values are current. */
            public static void flushDeferredVertexAttribs() { GLSMCurrentAttribs.flushDeferredVertexAttribs(); }

    public static Vec3fStack getNormalStack() {
        return ctx().normalStack;
    }

    public static Vec4fStack getTexCoordStack() {
        return ctx().texCoordStack;
    }

    public static ShaderManager getFfp() {
        return ctx().ffp;
    }

    public static VAOManager getVaoManager() {
        return ctx().vaos;
    }

    public static VAOManager.Attrib getVertexAttrib(int index) {
        return ctx().vaos.attrib(index);
    }

    public static StreamingVaos getStreamingVaos() {
        return ctx().streamingVaos;
    }

    public static float getLastBrightnessX() {
        return ctx().lastBrightnessX;
    }

    public static float getLastBrightnessY() {
        return ctx().lastBrightnessY;
    }

    // Highest texture unit index that has ever had a non-zero binding; limits onDeleteTexture scan range
    // Lock guard for texture bind callback (prevents recursion from RenderSystem.bindTextureToUnit)
    static boolean lockBindCallback;

    // Deferred texture deletion: names kept valid until glGenTextures recycles them.
    // Emulates Mesa/compat-profile behavior where delete-then-bind doesn't error.
    static final IntOpenHashSet deferredDeleteTextures = new IntOpenHashSet();
    private static long loggedUncachedTextureTargets = 0;

    @Getter private static Vendor VENDOR;

    // This setting varies depending on driver, so it gets queried at runtime
    public static int DEFAULT_DRAW_BUFFER = 0x0405; // GL_BACK

    private static Thread CurrentThread = MainThread;
    @Setter @Getter private static boolean runningSplash = true;

    private static volatile boolean splashComplete = false;
    // Set by markSplashComplete(): the next frame on the owning context must re-seed the
    // driver with the full cached state (see RenderBackend.onFrameBegin / replayStateToBackend).
    private static volatile boolean stateSeedPending = false;

    public static boolean takeStateSeedPending() {
        if (!stateSeedPending) return false;
        stateSeedPending = false;
        return true;
    }
    @Getter @Setter private static Thread drawableGLHolder = MainThread;
    @Setter private static Drawable drawableGL = null;
    private static Object displayContextToken;
    private static long displayContextHandle;
    private static long boundDisplayContextHandle;

    public static boolean isCachingEnabled() {
        if (splashComplete) return true;
        return Thread.currentThread() == drawableGLHolder || tlWorkerContext.get() != null;
    }

    /**
     * Check if splash screen is complete. After splash, there's only one GL context and no locking is needed.
     */
    public static boolean isSplashComplete() {
        return splashComplete;
    }

    /**
     * Get the active texture unit for server-side state operations. If caching is enabled, returns cached value. If caching is disabled (SharedDrawable),
     * queries actual GL state.
     */
            public static int getActiveTextureUnitForServerState() { return GLSMTextures.getActiveTextureUnitForServerState(); }

    /**
     * Get the texture bound to the current texture unit for server-side state operations.
     * If caching is enabled, returns cached value.
     * If caching is disabled (SharedDrawable), queries actual GL state.
     */
            public static int getBoundTextureForServerState() { return GLSMTextures.getBoundTextureForServerState(); }

    /**
     * Get the texture bound to a specific texture unit for server-side state operations.
     * If caching is enabled, returns cached value.
     * If caching is disabled (SharedDrawable), returns -1 to force operations to proceed
     */
            public static int getBoundTextureForServerState(int unit) { return GLSMTextures.getBoundTextureForServerState(unit); }

    static final String TEXTURE = "texture";
    static final String TEXTURE_RENAMED = "gtexture";

            public static void registerModifiedState(int id) { GLSMAttribStack.registerModifiedState(id); }

    static <T extends CowStateStack<?>> T mod(T stack) {
        stack.beforeModify();
        return stack;
    }

    // Line width range queried at init from GL_ALIASED_LINE_WIDTH_RANGE
    static float lineWidthMin = 1.0f;
    static float lineWidthMax = 1.0f;
    /** True when the driver cannot render wide lines natively (Mesa forward-compat). GS emulation handles widths > 1.0. */
    public static boolean wideLineEmulationEnabled = false;
    /**
     * Set per draw call: true when the current draw is a line primitive that needs GS expansion. Read by
     * {@link com.gtnewhorizons.angelica.glsm.ffp.VertexKey}.
     */
    public static boolean wideLineEmulationActive = false;
    /**
     * Set per draw call: true when the current draw is a line primitive with GL_LINE_STIPPLE
     * enabled. Read by {@link com.gtnewhorizons.angelica.glsm.ffp.VertexKey} and
     * {@link com.gtnewhorizons.angelica.glsm.ffp.FragmentKey}; also switches the provoking
     * vertex convention so flat varyings come from each segment's first vertex.
     */
    public static boolean lineStippleActive = false;

    static final MethodHandle MAT4_STACK_CURR_DEPTH;

    static {
        try {
            final Field curr = Matrix4fStack.class.getDeclaredField("curr");
            curr.setAccessible(true);
            MAT4_STACK_CURR_DEPTH = MethodHandles.lookup().unreflectGetter(curr);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
    public static int getDefaultVAO() {
        return ctx().defaultVAO;
    }

    public static void reset() {
        final GLContextState glCtx = ctx();
        runningSplash = true;
        glCtx.attribDepth = 0;
        glCtx.retainedOwner = null;
        for (int i = 0; i < glCtx.attribSets.length; i++) {
            glCtx.attribSets[i] = null;
        }
        for (int i = 0; i < glCtx.modifiedCount.length; i++) {
            glCtx.modifiedCount[i] = 0;
        }

        for (IStateStack<?> stack : glCtx.allStacks) {
            ((CowStateStack<?>) stack).drain();
        }

        glCtx.modelViewMatrix.clear();
        glCtx.projectionMatrix.clear();
        glCtx.vaos.reset(glCtx.boundVAO);
    }

    /**
     * Check if a display list exists (has been compiled and stored). Delegates to DisplayListManager.
     */
            public static boolean displayListExists(int list) { return GLSMDraws.displayListExists(list); }

    public static class GLFeatureSet extends IntOpenHashSet {

        private static final long serialVersionUID = 8558779940775721010L;

        public GLFeatureSet addFeature(int feature) {
            super.add(feature);
            return this;
        }

    }

    public static void initialize(GLSMInitConfig config) {
        applyInitConfig(config);
        preInit(config.getDisplayWidth(), config.getDisplayHeight());
        init(config.getPostInitCallback());
    }

    /**
     * Applies the host integration config without touching the GL context. {@link #initialize} calls
     * this first; the SDL integration harness uses it directly because that environment cannot resolve
     * the LWJGL2 capability shim that {@code preInit} needs.
     */
    static void applyInitConfig(GLSMInitConfig config) {
        initConfig = config;
    }

    static void recordGpuCommand(GpuCommandType type, int operand0, int operand1) {
        recordGpuCommand(type, GpuCommandPhase.ISSUED, operand0, operand1);
    }

    static void recordGpuCommand(GpuCommandType type, GpuCommandPhase phase, int operand0, int operand1) {
        final GLSMInitConfig config = initConfig;
        if (config == null) {
            return;
        }
        final GpuCommandRecorder recorder = config.getGpuCommandRecorder();
        if (recorder != null) {
            final boolean cacheTrusted = isGpuDiagnosticCacheTrusted();
            recorder.record(
                type,
                phase,
                GpuCommandDiagnostics.trustedValue(cacheTrusted, ctx().activeProgram),
                GpuCommandDiagnostics.trustedValue(cacheTrusted, ctx().drawFramebuffer),
                operand0,
                operand1
            );
        }
    }

    /**
     * Polls prior GPU fences without waiting and inserts a completion checkpoint for a submitted command.
     *
     * @param type command or semantic pass boundary associated with the checkpoint
     */
    public static void gpuCheckpoint(GpuCommandType type) {
        final GLSMInitConfig config = initConfig;
        if (config == null) {
            return;
        }
        final GpuCommandRecorder recorder = config.getGpuCommandRecorder();
        if (recorder != null) {
            GpuCheckpointTracker.checkpoint(recorder, type.code());
        }
    }

    static int packDimensions(int width, int height) {
        return (width & 0xFFFF) << 16 | height & 0xFFFF;
    }

    static int getBoundTextureForGpuDiagnostics() {
        if (!isGpuDiagnosticCacheTrusted()) {
            return GpuCommandDiagnostics.UNKNOWN;
        }
        return getBoundTexture();
    }

    static boolean isGpuDiagnosticCacheTrusted() {
        return GpuCommandDiagnostics.isCacheTrusted(
            isCachingEnabled(),
            BYPASS_CACHE,
            GLContext.getCapabilities(),
            capabilities
        );
    }

    static void preInit(int displayWidth, int displayHeight) {
        capabilities = GLContext.getCapabilities();
        HAS_MULTIPLE_SET
            .addFeature(GL11.GL_ACCUM_CLEAR_VALUE)
            .addFeature(GL14.GL_BLEND_COLOR)
            .addFeature(GL11.GL_COLOR_CLEAR_VALUE)
            .addFeature(GL11.GL_COLOR_WRITEMASK)
            .addFeature(GL11.GL_CURRENT_COLOR)
            .addFeature(GL11.GL_CURRENT_NORMAL)
            .addFeature(GL11.GL_CURRENT_RASTER_COLOR)
            .addFeature(GL11.GL_CURRENT_RASTER_POSITION)
            .addFeature(GL11.GL_CURRENT_RASTER_TEXTURE_COORDS)
            .addFeature(GL11.GL_CURRENT_TEXTURE_COORDS)
            .addFeature(GL11.GL_DEPTH_RANGE)
            .addFeature(GL14.GL_POINT_DISTANCE_ATTENUATION)
            .addFeature(GL11.GL_FOG_COLOR)
            .addFeature(GL11.GL_LIGHT_MODEL_AMBIENT)
            .addFeature(GL11.GL_LINE_WIDTH_RANGE)
            .addFeature(GL11.GL_MAP1_GRID_DOMAIN)
            .addFeature(GL11.GL_MAP2_GRID_DOMAIN)
            .addFeature(GL11.GL_MAP2_GRID_SEGMENTS)
            .addFeature(GL11.GL_MAX_VIEWPORT_DIMS)
            .addFeature(GL11.GL_MODELVIEW_MATRIX)
            .addFeature(GL11.GL_POINT_SIZE_RANGE)
            .addFeature(GL11.GL_POLYGON_MODE)
            .addFeature(GL11.GL_PROJECTION_MATRIX)
            .addFeature(GL11.GL_SCISSOR_BOX)
            .addFeature(GL11.GL_TEXTURE_ENV_COLOR)
            .addFeature(GL11.GL_TEXTURE_MATRIX)
            .addFeature(GL11.GL_VIEWPORT);

        if (displayWidth > 0 && displayHeight > 0) {
            // After Display.create(), viewport is (0, 0, width, height)
            mod(ctx().viewportState).setViewPort(0, 0, displayWidth, displayHeight);
            mod(ctx().scissorState).setScissor(0, 0, displayWidth, displayHeight);
        }
    }

    static void init(Runnable initCallback) {
        RenderSystem.initRenderer();

        DEFAULT_DRAW_BUFFER = RENDER_BACKEND.getInteger(GL11.GL_DRAW_BUFFER);

        final String glVendor = RENDER_BACKEND.getString(GL11.GL_VENDOR);
        VENDOR = Vendor.getVendor(glVendor.toLowerCase());

        // Keep the driver aligned with the cached all-ones defaults. The driver clamps these masks to the
        // stencil width of whichever framebuffer is current when a draw occurs.
        RENDER_BACKEND.stencilFunc(ctx().stencilState.getFuncFront(), ctx().stencilState.getRefFront(), ctx().stencilState.getValueMaskFront());
        RENDER_BACKEND.stencilMask(ctx().stencilState.getWriteMaskFront());

        final FloatBuffer lwRange = BufferUtils.createFloatBuffer(16);
        RENDER_BACKEND.getFloat(GL12.GL_ALIASED_LINE_WIDTH_RANGE, lwRange);
        lineWidthMin = lwRange.get(0);
        lineWidthMax = lwRange.get(1);
        final float queriedMax = lineWidthMax;
        // Probe whether the driver actually accepts the queried max (Mesa forward-compat rejects > 1.0)
        if (lineWidthMax > 1.0f) {
            while (RENDER_BACKEND.getError() != GL11.GL_NO_ERROR) {}
            RENDER_BACKEND.lineWidth(lineWidthMax);
            if (RENDER_BACKEND.getError() != GL11.GL_NO_ERROR) {
                // Binary search for the actual accepted max between 1.0 and queriedMax
                float lo = 1.0f, hi = queriedMax;
                while (hi - lo > 0.01f) {
                    final float mid = (lo + hi) * 0.5f;
                    while (RENDER_BACKEND.getError() != GL11.GL_NO_ERROR) {}
                    RENDER_BACKEND.lineWidth(mid);
                    if (RENDER_BACKEND.getError() == GL11.GL_NO_ERROR) {
                        lo = mid;
                    } else {
                        hi = mid;
                    }
                }
                lineWidthMax = lo;
            }
            RENDER_BACKEND.lineWidth(1.0f);
            while (RENDER_BACKEND.getError() != GL11.GL_NO_ERROR) {}
        }
        wideLineEmulationEnabled = lineWidthMax <= 1.0f;
        if (wideLineEmulationEnabled) {
            LOGGER.info("GL line width: aliased range [{}, {}], probe at {} rejected, native [{}, {}], GS emulation active for [{}, {}]", lineWidthMin, queriedMax, queriedMax, lineWidthMin, lineWidthMax, lineWidthMax, queriedMax);
        } else {
            LOGGER.info("GL line width: aliased range [{}, {}], probe at {} accepted, native [{}, {}]", lineWidthMin, queriedMax, queriedMax, lineWidthMin, lineWidthMax);
        }

        // Sync default vertex attribs with GLSM cache initial values.
        RENDER_BACKEND.vertexAttrib4f(1, 1.0f, 1.0f, 1.0f, 1.0f);    // COLOR = white
        RENDER_BACKEND.vertexAttrib3f(4, 0.0f, 0.0f, 1.0f);          // NORMAL = +Z

        if (BYPASS_CACHE) {
            LOGGER.info("GLStateManager cache bypassed");
        }
        if (initConfig != null && (initConfig.isLwjglDebug() || CaptureGate.enabledAtStartup())) {
            if (initConfig.isLwjglDebug()) {
                LOGGER.info("LWJGL debug context requested; installing synchronous high-severity driver output");
                GLDebug.setupDebugMessageCallback();
            }

            GLDebug.initDebugState();

            GLDebug.debugMessage("Angelica Debug Annotator Initialized");
        }

        final GLContextState glCtx = ctx();
        glCtx.defaultVAO = RENDER_BACKEND.genVertexArrays();
        displayContextHandle = RENDER_BACKEND.getContextHandle();
        if (boundDisplayContextHandle == 0) boundDisplayContextHandle = displayContextHandle;
        RENDER_BACKEND.bindVertexArray(glCtx.defaultVAO);
        glCtx.boundVAO = glCtx.defaultVAO;
        glCtx.vaos.init(glCtx.defaultVAO);
        if (initCallback != null) {
            initCallback.run();
        }

        // Drain any pending GL errors from initialization. In core profile, some legacy queries may generate GL_INVALID_ENUM. The splash thread inherits the
        // DrawableGL context and its error state, so stale errors here would cause SplashProgress.checkGLError() to fail.
        int err;
        while ((err = RENDER_BACKEND.getError()) != 0) {
            LOGGER.debug("Drained GL error 0x{} during init", Integer.toHexString(err));
        }
    }

    public static void assertMainThread() {
        if (Thread.currentThread() != CurrentThread && !runningSplash) {
            LOGGER.info("Call from not the Current Thread! - " + Thread.currentThread().getName() + " Current thread: " + CurrentThread.getName());
        }
    }

    public static void requireContext(String call) {
        if (Thread.currentThread() == MainThread || Thread.currentThread() == drawableGLHolder
            || tlWorkerContext.get() != null) return;
        throw new IllegalStateException(call + " on thread " + Thread.currentThread().getName() + ", which has no GL context");
    }

    public static boolean shouldBypassCache() {
        // Bypass cache when not using the main DrawableGL context
        return BYPASS_CACHE || !isCachingEnabled();
    }

    /**
     * Returns true if we should use DSA for texture operations. DSA relies on cached texture bindings, which aren't tracked when caching is disabled. Also,
     * proxy textures are special query textures that shouldn't use DSA.
     */
    static boolean shouldUseDSA(int target) {
        if (!isCachingEnabled()) return false;
        // Proxy textures are for capability queries, not real textures
        return target != GL11.GL_PROXY_TEXTURE_1D && target != GL11.GL_PROXY_TEXTURE_2D && target != GL12.GL_PROXY_TEXTURE_3D;
    }

    public static void glEnable(int cap) { GLSMEnable.glEnable(cap); }

    public static void glDisable(int cap) { GLSMEnable.glDisable(cap); }

    public static boolean glIsEnabled(int cap) { return GLSMEnable.glIsEnabled(cap); }

    /**
     * Whether the pname is an enable capability tracked by {@link #glIsEnabled}. Compatibility
     * GL allows querying enable caps through glGetInteger/glGetFloat, but the core-profile
     * backend rejects them; legacy mods (e.g. OreLib OpenGlState, issue #41) query caps this
     * way, so the glGet* variants answer from tracked state instead of forwarding.
     */
    static boolean isEnableCap(int pname) {
        return switch (pname) {
            case GL11.GL_ALPHA_TEST, GL11.GL_AUTO_NORMAL, GL11.GL_BLEND, GL11.GL_COLOR_MATERIAL,
                    GL11.GL_COLOR_LOGIC_OP, GL14.GL_COLOR_SUM, GL11.GL_CULL_FACE, GL11.GL_DEPTH_TEST, GL11.GL_DITHER,
                    GL11.GL_FOG, GL11.GL_INDEX_LOGIC_OP, GL11.GL_LIGHTING, GL11.GL_LIGHT0,
                    GL11.GL_LIGHT1, GL11.GL_LIGHT2, GL11.GL_LIGHT3, GL11.GL_LIGHT4, GL11.GL_LIGHT5,
                    GL11.GL_LIGHT6, GL11.GL_LIGHT7, GL11.GL_LINE_SMOOTH, GL11.GL_LINE_STIPPLE,
                    GL11.GL_MAP1_COLOR_4, GL11.GL_MAP1_INDEX, GL11.GL_MAP1_NORMAL,
                    GL11.GL_MAP1_TEXTURE_COORD_1, GL11.GL_MAP1_TEXTURE_COORD_2,
                    GL11.GL_MAP1_TEXTURE_COORD_3, GL11.GL_MAP1_TEXTURE_COORD_4,
                    GL11.GL_MAP1_VERTEX_3, GL11.GL_MAP1_VERTEX_4, GL11.GL_MAP2_COLOR_4,
                    GL11.GL_MAP2_INDEX, GL11.GL_MAP2_NORMAL, GL11.GL_MAP2_TEXTURE_COORD_1,
                    GL11.GL_MAP2_TEXTURE_COORD_2, GL11.GL_MAP2_TEXTURE_COORD_3,
                    GL11.GL_MAP2_TEXTURE_COORD_4, GL11.GL_MAP2_VERTEX_3, GL11.GL_MAP2_VERTEX_4,
                    GL13.GL_MULTISAMPLE, GL11.GL_NORMALIZE, GL11.GL_POINT_SMOOTH,
                    GL20.GL_POINT_SPRITE, GL11.GL_POLYGON_OFFSET_POINT, GL11.GL_POLYGON_OFFSET_LINE,
                    GL11.GL_POLYGON_OFFSET_FILL, GL11.GL_POLYGON_SMOOTH, GL11.GL_POLYGON_STIPPLE,
                    GL12.GL_RESCALE_NORMAL, GL13.GL_SAMPLE_ALPHA_TO_COVERAGE,
                    GL13.GL_SAMPLE_ALPHA_TO_ONE, GL13.GL_SAMPLE_COVERAGE, GL11.GL_SCISSOR_TEST,
                    GL11.GL_STENCIL_TEST, GL11.GL_TEXTURE_1D, GL11.GL_TEXTURE_2D,
                    GL12.GL_TEXTURE_3D, GL13.GL_TEXTURE_CUBE_MAP, GL11.GL_TEXTURE_GEN_S, GL11.GL_TEXTURE_GEN_T,
                    GL11.GL_TEXTURE_GEN_R, GL11.GL_TEXTURE_GEN_Q, GL20.GL_VERTEX_PROGRAM_TWO_SIDE,
                    // Client array enables answer through VAOManager's conventional slot table, which is the
                    // only record of them the core profile keeps.
                    GL11.GL_VERTEX_ARRAY, GL11.GL_NORMAL_ARRAY, GL11.GL_COLOR_ARRAY, GL11.GL_INDEX_ARRAY,
                    GL11.GL_TEXTURE_COORD_ARRAY, GL11.GL_EDGE_FLAG_ARRAY, GL14.GL_SECONDARY_COLOR_ARRAY,
                    GL14.GL_FOG_COORDINATE_ARRAY -> true;
            default -> false;
        };
    }

    public static boolean glGetBoolean(int pname) { return GLSMQueries.glGetBoolean(pname); }

    public static void glGetBoolean(int pname, ByteBuffer params) { GLSMQueries.glGetBoolean(pname, params); }

            @SneakyThrows public static int getMatrixStackDepth(Matrix4fStack stack) { return GLSMMatrices.getMatrixStackDepth(stack); }

    public static int glGetInteger(int pname) { return GLSMQueries.glGetInteger(pname); }

    /**
     * Answers the depth or stencil attachment size of the current draw framebuffer. Both legacy
     * {@code GL_DEPTH_BITS} and {@code GL_STENCIL_BITS} are gone from the core profile, so the size comes
     * from the framebuffer attachment query: the default framebuffer answers through its
     * {@code GL_DEPTH} or {@code GL_STENCIL} attachment point, and a named framebuffer answers the depth
     * or stencil attachment, reporting 0 when that attachment is absent.
     */
    static int drawFramebufferAttachmentSize(boolean stencil) {
        final int sizePname = stencil ? GL30.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE : GL30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE;
        if (ctx().drawFramebuffer == 0) {
            return RENDER_BACKEND.getFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, stencil ? GL11.GL_STENCIL : GL11.GL_DEPTH, sizePname);
        }
        final int attachment = stencil ? GL30.GL_STENCIL_ATTACHMENT : GL30.GL_DEPTH_ATTACHMENT;
        final int objectType = RENDER_BACKEND.getFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (objectType == GL11.GL_NONE) {
            return 0;
        }
        return RENDER_BACKEND.getFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, attachment, sizePname);
    }

    public static void glGetInteger(int pname, IntBuffer params) { GLSMQueries.glGetInteger(pname, params); }

    public static void glGetInteger(int pname, int[] params) { GLSMQueries.glGetInteger(pname, params); }

    /** Writes the {@code width} tracked elements of a vector pname, capped by the caller array length. */
    static void copyTrackedVector(int pname, int width, int[] params) {
        final IntBuffer scratch = ARRAY_QUERY_INT_SCRATCH;
        scratch.clear();
        glGetInteger(pname, scratch);
        scratch.get(0, params, 0, Math.min(width, params.length));
    }

    /**
     * Answers a pname the driver owns. GL writes to native memory, so the answer goes through a direct
     * scratch that is cleared first: the elements past the pname's own width read as zero instead of
     * what an earlier query left behind.
     */
    static void queryDriverVector(int pname, int[] params) {
        final IntBuffer scratch = params.length <= ARRAY_QUERY_SCRATCH_SIZE
            ? ARRAY_QUERY_INT_SCRATCH
            : BufferUtils.createIntBuffer(params.length);
        scratch.clear();
        for (int i = 0; i < scratch.capacity(); i++) {
            scratch.put(i, 0);
        }
        RENDER_BACKEND.getInteger(pname, scratch);
        scratch.get(0, params, 0, params.length);
    }

    public static void glGetMaterial(int face, int pname, FloatBuffer params) { GLSMLighting.glGetMaterial(face, pname, params); }

    public static void glGetMaterial(int face, int pname, IntBuffer params) { GLSMLighting.glGetMaterial(face, pname, params); }

    public static void glGetLight(int light, int pname, FloatBuffer params) { GLSMLighting.glGetLight(light, pname, params); }

    public static void glGetLight(int light, int pname, IntBuffer params) { GLSMLighting.glGetLight(light, pname, params); }

    /**
     * Converts a cached light or material vector to the integer flavour of {@code glGetLight}/{@code glGetMaterial}.
     * Color components use the fixed-point scale the driver reports; the remaining values round to nearest.
     */
    static void putLightingInts(FloatBuffer values, int pname, boolean material, IntBuffer params) {
        final int count = switch (pname) {
            case GL11.GL_AMBIENT, GL11.GL_DIFFUSE, GL11.GL_SPECULAR -> 4;
            case GL11.GL_EMISSION -> material ? 4 : 0;
            case GL11.GL_COLOR_INDEXES -> material ? 3 : 0;
            case GL11.GL_SHININESS -> material ? 1 : 0;
            case GL11.GL_POSITION -> material ? 0 : 4;
            case GL11.GL_SPOT_DIRECTION -> material ? 0 : 3;
            case GL11.GL_SPOT_EXPONENT, GL11.GL_SPOT_CUTOFF, GL11.GL_CONSTANT_ATTENUATION, GL11.GL_LINEAR_ATTENUATION,
                 GL11.GL_QUADRATIC_ATTENUATION -> material ? 0 : 1;
            default -> 0;
        };
        final boolean color = count == 4 && pname != GL11.GL_POSITION;
        final int pos = params.position();
        for (int i = 0; i < count; i++) {
            final float value = values.get(i);
            final int converted;
            if (color) {
                converted = LegacyQueries.floatToInt(value);
            } else if (material) {
                converted = LegacyQueries.lround(value);
            } else {
                converted = (int) value;
            }
            params.put(pos + i, converted);
        }
    }

    public static void glGetFloat(int pname, FloatBuffer params) { GLSMQueries.glGetFloat(pname, params); }

    public static void glGetFloat(int pname, float[] params) { GLSMQueries.glGetFloat(pname, params); }

    public static double glGetDouble(int pname) { return GLSMQueries.glGetDouble(pname); }

    public static void glGetDouble(int pname, DoubleBuffer params) { GLSMQueries.glGetDouble(pname, params); }

    public static void glGetDouble(int pname, double[] params) { GLSMQueries.glGetDouble(pname, params); }

    static void widenFromFloat(int pname, DoubleBuffer params, int count) {
        final FloatBuffer scratch = ctx().queryScratch;
        scratch.clear();
        glGetFloat(pname, scratch);
        final int pos = params.position();
        for (int i = 0; i < count; i++) {
            params.put(pos + i, scratch.get(i));
        }
    }

            public static void glGetClipPlane(int plane, DoubleBuffer equation) { GLSMRasterizer.glGetClipPlane(plane, equation); }

    public static float glGetFloat(int pname) { return GLSMQueries.glGetFloat(pname); }

    /**
     * Largest point size the driver accepts, used while the tracked {@code GL_POINT_SIZE_MAX} is still NaN.
     * The result is cached outside {@link com.gtnewhorizons.angelica.glsm.states.PointState} so a query
     * does not make the value part of the pushable point state.
     */
    static float maxPointSize(GLContextState glCtx) {
        if (Float.isNaN(glCtx.maxPointSize)) {
            final FloatBuffer range = glCtx.queryScratch;
            range.clear();
            RENDER_BACKEND.getFloat(GL11.GL_POINT_SIZE_RANGE, range);
            final float smooth = range.get(1);
            RENDER_BACKEND.getFloat(GL12.GL_ALIASED_POINT_SIZE_RANGE, range);
            glCtx.maxPointSize = Math.max(smooth, range.get(1));
        }
        return glCtx.maxPointSize;
    }

    public static void glBlendColor(float red, float green, float blue, float alpha) { GLSMBlend.glBlendColor(red, green, blue, alpha); }

            public static void enableBlend() { GLSMBlend.enableBlend(); }

            public static void disableBlend() { GLSMBlend.disableBlend(); }

    static void postVanillaBlendChange() {
        if (GLSMHooks.hasActiveConsumers() && GLSMHooks.VANILLA_BLEND_CHANGE.hasListeners()) {
            GLSMHooks.VANILLA_BLEND_CHANGE.post(GLSMHooks.vanillaBlendChangeEvent);
        }
    }

    static boolean snapshotVanillaBlendFunc() {
        if (!GLSMHooks.hasActiveConsumers() || !GLSMHooks.VANILLA_BLEND_CHANGE.hasListeners()) {
            return false;
        }
        ctx().blendState.readEffective(ctx().vanillaBlendBefore);
        ctx().vanillaBlendEnabledBefore = ctx().blendMode.isEffectivelyEnabled();
        return true;
    }

    static void postVanillaBlendChangeIfMoved(boolean snapshotted) {
        if (!snapshotted) {
            return;
        }
        final BlendState after = ctx().blendState.readEffective(ctx().vanillaBlendAfter);
        if (ctx().blendMode.isEffectivelyEnabled() != ctx().vanillaBlendEnabledBefore
            || after.getSrcRgb() != ctx().vanillaBlendBefore.getSrcRgb()
            || after.getDstRgb() != ctx().vanillaBlendBefore.getDstRgb()
            || after.getSrcAlpha() != ctx().vanillaBlendBefore.getSrcAlpha()
            || after.getDstAlpha() != ctx().vanillaBlendBefore.getDstAlpha()) {
            postVanillaBlendChange();
        }
    }

    /** Returns the blend enable value requested by vanilla for the current draw. */
            public static boolean isEffectiveBlendEnabled() { return GLSMBlend.isEffectiveBlendEnabled(); }

    /** Copies the blend function requested by vanilla into {@code out}. */
            public static BlendState getEffectiveBlendState(BlendState out) { return GLSMBlend.getEffectiveBlendState(out); }

    /** Returns the depth-write mask requested by vanilla for the current draw. */
            public static boolean isEffectiveDepthMaskEnabled() { return GLSMDepthStencilAlpha.isEffectiveDepthMaskEnabled(); }

    /** Returns the alpha-test enable value requested by vanilla for the current draw. */
            public static boolean isEffectiveAlphaTestEnabled() { return GLSMDepthStencilAlpha.isEffectiveAlphaTestEnabled(); }

    /** Copies the alpha-test function requested by vanilla into {@code out}. */
            public static AlphaState getEffectiveAlphaState(AlphaState out) { return GLSMDepthStencilAlpha.getEffectiveAlphaState(out); }

    private static int foreignDrawDepth;

    /** Marks the beginning of a renderer that may issue raw GL state changes. */
    public static void beginForeignDraw() {
        foreignDrawDepth++;
    }

    /** Marks the end of a renderer that may issue raw GL state changes. */
    public static void endForeignDraw() {
        if (foreignDrawDepth <= 0) {
            throw new IllegalStateException("Foreign draw scope underflow");
        }
        if (--foreignDrawDepth == 0 && GLSMHooks.FOREIGN_DRAW_END.hasListeners()) {
            GLSMHooks.FOREIGN_DRAW_END.post(GLSMHooks.foreignDrawEndEvent);
        }
    }

    /** Returns whether a foreign renderer is currently changing GL state. */
    public static boolean isForeignDraw() {
        return foreignDrawDepth > 0;
    }

            public static void enableScissorTest() { GLSMRasterizer.enableScissorTest(); }

            public static void disableScissorTest() { GLSMRasterizer.disableScissorTest(); }

    public static void glBlendFunc(int srcFactor, int dstFactor) { GLSMBlend.glBlendFunc(srcFactor, dstFactor); }

            public static void glBlendEquation(int mode) { GLSMBlend.glBlendEquation(mode); }

    public static void glBlendEquationSeparate(int modeRGB, int modeAlpha) { GLSMBlend.glBlendEquationSeparate(modeRGB, modeAlpha); }

            public static void tryBlendFuncSeparate(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) { GLSMBlend.tryBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha); }

    public static void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) { GLSMBlend.glBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha); }

    /**
     * StellarCore and Gnetum render the HUD into a cached framebuffer and change the blend factors
     * directly on the driver. GLSM answers their blend calls from its cache, so their interceptors
     * never run and the cached blit would show the per-element alpha areas as fully transparent.
     * While the override is active the cache keeps the factors the caller asked for and only the
     * driver receives {@code (ONE, ONE_MINUS_SRC_ALPHA)} for the alpha factors.
     */
            public static void setHudCacheOverride(boolean enabled) { GLSMBlend.setHudCacheOverride(enabled); }

    static void issueBlendFunc(BlendStateStack blend) {
        issueBlendFunc(blend.getSrcRgb(), blend.getDstRgb(), blend.getSrcAlpha(), blend.getDstAlpha());
    }

    static void issueBlendFunc(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        if (GLSMConfig.hudCacheOverride) {
            RENDER_BACKEND.blendFuncSeparate(srcRgb, dstRgb, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        } else {
            RENDER_BACKEND.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        }
    }

    static void postBlendFuncChange(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        if (GLSMHooks.BLEND_FUNC_CHANGE.hasListeners()) {
            GLSMHooks.blendFuncChangeEvent.srcRgb = srcRgb;
            GLSMHooks.blendFuncChangeEvent.dstRgb = dstRgb;
            GLSMHooks.blendFuncChangeEvent.srcAlpha = srcAlpha;
            GLSMHooks.blendFuncChangeEvent.dstAlpha = dstAlpha;
            GLSMHooks.BLEND_FUNC_CHANGE.post(GLSMHooks.blendFuncChangeEvent);
        }
    }

    public static void checkCompiling() {
        checkCompiling("");
    }

    public static void checkCompiling(String name) {
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("Not yet implemented (" + name + ")");
        }
    }

    // Records glBegin/glEnd/glVertex and compiles them to a VBO
    // Currently only used inside of display lists

            public static void glNormal3b(byte nx, byte ny, byte nz) { GLSMCurrentAttribs.glNormal3b(nx, ny, nz); }

            public static void glNormal3d(double nx, double ny, double nz) { GLSMCurrentAttribs.glNormal3d(nx, ny, nz); }

    public static void glNormal3f(float nx, float ny, float nz) { GLSMCurrentAttribs.glNormal3f(nx, ny, nz); }

            public static void glNormal3i(int nx, int ny, int nz) { GLSMCurrentAttribs.glNormal3i(nx, ny, nz); }

    public static void glDepthFunc(int func) { GLSMDepthStencilAlpha.glDepthFunc(func); }

    public static void glDepthMask(boolean mask) { GLSMDepthStencilAlpha.glDepthMask(mask); }

    public static void glEdgeFlag(boolean flag) { GLSMCurrentAttribs.glEdgeFlag(flag); }

    public static void glColor4f(float red, float green, float blue, float alpha) { GLSMCurrentAttribs.glColor4f(red, green, blue, alpha); }

            public static void glColor4d(double red, double green, double blue, double alpha) { GLSMCurrentAttribs.glColor4d(red, green, blue, alpha); }

            public static void glColor4b(byte red, byte green, byte blue, byte alpha) { GLSMCurrentAttribs.glColor4b(red, green, blue, alpha); }

    public static void glColor4ub(byte red, byte green, byte blue, byte alpha) { GLSMCurrentAttribs.glColor4ub(red, green, blue, alpha); }

            public static void glColor3f(float red, float green, float blue) { GLSMCurrentAttribs.glColor3f(red, green, blue); }

            public static void glColor3d(double red, double green, double blue) { GLSMCurrentAttribs.glColor3d(red, green, blue); }

            public static void glColor3b(byte red, byte green, byte blue) { GLSMCurrentAttribs.glColor3b(red, green, blue); }

            public static void glColor3ub(byte red, byte green, byte blue) { GLSMCurrentAttribs.glColor3ub(red, green, blue); }

    static float ub2f(byte b) {
        return (b & 0xFF) / 255.0F;
    }

    static float b2f(byte b) {
        return ((b - Byte.MIN_VALUE) & 0xFF) / 255.0F;
    }

            public static float i2f(int i) { return GLSMLighting.i2f(i); }

    static boolean changeColor(float red, float green, float blue, float alpha) {
        // Per GL semantics glColor* clamps its components to [0,1] at the API boundary, so clamp
        // before caching: raw out-of-range values would otherwise leak into the FFP u_CurrentColor
        // upload, where Uniforms.sanitizeUniformColor treats any negative channel as the
        // clearCurrentColor dirty sentinel and substitutes opaque white. Mods do pass negative
        // colors — GalaxySpace computes the night sky dome as skyColor - playerY/400, which is
        // negative at night and rendered as a white sky instead of black (issue #164).
        // The dirty sentinel bypasses this path: clearCurrentColor writes ctx().color directly.
        red = Color4.clamp01(red);
        green = Color4.clamp01(green);
        blue = Color4.clamp01(blue);
        alpha = Color4.clamp01(alpha);
        // Mirrors StellarCore's HudCaching color interceptor: while rendering into the
        // HUD cache framebuffer with blending disabled, translucent colors would write
        // their own alpha into the cache buffer; the cached-HUD blit blends RGB by alpha,
        // so those areas would show through as fully transparent. StellarCore forces
        // alpha to 1 in that window; GLSM's redirect path must apply the same override
        // because StellarCore's own mixin can no longer intercept the call.
        if (GLSMConfig.hudCacheOverride && !GLSMConfig.hudCacheBlendEnabled && alpha < 1.0F) {
            alpha = 1.0F;
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || red != ctx().color.getRed() || green != ctx().color.getGreen() || blue != ctx().color.getBlue() || alpha != ctx().color.getAlpha()) {
            if (caching) {
                mod(ctx().color);
                ctx().color.setRed(red);
                ctx().color.setGreen(green);
                ctx().color.setBlue(blue);
                ctx().color.setAlpha(alpha);
                ctx().colorGeneration++;
            }
            if (ImmediateModeRecorder.isDrawing()) {
                ImmediateModeRecorder.setColor(red, green, blue, alpha);
            }
            ctx().dirtyColorAttrib = true;
            return true;
        }
        return false;
    }

    public static void glSecondaryColor3f(float red, float green, float blue) { GLSMCurrentAttribs.glSecondaryColor3f(red, green, blue); }

            public static void glSecondaryColor3d(double red, double green, double blue) { GLSMCurrentAttribs.glSecondaryColor3d(red, green, blue); }

            public static void glSecondaryColor3b(byte red, byte green, byte blue) { GLSMCurrentAttribs.glSecondaryColor3b(red, green, blue); }

            public static void glSecondaryColor3ub(byte red, byte green, byte blue) { GLSMCurrentAttribs.glSecondaryColor3ub(red, green, blue); }

    /** Helper for glSecondaryColor* - the color sum is emulated by the FFP shaders, nothing is sent to the driver. */
    static void changeSecondaryColor(float red, float green, float blue) {
        final GLContextState glCtx = ctx();
        if (!isCachingEnabled() || red != glCtx.secondaryColor.getRed() || green != glCtx.secondaryColor.getGreen() || blue != glCtx.secondaryColor.getBlue()) {
            mod(glCtx.secondaryColor);
            glCtx.secondaryColor.setRed(red);
            glCtx.secondaryColor.setGreen(green);
            glCtx.secondaryColor.setBlue(blue);
            glCtx.fragmentGeneration++;
        }
    }

    private static final Color4 DirtyColor = new Color4(-1.0F, -1.0F, -1.0F, -1.0F);

    public static void clearCurrentColor() {
        // Marks the cache dirty, doesn't actually reset the color.
        // The -1 sentinel forces the next real glColor call to bypass the cache,
        // and bumps the generation so FFP emulation re-uploads the default color
        // (GL current color semantics: resetColor restores opaque white).
        // FFP uniform uploads must normalize the sentinel (see Uniforms.sanitizeUniformColor).
        mod(ctx().color).set(DirtyColor);
        ctx().colorGeneration++;
    }

    public static void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) { GLSMBlend.glColorMask(red, green, blue, alpha); }

    public static void glClearColor(float red, float green, float blue, float alpha) { GLSMBlend.glClearColor(red, green, blue, alpha); }

    public static void glClearDepth(double depth) { GLSMDepthStencilAlpha.glClearDepth(depth); }

            public static void enableAlphaTest() { GLSMDepthStencilAlpha.enableAlphaTest(); }

            public static void disableAlphaTest() { GLSMDepthStencilAlpha.disableAlphaTest(); }

    public static void glAlphaFunc(int function, float reference) { GLSMDepthStencilAlpha.glAlphaFunc(function, reference); }

    public static void glActiveTexture(int texture) { GLSMTextures.glActiveTexture(texture); }

            public static void glActiveTextureARB(int texture) { GLSMTextures.glActiveTextureARB(texture); }

    public static void glClientActiveTexture(int texture) { GLSMTextures.glClientActiveTexture(texture); }

            public static int getClientActiveTextureUnit() { return GLSMTextures.getClientActiveTextureUnit(); }

    static int getBoundTexture() {
        return getBoundTexture(ctx().activeTextureUnit.getValue());
    }

     static int getBoundTexture(int unit) {
        return ctx().textures.getTextureUnitBindings(unit).getBinding();
    }

    public static void glBindTexture(int target, int texture) { GLSMTextures.glBindTexture(target, texture); }

    static int changeFormatIfDeprecated(int internalformat) {
        internalformat = GLESFormatRemap.promoteAlphaFormat(internalformat);
        if (RenderSystem.isGLES()) {
            internalformat = GLESFormatRemap.remapInternalFormat(internalformat);
        }
        return internalformat;
    }

    public static final class GLESTexImageRemap {
        private final GLESFormatRemap.Result r;
        GLESTexImageRemap(GLESFormatRemap.Result r) { this.r = r; }
        public int internalFormat() { return r.internalFormat(); }
        public int format() { return r.format(); }
        public int type() { return r.type(); }
    }

            public static GLESTexImageRemap remapTexImageForGLES(int internalformat, int format, int type) { return GLSMTextures.remapTexImageForGLES(internalformat, format, type); }

    static {
        if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) {
            LOGGER.warn("GLSM GLES pixel-type remap assumes little-endian host; big-endian detected - BGRA uploads may be byte-swapped");
        }
    }

    static int remapPixelTypeForGLES(int format, int type) {
        return RenderSystem.isGLES() ? GLESFormatRemap.remapPixelType(format, type) : type;
    }

    static int remapTypeForRemappedInternalFormat(int internalformat, int type) {
        if (!RenderSystem.isGLES()) return type;
        if (!GLESFormatRemap.isGenericPixelType(type)) return type;
        return GLESFormatRemap.typeForInternalFormatES32(internalformat, type);
    }

    static void logUncachedTextureTarget(int target) {
        final int bit = switch (target) {
            case GL11.GL_TEXTURE_1D -> 0;
            case GL30.GL_TEXTURE_2D_ARRAY -> 1;
            case GL12.GL_TEXTURE_3D -> 2;
            case GL13.GL_TEXTURE_CUBE_MAP -> 3;
            case GL31.GL_TEXTURE_RECTANGLE -> 4;
            case GL32.GL_TEXTURE_2D_MULTISAMPLE -> 5;
            default -> 6;
        };
        final long mask = 1L << bit;
        if ((loggedUncachedTextureTargets & mask) == 0) {
            loggedUncachedTextureTargets |= mask;
            LOGGER.debug("glBindTexture target {} not cached by GLSM, passing through", target);
        }
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, IntBuffer pixels) { GLSMTextures.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels); }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, FloatBuffer pixels) { GLSMTextures.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels); }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, DoubleBuffer pixels) { GLSMTextures.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels); }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels) { GLSMTextures.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels); }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, long pixels_buffer_offset) { GLSMTextures.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels_buffer_offset); }

            public static void glTexCoord1f(float s) { GLSMCurrentAttribs.glTexCoord1f(s); }

            public static void glTexCoord1d(double s) { GLSMCurrentAttribs.glTexCoord1d(s); }

    public static void glTexCoord2f(float s, float t) { GLSMCurrentAttribs.glTexCoord2f(s, t); }

            public static void glTexCoord2d(double s, double t) { GLSMCurrentAttribs.glTexCoord2d(s, t); }

            public static void glTexCoord3f(float s, float t, float r) { GLSMCurrentAttribs.glTexCoord3f(s, t, r); }

            public static void glTexCoord3d(double s, double t, double r) { GLSMCurrentAttribs.glTexCoord3d(s, t, r); }

    public static void glTexCoord4f(float s, float t, float r, float q) { GLSMCurrentAttribs.glTexCoord4f(s, t, r, q); }

            public static void glTexCoord4d(double s, double t, double r, double q) { GLSMCurrentAttribs.glTexCoord4d(s, t, r, q); }

    public static void glDeleteTextures(int id) { GLSMTextures.glDeleteTextures(id); }

    public static void glDeleteTextures(IntBuffer ids) { GLSMTextures.glDeleteTextures(ids); }

    public static void glDeleteTextures(int[] ids) { GLSMTextures.glDeleteTextures(ids); }

    /** Shrink texture to 1x1 to free GPU memory, unbind from all units, but keep the name valid. */
    static void deferDeleteTexture(int id) {
        if (id == 0) return;

        final int savedUnit = ctx().activeTextureUnit.getValue();
        final int savedBinding = ctx().textures.getTextureUnitBindings(savedUnit).getBinding();
        boolean changedUnit = false;

        for (int i = 0; i <= ctx().maxBoundTextureUnit; i++) {
            if (ctx().textures.getTextureUnitBindings(i).getBinding() == id) {
                if (i != savedUnit) {
                    RENDER_BACKEND.activeTexture(GL13.GL_TEXTURE0 + i);
                    changedUnit = true;
                }
                RENDER_BACKEND.bindTexture(GL11.GL_TEXTURE_2D, 0);
            }
        }

        if (changedUnit) {
            RENDER_BACKEND.activeTexture(GL13.GL_TEXTURE0 + savedUnit);
        }

        // The shrink goes through TEXTURE_2D: non-2D targets keep their storage until the
        // deferred delete runs, rather than erroring on the mismatched bind and shrinking
        // whatever 2D texture happens to be bound instead.
        final TextureInfo info = TextureInfoCache.INSTANCE.peekInfo(id);
        if (info == null || info.getTarget() == GL11.GL_TEXTURE_2D) {
            RENDER_BACKEND.bindTexture(GL11.GL_TEXTURE_2D, id);
            RENDER_BACKEND.texParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            RENDER_BACKEND.texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, 1, 1, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);

            RENDER_BACKEND.bindTexture(GL11.GL_TEXTURE_2D, savedBinding == id ? 0 : savedBinding);
        }

        deferredDeleteTextures.add(id);
    }

    /** Flush deferred deletes so the driver can recycle names. */
    static void flushDeferredTextureDeletes() {
        if (deferredDeleteTextures.isEmpty()) return;
        final var it = deferredDeleteTextures.iterator();
        while (it.hasNext()) {
            RENDER_BACKEND.deleteTextures(it.nextInt());
        }
        deferredDeleteTextures.clear();
    }

    public static int glGenTextures() { return GLSMTextures.glGenTextures(); }

    public static void glGenTextures(IntBuffer textures) { GLSMTextures.glGenTextures(textures); }

    public static void glGenTextures(int[] textures) { GLSMTextures.glGenTextures(textures); }

            public static void enableTexture() { GLSMTextures.enableTexture(); }

            public static void disableTexture() { GLSMTextures.disableTexture(); }

    /**
     * Force-syncs a texture unit's GL_TEXTURE_2D enable state and notifies TEXTURE_UNIT_STATE
     * listeners. For render paths that restore captured state (e.g. DeferredDrawBatcher) where
     * setting the state directly would silently desync Iris pipeline input tracking (issue #41).
     */
    public static void setTexture2DEnabled(int textureUnit, boolean enabled) {
        final TextureUnitBooleanStateStack state = ctx().textures.getTextureUnitStates(textureUnit);
        if (!shouldBypassCache() && state.isEnabled() == enabled) {
            return;
        }
        postTextureUnitState(textureUnit, enabled);
        state.setEnabled(enabled);
    }

    static void postTextureUnitState(int textureUnit, boolean enabled) {
        if (!GLSMHooks.TEXTURE_UNIT_STATE.hasListeners()) {
            return;
        }
        GLSMHooks.textureUnitStateEvent.unit = textureUnit;
        GLSMHooks.textureUnitStateEvent.target = GL11.GL_TEXTURE_2D;
        GLSMHooks.textureUnitStateEvent.enabled = enabled;
        GLSMHooks.TEXTURE_UNIT_STATE.post(GLSMHooks.textureUnitStateEvent);
    }

    static final String PIXELTRANSFER_MSG = "glPixelTransfer is not available in GL 3.3 core profile.";

            public static void glPixelTransferf(int pname, float param) { GLSMDraws.glPixelTransferf(pname, param); }

            public static void glPixelTransferi(int pname, int param) { GLSMDraws.glPixelTransferi(pname, param); }

    private static final String RASTERPOS_MSG = "glRasterPos is not available in GL 3.3 core profile.";

    public static void glRasterPos2f(float x, float y) { guardUnsupportedFFP("glRasterPos", RASTERPOS_MSG); }
    public static void glRasterPos2d(double x, double y) { guardUnsupportedFFP("glRasterPos", RASTERPOS_MSG); }
    public static void glRasterPos2i(int x, int y) { guardUnsupportedFFP("glRasterPos", RASTERPOS_MSG); }
    public static void glRasterPos3f(float x, float y, float z) { guardUnsupportedFFP("glRasterPos", RASTERPOS_MSG); }
    public static void glRasterPos3d(double x, double y, double z) { guardUnsupportedFFP("glRasterPos", RASTERPOS_MSG); }
    public static void glRasterPos3i(int x, int y, int z) { guardUnsupportedFFP("glRasterPos", RASTERPOS_MSG); }
    public static void glRasterPos4f(float x, float y, float z, float w) { guardUnsupportedFFP("glRasterPos", RASTERPOS_MSG); }
    public static void glRasterPos4d(double x, double y, double z, double w) { guardUnsupportedFFP("glRasterPos", RASTERPOS_MSG); }
    public static void glRasterPos4i(int x, int y, int z, int w) { guardUnsupportedFFP("glRasterPos", RASTERPOS_MSG); }

            public static void setFilter(boolean bilinear, boolean mipmap) { GLSMTextures.setFilter(bilinear, mipmap); }

    public static void glBegin(int mode) { GLSMDraws.glBegin(mode); }

    public static void glEnd() { GLSMDraws.glEnd(); }

    // Vertex methods for display list recording and live immediate mode
    public static void glVertex2f(float x, float y) { GLSMDraws.glVertex2f(x, y); }

            public static void glVertex2i(int x, int y) { GLSMDraws.glVertex2i(x, y); }

            public static void glVertex2d(double x, double y) { GLSMDraws.glVertex2d(x, y); }

    public static void glVertex3f(float x, float y, float z) { GLSMDraws.glVertex3f(x, y, z); }

            public static void glVertex3d(double x, double y, double z) { GLSMDraws.glVertex3d(x, y, z); }

            public static void glVertex3i(int x, int y, int z) { GLSMDraws.glVertex3i(x, y, z); }

    public static void glDrawElements(int mode, ByteBuffer indices) { GLSMDraws.glDrawElements(mode, indices); }

    public static void glDrawElements(int mode, IntBuffer indices) { GLSMDraws.glDrawElements(mode, indices); }

    public static void glDrawElements(int mode, ShortBuffer indices) { GLSMDraws.glDrawElements(mode, indices); }

    public static void glDrawElements(int mode, int count, int type, ByteBuffer indices) { GLSMDraws.glDrawElements(mode, count, type, indices); }

    public static void glDrawElements(int mode, int count, ByteBuffer indices) { GLSMDraws.glDrawElements(mode, count, indices); }

    public static void glDrawElements(int mode, int indices_count, int type, long indices_buffer_offset) { GLSMDraws.glDrawElements(mode, indices_count, type, indices_buffer_offset); }

    public static void glDrawBuffer(int mode) { GLSMFramebuffer.glDrawBuffer(mode); }

            public static void glMultiDrawElementsIndirect(int mode, int type, long indirect, int drawcount, int stride) { GLSMDraws.glMultiDrawElementsIndirect(mode, type, indirect, drawcount, stride); }

            public static void glMultiDrawElementsBaseVertex(int mode, long pCount, int type, long pIndices, int drawcount, long pBaseVertex) { GLSMDraws.glMultiDrawElementsBaseVertex(mode, pCount, type, pIndices, drawcount, pBaseVertex); }

            public static void glDrawElementsBaseVertex(int mode, int count, int type, long indices, int basevertex) { GLSMDraws.glDrawElementsBaseVertex(mode, count, type, indices, basevertex); }

            public static void glDrawRangeElements(int mode, int start, int end, int count, int type, long indices) { GLSMDraws.glDrawRangeElements(mode, start, end, count, type, indices); }

            public static void glDrawRangeElementsBaseVertex(int mode, int start, int end, int count, int type, long indices, int baseVertex) { GLSMDraws.glDrawRangeElementsBaseVertex(mode, start, end, count, type, indices, baseVertex); }

            public static void glMultiDrawArrays(int mode, IntBuffer firsts, IntBuffer counts) { GLSMDraws.glMultiDrawArrays(mode, firsts, counts); }

            public static void glMultiDrawArrays(int mode, int[] firsts, int[] counts) { GLSMDraws.glMultiDrawArrays(mode, firsts, counts); }

            public static void glMultiDrawArraysIndirectCount(int mode, long indirect, long drawcount, int maxdrawcount, int stride) { GLSMDraws.glMultiDrawArraysIndirectCount(mode, indirect, drawcount, maxdrawcount, stride); }

    public static void glMultiDrawArraysIndirect(int mode, long indirect, int drawcount, int stride) {
        preDrawFFP();
        prepareClientArrays();
        recordGpuCommand(GpuCommandType.DRAW_ARRAYS, mode, drawcount);
        RENDER_BACKEND.multiDrawArraysIndirect(mode, indirect, drawcount, stride);
    }

            public static void glMultiDrawElementsIndirectCount(int mode, int type, long indirect, long drawcount, int maxdrawcount, int stride) { GLSMDraws.glMultiDrawElementsIndirectCount(mode, type, indirect, drawcount, maxdrawcount, stride); }

            public static void glPrimitiveRestartIndex(int index) { GLSMDraws.glPrimitiveRestartIndex(index); }

    public static void glPointParameterf(int pname, float param) { GLSMRasterizer.glPointParameterf(pname, param); }

    public static void glPointParameteri(int pname, int param) { GLSMRasterizer.glPointParameteri(pname, param); }

    public static void glPointParameter(int pname, FloatBuffer params) { GLSMRasterizer.glPointParameter(pname, params); }

    public static void glPointParameter(int pname, IntBuffer params) { GLSMRasterizer.glPointParameter(pname, params); }

    /**
     * Applies one {@code glPointParameter} call. {@code GL_POINT_SIZE_MIN}/{@code GL_MAX} and
     * {@code GL_POINT_DISTANCE_ATTENUATION} were removed from the core profile, so those only update the
     * tracked state; the remaining targets are stored and forwarded.
     */
    static void pointParameter(int pname, float v0, float v1, float v2) {
        final PointStateStack point = ctx().pointState;
        switch (pname) {
            case GL14.GL_POINT_SIZE_MIN -> {
                if (!(v0 < 0.0f) && point.getMinSize() != v0) mod(point).setMinSize(v0);
            }
            case GL14.GL_POINT_SIZE_MAX -> {
                if (!(v0 < 0.0f) && point.getMaxSize() != v0) mod(point).setMaxSize(v0);
            }
            case GL14.GL_POINT_FADE_THRESHOLD_SIZE -> {
                if (!(v0 < 0.0f) && point.getFadeThreshold() != v0) {
                    mod(point).setFadeThreshold(v0);
                    RENDER_BACKEND.pointParameterf(pname, v0);
                }
            }
            case GL14.GL_POINT_DISTANCE_ATTENUATION -> {
                if (point.getAttenuationConstant() == v0 && point.getAttenuationLinear() == v1 && point.getAttenuationQuadratic() == v2) return;
                mod(point);
                point.setAttenuationConstant(v0);
                point.setAttenuationLinear(v1);
                point.setAttenuationQuadratic(v2);
            }
            case GL20.GL_POINT_SPRITE_COORD_ORIGIN -> {
                final int origin = (int) v0;
                if ((origin == GL20.GL_LOWER_LEFT || origin == GL20.GL_UPPER_LEFT) && point.getSpriteCoordOrigin() != origin) {
                    mod(point).setSpriteCoordOrigin(origin);
                    RENDER_BACKEND.pointParameteri(pname, origin);
                }
            }
            default -> RENDER_BACKEND.pointParameterf(pname, v0);
        }
    }

    static void applyPointState(GLContextState glCtx) {
        final PointStateStack point = glCtx.pointState;
        RENDER_BACKEND.pointSize(point.getSize());
        RENDER_BACKEND.pointParameterf(GL14.GL_POINT_FADE_THRESHOLD_SIZE, point.getFadeThreshold());
        RENDER_BACKEND.pointParameteri(GL20.GL_POINT_SPRITE_COORD_ORIGIN, point.getSpriteCoordOrigin());
    }

    public static boolean supportsGeometryShaders() { return RENDER_BACKEND.supportsGeometryShaders(); }

            public static boolean framebufferCompletenessIsMeaningful() { return GLSMFramebuffer.framebufferCompletenessIsMeaningful(); }

    public static String getRenderBackendName() {
        return RENDER_BACKEND.getName();
    }

    /** Full pre-draw preparation for an indexed/unprepared draw of the given GL primitive mode. */
            public static void preDraw(int drawMode) { GLSMDraws.preDraw(drawMode); }

    /** Full pre-draw preparation when the primitive mode is unknown or not line-based. */
            public static void preDraw() { GLSMDraws.preDraw(); }

            public static boolean consumeUnit23TexCoordSetDuringDraw() { return GLSMCurrentAttribs.consumeUnit23TexCoordSetDuringDraw(); }

            public static void forceAttribDefaultsDirty() { GLSMCurrentAttribs.forceAttribDefaultsDirty(); }

            public static void trackMaxBoundTextureUnit(int unit) { GLSMTextures.trackMaxBoundTextureUnit(unit); }

            public static void setOverlayColor(float r, float g, float b, float a) { GLSMCurrentAttribs.setOverlayColor(r, g, b, a); }

            public static void setShaderColor(float r, float g, float b, float a) { GLSMCurrentAttribs.setShaderColor(r, g, b, a); }

            public static void forcePixelUnpackState(PixelStoreState target) { GLSMTextures.forcePixelUnpackState(target); }

            public static void restorePixelUnpackState(PixelStoreState applied) { GLSMTextures.restorePixelUnpackState(applied); }

    public static void incrementFragmentGeneration() {
        ctx().fragmentGeneration++;
    }

            public static void glDrawElementsInstanced(int mode, int count, int type, long indices, int primcount) { GLSMDraws.glDrawElementsInstanced(mode, count, type, indices, primcount); }

            public static void glDrawArraysInstanced(int mode, int first, int count, int primcount) { GLSMDraws.glDrawArraysInstanced(mode, first, count, primcount); }

    static void preDrawFFP() {
        final VAOManager vaos = ctx().vaos;
        final ShaderManager ffp = ctx().ffp;
        if (vaos.hasAnyClientSideEnabledAttrib()) {
            ffp.preDraw(vaos.currentClientArrayVertexFlags());
        } else {
            ffp.preDraw();
        }
    }

    static void prepareClientArrays() {
        prepareClientArrays(0, -1);
    }

    static void prepareClientArrays(int first, int count) {
        final boolean perfDebugEnabled = GLSMPerfDebug.isEnabled();
        final long perfStart = perfDebugEnabled ? GLSMPerfDebug.begin(GLSMPerfDebug.Stage.GL_PREPARE_CLIENT_ARRAYS) : 0L;
        final VAOManager vaos = ctx().vaos;
        if (ShaderManager.isEnabled() && vaos.hasAnyClientSideEnabledAttrib()) {
            vaos.uploadClientArraysToVBO(first, count);
        }
        if (perfDebugEnabled) {
            GLSMPerfDebug.end(GLSMPerfDebug.Stage.GL_PREPARE_CLIENT_ARRAYS, perfStart);
        }
    }

    public static void glDrawArrays(int mode, int first, int count) { GLSMDraws.glDrawArrays(mode, first, count); }

    /**
     * The conventional texcoord slot of the current client active texture unit. {@link #glClientActiveTexture}
     * only accepts units {@code 0..MAX_TEXTURE_COORDS-1}, and every one of them has a conventional slot.
     */
    static int texCoordConventional() {
        return VAOManager.CONV_TEX0 + ctx().clientActiveTextureUnit;
    }

    private static boolean isLegalClientArray(int conv, int size, int type, int stride) {
        if (stride < 0) {
            warnOnce("client-array-stride-" + conv, "Negative client array stride {} for conventional slot {}; the array keeps it, the driver rejects it", stride, conv);
        }
        if (VAOManager.isLegalConventional(conv, size, type)) return true;
        warnOnce("client-array-illegal-" + conv, "Ignoring client array {} with size {} / type 0x{}", conv, size, Integer.toHexString(type));
        return false;
    }

    static boolean isLegalVertexAttrib(int index, int size, int type, int stride, boolean integer) {
        if (stride < 0) {
            warnOnce("vertex-attrib-stride-" + index, "Negative vertex attribute stride {} for location {}; the pointer keeps it, the driver rejects it", stride, index);
        }
        if (VAOManager.isLegalGeneric(index, size, type, integer)) return true;
        warnOnce("vertex-attrib-illegal-" + index, "Ignoring vertex attribute {} with size {} / type 0x{}", index, size, Integer.toHexString(type));
        return false;
    }

    static void ffpClientArrayPointer(int conv, int size, int type, boolean normalized, int stride, long offset) {
        if (!isLegalClientArray(conv, size, type, stride)) return;
        if (ctx().vaos.setConventional(conv, size, type, normalized, stride, offset, ctx().boundVBO)) {
            RENDER_BACKEND.vertexAttribPointer(VAOManager.conventionalLocation(conv), size, type, normalized, stride, offset);
        }
    }

    static void ffpClientArrayPointer(int conv, int size, int type, boolean normalized, int stride, ByteBuffer pointer) {
        if (!isLegalClientArray(conv, size, type, stride)) return;
        ctx().vaos.setConventional(conv, size, type, normalized, stride, pointer);
    }

    public static void glVertexPointer(int size, int stride, IntBuffer pointer) { GLSMVertexInput.glVertexPointer(size, stride, pointer); }

    public static void glVertexPointer(int size, int stride, ShortBuffer pointer) { GLSMVertexInput.glVertexPointer(size, stride, pointer); }

    public static void glVertexPointer(int size, int stride, FloatBuffer pointer) { GLSMVertexInput.glVertexPointer(size, stride, pointer); }

    public static void glVertexPointer(int size, int stride, DoubleBuffer pointer) { GLSMVertexInput.glVertexPointer(size, stride, pointer); }

    public static void glVertexPointer(int size, int type, int stride, ByteBuffer pointer) { GLSMVertexInput.glVertexPointer(size, type, stride, pointer); }

    public static void glVertexPointer(int size, int type, int stride, long pointer_buffer_offset) { GLSMVertexInput.glVertexPointer(size, type, stride, pointer_buffer_offset); }

            public static void glColorPointer(int size, int stride, FloatBuffer pointer) { GLSMVertexInput.glColorPointer(size, stride, pointer); }

            public static void glColorPointer(int size, int stride, DoubleBuffer pointer) { GLSMVertexInput.glColorPointer(size, stride, pointer); }

            public static void glColorPointer(int size, int type, int stride, ByteBuffer pointer) { GLSMVertexInput.glColorPointer(size, type, stride, pointer); }

            public static void glColorPointer(int size, boolean unsigned, int stride, ByteBuffer pointer) { GLSMVertexInput.glColorPointer(size, unsigned, stride, pointer); }

            public static void glColorPointer(int size, int type, int stride, long pointer_buffer_offset) { GLSMVertexInput.glColorPointer(size, type, stride, pointer_buffer_offset); }

            public static void glNormalPointer(int stride, FloatBuffer pointer) { GLSMVertexInput.glNormalPointer(stride, pointer); }

            public static void glNormalPointer(int stride, ByteBuffer pointer) { GLSMVertexInput.glNormalPointer(stride, pointer); }

            public static void glNormalPointer(int stride, IntBuffer pointer) { GLSMVertexInput.glNormalPointer(stride, pointer); }

            public static void glNormalPointer(int stride, DoubleBuffer pointer) { GLSMVertexInput.glNormalPointer(stride, pointer); }

            public static void glNormalPointer(int type, int stride, ByteBuffer pointer) { GLSMVertexInput.glNormalPointer(type, stride, pointer); }

            public static void glNormalPointer(int type, int stride, FloatBuffer pointer) { GLSMVertexInput.glNormalPointer(type, stride, pointer); }

            public static void glNormalPointer(int type, int stride, ShortBuffer pointer) { GLSMVertexInput.glNormalPointer(type, stride, pointer); }

            public static void glNormalPointer(int type, int stride, IntBuffer pointer) { GLSMVertexInput.glNormalPointer(type, stride, pointer); }

            public static void glNormalPointer(int type, int stride, DoubleBuffer pointer) { GLSMVertexInput.glNormalPointer(type, stride, pointer); }

            public static void glNormalPointer(int type, int stride, long pointer_buffer_offset) { GLSMVertexInput.glNormalPointer(type, stride, pointer_buffer_offset); }

    public static void glTexCoordPointer(int size, int type, int stride, ByteBuffer pointer) { GLSMVertexInput.glTexCoordPointer(size, type, stride, pointer); }

    public static void glTexCoordPointer(int size, int stride, FloatBuffer pointer) { GLSMVertexInput.glTexCoordPointer(size, stride, pointer); }

    public static void glTexCoordPointer(int size, int stride, IntBuffer pointer) { GLSMVertexInput.glTexCoordPointer(size, stride, pointer); }

    public static void glTexCoordPointer(int size, int stride, ShortBuffer pointer) { GLSMVertexInput.glTexCoordPointer(size, stride, pointer); }

    public static void glTexCoordPointer(int size, int stride, DoubleBuffer pointer) { GLSMVertexInput.glTexCoordPointer(size, stride, pointer); }

    public static void glTexCoordPointer(int size, int type, int stride, FloatBuffer pointer) { GLSMVertexInput.glTexCoordPointer(size, type, stride, pointer); }

    public static void glTexCoordPointer(int size, int type, int stride, ShortBuffer pointer) { GLSMVertexInput.glTexCoordPointer(size, type, stride, pointer); }

    public static void glTexCoordPointer(int size, int type, int stride, IntBuffer pointer) { GLSMVertexInput.glTexCoordPointer(size, type, stride, pointer); }

    public static void glTexCoordPointer(int size, int type, int stride, DoubleBuffer pointer) { GLSMVertexInput.glTexCoordPointer(size, type, stride, pointer); }

    public static void glTexCoordPointer(int size, int type, int stride, long pointer_buffer_offset) { GLSMVertexInput.glTexCoordPointer(size, type, stride, pointer_buffer_offset); }

    public static void glEdgeFlagPointer(int stride, ByteBuffer pointer) { GLSMVertexInput.glEdgeFlagPointer(stride, pointer); }

    public static void glEdgeFlagPointer(int stride, long pointer_buffer_offset) { GLSMVertexInput.glEdgeFlagPointer(stride, pointer_buffer_offset); }

    public static void glFogCoordPointer(int type, FloatBuffer data) { GLSMVertexInput.glFogCoordPointer(type, data); }

    public static void glFogCoordPointer(int type, DoubleBuffer data) { GLSMVertexInput.glFogCoordPointer(type, data); }

    public static void glFogCoordPointer(int type, int stride, long data_buffer_offset) { GLSMVertexInput.glFogCoordPointer(type, stride, data_buffer_offset); }

    public static void glSecondaryColorPointer(int size, int stride, FloatBuffer data) { GLSMVertexInput.glSecondaryColorPointer(size, stride, data); }

    public static void glSecondaryColorPointer(int size, int stride, DoubleBuffer data) { GLSMVertexInput.glSecondaryColorPointer(size, stride, data); }

    public static void glSecondaryColorPointer(int size, boolean unsigned, int stride, ByteBuffer data) { GLSMVertexInput.glSecondaryColorPointer(size, unsigned, stride, data); }

    public static void glSecondaryColorPointer(int size, int type, int stride, long data_buffer_offset) { GLSMVertexInput.glSecondaryColorPointer(size, type, stride, data_buffer_offset); }

    /**
     * Answers the pointer of a conventional client array. Client-side pointers report the address of the
     * memory GLSM captured for them, buffer-backed arrays report their offset, and an array that was never
     * specified reports {@code null}.
     */
    public static ByteBuffer glGetPointer(int pname, long result_size) { return GLSMVertexInput.glGetPointer(pname, result_size); }

    public static void glEnableClientState(int cap) { GLSMVertexInput.glEnableClientState(cap); }

    public static void glDisableClientState(int cap) { GLSMVertexInput.glDisableClientState(cap); }

    public static void glVertexAttrib2f(int index, float v0, float v1) { GLSMCurrentAttribs.glVertexAttrib2f(index, v0, v1); }

    public static void glVertexAttrib2s(int index, short v0, short v1) { GLSMCurrentAttribs.glVertexAttrib2s(index, v0, v1); }

            public static void glVertexAttrib3f(int index, float v0, float v1, float v2) { GLSMCurrentAttribs.glVertexAttrib3f(index, v0, v1, v2); }

    public static void glVertexAttrib4f(int index, float v0, float v1, float v2, float v3) { GLSMCurrentAttribs.glVertexAttrib4f(index, v0, v1, v2, v3); }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long offset) { GLSMVertexInput.glVertexAttribPointer(index, size, type, normalized, stride, offset); }

    public static void glVertexAttribPointer(int index, int size, boolean normalized, int stride, FloatBuffer pointer) { GLSMVertexInput.glVertexAttribPointer(index, size, normalized, stride, pointer); }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, FloatBuffer pointer) { GLSMVertexInput.glVertexAttribPointer(index, size, type, normalized, stride, pointer); }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, DoubleBuffer pointer) { GLSMVertexInput.glVertexAttribPointer(index, size, type, normalized, stride, pointer); }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, IntBuffer pointer) { GLSMVertexInput.glVertexAttribPointer(index, size, type, normalized, stride, pointer); }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, ShortBuffer pointer) { GLSMVertexInput.glVertexAttribPointer(index, size, type, normalized, stride, pointer); }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, ByteBuffer pointer) { GLSMVertexInput.glVertexAttribPointer(index, size, type, normalized, stride, pointer); }

            public static void glVertexAttribIPointer(int index, int size, int type, int stride, long offset) { GLSMVertexInput.glVertexAttribIPointer(index, size, type, stride, offset); }

            public static void glVertexAttribIPointer(int index, int size, int type, int stride, ByteBuffer pointer) { GLSMVertexInput.glVertexAttribIPointer(index, size, type, stride, pointer); }

            public static void glVertexAttribIPointer(int index, int size, int type, int stride, IntBuffer pointer) { GLSMVertexInput.glVertexAttribIPointer(index, size, type, stride, pointer); }

            public static void glVertexAttribIPointer(int index, int size, int type, int stride, ShortBuffer pointer) { GLSMVertexInput.glVertexAttribIPointer(index, size, type, stride, pointer); }

            public static void glVertexAttribDivisor(int index, int divisor) { GLSMVertexInput.glVertexAttribDivisor(index, divisor); }

    public static void glVertexAttribDivisorARB(int index, int divisor) {
        glVertexAttribDivisor(index, divisor);
    }

    public static void glGetVertexAttrib(int index, int pname, IntBuffer params) { RENDER_BACKEND.getVertexAttribi(index, pname, params); }
    public static void glBindVertexBuffer(int bindingindex, int buffer, long offset, int stride) { RENDER_BACKEND.bindVertexBuffer(bindingindex, buffer, offset, stride); }
    public static void glVertexAttribFormat(int attribindex, int size, int type, boolean normalized, int relativeoffset) { RENDER_BACKEND.vertexAttribFormat(attribindex, size, type, normalized, relativeoffset); }
    public static void glVertexAttribIFormat(int attribindex, int size, int type, int relativeoffset) { RENDER_BACKEND.vertexAttribIFormat(attribindex, size, type, relativeoffset); }
    public static void glVertexAttribBinding(int attribindex, int bindingindex) { RENDER_BACKEND.vertexAttribBinding(attribindex, bindingindex); }

    public static void glEnableVertexAttribArray(int index) { GLSMVertexInput.glEnableVertexAttribArray(index); }

    public static void glDisableVertexAttribArray(int index) { GLSMVertexInput.glDisableVertexAttribArray(index); }

    static int clientStateToVertexFlag(int cap) {
        return switch (cap) {
            case GL11.GL_COLOR_ARRAY -> VertexFlags.COLOR_BIT;
            case GL11.GL_NORMAL_ARRAY -> VertexFlags.NORMAL_BIT;
            case GL11.GL_TEXTURE_COORD_ARRAY -> switch (ctx().clientActiveTextureUnit) {
                // Units 0/1 are the legacy texture/lightmap slots. Units 2/3 are the extended
                // multi-texture UV slots (attributes 5/6) and carry no legacy FFP format flag; their
                // per-vertex availability is tracked per attribute in VAOManager. This mirrors the
                // conventional texcoord slot mapping, which covers the same units 0..3.
                case 0 -> VertexFlags.TEXTURE_BIT;
                case 1 -> VertexFlags.BRIGHTNESS_BIT;
                case 2, 3 -> 0;
                default -> 0;
            };
            default -> 0; // GL_VERTEX_ARRAY: position is implicit
        };
    }

    static final int CLIENT_ATTRIB_STACK_DEPTH = 16;

    public static void glPushClientAttrib(int mask) { GLSMAttribStack.glPushClientAttrib(mask); }

    public static void glPopClientAttrib() { GLSMAttribStack.glPopClientAttrib(); }

    /** Drives the driver back to the pixel store state a matching {@code glPushClientAttrib} recorded. */
    static void restorePixelStore(PixelStoreState unpack, PixelStoreState pack) {
        PixelStoreState.applyDiff(ctx().pixelUnpackState, unpack, false);
        ctx().pixelUnpackState = unpack;
        PixelStoreState.applyDiff(ctx().pixelPackState, pack, true);
        ctx().pixelPackState = pack;
    }

            public static void glInterleavedArrays(int format, int stride, long pointer) { GLSMVertexInput.glInterleavedArrays(format, stride, pointer); }

            public static void glInterleavedArrays(int format, int stride, ByteBuffer pointer) { GLSMVertexInput.glInterleavedArrays(format, stride, pointer); }

            public static void glInterleavedArrays(int format, int stride, FloatBuffer pointer) { GLSMVertexInput.glInterleavedArrays(format, stride, pointer); }

            public static void glInterleavedArrays(int format, int stride, DoubleBuffer pointer) { GLSMVertexInput.glInterleavedArrays(format, stride, pointer); }

            public static void glInterleavedArrays(int format, int stride, IntBuffer pointer) { GLSMVertexInput.glInterleavedArrays(format, stride, pointer); }

            public static void glInterleavedArrays(int format, int stride, ShortBuffer pointer) { GLSMVertexInput.glInterleavedArrays(format, stride, pointer); }

    public static void glLogicOp(int opcode) { GLSMBlend.glLogicOp(opcode); }

            public static void defaultBlendFunc() { GLSMBlend.defaultBlendFunc(); }

            public static void enableCull() { GLSMRasterizer.enableCull(); }

            public static void disableCull() { GLSMRasterizer.disableCull(); }

            public static void enableDepthTest() { GLSMDepthStencilAlpha.enableDepthTest(); }

            public static void disableDepthTest() { GLSMDepthStencilAlpha.disableDepthTest(); }

            public static void enableLighting() { GLSMLighting.enableLighting(); }

            public static void enableLight(int light) { GLSMLighting.enableLight(light); }

            public static void enableColorMaterial() { GLSMLighting.enableColorMaterial(); }

            public static void disableColorMaterial() { GLSMLighting.disableColorMaterial(); }

            public static void disableLighting() { GLSMLighting.disableLighting(); }

            public static void disableLight(int light) { GLSMLighting.disableLight(light); }

            public static void enableRescaleNormal() { GLSMLighting.enableRescaleNormal(); }

            public static void disableRescaleNormal() { GLSMLighting.disableRescaleNormal(); }

            public static void enableFog() { GLSMFog.enableFog(); }

            public static void disableFog() { GLSMFog.disableFog(); }

    public static void glFog(int pname, FloatBuffer param) { GLSMFog.glFog(pname, param); }

    public static void glFog(int pname, IntBuffer param) { GLSMFog.glFog(pname, param); }

            public static Vector3d getFogColor() { return GLSMFog.getFogColor(); }

            public static void fogColor(float red, float green, float blue, float alpha) { GLSMFog.fogColor(red, green, blue, alpha); }

    public static void glFogf(int pname, float param) { GLSMFog.glFogf(pname, param); }

    public static void glFogi(int pname, int param) { GLSMFog.glFogi(pname, param); }

    public static void setFogBlack() {
        glFogf(GL11.GL_FOG_COLOR, 0.0F);
    }

    public static void glShadeModel(int mode) { GLSMRasterizer.glShadeModel(mode); }

    static void onDeleteTexture(int id) {
        TextureInfoCache.INSTANCE.onDeleteTexture(id);
        if (GLSMHooks.TEXTURE_DELETE.hasListeners()) {
            GLSMHooks.textureDeleteEvent.textureId = id;
            GLSMHooks.TEXTURE_DELETE.post(GLSMHooks.textureDeleteEvent);
        }

        for (int i = 0; i <= ctx().maxBoundTextureUnit; i++) {
            if (ctx().textures.getTextureUnitBindings(i).getBinding() == id) {
                mod(ctx().textures.getTextureUnitBindings(i)).setBinding(0);
            }
        }
    }

    public static void makeCurrent(Drawable drawable) throws LWJGLException {
        drawable.makeCurrent();
        CurrentThread = Thread.currentThread();

        final boolean display;
        try {
            display = drawable == Display.getDrawable();
        } catch (Exception e) {
            LOGGER.warn("Display not ready in makeCurrent", e);
            return;
        }
        final Object token = drawable instanceof DrawableGL drawableGL
            ? drawableGL.getContext()
            : drawable;
        final long nativeHandle = RENDER_BACKEND.getContextHandle();
        if (display) {
            if (!splashComplete) GLStateManager.drawableGL = drawable;
            bindDisplayContext(token, nativeHandle);
        } else {
            bindDrawableContext(token, nativeHandle);
        }
    }

    public static void releaseContext(Drawable drawable) throws LWJGLException {
        drawable.releaseContext();
        releaseBoundContext();
    }

    public static void registerDrawableContext(Object contextToken) {
        if (contextToken == null) throw new IllegalArgumentException("contextToken must not be null");
        contextFor(contextToken);
    }

    public static void bindDisplayContext(Object contextToken, long nativeHandle) {
        if (contextToken == null) throw new IllegalArgumentException("contextToken must not be null");
        if (displayContextHandle == 0) displayContextHandle = nativeHandle;
        boundDisplayContextHandle = nativeHandle;
        displayContextToken = contextToken;
        synchronized (GLStateManager.class) {
            drawableContexts.put(contextToken, primaryContext());
        }
        if (!splashComplete) drawableGLHolder = Thread.currentThread();
        tlBoundContextHandle.set(nativeHandle);
        if (bindThreadContext(null)) replayStateToBackend();
    }

    public static void bindDrawableContext(Object contextToken, long nativeHandle) {
        if (contextToken == null) throw new IllegalArgumentException("contextToken must not be null");
        if (contextToken == displayContextToken) {
            bindDisplayContext(contextToken, nativeHandle);
            return;
        }
        final GLContextState target = contextFor(contextToken);
        if (!target.viewportInitialized) {
            final IntBuffer viewport = BufferUtils.createIntBuffer(4);
            RENDER_BACKEND.getInteger(GL11.GL_VIEWPORT, viewport);
            target.viewportState.setViewPort(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
            RENDER_BACKEND.getInteger(GL11.GL_SCISSOR_BOX, viewport);
            target.scissorState.setScissor(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
            target.viewportInitialized = true;
        }
        initializeDrawableResources(target);
        if (drawableGLHolder == Thread.currentThread()) drawableGLHolder = null;
        tlBoundContextHandle.set(nativeHandle);
        if (bindThreadContext(target)) replayStateToBackend();
    }

    private static void initializeDrawableResources(GLContextState glCtx) {
        if (glCtx.defaultVAO == 0) {
            glCtx.defaultVAO = RENDER_BACKEND.genVertexArrays();
        }
        if (!glCtx.vaos.isInitialized()) {
            glCtx.boundVAO = glCtx.defaultVAO;
            glCtx.vaos.init(glCtx.defaultVAO);
        }
    }

    public static void releaseBoundContext() {
        bindThreadContext(null);
        tlBoundContextHandle.remove();
        if (drawableGLHolder == Thread.currentThread()) drawableGLHolder = null;
    }

    public static long getBoundContextHandle() {
        final Long handle = tlBoundContextHandle.get();
        return handle != null ? handle : 0L;
    }

    public static void retireDrawableContext(Object contextToken) {
        if (contextToken == displayContextToken) {
            retireDisplayContext(contextToken);
            return;
        }
        final GLContextState retired;
        synchronized (GLStateManager.class) {
            retired = drawableContexts.remove(contextToken);
            if (retired != null) registeredContexts.remove(retired);
        }
        if (retired != null && tlWorkerContext.get() == retired) releaseBoundContext();
    }

    public static void retireDisplayContext(Object contextToken) {
        synchronized (GLStateManager.class) {
            drawableContexts.remove(contextToken);
            if (displayContextToken == contextToken) {
                displayContextToken = null;
                boundDisplayContextHandle = 0;
            }
        }
    }

    public static boolean displayContextMigrated() {
        return displayContextHandle != 0 && boundDisplayContextHandle != 0
            && boundDisplayContextHandle != displayContextHandle;
    }

    public static void recreateDefaultVertexArray() {
        final GLContextState glCtx = ctx();
        glCtx.defaultVAO = glGenVertexArrays();
        glCtx.boundVAO = -1;
        glBindVertexArray(0);
        glCtx.vaos.init(glCtx.defaultVAO);
        glCtx.streamingVaos.reset();
        glCtx.ffp.resetForContextMigration();
        if (boundDisplayContextHandle == 0) {
            boundDisplayContextHandle = RENDER_BACKEND.getContextHandle();
        }
        displayContextHandle = boundDisplayContextHandle;
        replayStateToBackend();
    }

    /**
     * Mark splash as complete - enables fast path that always caches. Called when finish() permanently switches to DrawableGL for the main game loop.
     */
    public static void markSplashComplete() {
        if (splashComplete) return;
        splashComplete = true;
        stateSeedPending = true;
        drawableGLHolder = null;
        drawableGL = null;
        if (GLSMHooks.LOADING_CHECKPOINT.hasListeners()) {
            GLSMHooks.loadingCheckpointEvent.requiresSync = true;
            GLSMHooks.LOADING_CHECKPOINT.post(GLSMHooks.loadingCheckpointEvent);
            GLSMHooks.loadingCheckpointEvent.requiresSync = false;
        }
    }

    public static void markSplashComplete(String source) {
        if (splashComplete) return;
        LOGGER.info("Splash complete (source={}, thread={})", source, Thread.currentThread().getName());
        markSplashComplete();
    }

    public static void glNewList(int list, int mode) { GLSMDraws.glNewList(list, mode); }

    public static void glEndList() { GLSMDraws.glEndList(); }

    /**
     * Check if we're currently recording a display list.
     */
    public static boolean isRecordingDisplayList() {
        return DisplayListManager.isRecording();
    }

    /**
     * True when a GL context is current on the calling thread. Off-thread GL calls are
     * tolerated by LWJGL2 but abort the JVM under LWJGL3, so callers touching GL from
     * potentially context-less threads (e.g. the integrated server thread) must check this.
     */
    public static boolean hasContext() {
        return RENDER_BACKEND.hasContext();
    }

    public static int getRecordingDisplayListId() {
        return DisplayListManager.getRecordingListId();
    }

    static final DisplayListIDAllocator displayListIdAllocator = new DisplayListIDAllocator();

    public static int glGenLists(int range) { return GLSMDraws.glGenLists(range); }

            public static boolean glIsList(int list) { return GLSMDraws.glIsList(list); }

    /**
     * Delete display lists and free their VBO resources.
     */
    public static void glDeleteLists(int list, int range) { GLSMDraws.glDeleteLists(list, range); }

    /**
     * Get a compiled display list from the cache. Used for executing nested display lists.
     *
     * @return The CompiledDisplayList, or null if not found
     */
            public static CompiledDisplayList getDisplayList(int list) { return GLSMDraws.getDisplayList(list); }

    public static void glCallList(int list) { GLSMDraws.glCallList(list); }

    public static void pushState(int mask) {
        pushStateLive(StateSet.forMask(mask));
    }

    public static int pushState(StateSet set) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            final int token = ctx().attribDepth + DisplayListManager.virtualStateDepth(mode);
            if (token >= MAX_ATTRIB_STACK_DEPTH) {
                throw new IllegalStateException("Attrib stack overflow: max depth " + MAX_ATTRIB_STACK_DEPTH + " reached");
            }
            DisplayListManager.recordPushState(set);
            if (mode == RecordMode.COMPILE) {
                return token;
            }
        }
        final int depth = pushStateLive(set);
        if (mode == RecordMode.COMPILE_AND_EXECUTE) DisplayListManager.liveStatePushed(depth + 1);
        return depth;
    }

    static int pushStateLive(StateSet set) {
        final GLContextState glCtx = ctx();
        if (glCtx.attribDepth >= MAX_ATTRIB_STACK_DEPTH) {
            throw new IllegalStateException("Attrib stack overflow: max depth " + MAX_ATTRIB_STACK_DEPTH + " reached");
        }
        final int depth = glCtx.attribDepth;
        glCtx.attribSets[depth] = set;
        snapshotGenerations(glCtx, set.bump);
        glCtx.modifiedCount[depth] = 0;

        if ((set.restore & StateSet.R_PROGRAM) != 0) {
            glCtx.programStack.setValue(glCtx.activeProgram);
            glCtx.programStack.push();
        }

        glCtx.attribDepth++;
        return depth;
    }

    private static void snapshotGenerations(GLContextState glCtx, int bump) {
        final int depth = glCtx.attribDepth;
        if ((bump & StateSet.B_LIGHTING) != 0) {
            glCtx.savedLightingGen[depth] = glCtx.lightingGeneration;
        }
        if ((bump & (StateSet.B_FRAG_COLORBUF | StateSet.B_FRAG_TEXTURE | StateSet.B_FRAG_FOG)) != 0) {
            glCtx.savedFragmentGen[depth] = glCtx.fragmentGeneration;
        }
        if ((bump & StateSet.B_TRANSFORM) != 0) {
            glCtx.savedMvGen[depth] = glCtx.mvGeneration;
            glCtx.savedProjGen[depth] = glCtx.projGeneration;
            glCtx.savedTexMatGen[depth] = glCtx.texMatrixGeneration;
            glCtx.savedColorMatGen[depth] = glCtx.colorMatrixGeneration;
        }
        if ((bump & StateSet.B_CURRENT) != 0) {
            glCtx.savedColorGen[depth] = glCtx.colorGeneration;
            glCtx.savedNormalGen[depth] = ShaderManager.getNormalGeneration();
            glCtx.savedTexCoordGen[depth] = ShaderManager.getTexCoordGeneration();
        }
    }

    public static void popStateTo(int depth) {
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        while (glCtx.attribDepth + DisplayListManager.virtualStateDepth(mode) > depth) {
            if (mode == RecordMode.COMPILE && DisplayListManager.recordPopStateIfPending()) continue;
            if (mode == RecordMode.COMPILE_AND_EXECUTE) DisplayListManager.recordPopStateIfPending();
            popState();
        }
    }

    public static void popState() {
        final GLContextState glCtx = ctx();
        if (glCtx.attribDepth <= 0) {
            throw new IllegalStateException("Attrib stack underflow");
        }
        final int poppedDepth = glCtx.attribDepth;
        final StateSet set = glCtx.attribSets[poppedDepth - 1];
        final boolean inspectAlpha = GLSMHooks.ALPHA_STATE_CHANGE.hasListeners()
            && ((glCtx.alphaState.cowDepths().claimedAt(poppedDepth) && set.contains(glCtx.alphaState.stackId()))
                || (glCtx.alphaTest.cowDepths().claimedAt(poppedDepth) && set.contains(glCtx.alphaTest.stackId())));
        final AlphaState effectiveAlpha = inspectAlpha ? glCtx.alphaState.readEffective(glCtx.restoredAlphaScratch) : null;
        final boolean effectiveEnabled = inspectAlpha && glCtx.alphaTest.isEffectivelyEnabled();
        final int effectiveFunction = inspectAlpha ? effectiveAlpha.getFunction() : 0;
        final float effectiveReference = inspectAlpha ? effectiveAlpha.getReference() : 0.0f;
        final boolean liveEnabled = inspectAlpha && glCtx.alphaTest.isEnabled();
        final int liveFunction = inspectAlpha ? glCtx.alphaState.getFunction() : 0;
        final float liveReference = inspectAlpha ? glCtx.alphaState.getReference() : 0.0f;
        final boolean blendSnapshotted = set.blendSnapshot
            && (glCtx.blendState.cowDepths().claimedAt(poppedDepth) || glCtx.blendMode.cowDepths().claimedAt(poppedDepth))
            && snapshotVanillaBlendFunc();
        final int blendSrcRgbBeforeRestore = glCtx.blendState.getSrcRgb();
        final int blendDstRgbBeforeRestore = glCtx.blendState.getDstRgb();
        final int blendSrcAlphaBeforeRestore = glCtx.blendState.getSrcAlpha();
        final int blendDstAlphaBeforeRestore = glCtx.blendState.getDstAlpha();
        final boolean prevPoppingAttributes = glCtx.poppingAttributes;
        IntArrayList restoredTextureUnits = null;
        glCtx.poppingAttributes = true;
        try {
            glCtx.attribDepth--;
            int changed = 0;
            long unitsChanged = 0L;

            if ((set.restore & StateSet.R_PROGRAM) != 0) {
                glCtx.programStack.setValue(glCtx.activeProgram);
                if (glCtx.programStack.topSlotChanged()) changed |= StateSet.R_PROGRAM;
                glCtx.programStack.pop();
            }

            final int depth = glCtx.attribDepth;
            final int count = glCtx.modifiedCount[depth];
            final int[] ids = glCtx.modifiedIds[depth];
            for (int i = 0; i < count; i++) {
                final int id = ids[i];
                final CowStateStack<?> cow = glCtx.stackById[id];
                final byte kind = glCtx.kindById[id];
                if (set.contains(id)) {
                    final int bit = glCtx.restoreBitById[id];
                    if (bit != 0 && CowDispatch.topSlotChanged(kind, cow)) {
                        changed |= bit;
                        final int unit = glCtx.restoreUnitById[id];
                        if (unit >= 0) unitsChanged |= 1L << Math.min(unit, 63);
                    }
                    final boolean trackTextureUnit = cow instanceof TextureUnitBooleanStateStack textureState
                        && textureState == glCtx.textures.getTextureUnitStates(textureState.getUnitIndex());
                    final boolean enabledBeforeRestore = trackTextureUnit && ((TextureUnitBooleanStateStack) cow).isEnabled();
                    final int slot = glCtx.depthsById[id].pop();
                    if (slot >= 0) CowDispatch.restoreSlot(kind, cow, slot);
                    if (trackTextureUnit && enabledBeforeRestore != ((TextureUnitBooleanStateStack) cow).isEnabled()) {
                        if (restoredTextureUnits == null) restoredTextureUnits = new IntArrayList();
                        restoredTextureUnits.add(((TextureUnitBooleanStateStack) cow).getUnitIndex());
                    }
                } else if (glCtx.depthsById[id].discard(poppedDepth) >= 0) {
                    registerModifiedState(id);
                }
            }
            glCtx.modifiedCount[depth] = 0;
            glCtx.restoreChangedMask = changed;
            glCtx.restoreUnitChangedMask = unitsChanged;
            applyRestoredState(set, blendSnapshotted);
        } finally {
            glCtx.poppingAttributes = prevPoppingAttributes;
        }

        if ((set.restore & StateSet.R_BLEND) != 0
            && (blendSrcRgbBeforeRestore != glCtx.blendState.getSrcRgb()
            || blendDstRgbBeforeRestore != glCtx.blendState.getDstRgb()
            || blendSrcAlphaBeforeRestore != glCtx.blendState.getSrcAlpha()
            || blendDstAlphaBeforeRestore != glCtx.blendState.getDstAlpha())) {
            postBlendFuncChange(
                glCtx.blendState.getSrcRgb(), glCtx.blendState.getDstRgb(),
                glCtx.blendState.getSrcAlpha(), glCtx.blendState.getDstAlpha());
        }
        if (restoredTextureUnits != null) {
            for (int i = 0; i < restoredTextureUnits.size(); i++) {
                final int textureUnit = restoredTextureUnits.getInt(i);
                postTextureUnitState(textureUnit, glCtx.textures.getTextureUnitStates(textureUnit).isEnabled());
            }
        }
        if (inspectAlpha) {
            glCtx.alphaState.readEffective(glCtx.restoredAlphaScratch);
            if (effectiveEnabled != glCtx.alphaTest.isEffectivelyEnabled()
                || effectiveFunction != effectiveAlpha.getFunction()
                || Float.compare(effectiveReference, effectiveAlpha.getReference()) != 0
                || liveEnabled != glCtx.alphaTest.isEnabled()
                || liveFunction != glCtx.alphaState.getFunction()
                || Float.compare(liveReference, glCtx.alphaState.getReference()) != 0) {
                GLSMHooks.ALPHA_STATE_CHANGE.post(GLSMHooks.alphaStateChangeEvent);
            }
        }
        DisplayListManager.liveStatePopped(poppedDepth);
    }

            public static void retainModifiedState(int depth, RetainedState out) { GLSMAttribStack.retainModifiedState(depth, out); }

            public static void applyRetainedState(RetainedState in) { GLSMAttribStack.applyRetainedState(in); }

    static void applyRetainedChanges(GLContextState glCtx, RetainedState in, int first) {
        final boolean blendSnapshotted = snapshotVanillaBlendFunc();
        final int blendSrcRgbBeforeRestore = glCtx.blendState.getSrcRgb();
        final int blendDstRgbBeforeRestore = glCtx.blendState.getDstRgb();
        final int blendSrcAlphaBeforeRestore = glCtx.blendState.getSrcAlpha();
        final int blendDstAlphaBeforeRestore = glCtx.blendState.getDstAlpha();
        final int alphaFuncId = glCtx.alphaState.stackId();
        final int alphaTestId = glCtx.alphaTest.stackId();
        boolean alphaChanged = false;
        IntArrayList restoredTextureUnits = null;
        final boolean prevPoppingAttributes = glCtx.poppingAttributes;
        glCtx.poppingAttributes = true;
        try {
            final CowStateStack<?>[] stackById = glCtx.stackById;
            final int[] restoreBitById = glCtx.restoreBitById;
            final int[] restoreUnitById = glCtx.restoreUnitById;
            final byte[] kindById = glCtx.kindById;
            int changed = 0;
            long unitsChanged = 0L;
            for (int i = first; i < in.count; i++) {
                final int id = in.ids[i];
                final CowStateStack<?> cow = stackById[id];
                final byte kind = kindById[id];
                if (!CowDispatch.slotChanged(kind, cow, RETAINED_SLOT)) continue;
                cow.beforeModify();
                final int bit = restoreBitById[id];
                if (bit != 0) {
                    changed |= bit;
                    final int unit = restoreUnitById[id];
                    if (unit >= 0) unitsChanged |= 1L << Math.min(unit, 63);
                }
                final boolean trackTextureUnit = cow instanceof TextureUnitBooleanStateStack textureState
                    && textureState == glCtx.textures.getTextureUnitStates(textureState.getUnitIndex());
                final boolean enabledBeforeRestore = trackTextureUnit && ((TextureUnitBooleanStateStack) cow).isEnabled();
                CowDispatch.restoreSlot(kind, cow, RETAINED_SLOT);
                if (trackTextureUnit && enabledBeforeRestore != ((TextureUnitBooleanStateStack) cow).isEnabled()) {
                    if (restoredTextureUnits == null) restoredTextureUnits = new IntArrayList();
                    restoredTextureUnits.add(((TextureUnitBooleanStateStack) cow).getUnitIndex());
                }
                if (id == alphaFuncId || id == alphaTestId) alphaChanged = true;
            }
            glCtx.restoreChangedMask = changed;
            glCtx.restoreUnitChangedMask = unitsChanged;
            issueRestoredBackendState(glCtx, -1);
            glCtx.lightingGeneration++;
            glCtx.fragmentGeneration++;
            glCtx.colorGeneration++;
            ShaderManager.bumpNormalGeneration();
            ShaderManager.bumpTexCoordGeneration();
            glCtx.dirtyColorAttrib = true;
            glCtx.dirtyNormalAttrib = true;
            glCtx.dirtyTexCoordAttrib = true;
            postVanillaBlendChangeIfMoved(blendSnapshotted);
        } finally {
            glCtx.poppingAttributes = prevPoppingAttributes;
        }
        if (blendSrcRgbBeforeRestore != glCtx.blendState.getSrcRgb()
            || blendDstRgbBeforeRestore != glCtx.blendState.getDstRgb()
            || blendSrcAlphaBeforeRestore != glCtx.blendState.getSrcAlpha()
            || blendDstAlphaBeforeRestore != glCtx.blendState.getDstAlpha()) {
            postBlendFuncChange(
                glCtx.blendState.getSrcRgb(), glCtx.blendState.getDstRgb(),
                glCtx.blendState.getSrcAlpha(), glCtx.blendState.getDstAlpha());
        }
        if (restoredTextureUnits != null) {
            for (int i = 0; i < restoredTextureUnits.size(); i++) {
                final int textureUnit = restoredTextureUnits.getInt(i);
                postTextureUnitState(textureUnit, glCtx.textures.getTextureUnitStates(textureUnit).isEnabled());
            }
        }
        if (alphaChanged && GLSMHooks.ALPHA_STATE_CHANGE.hasListeners()) {
            GLSMHooks.ALPHA_STATE_CHANGE.post(GLSMHooks.alphaStateChangeEvent);
        }
    }

    /**
     * After popping GLSM stacks, apply restored state to the GL driver.
     */
    private static boolean isDepthColorOverridden() {
        final DeferredDepthColorHandler dch = GLSMHooks.depthColorHandler;
        return dch != null && dch.isOverrideHeld();
    }

    private static void issueRestoredBackendState(GLContextState glCtx, int restore) {
        final int changed = glCtx.restoreChangedMask;
        if ((restore & StateSet.R_DEPTH) != 0 && (changed & StateSet.R_DEPTH) != 0) {
            RENDER_BACKEND.depthFunc(glCtx.depthState.getFunc());
            if (!isDepthColorOverridden()) {
                RENDER_BACKEND.depthMask(glCtx.depthState.isMaskEnabled());
            }
            RENDER_BACKEND.clearDepth(glCtx.depthState.getClearValue());
        }
        if ((restore & StateSet.R_BLEND) != 0) {
            final BlendStateStack blend = glCtx.blendState;
            if ((changed & StateSet.R_BLEND) != 0 || blend.isFuncUnknown()) {
                final DeferredBlendHandler restoreBlendHandler = GLSMHooks.blendHandler;
                if (restoreBlendHandler == null || !restoreBlendHandler.isOverrideHeld()) {
                    issueBlendFunc(blend);
                    blend.clearFuncUnknownState();
                }
                if ((changed & StateSet.R_BLEND) != 0) {
                    RENDER_BACKEND.blendEquationSeparate(blend.getEquationRgb(), blend.getEquationAlpha());
                    RENDER_BACKEND.blendColor(blend.getBlendColorR(), blend.getBlendColorG(), blend.getBlendColorB(), blend.getBlendColorA());
                }
            }
        }
        if ((restore & StateSet.R_COLOR_MASK) != 0 && (changed & StateSet.R_COLOR_MASK) != 0 && !isDepthColorOverridden()) {
            RENDER_BACKEND.colorMask(glCtx.colorMask.red, glCtx.colorMask.green, glCtx.colorMask.blue, glCtx.colorMask.alpha);
        }
        if ((restore & StateSet.R_CLEAR_COLOR) != 0 && (changed & StateSet.R_CLEAR_COLOR) != 0) {
            RENDER_BACKEND.clearColor(glCtx.clearColor.getRed(), glCtx.clearColor.getGreen(), glCtx.clearColor.getBlue(), glCtx.clearColor.getAlpha());
        }
        if ((restore & StateSet.R_DRAW_BUFFER) != 0 && (changed & StateSet.R_DRAW_BUFFER) != 0 && glCtx.drawFramebuffer == 0) {
            RENDER_BACKEND.drawBuffer(glCtx.drawBuffer.getValue());
        }
        if ((restore & StateSet.R_LOGIC_OP) != 0 && (changed & StateSet.R_LOGIC_OP) != 0) {
            RENDER_BACKEND.logicOp(glCtx.logicOpMode.getValue());
        }
        if ((restore & StateSet.R_STENCIL) != 0 && (changed & StateSet.R_STENCIL) != 0) {
            RENDER_BACKEND.stencilFuncSeparate(GL11.GL_FRONT, glCtx.stencilState.getFuncFront(), glCtx.stencilState.getRefFront(), glCtx.stencilState.getValueMaskFront());
            RENDER_BACKEND.stencilFuncSeparate(GL11.GL_BACK, glCtx.stencilState.getFuncBack(), glCtx.stencilState.getRefBack(), glCtx.stencilState.getValueMaskBack());
            RENDER_BACKEND.stencilOpSeparate(GL11.GL_FRONT, glCtx.stencilState.getFailOpFront(), glCtx.stencilState.getZFailOpFront(), glCtx.stencilState.getZPassOpFront());
            RENDER_BACKEND.stencilOpSeparate(GL11.GL_BACK, glCtx.stencilState.getFailOpBack(), glCtx.stencilState.getZFailOpBack(), glCtx.stencilState.getZPassOpBack());
            RENDER_BACKEND.stencilMaskSeparate(GL11.GL_FRONT, glCtx.stencilState.getWriteMaskFront());
            RENDER_BACKEND.stencilMaskSeparate(GL11.GL_BACK, glCtx.stencilState.getWriteMaskBack());
            RENDER_BACKEND.clearStencil(glCtx.stencilState.getClearValue());
        }
        if ((restore & StateSet.R_VIEWPORT) != 0 && (changed & StateSet.R_VIEWPORT) != 0) {
            RENDER_BACKEND.viewport(glCtx.viewportState.x, glCtx.viewportState.y, glCtx.viewportState.width, glCtx.viewportState.height);
            RENDER_BACKEND.depthRange(glCtx.viewportState.depthRangeNear, glCtx.viewportState.depthRangeFar);
        }
        if ((restore & StateSet.R_SCISSOR) != 0 && (changed & StateSet.R_SCISSOR) != 0) {
            RENDER_BACKEND.scissor(glCtx.scissorState.x, glCtx.scissorState.y, glCtx.scissorState.width, glCtx.scissorState.height);
        }
        if ((restore & StateSet.R_LINE) != 0 && (changed & StateSet.R_LINE) != 0) {
            RENDER_BACKEND.lineWidth(Math.max(lineWidthMin, Math.min(lineWidthMax, glCtx.lineState.getWidth())));
        }
        if ((restore & StateSet.R_POINT) != 0 && (changed & StateSet.R_POINT) != 0) {
            applyPointState(glCtx);
        }
        if ((restore & StateSet.R_HINT) != 0 && (changed & StateSet.R_HINT) != 0) {
            applyHintState(glCtx);
        }
        if ((restore & StateSet.R_POLYGON) != 0 && (changed & StateSet.R_POLYGON) != 0) {
            RENDER_BACKEND.polygonMode(GL11.GL_FRONT_AND_BACK, glCtx.polygonState.getFrontMode());
            RENDER_BACKEND.polygonOffset(glCtx.polygonState.getOffsetFactor(), glCtx.polygonState.getOffsetUnits());
            RENDER_BACKEND.cullFace(glCtx.polygonState.getCullFaceMode());
            RENDER_BACKEND.frontFace(glCtx.polygonState.getFrontFace());
        }

        final boolean restoreTextures = (restore & StateSet.R_TEXTURE) != 0 && (changed & StateSet.R_TEXTURE) != 0;
        final boolean restoreActiveUnit = (restore & StateSet.R_ACTIVE_UNIT) != 0;
        if (restoreTextures || restoreActiveUnit) {
            boolean unitTouched = false;
            if (restoreTextures) {
                final long unitsChanged = glCtx.restoreUnitChangedMask;
                for (int i = 0; i < MAX_TEXTURE_UNITS; i++) {
                    if ((unitsChanged & (1L << Math.min(i, 63))) != 0) {
                        final TextureBinding restored = glCtx.textures.getTextureUnitBindings(i);
                        final int target = restored.getTarget() != 0 ? restored.getTarget() : GL11.GL_TEXTURE_2D;
                        RENDER_BACKEND.activeTexture(GL13.GL_TEXTURE0 + i);
                        RENDER_BACKEND.bindTexture(target, restored.getBinding());
                        unitTouched = true;
                    }
                }
            }
            if (unitTouched || (restoreActiveUnit && (changed & StateSet.R_ACTIVE_UNIT) != 0)) {
                RENDER_BACKEND.activeTexture(GL13.GL_TEXTURE0 + glCtx.activeTextureUnit.getValue());
            }
        }

    }

    private static void applyRestoredState(StateSet set, boolean blendSnapshotted) {
        final GLContextState glCtx = ctx();
        final int restore = set.restore;
        final int changed = glCtx.restoreChangedMask;
        issueRestoredBackendState(glCtx, restore);

        final int bump = set.bump;
        final int depth = glCtx.attribDepth;
        if ((bump & StateSet.B_LIGHTING) != 0 && glCtx.lightingGeneration != glCtx.savedLightingGen[depth]) glCtx.lightingGeneration++;
        if ((bump & StateSet.B_FRAG_FOG) != 0 && glCtx.fragmentGeneration != glCtx.savedFragmentGen[depth]) glCtx.fragmentGeneration++;
        if ((bump & StateSet.B_FRAG_COLORBUF) != 0 && glCtx.fragmentGeneration != glCtx.savedFragmentGen[depth]) glCtx.fragmentGeneration++;
        if ((bump & StateSet.B_FRAG_TEXTURE) != 0 && glCtx.fragmentGeneration != glCtx.savedFragmentGen[depth]) glCtx.fragmentGeneration++;
        if ((bump & StateSet.B_TRANSFORM) != 0) {
            if (glCtx.mvGeneration != glCtx.savedMvGen[depth]) glCtx.mvGeneration++;
            if (glCtx.projGeneration != glCtx.savedProjGen[depth]) glCtx.projGeneration++;
            if (glCtx.texMatrixGeneration != glCtx.savedTexMatGen[depth]) glCtx.texMatrixGeneration++;
            if (glCtx.colorMatrixGeneration != glCtx.savedColorMatGen[depth]) glCtx.colorMatrixGeneration++;
        }
        if ((bump & StateSet.B_CURRENT) != 0) {
            if (glCtx.colorGeneration != glCtx.savedColorGen[depth]) {
                glCtx.colorGeneration++;
                glCtx.dirtyColorAttrib = true;
            }
            if (ShaderManager.getNormalGeneration() != glCtx.savedNormalGen[depth]) {
                ShaderManager.bumpNormalGeneration();
                glCtx.dirtyNormalAttrib = true;
            }
            if (ShaderManager.getTexCoordGeneration() != glCtx.savedTexCoordGen[depth]) {
                ShaderManager.bumpTexCoordGeneration();
                glCtx.dirtyTexCoordAttrib = true;
            }
            if ((restore & StateSet.R_SECONDARY_COLOR) != 0 && (changed & StateSet.R_SECONDARY_COLOR) != 0) glCtx.fragmentGeneration++;
        }

        postVanillaBlendChangeIfMoved(blendSnapshotted);
        if ((restore & StateSet.R_PROGRAM) != 0 && (changed & StateSet.R_PROGRAM) != 0) {
            glUseProgram(glCtx.programStack.getValue());
        }
    }

    public static void glClear(int mask) { GLSMFramebuffer.glClear(mask); }

    public static void glPushAttrib(int mask) { GLSMAttribStack.glPushAttrib(mask); }

    public static void glPopAttrib() { GLSMAttribStack.glPopAttrib(); }

    public static void glMatrixMode(int mode) { GLSMMatrices.glMatrixMode(mode); }

    public static void glLoadMatrix(FloatBuffer m) { GLSMMatrices.glLoadMatrix(m); }

    public static void glLoadMatrix(DoubleBuffer m) { GLSMMatrices.glLoadMatrix(m); }

            public static void setModelViewMatrix(Matrix4fc m) { GLSMMatrices.setModelViewMatrix(m); }

            public static void setProjectionMatrix(Matrix4fc m) { GLSMMatrices.setProjectionMatrix(m); }

    public static Matrix4fStack getMatrixStack() {
        return getMatrixStack(ctx());
    }

    static Matrix4fStack getMatrixStack(GLContextState glCtx) {
        switch (glCtx.matrixMode.getMode()) {
            case GL11.GL_MODELVIEW -> {
                return glCtx.modelViewMatrix;
            }
            case GL11.GL_PROJECTION -> {
                return glCtx.projectionMatrix;
            }
            case GL11.GL_TEXTURE -> {
                return glCtx.textures.getTextureUnitMatrix(glCtx.activeTextureUnit.getValue());
            }
            case GL11.GL_COLOR -> {
                return glCtx.colorMatrix;
            }
            default -> {
                LOGGER.warn("Unknown matrix mode {}, falling back to modelViewMatrix", glCtx.matrixMode.getMode());
                return glCtx.modelViewMatrix;
            }
        }
    }

    static void bumpMatrixGeneration(GLContextState glCtx) {
        switch (glCtx.matrixMode.getMode()) {
            case GL11.GL_MODELVIEW -> glCtx.mvGeneration++;
            case GL11.GL_PROJECTION -> glCtx.projGeneration++;
            case GL11.GL_TEXTURE -> glCtx.texMatrixGeneration++;
            case GL11.GL_COLOR -> glCtx.colorMatrixGeneration++;
            default -> {
                LOGGER.warn("Unknown matrix mode {}, falling back to mvGeneration", glCtx.matrixMode.getMode());
                glCtx.mvGeneration++;
            }
        }
    }

    public static void glLoadIdentity() { GLSMMatrices.glLoadIdentity(); }

    public static void glTranslatef(float x, float y, float z) { GLSMMatrices.glTranslatef(x, y, z); }

    public static void glTranslated(double x, double y, double z) { GLSMMatrices.glTranslated(x, y, z); }

    public static void glScalef(float x, float y, float z) { GLSMMatrices.glScalef(x, y, z); }

    public static void glScaled(double x, double y, double z) { GLSMMatrices.glScaled(x, y, z); }

    static final Matrix4f multMatrix = new Matrix4f();

    public static void glMultMatrix(FloatBuffer floatBuffer) { GLSMMatrices.glMultMatrix(floatBuffer); }

    public static final Matrix4d conversionMatrix4d = new Matrix4d();
    public static final Matrix4f conversionMatrix4f = new Matrix4f();

    public static void glMultMatrix(DoubleBuffer matrix) { GLSMMatrices.glMultMatrix(matrix); }

            public static void applyMultMatrix(Matrix4f multMatrix) { GLSMMatrices.applyMultMatrix(multMatrix); }

    static final Vector3f rotation = new Vector3f();

    public static void glRotatef(float angle, float x, float y, float z) { GLSMMatrices.glRotatef(angle, x, y, z); }

    public static void glRotated(double angle, double x, double y, double z) { GLSMMatrices.glRotated(angle, x, y, z); }

    public static void glOrtho(double left, double right, double bottom, double top, double zNear, double zFar) { GLSMMatrices.glOrtho(left, right, bottom, top, zNear, zFar); }

    public static void glFrustum(double left, double right, double bottom, double top, double zNear, double zFar) { GLSMMatrices.glFrustum(left, right, bottom, top, zNear, zFar); }

    public static void glPushMatrix() { GLSMMatrices.glPushMatrix(); }

    public static void glPopMatrix() { GLSMMatrices.glPopMatrix(); }

    static final Matrix4f gluMatrix = new Matrix4f();
    static final Matrix4f gluMatrix2 = new Matrix4f();
    static final Vector4f gluVec4 = new Vector4f();
    static final FloatBuffer gluBuffer = BufferUtils.createFloatBuffer(16);

            public static void gluPerspective(float fovy, float aspect, float zNear, float zFar) { GLSMMatrices.gluPerspective(fovy, aspect, zNear, zFar); }

            public static void gluLookAt(float eyex, float eyey, float eyez, float centerx, float centery, float centerz, float upx, float upy, float upz) { GLSMMatrices.gluLookAt(eyex, eyey, eyez, centerx, centery, centerz, upx, upy, upz); }

            public static void gluOrtho2D(float left, float right, float bottom, float top) { GLSMMatrices.gluOrtho2D(left, right, bottom, top); }

            public static void gluPickMatrix(float x, float y, float deltaX, float deltaY, IntBuffer viewport) { GLSMMatrices.gluPickMatrix(x, y, deltaX, deltaY, viewport); }

            public static int gluBuild2DMipmaps(int target, int components, int width, int height, int format, int type, ByteBuffer data) { return GLSMMatrices.gluBuild2DMipmaps(target, components, width, height, format, type, data); }

            public static String gluErrorString(int errorCode) { return GLSMMatrices.gluErrorString(errorCode); }

            public static boolean gluUnProject(float winx, float winy, float winz, FloatBuffer modelMatrix, FloatBuffer projMatrix, IntBuffer viewport, FloatBuffer obj_pos) { return GLSMMatrices.gluUnProject(winx, winy, winz, modelMatrix, projMatrix, viewport, obj_pos); }

    public static void glViewport(int x, int y, int width, int height) { GLSMRasterizer.glViewport(x, y, width, height); }

            public static int getActiveTextureUnit() { return GLSMTextures.getActiveTextureUnit(); }

            public static int getListMode() { return GLSMDraws.getListMode(); }

    /**
     * Resolves the TextureInfo of the currently bound 2D texture, preferring the per-context
     * binding cache when caching is enabled.
     */
    static TextureInfo getBoundTextureInfo() {
        final GLContextState glCtx = ctx();
        if (isCachingEnabled()) {
            return glCtx.textures.getTextureUnitBindings(glCtx.activeTextureUnit.getValue()).getOrResolveInfo();
        }
        return TextureInfoCache.INSTANCE.getInfo(getBoundTextureForServerState());
    }

    /** Auto-mipmap: regenerate mipmaps after a base-level upload when GL_GENERATE_MIPMAP is set. */
    static void maybeGenerateMipmap(int target, int level) {
        if (target != GL11.GL_TEXTURE_2D) return;
        final TextureInfo info = getBoundTextureInfo();
        if (info != null && info.isGenerateMipmap() && level == info.getBaseLevel() && level < info.getMaxLevel()) {
            RENDER_BACKEND.generateMipmap(target);
        }
    }

    /**
     * GL_GENERATE_MIPMAP was removed in GL 3.1 core; track it on the TextureInfo instead of
     * forwarding to the driver. Returns true when the pname was consumed here.
     */
    static boolean handleRemovedTexParam(int target, int pname, int value) {
        if (pname == GL14.GL_GENERATE_MIPMAP) {
            if (target == GL11.GL_TEXTURE_2D) {
                final TextureInfo info = getBoundTextureInfo();
                if (info != null) info.setGenerateMipmap(value != 0);
            }
            return true;
        }
        return false;
    }

            public static boolean updateTexParameteriCache(int target, int texture, int pname, int param) { return GLSMTextures.updateTexParameteriCache(target, texture, pname, param); }

            static boolean updateTexParameteriCache(TextureInfo info, int pname, int param) { return GLSMTextures.updateTexParameteriCache(info, pname, param); }

    public static void glTexParameter(int target, int pname, IntBuffer params) { GLSMTextures.glTexParameter(target, pname, params); }

    public static void glTexParameter(int target, int pname, FloatBuffer params) { GLSMTextures.glTexParameter(target, pname, params); }

    /** LWJGL3 array-signature variants used by kirino's texture accessors. */
    public static void glTexParameteriv(int target, int pname, int[] params) {
        glTexParameter(target, pname, IntBuffer.wrap(params));
    }

    public static void glTexParameterfv(int target, int pname, float[] params) {
        glTexParameter(target, pname, FloatBuffer.wrap(params));
    }

    public static void glTexParameterIiv(int target, int pname, int[] params) {
        RENDER_BACKEND.texParameterIiv(target, pname, IntBuffer.wrap(params));
    }

    public static void glTexParameterIuiv(int target, int pname, int[] params) {
        RENDER_BACKEND.texParameterIuiv(target, pname, IntBuffer.wrap(params));
    }

    public static void glTexParameteri(int target, int pname, int param) { GLSMTextures.glTexParameteri(target, pname, param); }

            public static boolean updateTexParameterfCache(int target, int texture, int pname, float param) { return GLSMTextures.updateTexParameterfCache(target, texture, pname, param); }

            static boolean updateTexParameterfCache(TextureInfo info, int pname, float param) { return GLSMTextures.updateTexParameterfCache(info, pname, param); }

    public static void glTexParameterf(int target, int pname, float param) { GLSMTextures.glTexParameterf(target, pname, param); }

    public static int getTexParameterOrDefault(int texture, int pname, IntSupplier defaultSupplier) {
        final TextureInfo info = TextureInfoCache.INSTANCE.getInfo(texture);
        if (info == null) {
            if (isRecordingDisplayList()) {
                throw new IllegalStateException(String.format(
                    "glGetTexParameteri called during display list recording with no cached TextureInfo for texture %d. " +
                        "Cannot query OpenGL state during compilation!", texture));
            }
            return defaultSupplier.getAsInt();
        }
        return switch (pname) {
            case GL11.GL_TEXTURE_MIN_FILTER -> info.getMinFilter();
            case GL11.GL_TEXTURE_MAG_FILTER -> info.getMagFilter();
            case GL11.GL_TEXTURE_WRAP_S -> info.getWrapS();
            case GL11.GL_TEXTURE_WRAP_T -> info.getWrapT();
            case GL12.GL_TEXTURE_MAX_LEVEL -> info.getMaxLevel();
            case GL12.GL_TEXTURE_MIN_LOD -> info.getMinLod();
            case GL12.GL_TEXTURE_MAX_LOD -> info.getMaxLod();
            default -> {
                if (isRecordingDisplayList()) {
                    throw new IllegalStateException(String.format(
                        "glGetTexParameteri called during display list recording with uncached pname 0x%s for texture %d. " +
                            "Cannot query OpenGL state during compilation!", Integer.toHexString(pname), texture));
                }
                yield defaultSupplier.getAsInt();
            }
        };
    }

    public static int glGetTexParameteri(int target, int pname) { return GLSMTextures.glGetTexParameteri(target, pname); }

            public static float glGetTexParameterf(int target, int pname) { return GLSMTextures.glGetTexParameterf(target, pname); }

    public static int glGetTexLevelParameteri(int target, int level, int pname) { return GLSMTextures.glGetTexLevelParameteri(target, level, pname); }

    public static float glGetTexLevelParameterf(int target, int level, int pname) {
        return glGetTexLevelParameteri(target, level, pname);
    }

    public static void glGetTexImage(int target, int level, int format, int type, ByteBuffer pixels) { GLSMTextures.glGetTexImage(target, level, format, type, pixels); }
    public static void glGetTexImage(int target, int level, int format, int type, IntBuffer pixels) { GLSMTextures.glGetTexImage(target, level, format, type, pixels); }

    static void getTexImageViaFboReadPixels(int target, int level, int format, int type, Buffer pixels) {
        if (target != GL11.GL_TEXTURE_2D) {
            LOGGER.warn("glGetTexImage ES: unsupported target 0x{}", Integer.toHexString(target));
            return;
        }
        final int texId = getBoundTextureForServerState();
        if (texId == 0) return;
        final int width = RENDER_BACKEND.getTexLevelParameteri(target, level, GL11.GL_TEXTURE_WIDTH);
        final int height = RENDER_BACKEND.getTexLevelParameteri(target, level, GL11.GL_TEXTURE_HEIGHT);
        if (width <= 0 || height <= 0) return;

        final int prevReadFb = RENDER_BACKEND.getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        final int fbo = RENDER_BACKEND.genFramebuffers();
        try {
            RENDER_BACKEND.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            RENDER_BACKEND.framebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, target, texId, level);
            final int status = RENDER_BACKEND.checkFramebufferStatus(GL30.GL_READ_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                LOGGER.warn("glGetTexImage ES: FBO incomplete (0x{})", Integer.toHexString(status));
                return;
            }
            final int esType = remapPixelTypeForGLES(format, type);
            final boolean bgra = format == GL12.GL_BGRA;
            final int esFormat = bgra ? GL11.GL_RGBA : format;
            suspendPixelPackBuffer();
            try {
                if (pixels instanceof ByteBuffer bb) {
                    RENDER_BACKEND.readPixels(0, 0, width, height, esFormat, esType, bb);
                } else if (pixels instanceof IntBuffer ib) {
                    RENDER_BACKEND.readPixels(0, 0, width, height, esFormat, esType, ib);
                }
                if (bgra) swapRedBlueInPlace(pixels);
            } finally {
                restorePixelPackBuffer();
            }
        } finally {
            RENDER_BACKEND.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFb);
            RENDER_BACKEND.deleteFramebuffers(fbo);
        }
    }

    private static void swapRedBlueInPlace(Buffer pixels) {
        if (pixels instanceof ByteBuffer bb) {
            final int p = bb.position();
            final int lim = bb.limit();
            for (int i = p; i + 3 < lim; i += 4) {
                final byte r = bb.get(i);
                bb.put(i, bb.get(i + 2));
                bb.put(i + 2, r);
            }
        } else if (pixels instanceof IntBuffer ib) {
            final int p = ib.position();
            final int lim = ib.limit();
            for (int i = p; i < lim; i++) {
                final int w = ib.get(i);
                ib.put(i, (w & 0xFF00FF00) | ((w & 0xFF) << 16) | ((w >> 16) & 0xFF));
            }
        }
    }

            public static void glReadPixels(int x, int y, int width, int height, int format, int type, ByteBuffer pixels) { GLSMFramebuffer.glReadPixels(x, y, width, height, format, type, pixels); }
            public static void glReadPixels(int x, int y, int width, int height, int format, int type, FloatBuffer pixels) { GLSMFramebuffer.glReadPixels(x, y, width, height, format, type, pixels); }
            public static void glReadPixels(int x, int y, int width, int height, int format, int type, IntBuffer pixels) { GLSMFramebuffer.glReadPixels(x, y, width, height, format, type, pixels); }

    /**
     * Reads pixels into the currently bound pixel-pack buffer at a byte offset. This overload
     * preserves that binding for PBO-backed capture paths that pass a raw OpenGL buffer offset.
     */
            public static void glReadPixels(int x, int y, int width, int height, int format, int type, long pixelBufferOffset) { GLSMFramebuffer.glReadPixels(x, y, width, height, format, type, pixelBufferOffset); }
            public static void glTexStorage2D(int target, int levels, int internalFormat, int width, int height) { GLSMTextures.glTexStorage2D(target, levels, internalFormat, width, height); }

            public static void glClearTexImage(int texture, int level, int format, int type, ByteBuffer data) { GLSMTextures.glClearTexImage(texture, level, format, type, data); }

    public static void glClearTexSubImage(int texture, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth, int format, int type, ByteBuffer data) {
        recordGpuCommand(GpuCommandType.CLEAR_TEX_IMAGE, GpuCommandPhase.BEGIN, texture, level);
        RENDER_BACKEND.clearTexSubImage(texture, level, xoffset, yoffset, zoffset, width, height, depth, format, type, data);
        recordGpuCommand(GpuCommandType.CLEAR_TEX_IMAGE, GpuCommandPhase.END, texture, level);
        gpuCheckpoint(GpuCommandType.CLEAR_TEX_IMAGE);
    }

            public static int glCreateTextures(int target) { return GLSMTextures.glCreateTextures(target); }

    public static void glBindTextureUnit(int unit, int texture) { GLSMTextures.glBindTextureUnit(unit, texture); }

    /** DSA texture parameter paths recover the target from the GLSM-side cache (set at glCreateTextures). */
    private static int dsaTargetOf(int texture) {
        final TextureInfo info = TextureInfoCache.INSTANCE.peekInfo(texture);
        return info != null ? info.getTarget() : 0;
    }

    public static void glTextureParameteri(int texture, int pname, int param) {
        param = remapTexClamp(pname, param);
        final int target = dsaTargetOf(texture);
        if (!updateTexParameteriCache(target, texture, pname, param)) return;
        RENDER_BACKEND.textureParameteri(texture, target, pname, param);
    }

    public static void glTextureParameterf(int texture, int pname, float param) {
        param = remapTexClamp(pname, param);
        final int target = dsaTargetOf(texture);
        if (!updateTexParameterfCache(target, texture, pname, param)) return;
        RENDER_BACKEND.textureParameterf(texture, target, pname, param);
    }

    public static void glTextureParameteriv(int texture, int pname, int[] params) {
        final IntBuffer buf = IntBuffer.wrap(params);
        remapTexClampBuffer(pname, buf);
        RENDER_BACKEND.textureParameteriv(texture, dsaTargetOf(texture), pname, buf);
    }

    public static void glTextureParameterfv(int texture, int pname, float[] params) {
        if (params.length > 0) {
            final float remapped = remapTexClamp(pname, params[0]);
            if (remapped != params[0]) params[0] = remapped;
        }
        RENDER_BACKEND.textureParameterfv(texture, dsaTargetOf(texture), pname, FloatBuffer.wrap(params));
    }

    public static void glTextureParameterIiv(int texture, int pname, int[] params) {
        RENDER_BACKEND.textureParameterIiv(texture, dsaTargetOf(texture), pname, IntBuffer.wrap(params));
    }

    public static void glTextureParameterIuiv(int texture, int pname, int[] params) {
        RENDER_BACKEND.textureParameterIuiv(texture, dsaTargetOf(texture), pname, IntBuffer.wrap(params));
    }

    public static void glTexStorage3D(int target, int levels, int internalFormat, int width, int height, int depth) {
        final int texture = getBoundTextureForGpuDiagnostics();
        recordGpuCommand(GpuCommandType.TEX_STORAGE_3D, GpuCommandPhase.BEGIN, texture, packDimensions(width, height));
        RENDER_BACKEND.texStorage3D(target, levels, internalFormat, width, height, depth);
        recordGpuCommand(GpuCommandType.TEX_STORAGE_3D, GpuCommandPhase.END, texture, packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_STORAGE_3D);
    }

    public static void glTextureStorage3D(int texture, int levels, int internalFormat, int width, int height, int depth) {
        recordGpuCommand(GpuCommandType.TEX_STORAGE_3D, GpuCommandPhase.BEGIN, texture, packDimensions(width, height));
        RENDER_BACKEND.textureStorage3D(texture, levels, internalFormat, width, height, depth);
        recordGpuCommand(GpuCommandType.TEX_STORAGE_3D, GpuCommandPhase.END, texture, packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_STORAGE_3D);
    }

    public static void glTextureSubImage3D(int texture, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth, int format, int type, ByteBuffer pixels) {
        recordGpuCommand(GpuCommandType.TEX_SUB_IMAGE_3D, GpuCommandPhase.BEGIN, texture, packDimensions(width, height));
        suspendPixelUnpackBuffer();
        RENDER_BACKEND.textureSubImage3D(texture, level, xoffset, yoffset, zoffset, width, height, depth, format, type, pixels);
        restorePixelUnpackBuffer();
        recordGpuCommand(GpuCommandType.TEX_SUB_IMAGE_3D, GpuCommandPhase.END, texture, packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_SUB_IMAGE_3D);
    }

    public static void glCompressedTexImage2D(int target, int level, int internalFormat, int width, int height, int border, ByteBuffer pixels) {
        final int texture = getBoundTextureForGpuDiagnostics();
        recordGpuCommand(GpuCommandType.TEX_IMAGE_2D, GpuCommandPhase.BEGIN, texture, packDimensions(width, height));
        suspendPixelUnpackBuffer();
        RENDER_BACKEND.compressedTexImage2D(target, level, internalFormat, width, height, border, pixels);
        restorePixelUnpackBuffer();
        recordGpuCommand(GpuCommandType.TEX_IMAGE_2D, GpuCommandPhase.END, texture, packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_IMAGE_2D);
    }

    public static void glCompressedTexImage2D(int target, int level, int internalFormat, int width, int height, int border, int imageSize, long data) {
        final int texture = getBoundTextureForGpuDiagnostics();
        recordGpuCommand(GpuCommandType.TEX_IMAGE_2D, GpuCommandPhase.BEGIN, texture, packDimensions(width, height));
        suspendPixelUnpackBuffer();
        RENDER_BACKEND.compressedTexImage2D(target, level, internalFormat, width, height, border, imageSize, data);
        restorePixelUnpackBuffer();
        recordGpuCommand(GpuCommandType.TEX_IMAGE_2D, GpuCommandPhase.END, texture, packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_IMAGE_2D);
    }

    public static void glCompressedTexImage3D(int target, int level, int internalFormat, int width, int height, int depth, int border, ByteBuffer pixels) {
        final int texture = getBoundTextureForGpuDiagnostics();
        recordGpuCommand(GpuCommandType.TEX_IMAGE_3D, GpuCommandPhase.BEGIN, texture, packDimensions(width, height));
        suspendPixelUnpackBuffer();
        RENDER_BACKEND.compressedTexImage3D(target, level, internalFormat, width, height, depth, border, pixels);
        restorePixelUnpackBuffer();
        recordGpuCommand(GpuCommandType.TEX_IMAGE_3D, GpuCommandPhase.END, texture, packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_IMAGE_3D);
    }

    public static void glCompressedTexSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth, int format, ByteBuffer pixels) {
        final int texture = getBoundTextureForGpuDiagnostics();
        recordGpuCommand(GpuCommandType.TEX_SUB_IMAGE_3D, GpuCommandPhase.BEGIN, texture, packDimensions(width, height));
        suspendPixelUnpackBuffer();
        RENDER_BACKEND.compressedTexSubImage3D(target, level, xoffset, yoffset, zoffset, width, height, depth, format, pixels);
        restorePixelUnpackBuffer();
        recordGpuCommand(GpuCommandType.TEX_SUB_IMAGE_3D, GpuCommandPhase.END, texture, packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_SUB_IMAGE_3D);
    }

    public static void glCompressedTextureSubImage3D(int texture, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth, int format, ByteBuffer pixels) {
        recordGpuCommand(GpuCommandType.TEX_SUB_IMAGE_3D, GpuCommandPhase.BEGIN, texture, packDimensions(width, height));
        suspendPixelUnpackBuffer();
        RENDER_BACKEND.compressedTextureSubImage3D(texture, level, xoffset, yoffset, zoffset, width, height, depth, format, pixels);
        restorePixelUnpackBuffer();
        recordGpuCommand(GpuCommandType.TEX_SUB_IMAGE_3D, GpuCommandPhase.END, texture, packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_SUB_IMAGE_3D);
    }

    public static void glCopyTextureSubImage3D(int texture, int level, int xoffset, int yoffset, int zoffset, int x, int y, int width, int height) {
        recordGpuCommand(GpuCommandType.COPY_TEX_SUB_IMAGE_2D, GpuCommandPhase.BEGIN, texture, packDimensions(width, height));
        RENDER_BACKEND.copyTextureSubImage3D(texture, level, xoffset, yoffset, zoffset, x, y, width, height);
        recordGpuCommand(GpuCommandType.COPY_TEX_SUB_IMAGE_2D, GpuCommandPhase.END, texture, packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.COPY_TEX_SUB_IMAGE_2D);
    }

    public static void glGenerateTextureMipmap(int texture) {
        recordGpuCommand(GpuCommandType.GENERATE_MIPMAP, GpuCommandPhase.BEGIN, texture, 0);
        RENDER_BACKEND.generateTextureMipmap(texture);
        recordGpuCommand(GpuCommandType.GENERATE_MIPMAP, GpuCommandPhase.END, texture, 0);
        gpuCheckpoint(GpuCommandType.GENERATE_MIPMAP);
    }

    public static int glGetTextureLevelParameteri(int texture, int level, int pname) {
        return RENDER_BACKEND.getTextureLevelParameteri(texture, level, pname);
    }

    public static float glGetTextureLevelParameterf(int texture, int level, int pname) {
        return RENDER_BACKEND.getTextureLevelParameteri(texture, level, pname);
    }

    public static void glGetTextureImage(int texture, int level, int format, int type, ByteBuffer pixels) {
        suspendPixelPackBuffer();
        RENDER_BACKEND.getTextureImage(texture, level, format, type, pixels);
        restorePixelPackBuffer();
    }

    public static void glGetCompressedTextureImage(int texture, int level, ByteBuffer pixels) {
        suspendPixelPackBuffer();
        RENDER_BACKEND.getCompressedTextureImage(texture, level, pixels);
        restorePixelPackBuffer();
    }

    public static void glGetCompressedTexImage(int target, int level, ByteBuffer pixels) {
        suspendPixelPackBuffer();
        RENDER_BACKEND.getCompressedTexImage(target, level, pixels);
        restorePixelPackBuffer();
    }

            public static int glGenSamplers() { return GLSMTextures.glGenSamplers(); }
            public static void glDeleteSamplers(int sampler) { GLSMTextures.glDeleteSamplers(sampler); }
            public static void glBindSampler(int unit, int sampler) { GLSMTextures.glBindSampler(unit, sampler); }
    public static void glSamplerParameteri(int sampler, int pname, int param) { RENDER_BACKEND.samplerParameteri(sampler, pname, param); }
    public static void glSamplerParameterf(int sampler, int pname, float param) { RENDER_BACKEND.samplerParameterf(sampler, pname, param); }

    static void glMaterialFront(int pname, FloatBuffer params) {
        switch (pname) {
            case GL11.GL_AMBIENT -> ctx().frontMaterial.setAmbient(params);
            case GL11.GL_DIFFUSE -> ctx().frontMaterial.setDiffuse(params);
            case GL11.GL_SPECULAR -> ctx().frontMaterial.setSpecular(params);
            case GL11.GL_EMISSION -> ctx().frontMaterial.setEmission(params);
            case GL11.GL_SHININESS -> ctx().frontMaterial.setShininess(params);
            case GL11.GL_AMBIENT_AND_DIFFUSE -> {
                ctx().frontMaterial.setAmbient(params);
                ctx().frontMaterial.setDiffuse(params);
            }
            case GL11.GL_COLOR_INDEXES -> ctx().frontMaterial.setColorIndexes(params);
        }
    }

    static void glMaterialBack(int pname, FloatBuffer params) {
        switch (pname) {
            case GL11.GL_AMBIENT -> ctx().backMaterial.setAmbient(params);
            case GL11.GL_DIFFUSE -> ctx().backMaterial.setDiffuse(params);
            case GL11.GL_SPECULAR -> ctx().backMaterial.setSpecular(params);
            case GL11.GL_EMISSION -> ctx().backMaterial.setEmission(params);
            case GL11.GL_SHININESS -> ctx().backMaterial.setShininess(params);
            case GL11.GL_AMBIENT_AND_DIFFUSE -> {
                ctx().backMaterial.setAmbient(params);
                ctx().backMaterial.setDiffuse(params);
            }
            case GL11.GL_COLOR_INDEXES -> ctx().backMaterial.setColorIndexes(params);
        }
    }

    static void glMaterialFront(int pname, IntBuffer params) {
        switch (pname) {
            case GL11.GL_AMBIENT -> ctx().frontMaterial.setAmbient(params);
            case GL11.GL_DIFFUSE -> ctx().frontMaterial.setDiffuse(params);
            case GL11.GL_SPECULAR -> ctx().frontMaterial.setSpecular(params);
            case GL11.GL_EMISSION -> ctx().frontMaterial.setEmission(params);
            case GL11.GL_SHININESS -> ctx().frontMaterial.setShininess(params);
            case GL11.GL_AMBIENT_AND_DIFFUSE -> {
                ctx().frontMaterial.setAmbient(params);
                ctx().frontMaterial.setDiffuse(params);
            }
            case GL11.GL_COLOR_INDEXES -> ctx().frontMaterial.setColorIndexes(params);
        }
    }

    static void glMaterialBack(int pname, IntBuffer params) {
        switch (pname) {
            case GL11.GL_AMBIENT -> ctx().backMaterial.setAmbient(params);
            case GL11.GL_DIFFUSE -> ctx().backMaterial.setDiffuse(params);
            case GL11.GL_SPECULAR -> ctx().backMaterial.setSpecular(params);
            case GL11.GL_EMISSION -> ctx().backMaterial.setEmission(params);
            case GL11.GL_SHININESS -> ctx().backMaterial.setShininess(params);
            case GL11.GL_AMBIENT_AND_DIFFUSE -> {
                ctx().backMaterial.setAmbient(params);
                ctx().backMaterial.setDiffuse(params);
            }
            case GL11.GL_COLOR_INDEXES -> ctx().backMaterial.setColorIndexes(params);
        }
    }

    public static void glMaterial(int face, int pname, FloatBuffer params) { GLSMLighting.glMaterial(face, pname, params); }

    public static void glMaterial(int face, int pname, IntBuffer params) { GLSMLighting.glMaterial(face, pname, params); }

    public static void glMaterialf(int face, int pname, float val) { GLSMLighting.glMaterialf(face, pname, val); }

            public static void glMateriali(int face, int pname, int val) { GLSMLighting.glMateriali(face, pname, val); }

    public static void glLight(int light, int pname, FloatBuffer params) { GLSMLighting.glLight(light, pname, params); }

    public static void glLight(int light, int pname, IntBuffer params) { GLSMLighting.glLight(light, pname, params); }

    public static void glLightf(int light, int pname, float param) { GLSMLighting.glLightf(light, pname, param); }

    public static void glLighti(int light, int pname, int param) { GLSMLighting.glLighti(light, pname, param); }

    public static void glLightModel(int pname, FloatBuffer params) { GLSMLighting.glLightModel(pname, params); }

    public static void glLightModel(int pname, IntBuffer params) { GLSMLighting.glLightModel(pname, params); }

    public static void glLightModelf(int pname, float param) { GLSMLighting.glLightModelf(pname, param); }

    public static void glLightModeli(int pname, int param) { GLSMLighting.glLightModeli(pname, param); }

    public static void glColorMaterial(int face, int mode) { GLSMLighting.glColorMaterial(face, mode); }

            public static void glDepthRange(double near, double far) { GLSMDepthStencilAlpha.glDepthRange(near, far); }

    public static void glUseProgram(int program) { GLSMPrograms.glUseProgram(program); }

    // Missing GL commands from Mesa cross-check
            public static void glTexImage1D(int target, int level, int internalformat, int width, int border, int format, int type, ByteBuffer pixels) { GLSMTextures.glTexImage1D(target, level, internalformat, width, border, format, type, pixels); }

            public static void glTexImage3D(int target, int level, int internalformat, int width, int height, int depth, int border, int format, int type, ByteBuffer pixels) { GLSMTextures.glTexImage3D(target, level, internalformat, width, height, depth, border, format, type, pixels); }

            public static void glTexImage3D(int target, int level, int internalformat, int width, int height, int depth, int border, int format, int type, IntBuffer pixels) { GLSMTextures.glTexImage3D(target, level, internalformat, width, height, depth, border, format, type, pixels); }

            public static void glTexSubImage1D(int target, int level, int xoffset, int width, int format, int type, ByteBuffer pixels) { GLSMTextures.glTexSubImage1D(target, level, xoffset, width, format, type, pixels); }

    public static void glLineWidth(float width) { GLSMRasterizer.glLineWidth(width); }

    public static void prepareWideLineEmulation(int drawMode) {
        final GLContextState glCtx = ctx();
        final boolean isLine = drawMode == GL11.GL_LINES || drawMode == GL11.GL_LINE_STRIP || drawMode == GL11.GL_LINE_LOOP;
        wideLineEmulationActive = wideLineEmulationEnabled && isLine && glCtx.lineState.getWidth() > 1.0f;
        setLineStippleActive(isLine && glCtx.lineStippleState.isEnabled());
    }

    /**
     * Line stipple emulation feeds a flat varying from each segment's first vertex, so the
     * provoking vertex convention must flip while stippled lines are drawn (GL default is
     * LAST_VERTEX_CONVENTION). Only touched on transitions to avoid redundant driver calls.
     */
    static void setLineStippleActive(boolean active) {
        if (lineStippleActive != active) {
            lineStippleActive = active;
            RENDER_BACKEND.provokingVertex(active ? GL32.GL_FIRST_VERTEX_CONVENTION : GL32.GL_LAST_VERTEX_CONVENTION);
        }
    }

            public static void glFlush() { GLSMDraws.glFlush(); }

            public static void glFinish() { GLSMDraws.glFinish(); }

    public static int glGetError() { return GLSMQueries.glGetError(); }

    /** LWJGL2 {@code Util.checkGLError} semantics: throw on any pending GL error. */
            public static void checkGLError() { GLSMQueries.checkGLError(); }

    /**
     * Redirect target for {@code GLContext.getCapabilities()} call sites: the ASM redirector
     * rewrites them here so a backend can substitute its own capabilities view. The GL backend
     * has no override, so this is a plain passthrough.
     */
    public static ContextCapabilities currentContextCapabilities() {
        return GLContext.getCapabilities();
    }

    public static String glGetString(int pname) { return GLSMQueries.glGetString(pname); }

    public static String glGetStringi(int name, int index) { return GLSMQueries.glGetStringi(name, index); }

    public static void glShaderSource(int shader, CharSequence source) { GLSMPrograms.glShaderSource(shader, source); }

    public static void glShaderSource(int shader, ByteBuffer source) { GLSMPrograms.glShaderSource(shader, source); }

    static String decodeShaderSourceString(long strings, long lengths, int i) {
        final long strAddr = MemoryUtilities.memGetAddress(strings + (long) i * Pointer.POINTER_SIZE);
        final int len = lengths == MemoryUtilities.NULL ? -1 : MemoryUtilities.memGetInt(lengths + (long) i * 4);
        return len < 0 ? MemoryUtilities.memUTF8(strAddr) : MemoryUtilities.memUTF8(strAddr, len);
    }

            public static void nglShaderSource(int shader, int count, long strings, long lengths) { GLSMPrograms.nglShaderSource(shader, count, strings, lengths); }

    public static void glShaderSource(int shader, CharSequence[] sources) { GLSMPrograms.glShaderSource(shader, sources); }

    /** Check shader type to determine if fragment output transformation is needed. */
    static boolean isFragmentShader(int shader) {
        return RENDER_BACKEND.getShaderi(shader, GL20.GL_SHADER_TYPE) == GL20.GL_FRAGMENT_SHADER;
    }

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels) { GLSMTextures.glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels); }

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, IntBuffer pixels) { GLSMTextures.glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels); }

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, long pixels_buffer_offset) { GLSMTextures.glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels_buffer_offset); }

            public static void glTexSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth, int format, int type, ByteBuffer pixels) { GLSMTextures.glTexSubImage3D(target, level, xoffset, yoffset, zoffset, width, height, depth, format, type, pixels); }

            public static void glCopyTexImage1D(int target, int level, int internalFormat, int x, int y, int width, int border) { GLSMTextures.glCopyTexImage1D(target, level, internalFormat, x, y, width, border); }

    public static void glCopyTexImage2D(int target, int level, int internalFormat, int x, int y, int width, int height, int border) { GLSMTextures.glCopyTexImage2D(target, level, internalFormat, x, y, width, height, border); }

            public static void glCopyTexSubImage1D(int target, int level, int xoffset, int x, int y, int width) { GLSMTextures.glCopyTexSubImage1D(target, level, xoffset, x, y, width); }

    public static void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) { GLSMTextures.glCopyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height); }

            public static void glCopyTexSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset, int x, int y, int width, int height) { GLSMTextures.glCopyTexSubImage3D(target, level, xoffset, yoffset, zoffset, x, y, width, height); }

    public static void glCullFace(int mode) { GLSMRasterizer.glCullFace(mode); }

    public static void glFrontFace(int mode) { GLSMRasterizer.glFrontFace(mode); }

    public static void glHint(int target, int hint) { GLSMRasterizer.glHint(target, hint); }

    /** Whether {@code glHint} accepts the target on the core profile. */
    static boolean isDriverHint(int target) {
        return switch (target) {
            case GL11.GL_PERSPECTIVE_CORRECTION_HINT, GL11.GL_POINT_SMOOTH_HINT, GL11.GL_FOG_HINT,
                 GL14.GL_GENERATE_MIPMAP_HINT -> false;
            default -> true;
        };
    }

    /** Bit offset of a hint target in the packed {@link GLContextState#hintState}, or -1 when untracked. */
    static int hintShift(int target) {
        return switch (target) {
            case GL11.GL_PERSPECTIVE_CORRECTION_HINT -> 0;
            case GL11.GL_POINT_SMOOTH_HINT -> 2;
            case GL11.GL_LINE_SMOOTH_HINT -> 4;
            case GL11.GL_POLYGON_SMOOTH_HINT -> 6;
            case GL11.GL_FOG_HINT -> 8;
            case GL13.GL_TEXTURE_COMPRESSION_HINT -> 10;
            case GL14.GL_GENERATE_MIPMAP_HINT -> 12;
            case GL20.GL_FRAGMENT_SHADER_DERIVATIVE_HINT -> 14;
            default -> -1;
        };
    }

    static boolean isHintMode(int hint) {
        return hint == GL11.GL_FASTEST || hint == GL11.GL_NICEST || hint == GL11.GL_DONT_CARE;
    }

    /** Hint mode stored for {@code target} in the packed hint state. */
    static int hintMode(GLContextState glCtx, int target) {
        final int shift = hintShift(target);
        if (shift < 0) {
            throw new IllegalArgumentException("Pname 0x" + Integer.toHexString(target) + " is not a packed hint target");
        }
        return switch (glCtx.hintState.getValue() >>> shift & 3) {
            case 1 -> GL11.GL_FASTEST;
            case 2 -> GL11.GL_NICEST;
            default -> GL11.GL_DONT_CARE;
        };
    }

    private static final int[] DRIVER_HINTS = {
        GL11.GL_LINE_SMOOTH_HINT, GL11.GL_POLYGON_SMOOTH_HINT, GL13.GL_TEXTURE_COMPRESSION_HINT, GL20.GL_FRAGMENT_SHADER_DERIVATIVE_HINT
    };

    static void applyHintState(GLContextState glCtx) {
        for (final int target : DRIVER_HINTS) {
            RENDER_BACKEND.hint(target, hintMode(glCtx, target));
        }
    }

    public static void glLineStipple(int factor, short pattern) { GLSMRasterizer.glLineStipple(factor, pattern); }

    public static void glPointSize(float size) { GLSMRasterizer.glPointSize(size); }

    public static void glPolygonMode(int face, int polygonMode) { GLSMRasterizer.glPolygonMode(face, polygonMode); }

    public static void glPolygonOffset(float factor, float units) { GLSMRasterizer.glPolygonOffset(factor, units); }

            public static void glPolygonOffsetClamp(float factor, float units, float clamp) { GLSMRasterizer.glPolygonOffsetClamp(factor, units, clamp); }

    public static void glReadBuffer(int mode) { GLSMFramebuffer.glReadBuffer(mode); }

    public static void glScissor(int x, int y, int width, int height) { GLSMRasterizer.glScissor(x, y, width, height); }

    public static void glStencilFunc(int func, int ref, int mask) { GLSMDepthStencilAlpha.glStencilFunc(func, ref, mask); }

    public static void glStencilMask(int mask) { GLSMDepthStencilAlpha.glStencilMask(mask); }

    public static void glStencilOp(int fail, int zfail, int zpass) { GLSMDepthStencilAlpha.glStencilOp(fail, zfail, zpass); }

    public static void glPixelStorei(int pname, int param) { GLSMTextures.glPixelStorei(pname, param); }

            public static void glPixelStoref(int pname, float param) { GLSMTextures.glPixelStoref(pname, param); }

            public static void glCallLists(IntBuffer lists) { GLSMDraws.glCallLists(lists); }

            public static void glCallLists(ShortBuffer lists) { GLSMDraws.glCallLists(lists); }

            public static void glCallLists(ByteBuffer lists) { GLSMDraws.glCallLists(lists); }

            public static void glListBase(int base) { GLSMDraws.glListBase(base); }

    public static void glClipPlane(int plane, DoubleBuffer equation) { GLSMRasterizer.glClipPlane(plane, equation); }

    /** Returns true if any GL_CLIP_PLANE0..7 is currently enabled. */
            public static boolean anyClipPlaneEnabled() { return GLSMRasterizer.anyClipPlaneEnabled(); }

    public static void glClearStencil(int s) { GLSMDepthStencilAlpha.glClearStencil(s); }

    // Draw Buffer Commands (GL 2.0+)
    public static void glDrawBuffers(int buffer) { GLSMFramebuffer.glDrawBuffers(buffer); }

    public static void glDrawBuffers(IntBuffer bufs) { GLSMFramebuffer.glDrawBuffers(bufs); }

    public static void glDrawBuffers(int[] bufs) { GLSMFramebuffer.glDrawBuffers(bufs); }

            public static void glSampleCoverage(float value, boolean invert) { GLSMRasterizer.glSampleCoverage(value, invert); }

    // Stencil Separate Functions (GL 2.0+)
    public static void glStencilFuncSeparate(int face, int func, int ref, int mask) { GLSMDepthStencilAlpha.glStencilFuncSeparate(face, func, ref, mask); }

    public static void glStencilMaskSeparate(int face, int mask) { GLSMDepthStencilAlpha.glStencilMaskSeparate(face, mask); }

    public static void glStencilOpSeparate(int face, int sfail, int dpfail, int dppass) { GLSMDepthStencilAlpha.glStencilOpSeparate(face, sfail, dpfail, dppass); }

    public static void glDeleteBuffers(int buffer) { GLSMBuffers.glDeleteBuffers(buffer); }

    public static void glDeleteBuffers(IntBuffer buffers) { GLSMBuffers.glDeleteBuffers(buffers); }

    public static void glDeleteBuffers(int[] buffers) { GLSMBuffers.glDeleteBuffers(buffers); }

    static void invalidateDeletedBuffer(int buffer) {
        if (buffer == 0) return;
        final GLContextState glCtx = ctx();
        if (glCtx.boundVBO == buffer) {
            glCtx.boundVBO = 0;
        }
        glCtx.vaos.onDeleteBuffer(buffer);
    }

    public static void glBindBuffer(int target, int buffer) { GLSMBuffers.glBindBuffer(target, buffer); }

    static void suspendPixelUnpackBuffer() {
        if (ctx().boundPixelUnpackBuffer != 0) {
            RENDER_BACKEND.bindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        }
    }

    static void restorePixelUnpackBuffer() {
        if (ctx().boundPixelUnpackBuffer != 0) {
            RENDER_BACKEND.bindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, ctx().boundPixelUnpackBuffer);
        }
    }

    static void suspendPixelPackBuffer() {
        if (ctx().boundPixelPackBuffer != 0) {
            RENDER_BACKEND.bindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        }
    }

    static void restorePixelPackBuffer() {
        if (ctx().boundPixelPackBuffer != 0) {
            RENDER_BACKEND.bindBuffer(GL21.GL_PIXEL_PACK_BUFFER, ctx().boundPixelPackBuffer);
        }
    }

    public static int glGenBuffers() { return GLSMBuffers.glGenBuffers(); }

    public static void glGenBuffers(IntBuffer buffers) { GLSMBuffers.glGenBuffers(buffers); }

    public static void glGenBuffers(int[] buffers) { GLSMBuffers.glGenBuffers(buffers); }

            public static int glCreateBuffers() { return GLSMBuffers.glCreateBuffers(); }

            public static void glCreateBuffers(IntBuffer buffers) { GLSMBuffers.glCreateBuffers(buffers); }

            public static void glCreateBuffers(int[] buffers) { GLSMBuffers.glCreateBuffers(buffers); }

    public static void glBufferData(int target, long size, int usage) { GLSMBuffers.glBufferData(target, size, usage); }
    public static void glBufferData(int target, int[] data, int usage) { GLSMBuffers.glBufferData(target, data, usage); }
    public static void glBufferData(int target, float[] data, int usage) { GLSMBuffers.glBufferData(target, data, usage); }
    public static void glBufferData(int target, java.nio.ByteBuffer data, int usage) { GLSMBuffers.glBufferData(target, data, usage); }
    public static void glBufferData(int target, java.nio.ShortBuffer data, int usage) { GLSMBuffers.glBufferData(target, data, usage); }
    public static void glBufferData(int target, java.nio.IntBuffer data, int usage) { GLSMBuffers.glBufferData(target, data, usage); }
    public static void glBufferData(int target, java.nio.FloatBuffer data, int usage) { GLSMBuffers.glBufferData(target, data, usage); }
    public static void glBufferData(int target, java.nio.DoubleBuffer data, int usage) { GLSMBuffers.glBufferData(target, data, usage); }
    public static void glBufferData(int target, LongBuffer data, int usage) { GLSMBuffers.glBufferData(target, data, usage); }
    public static void glBufferData(int target, short[] data, int usage) { GLSMBuffers.glBufferData(target, data, usage); }
    public static void glBufferData(int target, long[] data, int usage) { GLSMBuffers.glBufferData(target, data, usage); }
    public static void glBufferData(int target, double[] data, int usage) { GLSMBuffers.glBufferData(target, data, usage); }
            public static void nglBufferData(int target, long size, long data, int usage) { GLSMBuffers.nglBufferData(target, size, data, usage); }
    public static void glBufferSubData(int target, long offset, java.nio.ByteBuffer data) { GLSMBuffers.glBufferSubData(target, offset, data); }
    public static void glBufferSubData(int target, long offset, java.nio.ShortBuffer data) { GLSMBuffers.glBufferSubData(target, offset, data); }
    public static void glBufferSubData(int target, long offset, java.nio.IntBuffer data) { GLSMBuffers.glBufferSubData(target, offset, data); }
    public static void glBufferSubData(int target, long offset, java.nio.FloatBuffer data) { GLSMBuffers.glBufferSubData(target, offset, data); }
    public static void glBufferSubData(int target, long offset, java.nio.DoubleBuffer data) { GLSMBuffers.glBufferSubData(target, offset, data); }
    public static void glBufferSubData(int target, long offset, LongBuffer data) { GLSMBuffers.glBufferSubData(target, offset, data); }
    public static void glBufferSubData(int target, long offset, short[] data) { GLSMBuffers.glBufferSubData(target, offset, data); }
    public static void glBufferSubData(int target, long offset, int[] data) { GLSMBuffers.glBufferSubData(target, offset, data); }
    public static void glBufferSubData(int target, long offset, long[] data) { GLSMBuffers.glBufferSubData(target, offset, data); }
    public static void glBufferSubData(int target, long offset, float[] data) { GLSMBuffers.glBufferSubData(target, offset, data); }
    public static void glBufferSubData(int target, long offset, double[] data) { GLSMBuffers.glBufferSubData(target, offset, data); }
    public static void nglBufferSubData(int target, long offset, long size, long data) { glBufferSubData(target, offset, MemoryUtilities.memByteBuffer(data, checkedSize(size))); }
            public static ByteBuffer glMapBuffer(int target, int access) { return GLSMBuffers.glMapBuffer(target, access); }
            public static ByteBuffer glMapBuffer(int target, int access, ByteBuffer old_buffer) { return GLSMBuffers.glMapBuffer(target, access, old_buffer); }
            public static ByteBuffer glMapBuffer(int target, int access, long length, ByteBuffer old_buffer) { return GLSMBuffers.glMapBuffer(target, access, length, old_buffer); }
            public static ByteBuffer glMapBufferRange(int target, long offset, long length, int access) { return GLSMBuffers.glMapBufferRange(target, offset, length, access); }
            public static ByteBuffer glMapBufferRange(int target, long offset, long length, int access, ByteBuffer old_buffer) { return GLSMBuffers.glMapBufferRange(target, offset, length, access, old_buffer); }
            public static long nglMapBuffer(int target, int access) { return GLSMBuffers.nglMapBuffer(target, access); }
            public static boolean glUnmapBuffer(int target) { return GLSMBuffers.glUnmapBuffer(target); }
    public static void glGetBufferSubData(int target, long offset, java.nio.ByteBuffer data) { GLSMBuffers.glGetBufferSubData(target, offset, data); }
    public static void glGetBufferSubData(int target, long offset, java.nio.ShortBuffer data) { GLSMBuffers.glGetBufferSubData(target, offset, data); }
    public static void glGetBufferSubData(int target, long offset, java.nio.IntBuffer data) { GLSMBuffers.glGetBufferSubData(target, offset, data); }
    public static void glGetBufferSubData(int target, long offset, java.nio.FloatBuffer data) { GLSMBuffers.glGetBufferSubData(target, offset, data); }
    public static void glGetBufferSubData(int target, long offset, java.nio.DoubleBuffer data) { GLSMBuffers.glGetBufferSubData(target, offset, data); }
    public static void glGetBufferSubData(int target, long offset, LongBuffer data) { GLSMBuffers.glGetBufferSubData(target, offset, data); }
    public static void glGetBufferSubData(int target, long offset, short[] data) { GLSMBuffers.glGetBufferSubData(target, offset, data); }
    public static void glGetBufferSubData(int target, long offset, int[] data) { GLSMBuffers.glGetBufferSubData(target, offset, data); }
    public static void glGetBufferSubData(int target, long offset, long[] data) { GLSMBuffers.glGetBufferSubData(target, offset, data); }
    public static void glGetBufferSubData(int target, long offset, float[] data) { GLSMBuffers.glGetBufferSubData(target, offset, data); }
    public static void glGetBufferSubData(int target, long offset, double[] data) { GLSMBuffers.glGetBufferSubData(target, offset, data); }
    public static void nglGetBufferSubData(int target, long offset, long size, long data) { glGetBufferSubData(target, offset, MemoryUtilities.memByteBuffer(data, checkedSize(size))); }
    public static int glGetBufferParameteri(int target, int pname) { return GLSMBuffers.glGetBufferParameteri(target, pname); }
    public static int glGetBufferParameter(int target, int pname) { return glGetBufferParameteri(target, pname); }
    public static void glGetBufferParameter(int target, int pname, IntBuffer params) { params.put(params.position(), glGetBufferParameteri(target, pname)); }
            public static void glGetBufferParameteriv(int target, int pname, IntBuffer params) { GLSMBuffers.glGetBufferParameteriv(target, pname, params); }
            public static void glGetBufferParameteriv(int target, int pname, int[] params) { GLSMBuffers.glGetBufferParameteriv(target, pname, params); }
            public static void glBufferStorage(int target, java.nio.ByteBuffer data, int flags) { GLSMBuffers.glBufferStorage(target, data, flags); }
            public static void glBufferStorage(int target, long size, int flags) { GLSMBuffers.glBufferStorage(target, size, flags); }
            public static void glBufferStorage(int target, ShortBuffer data, int flags) { GLSMBuffers.glBufferStorage(target, data, flags); }
            public static void glBufferStorage(int target, IntBuffer data, int flags) { GLSMBuffers.glBufferStorage(target, data, flags); }
            public static void glBufferStorage(int target, FloatBuffer data, int flags) { GLSMBuffers.glBufferStorage(target, data, flags); }
            public static void glBufferStorage(int target, DoubleBuffer data, int flags) { GLSMBuffers.glBufferStorage(target, data, flags); }
            public static void glBufferStorage(int target, short[] data, int flags) { GLSMBuffers.glBufferStorage(target, data, flags); }
            public static void glBufferStorage(int target, int[] data, int flags) { GLSMBuffers.glBufferStorage(target, data, flags); }
            public static void glBufferStorage(int target, float[] data, int flags) { GLSMBuffers.glBufferStorage(target, data, flags); }
            public static void glBufferStorage(int target, double[] data, int flags) { GLSMBuffers.glBufferStorage(target, data, flags); }

            public static void glNamedBufferData(int buffer, long size, int usage) { GLSMBuffers.glNamedBufferData(buffer, size, usage); }
            public static void glNamedBufferData(int buffer, ByteBuffer data, int usage) { GLSMBuffers.glNamedBufferData(buffer, data, usage); }
            public static void glNamedBufferData(int buffer, FloatBuffer data, int usage) { GLSMBuffers.glNamedBufferData(buffer, data, usage); }
            public static void glNamedBufferData(int buffer, ShortBuffer data, int usage) { GLSMBuffers.glNamedBufferData(buffer, data, usage); }
            public static void glNamedBufferData(int buffer, IntBuffer data, int usage) { GLSMBuffers.glNamedBufferData(buffer, data, usage); }
            public static void glNamedBufferData(int buffer, LongBuffer data, int usage) { GLSMBuffers.glNamedBufferData(buffer, data, usage); }
            public static void glNamedBufferData(int buffer, DoubleBuffer data, int usage) { GLSMBuffers.glNamedBufferData(buffer, data, usage); }
            public static void glNamedBufferData(int buffer, short[] data, int usage) { GLSMBuffers.glNamedBufferData(buffer, data, usage); }
            public static void glNamedBufferData(int buffer, int[] data, int usage) { GLSMBuffers.glNamedBufferData(buffer, data, usage); }
            public static void glNamedBufferData(int buffer, long[] data, int usage) { GLSMBuffers.glNamedBufferData(buffer, data, usage); }
            public static void glNamedBufferData(int buffer, float[] data, int usage) { GLSMBuffers.glNamedBufferData(buffer, data, usage); }
            public static void glNamedBufferData(int buffer, double[] data, int usage) { GLSMBuffers.glNamedBufferData(buffer, data, usage); }

            public static void glNamedBufferSubData(int buffer, long offset, ByteBuffer data) { GLSMBuffers.glNamedBufferSubData(buffer, offset, data); }
            public static void glNamedBufferSubData(int buffer, long offset, ShortBuffer data) { GLSMBuffers.glNamedBufferSubData(buffer, offset, data); }
            public static void glNamedBufferSubData(int buffer, long offset, IntBuffer data) { GLSMBuffers.glNamedBufferSubData(buffer, offset, data); }
            public static void glNamedBufferSubData(int buffer, long offset, LongBuffer data) { GLSMBuffers.glNamedBufferSubData(buffer, offset, data); }
            public static void glNamedBufferSubData(int buffer, long offset, FloatBuffer data) { GLSMBuffers.glNamedBufferSubData(buffer, offset, data); }
            public static void glNamedBufferSubData(int buffer, long offset, DoubleBuffer data) { GLSMBuffers.glNamedBufferSubData(buffer, offset, data); }
            public static void glNamedBufferSubData(int buffer, long offset, short[] data) { GLSMBuffers.glNamedBufferSubData(buffer, offset, data); }
            public static void glNamedBufferSubData(int buffer, long offset, int[] data) { GLSMBuffers.glNamedBufferSubData(buffer, offset, data); }
            public static void glNamedBufferSubData(int buffer, long offset, long[] data) { GLSMBuffers.glNamedBufferSubData(buffer, offset, data); }
            public static void glNamedBufferSubData(int buffer, long offset, float[] data) { GLSMBuffers.glNamedBufferSubData(buffer, offset, data); }
            public static void glNamedBufferSubData(int buffer, long offset, double[] data) { GLSMBuffers.glNamedBufferSubData(buffer, offset, data); }

            public static void nglMultiDrawElementsBaseVertex(int mode, long count, int type, long indices, int primcount, long basevertex) { GLSMDraws.nglMultiDrawElementsBaseVertex(mode, count, type, indices, primcount, basevertex); }

            static int checkedSize(long size) { return GLSMBuffers.checkedSize(size); }

    static ByteBuffer copyOf(short[] data) {
        final ByteBuffer bb = MemoryUtilities.memAlloc(data.length << 1);
        bb.asShortBuffer().put(data);
        return bb;
    }

    static ByteBuffer copyOf(int[] data) {
        final ByteBuffer bb = MemoryUtilities.memAlloc(data.length << 2);
        bb.asIntBuffer().put(data);
        return bb;
    }

    static ByteBuffer copyOf(long[] data) {
        final ByteBuffer bb = MemoryUtilities.memAlloc(data.length << 3);
        bb.asLongBuffer().put(data);
        return bb;
    }

    static ByteBuffer copyOf(float[] data) {
        final ByteBuffer bb = MemoryUtilities.memAlloc(data.length << 2);
        bb.asFloatBuffer().put(data);
        return bb;
    }

    static ByteBuffer copyOf(double[] data) {
        final ByteBuffer bb = MemoryUtilities.memAlloc(data.length << 3);
        bb.asDoubleBuffer().put(data);
        return bb;
    }
    public static void glClearBufferSubData(int target, int internalFormat, long offset, long size, int format, int type, java.nio.ByteBuffer data) { RENDER_BACKEND.clearBufferSubData(target, internalFormat, offset, size, format, type, data); }
    public static boolean glIsBuffer(int buffer) { return RENDER_BACKEND.isBuffer(buffer); }
    public static void glCopyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size) { RENDER_BACKEND.copyBufferSubData(readTarget, writeTarget, readOffset, writeOffset, size); }

    public static void glBindVertexArray(int array) { GLSMVertexInput.glBindVertexArray(array); }

    public static void glDeleteVertexArrays(int array) { GLSMVertexInput.glDeleteVertexArrays(array); }

    public static void glDeleteVertexArrays(IntBuffer arrays) { GLSMVertexInput.glDeleteVertexArrays(arrays); }

    public static void glDeleteVertexArrays(int[] arrays) { GLSMVertexInput.glDeleteVertexArrays(arrays); }

    public static int glGenVertexArrays() { return GLSMVertexInput.glGenVertexArrays(); }

    public static void glGenVertexArrays(IntBuffer arrays) { GLSMVertexInput.glGenVertexArrays(arrays); }

    public static void glGenVertexArrays(int[] arrays) { GLSMVertexInput.glGenVertexArrays(arrays); }

            public static boolean glIsVertexArray(int array) { return GLSMVertexInput.glIsVertexArray(array); }

            public static void glBindVertexArrayAPPLE(int array) { GLSMVertexInput.glBindVertexArrayAPPLE(array); }

    public static boolean isVBOBound() {
        return ctx().boundVBO != 0;
    }

    public static boolean vendorIsAMD() {
        return VENDOR == AMD;
    }

    public static boolean vendorIsIntel() {
        return VENDOR == INTEL;
    }

    public static boolean vendorIsMesa() {
        return VENDOR == MESA;
    }

    public static boolean vendorIsNVIDIA() {
        return VENDOR == NVIDIA;
    }


    public static void glBindFramebuffer(int target, int framebuffer) { GLSMFramebuffer.glBindFramebuffer(target, framebuffer); }

    public static void glDeleteFramebuffers(int framebuffer) { GLSMFramebuffer.glDeleteFramebuffers(framebuffer); }

    public static void glDeleteFramebuffers(IntBuffer framebuffers) { GLSMFramebuffer.glDeleteFramebuffers(framebuffers); }

    public static void glDeleteFramebuffers(int[] framebuffers) { GLSMFramebuffer.glDeleteFramebuffers(framebuffers); }

    public static int glGenFramebuffers() { return GLSMFramebuffer.glGenFramebuffers(); }

    public static void glGenFramebuffers(IntBuffer framebuffers) { GLSMFramebuffer.glGenFramebuffers(framebuffers); }

    public static void glGenFramebuffers(int[] framebuffers) { GLSMFramebuffer.glGenFramebuffers(framebuffers); }

    public static int glCheckFramebufferStatus(int target) { return GLSMFramebuffer.glCheckFramebufferStatus(target); }

    public static int glGetFramebufferAttachmentParameteri(int target, int attachment, int pname) { return GLSMFramebuffer.glGetFramebufferAttachmentParameteri(target, attachment, pname); }

    public static void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) { GLSMFramebuffer.glFramebufferTexture2D(target, attachment, textarget, texture, level); }

            public static void glFramebufferTexture(int target, int attachment, int texture, int level) { GLSMFramebuffer.glFramebufferTexture(target, attachment, texture, level); }

    public static int glGenRenderbuffers() { return GLSMFramebuffer.glGenRenderbuffers(); }

    public static void glGenRenderbuffers(IntBuffer renderbuffers) { GLSMFramebuffer.glGenRenderbuffers(renderbuffers); }

    public static void glGenRenderbuffers(int[] renderbuffers) { GLSMFramebuffer.glGenRenderbuffers(renderbuffers); }

    public static void glDeleteRenderbuffers(int renderbuffer) { RENDER_BACKEND.deleteRenderbuffers(renderbuffer); }

    public static void glDeleteRenderbuffers(IntBuffer renderbuffers) {
        for (int i = renderbuffers.position(); i < renderbuffers.limit(); i++) glDeleteRenderbuffers(renderbuffers.get(i));
    }

    public static void glDeleteRenderbuffers(int[] renderbuffers) {
        for (int renderbuffer : renderbuffers) glDeleteRenderbuffers(renderbuffer);
    }

    public static void glBindRenderbuffer(int target, int renderbuffer) { RENDER_BACKEND.bindRenderbuffer(target, renderbuffer); }

    public static void glRenderbufferStorage(int target, int internalformat, int width, int height) { RENDER_BACKEND.renderbufferStorage(target, internalformat, width, height); }

    public static void glRenderbufferStorageMultisample(int target, int samples, int internalformat, int width, int height) { RENDER_BACKEND.renderbufferStorageMultisample(target, samples, internalformat, width, height); }

    public static void glFramebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) { GLSMFramebuffer.glFramebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer); }

    public static boolean glIsTexture(int texture) { return RENDER_BACKEND.isTexture(texture); }

    public static boolean glIsFramebuffer(int framebuffer) { return RENDER_BACKEND.isFramebuffer(framebuffer); }

    public static boolean glIsRenderbuffer(int renderbuffer) { return RENDER_BACKEND.isRenderbuffer(renderbuffer); }

    public static boolean glIsSampler(int sampler) { return RENDER_BACKEND.isSampler(sampler); }

    public static boolean glIsQuery(int query) { return RENDER_BACKEND.isQuery(query); }

            public static boolean glIsProgram(int obj) { return GLSMPrograms.glIsProgram(obj); }

            public static boolean glIsShader(int obj) { return GLSMPrograms.glIsShader(obj); }

    public static long glFenceSync(int condition, int flags) { return RENDER_BACKEND.fenceSync(condition, flags); }

    public static int glClientWaitSync(long sync, int flags, long timeout) { return RENDER_BACKEND.clientWaitSync(sync, flags, timeout); }

            public static void glDeleteSync(long sync) { GLSMBuffers.glDeleteSync(sync); }

    public static void glWaitSync(long sync, int flags, long timeout) { RENDER_BACKEND.waitSync(sync, flags, timeout); }

    public static int glGetSynci(long sync, int pname, IntBuffer length) { return RENDER_BACKEND.getSynci(sync, pname, length); }

            public static void glGenQueries(IntBuffer ids) { GLSMQueries.glGenQueries(ids); }

            public static void glGenQueries(int[] ids) { GLSMQueries.glGenQueries(ids); }

            public static int glGenQueries() { return GLSMQueries.glGenQueries(); }

    public static void glDeleteQueries(int id) { RENDER_BACKEND.deleteQueries(id); }

    public static void glDeleteQueries(IntBuffer ids) {
        for (int i = ids.position(); i < ids.limit(); i++) glDeleteQueries(ids.get(i));
    }

    public static void glDeleteQueries(int[] ids) {
        for (int id : ids) glDeleteQueries(id);
    }

    public static void glBeginQuery(int target, int id) { RENDER_BACKEND.beginQuery(target, id); }

    public static void glEndQuery(int target) { RENDER_BACKEND.endQuery(target); }

    public static void glGetQueryObjectui(int id, int pname, IntBuffer params) { RENDER_BACKEND.getQueryObjectui(id, pname, params); }

    public static void glGetQueryObject(int id, int pname, IntBuffer params) { RENDER_BACKEND.getQueryObjecti(id, pname, params); }

    public static int glGetQueryObjectui(int id, int pname) { return RENDER_BACKEND.getQueryObjectui(id, pname); }

    public static int glGetQueryObjecti(int id, int pname) { return RENDER_BACKEND.getQueryObjecti(id, pname); }

    public static void glQueryCounter(int id, int target) { RENDER_BACKEND.queryCounter(id, target); }

    public static long glGetQueryObjectui64(int id, int pname) { return RENDER_BACKEND.getQueryObjectui64(id, pname); }

    public static void glGetQueryObjectui64(int id, int pname, LongBuffer params) {
        params.put(params.position(), RENDER_BACKEND.getQueryObjectui64(id, pname));
    }

            public static void glBindBufferBase(int target, int index, int buffer) { GLSMBuffers.glBindBufferBase(target, index, buffer); }

    public static int glGetUniformBlockIndex(int program, CharSequence name) { return RENDER_BACKEND.getUniformBlockIndex(program, name); }

    public static int glGetUniformBlockIndex(int program, ByteBuffer name) { return RENDER_BACKEND.getUniformBlockIndex(program, name); }

    public static void glUniformBlockBinding(int program, int blockIndex, int binding) { RENDER_BACKEND.uniformBlockBinding(program, blockIndex, binding); }

    public static void glFlushMappedBufferRange(int target, long offset, long length) { RENDER_BACKEND.flushMappedBufferRange(target, offset, length); }

    public static void glClearBufferData(int target, int internalformat, int format, int type, ByteBuffer data) { RENDER_BACKEND.clearBufferData(target, internalformat, format, type, data); }

            public static void glSpecializeShader(int shader, CharSequence entryPoint, IntBuffer constantIndex, IntBuffer constantValue) { GLSMPrograms.glSpecializeShader(shader, entryPoint, constantIndex, constantValue); }

    public static void glBindFragDataLocation(int program, int colorNumber, CharSequence name) { RENDER_BACKEND.bindFragDataLocation(program, colorNumber, name); }

            public static void glGetActiveAttrib(int program, int index, IntBuffer length, IntBuffer size, IntBuffer type, ByteBuffer name) { GLSMPrograms.glGetActiveAttrib(program, index, length, size, type, name); }

            public static void glGetActiveAttrib(int program, int index, int[] length, int[] size, int[] type, ByteBuffer name) { GLSMPrograms.glGetActiveAttrib(program, index, length, size, type, name); }

            public static String glGetActiveAttrib(int program, int index, int maxLength, IntBuffer sizeType) { return GLSMPrograms.glGetActiveAttrib(program, index, maxLength, sizeType); }

            public static String glGetActiveAttrib(int program, int index, int maxLength, IntBuffer size, IntBuffer type) { return GLSMPrograms.glGetActiveAttrib(program, index, maxLength, size, type); }

            public static String glGetActiveAttrib(int program, int index, IntBuffer size, IntBuffer type) { return GLSMPrograms.glGetActiveAttrib(program, index, size, type); }

            public static void glObjectLabel(int identifier, int name, CharSequence label) { GLSMDraws.glObjectLabel(identifier, name, label); }

    public static String glGetObjectLabel(int identifier, int name, int bufSize) { return RENDER_BACKEND.getObjectLabel(identifier, name); }

            public static void glPushDebugGroup(int source, int id, CharSequence message) { GLSMDraws.glPushDebugGroup(source, id, message); }

            public static void glPopDebugGroup() { GLSMDraws.glPopDebugGroup(); }

            public static void glDebugMessageInsert(int source, int type, int id, int severity, CharSequence buf) { GLSMDraws.glDebugMessageInsert(source, type, id, severity, buf); }

            public static void glDebugMessageControl(int source, int type, int severity, IntBuffer ids, boolean enabled) { GLSMDraws.glDebugMessageControl(source, type, severity, ids, enabled); }

            public static int glGetDebugMessageLog(int count, IntBuffer sources, IntBuffer types, IntBuffer ids, IntBuffer severities, IntBuffer lengths, ByteBuffer messageLog) { return GLSMDraws.glGetDebugMessageLog(count, sources, types, ids, severities, lengths, messageLog); }

            public static void registerDebugMessageListener(GLDebugMessageListener listener, long userParam) { GLSMDraws.registerDebugMessageListener(listener, userParam); }

    public static void glDebugMessageCallback(KHRDebugCallback callback) { GLSMDraws.glDebugMessageCallback(callback); }

    /**
     * Redirect target for {@code org.lwjgl.opengl.GLUtil.setupDebugMessageCallback(PrintStream)}.
     * GLUtil is core-excluded from transformation, so the call sites in mod classes (Distant
     * Horizons' debug logger) are rewritten to this entry point instead.
     */
    public static Callback setupDebugMessageCallback(PrintStream stream) {
        return GLUtil.setupDebugMessageCallback(stream);
    }

            public static void glGenerateMipmap(int target) { GLSMTextures.glGenerateMipmap(target); }

            public static void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) { GLSMFramebuffer.glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter); }

            public static void setActiveTexture(int textureUnit) { GLSMTextures.setActiveTexture(textureUnit); }

    public static void glMultiTexCoord2f(int target, float s, float t) { GLSMCurrentAttribs.glMultiTexCoord2f(target, s, t); }

            public static void glMultiTexCoord2d(int target, double s, double t) { GLSMCurrentAttribs.glMultiTexCoord2d(target, s, t); }

            public static void glMultiTexCoord2s(int target, short s, short t) { GLSMCurrentAttribs.glMultiTexCoord2s(target, s, t); }

            public static void setLightmapTextureCoords(int unit, float x, float y) { GLSMCurrentAttribs.setLightmapTextureCoords(unit, x, y); }

    private static void checkMismatch(int cap) {
        final int cached = GLSMQueries.glGetInteger(cap);
        final int actual = RENDER_BACKEND.getInteger(cap);
        if (actual != cached) {
            throw new IllegalStateException("GLSM Mismatch! cap=" + cap + " cached=" + cached + " actual=" + actual);
        }
    }

            public static boolean isFramebufferEnabled() { return GLSMFramebuffer.isFramebufferEnabled(); }

    static final boolean FFP_WARN_ON_UNSUPPORTED = Boolean.parseBoolean(System.getProperty("angelica.ffp.warnOnUnsupported", "false"));

    static final Set<String> warnedFFPFunctions = new HashSet<>();

    /**
     * Guards against FFP features not supported in core profile.
     * Default: throws UnsupportedOperationException.
     * With {@code -Dangelica.ffp.warnOnUnsupported=true}: warns once per function (diagnostic mode).
     * @return true (always — caller should skip the GL call)
     */
            static boolean guardUnsupportedFFP(String function, String explanation) { return GLSMDraws.guardUnsupportedFFP(function, explanation); }

    /**
     * In core profile, GL_CLAMP (0x2900) is removed. Remap to GL_CLAMP_TO_EDGE for wrap modes. Semantically equivalent when texture border is not used (border
     * textures are also removed in core).
     */
            public static int remapTexClamp(int pname, int param) { return GLSMTextures.remapTexClamp(pname, param); }

            public static float remapTexClamp(int pname, float param) { return GLSMTextures.remapTexClamp(pname, param); }

            public static void remapTexClampBuffer(int pname, IntBuffer params) { GLSMTextures.remapTexClampBuffer(pname, params); }

    /**
     * Entity glow outline. Mirrors vanilla GlStateManager.enableOutlineMode: switches the active texture unit's texenv to
     * GL_COMBINE wired so the fragment color is replaced by the given outline color, letting the entity render as a flat
     * silhouette. All state changes flow through the tracked glTexEnv/glTexEnvi paths, so FFP shaders pick them up.
     */
    public static void enableOutlineMode(int color) {
        final FloatBuffer envColor = BufferUtils.createFloatBuffer(4);
        envColor.put(0, (color >> 16 & 255) / 255.0F);
        envColor.put(1, (color >> 8 & 255) / 255.0F);
        envColor.put(2, (color & 255) / 255.0F);
        envColor.put(3, (color >> 24 & 255) / 255.0F);
        glTexEnv(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_COLOR, envColor);
        glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL13.GL_COMBINE);
        glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_RGB, GL13.GL_REPLACE);
        glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_RGB, GL13.GL_CONSTANT);
        glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND0_RGB, GL11.GL_SRC_COLOR);
        glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_ALPHA, GL13.GL_REPLACE);
        glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_ALPHA, GL13.GL_PREVIOUS);
        glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND0_ALPHA, GL11.GL_SRC_ALPHA);
    }

    /** Reverts enableOutlineMode to the default modulate texenv. */
    public static void disableOutlineMode() {
        glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_RGB, GL11.GL_MODULATE);
        glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_RGB, GL11.GL_TEXTURE);
        glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND0_RGB, GL11.GL_SRC_COLOR);
        glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_COMBINE_ALPHA, GL11.GL_MODULATE);
        glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_SOURCE0_ALPHA, GL11.GL_TEXTURE);
        glTexEnvi(GL11.GL_TEXTURE_ENV, GL13.GL_OPERAND0_ALPHA, GL11.GL_SRC_ALPHA);
    }

    public static void glTexEnvi(int target, int pname, int param) { GLSMTexEnvGen.glTexEnvi(target, pname, param); }

            public static void glTexEnvf(int target, int pname, float param) { GLSMTexEnvGen.glTexEnvf(target, pname, param); }

            public static void glTexEnv(int target, int pname, FloatBuffer params) { GLSMTexEnvGen.glTexEnv(target, pname, params); }

            public static void glTexEnv(int target, int pname, IntBuffer params) { GLSMTexEnvGen.glTexEnv(target, pname, params); }

    /**
     * Scalar glTexEnvi/glTexEnvf dispatch. glTexEnv is removed in core profile. GL_TEXTURE_FILTER_CONTROL (LOD bias) is remapped to glTexParameterf. All
     * GL_TEXTURE_ENV parameters including GL_COMBINE sub-params are tracked in per-unit TexEnvState.
     */
    static void handleTexEnvScalar(int target, int pname, float param) {
        if (target == GL14.GL_TEXTURE_FILTER_CONTROL) {
            // LOD bias via legacy glTexEnv path — remap to core-profile glTexParameterf
            glTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, param);
            return;
        }
        if (target != GL11.GL_TEXTURE_ENV) return;

        final var envState = ctx().textures.getTexEnvState(ctx().activeTextureUnit.getValue());
        envState.beforeModify();
        final int iparam = (int) param;

        switch (pname) {
            case GL11.GL_TEXTURE_ENV_MODE -> {
                envState.mode = iparam;
                ctx().fragmentGeneration++;
            }
            case GL13.GL_COMBINE_RGB -> {
                envState.combineRgb = iparam;
                ctx().fragmentGeneration++;
            }
            case GL13.GL_COMBINE_ALPHA -> {
                envState.combineAlpha = iparam;
                ctx().fragmentGeneration++;
            }
            case GL13.GL_SOURCE0_RGB -> {
                envState.sourceRgb[0] = iparam;
                ctx().fragmentGeneration++;
            }
            case GL13.GL_SOURCE1_RGB -> {
                envState.sourceRgb[1] = iparam;
                ctx().fragmentGeneration++;
            }
            case GL13.GL_SOURCE2_RGB -> {
                envState.sourceRgb[2] = iparam;
                ctx().fragmentGeneration++;
            }
            case GL13.GL_SOURCE0_ALPHA -> {
                envState.sourceAlpha[0] = iparam;
                ctx().fragmentGeneration++;
            }
            case GL13.GL_SOURCE1_ALPHA -> {
                envState.sourceAlpha[1] = iparam;
                ctx().fragmentGeneration++;
            }
            case GL13.GL_SOURCE2_ALPHA -> {
                envState.sourceAlpha[2] = iparam;
                ctx().fragmentGeneration++;
            }
            case GL13.GL_OPERAND0_RGB -> {
                envState.operandRgb[0] = iparam;
                ctx().fragmentGeneration++;
            }
            case GL13.GL_OPERAND1_RGB -> {
                envState.operandRgb[1] = iparam;
                ctx().fragmentGeneration++;
            }
            case GL13.GL_OPERAND2_RGB -> {
                envState.operandRgb[2] = iparam;
                ctx().fragmentGeneration++;
            }
            case GL13.GL_OPERAND0_ALPHA -> {
                envState.operandAlpha[0] = iparam;
                ctx().fragmentGeneration++;
            }
            case GL13.GL_OPERAND1_ALPHA -> {
                envState.operandAlpha[1] = iparam;
                ctx().fragmentGeneration++;
            }
            case GL13.GL_OPERAND2_ALPHA -> {
                envState.operandAlpha[2] = iparam;
                ctx().fragmentGeneration++;
            }
            case GL13.GL_RGB_SCALE -> {
                envState.scaleRgb = param;
                ctx().fragmentGeneration++;
            }
            case GL11.GL_ALPHA_SCALE -> {
                envState.scaleAlpha = param;
                ctx().fragmentGeneration++;
            }
            // Silently ignore unknown pname — other mods may pass unrecognized params
        }
    }

            public static float glGetTexEnvf(int target, int pname) { return GLSMTexEnvGen.glGetTexEnvf(target, pname); }

            public static int glGetTexEnvi(int target, int pname) { return GLSMTexEnvGen.glGetTexEnvi(target, pname); }

            public static void glGetTexEnv(int target, int pname, IntBuffer params) { GLSMTexEnvGen.glGetTexEnv(target, pname, params); }

            public static void glGetTexEnv(int target, int pname, FloatBuffer params) { GLSMTexEnvGen.glGetTexEnv(target, pname, params); }

    static final float[] texGenTempPlane = new float[4];

            public static void glTexGeni(int coord, int pname, int param) { GLSMTexEnvGen.glTexGeni(coord, pname, param); }

            public static void glTexGenf(int coord, int pname, float param) { GLSMTexEnvGen.glTexGenf(coord, pname, param); }

            public static void glTexGend(int coord, int pname, double param) { GLSMTexEnvGen.glTexGend(coord, pname, param); }

            public static void glTexGen(int coord, int pname, FloatBuffer params) { GLSMTexEnvGen.glTexGen(coord, pname, params); }

            public static void glTexGen(int coord, int pname, DoubleBuffer params) { GLSMTexEnvGen.glTexGen(coord, pname, params); }

            public static void glTexGen(int coord, int pname, IntBuffer params) { GLSMTexEnvGen.glTexGen(coord, pname, params); }

            public static int glGetTexGeni(int coord, int pname) { return GLSMTexEnvGen.glGetTexGeni(coord, pname); }

            public static float glGetTexGenf(int coord, int pname) { return GLSMTexEnvGen.glGetTexGenf(coord, pname); }

            public static double glGetTexGend(int coord, int pname) { return GLSMTexEnvGen.glGetTexGend(coord, pname); }

            public static void glGetTexGen(int coord, int pname, IntBuffer params) { GLSMTexEnvGen.glGetTexGen(coord, pname, params); }

            public static void glGetTexGen(int coord, int pname, FloatBuffer params) { GLSMTexEnvGen.glGetTexGen(coord, pname, params); }

            public static void glGetTexGen(int coord, int pname, DoubleBuffer params) { GLSMTexEnvGen.glGetTexGen(coord, pname, params); }

            public static void glPolygonStipple(ByteBuffer pattern) { GLSMRasterizer.glPolygonStipple(pattern); }

            public static void glAccum(int op, float value) { GLSMDraws.glAccum(op, value); }

            public static void glFeedbackBuffer(int type, FloatBuffer buffer) { GLSMDraws.glFeedbackBuffer(type, buffer); }

    public static int glRenderMode(int mode) { return GLSMDraws.glRenderMode(mode); }

            public static void glPassThrough(float token) { GLSMDraws.glPassThrough(token); }

    static boolean selectWarned = false;

            public static void glSelectBuffer(IntBuffer buffer) { GLSMDraws.glSelectBuffer(buffer); }

            public static void glInitNames() { GLSMDraws.glInitNames(); }

            public static void glPushName(int name) { GLSMDraws.glPushName(name); }

            public static void glPopName() { GLSMDraws.glPopName(); }

            public static void glLoadName(int name) { GLSMDraws.glLoadName(name); }

    public static void glLinkProgram(int program) { GLSMPrograms.glLinkProgram(program); }

    private static final IntBuffer SHADER_BUF = BufferUtils.createIntBuffer(8);

    /**
     * If a program has a fragment shader but no vertex shader, generate a passthrough vertex shader from the fragment's in declarations and attach it.
     */
    static void generateVertexShaderIfNeeded(int program) {
        final int shaderCount = RENDER_BACKEND.getProgrami(program, GL20.GL_ATTACHED_SHADERS);
        if (shaderCount == 0 || shaderCount > SHADER_BUF.capacity()) return;

        SHADER_BUF.clear().limit(shaderCount);
        RENDER_BACKEND.getAttachedShaders(program, null, SHADER_BUF);

        int fragmentShader = 0;
        boolean hasVertex = false;
        for (int i = 0; i < shaderCount; i++) {
            final int shader = SHADER_BUF.get(i);
            final int type = RENDER_BACKEND.getShaderi(shader, GL20.GL_SHADER_TYPE);
            if (type == GL20.GL_VERTEX_SHADER) {
                hasVertex = true;
                break;
            } else if (type == GL20.GL_FRAGMENT_SHADER) {
                fragmentShader = shader;
            }
        }

        if (hasVertex || fragmentShader == 0) return;

        // Fragment-only program — generate a passthrough vertex shader
        final String fragSource = RENDER_BACKEND.getShaderSource(fragmentShader, 65536);
        final String vertSource = CompatShaderTransformer.generatePassthroughVertexShader(fragSource);

        final int vertShader = RENDER_BACKEND.createShader(GL20.GL_VERTEX_SHADER);
        RENDER_BACKEND.shaderSource(vertShader, vertSource);
        RENDER_BACKEND.compileShader(vertShader);

        if (RENDER_BACKEND.getShaderi(vertShader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            final String log = RENDER_BACKEND.getShaderInfoLog(vertShader, 8192);
            LOGGER.warn("CompatShaderTransformer: Generated passthrough vertex shader failed to compile:\n{}\nSource:\n{}", log, vertSource);
            RENDER_BACKEND.deleteShader(vertShader);
            return;
        }

        LOGGER.debug("CompatShaderTransformer: Generated passthrough vertex shader for fragment-only program {}", program);
        RENDER_BACKEND.attachShader(program, vertShader);
        RENDER_BACKEND.deleteShader(vertShader);
    }

    static long programLifetimeGeneration;

    public static void glDeleteProgram(int program) { GLSMPrograms.glDeleteProgram(program); }

    public static int glCreateShader(int type) { return GLSMPrograms.glCreateShader(type); }

    public static void glCompileShader(int shader) { GLSMPrograms.glCompileShader(shader); }

    public static int glCreateProgram() { return GLSMPrograms.glCreateProgram(); }

    public static void glAttachShader(int program, int shader) { GLSMPrograms.glAttachShader(program, shader); }

            public static void glDetachShader(int program, int shader) { GLSMPrograms.glDetachShader(program, shader); }

    public static void glDeleteShader(int shader) { GLSMPrograms.glDeleteShader(shader); }

    public static void glBindAttribLocation(int program, int index, CharSequence name) { GLSMPrograms.glBindAttribLocation(program, index, name); }

            public static void glValidateProgram(int program) { GLSMPrograms.glValidateProgram(program); }

    public static int glGetShaderi(int shader, int pname) { return GLSMPrograms.glGetShaderi(shader, pname); }

    public static String glGetShaderInfoLog(int shader, int maxLength) { return GLSMPrograms.glGetShaderInfoLog(shader, maxLength); }

    public static String glGetShaderInfoLog(int shader) { return GLSMPrograms.glGetShaderInfoLog(shader); }

    public static void glGetShaderInfoLog(int shader, IntBuffer length, ByteBuffer infoLog) { GLSMPrograms.glGetShaderInfoLog(shader, length, infoLog); }

    public static void glGetShaderInfoLog(int shader, int[] length, ByteBuffer infoLog) { GLSMPrograms.glGetShaderInfoLog(shader, length, infoLog); }

    public static int glGetProgrami(int program, int pname) { return GLSMPrograms.glGetProgrami(program, pname); }

    public static String glGetProgramInfoLog(int program, int maxLength) { return GLSMPrograms.glGetProgramInfoLog(program, maxLength); }

    public static String glGetProgramInfoLog(int program) { return GLSMPrograms.glGetProgramInfoLog(program); }

    public static void glGetProgramInfoLog(int program, IntBuffer length, ByteBuffer infoLog) { GLSMPrograms.glGetProgramInfoLog(program, length, infoLog); }

    public static void glGetProgramInfoLog(int program, int[] length, ByteBuffer infoLog) { GLSMPrograms.glGetProgramInfoLog(program, length, infoLog); }

            public static void glBindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access, int format) { GLSMTextures.glBindImageTexture(unit, texture, level, layered, layer, access, format); }
    public static void glMemoryBarrier(int barriers) { RENDER_BACKEND.memoryBarrier(barriers); }
    public static void glCopyImageSubData(int srcName, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ, int dstName, int dstTarget, int dstLevel, int dstX, int dstY, int dstZ, int srcWidth, int srcHeight, int srcDepth) { GLSMTextures.glCopyImageSubData(srcName, srcTarget, srcLevel, srcX, srcY, srcZ, dstName, dstTarget, dstLevel, dstX, dstY, dstZ, srcWidth, srcHeight, srcDepth); }

    public static void glDispatchCompute(int x, int y, int z) {
        recordGpuCommand(GpuCommandType.DISPATCH_COMPUTE, x, y << 16 | z & 0xFFFF);
        RENDER_BACKEND.dispatchCompute(x, y, z);
    }

    public static int glGetUniformLocation(int program, CharSequence name) { return GLSMPrograms.glGetUniformLocation(program, name); }

    public static int glGetUniformLocation(int program, ByteBuffer name) { return GLSMPrograms.glGetUniformLocation(program, name); }

    public static int glGetAttribLocation(int program, CharSequence name) { return GLSMPrograms.glGetAttribLocation(program, name); }

    public static int glGetAttribLocation(int program, ByteBuffer name) { return GLSMPrograms.glGetAttribLocation(program, name); }

    private static final ByteBuffer TEXTURE_BYTES = ByteBuffer.wrap(TEXTURE.getBytes(StandardCharsets.US_ASCII));

    static boolean isTextureBuffer(ByteBuffer buf) {
        final int len = buf.remaining();
        if (len != 7 && len != 8) return false;
        // I believe the driver accepts null terminated or not
        if (len == 8 && buf.get(buf.position() + 7) != 0) return false;
        return buf.slice(buf.position(), 7).equals(TEXTURE_BYTES);
    }

            public static void glDeleteObjectARB(int obj) { GLSMPrograms.glDeleteObjectARB(obj); }

            public static int glGetHandleARB(int pname) { return GLSMPrograms.glGetHandleARB(pname); }

            public static void glGetObjectParameterARB(int obj, int pname, IntBuffer params) { GLSMPrograms.glGetObjectParameterARB(obj, pname, params); }

            public static void glGetObjectParameterARB(int obj, int pname, FloatBuffer params) { GLSMPrograms.glGetObjectParameterARB(obj, pname, params); }

            public static int glGetObjectParameteriARB(int obj, int pname) { return GLSMPrograms.glGetObjectParameteriARB(obj, pname); }

            public static String glGetInfoLogARB(int obj, int maxLength) { return GLSMPrograms.glGetInfoLogARB(obj, maxLength); }

            public static void glGetInfoLogARB(int obj, IntBuffer length, ByteBuffer infoLog) { GLSMPrograms.glGetInfoLogARB(obj, length, infoLog); }

    public static void glUniform1f(int location, float v0) { GLSMPrograms.glUniform1f(location, v0); }

    public static void glUniform2f(int location, float v0, float v1) { GLSMPrograms.glUniform2f(location, v0, v1); }

    public static void glUniform3f(int location, float v0, float v1, float v2) { GLSMPrograms.glUniform3f(location, v0, v1, v2); }

    public static void glUniform4f(int location, float v0, float v1, float v2, float v3) { GLSMPrograms.glUniform4f(location, v0, v1, v2, v3); }

    public static void glUniform1i(int location, int v0) { GLSMPrograms.glUniform1i(location, v0); }

    public static void glUniform1ui(int location, int v0) {
        RENDER_BACKEND.uniform1ui(location, v0);
    }

    public static void glUniform2ui(int location, int v0, int v1) {
        RENDER_BACKEND.uniform2ui(location, v0, v1);
    }

    public static void glUniform3ui(int location, int v0, int v1, int v2) {
        RENDER_BACKEND.uniform3ui(location, v0, v1, v2);
    }

    public static void glUniform4ui(int location, int v0, int v1, int v2, int v3) {
        RENDER_BACKEND.uniform4ui(location, v0, v1, v2, v3);
    }

    public static void glUniform1d(int location, double v0) {
        RENDER_BACKEND.uniform1d(location, v0);
    }

    public static void glUniform2i(int location, int v0, int v1) { GLSMPrograms.glUniform2i(location, v0, v1); }

    public static void glUniform3i(int location, int v0, int v1, int v2) { GLSMPrograms.glUniform3i(location, v0, v1, v2); }

            public static void glUniform4i(int location, int v0, int v1, int v2, int v3) { GLSMPrograms.glUniform4i(location, v0, v1, v2, v3); }

            public static void glUniform1(int location, FloatBuffer values) { GLSMPrograms.glUniform1(location, values); }

            public static void glUniform1(int location, IntBuffer values) { GLSMPrograms.glUniform1(location, values); }

            public static void glUniform2(int location, FloatBuffer values) { GLSMPrograms.glUniform2(location, values); }

            public static void glUniform2(int location, IntBuffer values) { GLSMPrograms.glUniform2(location, values); }

            public static void glUniform3(int location, FloatBuffer values) { GLSMPrograms.glUniform3(location, values); }

            public static void glUniform3(int location, IntBuffer values) { GLSMPrograms.glUniform3(location, values); }

            public static void glUniform4(int location, FloatBuffer values) { GLSMPrograms.glUniform4(location, values); }

            public static void glUniform4(int location, IntBuffer values) { GLSMPrograms.glUniform4(location, values); }

            public static void glUniform1(int location, float[] values) { GLSMPrograms.glUniform1(location, values); }

            public static void glUniform3(int location, float[] values) { GLSMPrograms.glUniform3(location, values); }

            public static void glUniform4(int location, float[] values) { GLSMPrograms.glUniform4(location, values); }

    // ARB_shader_objects buffer forms: the GL redirector rewrites ARBShaderObjects
    // glUniform{1..4}ARB calls to these same-named methods while preserving the caller's
    // descriptor, so every FloatBuffer/IntBuffer overload must exist here or the call
    // fails with NoSuchMethodError (Dynamic Surroundings aurora shader, issue #137).
    public static void glUniform1ARB(int location, FloatBuffer values) {
        glUniform1(location, values);
    }

    public static void glUniform1ARB(int location, IntBuffer values) {
        glUniform1(location, values);
    }

    public static void glUniform2ARB(int location, FloatBuffer values) {
        glUniform2(location, values);
    }

    public static void glUniform2ARB(int location, IntBuffer values) {
        glUniform2(location, values);
    }

    public static void glUniform3ARB(int location, FloatBuffer values) {
        glUniform3(location, values);
    }

    public static void glUniform3ARB(int location, IntBuffer values) {
        glUniform3(location, values);
    }

    public static void glUniform4ARB(int location, FloatBuffer values) {
        glUniform4(location, values);
    }

    public static void glUniform4ARB(int location, IntBuffer values) {
        glUniform4(location, values);
    }

            public static void glUniformMatrix2(int location, boolean transpose, FloatBuffer matrices) { GLSMPrograms.glUniformMatrix2(location, transpose, matrices); }

    public static void glUniformMatrix3(int location, boolean transpose, FloatBuffer matrices) { GLSMPrograms.glUniformMatrix3(location, transpose, matrices); }

    public static void glUniformMatrix4(int location, boolean transpose, FloatBuffer matrices) { GLSMPrograms.glUniformMatrix4(location, transpose, matrices); }

    public static void glUniformMatrix3(int location, boolean transpose, float[] matrices) { GLSMPrograms.glUniformMatrix3(location, transpose, matrices); }

    public static void glUniformMatrix4(int location, boolean transpose, float[] matrices) { GLSMPrograms.glUniformMatrix4(location, transpose, matrices); }

    public static void glGetActiveUniform(int program, int index, IntBuffer length, IntBuffer size, IntBuffer type, ByteBuffer name) { GLSMPrograms.glGetActiveUniform(program, index, length, size, type, name); }

    public static void glGetActiveUniform(int program, int index, int[] length, int[] size, int[] type, ByteBuffer name) { GLSMPrograms.glGetActiveUniform(program, index, length, size, type, name); }

    public static String glGetActiveUniform(int program, int index, int maxLength) { return GLSMPrograms.glGetActiveUniform(program, index, maxLength); }

            public static void glGetAttachedShaders(int program, IntBuffer count, IntBuffer shaders) { GLSMPrograms.glGetAttachedShaders(program, count, shaders); }

            public static void glGetAttachedShaders(int program, int[] count, int[] shaders) { GLSMPrograms.glGetAttachedShaders(program, count, shaders); }

            public static String glGetShaderSource(int shader, int maxLength) { return GLSMPrograms.glGetShaderSource(shader, maxLength); }

            public static String glGetShaderSource(int shader) { return GLSMPrograms.glGetShaderSource(shader); }

            public static void glGetShaderSource(int shader, IntBuffer length, ByteBuffer source) { GLSMPrograms.glGetShaderSource(shader, length, source); }

            public static void glGetShaderSource(int shader, int[] length, ByteBuffer source) { GLSMPrograms.glGetShaderSource(shader, length, source); }

            public static void glGetUniform(int program, int location, FloatBuffer params) { GLSMPrograms.glGetUniform(program, location, params); }

            public static void glGetUniform(int program, int location, IntBuffer params) { GLSMPrograms.glGetUniform(program, location, params); }

    public static float glGetUniformf(int program, int location) {
        final FloatBuffer buf = BufferUtils.createFloatBuffer(1);
        RENDER_BACKEND.getUniformfv(program, location, buf);
        return buf.get(0);
    }

    public static int glGetUniformi(int program, int location) {
        final IntBuffer buf = BufferUtils.createIntBuffer(1);
        RENDER_BACKEND.getUniformiv(program, location, buf);
        return buf.get(0);
    }

    public static void glPushAttrib() { GLSMAttribStack.glPushAttrib(); }

    public static void blendFunc(GlStateManager.SourceFactor srcFactor,
                                 GlStateManager.DestFactor dstFactor) {
        glBlendFunc(srcFactor.factor, dstFactor.factor);
    }

    public static void glBlendFunc(GlStateManager.SourceFactor srcFactor, GlStateManager.DestFactor dstFactor) { GLSMBlend.glBlendFunc(srcFactor, dstFactor); }

            public static void tryBlendFuncSeparate(GlStateManager.SourceFactor srcFactor, GlStateManager.DestFactor dstFactor, GlStateManager.SourceFactor srcFactorAlpha, GlStateManager.DestFactor dstFactorAlpha) { GLSMBlend.tryBlendFuncSeparate(srcFactor, dstFactor, srcFactorAlpha, dstFactorAlpha); }

    public static void setFog(GlStateManager.FogMode fogMode) {
        setFog(fogMode.capabilityId);
    }

    public static void setFog(int mode) {
        glFogi(GL11.GL_FOG_MODE, mode);
    }

    public static void setFogDensity(float density) {
        glFogf(GL11.GL_FOG_DENSITY, density);
    }

    public static void setFogStart(float start) {
        glFogf(GL11.GL_FOG_START, start);
    }

    public static void setFogEnd(float end) {
        glFogf(GL11.GL_FOG_END, end);
    }

    public static void cullFace(GlStateManager.CullFace cullFace) {
        glCullFace(cullFace.mode);
    }

    public static void glCullFace(GlStateManager.CullFace cullFace) { GLSMRasterizer.glCullFace(cullFace); }

    public static void enablePolygonOffset() {
        glEnable(GL11.GL_POLYGON_OFFSET_FILL);
    }

    public static void disablePolygonOffset() {
        glDisable(GL11.GL_POLYGON_OFFSET_FILL);
    }

    public static void enableColorLogic() {
        glEnable(GL11.GL_COLOR_LOGIC_OP);
    }

    public static void disableColorLogic() {
        glDisable(GL11.GL_COLOR_LOGIC_OP);
    }

    public static void colorLogicOp(GlStateManager.LogicOp logicOperation) {
        glLogicOp(logicOperation.opcode);
    }

    public static void glLogicOp(GlStateManager.LogicOp logicOperation) { GLSMBlend.glLogicOp(logicOperation); }

    public static void enableTexGenCoord(GlStateManager.TexGen texGen) {
        glEnable(texGenCapability(texGen));
    }

    public static void disableTexGenCoord(GlStateManager.TexGen texGen) {
        glDisable(texGenCapability(texGen));
    }

    public static void texGen(GlStateManager.TexGen texGen, int param) {
        glTexGeni(texGenCoord(texGen), GL11.GL_TEXTURE_GEN_MODE, param);
    }

    public static void texGen(GlStateManager.TexGen texGen, int pname, FloatBuffer params) {
        glTexGen(texGenCoord(texGen), pname, params);
    }

    private static int texGenCoord(GlStateManager.TexGen texGen) {
        return switch (texGen) {
            case S -> GL11.GL_S;
            case T -> GL11.GL_T;
            case R -> GL11.GL_R;
            case Q -> GL11.GL_Q;
        };
    }

    private static int texGenCapability(GlStateManager.TexGen texGen) {
        return switch (texGen) {
            case S -> GL11.GL_TEXTURE_GEN_S;
            case T -> GL11.GL_TEXTURE_GEN_T;
            case R -> GL11.GL_TEXTURE_GEN_R;
            case Q -> GL11.GL_TEXTURE_GEN_Q;
        };
    }

    public static void bindTexture(int texture) {
        glBindTexture(GL11.GL_TEXTURE_2D, texture);
    }

    public static void enableNormalize() {
        glEnable(GL11.GL_NORMALIZE);
    }

    public static void disableNormalize() {
        glDisable(GL11.GL_NORMALIZE);
    }

    public static void glScalef(double x, double y, double z) { GLSMMatrices.glScalef(x, y, z); }

    public static void glTranslatef(double x, double y, double z) { GLSMMatrices.glTranslatef(x, y, z); }

    public static void glRotatef(double angle, float x, float y, float z) { GLSMMatrices.glRotatef(angle, x, y, z); }

    public static void glRotatef(org.lwjgl.util.vector.Quaternion quaternion) { GLSMMatrices.glRotatef(quaternion); }

    public static void glColor4f(float red, float green, float blue) { GLSMCurrentAttribs.glColor4f(red, green, blue); }

    public static void glTexCoordPointer(int size, int type, int stride, int pointer) { GLSMVertexInput.glTexCoordPointer(size, type, stride, pointer); }

    public static void glVertexPointer(int size, int type, int stride, int pointer) { GLSMVertexInput.glVertexPointer(size, type, stride, pointer); }

            public static void glColorPointer(int size, int type, int stride, int pointer) { GLSMVertexInput.glColorPointer(size, type, stride, pointer); }

    public static void enableBlendProfile(GlStateManager.Profile profile) {
        profile.apply();
    }

    public static void disableBlendProfile(GlStateManager.Profile profile) {
        profile.clean();
    }

    public static void glGetTexImage(int target, int level, int format, int type, long pixelBufferOffset) { GLSMTextures.glGetTexImage(target, level, format, type, pixelBufferOffset); }

    /**
     * Re-apply every cached GL state value to the driver. Used after a display-context
     * migration (the new context starts with driver defaults) and available to
     * {@link RenderBackend#onFrameBegin()} for consuming the splash state seed.
     */
            public static void replayStateToBackend() { GLSMAttribStack.replayStateToBackend(); }

    static void applyPolygonOffset(GLContextState glCtx) {
        final float clamp = glCtx.polygonState.getOffsetClamp();
        if (clamp == 0.0f) {
            RENDER_BACKEND.polygonOffset(glCtx.polygonState.getOffsetFactor(), glCtx.polygonState.getOffsetUnits());
        } else {
            RENDER_BACKEND.polygonOffsetClamp(glCtx.polygonState.getOffsetFactor(), glCtx.polygonState.getOffsetUnits(), clamp);
        }
    }

            public static boolean isPoppingAttributes() { return GLSMAttribStack.isPoppingAttributes(); }

            public static int getMvGeneration() { return GLSMMatrices.getMvGeneration(); }

            public static int getProjGeneration() { return GLSMMatrices.getProjGeneration(); }

            public static int getTexMatrixGeneration() { return GLSMMatrices.getTexMatrixGeneration(); }

    public static int getColorMatrixGeneration() {
        return ctx().colorMatrixGeneration;
    }

            public static int getLightingGeneration() { return GLSMLighting.getLightingGeneration(); }

    public static int getFragmentGeneration() {
        return ctx().fragmentGeneration;
    }

    public static int getColorGeneration() {
        return ctx().colorGeneration;
    }

            public static int getProgramGeneration() { return GLSMPrograms.getProgramGeneration(); }

            public static int getDrawFramebufferGeneration() { return GLSMFramebuffer.getDrawFramebufferGeneration(); }

            public static float getOverlayR() { return GLSMCurrentAttribs.getOverlayR(); }

            public static float getOverlayG() { return GLSMCurrentAttribs.getOverlayG(); }

            public static float getOverlayB() { return GLSMCurrentAttribs.getOverlayB(); }

            public static float getOverlayA() { return GLSMCurrentAttribs.getOverlayA(); }

            public static float getShaderColorR() { return GLSMCurrentAttribs.getShaderColorR(); }

            public static float getShaderColorG() { return GLSMCurrentAttribs.getShaderColorG(); }

            public static float getShaderColorB() { return GLSMCurrentAttribs.getShaderColorB(); }

            public static float getShaderColorA() { return GLSMCurrentAttribs.getShaderColorA(); }

    public static int getClipPlaneGeneration() {
        return ctx().clipPlaneGeneration;
    }

    public static int getTexGenGeneration() {
        return ctx().texGenGeneration;
    }

            public static int getAttribDepth() { return GLSMAttribStack.getAttribDepth(); }

            public static int getMaxBoundTextureUnit() { return GLSMTextures.getMaxBoundTextureUnit(); }

            public static IntegerStateStack getActiveTextureUnitStack() { return GLSMTextures.getActiveTextureUnitStack(); }

            public static IntegerStateStack getProgramStack() { return GLSMPrograms.getProgramStack(); }

            public static IntegerStateStack getShadeModelState() { return GLSMRasterizer.getShadeModelState(); }

            public static TextureUnitArray getTextures() { return GLSMTextures.getTextures(); }

    public static ImageUnitArray getImageUnits() {
        return ctx().imageUnits;
    }

            public static ImageUnitBinding getImageUnitBinding(int unit) { return GLSMTextures.getImageUnitBinding(unit); }

    static int imageBindingOrBackend(int pname, int index) {
        final ImageUnitBinding binding = !isCachingEnabled() ? null : ctx().imageUnits.get(index);
        if (binding == null) return RENDER_BACKEND.getIntegerIndexed(pname, index);
        return switch (pname) {
            case GL42.GL_IMAGE_BINDING_NAME -> binding.getTexture();
            case GL42.GL_IMAGE_BINDING_LEVEL -> binding.getLevel();
            case GL42.GL_IMAGE_BINDING_LAYERED -> binding.isLayered() ? GL11.GL_TRUE : GL11.GL_FALSE;
            case GL42.GL_IMAGE_BINDING_LAYER -> binding.getLayer();
            case GL42.GL_IMAGE_BINDING_ACCESS -> binding.getAccess();
            case GL42.GL_IMAGE_BINDING_FORMAT -> binding.getFormat();
            default -> RENDER_BACKEND.getIntegerIndexed(pname, index);
        };
    }

    public static int glGetInteger(int pname, int index) { return GLSMQueries.glGetInteger(pname, index); }

            public static int glGetIntegeri(int pname, int index) { return GLSMQueries.glGetIntegeri(pname, index); }

            public static void glGetIntegeri(int pname, int index, IntBuffer params) { GLSMQueries.glGetIntegeri(pname, index, params); }

            public static void glGetIntegeri_v(int pname, int index, IntBuffer params) { GLSMQueries.glGetIntegeri_v(pname, index, params); }

            public static void glGetIntegeri_v(int pname, int index, int[] params) { GLSMQueries.glGetIntegeri_v(pname, index, params); }

            public static boolean glGetBooleani(int pname, int index) { return GLSMQueries.glGetBooleani(pname, index); }

            public static void glGetBooleani_v(int pname, int index, ByteBuffer params) { GLSMQueries.glGetBooleani_v(pname, index, params); }

            public static void glGetBooleani(int pname, int index, ByteBuffer params) { GLSMQueries.glGetBooleani(pname, index, params); }

    public static void glGetBoolean(int pname, int index, ByteBuffer params) { GLSMQueries.glGetBoolean(pname, index, params); }

            public static BlendStateStack getBlendState() { return GLSMBlend.getBlendState(); }

            public static BooleanStateStack getBlendMode() { return GLSMBlend.getBlendMode(); }

            public static BooleanStateStack getScissorTest() { return GLSMRasterizer.getScissorTest(); }

            public static ScissorStateStack getScissorState() { return GLSMRasterizer.getScissorState(); }

            public static DepthStateStack getDepthState() { return GLSMDepthStencilAlpha.getDepthState(); }

            public static BooleanStateStack getDepthTest() { return GLSMDepthStencilAlpha.getDepthTest(); }

            public static FogStateStack getFogState() { return GLSMFog.getFogState(); }

            public static BooleanStateStack getFogMode() { return GLSMFog.getFogMode(); }

            public static Color4Stack getColor() { return GLSMCurrentAttribs.getColor(); }

            public static Color4Stack getSecondaryColor() { return GLSMCurrentAttribs.getSecondaryColor(); }

            public static BooleanStateStack getColorSumState() { return GLSMCurrentAttribs.getColorSumState(); }

            public static Color4Stack getClearColor() { return GLSMBlend.getClearColor(); }

            public static ColorMaskStack getColorMask() { return GLSMBlend.getColorMask(); }

            public static IntegerStateStack getDrawBuffer() { return GLSMFramebuffer.getDrawBuffer(); }

            public static IntegerStateStack getLogicOpMode() { return GLSMBlend.getLogicOpMode(); }

            public static BooleanStateStack getCullState() { return GLSMRasterizer.getCullState(); }

            public static AlphaStateStack getAlphaState() { return GLSMDepthStencilAlpha.getAlphaState(); }

            public static BooleanStateStack getAlphaTest() { return GLSMDepthStencilAlpha.getAlphaTest(); }

            public static BooleanStateStack getLightingState() { return GLSMLighting.getLightingState(); }

            public static BooleanStateStack getRescaleNormalState() { return GLSMLighting.getRescaleNormalState(); }

            public static BooleanStateStack getNormalizeState() { return GLSMLighting.getNormalizeState(); }

            public static BooleanStateStack getDitherState() { return GLSMRasterizer.getDitherState(); }

            public static BooleanStateStack getStencilTest() { return GLSMDepthStencilAlpha.getStencilTest(); }

            public static BooleanStateStack getLineSmoothState() { return GLSMRasterizer.getLineSmoothState(); }

            public static BooleanStateStack getLineStippleState() { return GLSMRasterizer.getLineStippleState(); }

            public static BooleanStateStack getPointSmoothState() { return GLSMRasterizer.getPointSmoothState(); }

            public static BooleanStateStack getPolygonSmoothState() { return GLSMRasterizer.getPolygonSmoothState(); }

            public static BooleanStateStack getPolygonStippleState() { return GLSMRasterizer.getPolygonStippleState(); }

            public static BooleanStateStack getMultisampleState() { return GLSMRasterizer.getMultisampleState(); }

            public static BooleanStateStack getSampleAlphaToCoverageState() { return GLSMRasterizer.getSampleAlphaToCoverageState(); }

            public static BooleanStateStack getSampleAlphaToOneState() { return GLSMRasterizer.getSampleAlphaToOneState(); }

            public static BooleanStateStack getSampleCoverageState() { return GLSMRasterizer.getSampleCoverageState(); }

            public static BooleanStateStack getColorLogicOpState() { return GLSMBlend.getColorLogicOpState(); }

            public static BooleanStateStack getIndexLogicOpState() { return GLSMBlend.getIndexLogicOpState(); }

            public static BooleanStateStack getPolygonOffsetPointState() { return GLSMRasterizer.getPolygonOffsetPointState(); }

            public static BooleanStateStack getPolygonOffsetLineState() { return GLSMRasterizer.getPolygonOffsetLineState(); }

            public static BooleanStateStack getPolygonOffsetFillState() { return GLSMRasterizer.getPolygonOffsetFillState(); }

            public static LineStateStack getLineState() { return GLSMRasterizer.getLineState(); }

            public static PointStateStack getPointState() { return GLSMRasterizer.getPointState(); }

            public static IntegerStateStack getHintState() { return GLSMRasterizer.getHintState(); }

            public static BooleanStateStack getPointSpriteState() { return GLSMRasterizer.getPointSpriteState(); }

            public static BooleanStateStack getVertexProgramTwoSideState() { return GLSMPrograms.getVertexProgramTwoSideState(); }

            public static BooleanStateStack getEdgeFlagState() { return GLSMRasterizer.getEdgeFlagState(); }

            public static PolygonStateStack getPolygonState() { return GLSMRasterizer.getPolygonState(); }

            public static StencilStateStack getStencilState() { return GLSMDepthStencilAlpha.getStencilState(); }

            public static BooleanStateStack getAutoNormalState() { return GLSMRasterizer.getAutoNormalState(); }

            public static BooleanStateStack getMap1Color4State() { return GLSMRasterizer.getMap1Color4State(); }

            public static BooleanStateStack getMap1IndexState() { return GLSMRasterizer.getMap1IndexState(); }

            public static BooleanStateStack getMap1NormalState() { return GLSMRasterizer.getMap1NormalState(); }

            public static BooleanStateStack getMap1TextureCoord1State() { return GLSMRasterizer.getMap1TextureCoord1State(); }

            public static BooleanStateStack getMap1TextureCoord2State() { return GLSMRasterizer.getMap1TextureCoord2State(); }

            public static BooleanStateStack getMap1TextureCoord3State() { return GLSMRasterizer.getMap1TextureCoord3State(); }

            public static BooleanStateStack getMap1TextureCoord4State() { return GLSMRasterizer.getMap1TextureCoord4State(); }

            public static BooleanStateStack getMap1Vertex3State() { return GLSMRasterizer.getMap1Vertex3State(); }

            public static BooleanStateStack getMap1Vertex4State() { return GLSMRasterizer.getMap1Vertex4State(); }

            public static BooleanStateStack getMap2Color4State() { return GLSMRasterizer.getMap2Color4State(); }

            public static BooleanStateStack getMap2IndexState() { return GLSMRasterizer.getMap2IndexState(); }

            public static BooleanStateStack getMap2NormalState() { return GLSMRasterizer.getMap2NormalState(); }

            public static BooleanStateStack getMap2TextureCoord1State() { return GLSMRasterizer.getMap2TextureCoord1State(); }

            public static BooleanStateStack getMap2TextureCoord2State() { return GLSMRasterizer.getMap2TextureCoord2State(); }

            public static BooleanStateStack getMap2TextureCoord3State() { return GLSMRasterizer.getMap2TextureCoord3State(); }

            public static BooleanStateStack getMap2TextureCoord4State() { return GLSMRasterizer.getMap2TextureCoord4State(); }

            public static BooleanStateStack getMap2Vertex3State() { return GLSMRasterizer.getMap2Vertex3State(); }

            public static BooleanStateStack getMap2Vertex4State() { return GLSMRasterizer.getMap2Vertex4State(); }

            public static BooleanStateStack[] getClipPlaneStates() { return GLSMRasterizer.getClipPlaneStates(); }

            public static ClipPlaneState getClipPlaneState() { return GLSMRasterizer.getClipPlaneState(); }

            public static MatrixModeStack getMatrixMode() { return GLSMMatrices.getMatrixMode(); }

            public static Matrix4fStack getModelViewMatrix() { return GLSMMatrices.getModelViewMatrix(); }

            public static Matrix4fStack getProjectionMatrix() { return GLSMMatrices.getProjectionMatrix(); }

    public static Matrix4fStack getColorMatrix() {
        return ctx().colorMatrix;
    }

            public static BooleanStateStack[] getLightStates() { return GLSMLighting.getLightStates(); }

            public static LightStateStack[] getLightDataStates() { return GLSMLighting.getLightDataStates(); }

            public static BooleanStateStack getColorMaterial() { return GLSMLighting.getColorMaterial(); }

            public static IntegerStateStack getColorMaterialFace() { return GLSMLighting.getColorMaterialFace(); }

            public static IntegerStateStack getColorMaterialParameter() { return GLSMLighting.getColorMaterialParameter(); }

            public static LightModelStateStack getLightModel() { return GLSMLighting.getLightModel(); }

            public static MaterialStateStack getFrontMaterial() { return GLSMLighting.getFrontMaterial(); }

            public static MaterialStateStack getBackMaterial() { return GLSMLighting.getBackMaterial(); }

            public static ViewPortStateStack getViewportState() { return GLSMRasterizer.getViewportState(); }

            public static int getActiveProgram() { return GLSMPrograms.getActiveProgram(); }

    public static int getListBase() {
        return ctx().listBase;
    }

            public static int getBoundVBO() { return GLSMBuffers.getBoundVBO(); }

            public static int getBoundEBO() { return GLSMVertexInput.getBoundEBO(); }

            public static int getBoundVAO() { return GLSMVertexInput.getBoundVAO(); }

            public static int getDrawFramebuffer() { return GLSMFramebuffer.getDrawFramebuffer(); }

            public static int getReadFramebuffer() { return GLSMFramebuffer.getReadFramebuffer(); }

    public static PixelStoreState getPixelUnpackState() {
        return ctx().pixelUnpackState;
    }
}
