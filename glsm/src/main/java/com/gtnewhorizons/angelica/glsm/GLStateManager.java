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
import com.gtnewhorizons.angelica.glsm.recording.commands.TexImage2DCmd;
import com.gtnewhorizons.angelica.glsm.recording.commands.TexSubImage2DCmd;
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

import org.lwjgl.opengl.ARBImaging;
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
import org.lwjgl.opengl.KHRDebug;
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
    private static final int ARRAY_QUERY_SCRATCH_SIZE = 16;

    /**
     * Direct-memory scratch for the array query overloads. The backend hands the buffer straight to the
     * driver, so these must not be heap buffers: a heap buffer has no stable native address, and the
     * driver then writes through a null pointer.
     */
    private static final IntBuffer ARRAY_QUERY_INT_SCRATCH = BufferUtils.createIntBuffer(ARRAY_QUERY_SCRATCH_SIZE);
    private static final FloatBuffer ARRAY_QUERY_FLOAT_SCRATCH = BufferUtils.createFloatBuffer(ARRAY_QUERY_SCRATCH_SIZE);

    // Generation counters for FFP uniform dirty tracking. Bumped when the corresponding GLSM state changes.
    // Per-matrix-mode generation counters — avoids re-uploading all matrices when only one mode changed

    // Deferred vertex attribute upload flags — set when state changes, flushed before draw

    /** Flush deferred vertex attribute uploads. Called before draw to ensure default attrib values are current. */
    public static void flushDeferredVertexAttribs() {
        final GLContextState glCtx = ctx();
        RENDER_BACKEND.vertexAttrib4f(Usage.COLOR.getAttributeLocation(), glCtx.color.getRed(), glCtx.color.getGreen(), glCtx.color.getBlue(), glCtx.color.getAlpha());
        glCtx.dirtyColorAttrib = false;
        RENDER_BACKEND.vertexAttrib4f(Usage.SECONDARY_UV.getAttributeLocation(), glCtx.lastBrightnessX, glCtx.lastBrightnessY, 0.0f, 1.0f);
        glCtx.dirtyLightmapAttrib = false;
        if (glCtx.dirtyNormalAttrib) {
            final var n = ShaderManager.getCurrentNormal();
            RENDER_BACKEND.vertexAttrib3f(Usage.NORMAL.getAttributeLocation(), n.x, n.y, n.z);
            glCtx.dirtyNormalAttrib = false;
        }
        if (glCtx.dirtyTexCoordAttrib) {
            final var tc = ShaderManager.getCurrentTexCoord();
            RENDER_BACKEND.vertexAttrib4f(Usage.PRIMARY_UV.getAttributeLocation(), tc.x, tc.y, tc.z, tc.w);
            glCtx.dirtyTexCoordAttrib = false;
        }
    }

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
    private static boolean lockBindCallback;

    // Deferred texture deletion: names kept valid until glGenTextures recycles them.
    // Emulates Mesa/compat-profile behavior where delete-then-bind doesn't error.
    private static final IntOpenHashSet deferredDeleteTextures = new IntOpenHashSet();
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
    public static int getActiveTextureUnitForServerState() {
        if (isCachingEnabled()) {
            return getActiveTextureUnit();
        }
        return RENDER_BACKEND.getInteger(GL13.GL_ACTIVE_TEXTURE) - GL13.GL_TEXTURE0;
    }

    /**
     * Get the texture bound to the current texture unit for server-side state operations.
     * If caching is enabled, returns cached value.
     * If caching is disabled (SharedDrawable), queries actual GL state.
     */
    public static int getBoundTextureForServerState() {
        if (isCachingEnabled()) {
            return getBoundTexture();
        }
        return RENDER_BACKEND.getInteger(GL11.GL_TEXTURE_BINDING_2D);
    }

    /**
     * Get the texture bound to a specific texture unit for server-side state operations.
     * If caching is enabled, returns cached value.
     * If caching is disabled (SharedDrawable), returns -1 to force operations to proceed
     */
    public static int getBoundTextureForServerState(int unit) {
        if (isCachingEnabled()) {
            return getBoundTexture(unit);
        }
        return -1;
    }

    private static final String TEXTURE = "texture";
    private static final String TEXTURE_RENAMED = "gtexture";

    public static void registerModifiedState(int id) {
        final GLContextState glCtx = ctx();
        if (glCtx.attribDepth > 0) {
            final int depth = glCtx.attribDepth - 1;
            glCtx.modifiedIds[depth][glCtx.modifiedCount[depth]++] = id;
        }
    }

    private static <T extends CowStateStack<?>> T mod(T stack) {
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

    private static final MethodHandle MAT4_STACK_CURR_DEPTH;

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
    public static boolean displayListExists(int list) {
        return DisplayListManager.displayListExists(list);
    }

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

    private static int packDimensions(int width, int height) {
        return (width & 0xFFFF) << 16 | height & 0xFFFF;
    }

    private static int getBoundTextureForGpuDiagnostics() {
        if (!isGpuDiagnosticCacheTrusted()) {
            return GpuCommandDiagnostics.UNKNOWN;
        }
        return getBoundTexture();
    }

    private static boolean isGpuDiagnosticCacheTrusted() {
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
    private static boolean shouldUseDSA(int target) {
        if (!isCachingEnabled()) return false;
        // Proxy textures are for capability queries, not real textures
        return target != GL11.GL_PROXY_TEXTURE_1D && target != GL11.GL_PROXY_TEXTURE_2D && target != GL12.GL_PROXY_TEXTURE_3D;
    }

    public static void glEnable(int cap) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordEnable(cap);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (cap >= GL11.GL_CLIP_PLANE0 && cap < GL11.GL_CLIP_PLANE0 + MAX_CLIP_PLANES) {
            ctx().clipPlaneStates[cap - GL11.GL_CLIP_PLANE0].enable();
            ctx().clipPlaneGeneration++;
            return;
        }

        switch (cap) {
            case GL11.GL_ALPHA_TEST -> enableAlphaTest();
            case GL11.GL_AUTO_NORMAL -> ctx().autoNormalState.enable();
            case GL11.GL_BLEND -> enableBlend();
            case GL11.GL_COLOR_MATERIAL -> enableColorMaterial();
            case GL11.GL_COLOR_LOGIC_OP -> ctx().colorLogicOpState.enable();
            case GL14.GL_COLOR_SUM -> ctx().colorSumState.enable();
            case GL11.GL_CULL_FACE -> enableCull();
            case GL11.GL_DEPTH_TEST -> enableDepthTest();
            case GL11.GL_DITHER -> ctx().ditherState.enable();
            case GL11.GL_FOG -> enableFog();
            case GL11.GL_INDEX_LOGIC_OP -> ctx().indexLogicOpState.enable();
            case GL11.GL_LIGHTING -> enableLighting();
            case GL11.GL_LIGHT0 -> enableLight(0);
            case GL11.GL_LIGHT1 -> enableLight(1);
            case GL11.GL_LIGHT2 -> enableLight(2);
            case GL11.GL_LIGHT3 -> enableLight(3);
            case GL11.GL_LIGHT4 -> enableLight(4);
            case GL11.GL_LIGHT5 -> enableLight(5);
            case GL11.GL_LIGHT6 -> enableLight(6);
            case GL11.GL_LIGHT7 -> enableLight(7);
            case GL11.GL_LINE_SMOOTH -> ctx().lineSmoothState.enable();
            case GL11.GL_LINE_STIPPLE -> ctx().lineStippleState.enable();
            case GL11.GL_MAP1_COLOR_4 -> ctx().map1Color4State.enable();
            case GL11.GL_MAP1_INDEX -> ctx().map1IndexState.enable();
            case GL11.GL_MAP1_NORMAL -> ctx().map1NormalState.enable();
            case GL11.GL_MAP1_TEXTURE_COORD_1 -> ctx().map1TextureCoord1State.enable();
            case GL11.GL_MAP1_TEXTURE_COORD_2 -> ctx().map1TextureCoord2State.enable();
            case GL11.GL_MAP1_TEXTURE_COORD_3 -> ctx().map1TextureCoord3State.enable();
            case GL11.GL_MAP1_TEXTURE_COORD_4 -> ctx().map1TextureCoord4State.enable();
            case GL11.GL_MAP1_VERTEX_3 -> ctx().map1Vertex3State.enable();
            case GL11.GL_MAP1_VERTEX_4 -> ctx().map1Vertex4State.enable();
            case GL11.GL_MAP2_COLOR_4 -> ctx().map2Color4State.enable();
            case GL11.GL_MAP2_INDEX -> ctx().map2IndexState.enable();
            case GL11.GL_MAP2_NORMAL -> ctx().map2NormalState.enable();
            case GL11.GL_MAP2_TEXTURE_COORD_1 -> ctx().map2TextureCoord1State.enable();
            case GL11.GL_MAP2_TEXTURE_COORD_2 -> ctx().map2TextureCoord2State.enable();
            case GL11.GL_MAP2_TEXTURE_COORD_3 -> ctx().map2TextureCoord3State.enable();
            case GL11.GL_MAP2_TEXTURE_COORD_4 -> ctx().map2TextureCoord4State.enable();
            case GL11.GL_MAP2_VERTEX_3 -> ctx().map2Vertex3State.enable();
            case GL11.GL_MAP2_VERTEX_4 -> ctx().map2Vertex4State.enable();
            case GL13.GL_MULTISAMPLE -> ctx().multisampleState.enable();
            case GL11.GL_NORMALIZE -> ctx().normalizeState.enable();
            case GL11.GL_POINT_SMOOTH -> ctx().pointSmoothState.enable();
            case GL11.GL_POLYGON_OFFSET_POINT -> ctx().polygonOffsetPointState.enable();
            case GL11.GL_POLYGON_OFFSET_LINE -> ctx().polygonOffsetLineState.enable();
            case GL11.GL_POLYGON_OFFSET_FILL -> ctx().polygonOffsetFillState.enable();
            case GL11.GL_POLYGON_SMOOTH -> ctx().polygonSmoothState.enable();
            case GL11.GL_POLYGON_STIPPLE -> ctx().polygonStippleState.enable();
            case GL12.GL_RESCALE_NORMAL -> enableRescaleNormal();
            case GL13.GL_SAMPLE_ALPHA_TO_COVERAGE -> ctx().sampleAlphaToCoverageState.enable();
            case GL13.GL_SAMPLE_ALPHA_TO_ONE -> ctx().sampleAlphaToOneState.enable();
            case GL13.GL_SAMPLE_COVERAGE -> ctx().sampleCoverageState.enable();
            case GL11.GL_SCISSOR_TEST -> enableScissorTest();
            case GL11.GL_STENCIL_TEST -> ctx().stencilTest.enable();
            case GL11.GL_TEXTURE_1D -> ctx().textures.getTexture1DStates(ctx().activeTextureUnit.getValue()).enable();
            case GL11.GL_TEXTURE_2D -> enableTexture();
            case GL12.GL_TEXTURE_3D -> ctx().textures.getTexture3DStates(ctx().activeTextureUnit.getValue()).enable();
            case GL13.GL_TEXTURE_CUBE_MAP -> ctx().textures.getTextureCubeMapStates(ctx().activeTextureUnit.getValue()).enable();
            case GL20.GL_POINT_SPRITE -> ctx().pointSpriteState.enable();
            case GL20.GL_VERTEX_PROGRAM_TWO_SIDE -> ctx().vertexProgramTwoSideState.enable();
            case GL11.GL_TEXTURE_GEN_S -> ctx().textures.getTexGenSStates(ctx().activeTextureUnit.getValue()).enable();
            case GL11.GL_TEXTURE_GEN_T -> ctx().textures.getTexGenTStates(ctx().activeTextureUnit.getValue()).enable();
            case GL11.GL_TEXTURE_GEN_R -> ctx().textures.getTexGenRStates(ctx().activeTextureUnit.getValue()).enable();
            case GL11.GL_TEXTURE_GEN_Q -> ctx().textures.getTexGenQStates(ctx().activeTextureUnit.getValue()).enable();
            default -> RENDER_BACKEND.enable(cap);
        }
    }

    public static void glDisable(int cap) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDisable(cap);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (cap >= GL11.GL_CLIP_PLANE0 && cap < GL11.GL_CLIP_PLANE0 + MAX_CLIP_PLANES) {
            ctx().clipPlaneStates[cap - GL11.GL_CLIP_PLANE0].disable();
            ctx().clipPlaneGeneration++;
            return;
        }

        switch (cap) {
            case GL11.GL_ALPHA_TEST -> disableAlphaTest();
            case GL11.GL_AUTO_NORMAL -> ctx().autoNormalState.disable();
            case GL11.GL_BLEND -> disableBlend();
            case GL11.GL_COLOR_MATERIAL -> disableColorMaterial();
            case GL11.GL_COLOR_LOGIC_OP -> ctx().colorLogicOpState.disable();
            case GL14.GL_COLOR_SUM -> ctx().colorSumState.disable();
            case GL11.GL_CULL_FACE -> disableCull();
            case GL11.GL_DEPTH_TEST -> disableDepthTest();
            case GL11.GL_DITHER -> ctx().ditherState.disable();
            case GL11.GL_FOG -> disableFog();
            case GL11.GL_INDEX_LOGIC_OP -> ctx().indexLogicOpState.disable();
            case GL11.GL_LIGHTING -> disableLighting();
            case GL11.GL_LIGHT0 -> disableLight(0);
            case GL11.GL_LIGHT1 -> disableLight(1);
            case GL11.GL_LIGHT2 -> disableLight(2);
            case GL11.GL_LIGHT3 -> disableLight(3);
            case GL11.GL_LIGHT4 -> disableLight(4);
            case GL11.GL_LIGHT5 -> disableLight(5);
            case GL11.GL_LIGHT6 -> disableLight(6);
            case GL11.GL_LIGHT7 -> disableLight(7);
            case GL11.GL_LINE_SMOOTH -> ctx().lineSmoothState.disable();
            case GL11.GL_LINE_STIPPLE -> ctx().lineStippleState.disable();
            case GL11.GL_MAP1_COLOR_4 -> ctx().map1Color4State.disable();
            case GL11.GL_MAP1_INDEX -> ctx().map1IndexState.disable();
            case GL11.GL_MAP1_NORMAL -> ctx().map1NormalState.disable();
            case GL11.GL_MAP1_TEXTURE_COORD_1 -> ctx().map1TextureCoord1State.disable();
            case GL11.GL_MAP1_TEXTURE_COORD_2 -> ctx().map1TextureCoord2State.disable();
            case GL11.GL_MAP1_TEXTURE_COORD_3 -> ctx().map1TextureCoord3State.disable();
            case GL11.GL_MAP1_TEXTURE_COORD_4 -> ctx().map1TextureCoord4State.disable();
            case GL11.GL_MAP1_VERTEX_3 -> ctx().map1Vertex3State.disable();
            case GL11.GL_MAP1_VERTEX_4 -> ctx().map1Vertex4State.disable();
            case GL11.GL_MAP2_COLOR_4 -> ctx().map2Color4State.disable();
            case GL11.GL_MAP2_INDEX -> ctx().map2IndexState.disable();
            case GL11.GL_MAP2_NORMAL -> ctx().map2NormalState.disable();
            case GL11.GL_MAP2_TEXTURE_COORD_1 -> ctx().map2TextureCoord1State.disable();
            case GL11.GL_MAP2_TEXTURE_COORD_2 -> ctx().map2TextureCoord2State.disable();
            case GL11.GL_MAP2_TEXTURE_COORD_3 -> ctx().map2TextureCoord3State.disable();
            case GL11.GL_MAP2_TEXTURE_COORD_4 -> ctx().map2TextureCoord4State.disable();
            case GL11.GL_MAP2_VERTEX_3 -> ctx().map2Vertex3State.disable();
            case GL11.GL_MAP2_VERTEX_4 -> ctx().map2Vertex4State.disable();
            case GL13.GL_MULTISAMPLE -> ctx().multisampleState.disable();
            case GL11.GL_NORMALIZE -> ctx().normalizeState.disable();
            case GL11.GL_POINT_SMOOTH -> ctx().pointSmoothState.disable();
            case GL11.GL_POLYGON_OFFSET_POINT -> ctx().polygonOffsetPointState.disable();
            case GL11.GL_POLYGON_OFFSET_LINE -> ctx().polygonOffsetLineState.disable();
            case GL11.GL_POLYGON_OFFSET_FILL -> ctx().polygonOffsetFillState.disable();
            case GL11.GL_POLYGON_SMOOTH -> ctx().polygonSmoothState.disable();
            case GL11.GL_POLYGON_STIPPLE -> ctx().polygonStippleState.disable();
            case GL12.GL_RESCALE_NORMAL -> disableRescaleNormal();
            case GL13.GL_SAMPLE_ALPHA_TO_COVERAGE -> ctx().sampleAlphaToCoverageState.disable();
            case GL13.GL_SAMPLE_ALPHA_TO_ONE -> ctx().sampleAlphaToOneState.disable();
            case GL13.GL_SAMPLE_COVERAGE -> ctx().sampleCoverageState.disable();
            case GL11.GL_SCISSOR_TEST -> disableScissorTest();
            case GL11.GL_STENCIL_TEST -> ctx().stencilTest.disable();
            case GL11.GL_TEXTURE_1D -> ctx().textures.getTexture1DStates(ctx().activeTextureUnit.getValue()).disable();
            case GL11.GL_TEXTURE_2D -> disableTexture();
            case GL12.GL_TEXTURE_3D -> ctx().textures.getTexture3DStates(ctx().activeTextureUnit.getValue()).disable();
            case GL13.GL_TEXTURE_CUBE_MAP -> ctx().textures.getTextureCubeMapStates(ctx().activeTextureUnit.getValue()).disable();
            case GL20.GL_POINT_SPRITE -> ctx().pointSpriteState.disable();
            case GL20.GL_VERTEX_PROGRAM_TWO_SIDE -> ctx().vertexProgramTwoSideState.disable();
            case GL11.GL_TEXTURE_GEN_S -> ctx().textures.getTexGenSStates(ctx().activeTextureUnit.getValue()).disable();
            case GL11.GL_TEXTURE_GEN_T -> ctx().textures.getTexGenTStates(ctx().activeTextureUnit.getValue()).disable();
            case GL11.GL_TEXTURE_GEN_R -> ctx().textures.getTexGenRStates(ctx().activeTextureUnit.getValue()).disable();
            case GL11.GL_TEXTURE_GEN_Q -> ctx().textures.getTexGenQStates(ctx().activeTextureUnit.getValue()).disable();
            default -> RENDER_BACKEND.disable(cap);
        }
    }

    public static boolean glIsEnabled(int cap) {
        if (shouldBypassCache()) {
            return RENDER_BACKEND.getBoolean(cap);
        }
        if (cap >= GL11.GL_CLIP_PLANE0 && cap < GL11.GL_CLIP_PLANE0 + MAX_CLIP_PLANES) {
            return ctx().clipPlaneStates[cap - GL11.GL_CLIP_PLANE0].isEnabled();
        }

        return switch (cap) {
            case GL11.GL_ALPHA_TEST -> ctx().alphaTest.isEnabled();
            case GL11.GL_AUTO_NORMAL -> ctx().autoNormalState.isEnabled();
            case GL11.GL_BLEND -> ctx().blendMode.isEnabled();
            case GL11.GL_COLOR_MATERIAL -> ctx().colorMaterial.isEnabled();
            case GL11.GL_COLOR_LOGIC_OP -> ctx().colorLogicOpState.isEnabled();
            case GL14.GL_COLOR_SUM -> ctx().colorSumState.isEnabled();
            case GL11.GL_CULL_FACE -> ctx().cullState.isEnabled();
            case GL11.GL_DEPTH_TEST -> ctx().depthTest.isEnabled();
            case GL11.GL_DITHER -> ctx().ditherState.isEnabled();
            case GL11.GL_FOG -> ctx().fogMode.isEnabled();
            case GL11.GL_INDEX_LOGIC_OP -> ctx().indexLogicOpState.isEnabled();
            case GL11.GL_LIGHTING -> ctx().lightingState.isEnabled();
            case GL11.GL_LIGHT0 -> ctx().lightStates[0].isEnabled();
            case GL11.GL_LIGHT1 -> ctx().lightStates[1].isEnabled();
            case GL11.GL_LIGHT2 -> ctx().lightStates[2].isEnabled();
            case GL11.GL_LIGHT3 -> ctx().lightStates[3].isEnabled();
            case GL11.GL_LIGHT4 -> ctx().lightStates[4].isEnabled();
            case GL11.GL_LIGHT5 -> ctx().lightStates[5].isEnabled();
            case GL11.GL_LIGHT6 -> ctx().lightStates[6].isEnabled();
            case GL11.GL_LIGHT7 -> ctx().lightStates[7].isEnabled();
            case GL11.GL_LINE_SMOOTH -> ctx().lineSmoothState.isEnabled();
            case GL11.GL_LINE_STIPPLE -> ctx().lineStippleState.isEnabled();
            case GL11.GL_MAP1_COLOR_4 -> ctx().map1Color4State.isEnabled();
            case GL11.GL_MAP1_INDEX -> ctx().map1IndexState.isEnabled();
            case GL11.GL_MAP1_NORMAL -> ctx().map1NormalState.isEnabled();
            case GL11.GL_MAP1_TEXTURE_COORD_1 -> ctx().map1TextureCoord1State.isEnabled();
            case GL11.GL_MAP1_TEXTURE_COORD_2 -> ctx().map1TextureCoord2State.isEnabled();
            case GL11.GL_MAP1_TEXTURE_COORD_3 -> ctx().map1TextureCoord3State.isEnabled();
            case GL11.GL_MAP1_TEXTURE_COORD_4 -> ctx().map1TextureCoord4State.isEnabled();
            case GL11.GL_MAP1_VERTEX_3 -> ctx().map1Vertex3State.isEnabled();
            case GL11.GL_MAP1_VERTEX_4 -> ctx().map1Vertex4State.isEnabled();
            case GL11.GL_MAP2_COLOR_4 -> ctx().map2Color4State.isEnabled();
            case GL11.GL_MAP2_INDEX -> ctx().map2IndexState.isEnabled();
            case GL11.GL_MAP2_NORMAL -> ctx().map2NormalState.isEnabled();
            case GL11.GL_MAP2_TEXTURE_COORD_1 -> ctx().map2TextureCoord1State.isEnabled();
            case GL11.GL_MAP2_TEXTURE_COORD_2 -> ctx().map2TextureCoord2State.isEnabled();
            case GL11.GL_MAP2_TEXTURE_COORD_3 -> ctx().map2TextureCoord3State.isEnabled();
            case GL11.GL_MAP2_TEXTURE_COORD_4 -> ctx().map2TextureCoord4State.isEnabled();
            case GL11.GL_MAP2_VERTEX_3 -> ctx().map2Vertex3State.isEnabled();
            case GL11.GL_MAP2_VERTEX_4 -> ctx().map2Vertex4State.isEnabled();
            case GL13.GL_MULTISAMPLE -> ctx().multisampleState.isEnabled();
            case GL11.GL_NORMALIZE -> ctx().normalizeState.isEnabled();
            case GL11.GL_POINT_SMOOTH -> ctx().pointSmoothState.isEnabled();
            case GL11.GL_POLYGON_OFFSET_POINT -> ctx().polygonOffsetPointState.isEnabled();
            case GL11.GL_POLYGON_OFFSET_LINE -> ctx().polygonOffsetLineState.isEnabled();
            case GL11.GL_POLYGON_OFFSET_FILL -> ctx().polygonOffsetFillState.isEnabled();
            case GL11.GL_POLYGON_SMOOTH -> ctx().polygonSmoothState.isEnabled();
            case GL11.GL_POLYGON_STIPPLE -> ctx().polygonStippleState.isEnabled();
            case GL12.GL_RESCALE_NORMAL -> ctx().rescaleNormalState.isEnabled();
            case GL13.GL_SAMPLE_ALPHA_TO_COVERAGE -> ctx().sampleAlphaToCoverageState.isEnabled();
            case GL13.GL_SAMPLE_ALPHA_TO_ONE -> ctx().sampleAlphaToOneState.isEnabled();
            case GL13.GL_SAMPLE_COVERAGE -> ctx().sampleCoverageState.isEnabled();
            case GL11.GL_SCISSOR_TEST -> ctx().scissorTest.isEnabled();
            case GL11.GL_STENCIL_TEST -> ctx().stencilTest.isEnabled();
            case GL11.GL_TEXTURE_1D -> ctx().textures.getTexture1DStates(ctx().activeTextureUnit.getValue()).isEnabled();
            case GL11.GL_TEXTURE_2D -> ctx().textures.getTextureUnitStates(ctx().activeTextureUnit.getValue()).isEnabled();
            case GL12.GL_TEXTURE_3D -> ctx().textures.getTexture3DStates(ctx().activeTextureUnit.getValue()).isEnabled();
            case GL13.GL_TEXTURE_CUBE_MAP -> ctx().textures.getTextureCubeMapStates(ctx().activeTextureUnit.getValue()).isEnabled();
            case GL20.GL_POINT_SPRITE -> ctx().pointSpriteState.isEnabled();
            case GL20.GL_VERTEX_PROGRAM_TWO_SIDE -> ctx().vertexProgramTwoSideState.isEnabled();
            case GL11.GL_TEXTURE_GEN_S -> ctx().textures.getTexGenSStates(ctx().activeTextureUnit.getValue()).isEnabled();
            case GL11.GL_TEXTURE_GEN_T -> ctx().textures.getTexGenTStates(ctx().activeTextureUnit.getValue()).isEnabled();
            case GL11.GL_TEXTURE_GEN_R -> ctx().textures.getTexGenRStates(ctx().activeTextureUnit.getValue()).isEnabled();
            case GL11.GL_TEXTURE_GEN_Q -> ctx().textures.getTexGenQStates(ctx().activeTextureUnit.getValue()).isEnabled();
            default -> {
                final int conv = VAOManager.pnameToConventional(cap, ctx().clientActiveTextureUnit);
                yield conv >= 0 ? ctx().vaos.isConventionalEnabled(conv) : RENDER_BACKEND.getBoolean(cap);
            }
        };
    }

    /**
     * Whether the pname is an enable capability tracked by {@link #glIsEnabled}. Compatibility
     * GL allows querying enable caps through glGetInteger/glGetFloat, but the core-profile
     * backend rejects them; legacy mods (e.g. OreLib OpenGlState, issue #41) query caps this
     * way, so the glGet* variants answer from tracked state instead of forwarding.
     */
    private static boolean isEnableCap(int pname) {
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

    public static boolean glGetBoolean(int pname) {
        if (shouldBypassCache()) {
            return RENDER_BACKEND.getBoolean(pname);
        }
        if (pname >= GL11.GL_CLIP_PLANE0 && pname < GL11.GL_CLIP_PLANE0 + MAX_CLIP_PLANES) {
            return ctx().clipPlaneStates[pname - GL11.GL_CLIP_PLANE0].isEnabled();
        }

        return switch (pname) {
            case GL11.GL_ALPHA_TEST -> ctx().alphaTest.isEnabled();
            case GL11.GL_AUTO_NORMAL -> ctx().autoNormalState.isEnabled();
            case GL11.GL_BLEND -> ctx().blendMode.isEnabled();
            case GL11.GL_COLOR_MATERIAL -> ctx().colorMaterial.isEnabled();
            case GL11.GL_COLOR_LOGIC_OP -> ctx().colorLogicOpState.isEnabled();
            case GL14.GL_COLOR_SUM -> ctx().colorSumState.isEnabled();
            case GL11.GL_CULL_FACE -> ctx().cullState.isEnabled();
            case GL11.GL_DEPTH_TEST -> ctx().depthTest.isEnabled();
            case GL11.GL_DEPTH_WRITEMASK -> ctx().depthState.isMaskEnabled();
            case GL11.GL_DITHER -> ctx().ditherState.isEnabled();
            case GL11.GL_FOG -> ctx().fogMode.isEnabled();
            case GL11.GL_INDEX_LOGIC_OP -> ctx().indexLogicOpState.isEnabled();
            case GL11.GL_LIGHTING -> ctx().lightingState.isEnabled();
            case GL11.GL_LIGHT0 -> ctx().lightStates[0].isEnabled();
            case GL11.GL_LIGHT1 -> ctx().lightStates[1].isEnabled();
            case GL11.GL_LIGHT2 -> ctx().lightStates[2].isEnabled();
            case GL11.GL_LIGHT3 -> ctx().lightStates[3].isEnabled();
            case GL11.GL_LIGHT4 -> ctx().lightStates[4].isEnabled();
            case GL11.GL_LIGHT5 -> ctx().lightStates[5].isEnabled();
            case GL11.GL_LIGHT6 -> ctx().lightStates[6].isEnabled();
            case GL11.GL_LIGHT7 -> ctx().lightStates[7].isEnabled();
            case GL11.GL_LINE_SMOOTH -> ctx().lineSmoothState.isEnabled();
            case GL11.GL_LINE_STIPPLE -> ctx().lineStippleState.isEnabled();
            case GL11.GL_MAP1_COLOR_4 -> ctx().map1Color4State.isEnabled();
            case GL11.GL_MAP1_INDEX -> ctx().map1IndexState.isEnabled();
            case GL11.GL_MAP1_NORMAL -> ctx().map1NormalState.isEnabled();
            case GL11.GL_MAP1_TEXTURE_COORD_1 -> ctx().map1TextureCoord1State.isEnabled();
            case GL11.GL_MAP1_TEXTURE_COORD_2 -> ctx().map1TextureCoord2State.isEnabled();
            case GL11.GL_MAP1_TEXTURE_COORD_3 -> ctx().map1TextureCoord3State.isEnabled();
            case GL11.GL_MAP1_TEXTURE_COORD_4 -> ctx().map1TextureCoord4State.isEnabled();
            case GL11.GL_MAP1_VERTEX_3 -> ctx().map1Vertex3State.isEnabled();
            case GL11.GL_MAP1_VERTEX_4 -> ctx().map1Vertex4State.isEnabled();
            case GL11.GL_MAP2_COLOR_4 -> ctx().map2Color4State.isEnabled();
            case GL11.GL_MAP2_INDEX -> ctx().map2IndexState.isEnabled();
            case GL11.GL_MAP2_NORMAL -> ctx().map2NormalState.isEnabled();
            case GL11.GL_MAP2_TEXTURE_COORD_1 -> ctx().map2TextureCoord1State.isEnabled();
            case GL11.GL_MAP2_TEXTURE_COORD_2 -> ctx().map2TextureCoord2State.isEnabled();
            case GL11.GL_MAP2_TEXTURE_COORD_3 -> ctx().map2TextureCoord3State.isEnabled();
            case GL11.GL_MAP2_TEXTURE_COORD_4 -> ctx().map2TextureCoord4State.isEnabled();
            case GL11.GL_MAP2_VERTEX_3 -> ctx().map2Vertex3State.isEnabled();
            case GL11.GL_MAP2_VERTEX_4 -> ctx().map2Vertex4State.isEnabled();
            case GL13.GL_MULTISAMPLE -> ctx().multisampleState.isEnabled();
            case GL11.GL_NORMALIZE -> ctx().normalizeState.isEnabled();
            case GL11.GL_POINT_SMOOTH -> ctx().pointSmoothState.isEnabled();
            case GL11.GL_POLYGON_OFFSET_POINT -> ctx().polygonOffsetPointState.isEnabled();
            case GL11.GL_POLYGON_OFFSET_LINE -> ctx().polygonOffsetLineState.isEnabled();
            case GL11.GL_POLYGON_OFFSET_FILL -> ctx().polygonOffsetFillState.isEnabled();
            case GL11.GL_POLYGON_SMOOTH -> ctx().polygonSmoothState.isEnabled();
            case GL11.GL_POLYGON_STIPPLE -> ctx().polygonStippleState.isEnabled();
            case GL12.GL_RESCALE_NORMAL -> ctx().rescaleNormalState.isEnabled();
            case GL13.GL_SAMPLE_ALPHA_TO_COVERAGE -> ctx().sampleAlphaToCoverageState.isEnabled();
            case GL13.GL_SAMPLE_ALPHA_TO_ONE -> ctx().sampleAlphaToOneState.isEnabled();
            case GL13.GL_SAMPLE_COVERAGE -> ctx().sampleCoverageState.isEnabled();
            case GL11.GL_SCISSOR_TEST -> ctx().scissorTest.isEnabled();
            case GL11.GL_STENCIL_TEST -> ctx().stencilTest.isEnabled();
            case GL11.GL_TEXTURE_1D -> ctx().textures.getTexture1DStates(ctx().activeTextureUnit.getValue()).isEnabled();
            case GL11.GL_TEXTURE_2D -> ctx().textures.getTextureUnitStates(ctx().activeTextureUnit.getValue()).isEnabled();
            case GL12.GL_TEXTURE_3D -> ctx().textures.getTexture3DStates(ctx().activeTextureUnit.getValue()).isEnabled();
            case GL13.GL_TEXTURE_CUBE_MAP -> ctx().textures.getTextureCubeMapStates(ctx().activeTextureUnit.getValue()).isEnabled();
            case GL11.GL_TEXTURE_GEN_S -> ctx().textures.getTexGenSStates(ctx().activeTextureUnit.getValue()).isEnabled();
            case GL11.GL_TEXTURE_GEN_T -> ctx().textures.getTexGenTStates(ctx().activeTextureUnit.getValue()).isEnabled();
            case GL11.GL_TEXTURE_GEN_R -> ctx().textures.getTexGenRStates(ctx().activeTextureUnit.getValue()).isEnabled();
            case GL11.GL_TEXTURE_GEN_Q -> ctx().textures.getTexGenQStates(ctx().activeTextureUnit.getValue()).isEnabled();
            case GL11.GL_LIGHT_MODEL_LOCAL_VIEWER -> ctx().lightModel.localViewer != 0.0f;
            case GL11.GL_LIGHT_MODEL_TWO_SIDE -> ctx().lightModel.twoSide != 0.0f;
            case GL20.GL_POINT_SPRITE -> ctx().pointSpriteState.isEnabled();
            case GL20.GL_VERTEX_PROGRAM_TWO_SIDE -> ctx().vertexProgramTwoSideState.isEnabled();
            case GL11.GL_EDGE_FLAG -> ctx().edgeFlagState.isEnabled();
            default -> {
                final int conv = VAOManager.pnameToConventional(pname, ctx().clientActiveTextureUnit);
                if (conv >= 0) yield ctx().vaos.isConventionalEnabled(conv);
                final int desc = LegacyQueries.desc(pname);
                yield desc != 0 ? LegacyQueries.getBoolean(ctx(), pname, desc) : RENDER_BACKEND.getBoolean(pname);
            }
        };
    }

    public static void glGetBoolean(int pname, ByteBuffer params) {
        if (shouldBypassCache()) {
            RENDER_BACKEND.getBoolean(pname, params);
            return;
        }

        switch (pname) {
            case GL11.GL_COLOR_WRITEMASK -> {
                final int pos = params.position();
                params.put(pos, (byte) (ctx().colorMask.red ? GL11.GL_TRUE : GL11.GL_FALSE));
                params.put(pos + 1, (byte) (ctx().colorMask.green ? GL11.GL_TRUE : GL11.GL_FALSE));
                params.put(pos + 2, (byte) (ctx().colorMask.blue ? GL11.GL_TRUE : GL11.GL_FALSE));
                params.put(pos + 3, (byte) (ctx().colorMask.alpha ? GL11.GL_TRUE : GL11.GL_FALSE));
            }
            default -> {
                if (!HAS_MULTIPLE_SET.contains(pname)) {
                    params.put(params.position(), (byte) (glGetBoolean(pname) ? GL11.GL_TRUE : GL11.GL_FALSE));
                } else if (!LegacyQueries.getBooleanv(ctx(), pname, params)) {
                    RENDER_BACKEND.getBoolean(pname, params);
                }
            }
        }
    }

    @SneakyThrows
    public static int getMatrixStackDepth(Matrix4fStack stack) {
        // JOML's curr is 0-based (0 = no pushes), but OpenGL starts at 1 (base matrix counts)
        return (int) MAT4_STACK_CURR_DEPTH.invokeExact(stack) + 1;
    }

    public static int glGetInteger(int pname) {
        if (shouldBypassCache()) {
            return RENDER_BACKEND.getInteger(pname);
        }

        if (isEnableCap(pname)) {
            return glIsEnabled(pname) ? GL11.GL_TRUE : GL11.GL_FALSE;
        }

        return switch (pname) {
            case GL11.GL_ALPHA_TEST_FUNC -> ctx().alphaState.getFunction();
            case GL11.GL_DEPTH_FUNC -> ctx().depthState.getFunc();
            case GL11.GL_DEPTH_WRITEMASK -> ctx().depthState.isMaskEnabled() ? 1 : 0;
            case GL11.GL_LIST_BASE -> ctx().listBase;
            case GL11.GL_LIST_MODE -> DisplayListManager.getListMode();
            case GL11.GL_MATRIX_MODE -> ctx().matrixMode.getMode();
            case GL11.GL_SHADE_MODEL -> ctx().shadeModelState.getValue();
            case GL11.GL_TEXTURE_BINDING_2D -> getBoundTextureForServerState();
            case GL13.GL_ACTIVE_TEXTURE -> GL13.GL_TEXTURE0 + getActiveTextureUnitForServerState();
            case GL11.GL_COLOR_MATERIAL_FACE -> ctx().colorMaterialFace.getValue();
            case GL11.GL_COLOR_MATERIAL_PARAMETER -> ctx().colorMaterialParameter.getValue();
            case GL11.GL_MODELVIEW_STACK_DEPTH -> getMatrixStackDepth(ctx().modelViewMatrix);
            case GL11.GL_PROJECTION_STACK_DEPTH -> getMatrixStackDepth(ctx().projectionMatrix);
            case GL11.GL_TEXTURE_STACK_DEPTH -> getMatrixStackDepth(ctx().textures.getTextureUnitMatrix(getActiveTextureUnit()));
            case ARBImaging.GL_COLOR_MATRIX_STACK_DEPTH -> getMatrixStackDepth(ctx().colorMatrix);
            case ARBImaging.GL_MAX_COLOR_MATRIX_STACK_DEPTH -> MAX_COLOR_STACK_DEPTH;
            case GL11.GL_MAX_MODELVIEW_STACK_DEPTH -> MAX_MODELVIEW_STACK_DEPTH;
            case GL11.GL_MAX_PROJECTION_STACK_DEPTH -> MAX_PROJECTION_STACK_DEPTH;
            case GL11.GL_MAX_TEXTURE_STACK_DEPTH -> MAX_TEXTURE_STACK_DEPTH;
            case GL11.GL_MAX_ATTRIB_STACK_DEPTH -> MAX_ATTRIB_STACK_DEPTH;
            case GL11.GL_MAX_CLIENT_ATTRIB_STACK_DEPTH -> CLIENT_ATTRIB_STACK_DEPTH;
            case GL11.GL_ATTRIB_STACK_DEPTH -> ctx().attribDepth + DisplayListManager.virtualStateDepth(DisplayListManager.getRecordMode());
            case GL11.GL_CLIENT_ATTRIB_STACK_DEPTH -> ctx().clientAttribStackPointer;
            case GL11.GL_MAX_LIGHTS -> ctx().lightStates.length;
            case GL11.GL_MAX_CLIP_PLANES -> MAX_CLIP_PLANES;
            case GL20.GL_MAX_TEXTURE_COORDS -> MAX_TEXTURE_COORDS;
            case GL13.GL_MAX_TEXTURE_UNITS -> MAX_LEGACY_TEXTURE_UNITS;
            case GL13.GL_CLIENT_ACTIVE_TEXTURE -> GL13.GL_TEXTURE0 + ctx().clientActiveTextureUnit;
            case GL11.GL_RENDER_MODE -> FeedbackManager.getRenderMode();
            case GL20.GL_POINT_SPRITE_COORD_ORIGIN -> ctx().pointState.getSpriteCoordOrigin();
            case GL11.GL_FOG_HINT, GL11.GL_PERSPECTIVE_CORRECTION_HINT, GL11.GL_POINT_SMOOTH_HINT,
                 GL11.GL_LINE_SMOOTH_HINT, GL11.GL_POLYGON_SMOOTH_HINT, GL13.GL_TEXTURE_COMPRESSION_HINT,
                 GL14.GL_GENERATE_MIPMAP_HINT, GL20.GL_FRAGMENT_SHADER_DERIVATIVE_HINT -> hintMode(ctx(), pname);
            case GL11.GL_CULL_FACE_MODE -> ctx().polygonState.getCullFaceMode();
            case GL11.GL_FRONT_FACE -> ctx().polygonState.getFrontFace();
            case GL11.GL_POLYGON_MODE -> ctx().polygonState.getFrontMode();
            case GL11.GL_UNPACK_ALIGNMENT -> ctx().pixelUnpackState.alignment();
            case GL11.GL_UNPACK_ROW_LENGTH -> ctx().pixelUnpackState.rowLength();
            case GL11.GL_UNPACK_SKIP_ROWS -> ctx().pixelUnpackState.skipRows();
            case GL11.GL_UNPACK_SKIP_PIXELS -> ctx().pixelUnpackState.skipPixels();
            case GL12.GL_UNPACK_IMAGE_HEIGHT -> ctx().pixelUnpackState.imageHeight();
            case GL12.GL_UNPACK_SKIP_IMAGES -> ctx().pixelUnpackState.skipImages();
            case GL11.GL_UNPACK_SWAP_BYTES -> ctx().pixelUnpackState.swapBytes() ? GL11.GL_TRUE : GL11.GL_FALSE;
            case GL11.GL_UNPACK_LSB_FIRST -> ctx().pixelUnpackState.lsbFirst() ? GL11.GL_TRUE : GL11.GL_FALSE;
            case GL11.GL_PACK_ALIGNMENT -> ctx().pixelPackState.alignment();
            case GL11.GL_PACK_ROW_LENGTH -> ctx().pixelPackState.rowLength();
            case GL11.GL_PACK_SKIP_ROWS -> ctx().pixelPackState.skipRows();
            case GL11.GL_PACK_SKIP_PIXELS -> ctx().pixelPackState.skipPixels();
            case GL12.GL_PACK_IMAGE_HEIGHT -> ctx().pixelPackState.imageHeight();
            case GL12.GL_PACK_SKIP_IMAGES -> ctx().pixelPackState.skipImages();
            case GL11.GL_PACK_SWAP_BYTES -> ctx().pixelPackState.swapBytes() ? GL11.GL_TRUE : GL11.GL_FALSE;
            case GL11.GL_PACK_LSB_FIRST -> ctx().pixelPackState.lsbFirst() ? GL11.GL_TRUE : GL11.GL_FALSE;

            case GL12.GL_LIGHT_MODEL_COLOR_CONTROL -> ctx().lightModel.colorControl;

            case GL14.GL_BLEND_DST_ALPHA -> ctx().blendState.getDstAlpha();
            case GL14.GL_BLEND_DST_RGB -> ctx().blendState.getDstRgb();
            case GL14.GL_BLEND_SRC_ALPHA -> ctx().blendState.getSrcAlpha();
            case GL14.GL_BLEND_SRC_RGB -> ctx().blendState.getSrcRgb();
            case GL14.GL_BLEND_EQUATION -> ctx().blendState.getEquationRgb();
            case GL20.GL_BLEND_EQUATION_ALPHA -> ctx().blendState.getEquationAlpha();

            // Legacy blend-factor aliases (GL_BLEND_SRC/GL_BLEND_DST) differ from the GL14 RGB
            // tokens and are rejected by the core-profile backend; map to the RGB factors.
            case GL11.GL_BLEND_SRC -> ctx().blendState.getSrcRgb();
            case GL11.GL_BLEND_DST -> ctx().blendState.getDstRgb();

            case GL11.GL_LOGIC_OP_MODE -> ctx().logicOpMode.getValue();
            case GL11.GL_DRAW_BUFFER -> ctx().drawBuffer.getValue();

            case GL11.GL_STENCIL_FUNC -> ctx().stencilState.getFuncFront();
            case GL11.GL_STENCIL_REF -> ctx().stencilState.getRefFront();
            case GL11.GL_STENCIL_VALUE_MASK -> ctx().stencilState.getValueMaskFront();
            case GL11.GL_STENCIL_FAIL -> ctx().stencilState.getFailOpFront();
            case GL11.GL_STENCIL_PASS_DEPTH_FAIL -> ctx().stencilState.getZFailOpFront();
            case GL11.GL_STENCIL_PASS_DEPTH_PASS -> ctx().stencilState.getZPassOpFront();
            case GL11.GL_STENCIL_WRITEMASK -> ctx().stencilState.getWriteMaskFront();
            case GL11.GL_STENCIL_CLEAR_VALUE -> ctx().stencilState.getClearValue();

            case GL15.GL_ARRAY_BUFFER_BINDING -> ctx().boundVBO;
            case GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING -> ctx().vaos.boundEBO;
            case GL21.GL_PIXEL_UNPACK_BUFFER_BINDING -> ctx().boundPixelUnpackBuffer;
            case GL21.GL_PIXEL_PACK_BUFFER_BINDING -> ctx().boundPixelPackBuffer;

            case GL20.GL_CURRENT_PROGRAM -> ctx().activeProgram;

            case GL30.GL_VERTEX_ARRAY_BINDING -> ctx().boundVAO;

            case GL30.GL_DRAW_FRAMEBUFFER_BINDING -> ctx().drawFramebuffer;
            case GL30.GL_READ_FRAMEBUFFER_BINDING -> ctx().readFramebuffer;

            default -> switch (pname) {
                case GL11.GL_FOG_MODE -> ctx().fogState.getFogMode();
                case GL11.GL_LINE_STIPPLE_PATTERN -> ctx().lineState.getStipplePattern() & 0xFFFF;
                case GL11.GL_LINE_STIPPLE_REPEAT -> ctx().lineState.getStippleFactor();
                case GL11.GL_DEPTH_BITS -> drawFramebufferAttachmentSize(false);
                case GL11.GL_STENCIL_BITS -> drawFramebufferAttachmentSize(true);
                default -> {
                    final int field = VAOManager.clientArrayField(pname, ctx().clientActiveTextureUnit);
                    if (field >= 0 && (field & ~0xFF) != VAOManager.FIELD_POINTER) yield ctx().vaos.clientArrayParam(field);
                    final int desc = LegacyQueries.desc(pname);
                    yield desc != 0 ? LegacyQueries.getInteger(ctx(), pname, desc) : RENDER_BACKEND.getInteger(pname);
                }
            };
        };
    }

    /**
     * Answers the depth or stencil attachment size of the current draw framebuffer. Both legacy
     * {@code GL_DEPTH_BITS} and {@code GL_STENCIL_BITS} are gone from the core profile, so the size comes
     * from the framebuffer attachment query: the default framebuffer answers through its
     * {@code GL_DEPTH} or {@code GL_STENCIL} attachment point, and a named framebuffer answers the depth
     * or stencil attachment, reporting 0 when that attachment is absent.
     */
    private static int drawFramebufferAttachmentSize(boolean stencil) {
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

    public static void glGetInteger(int pname, IntBuffer params) {
        if (shouldBypassCache()) {
            RENDER_BACKEND.getInteger(pname, params);
            return;
        }

        switch (pname) {
            case GL11.GL_VIEWPORT -> ctx().viewportState.get(params);
            case GL11.GL_SCISSOR_BOX -> ctx().scissorState.get(params);
            case GL11.GL_POLYGON_MODE -> {
                final int pos = params.position();
                params.put(pos, ctx().polygonState.getFrontMode());
                params.put(pos + 1, ctx().polygonState.getBackMode());
            }
            default -> {
                if (!HAS_MULTIPLE_SET.contains(pname)) {
                    params.put(params.position(), glGetInteger(pname));
                } else if (!LegacyQueries.getIntegerv(ctx(), pname, params)) {
                    RENDER_BACKEND.getInteger(pname, params);
                }
            }
        }
    }

    /**
     * Array flavour of {@link #glGetInteger(int, IntBuffer)}. Callers whose GL queries GLSM redirects
     * pass arrays, so this overload has to exist.
     *
     * <p>Only the elements the query produces are written, so the tail of a caller array keeps its own
     * values instead of what an earlier query left there: a single-valued pname fills one slot, a
     * tracked vector fills its own width (capped by {@code params.length}), and the driver answers a
     * pname GLSM does not track through a direct scratch cleared before the query.
     */
    public static void glGetInteger(int pname, int[] params) {
        if (params.length == 0) return;
        if (shouldBypassCache()) {
            queryDriverVector(pname, params);
            return;
        }

        switch (pname) {
            case GL11.GL_VIEWPORT, GL11.GL_SCISSOR_BOX -> copyTrackedVector(pname, 4, params);
            case GL11.GL_POLYGON_MODE -> copyTrackedVector(pname, 2, params);
            default -> {
                if (LegacyQueries.desc(pname) != 0) {
                    LegacyQueries.getIntegerv(ctx(), pname, params);
                } else if (HAS_MULTIPLE_SET.contains(pname)) {
                    queryDriverVector(pname, params);
                } else {
                    params[0] = glGetInteger(pname);
                }
            }
        }
    }

    /** Writes the {@code width} tracked elements of a vector pname, capped by the caller array length. */
    private static void copyTrackedVector(int pname, int width, int[] params) {
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
    private static void queryDriverVector(int pname, int[] params) {
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

    public static void glGetMaterial(int face, int pname, FloatBuffer params) {
        final MaterialStateStack state;
        if (face == GL11.GL_FRONT) {
            state = ctx().frontMaterial;
        } else if (face == GL11.GL_BACK) {
            state = ctx().backMaterial;
        } else {
            throw new RuntimeException("Invalid face parameter specified to glGetMaterial: " + face);
        }

        switch (pname) {
            case GL11.GL_AMBIENT -> state.ambient.get(params.position(), params);
            case GL11.GL_DIFFUSE -> state.diffuse.get(params.position(), params);
            case GL11.GL_SPECULAR -> state.specular.get(params.position(), params);
            case GL11.GL_EMISSION -> state.emission.get(params.position(), params);
            case GL11.GL_SHININESS -> params.put(params.position(), state.shininess);
            case GL11.GL_COLOR_INDEXES -> state.colorIndexes.get(params.position(), params);
            default -> {
            }
        }
    }

    public static void glGetMaterial(int face, int pname, IntBuffer params) {
        final FloatBuffer scratch = ctx().queryScratch;
        scratch.clear();
        glGetMaterial(face, pname, scratch);
        putLightingInts(scratch, pname, true, params);
    }

    public static void glGetLight(int light, int pname, FloatBuffer params) {
        final LightStateStack state = ctx().lightDataStates[light - GL11.GL_LIGHT0];
        switch (pname) {
            case GL11.GL_AMBIENT -> state.ambient.get(params.position(), params);
            case GL11.GL_DIFFUSE -> state.diffuse.get(params.position(), params);
            case GL11.GL_SPECULAR -> state.specular.get(params.position(), params);
            case GL11.GL_POSITION -> state.position.get(params.position(), params);
            case GL11.GL_SPOT_DIRECTION -> state.spotDirection.get(params.position(), params);
            case GL11.GL_SPOT_EXPONENT -> params.put(params.position(), state.spotExponent);
            case GL11.GL_SPOT_CUTOFF -> params.put(params.position(), state.spotCutoff);
            case GL11.GL_CONSTANT_ATTENUATION -> params.put(params.position(), state.constantAttenuation);
            case GL11.GL_LINEAR_ATTENUATION -> params.put(params.position(), state.linearAttenuation);
            case GL11.GL_QUADRATIC_ATTENUATION -> params.put(params.position(), state.quadraticAttenuation);
            default -> {
            }
        }
    }

    public static void glGetLight(int light, int pname, IntBuffer params) {
        final FloatBuffer scratch = ctx().queryScratch;
        scratch.clear();
        glGetLight(light, pname, scratch);
        putLightingInts(scratch, pname, false, params);
    }

    /**
     * Converts a cached light or material vector to the integer flavour of {@code glGetLight}/{@code glGetMaterial}.
     * Color components use the fixed-point scale the driver reports; the remaining values round to nearest.
     */
    private static void putLightingInts(FloatBuffer values, int pname, boolean material, IntBuffer params) {
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

    public static void glGetFloat(int pname, FloatBuffer params) {
        if (shouldBypassCache()) {
            RENDER_BACKEND.getFloat(pname, params);
            return;
        }

        switch (pname) {
            case GL11.GL_MODELVIEW_MATRIX -> ctx().modelViewMatrix.get(params.position(), params);
            case GL11.GL_PROJECTION_MATRIX -> ctx().projectionMatrix.get(params.position(), params);
            case GL11.GL_TEXTURE_MATRIX -> ctx().textures.getTextureUnitMatrix(getActiveTextureUnit()).get(params.position(), params);
            case ARBImaging.GL_COLOR_MATRIX -> ctx().colorMatrix.get(params.position(), params);
            case GL11.GL_COLOR_CLEAR_VALUE -> ctx().clearColor.get(params);
            case GL11.GL_CURRENT_COLOR -> ctx().color.get(params);
            case GL14.GL_CURRENT_SECONDARY_COLOR -> ctx().secondaryColor.get(params);
            case GL11.GL_DEPTH_RANGE -> {
                final int pos = params.position();
                params.put(pos, (float) ctx().viewportState.depthRangeNear);
                params.put(pos + 1, (float) ctx().viewportState.depthRangeFar);
            }
            case GL14.GL_BLEND_COLOR -> {
                final int pos = params.position();
                params.put(pos, ctx().blendState.getBlendColorR());
                params.put(pos + 1, ctx().blendState.getBlendColorG());
                params.put(pos + 2, ctx().blendState.getBlendColorB());
                params.put(pos + 3, ctx().blendState.getBlendColorA());
            }
            case GL11.GL_CURRENT_NORMAL -> {
                final Vector3f normal = ShaderManager.getCurrentNormal();
                final int pos = params.position();
                params.put(pos, normal.x);
                params.put(pos + 1, normal.y);
                params.put(pos + 2, normal.z);
            }
            case GL11.GL_CURRENT_TEXTURE_COORDS -> {
                // Legacy GL reports the coordinates of the active texture unit; unit 1 is the
                // lightmap slot, whose per-vertex value lives in the brightness fields.
                final GLContextState glCtx = ctx();
                final int unit = glCtx.activeTextureUnit.getValue();
                final int pos = params.position();
                if (unit == 1) {
                    params.put(pos, glCtx.lastBrightnessX);
                    params.put(pos + 1, glCtx.lastBrightnessY);
                    params.put(pos + 2, 0.0f);
                    params.put(pos + 3, 1.0f);
                } else if (unit >= 0 && unit < glCtx.ffp.currentTexCoords.length) {
                    final Vector4f tc = glCtx.ffp.currentTexCoords[unit];
                    params.put(pos, tc.x);
                    params.put(pos + 1, tc.y);
                    params.put(pos + 2, tc.z);
                    params.put(pos + 3, tc.w);
                } else {
                    // Units beyond the tracked slots start at the GL initial value.
                    params.put(pos, 0.0f);
                    params.put(pos + 1, 0.0f);
                    params.put(pos + 2, 0.0f);
                    params.put(pos + 3, 1.0f);
                }
            }
            case GL11.GL_FOG_COLOR -> {
                final FloatBuffer fogBuf = ctx().fogState.getFogColorBuffer();
                final int pos = params.position();
                params.put(pos, fogBuf.get(0));
                params.put(pos + 1, fogBuf.get(1));
                params.put(pos + 2, fogBuf.get(2));
                params.put(pos + 3, fogBuf.get(3));
            }
            case GL11.GL_LIGHT_MODEL_AMBIENT -> ctx().lightModel.ambient.get(params.position(), params);
            case GL14.GL_POINT_DISTANCE_ATTENUATION -> {
                final int pos = params.position();
                params.put(pos, ctx().pointState.getAttenuationConstant());
                params.put(pos + 1, ctx().pointState.getAttenuationLinear());
                params.put(pos + 2, ctx().pointState.getAttenuationQuadratic());
            }
            default -> {
                if (!HAS_MULTIPLE_SET.contains(pname)) {
                    params.put(params.position(), glGetFloat(pname));
                } else {
                    RENDER_BACKEND.getFloat(pname, params);
                }
            }
        }
    }

    /**
     * Array flavour of {@link #glGetFloat(int, FloatBuffer)}. Callers whose GL queries GLSM redirects
     * pass arrays, so this overload has to exist. The scratch buffer is sized for the widest query GLSM
     * answers (a 4x4 matrix) so the backend can never overflow a shorter caller array.
     */
    public static void glGetFloat(int pname, float[] params) {
        if (params.length == 0) return;
        final FloatBuffer buffer = params.length <= ARRAY_QUERY_SCRATCH_SIZE
            ? ARRAY_QUERY_FLOAT_SCRATCH
            : BufferUtils.createFloatBuffer(params.length);
        buffer.clear();
        for (int i = 0; i < buffer.capacity(); i++) {
            buffer.put(i, 0.0f);
        }
        glGetFloat(pname, buffer);
        buffer.get(0, params, 0, params.length);
    }

    public static double glGetDouble(int pname) {
        return switch (pname) {
            case GL11.GL_DEPTH_CLEAR_VALUE -> ctx().depthState.getClearValue();
            default -> glGetFloat(pname);
        };
    }

    public static void glGetDouble(int pname, DoubleBuffer params) {
        final GLContextState glCtx = ctx();
        if (!isCachingEnabled()) {
            RENDER_BACKEND.getDouble(pname, params);
            return;
        }

        final int pos = params.position();
        switch (pname) {
            case GL11.GL_DEPTH_CLEAR_VALUE -> params.put(pos, glCtx.depthState.getClearValue());
            case GL11.GL_DEPTH_RANGE -> {
                params.put(pos, glCtx.viewportState.depthRangeNear);
                params.put(pos + 1, glCtx.viewportState.depthRangeFar);
            }
            case GL11.GL_MODELVIEW_MATRIX, GL11.GL_PROJECTION_MATRIX, GL11.GL_TEXTURE_MATRIX -> widenFromFloat(pname, params, 16);
            case GL11.GL_COLOR_CLEAR_VALUE, GL11.GL_CURRENT_COLOR, GL11.GL_CURRENT_TEXTURE_COORDS, GL11.GL_FOG_COLOR,
                GL11.GL_LIGHT_MODEL_AMBIENT, GL14.GL_BLEND_COLOR, GL14.GL_CURRENT_SECONDARY_COLOR -> widenFromFloat(pname, params, 4);
            case GL11.GL_CURRENT_NORMAL, GL14.GL_POINT_DISTANCE_ATTENUATION -> widenFromFloat(pname, params, 3);
            default -> {
                if (!HAS_MULTIPLE_SET.contains(pname)) {
                    params.put(pos, glGetDouble(pname));
                } else {
                    RENDER_BACKEND.getDouble(pname, params);
                }
            }
        }
    }

    public static void glGetDouble(int pname, double[] params) {
        final DoubleBuffer scratch = ctx().queryScratchDouble;
        final DoubleBuffer buf;
        if (params.length <= scratch.capacity()) {
            buf = scratch;
            buf.clear();
            buf.limit(params.length);
        } else {
            buf = BufferUtils.createDoubleBuffer(params.length);
        }
        glGetDouble(pname, buf);
        buf.position(0);
        buf.get(params);
    }

    private static void widenFromFloat(int pname, DoubleBuffer params, int count) {
        final FloatBuffer scratch = ctx().queryScratch;
        scratch.clear();
        glGetFloat(pname, scratch);
        final int pos = params.position();
        for (int i = 0; i < count; i++) {
            params.put(pos + i, scratch.get(i));
        }
    }

    public static void glGetClipPlane(int plane, DoubleBuffer equation) {
        final int index = plane - GL11.GL_CLIP_PLANE0;
        if (index < 0 || index >= MAX_CLIP_PLANES) return;
        ctx().clipPlaneState.getEyePlane(index, equation);
    }

    public static float glGetFloat(int pname) {
        if (shouldBypassCache()) {
            return RENDER_BACKEND.getFloat(pname);
        }
        if (isEnableCap(pname)) {
            return glIsEnabled(pname) ? 1.0F : 0.0F;
        }

        return switch (pname) {
            case GL11.GL_ALPHA_TEST_REF -> ctx().alphaState.getReference();
            case GL11.GL_FOG_DENSITY -> ctx().fogState.getDensity();
            case GL11.GL_FOG_START -> ctx().fogState.getStart();
            case GL11.GL_FOG_END -> ctx().fogState.getEnd();
            case GL11.GL_DEPTH_CLEAR_VALUE -> (float) ctx().depthState.getClearValue();
            case GL11.GL_LINE_WIDTH -> ctx().lineState.getWidth();
            case GL11.GL_POINT_SIZE -> ctx().pointState.getSize();
            case GL14.GL_POINT_SIZE_MIN -> ctx().pointState.getMinSize();
            case GL14.GL_POINT_SIZE_MAX -> {
                final GLContextState glCtx = ctx();
                yield Float.isNaN(glCtx.pointState.getMaxSize()) ? maxPointSize(glCtx) : glCtx.pointState.getMaxSize();
            }
            case GL14.GL_POINT_FADE_THRESHOLD_SIZE -> ctx().pointState.getFadeThreshold();
            default -> {
                final int desc = LegacyQueries.desc(pname);
                yield desc != 0 ? LegacyQueries.getFloat(ctx(), pname, desc) : RENDER_BACKEND.getFloat(pname);
            }
        };
    }

    /**
     * Largest point size the driver accepts, used while the tracked {@code GL_POINT_SIZE_MAX} is still NaN.
     * The result is cached outside {@link com.gtnewhorizons.angelica.glsm.states.PointState} so a query
     * does not make the value part of the pushable point state.
     */
    private static float maxPointSize(GLContextState glCtx) {
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

    public static void glBlendColor(float red, float green, float blue, float alpha) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordBlendColor(red, green, blue, alpha);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        final boolean bypass = BYPASS_CACHE || !caching;
        if (bypass || red != ctx().blendState.getBlendColorR() || green != ctx().blendState.getBlendColorG() || blue != ctx().blendState.getBlendColorB() || alpha != ctx().blendState.getBlendColorA()) {
            if (caching) {
                mod(ctx().blendState);
                ctx().blendState.setBlendColorR(red);
                ctx().blendState.setBlendColorG(green);
                ctx().blendState.setBlendColorB(blue);
                ctx().blendState.setBlendColorA(alpha);
            }
            RENDER_BACKEND.blendColor(red, green, blue, alpha);
        }
    }

    public static void enableBlend() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordEnable(GL11.GL_BLEND);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean wasEnabled = ctx().blendMode.isEffectivelyEnabled();
        final DeferredBlendHandler bh = GLSMHooks.blendHandler;
        if (bh != null && bh.isBlendLocked()) {
            ctx().blendMode.beforeModify();
            bh.deferBlendModeToggle(true);
        } else {
            ctx().blendMode.enable();
        }
        if (GLSMConfig.hudCacheOverride) {
            GLSMConfig.hudCacheBlendEnabled = true;
        }
        if (!wasEnabled && ctx().blendMode.isEffectivelyEnabled()) {
            postVanillaBlendChange();
        }
    }

    public static void disableBlend() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDisable(GL11.GL_BLEND);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean wasEnabled = ctx().blendMode.isEffectivelyEnabled();
        final DeferredBlendHandler bh = GLSMHooks.blendHandler;
        if (bh != null && bh.isBlendLocked()) {
            ctx().blendMode.beforeModify();
            bh.deferBlendModeToggle(false);
        } else {
            ctx().blendMode.disable();
        }
        if (GLSMConfig.hudCacheOverride) {
            GLSMConfig.hudCacheBlendEnabled = false;
        }
        if (wasEnabled && !ctx().blendMode.isEffectivelyEnabled()) {
            postVanillaBlendChange();
        }
    }

    private static void postVanillaBlendChange() {
        if (GLSMHooks.hasActiveConsumers() && GLSMHooks.VANILLA_BLEND_CHANGE.hasListeners()) {
            GLSMHooks.VANILLA_BLEND_CHANGE.post(GLSMHooks.vanillaBlendChangeEvent);
        }
    }

    private static boolean snapshotVanillaBlendFunc() {
        if (!GLSMHooks.hasActiveConsumers() || !GLSMHooks.VANILLA_BLEND_CHANGE.hasListeners()) {
            return false;
        }
        ctx().blendState.readEffective(ctx().vanillaBlendBefore);
        ctx().vanillaBlendEnabledBefore = ctx().blendMode.isEffectivelyEnabled();
        return true;
    }

    private static void postVanillaBlendChangeIfMoved(boolean snapshotted) {
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
    public static boolean isEffectiveBlendEnabled() {
        return ctx().blendMode.isEffectivelyEnabled();
    }

    /** Copies the blend function requested by vanilla into {@code out}. */
    public static BlendState getEffectiveBlendState(BlendState out) {
        return ctx().blendState.readEffective(out);
    }

    /** Returns the depth-write mask requested by vanilla for the current draw. */
    public static boolean isEffectiveDepthMaskEnabled() {
        return ctx().depthState.isEffectiveMaskEnabled();
    }

    /** Returns the alpha-test enable value requested by vanilla for the current draw. */
    public static boolean isEffectiveAlphaTestEnabled() {
        return ctx().alphaTest.isEffectivelyEnabled();
    }

    /** Copies the alpha-test function requested by vanilla into {@code out}. */
    public static AlphaState getEffectiveAlphaState(AlphaState out) {
        return ctx().alphaState.readEffective(out);
    }

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

    public static void enableScissorTest() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordEnable(GL11.GL_SCISSOR_TEST);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        ctx().scissorTest.enable();
    }

    public static void disableScissorTest() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDisable(GL11.GL_SCISSOR_TEST);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        ctx().scissorTest.disable();
    }

    public static void glBlendFunc(int srcFactor, int dstFactor) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordBlendFunc(srcFactor, dstFactor, srcFactor, dstFactor);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean snapshotted = snapshotVanillaBlendFunc();
        final DeferredBlendHandler bh = GLSMHooks.blendHandler;
        if (bh != null && bh.isBlendLocked()) {
            ctx().blendState.beforeModify();
            bh.deferBlendFunc(srcFactor, dstFactor, srcFactor, dstFactor);
            postVanillaBlendChangeIfMoved(snapshotted);
            return;
        }
        // Cache thread check - only update state on main thread, but always make GL call if needed
        final boolean caching = isCachingEnabled();
        final boolean bypass = BYPASS_CACHE || !caching;
        if (bypass
                || ctx().blendState.getSrcRgb() != srcFactor
                || ctx().blendState.getDstRgb() != dstFactor
                || ctx().blendState.getSrcAlpha() != srcFactor
                || ctx().blendState.getDstAlpha() != dstFactor) {
            if (caching) mod(ctx().blendState).setAll(srcFactor, dstFactor, srcFactor, dstFactor);
            issueBlendFunc(srcFactor, dstFactor, srcFactor, dstFactor);
            postBlendFuncChange(
                srcFactor, dstFactor,
                GLSMConfig.hudCacheOverride ? GL11.GL_ONE : srcFactor,
                GLSMConfig.hudCacheOverride ? GL11.GL_ONE_MINUS_SRC_ALPHA : dstFactor);
        }
        postVanillaBlendChangeIfMoved(snapshotted);
    }

    public static void glBlendEquation(int mode) {
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glBlendEquation in display lists not yet implemented - if you see this, please report!");
        }
        final boolean caching = isCachingEnabled();
        final boolean bypass = BYPASS_CACHE || !caching;
        if (bypass || ctx().blendState.getEquationRgb() != mode || ctx().blendState.getEquationAlpha() != mode) {
            if (caching) {
                mod(ctx().blendState);
                ctx().blendState.setEquationRgb(mode);
                ctx().blendState.setEquationAlpha(mode);
            }
            RENDER_BACKEND.blendEquation(mode);
        }
    }

    public static void glBlendEquationSeparate(int modeRGB, int modeAlpha) {
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glBlendEquationSeparate in display lists not yet implemented - if you see this, please report!");
        }
        final boolean caching = isCachingEnabled();
        final boolean bypass = BYPASS_CACHE || !caching;
        if (bypass || ctx().blendState.getEquationRgb() != modeRGB || ctx().blendState.getEquationAlpha() != modeAlpha) {
            if (caching) {
                mod(ctx().blendState);
                ctx().blendState.setEquationRgb(modeRGB);
                ctx().blendState.setEquationAlpha(modeAlpha);
            }
            RENDER_BACKEND.blendEquationSeparate(modeRGB, modeAlpha);
        }
    }

    public static void tryBlendFuncSeparate(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordBlendFunc(srcRgb, dstRgb, srcAlpha, dstAlpha);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean snapshotted = snapshotVanillaBlendFunc();
        final DeferredBlendHandler bh = GLSMHooks.blendHandler;
        if (bh != null && bh.isBlendLocked()) {
            ctx().blendState.beforeModify();
            bh.deferBlendFunc(srcRgb, dstRgb, srcAlpha, dstAlpha);
            postVanillaBlendChangeIfMoved(snapshotted);
            return;
        }
        // Cache thread check - only update state on main thread, but always make GL call if needed
        final boolean caching = isCachingEnabled();
        final boolean bypass = BYPASS_CACHE || !caching;
        if (bypass || ctx().blendState.getSrcRgb() != srcRgb || ctx().blendState.getDstRgb() != dstRgb || ctx().blendState.getSrcAlpha() != srcAlpha || ctx().blendState.getDstAlpha() != dstAlpha) {
            if (caching) mod(ctx().blendState).setAll(srcRgb, dstRgb, srcAlpha, dstAlpha);
            issueBlendFunc(srcRgb, dstRgb, srcAlpha, dstAlpha);
            postBlendFuncChange(
                srcRgb, dstRgb,
                GLSMConfig.hudCacheOverride ? GL11.GL_ONE : srcAlpha,
                GLSMConfig.hudCacheOverride ? GL11.GL_ONE_MINUS_SRC_ALPHA : dstAlpha);
        }
        postVanillaBlendChangeIfMoved(snapshotted);
    }

    public static void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        tryBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
    }

    /**
     * StellarCore and Gnetum render the HUD into a cached framebuffer and change the blend factors
     * directly on the driver. GLSM answers their blend calls from its cache, so their interceptors
     * never run and the cached blit would show the per-element alpha areas as fully transparent.
     * While the override is active the cache keeps the factors the caller asked for and only the
     * driver receives {@code (ONE, ONE_MINUS_SRC_ALPHA)} for the alpha factors.
     */
    public static void setHudCacheOverride(boolean enabled) {
        if (GLSMConfig.hudCacheOverride == enabled) return;
        GLSMConfig.hudCacheOverride = enabled;
        final DeferredBlendHandler bh = GLSMHooks.blendHandler;
        if (bh != null && bh.isOverrideHeld()) return;
        final BlendStateStack blend = ctx().blendState;
        issueBlendFunc(blend);
        blend.clearFuncUnknownState();
    }

    private static void issueBlendFunc(BlendStateStack blend) {
        issueBlendFunc(blend.getSrcRgb(), blend.getDstRgb(), blend.getSrcAlpha(), blend.getDstAlpha());
    }

    private static void issueBlendFunc(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        if (GLSMConfig.hudCacheOverride) {
            RENDER_BACKEND.blendFuncSeparate(srcRgb, dstRgb, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        } else {
            RENDER_BACKEND.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        }
    }

    private static void postBlendFuncChange(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
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

    public static void glNormal3b(byte nx, byte ny, byte nz) {
        final float fnx = b2f(nx), fny = b2f(ny), fnz = b2f(nz);
        ShaderManager.setCurrentNormal(fnx, fny, fnz);
        if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
            ImmediateModeRecorder.setNormal(fnx, fny, fnz);
            return;
        }
        ctx().dirtyNormalAttrib = true;
    }

    public static void glNormal3d(double nx, double ny, double nz) {
        final float fnx = (float) nx, fny = (float) ny, fnz = (float) nz;
        ShaderManager.setCurrentNormal(fnx, fny, fnz);
        if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
            ImmediateModeRecorder.setNormal(fnx, fny, fnz);
            return;
        }
        ctx().dirtyNormalAttrib = true;
    }

    public static void glNormal3f(float nx, float ny, float nz) { GLSMCurrentAttribs.glNormal3f(nx, ny, nz); }

    public static void glNormal3i(int nx, int ny, int nz) {
        final float fnx = nx / 2147483647.0f, fny = ny / 2147483647.0f, fnz = nz / 2147483647.0f;
        ShaderManager.setCurrentNormal(fnx, fny, fnz);
        if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
            ImmediateModeRecorder.setNormal(fnx, fny, fnz);
            return;
        }
        ctx().dirtyNormalAttrib = true;
    }

    public static void glDepthFunc(int func) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDepthFunc(func);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || func != ctx().depthState.getFunc()) {
            if (caching) mod(ctx().depthState).setFunc(func);
            RENDER_BACKEND.depthFunc(func);
        }
    }

    public static void glDepthMask(boolean mask) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDepthMask(mask);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final DeferredDepthColorHandler dch = GLSMHooks.depthColorHandler;
        if (dch != null && dch.isDepthColorLocked()) {
            ctx().depthState.beforeModify();
            dch.deferDepthEnable(mask);
            return;
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || mask != ctx().depthState.isMaskEnabled()) {
            if (caching) mod(ctx().depthState).setMaskEnabled(mask);
            RENDER_BACKEND.depthMask(mask);
        }
    }

    public static void glEdgeFlag(boolean flag) { GLSMCurrentAttribs.glEdgeFlag(flag); }

    public static void glColor4f(float red, float green, float blue, float alpha) { GLSMCurrentAttribs.glColor4f(red, green, blue, alpha); }

    public static void glColor4d(double red, double green, double blue, double alpha) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordColor((float) red, (float) green, (float) blue, (float) alpha);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        changeColor((float) red, (float) green, (float) blue, (float) alpha);
    }

    public static void glColor4b(byte red, byte green, byte blue, byte alpha) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordColor(b2f(red), b2f(green), b2f(blue), b2f(alpha));
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        changeColor(b2f(red), b2f(green), b2f(blue), b2f(alpha));
    }

    public static void glColor4ub(byte red, byte green, byte blue, byte alpha) { GLSMCurrentAttribs.glColor4ub(red, green, blue, alpha); }

    public static void glColor3f(float red, float green, float blue) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordColor(red, green, blue, 1.0F);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        changeColor(red, green, blue, 1.0F);
    }

    public static void glColor3d(double red, double green, double blue) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordColor((float) red, (float) green, (float) blue, 1.0F);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        changeColor((float) red, (float) green, (float) blue, 1.0F);
    }

    public static void glColor3b(byte red, byte green, byte blue) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordColor(b2f(red), b2f(green), b2f(blue), 1.0F);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        changeColor(b2f(red), b2f(green), b2f(blue), 1.0F);
    }

    public static void glColor3ub(byte red, byte green, byte blue) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordColor(ub2f(red), ub2f(green), ub2f(blue), 1.0F);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        changeColor(ub2f(red), ub2f(green), ub2f(blue), 1.0F);
    }

    static float ub2f(byte b) {
        return (b & 0xFF) / 255.0F;
    }

    private static float b2f(byte b) {
        return ((b - Byte.MIN_VALUE) & 0xFF) / 255.0F;
    }

    public static float i2f(int i) {return ((i - Integer.MIN_VALUE) & 0xFFFFFFFFL) / 4294967295.0F;}

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

    public static void glSecondaryColor3d(double red, double green, double blue) {
        glSecondaryColor3f((float) red, (float) green, (float) blue);
    }

    public static void glSecondaryColor3b(byte red, byte green, byte blue) {
        glSecondaryColor3f(b2f(red), b2f(green), b2f(blue));
    }

    public static void glSecondaryColor3ub(byte red, byte green, byte blue) {
        glSecondaryColor3f(ub2f(red), ub2f(green), ub2f(blue));
    }

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

    public static void glColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordColorMask(red, green, blue, alpha);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final DeferredDepthColorHandler dch = GLSMHooks.depthColorHandler;
        if (dch != null && dch.isDepthColorLocked()) {
            ctx().colorMask.beforeModify();
            dch.deferColorMask(red, green, blue, alpha);
            return;
        }
        // Cache thread check - only update state on main thread, but always make GL call if needed
        final boolean caching = isCachingEnabled();
        final boolean bypass = BYPASS_CACHE || !caching;
        if (bypass || red != ctx().colorMask.red || green != ctx().colorMask.green || blue != ctx().colorMask.blue || alpha != ctx().colorMask.alpha) {
            if (caching) mod(ctx().colorMask).setAll(red, green, blue, alpha);
            RENDER_BACKEND.colorMask(red, green, blue, alpha);
        }
    }

    public static void glClearColor(float red, float green, float blue, float alpha) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordClearColor(red, green, blue, alpha);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || red != ctx().clearColor.getRed() || green != ctx().clearColor.getGreen() || blue != ctx().clearColor.getBlue() || alpha != ctx().clearColor.getAlpha()) {
            if (caching) {
                mod(ctx().clearColor);
                ctx().clearColor.setRed(red);
                ctx().clearColor.setGreen(green);
                ctx().clearColor.setBlue(blue);
                ctx().clearColor.setAlpha(alpha);
            }
            RENDER_BACKEND.clearColor(red, green, blue, alpha);
        }
    }

    public static void glClearDepth(double depth) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordClearDepth(depth);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (isCachingEnabled()) mod(ctx().depthState).setClearValue(depth);
        RENDER_BACKEND.clearDepth(depth);
    }

    public static void enableAlphaTest() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordEnable(GL11.GL_ALPHA_TEST);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final DeferredAlphaHandler ah = GLSMHooks.alphaHandler;
        if (ah != null && ah.isAlphaTestLocked()) {
            ctx().alphaTest.beforeModify();
            ah.deferAlphaTestToggle(true);
        } else {
            ctx().alphaTest.enable();
            if (GLSMHooks.ALPHA_STATE_CHANGE.hasListeners()) {
                GLSMHooks.ALPHA_STATE_CHANGE.post(GLSMHooks.alphaStateChangeEvent);
            }
        }
        ctx().fragmentGeneration++;
    }

    public static void disableAlphaTest() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDisable(GL11.GL_ALPHA_TEST);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final DeferredAlphaHandler ah = GLSMHooks.alphaHandler;
        if (ah != null && ah.isAlphaTestLocked()) {
            ctx().alphaTest.beforeModify();
            ah.deferAlphaTestToggle(false);
        } else {
            ctx().alphaTest.disable();
            if (GLSMHooks.ALPHA_STATE_CHANGE.hasListeners()) {
                GLSMHooks.ALPHA_STATE_CHANGE.post(GLSMHooks.alphaStateChangeEvent);
            }
        }
        ctx().fragmentGeneration++;
    }

    public static void glAlphaFunc(int function, float reference) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordAlphaFunc(function, reference);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final DeferredAlphaHandler ah = GLSMHooks.alphaHandler;
        if (ah != null && ah.isAlphaTestLocked()) {
            ctx().alphaState.beforeModify();
            ah.deferAlphaFunc(function, reference);
            return;
        }
        if (isCachingEnabled()
            && (function != ctx().alphaState.getFunction() || Float.compare(reference, ctx().alphaState.getReference()) != 0)) {
            mod(ctx().alphaState);
            ctx().alphaState.setFunction(function);
            ctx().alphaState.setReference(reference);
            ctx().fragmentGeneration++;
            if (GLSMHooks.ALPHA_STATE_CHANGE.hasListeners()) {
                GLSMHooks.ALPHA_STATE_CHANGE.post(GLSMHooks.alphaStateChangeEvent);
            }
        }
    }

    public static void glActiveTexture(int texture) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordActiveTexture(texture);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final int newTexture = texture - GL13.GL_TEXTURE0;
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || getActiveTextureUnit() != newTexture) {
            if (caching) mod(ctx().activeTextureUnit).setValue(newTexture);
            RENDER_BACKEND.activeTexture(texture);
        }
    }

    public static void glActiveTextureARB(int texture) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordActiveTexture(texture);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final int newTexture = texture - GL13.GL_TEXTURE0;
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || getActiveTextureUnit() != newTexture) {
            if (caching) mod(ctx().activeTextureUnit).setValue(newTexture);
            RENDER_BACKEND.activeTexture(texture);
        }
    }

    public static void glClientActiveTexture(int texture) {
        final int unit = texture - GL13.GL_TEXTURE0;
        if (unit < 0 || unit >= MAX_TEXTURE_COORDS) {
            warnOnce("client-active-texture-" + texture,
                "glClientActiveTexture({}) is outside the {} supported texture coordinate units; ignoring", texture, MAX_TEXTURE_COORDS);
            return;
        }
        ctx().clientActiveTextureUnit = unit;
    }

    public static int getClientActiveTextureUnit() {
        return ctx().clientActiveTextureUnit;
    }

    private static int getBoundTexture() {
        return getBoundTexture(ctx().activeTextureUnit.getValue());
    }

    private static int getBoundTexture(int unit) {
        return ctx().textures.getTextureUnitBindings(unit).getBinding();
    }

    public static void glBindTexture(int target, int texture) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordBindTexture(target, texture);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        // Rebinding a deferred-delete texture rescues it (Mesa/compat-profile behavior)
        if (texture != 0) {
            deferredDeleteTextures.remove(texture);
            TextureInfoCache.INSTANCE.getInfo(texture).setTarget(target);
        }

        if (target != GL11.GL_TEXTURE_2D) {
            // Non-2D targets (e.g. GL_TEXTURE_2D_ARRAY) pass through without caching
            logUncachedTextureTarget(target);
            recordGpuCommand(GpuCommandType.TEXTURE_BIND, target, texture);
            RENDER_BACKEND.bindTexture(target, texture);
            return;
        }

        if (!isCachingEnabled()) {
            final int activeUnit = getActiveTextureUnitForServerState();
            recordGpuCommand(GpuCommandType.TEXTURE_BIND, target, texture);
            RENDER_BACKEND.bindTexture(target, texture);
            GLSMHooks.notifyTextureBindSync(activeUnit, texture);
            return;
        }

        final int activeUnit = ctx().activeTextureUnit.getValue();
        final TextureBinding textureUnit = ctx().textures.getTextureUnitBindings(activeUnit);
        final int cachedBinding = textureUnit.getBinding();

        if (cachedBinding != texture) {
            recordGpuCommand(GpuCommandType.TEXTURE_BIND, target, texture);
            RENDER_BACKEND.bindTexture(target, texture);
            mod(ctx().textures.getTextureUnitBindings(activeUnit)).setBinding(texture, target);
            if (texture != 0 && activeUnit > ctx().maxBoundTextureUnit) {
                ctx().maxBoundTextureUnit = activeUnit;
            }
            if (!lockBindCallback && ctx().activeTextureUnit.getValue() == 0) {
                lockBindCallback = true;
                if (GLSMHooks.TEXTURE_BIND.hasListeners()) {
                    GLSMHooks.textureBindEvent.textureId = texture;
                    GLSMHooks.TEXTURE_BIND.post(GLSMHooks.textureBindEvent);
                }
                RenderSystem.bindTextureToUnit(0, texture);
                lockBindCallback = false;
            }
        }
        // Mirror executed binds even during GL_COMPILE_AND_EXECUTE recording and on cache hits.
        GLSMHooks.notifyTextureBindSync(activeUnit, texture);
    }

    private static int changeFormatIfDeprecated(int internalformat) {
        internalformat = GLESFormatRemap.promoteAlphaFormat(internalformat);
        if (RenderSystem.isGLES()) {
            internalformat = GLESFormatRemap.remapInternalFormat(internalformat);
        }
        return internalformat;
    }

    public static final class GLESTexImageRemap {
        private final GLESFormatRemap.Result r;
        private GLESTexImageRemap(GLESFormatRemap.Result r) { this.r = r; }
        public int internalFormat() { return r.internalFormat(); }
        public int format() { return r.format(); }
        public int type() { return r.type(); }
    }

    public static GLESTexImageRemap remapTexImageForGLES(int internalformat, int format, int type) {
        return new GLESTexImageRemap(GLESFormatRemap.apply(internalformat, format, type, RenderSystem.isGLES()));
    }

    static {
        if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) {
            LOGGER.warn("GLSM GLES pixel-type remap assumes little-endian host; big-endian detected - BGRA uploads may be byte-swapped");
        }
    }

    private static int remapPixelTypeForGLES(int format, int type) {
        return RenderSystem.isGLES() ? GLESFormatRemap.remapPixelType(format, type) : type;
    }

    private static int remapTypeForRemappedInternalFormat(int internalformat, int type) {
        if (!RenderSystem.isGLES()) return type;
        if (!GLESFormatRemap.isGenericPixelType(type)) return type;
        return GLESFormatRemap.typeForInternalFormatES32(internalformat, type);
    }

    private static void logUncachedTextureTarget(int target) {
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

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, IntBuffer pixels) {
        internalformat = changeFormatIfDeprecated(internalformat);
        type = remapPixelTypeForGLES(format, type);
        type = remapTypeForRemappedInternalFormat(internalformat, type);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordComplexCommand(TexImage2DCmd.fromIntBuffer(target, level, internalformat, width, height, border, format, type, pixels, ctx().pixelUnpackState));
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        TextureInfoCache.INSTANCE.onTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        recordGpuCommand(GpuCommandType.TEX_IMAGE_2D, GpuCommandPhase.BEGIN, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        suspendPixelUnpackBuffer();
        if (shouldUseDSA(target)) {
            RenderSystem.textureImage2D(getBoundTextureForServerState(), target, level, internalformat, width, height, border, format, type, pixels);
        } else {
            // Non-main thread or proxy texture - use direct GL call
            RENDER_BACKEND.texImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        }
        restorePixelUnpackBuffer();
        recordGpuCommand(GpuCommandType.TEX_IMAGE_2D, GpuCommandPhase.END, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_IMAGE_2D);
        if (mode == RecordMode.NONE && RenderSystem.isLTW()) LTWWorkaround.onTexImage2D(target, level, format, pixels != null);
        maybeGenerateMipmap(target, level);

    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, FloatBuffer pixels) {
        internalformat = changeFormatIfDeprecated(internalformat);
        type = remapPixelTypeForGLES(format, type);
        type = remapTypeForRemappedInternalFormat(internalformat, type);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordComplexCommand(TexImage2DCmd.fromFloatBuffer(target, level, internalformat, width, height, border, format, type, pixels, ctx().pixelUnpackState));
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        TextureInfoCache.INSTANCE.onTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        recordGpuCommand(GpuCommandType.TEX_IMAGE_2D, GpuCommandPhase.BEGIN, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        suspendPixelUnpackBuffer();
        RENDER_BACKEND.texImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        restorePixelUnpackBuffer();
        recordGpuCommand(GpuCommandType.TEX_IMAGE_2D, GpuCommandPhase.END, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_IMAGE_2D);
        if (mode == RecordMode.NONE && RenderSystem.isLTW()) LTWWorkaround.onTexImage2D(target, level, format, pixels != null);
        maybeGenerateMipmap(target, level);

    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, DoubleBuffer pixels) {
        internalformat = changeFormatIfDeprecated(internalformat);
        type = remapPixelTypeForGLES(format, type);
        type = remapTypeForRemappedInternalFormat(internalformat, type);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordComplexCommand(TexImage2DCmd.fromDoubleBuffer(target, level, internalformat, width, height, border, format, type, pixels, ctx().pixelUnpackState));
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        TextureInfoCache.INSTANCE.onTexImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        recordGpuCommand(GpuCommandType.TEX_IMAGE_2D, GpuCommandPhase.BEGIN, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        suspendPixelUnpackBuffer();
        RENDER_BACKEND.texImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        restorePixelUnpackBuffer();
        recordGpuCommand(GpuCommandType.TEX_IMAGE_2D, GpuCommandPhase.END, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_IMAGE_2D);
        if (mode == RecordMode.NONE && RenderSystem.isLTW()) LTWWorkaround.onTexImage2D(target, level, format, pixels != null);
        maybeGenerateMipmap(target, level);

    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels) {
        internalformat = changeFormatIfDeprecated(internalformat);
        type = remapPixelTypeForGLES(format, type);
        type = remapTypeForRemappedInternalFormat(internalformat, type);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordComplexCommand(TexImage2DCmd.fromByteBuffer(target, level, internalformat, width, height, border, format, type, pixels, ctx().pixelUnpackState));
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        TextureInfoCache.INSTANCE.onTexImage2D(target, level, internalformat, width, height, border, format, type, pixels != null ? pixels.asIntBuffer() : null);
        recordGpuCommand(GpuCommandType.TEX_IMAGE_2D, GpuCommandPhase.BEGIN, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        suspendPixelUnpackBuffer();
        if (shouldUseDSA(target)) {
            RenderSystem.textureImage2D(getBoundTextureForServerState(), target, level, internalformat, width, height, border, format, type, pixels);
        } else {
            RENDER_BACKEND.texImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        }
        restorePixelUnpackBuffer();
        recordGpuCommand(GpuCommandType.TEX_IMAGE_2D, GpuCommandPhase.END, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_IMAGE_2D);
        if (mode == RecordMode.NONE && RenderSystem.isLTW()) LTWWorkaround.onTexImage2D(target, level, format, pixels != null);
        maybeGenerateMipmap(target, level);

    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height, int border, int format, int type, long pixels_buffer_offset) {
        internalformat = changeFormatIfDeprecated(internalformat);
        type = remapPixelTypeForGLES(format, type);
        type = remapTypeForRemappedInternalFormat(internalformat, type);
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glTexImage2D with buffer offset in display lists not yet supported");
        }
        TextureInfoCache.INSTANCE.onTexImage2D(target, level, internalformat, width, height, border, format, type, pixels_buffer_offset);
        recordGpuCommand(GpuCommandType.TEX_IMAGE_2D, GpuCommandPhase.BEGIN, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        RENDER_BACKEND.texImage2D(target, level, internalformat, width, height, border, format, type, pixels_buffer_offset);
        recordGpuCommand(GpuCommandType.TEX_IMAGE_2D, GpuCommandPhase.END, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_IMAGE_2D);
        maybeGenerateMipmap(target, level);

    }

    public static void glTexCoord1f(float s) {
        if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
            ImmediateModeRecorder.setTexCoord(s, 0.0f);
            return;
        }
        ShaderManager.setCurrentTexCoord(s, 0.0f, 0.0f, 1.0f);
        ctx().dirtyTexCoordAttrib = true;
    }

    public static void glTexCoord1d(double s) {
        if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
            ImmediateModeRecorder.setTexCoord((float) s, 0.0f);
            return;
        }
        ShaderManager.setCurrentTexCoord((float) s, 0.0f, 0.0f, 1.0f);
        ctx().dirtyTexCoordAttrib = true;
    }

    public static void glTexCoord2f(float s, float t) { GLSMCurrentAttribs.glTexCoord2f(s, t); }

    public static void glTexCoord2d(double s, double t) {
        if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
            ImmediateModeRecorder.setTexCoord((float) s, (float) t);
            return;
        }
        ShaderManager.setCurrentTexCoord((float) s, (float) t, 0.0f, 1.0f);
        ctx().dirtyTexCoordAttrib = true;
    }

    public static void glTexCoord3f(float s, float t, float r) {
        if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
            ImmediateModeRecorder.setTexCoord(s, t);  // Only track s,t for 2D textures
            return;
        }
        ShaderManager.setCurrentTexCoord(s, t, r, 1.0f);
        ctx().dirtyTexCoordAttrib = true;
    }

    public static void glTexCoord3d(double s, double t, double r) {
        if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
            ImmediateModeRecorder.setTexCoord((float) s, (float) t);
            return;
        }
        ShaderManager.setCurrentTexCoord((float) s, (float) t, (float) r, 1.0f);
        ctx().dirtyTexCoordAttrib = true;
    }

    public static void glTexCoord4f(float s, float t, float r, float q) { GLSMCurrentAttribs.glTexCoord4f(s, t, r, q); }

    public static void glTexCoord4d(double s, double t, double r, double q) {
        if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
            ImmediateModeRecorder.setTexCoord((float) s, (float) t);
            return;
        }
        ShaderManager.setCurrentTexCoord((float) s, (float) t, (float) r, (float) q);
        ctx().dirtyTexCoordAttrib = true;
    }

    public static void glDeleteTextures(int id) {
        deferDeleteTexture(id);
        onDeleteTexture(id);
    }

    public static void glDeleteTextures(IntBuffer ids) {
        for (int i = 0; i < ids.remaining(); i++) {
            final int id = ids.get(ids.position() + i);
            deferDeleteTexture(id);
            onDeleteTexture(id);
        }
    }

    public static void glDeleteTextures(int[] ids) {
        for (int id : ids) {
            glDeleteTextures(id);
        }
    }

    /** Shrink texture to 1x1 to free GPU memory, unbind from all units, but keep the name valid. */
    private static void deferDeleteTexture(int id) {
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
    private static void flushDeferredTextureDeletes() {
        if (deferredDeleteTextures.isEmpty()) return;
        final var it = deferredDeleteTextures.iterator();
        while (it.hasNext()) {
            RENDER_BACKEND.deleteTextures(it.nextInt());
        }
        deferredDeleteTextures.clear();
    }

    public static int glGenTextures() {
        flushDeferredTextureDeletes();
        return RENDER_BACKEND.genTextures();
    }

    public static void glGenTextures(IntBuffer textures) {
        flushDeferredTextureDeletes();
        RENDER_BACKEND.genTextures(textures);
    }

    public static void glGenTextures(int[] textures) {
        requireContext("glGenTextures");
        flushDeferredTextureDeletes();
        for (int i = 0; i < textures.length; i++) textures[i] = RENDER_BACKEND.genTextures();
    }

    public static void enableTexture() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordEnable(GL11.GL_TEXTURE_2D);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final int textureUnit = getActiveTextureUnit();
        postTextureUnitState(textureUnit, true);
        ctx().textures.getTextureUnitStates(textureUnit).enable();
    }

    public static void disableTexture() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDisable(GL11.GL_TEXTURE_2D);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final int textureUnit = getActiveTextureUnit();
        postTextureUnitState(textureUnit, false);
        ctx().textures.getTextureUnitStates(textureUnit).disable();
    }

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

    private static void postTextureUnitState(int textureUnit, boolean enabled) {
        if (!GLSMHooks.TEXTURE_UNIT_STATE.hasListeners()) {
            return;
        }
        GLSMHooks.textureUnitStateEvent.unit = textureUnit;
        GLSMHooks.textureUnitStateEvent.target = GL11.GL_TEXTURE_2D;
        GLSMHooks.textureUnitStateEvent.enabled = enabled;
        GLSMHooks.TEXTURE_UNIT_STATE.post(GLSMHooks.textureUnitStateEvent);
    }

    private static final String PIXELTRANSFER_MSG = "glPixelTransfer is not available in GL 3.3 core profile.";

    public static void glPixelTransferf(int pname, float param) {
        guardUnsupportedFFP("glPixelTransfer", PIXELTRANSFER_MSG);
    }

    public static void glPixelTransferi(int pname, int param) {
        guardUnsupportedFFP("glPixelTransfer", PIXELTRANSFER_MSG);
    }

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

    public static void setFilter(boolean bilinear, boolean mipmap) {
        final int i, j;
        if (bilinear) {
            i = mipmap ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_LINEAR;
            j = GL11.GL_LINEAR;
        } else {
            i = mipmap ? GL11.GL_LINEAR_MIPMAP_LINEAR : GL11.GL_NEAREST;
            j = GL11.GL_NEAREST;
        }
        glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, i);
        glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, j);
    }

    public static void glBegin(int mode) { GLSMDraws.glBegin(mode); }

    public static void glEnd() { GLSMDraws.glEnd(); }

    // Vertex methods for display list recording and live immediate mode
    public static void glVertex2f(float x, float y) { GLSMDraws.glVertex2f(x, y); }

    public static void glVertex2i(int x, int y) {
        if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
            ImmediateModeRecorder.vertex((float) x, (float) y, 0.0f);
        }
    }

    public static void glVertex2d(double x, double y) {
        if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
            ImmediateModeRecorder.vertex((float) x, (float) y, 0.0f);
        }
    }

    public static void glVertex3f(float x, float y, float z) { GLSMDraws.glVertex3f(x, y, z); }

    public static void glVertex3d(double x, double y, double z) {
        if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
            ImmediateModeRecorder.vertex((float) x, (float) y, (float) z);
        }
    }

    public static void glVertex3i(int x, int y, int z) {
        if (DisplayListManager.isRecording() || ImmediateModeRecorder.isDrawing()) {
            ImmediateModeRecorder.vertex((float) x, (float) y, (float) z);
        }
    }

    public static void glDrawElements(int mode, ByteBuffer indices) { GLSMDraws.glDrawElements(mode, indices); }

    public static void glDrawElements(int mode, IntBuffer indices) { GLSMDraws.glDrawElements(mode, indices); }

    public static void glDrawElements(int mode, ShortBuffer indices) { GLSMDraws.glDrawElements(mode, indices); }

    public static void glDrawElements(int mode, int count, int type, ByteBuffer indices) { GLSMDraws.glDrawElements(mode, count, type, indices); }

    public static void glDrawElements(int mode, int count, ByteBuffer indices) { GLSMDraws.glDrawElements(mode, count, indices); }

    public static void glDrawElements(int mode, int indices_count, int type, long indices_buffer_offset) { GLSMDraws.glDrawElements(mode, indices_count, type, indices_buffer_offset); }

    public static void glDrawBuffer(int mode) {
        final RecordMode recordMode = DisplayListManager.getRecordMode();
        if (recordMode != RecordMode.NONE) {
            DisplayListManager.recordDrawBuffer(mode);
            if (recordMode == RecordMode.COMPILE) {
                return;
            }
        }
        if (isCachingEnabled()) mod(ctx().drawBuffer).setValue(mode);
        RENDER_BACKEND.drawBuffer(mode);
    }

    public static void glMultiDrawElementsIndirect(int mode, int type, long indirect, int drawcount, int stride) {
        preDrawFFP();
        prepareClientArrays();
        recordGpuCommand(GpuCommandType.DRAW_ELEMENTS, mode, drawcount);
        RENDER_BACKEND.multiDrawElementsIndirect(mode, type, indirect, drawcount, stride);
    }

    public static void glMultiDrawElementsBaseVertex(int mode, long pCount, int type, long pIndices, int drawcount, long pBaseVertex) {
        preDrawFFP();
        prepareClientArrays();
        recordGpuCommand(GpuCommandType.DRAW_ELEMENTS, mode, drawcount);
        RENDER_BACKEND.multiDrawElementsBaseVertex(mode, pCount, type, pIndices, drawcount, pBaseVertex);
    }

    public static void glDrawElementsBaseVertex(int mode, int count, int type, long indices, int basevertex) {
        preDrawFFP();
        prepareClientArrays();
        recordGpuCommand(GpuCommandType.DRAW_ELEMENTS, mode, count);
        RENDER_BACKEND.drawElementsBaseVertex(mode, count, type, indices, basevertex);
    }

    public static void glDrawRangeElements(int mode, int start, int end, int count, int type, long indices) {
        preDrawFFP();
        prepareClientArrays();
        recordGpuCommand(GpuCommandType.DRAW_ELEMENTS, mode, count);
        RENDER_BACKEND.drawRangeElements(mode, start, end, count, type, indices);
    }

    public static void glDrawRangeElementsBaseVertex(int mode, int start, int end, int count, int type, long indices, int baseVertex) {
        preDrawFFP();
        prepareClientArrays();
        recordGpuCommand(GpuCommandType.DRAW_ELEMENTS, mode, count);
        RENDER_BACKEND.drawRangeElementsBaseVertex(mode, start, end, count, type, indices, baseVertex);
    }

    public static void glMultiDrawArrays(int mode, IntBuffer firsts, IntBuffer counts) {
        preDrawFFP();
        prepareClientArrays();
        recordGpuCommand(GpuCommandType.DRAW_ARRAYS, mode, counts.remaining());
        RENDER_BACKEND.multiDrawArrays(mode, firsts, counts);
    }

    public static void glMultiDrawArrays(int mode, int[] firsts, int[] counts) {
        preDrawFFP();
        prepareClientArrays();
        recordGpuCommand(GpuCommandType.DRAW_ARRAYS, mode, counts.length);
        RENDER_BACKEND.multiDrawArrays(mode, firsts, counts);
    }

    public static void glMultiDrawArraysIndirectCount(int mode, long indirect, long drawcount, int maxdrawcount, int stride) {
        preDrawFFP();
        prepareClientArrays();
        recordGpuCommand(GpuCommandType.DRAW_ARRAYS, mode, maxdrawcount);
        RENDER_BACKEND.multiDrawArraysIndirectCount(mode, indirect, drawcount, maxdrawcount, stride);
    }

    public static void glMultiDrawArraysIndirect(int mode, long indirect, int drawcount, int stride) {
        preDrawFFP();
        prepareClientArrays();
        recordGpuCommand(GpuCommandType.DRAW_ARRAYS, mode, drawcount);
        RENDER_BACKEND.multiDrawArraysIndirect(mode, indirect, drawcount, stride);
    }

    public static void glMultiDrawElementsIndirectCount(int mode, int type, long indirect, long drawcount, int maxdrawcount, int stride) {
        preDrawFFP();
        prepareClientArrays();
        recordGpuCommand(GpuCommandType.DRAW_ELEMENTS, mode, maxdrawcount);
        RENDER_BACKEND.multiDrawElementsIndirectCount(mode, type, indirect, drawcount, maxdrawcount, stride);
    }

    public static void glPrimitiveRestartIndex(int index) {
        RENDER_BACKEND.primitiveRestartIndex(index);
    }

    public static void glPointParameterf(int pname, float param) {
        pointParameter(pname, param, 0.0f, 0.0f);
    }

    public static void glPointParameteri(int pname, int param) {
        pointParameter(pname, param, 0.0f, 0.0f);
    }

    public static void glPointParameter(int pname, FloatBuffer params) {
        final int pos = params.position();
        final boolean attenuation = pname == GL14.GL_POINT_DISTANCE_ATTENUATION;
        pointParameter(pname, params.get(pos), attenuation ? params.get(pos + 1) : 0.0f, attenuation ? params.get(pos + 2) : 0.0f);
    }

    public static void glPointParameter(int pname, IntBuffer params) {
        final int pos = params.position();
        final boolean attenuation = pname == GL14.GL_POINT_DISTANCE_ATTENUATION;
        pointParameter(pname, params.get(pos), attenuation ? params.get(pos + 1) : 0.0f, attenuation ? params.get(pos + 2) : 0.0f);
    }

    /**
     * Applies one {@code glPointParameter} call. {@code GL_POINT_SIZE_MIN}/{@code GL_MAX} and
     * {@code GL_POINT_DISTANCE_ATTENUATION} were removed from the core profile, so those only update the
     * tracked state; the remaining targets are stored and forwarded.
     */
    private static void pointParameter(int pname, float v0, float v1, float v2) {
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

    private static void applyPointState(GLContextState glCtx) {
        final PointStateStack point = glCtx.pointState;
        RENDER_BACKEND.pointSize(point.getSize());
        RENDER_BACKEND.pointParameterf(GL14.GL_POINT_FADE_THRESHOLD_SIZE, point.getFadeThreshold());
        RENDER_BACKEND.pointParameteri(GL20.GL_POINT_SPRITE_COORD_ORIGIN, point.getSpriteCoordOrigin());
    }

    public static boolean supportsGeometryShaders() { return RENDER_BACKEND.supportsGeometryShaders(); }

    public static boolean framebufferCompletenessIsMeaningful() { return RENDER_BACKEND.framebufferCompletenessIsMeaningful(); }

    public static String getRenderBackendName() {
        return RENDER_BACKEND.getName();
    }

    /** Full pre-draw preparation for an indexed/unprepared draw of the given GL primitive mode. */
    public static void preDraw(int drawMode) {
        prepareWideLineEmulation(drawMode);
        preDrawFFP();
        prepareClientArrays();
    }

    /** Full pre-draw preparation when the primitive mode is unknown or not line-based. */
    public static void preDraw() {
        wideLineEmulationActive = false;
        setLineStippleActive(false);
        preDrawFFP();
        prepareClientArrays();
    }

    public static boolean consumeUnit23TexCoordSetDuringDraw() {
        final GLContextState glCtx = ctx();
        final boolean v = glCtx.unit23TexCoordSetDuringDraw;
        glCtx.unit23TexCoordSetDuringDraw = false;
        return v;
    }

    public static void forceAttribDefaultsDirty() {
        final GLContextState glCtx = ctx();
        glCtx.dirtyNormalAttrib = true;
        glCtx.dirtyTexCoordAttrib = true;
    }

    public static void trackMaxBoundTextureUnit(int unit) {
        final GLContextState glCtx = ctx();
        if (unit > glCtx.maxBoundTextureUnit) glCtx.maxBoundTextureUnit = unit;
    }

    public static void setOverlayColor(float r, float g, float b, float a) {
        final GLContextState glCtx = ctx();
        if (r == glCtx.overlayR && g == glCtx.overlayG && b == glCtx.overlayB && a == glCtx.overlayA) return;
        glCtx.overlayR = r; glCtx.overlayG = g; glCtx.overlayB = b; glCtx.overlayA = a;
        glCtx.fragmentGeneration++;
    }

    public static void setShaderColor(float r, float g, float b, float a) {
        final GLContextState glCtx = ctx();
        if (r == glCtx.shaderColorR && g == glCtx.shaderColorG && b == glCtx.shaderColorB && a == glCtx.shaderColorA) return;
        glCtx.shaderColorR = r; glCtx.shaderColorG = g; glCtx.shaderColorB = b; glCtx.shaderColorA = a;
        if (GLSMHooks.SHADER_COLOR_CHANGE.hasListeners()) {
            GLSMHooks.shaderColorChangeEvent.red = r;
            GLSMHooks.shaderColorChangeEvent.green = g;
            GLSMHooks.shaderColorChangeEvent.blue = b;
            GLSMHooks.shaderColorChangeEvent.alpha = a;
            GLSMHooks.SHADER_COLOR_CHANGE.post(GLSMHooks.shaderColorChangeEvent);
        }
    }

    public static void forcePixelUnpackState(PixelStoreState target) {
        PixelStoreState.applyDiff(ctx().pixelUnpackState, target, false);
    }

    public static void restorePixelUnpackState(PixelStoreState applied) {
        PixelStoreState.applyDiff(applied, ctx().pixelUnpackState, false);
    }

    public static void incrementFragmentGeneration() {
        ctx().fragmentGeneration++;
    }

    public static void glDrawElementsInstanced(int mode, int count, int type, long indices, int primcount) {
        preDrawFFP();
        prepareClientArrays();
        recordGpuCommand(GpuCommandType.DRAW_ELEMENTS, mode, count);
        RENDER_BACKEND.drawElementsInstanced(mode, count, type, indices, primcount);
    }

    public static void glDrawArraysInstanced(int mode, int first, int count, int primcount) {
        prepareWideLineEmulation(mode);
        preDrawFFP();
        prepareClientArrays(first, count);
        recordGpuCommand(GpuCommandType.DRAW_ARRAYS, mode, count);
        if (mode == GL11.GL_QUADS) {
            QuadConverter.drawQuadsAsTrianglesInstanced(first, count, primcount);
        } else if (mode == GL11.GL_QUAD_STRIP) {
            RENDER_BACKEND.drawArraysInstanced(GL11.GL_TRIANGLE_STRIP, first, count & ~1, primcount);
        } else if (mode == GL11.GL_POLYGON) {
            RENDER_BACKEND.drawArraysInstanced(GL11.GL_TRIANGLE_FAN, first, count, primcount);
        } else {
            RENDER_BACKEND.drawArraysInstanced(mode, first, count, primcount);
        }
    }

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

    public static void glColorPointer(int size, int stride, FloatBuffer pointer) {
        ffpClientArrayPointer(VAOManager.CONV_COLOR, size, GL11.GL_FLOAT, Usage.COLOR.isNormalized(), stride, MemoryUtilities.memByteBuffer(pointer));
    }

    public static void glColorPointer(int size, int stride, DoubleBuffer pointer) {
        ffpClientArrayPointer(VAOManager.CONV_COLOR, size, GL11.GL_DOUBLE, Usage.COLOR.isNormalized(), stride, MemoryUtilities.memByteBuffer(pointer));
    }

    public static void glColorPointer(int size, int type, int stride, ByteBuffer pointer) {
        ffpClientArrayPointer(VAOManager.CONV_COLOR, size, type, Usage.COLOR.isNormalized(), stride, pointer);
    }

    public static void glColorPointer(int size, boolean unsigned, int stride, ByteBuffer pointer) {
        final int type = unsigned ? GL11.GL_UNSIGNED_BYTE : GL11.GL_BYTE;
        ffpClientArrayPointer(VAOManager.CONV_COLOR, size, type, Usage.COLOR.isNormalized(), stride, pointer);
    }

    public static void glColorPointer(int size, int type, int stride, long pointer_buffer_offset) {
        ffpClientArrayPointer(VAOManager.CONV_COLOR, size, type, Usage.COLOR.isNormalized(), stride, pointer_buffer_offset);
    }

    public static void glNormalPointer(int stride, FloatBuffer pointer) {
        glNormalPointer(GL11.GL_FLOAT, stride, pointer);
    }

    public static void glNormalPointer(int stride, ByteBuffer pointer) {
        glNormalPointer(GL11.GL_BYTE, stride, pointer);
    }

    public static void glNormalPointer(int stride, IntBuffer pointer) {
        glNormalPointer(GL11.GL_INT, stride, pointer);
    }

    public static void glNormalPointer(int stride, DoubleBuffer pointer) {
        glNormalPointer(GL11.GL_DOUBLE, stride, pointer);
    }

    public static void glNormalPointer(int type, int stride, ByteBuffer pointer) {
        ffpClientArrayPointer(VAOManager.CONV_NORMAL, 3, type, Usage.NORMAL.isNormalized(), stride, pointer);
    }

    public static void glNormalPointer(int type, int stride, FloatBuffer pointer) {
        ffpClientArrayPointer(VAOManager.CONV_NORMAL, 3, GL11.GL_FLOAT, Usage.NORMAL.isNormalized(), stride, MemoryUtilities.memByteBuffer(pointer));
    }

    public static void glNormalPointer(int type, int stride, ShortBuffer pointer) {
        ffpClientArrayPointer(VAOManager.CONV_NORMAL, 3, GL11.GL_SHORT, Usage.NORMAL.isNormalized(), stride, MemoryUtilities.memByteBuffer(pointer));
    }

    public static void glNormalPointer(int type, int stride, IntBuffer pointer) {
        ffpClientArrayPointer(VAOManager.CONV_NORMAL, 3, GL11.GL_INT, Usage.NORMAL.isNormalized(), stride, MemoryUtilities.memByteBuffer(pointer));
    }

    public static void glNormalPointer(int type, int stride, DoubleBuffer pointer) {
        ffpClientArrayPointer(VAOManager.CONV_NORMAL, 3, GL11.GL_DOUBLE, Usage.NORMAL.isNormalized(), stride, MemoryUtilities.memByteBuffer(pointer));
    }

    public static void glNormalPointer(int type, int stride, long pointer_buffer_offset) {
        ffpClientArrayPointer(VAOManager.CONV_NORMAL, 3, type, Usage.NORMAL.isNormalized(), stride, pointer_buffer_offset);
    }

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

    public static void glVertexAttrib3f(int index, float v0, float v1, float v2) {
        RENDER_BACKEND.vertexAttrib3f(index, v0, v1, v2);
    }

    public static void glVertexAttrib4f(int index, float v0, float v1, float v2, float v3) { GLSMCurrentAttribs.glVertexAttrib4f(index, v0, v1, v2, v3); }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long offset) { GLSMVertexInput.glVertexAttribPointer(index, size, type, normalized, stride, offset); }

    public static void glVertexAttribPointer(int index, int size, boolean normalized, int stride, FloatBuffer pointer) { GLSMVertexInput.glVertexAttribPointer(index, size, normalized, stride, pointer); }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, FloatBuffer pointer) { GLSMVertexInput.glVertexAttribPointer(index, size, type, normalized, stride, pointer); }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, DoubleBuffer pointer) { GLSMVertexInput.glVertexAttribPointer(index, size, type, normalized, stride, pointer); }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, IntBuffer pointer) { GLSMVertexInput.glVertexAttribPointer(index, size, type, normalized, stride, pointer); }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, ShortBuffer pointer) { GLSMVertexInput.glVertexAttribPointer(index, size, type, normalized, stride, pointer); }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, ByteBuffer pointer) { GLSMVertexInput.glVertexAttribPointer(index, size, type, normalized, stride, pointer); }

    public static void glVertexAttribIPointer(int index, int size, int type, int stride, long offset) {
        if (!isLegalVertexAttrib(index, size, type, stride, true)) return;
        if (ctx().vaos.setIntegerAttribute(index, size, type, stride, offset, ctx().boundVBO)) {
            RENDER_BACKEND.vertexAttribIPointer(index, size, type, stride, offset);
        }
    }

    public static void glVertexAttribIPointer(int index, int size, int type, int stride, ByteBuffer pointer) {
        if (!isLegalVertexAttrib(index, size, type, stride, true)) return;
        ctx().vaos.setIntegerAttribute(index, size, type, stride, pointer, 0);
    }

    public static void glVertexAttribIPointer(int index, int size, int type, int stride, IntBuffer pointer) {
        glVertexAttribIPointer(index, size, type, stride, MemoryUtilities.memByteBuffer(pointer));
    }

    public static void glVertexAttribIPointer(int index, int size, int type, int stride, ShortBuffer pointer) {
        glVertexAttribIPointer(index, size, type, stride, MemoryUtilities.memByteBuffer(pointer));
    }

    public static void glVertexAttribDivisor(int index, int divisor) {
        if (ctx().vaos.setDivisor(index, divisor)) RENDER_BACKEND.vertexAttribDivisor(index, divisor);
    }

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

    public static void glPushClientAttrib(int mask) {
        final int slot = ctx().clientAttribStackPointer;
        if (slot >= CLIENT_ATTRIB_STACK_DEPTH) {
            warnOnce("pushclientattrib-overflow", "glPushClientAttrib called with a full client attribute stack ({} deep); ignoring", CLIENT_ATTRIB_STACK_DEPTH);
            return;
        }
        ctx().vaos.saveClientArrays(slot, mask, ctx().boundVAO, ctx().boundVBO);
        ctx().clientAttribSavedTextureUnit[slot] = ctx().clientActiveTextureUnit;
        ctx().clientAttribSavedVertexFlags[slot] = ShaderManager.getInstance().getCurrentVertexFlags();
        if ((mask & GL11.GL_CLIENT_PIXEL_STORE_BIT) != 0) {
            ctx().clientAttribSavedPixelUnpack[slot] = ctx().pixelUnpackState;
            ctx().clientAttribSavedPixelPack[slot] = ctx().pixelPackState;
        }
        ctx().clientAttribStackPointer = slot + 1;
    }

    public static void glPopClientAttrib() {
        if (ctx().clientAttribStackPointer == 0) {
            warnOnce("popclientattrib-underflow", "glPopClientAttrib called with an empty client attribute stack; ignoring");
            return;
        }
        final int slot = --ctx().clientAttribStackPointer;
        final int mask = ctx().vaos.savedClientAttribMask(slot);
        if ((mask & GL11.GL_CLIENT_PIXEL_STORE_BIT) != 0) {
            restorePixelStore(ctx().clientAttribSavedPixelUnpack[slot], ctx().clientAttribSavedPixelPack[slot]);
            ctx().clientAttribSavedPixelUnpack[slot] = null;
            ctx().clientAttribSavedPixelPack[slot] = null;
        }
        if ((mask & GL11.GL_CLIENT_VERTEX_ARRAY_BIT) == 0) return;
        ctx().vaos.restoreClientArrays(slot);
        ctx().clientActiveTextureUnit = ctx().clientAttribSavedTextureUnit[slot];
        ShaderManager.getInstance().setCurrentVertexFlags(ctx().clientAttribSavedVertexFlags[slot]);
    }

    /** Drives the driver back to the pixel store state a matching {@code glPushClientAttrib} recorded. */
    private static void restorePixelStore(PixelStoreState unpack, PixelStoreState pack) {
        PixelStoreState.applyDiff(ctx().pixelUnpackState, unpack, false);
        ctx().pixelUnpackState = unpack;
        PixelStoreState.applyDiff(ctx().pixelPackState, pack, true);
        ctx().pixelPackState = pack;
    }

    public static void glInterleavedArrays(int format, int stride, long pointer) {
        if (!ShaderManager.getInstance().isActive()) {
            return;
        }

        // Mesa _mesa_get_interleaved_layout decomposition
        final int f = 4; // sizeof(float)
        final int c = f * ((4 + (f - 1)) / f); // 4 ubytes padded to float alignment = 4

        boolean tflag = false, cflag = false, nflag = false;
        int tcomps = 0, ccomps = 0, vcomps = 0;
        int ctype = 0;
        final int toffset = 0;
        int coffset = 0;
        int noffset = 0;
        int voffset = 0;
        final int defstride;

        switch (format) {
            case GL11.GL_V2F:
                vcomps = 2; defstride = 2 * f; break;
            case GL11.GL_V3F:
                vcomps = 3; defstride = 3 * f; break;
            case GL11.GL_C4UB_V2F:
                cflag = true; ccomps = 4; vcomps = 2;
                ctype = GL11.GL_UNSIGNED_BYTE; voffset = c; defstride = c + 2 * f; break;
            case GL11.GL_C4UB_V3F:
                cflag = true; ccomps = 4; vcomps = 3;
                ctype = GL11.GL_UNSIGNED_BYTE; voffset = c; defstride = c + 3 * f; break;
            case GL11.GL_C3F_V3F:
                cflag = true; ccomps = 3; vcomps = 3;
                ctype = GL11.GL_FLOAT; voffset = 3 * f; defstride = 6 * f; break;
            case GL11.GL_N3F_V3F:
                nflag = true; vcomps = 3;
                voffset = 3 * f; defstride = 6 * f; break;
            case GL11.GL_C4F_N3F_V3F:
                cflag = true; nflag = true; ccomps = 4; vcomps = 3;
                ctype = GL11.GL_FLOAT; noffset = 4 * f; voffset = 7 * f; defstride = 10 * f; break;
            case GL11.GL_T2F_V3F:
                tflag = true; tcomps = 2; vcomps = 3;
                voffset = 2 * f; defstride = 5 * f; break;
            case GL11.GL_T4F_V4F:
                tflag = true; tcomps = 4; vcomps = 4;
                voffset = 4 * f; defstride = 8 * f; break;
            case GL11.GL_T2F_C4UB_V3F:
                tflag = true; cflag = true; tcomps = 2; ccomps = 4; vcomps = 3;
                ctype = GL11.GL_UNSIGNED_BYTE; coffset = 2 * f; voffset = c + 2 * f; defstride = c + 5 * f; break;
            case GL11.GL_T2F_C3F_V3F:
                tflag = true; cflag = true; tcomps = 2; ccomps = 3; vcomps = 3;
                ctype = GL11.GL_FLOAT; coffset = 2 * f; voffset = 5 * f; defstride = 8 * f; break;
            case GL11.GL_T2F_N3F_V3F:
                tflag = true; nflag = true; tcomps = 2; vcomps = 3;
                noffset = 2 * f; voffset = 5 * f; defstride = 8 * f; break;
            case GL11.GL_T2F_C4F_N3F_V3F:
                tflag = true; cflag = true; nflag = true; tcomps = 2; ccomps = 4; vcomps = 3;
                ctype = GL11.GL_FLOAT; coffset = 2 * f; noffset = 6 * f; voffset = 9 * f; defstride = 12 * f; break;
            case GL11.GL_T4F_C4F_N3F_V4F:
                tflag = true; cflag = true; nflag = true; tcomps = 4; ccomps = 4; vcomps = 4;
                ctype = GL11.GL_FLOAT; coffset = 4 * f; noffset = 8 * f; voffset = 11 * f; defstride = 15 * f; break;
            default:
                return; // Invalid format
        }

        if (stride == 0) stride = defstride;

        if (tflag) {
            glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            glTexCoordPointer(tcomps, GL11.GL_FLOAT, stride, pointer + toffset);
        } else {
            glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        }

        if (cflag) {
            glEnableClientState(GL11.GL_COLOR_ARRAY);
            glColorPointer(ccomps, ctype, stride, pointer + coffset);
        } else {
            glDisableClientState(GL11.GL_COLOR_ARRAY);
        }

        if (nflag) {
            glEnableClientState(GL11.GL_NORMAL_ARRAY);
            glNormalPointer(GL11.GL_FLOAT, stride, pointer + noffset);
        } else {
            glDisableClientState(GL11.GL_NORMAL_ARRAY);
        }

        glEnableClientState(GL11.GL_VERTEX_ARRAY);
        glVertexPointer(vcomps, GL11.GL_FLOAT, stride, pointer + voffset);
    }

    public static void glInterleavedArrays(int format, int stride, ByteBuffer pointer) {
        glInterleavedArrays(format, stride, MemoryUtilities.memAddress0(pointer));
    }

    public static void glInterleavedArrays(int format, int stride, FloatBuffer pointer) {
        glInterleavedArrays(format, stride, MemoryUtilities.memAddress0(MemoryUtilities.memByteBuffer(pointer)));
    }

    public static void glInterleavedArrays(int format, int stride, DoubleBuffer pointer) {
        glInterleavedArrays(format, stride, MemoryUtilities.memAddress0(MemoryUtilities.memByteBuffer(pointer)));
    }

    public static void glInterleavedArrays(int format, int stride, IntBuffer pointer) {
        glInterleavedArrays(format, stride, MemoryUtilities.memAddress0(MemoryUtilities.memByteBuffer(pointer)));
    }

    public static void glInterleavedArrays(int format, int stride, ShortBuffer pointer) {
        glInterleavedArrays(format, stride, MemoryUtilities.memAddress0(MemoryUtilities.memByteBuffer(pointer)));
    }

    public static void glLogicOp(int opcode) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordLogicOp(opcode);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (isCachingEnabled()) mod(ctx().logicOpMode).setValue(opcode);
        RENDER_BACKEND.logicOp(opcode);
    }

    public static void defaultBlendFunc() {
        tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
    }

    public static void enableCull() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordEnable(GL11.GL_CULL_FACE);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        ctx().cullState.enable();
    }

    public static void disableCull() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDisable(GL11.GL_CULL_FACE);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        ctx().cullState.disable();
    }

    public static void enableDepthTest() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordEnable(GL11.GL_DEPTH_TEST);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        ctx().depthTest.enable();
    }

    public static void disableDepthTest() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDisable(GL11.GL_DEPTH_TEST);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        ctx().depthTest.disable();
    }

    public static void enableLighting() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordEnable(GL11.GL_LIGHTING);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        ctx().lightingState.enable();
    }

    public static void enableLight(int light) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordEnable(GL11.GL_LIGHT0 + light);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        ctx().lightStates[light].enable();
    }

    public static void enableColorMaterial() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordEnable(GL11.GL_COLOR_MATERIAL);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        ctx().colorMaterial.enable();
        final float r = getColor().getRed();
        final float g = getColor().getGreen();
        final float b = getColor().getBlue();
        final float a = getColor().getAlpha();
        if (ctx().colorMaterialFace.getValue() == GL11.GL_FRONT || ctx().colorMaterialFace.getValue() == GL11.GL_FRONT_AND_BACK) {
            switch (ctx().colorMaterialParameter.getValue()) {
                case GL11.GL_AMBIENT_AND_DIFFUSE -> {
                    ctx().frontMaterial.ambient.set(r, g, b, a);
                    ctx().frontMaterial.diffuse.set(r, g, b, a);
                }
                case GL11.GL_AMBIENT -> ctx().frontMaterial.ambient.set(r, g, b, a);
                case GL11.GL_DIFFUSE -> ctx().frontMaterial.diffuse.set(r, g, b, a);
                case GL11.GL_SPECULAR -> ctx().frontMaterial.specular.set(r, g, b, a);
                case GL11.GL_EMISSION -> ctx().frontMaterial.emission.set(r, g, b, a);
            }
        }
        if (ctx().colorMaterialFace.getValue() == GL11.GL_BACK || ctx().colorMaterialFace.getValue() == GL11.GL_FRONT_AND_BACK) {
            switch (ctx().colorMaterialParameter.getValue()) {
                case GL11.GL_AMBIENT_AND_DIFFUSE -> {
                    ctx().backMaterial.ambient.set(r, g, b, a);
                    ctx().backMaterial.diffuse.set(r, g, b, a);
                }
                case GL11.GL_AMBIENT -> ctx().backMaterial.ambient.set(r, g, b, a);
                case GL11.GL_DIFFUSE -> ctx().backMaterial.diffuse.set(r, g, b, a);
                case GL11.GL_SPECULAR -> ctx().backMaterial.specular.set(r, g, b, a);
                case GL11.GL_EMISSION -> ctx().backMaterial.emission.set(r, g, b, a);
            }
        }
    }

    public static void disableColorMaterial() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDisable(GL11.GL_COLOR_MATERIAL);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        ctx().colorMaterial.disable();
    }

    public static void disableLighting() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDisable(GL11.GL_LIGHTING);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        ctx().lightingState.disable();
    }

    public static void disableLight(int light) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDisable(GL11.GL_LIGHT0 + light);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        ctx().lightStates[light].disable();
    }

    public static void enableRescaleNormal() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordEnable(GL12.GL_RESCALE_NORMAL);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        ctx().rescaleNormalState.enable();
    }

    public static void disableRescaleNormal() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDisable(GL12.GL_RESCALE_NORMAL);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        ctx().rescaleNormalState.disable();
    }

    public static void enableFog() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordEnable(GL11.GL_FOG);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        ctx().fogMode.enable();
        if (GLSMHooks.FOG_STATE_CHANGE.hasListeners()) {
            GLSMHooks.FOG_STATE_CHANGE.post(GLSMHooks.fogStateChangeEvent);
        }
    }

    public static void disableFog() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDisable(GL11.GL_FOG);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        ctx().fogMode.disable();
        if (GLSMHooks.FOG_STATE_CHANGE.hasListeners()) {
            GLSMHooks.FOG_STATE_CHANGE.post(GLSMHooks.fogStateChangeEvent);
        }
    }

    public static void glFog(int pname, FloatBuffer param) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordFog(pname, param);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (HAS_MULTIPLE_SET.contains(pname)) {
            if (pname == GL11.GL_FOG_COLOR && isCachingEnabled()) {
                final float red = param.get(0);
                final float green = param.get(1);
                final float blue = param.get(2);

                mod(ctx().fogState);
                ctx().fogState.getFogColor().set(red, green, blue);
                ctx().fogState.setFogAlpha(param.get(3));
                ctx().fogState.getFogColorBuffer().clear();
                ctx().fogState.getFogColorBuffer().put((FloatBuffer) param.position(0)).flip();
                ctx().fragmentGeneration++;
                if (GLSMHooks.FOG_STATE_CHANGE.hasListeners()) {
                    GLSMHooks.FOG_STATE_CHANGE.post(GLSMHooks.fogStateChangeEvent);
                }
            }
        } else {
            GLStateManager.glFogf(pname, param.get(0));
        }
    }

    public static void glFog(int pname, IntBuffer param) {
        if (HAS_MULTIPLE_SET.contains(pname)) {
            final FloatBuffer floats = BufferUtils.createFloatBuffer(param.remaining());
            for (int i = param.position(); i < param.limit(); i++) {
                floats.put((2.0f * param.get(i) + 1.0f) / Integer.MAX_VALUE);
            }
            floats.flip();
            glFog(pname, floats);
        } else {
            glFogi(pname, param.get(0));
        }
    }

    public static Vector3d getFogColor() {
        return ctx().fogState.getFogColor();
    }

    public static void fogColor(float red, float green, float blue, float alpha) {
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || red != ctx().fogState.getFogColor().x || green != ctx().fogState.getFogColor().y || blue != ctx().fogState.getFogColor().z || alpha != ctx().fogState.getFogAlpha()) {
            if (caching) {
                mod(ctx().fogState);
                ctx().fogState.getFogColor().set(red, green, blue);
                ctx().fogState.setFogAlpha(alpha);
                ctx().fogState.getFogColorBuffer().clear();
                ctx().fogState.getFogColorBuffer().put(red).put(green).put(blue).put(alpha).flip();
                ctx().fragmentGeneration++;
                if (GLSMHooks.FOG_STATE_CHANGE.hasListeners()) {
                    GLSMHooks.FOG_STATE_CHANGE.post(GLSMHooks.fogStateChangeEvent);
                }
            }
        }
    }

    public static void glFogf(int pname, float param) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordFogf(pname, param);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (isCachingEnabled()) {
            boolean changed = true;
            switch (pname) {
                case GL11.GL_FOG_DENSITY -> {
                    if (ctx().fogState.getDensity() != param) {
                        mod(ctx().fogState).setDensity(param);
                        ctx().fragmentGeneration++;
                    }
                }
                case GL11.GL_FOG_START -> {
                    if (ctx().fogState.getStart() != param) {
                        mod(ctx().fogState).setStart(param);
                        ctx().fragmentGeneration++;
                    }
                }
                case GL11.GL_FOG_END -> {
                    if (ctx().fogState.getEnd() != param) {
                        mod(ctx().fogState).setEnd(param);
                        ctx().fragmentGeneration++;
                    }
                }
                case GL11.GL_FOG_MODE -> {
                    if (ctx().fogState.getFogMode() != (int) param) {
                        mod(ctx().fogState).setFogMode((int) param);
                        ctx().fragmentGeneration++;
                    }
                }
                default -> changed = false;
            }
            if (changed && GLSMHooks.FOG_STATE_CHANGE.hasListeners()) {
                GLSMHooks.FOG_STATE_CHANGE.post(GLSMHooks.fogStateChangeEvent);
            }
        }
    }

    public static void glFogi(int pname, int param) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordFogi(pname, param);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (isCachingEnabled() && pname == GL11.GL_FOG_MODE) {
            mod(ctx().fogState).setFogMode(param);
            ctx().fragmentGeneration++;
            if (GLSMHooks.FOG_STATE_CHANGE.hasListeners()) {
                GLSMHooks.FOG_STATE_CHANGE.post(GLSMHooks.fogStateChangeEvent);
            }
        }
    }

    public static void setFogBlack() {
        glFogf(GL11.GL_FOG_COLOR, 0.0F);
    }

    public static void glShadeModel(int mode) {
        final RecordMode recordMode = DisplayListManager.getRecordMode();
        if (recordMode != RecordMode.NONE) {
            DisplayListManager.recordShadeModel(mode);
            if (recordMode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        final int oldValue = ctx().shadeModelState.getValue();
        final boolean needsUpdate = BYPASS_CACHE || !caching || oldValue != mode;

        if (needsUpdate) {
            if (caching) {
                mod(ctx().shadeModelState).setValue(mode);
            }
        }
    }

    private static void onDeleteTexture(int id) {
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

    public static boolean glIsList(int list) {
        return displayListIdAllocator.isAllocated(list);
    }

    /**
     * Delete display lists and free their VBO resources.
     */
    public static void glDeleteLists(int list, int range) { GLSMDraws.glDeleteLists(list, range); }

    /**
     * Get a compiled display list from the cache. Used for executing nested display lists.
     *
     * @return The CompiledDisplayList, or null if not found
     */
    public static CompiledDisplayList getDisplayList(int list) {
        return DisplayListManager.getDisplayList(list);
    }

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

    public static void retainModifiedState(int depth, RetainedState out) {
        final GLContextState glCtx = ctx();
        if (depth < 0 || depth != glCtx.attribDepth - 1) {
            throw new IllegalStateException("retainModifiedState: depth " + depth + " is not the open push (attrib depth " + glCtx.attribDepth + ")");
        }
        glCtx.retainedOwner = out;
        final int count = glCtx.modifiedCount[depth];
        final int[] ids = glCtx.modifiedIds[depth];
        final CowStateStack<?>[] stackById = glCtx.stackById;
        out.ensureCapacity(count);
        for (int i = 0; i < count; i++) {
            final int id = ids[i];
            stackById[id].captureSlot(RETAINED_SLOT);
            out.ids[i] = id;
        }
        out.count = count;
        out.program = glCtx.activeProgram;
        out.programGen = programLifetimeGeneration;
    }

    public static void applyRetainedState(RetainedState in) {
        final GLContextState glCtx = ctx();
        if (glCtx.retainedOwner != in) return;
        final CowStateStack<?>[] stackById = glCtx.stackById;
        final byte[] kindById = glCtx.kindById;
        final int[] ids = in.ids;
        final int count = in.count;
        int first = 0;
        while (first < count && !CowDispatch.slotChanged(kindById[ids[first]], stackById[ids[first]], RETAINED_SLOT)) first++;
        if (first < count) applyRetainedChanges(glCtx, in, first);
        if (in.program != glCtx.activeProgram && (in.program == 0 || in.programGen == programLifetimeGeneration)) {
            glUseProgram(in.program);
        }
    }

    private static void applyRetainedChanges(GLContextState glCtx, RetainedState in, int first) {
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

    public static void glClear(int mask) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordClear(mask);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        recordGpuCommand(GpuCommandType.CLEAR, GpuCommandPhase.BEGIN, mask, 0);
        RENDER_BACKEND.clear(mask);
        recordGpuCommand(GpuCommandType.CLEAR, GpuCommandPhase.END, mask, 0);
        gpuCheckpoint(GpuCommandType.CLEAR);
    }

    public static void glPushAttrib(int mask) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordPushAttrib(mask);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        GLDebug.pushGroup("pushState");
        pushState(mask);
    }

    public static void glPopAttrib() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordPopAttrib();
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        GLDebug.popGroup();
        ctx().poppingAttributes = true;
        GLDebug.pushGroup("popState");
        try {
            popState();
        } finally {
            GLDebug.popGroup();
            ctx().poppingAttributes = false;
        }
    }

    public static void glMatrixMode(int mode) {
        final RecordMode recordMode = DisplayListManager.getRecordMode();
        if (recordMode != RecordMode.NONE) {
            DisplayListManager.recordMatrixMode(mode);
            DisplayListManager.resetRelativeTransform();  // Mode switch is a barrier - reset delta tracking
            if (recordMode == RecordMode.COMPILE) {
                return;
            }
        }
        mod(ctx().matrixMode).setMode(mode);
    }

    public static void glLoadMatrix(FloatBuffer m) {
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            final Matrix4f matrix = new Matrix4f().set(m);
            m.rewind();
            DisplayListManager.recordLoadMatrix(matrix);
            DisplayListManager.resetRelativeTransform();
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (isCachingEnabled()) {
            getMatrixStack(glCtx).set(m);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glLoadMatrix(DoubleBuffer m) {
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            conversionMatrix4d.set(m);
            m.rewind();
            final Matrix4f floatMatrix = new Matrix4f();
            floatMatrix.set(conversionMatrix4d);
            DisplayListManager.recordLoadMatrix(floatMatrix);
            DisplayListManager.resetRelativeTransform();
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (isCachingEnabled()) {
            conversionMatrix4d.set(m);
            getMatrixStack(glCtx).set(conversionMatrix4d);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void setModelViewMatrix(Matrix4fc m) {
        final GLContextState glCtx = ctx();
        if (isCachingEnabled()) {
            glCtx.modelViewMatrix.set(m);
            glCtx.mvGeneration++;
        }
    }

    public static void setProjectionMatrix(Matrix4fc m) {
        final GLContextState glCtx = ctx();
        if (isCachingEnabled()) {
            glCtx.projectionMatrix.set(m);
            glCtx.projGeneration++;
        }
    }

    public static Matrix4fStack getMatrixStack() {
        return getMatrixStack(ctx());
    }

    private static Matrix4fStack getMatrixStack(GLContextState glCtx) {
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

    private static void bumpMatrixGeneration(GLContextState glCtx) {
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

    public static void glLoadIdentity() {
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordLoadIdentity();
            DisplayListManager.resetRelativeTransform();
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (isCachingEnabled()) {
            getMatrixStack(glCtx).identity();
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glTranslatef(float x, float y, float z) {
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.updateRelativeTransform(x, y, z, DisplayListManager.TransformOp.TRANSLATE, null);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            getMatrixStack(glCtx).translate(x, y, z);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glTranslated(double x, double y, double z) {
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.updateRelativeTransform((float) x, (float) y, (float) z, DisplayListManager.TransformOp.TRANSLATE, null);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            getMatrixStack(glCtx).translate((float) x, (float) y, (float) z);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glScalef(float x, float y, float z) {
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.updateRelativeTransform(x, y, z, DisplayListManager.TransformOp.SCALE, null);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            getMatrixStack(glCtx).scale(x, y, z);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glScaled(double x, double y, double z) {
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.updateRelativeTransform((float) x, (float) y, (float) z, DisplayListManager.TransformOp.SCALE, null);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            getMatrixStack(glCtx).scale((float) x, (float) y, (float) z);
            bumpMatrixGeneration(glCtx);
        }
    }

    private static final Matrix4f multMatrix = new Matrix4f();

    public static void glMultMatrix(FloatBuffer floatBuffer) {
        final GLContextState glCtx = ctx();
        multMatrix.set(floatBuffer);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.updateRelativeTransform(multMatrix);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            getMatrixStack(glCtx).mul(multMatrix);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static final Matrix4d conversionMatrix4d = new Matrix4d();
    public static final Matrix4f conversionMatrix4f = new Matrix4f();

    public static void glMultMatrix(DoubleBuffer matrix) {
        final GLContextState glCtx = ctx();
        conversionMatrix4d.set(matrix);
        conversionMatrix4f.set(conversionMatrix4d);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.updateRelativeTransform(conversionMatrix4f);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            getMatrixStack(glCtx).mul(conversionMatrix4f);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void applyMultMatrix(Matrix4f multMatrix) {
        final GLContextState glCtx = ctx();
        if (isCachingEnabled()) {
            getMatrixStack(glCtx).mul(multMatrix);
            bumpMatrixGeneration(glCtx);
        }
    }

    private static final Vector3f rotation = new Vector3f();

    public static void glRotatef(float angle, float x, float y, float z) {
        final float lenSq = x * x + y * y + z * z;
        if (lenSq == 0.0f) return;
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            rotation.set(x, y, z).normalize();
            DisplayListManager.updateRelativeTransform(angle, 0, 0, DisplayListManager.TransformOp.ROTATE, rotation);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            rotation.set(x, y, z).normalize();
            getMatrixStack(glCtx).rotate((float) Math.toRadians(angle), rotation);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glRotated(double angle, double x, double y, double z) {
        final double lenSq = x * x + y * y + z * z;
        if (lenSq == 0.0) return;
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            rotation.set((float) x, (float) y, (float) z).normalize();
            DisplayListManager.updateRelativeTransform((float) angle, 0, 0, DisplayListManager.TransformOp.ROTATE, rotation);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            rotation.set((float) x, (float) y, (float) z).normalize();
            getMatrixStack(glCtx).rotate((float) Math.toRadians(angle), rotation);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glOrtho(double left, double right, double bottom, double top, double zNear, double zFar) {
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.updateRelativeTransformOrtho(left, right, bottom, top, zNear, zFar);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            getMatrixStack(glCtx).ortho((float) left, (float) right, (float) bottom, (float) top, (float) zNear, (float) zFar);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glFrustum(double left, double right, double bottom, double top, double zNear, double zFar) {
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.updateRelativeTransformFrustum(left, right, bottom, top, zNear, zFar);
            if (mode == RecordMode.COMPILE) return;
        }
        if (isCachingEnabled()) {
            getMatrixStack(glCtx).frustum((float) left, (float) right, (float) bottom, (float) top, (float) zNear, (float) zFar);
            bumpMatrixGeneration(glCtx);
        }
    }

    public static void glPushMatrix() {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordPushMatrix();
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (isCachingEnabled()) {
            try {
                getMatrixStack(ctx()).pushMatrix();
            } catch (IllegalStateException ignored) {
                if (initConfig != null && initConfig.isLwjglDebug()) LOGGER.warn("Matrix stack overflow ", new Throwable());
            }
        }
    }

    public static void glPopMatrix() {
        final GLContextState glCtx = ctx();
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordPopMatrix();
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (isCachingEnabled()) {
            try {
                getMatrixStack(glCtx).popMatrix();
                bumpMatrixGeneration(glCtx);
            } catch (IllegalStateException ignored) {
                if (initConfig != null && initConfig.isLwjglDebug()) LOGGER.warn("Matrix stack underflow ", new Throwable());
            }
        }
    }

    private static final Matrix4f gluMatrix = new Matrix4f();
    private static final Matrix4f gluMatrix2 = new Matrix4f();
    private static final Vector4f gluVec4 = new Vector4f();
    private static final FloatBuffer gluBuffer = BufferUtils.createFloatBuffer(16);

    public static void gluPerspective(float fovy, float aspect, float zNear, float zFar) {
        gluMatrix.identity().perspective((float) Math.toRadians(fovy), aspect, zNear, zFar);
        gluMatrix.get(0, gluBuffer);
        GLStateManager.glMultMatrix(gluBuffer);
    }

    public static void gluLookAt(float eyex, float eyey, float eyez, float centerx, float centery, float centerz, float upx, float upy, float upz) {
        gluMatrix.identity().lookAt(eyex, eyey, eyez, centerx, centery, centerz, upx, upy, upz);
        gluMatrix.get(0, gluBuffer);
        GLStateManager.glMultMatrix(gluBuffer);
    }

    public static void gluOrtho2D(float left, float right, float bottom, float top) {
        GLStateManager.glOrtho(left, right, bottom, top, -1.0, 1.0);
    }

    public static void gluPickMatrix(float x, float y, float deltaX, float deltaY, IntBuffer viewport) {
        if (deltaX <= 0 || deltaY <= 0) return;
        GLStateManager.glTranslatef(
                (viewport.get(viewport.position() + 2) - 2 * (x - viewport.get(viewport.position() + 0))) / deltaX,
                (viewport.get(viewport.position() + 3) - 2 * (y - viewport.get(viewport.position() + 1))) / deltaY,
                0);
        GLStateManager.glScalef(viewport.get(viewport.position() + 2) / deltaX, viewport.get(viewport.position() + 3) / deltaY, 1.0f);
    }

    public static int gluBuild2DMipmaps(int target, int components, int width, int height, int format, int type, ByteBuffer data) {
        if (width < 1 || height < 1) return GLU.GLU_INVALID_VALUE;
        glTexImage2D(target, 0, components, width, height, 0, format, type, data);
        glGenerateMipmap(target);
        return 0;
    }

    public static String gluErrorString(int errorCode) {
        return switch (errorCode) {
            case GLU.GLU_INVALID_ENUM -> "Invalid enum (glu)";
            case GLU.GLU_INVALID_VALUE -> "Invalid value (glu)";
            case GLU.GLU_OUT_OF_MEMORY -> "Out of memory (glu)";
            case GL11.GL_NO_ERROR -> "No error";
            case GL11.GL_INVALID_ENUM -> "Invalid enum";
            case GL11.GL_INVALID_VALUE -> "Invalid value";
            case GL11.GL_INVALID_OPERATION -> "Invalid operation";
            case GL11.GL_OUT_OF_MEMORY -> "Out of memory";
            default -> "Unknown error: " + errorCode;
        };
    }

    public static boolean gluUnProject(float winx, float winy, float winz, FloatBuffer modelMatrix, FloatBuffer projMatrix, IntBuffer viewport, FloatBuffer obj_pos) {
        gluMatrix.set(projMatrix).mul(gluMatrix2.set(modelMatrix));
        if (Math.abs(gluMatrix.determinant()) < 1.0e-10f) return false;
        gluMatrix.invert(gluMatrix);

        float ndcX = (winx - viewport.get(viewport.position())) / viewport.get(viewport.position() + 2) * 2.0f - 1.0f;
        float ndcY = (winy - viewport.get(viewport.position() + 1)) / viewport.get(viewport.position() + 3) * 2.0f - 1.0f;
        float ndcZ = winz * 2.0f - 1.0f;

        gluVec4.set(ndcX, ndcY, ndcZ, 1.0f);
        gluMatrix.transform(gluVec4);
        if (gluVec4.w == 0.0f) return false;

        float invW = 1.0f / gluVec4.w;
        obj_pos.put(obj_pos.position(), gluVec4.x * invW);
        obj_pos.put(obj_pos.position() + 1, gluVec4.y * invW);
        obj_pos.put(obj_pos.position() + 2, gluVec4.z * invW);
        return true;
    }

    public static void glViewport(int x, int y, int width, int height) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordViewport(x, y, width, height);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        RENDER_BACKEND.viewport(x, y, width, height);
        if (isCachingEnabled()) {
            mod(ctx().viewportState).setViewPort(x, y, width, height);
        }
    }

    public static int getActiveTextureUnit() {
        return ctx().activeTextureUnit.getValue();
    }

    public static int getListMode() {
        return DisplayListManager.getListMode();
    }

    /**
     * Resolves the TextureInfo of the currently bound 2D texture, preferring the per-context
     * binding cache when caching is enabled.
     */
    private static TextureInfo getBoundTextureInfo() {
        final GLContextState glCtx = ctx();
        if (isCachingEnabled()) {
            return glCtx.textures.getTextureUnitBindings(glCtx.activeTextureUnit.getValue()).getOrResolveInfo();
        }
        return TextureInfoCache.INSTANCE.getInfo(getBoundTextureForServerState());
    }

    /** Auto-mipmap: regenerate mipmaps after a base-level upload when GL_GENERATE_MIPMAP is set. */
    private static void maybeGenerateMipmap(int target, int level) {
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
    private static boolean handleRemovedTexParam(int target, int pname, int value) {
        if (pname == GL14.GL_GENERATE_MIPMAP) {
            if (target == GL11.GL_TEXTURE_2D) {
                final TextureInfo info = getBoundTextureInfo();
                if (info != null) info.setGenerateMipmap(value != 0);
            }
            return true;
        }
        return false;
    }

    public static boolean updateTexParameteriCache(int target, int texture, int pname, int param) {
        if (target != GL11.GL_TEXTURE_2D) {
            return true;
        }
        return updateTexParameteriCache(TextureInfoCache.INSTANCE.getInfo(texture), pname, param);
    }

    static boolean updateTexParameteriCache(TextureInfo info, int pname, int param) {
        if (info == null) {
            return true;
        }
        switch (pname) {
            case GL11.GL_TEXTURE_MIN_FILTER -> {
                if (info.getMinFilter() == param && isCachingEnabled()) return false;
                info.setMinFilter(param);
            }
            case GL11.GL_TEXTURE_MAG_FILTER -> {
                if (info.getMagFilter() == param && isCachingEnabled()) return false;
                info.setMagFilter(param);
            }
            case GL11.GL_TEXTURE_WRAP_S -> {
                if (info.getWrapS() == param && isCachingEnabled()) return false;
                info.setWrapS(param);
            }
            case GL11.GL_TEXTURE_WRAP_T -> {
                if (info.getWrapT() == param && isCachingEnabled()) return false;
                info.setWrapT(param);
            }
            case GL12.GL_TEXTURE_BASE_LEVEL -> {
                if (info.getBaseLevel() == param && isCachingEnabled()) return false;
                info.setBaseLevel(param);
            }
            case GL12.GL_TEXTURE_MAX_LEVEL -> {
                if (info.getMaxLevel() == param && isCachingEnabled()) return false;
                info.setMaxLevel(param);
            }
            case GL12.GL_TEXTURE_MIN_LOD -> {
                if (info.getMinLod() == param && isCachingEnabled()) return false;
                info.setMinLod(param);
            }
            case GL12.GL_TEXTURE_MAX_LOD -> {
                if (info.getMaxLod() == param && isCachingEnabled()) return false;
                info.setMaxLod(param);
            }
        }
        return true;
    }

    public static void glTexParameter(int target, int pname, IntBuffer params) {
        if (handleRemovedTexParam(target, pname, params.remaining() >= 1 ? params.get(params.position()) : 0)) return;
        if (params.remaining() >= 1) {
            final int val = params.get(params.position());
            final int remapped = remapTexClamp(pname, val);
            if (remapped != val) params.put(params.position(), remapped);
            if (target == GL11.GL_TEXTURE_2D) {
                updateTexParameteriCache(getBoundTextureInfo(), pname, remapped);
            }
        }
        RENDER_BACKEND.texParameteriv(target, pname, params);
    }

    public static void glTexParameter(int target, int pname, FloatBuffer params) {
        if (handleRemovedTexParam(target, pname, params.remaining() >= 1 ? (int) params.get(params.position()) : 0)) return;
        if (params.remaining() >= 1) {
            final float val = params.get(params.position());
            final float remapped = remapTexClamp(pname, val);
            if (remapped != val) params.put(params.position(), remapped);
            if (target == GL11.GL_TEXTURE_2D) {
                updateTexParameterfCache(getBoundTextureInfo(), pname, remapped);
            }
        }
        RENDER_BACKEND.texParameterfv(target, pname, params);
    }

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

    public static void glTexParameteri(int target, int pname, int param) {
        if (handleRemovedTexParam(target, pname, param)) return;
        param = remapTexClamp(pname, param);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordTexParameteri(target, pname, param);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (target != GL11.GL_TEXTURE_2D) {
            RENDER_BACKEND.texParameteri(target, pname, param);
            return;
        }
        if (!updateTexParameteriCache(getBoundTextureInfo(), pname, param)) return;

        RENDER_BACKEND.texParameteri(target, pname, param);
    }

    public static boolean updateTexParameterfCache(int target, int texture, int pname, float param) {
        if (target != GL11.GL_TEXTURE_2D) {
            return true;
        }
        return updateTexParameterfCache(TextureInfoCache.INSTANCE.getInfo(texture), pname, param);
    }

    static boolean updateTexParameterfCache(TextureInfo info, int pname, float param) {
        if (info == null) {
            return true;
        }
        switch (pname) {
            case EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT -> {
                if (info.getMaxAnisotropy() == param && isCachingEnabled()) return false;
                info.setMaxAnisotropy(param);
            }
            case GL14.GL_TEXTURE_LOD_BIAS -> {
                if (info.getLodBias() == param && isCachingEnabled()) return false;
                info.setLodBias(param);
            }
        }
        return true;
    }

    public static void glTexParameterf(int target, int pname, float param) {
        if (handleRemovedTexParam(target, pname, param > 0 ? (int) (param + 0.5f) : (int) (param - 0.5f))) return;
        param = remapTexClamp(pname, param);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordTexParameterf(target, pname, param);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (target != GL11.GL_TEXTURE_2D) {
            RENDER_BACKEND.texParameterf(target, pname, param);
            return;
        }
        if (!updateTexParameterfCache(target, getBoundTextureForServerState(), pname, param)) return;

        RENDER_BACKEND.texParameterf(target, pname, param);
    }

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

    public static int glGetTexParameteri(int target, int pname) {
        if (target != GL11.GL_TEXTURE_2D || shouldBypassCache()) {
            return RENDER_BACKEND.getTexParameteri(target, pname);
        }
        return getTexParameterOrDefault(getBoundTextureForServerState(), pname, () -> RENDER_BACKEND.getTexParameteri(target, pname));
    }

    public static float glGetTexParameterf(int target, int pname) {
        if (target != GL11.GL_TEXTURE_2D || shouldBypassCache()) {
            return RENDER_BACKEND.getTexParameterf(target, pname);
        }
        final int boundTexture = getBoundTextureForServerState();
        final TextureInfo info = TextureInfoCache.INSTANCE.getInfo(boundTexture);
        if(info == null) {
            if (isRecordingDisplayList()) {
                throw new IllegalStateException(String.format(
                    "glGetTexParameterf called during display list recording with no cached TextureInfo for texture %d. " +
                    "Cannot query OpenGL state during compilation!", boundTexture));
            }
            return RENDER_BACKEND.getTexParameterf(target, pname);
        }

        return switch (pname) {
            case EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT -> info.getMaxAnisotropy();
            case GL14.GL_TEXTURE_LOD_BIAS -> info.getLodBias();
            default -> {
                if (isRecordingDisplayList()) {
                    throw new IllegalStateException(String.format(
                        "glGetTexParameterf called during display list recording with uncached pname 0x%s for texture %d. " +
                        "Cannot query OpenGL state during compilation!", Integer.toHexString(pname), boundTexture));
                }
                yield RENDER_BACKEND.getTexParameterf(target, pname);
            }
        };
    }

    public static int glGetTexLevelParameteri(int target, int level, int pname) {
        if (target != GL11.GL_TEXTURE_2D || shouldBypassCache()) {
            return RENDER_BACKEND.getTexLevelParameteri(target, level, pname);
        }
        final TextureInfo info = TextureInfoCache.INSTANCE.getInfo(getBoundTextureForServerState());
        if (info == null) {
            if (isRecordingDisplayList()) {
                throw new IllegalStateException(String.format(
                    "glGetTexLevelParameteri called during display list recording with no cached TextureInfo for texture %d. " +
                    "Cannot query OpenGL state during compilation!", getBoundTextureForServerState()));
            }
            return RENDER_BACKEND.getTexLevelParameteri(target, level, pname);
        }
        return switch (pname) {
            case GL11.GL_TEXTURE_WIDTH -> info.getWidth();
            case GL11.GL_TEXTURE_HEIGHT -> info.getHeight();
            case GL11.GL_TEXTURE_INTERNAL_FORMAT -> info.getInternalFormat();
            default -> {
                if (isRecordingDisplayList()) {
                    throw new IllegalStateException(String.format(
                        "glGetTexLevelParameteri called during display list recording with uncached pname 0x%s for texture %d. " +
                        "Cannot query OpenGL state during compilation!", Integer.toHexString(pname), getBoundTextureForServerState()));
                }
                yield RENDER_BACKEND.getTexLevelParameteri(target, level, pname);
            }
        };
    }

    public static float glGetTexLevelParameterf(int target, int level, int pname) {
        return glGetTexLevelParameteri(target, level, pname);
    }

    public static void glGetTexImage(int target, int level, int format, int type, ByteBuffer pixels) {
        if (RenderSystem.isGLES()) {
            getTexImageViaFboReadPixels(target, level, format, type, pixels);
            return;
        }
        suspendPixelPackBuffer();
        RENDER_BACKEND.getTexImage(target, level, format, type, pixels);
        restorePixelPackBuffer();
    }
    public static void glGetTexImage(int target, int level, int format, int type, IntBuffer pixels) {
        if (RenderSystem.isGLES()) {
            getTexImageViaFboReadPixels(target, level, format, type, pixels);
            return;
        }
        suspendPixelPackBuffer();
        RENDER_BACKEND.getTexImage(target, level, format, type, pixels);
        restorePixelPackBuffer();
    }

    private static void getTexImageViaFboReadPixels(int target, int level, int format, int type, Buffer pixels) {
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

    public static void glReadPixels(int x, int y, int width, int height, int format, int type, ByteBuffer pixels) {
        type = remapPixelTypeForGLES(format, type);
        suspendPixelPackBuffer();
        RENDER_BACKEND.readPixels(x, y, width, height, format, type, pixels);
        restorePixelPackBuffer();
    }
    public static void glReadPixels(int x, int y, int width, int height, int format, int type, FloatBuffer pixels) {
        type = remapPixelTypeForGLES(format, type);
        suspendPixelPackBuffer();
        RENDER_BACKEND.readPixels(x, y, width, height, format, type, pixels);
        restorePixelPackBuffer();
    }
    public static void glReadPixels(int x, int y, int width, int height, int format, int type, IntBuffer pixels) {
        type = remapPixelTypeForGLES(format, type);
        suspendPixelPackBuffer();
        RENDER_BACKEND.readPixels(x, y, width, height, format, type, pixels);
        restorePixelPackBuffer();
    }

    /**
     * Reads pixels into the currently bound pixel-pack buffer at a byte offset. This overload
     * preserves that binding for PBO-backed capture paths that pass a raw OpenGL buffer offset.
     */
    public static void glReadPixels(int x, int y, int width, int height, int format, int type, long pixelBufferOffset) {
        type = remapPixelTypeForGLES(format, type);
        RENDER_BACKEND.readPixels(x, y, width, height, format, type, pixelBufferOffset);
    }
    public static void glTexStorage2D(int target, int levels, int internalFormat, int width, int height) {
        int texture = getBoundTextureForGpuDiagnostics();
        recordGpuCommand(GpuCommandType.TEX_STORAGE_2D, GpuCommandPhase.BEGIN, texture, packDimensions(width, height));
        RENDER_BACKEND.texStorage2D(target, levels, internalFormat, width, height);
        recordGpuCommand(GpuCommandType.TEX_STORAGE_2D, GpuCommandPhase.END, texture, packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_STORAGE_2D);
    }

    public static void glClearTexImage(int texture, int level, int format, int type, ByteBuffer data) {
        recordGpuCommand(GpuCommandType.CLEAR_TEX_IMAGE, GpuCommandPhase.BEGIN, texture, level);
        RENDER_BACKEND.clearTexImage(texture, level, format, type);
        recordGpuCommand(GpuCommandType.CLEAR_TEX_IMAGE, GpuCommandPhase.END, texture, level);
        gpuCheckpoint(GpuCommandType.CLEAR_TEX_IMAGE);
    }

    public static void glClearTexSubImage(int texture, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth, int format, int type, ByteBuffer data) {
        recordGpuCommand(GpuCommandType.CLEAR_TEX_IMAGE, GpuCommandPhase.BEGIN, texture, level);
        RENDER_BACKEND.clearTexSubImage(texture, level, xoffset, yoffset, zoffset, width, height, depth, format, type, data);
        recordGpuCommand(GpuCommandType.CLEAR_TEX_IMAGE, GpuCommandPhase.END, texture, level);
        gpuCheckpoint(GpuCommandType.CLEAR_TEX_IMAGE);
    }

    public static int glCreateTextures(int target) {
        requireContext("glCreateTextures");
        final int texture = RENDER_BACKEND.createTextures(target);
        TextureInfoCache.INSTANCE.getInfo(texture).setTarget(target);
        return texture;
    }

    public static void glBindTextureUnit(int unit, int texture) {
        if (getBoundTextureForServerState(unit) == texture) return;
        recordGpuCommand(GpuCommandType.TEXTURE_BIND, 0, texture);
        RENDER_BACKEND.bindTextureUnit(unit, texture);
        mod(ctx().textures.getTextureUnitBindings(unit)).setBinding(texture);
        if (texture != 0 && unit > ctx().maxBoundTextureUnit) {
            ctx().maxBoundTextureUnit = unit;
        }
    }

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

    public static int glGenSamplers() { return RENDER_BACKEND.genSamplers(); }
    public static void glDeleteSamplers(int sampler) { RENDER_BACKEND.deleteSamplers(sampler); }
    public static void glBindSampler(int unit, int sampler) { RENDER_BACKEND.bindSampler(unit, sampler); }
    public static void glSamplerParameteri(int sampler, int pname, int param) { RENDER_BACKEND.samplerParameteri(sampler, pname, param); }
    public static void glSamplerParameterf(int sampler, int pname, float param) { RENDER_BACKEND.samplerParameterf(sampler, pname, param); }

    private static void glMaterialFront(int pname, FloatBuffer params) {
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

    private static void glMaterialBack(int pname, FloatBuffer params) {
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

    private static void glMaterialFront(int pname, IntBuffer params) {
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

    private static void glMaterialBack(int pname, IntBuffer params) {
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

    public static void glMaterial(int face, int pname, FloatBuffer params) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordMaterial(face, pname, params);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (face == GL11.GL_FRONT) {
            mod(ctx().frontMaterial);
            glMaterialFront(pname, params);
        } else if (face == GL11.GL_BACK) {
            mod(ctx().backMaterial);
            glMaterialBack(pname, params);
        } else if (face == GL11.GL_FRONT_AND_BACK) {
            mod(ctx().frontMaterial);
            mod(ctx().backMaterial);
            glMaterialFront(pname, params);
            glMaterialBack(pname, params);
        } else {
            throw new RuntimeException("Unsupported face value for glMaterial: " + face);
        }
        ctx().lightingGeneration++;
    }

    public static void glMaterial(int face, int pname, IntBuffer params) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            final float[] floatParams = new float[params.remaining()];
            params.mark();
            for (int i = 0; i < floatParams.length; i++) {
                floatParams[i] = GLStateManager.i2f(params.get());
            }
            params.reset();
            DisplayListManager.recordMaterial(face, pname, FloatBuffer.wrap(floatParams));
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (face == GL11.GL_FRONT) {
            mod(ctx().frontMaterial);
            glMaterialFront(pname, params);
        } else if (face == GL11.GL_BACK) {
            mod(ctx().backMaterial);
            glMaterialBack(pname, params);
        } else if (face == GL11.GL_FRONT_AND_BACK) {
            mod(ctx().frontMaterial);
            mod(ctx().backMaterial);
            glMaterialFront(pname, params);
            glMaterialBack(pname, params);
        } else {
            throw new RuntimeException("Unsupported face value for glMaterial: " + face);
        }
        ctx().lightingGeneration++;
    }

    public static void glMaterialf(int face, int pname, float val) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordMaterialf(face, pname, val);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        if (pname != GL11.GL_SHININESS) {
            // it is only valid to call glMaterialf for the GL_SHININESS parameter
            return;
        }

        if (face == GL11.GL_FRONT) {
            mod(ctx().frontMaterial).setShininess(val);
        } else if (face == GL11.GL_BACK) {
            mod(ctx().backMaterial).setShininess(val);
        } else if (face == GL11.GL_FRONT_AND_BACK) {
            mod(ctx().frontMaterial).setShininess(val);
            mod(ctx().backMaterial).setShininess(val);
        } else {
            throw new RuntimeException("Unsupported face value for glMaterial: " + face);
        }
        ctx().lightingGeneration++;
    }

    public static void glMateriali(int face, int pname, int val) {
        // This will end up no-opping if pname != GL_SHININESS, it is invalid to call this with another pname
        // Command recording happens in glMaterialf
        glMaterialf(face, pname, (float) val);
    }

    public static void glLight(int light, int pname, FloatBuffer params) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordLight(light, pname, params);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final LightStateStack lightState = ctx().lightDataStates[light - GL11.GL_LIGHT0];
        mod(lightState);
        switch (pname) {
            case GL11.GL_AMBIENT -> lightState.setAmbient(params);
            case GL11.GL_DIFFUSE -> lightState.setDiffuse(params);
            case GL11.GL_SPECULAR -> lightState.setSpecular(params);
            case GL11.GL_POSITION -> lightState.setPosition(params);
            case GL11.GL_SPOT_DIRECTION -> lightState.setSpotDirection(params);
            case GL11.GL_SPOT_EXPONENT -> lightState.setSpotExponent(params);
            case GL11.GL_SPOT_CUTOFF -> lightState.setSpotCutoff(params);
            case GL11.GL_CONSTANT_ATTENUATION -> lightState.setConstantAttenuation(params);
            case GL11.GL_LINEAR_ATTENUATION -> lightState.setLinearAttenuation(params);
            case GL11.GL_QUADRATIC_ATTENUATION -> lightState.setQuadraticAttenuation(params);
            default -> {}
        }
        ctx().lightingGeneration++;
    }

    public static void glLight(int light, int pname, IntBuffer params) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            final float[] floatParams = new float[params.remaining()];
            params.mark();
            for (int i = 0; i < floatParams.length; i++) {
                floatParams[i] = GLStateManager.i2f(params.get());
            }
            params.reset();
            DisplayListManager.recordLight(light, pname, FloatBuffer.wrap(floatParams));
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final LightStateStack lightState = ctx().lightDataStates[light - GL11.GL_LIGHT0];
        mod(lightState);
        switch (pname) {
            case GL11.GL_AMBIENT -> lightState.setAmbient(params);
            case GL11.GL_DIFFUSE -> lightState.setDiffuse(params);
            case GL11.GL_SPECULAR -> lightState.setSpecular(params);
            case GL11.GL_POSITION -> lightState.setPosition(params);
            case GL11.GL_SPOT_DIRECTION -> lightState.setSpotDirection(params);
            case GL11.GL_SPOT_EXPONENT -> lightState.setSpotExponent(params);
            case GL11.GL_SPOT_CUTOFF -> lightState.setSpotCutoff(params);
            case GL11.GL_CONSTANT_ATTENUATION -> lightState.setConstantAttenuation(params);
            case GL11.GL_LINEAR_ATTENUATION -> lightState.setLinearAttenuation(params);
            case GL11.GL_QUADRATIC_ATTENUATION -> lightState.setQuadraticAttenuation(params);
            default -> {}
        }
        ctx().lightingGeneration++;
    }

    public static void glLightf(int light, int pname, float param) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordLightf(light, pname, param);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final LightStateStack lightState = ctx().lightDataStates[light - GL11.GL_LIGHT0];
        mod(lightState);
        switch (pname) {
            case GL11.GL_SPOT_EXPONENT -> lightState.setSpotExponent(param);
            case GL11.GL_SPOT_CUTOFF -> lightState.setSpotCutoff(param);
            case GL11.GL_CONSTANT_ATTENUATION -> lightState.setConstantAttenuation(param);
            case GL11.GL_LINEAR_ATTENUATION -> lightState.setLinearAttenuation(param);
            case GL11.GL_QUADRATIC_ATTENUATION -> lightState.setQuadraticAttenuation(param);
            default -> {}
        }
        ctx().lightingGeneration++;
    }

    public static void glLighti(int light, int pname, int param) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordLighti(light, pname, param);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final LightStateStack lightState = ctx().lightDataStates[light - GL11.GL_LIGHT0];
        mod(lightState);
        switch (pname) {
            case GL11.GL_SPOT_EXPONENT -> lightState.setSpotExponent(param);
            case GL11.GL_SPOT_CUTOFF -> lightState.setSpotCutoff(param);
            case GL11.GL_CONSTANT_ATTENUATION -> lightState.setConstantAttenuation(param);
            case GL11.GL_LINEAR_ATTENUATION -> lightState.setLinearAttenuation(param);
            case GL11.GL_QUADRATIC_ATTENUATION -> lightState.setQuadraticAttenuation(param);
            default -> {
            }
        }
        ctx().lightingGeneration++;
    }

    public static void glLightModel(int pname, FloatBuffer params) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordLightModel(pname, params);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        mod(ctx().lightModel);
        switch (pname) {
            case GL11.GL_LIGHT_MODEL_AMBIENT -> ctx().lightModel.setAmbient(params);
            case GL11.GL_LIGHT_MODEL_LOCAL_VIEWER -> ctx().lightModel.setLocalViewer(params);
            case GL11.GL_LIGHT_MODEL_TWO_SIDE -> ctx().lightModel.setTwoSide(params);
            default -> {
            }
        }
        ctx().lightingGeneration++;
    }

    public static void glLightModel(int pname, IntBuffer params) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            final float[] floatParams = new float[params.remaining()];
            params.mark();
            for (int i = 0; i < floatParams.length; i++) {
                floatParams[i] = GLStateManager.i2f(params.get());
            }
            params.reset();
            DisplayListManager.recordLightModel(pname, FloatBuffer.wrap(floatParams));
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        mod(ctx().lightModel);
        switch (pname) {
            case GL11.GL_LIGHT_MODEL_AMBIENT -> ctx().lightModel.setAmbient(params);
            case GL12.GL_LIGHT_MODEL_COLOR_CONTROL -> ctx().lightModel.setColorControl(params);
            case GL11.GL_LIGHT_MODEL_LOCAL_VIEWER -> ctx().lightModel.setLocalViewer(params);
            case GL11.GL_LIGHT_MODEL_TWO_SIDE -> ctx().lightModel.setTwoSide(params);
            default -> {
            }
        }
        ctx().lightingGeneration++;
    }

    public static void glLightModelf(int pname, float param) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordLightModelf(pname, param);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }

        if (isCachingEnabled()) {
            mod(ctx().lightModel);
            switch (pname) {
                case GL11.GL_LIGHT_MODEL_LOCAL_VIEWER -> ctx().lightModel.setLocalViewer(param);
                case GL11.GL_LIGHT_MODEL_TWO_SIDE -> ctx().lightModel.setTwoSide(param);
                default -> {
                }
            }
            ctx().lightingGeneration++;
        }
    }

    public static void glLightModeli(int pname, int param) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordLightModeli(pname, param);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }

        if (isCachingEnabled()) {
            mod(ctx().lightModel);
            switch (pname) {
                case GL12.GL_LIGHT_MODEL_COLOR_CONTROL -> ctx().lightModel.setColorControl(param);
                case GL11.GL_LIGHT_MODEL_LOCAL_VIEWER -> ctx().lightModel.setLocalViewer(param);
                case GL11.GL_LIGHT_MODEL_TWO_SIDE -> ctx().lightModel.setTwoSide(param);
                default -> {
                }
            }
            ctx().lightingGeneration++;
        }
    }

    public static void glColorMaterial(int face, int mode) {
        final RecordMode recordMode = DisplayListManager.getRecordMode();
        if (recordMode != RecordMode.NONE) {
            DisplayListManager.recordColorMaterial(face, mode);
            if (recordMode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || ctx().colorMaterialFace.getValue() != face || ctx().colorMaterialParameter.getValue() != mode) {
            if (caching) {
                mod(ctx().colorMaterialFace).setValue(face);
                mod(ctx().colorMaterialParameter).setValue(mode);
                ctx().lightingGeneration++;
            }
        }
    }

    public static void glDepthRange(double near, double far) {
        if (isCachingEnabled()) mod(ctx().viewportState).setDepthRange(near, far);
        RENDER_BACKEND.depthRange(near, far);
    }

    public static void glUseProgram(int program) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordUseProgram(program);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }

        final ShaderManager ffp = ShaderManager.getInstance();
        if (program == 0 && ffp.isEnabled()) {
            final int prev = ctx().activeProgram;
            final boolean caching = isCachingEnabled();
            if (caching && prev == 0 && ffp.isActive()) {
                return;
            }
            if (caching) {
                if (prev != 0) ctx().programGeneration++;
                ctx().activeProgram = 0; // Track that FFP was requested
            }
            if (GLSMHooks.hasActiveConsumers() && GLSMHooks.PROGRAM_CHANGE.hasListeners()) {
                GLSMHooks.programChangeEvent.previousProgram = prev;
                GLSMHooks.programChangeEvent.newProgram = 0;
                GLSMHooks.programChangeEvent.postBind = false;
                GLSMHooks.PROGRAM_CHANGE.post(GLSMHooks.programChangeEvent);
            }
            recordGpuCommand(GpuCommandType.PROGRAM_USE, program, 0);
            ffp.activate();
            if (GLSMHooks.hasActiveConsumers() && GLSMHooks.PROGRAM_CHANGE.hasListeners()) {
                GLSMHooks.programChangeEvent.previousProgram = prev;
                GLSMHooks.programChangeEvent.newProgram = 0;
                GLSMHooks.programChangeEvent.postBind = true;
                GLSMHooks.PROGRAM_CHANGE.post(GLSMHooks.programChangeEvent);
            }
            return;
        }

        // Non-zero program or FFP emulation not enabled
        if (ffp.isActive()) {
            ffp.deactivate();
        }

        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || program != ctx().activeProgram) {
            final int prev = ctx().activeProgram;
            if (caching) {
                ctx().programGeneration++;
                ctx().activeProgram = program;
            }
            if (GLSMHooks.hasActiveConsumers() && GLSMHooks.PROGRAM_CHANGE.hasListeners()) {
                GLSMHooks.programChangeEvent.previousProgram = prev;
                GLSMHooks.programChangeEvent.newProgram = program;
                GLSMHooks.programChangeEvent.postBind = false;
                GLSMHooks.PROGRAM_CHANGE.post(GLSMHooks.programChangeEvent);
            }
            if (initConfig != null && initConfig.isLwjglDebug()) {
                final String programName = GLDebug.getObjectLabel(KHRDebug.GL_PROGRAM, program);
                GLDebug.debugMessage("Activating Program - " + program + ":" + programName);
            }
            recordGpuCommand(GpuCommandType.PROGRAM_USE, program, 0);
            RENDER_BACKEND.useProgram(program);
            CompatUniformManager.onUseProgram(program);
            if (GLSMHooks.hasActiveConsumers() && GLSMHooks.PROGRAM_CHANGE.hasListeners()) {
                GLSMHooks.programChangeEvent.previousProgram = prev;
                GLSMHooks.programChangeEvent.newProgram = program;
                GLSMHooks.programChangeEvent.postBind = true;
                GLSMHooks.PROGRAM_CHANGE.post(GLSMHooks.programChangeEvent);
            }
        }
    }

    // Missing GL commands from Mesa cross-check
    public static void glTexImage1D(int target, int level, int internalformat, int width, int border, int format, int type, ByteBuffer pixels) {
        internalformat = changeFormatIfDeprecated(internalformat);
        type = remapPixelTypeForGLES(format, type);
        type = remapTypeForRemappedInternalFormat(internalformat, type);
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glTexImage1D in display lists not yet implemented");
        }
        suspendPixelUnpackBuffer();
        RENDER_BACKEND.texImage1D(target, level, internalformat, width, border, format, type, pixels);
        restorePixelUnpackBuffer();
    }

    public static void glTexImage3D(int target, int level, int internalformat, int width, int height, int depth, int border, int format, int type, ByteBuffer pixels) {
        internalformat = changeFormatIfDeprecated(internalformat);
        type = remapPixelTypeForGLES(format, type);
        type = remapTypeForRemappedInternalFormat(internalformat, type);
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glTexImage3D in display lists not yet implemented");
        }
        recordGpuCommand(GpuCommandType.TEX_IMAGE_3D, GpuCommandPhase.BEGIN, target, packDimensions(width, height));
        suspendPixelUnpackBuffer();
        RENDER_BACKEND.texImage3D(target, level, internalformat, width, height, depth, border, format, type, pixels);
        restorePixelUnpackBuffer();
        recordGpuCommand(GpuCommandType.TEX_IMAGE_3D, GpuCommandPhase.END, target, packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_IMAGE_3D);
    }

    public static void glTexImage3D(int target, int level, int internalformat, int width, int height, int depth, int border, int format, int type, IntBuffer pixels) {
        internalformat = changeFormatIfDeprecated(internalformat);
        type = remapPixelTypeForGLES(format, type);
        type = remapTypeForRemappedInternalFormat(internalformat, type);
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glTexImage3D in display lists not yet implemented");
        }
        recordGpuCommand(GpuCommandType.TEX_IMAGE_3D, GpuCommandPhase.BEGIN, target, packDimensions(width, height));
        suspendPixelUnpackBuffer();
        RENDER_BACKEND.texImage3D(target, level, internalformat, width, height, depth, border, format, type, pixels);
        restorePixelUnpackBuffer();
        recordGpuCommand(GpuCommandType.TEX_IMAGE_3D, GpuCommandPhase.END, target, packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_IMAGE_3D);
    }

    public static void glTexSubImage1D(int target, int level, int xoffset, int width, int format, int type, ByteBuffer pixels) {
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glTexSubImage1D in display lists not yet implemented");
        }
        suspendPixelUnpackBuffer();
        RENDER_BACKEND.texSubImage1D(target, level, xoffset, width, format, type, pixels);
        restorePixelUnpackBuffer();
    }

    public static void glLineWidth(float width) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordLineWidth(width);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || ctx().lineState.getWidth() != width) {
            if (caching) {
                mod(ctx().lineState).setWidth(width);
            }
            RENDER_BACKEND.lineWidth(Math.max(lineWidthMin, Math.min(lineWidthMax, width)));
        }
    }

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
    private static void setLineStippleActive(boolean active) {
        if (lineStippleActive != active) {
            lineStippleActive = active;
            RENDER_BACKEND.provokingVertex(active ? GL32.GL_FIRST_VERTEX_CONVENTION : GL32.GL_LAST_VERTEX_CONVENTION);
        }
    }

    public static void glFlush() {
        RENDER_BACKEND.flush();
    }

    public static void glFinish() {
        RENDER_BACKEND.finish();
    }

    public static int glGetError() {
        if (!RENDER_BACKEND.hasContext()) return 0;
        final GLSMInitConfig config = initConfig;
        if (config != null && config.isNoErrorChecks()) return 0;
        return RENDER_BACKEND.getError();
    }

    /** LWJGL2 {@code Util.checkGLError} semantics: throw on any pending GL error. */
    public static void checkGLError() {
        final int error = glGetError();
        if (error != GL11.GL_NO_ERROR) {
            throw new OpenGLException(error);
        }
    }

    /**
     * Redirect target for {@code GLContext.getCapabilities()} call sites: the ASM redirector
     * rewrites them here so a backend can substitute its own capabilities view. The GL backend
     * has no override, so this is a plain passthrough.
     */
    public static ContextCapabilities currentContextCapabilities() {
        return GLContext.getCapabilities();
    }

    public static String glGetString(int pname) {
        if (!RENDER_BACKEND.hasContext()) return "no valid GL/render context";
        return RENDER_BACKEND.getString(pname);
    }

    public static String glGetStringi(int name, int index) {
        if (!RENDER_BACKEND.hasContext()) return "no valid GL/render context";
        return RENDER_BACKEND.getStringi(name, index);
    }

    public static void glShaderSource(int shader, CharSequence source) {
        String src = source.toString();
        // Always rename reserved words for the target GLSL version (e.g. 'sampler' at 460)
        src = GlslTransformUtils.renameReservedWords(src, RENDER_BACKEND.getMinGLSLVersion());
        if (ShaderManager.getInstance().isEnabled()) {
            src = CompatShaderTransformer.transform(src, isFragmentShader(shader));
        }
        RENDER_BACKEND.shaderSource(shader, src);
    }

    public static void glShaderSource(int shader, ByteBuffer source) {
        final byte[] bytes = new byte[source.remaining()];
        final int pos = source.position();
        source.get(bytes);
        source.position(pos);
        glShaderSource(shader, new String(bytes, StandardCharsets.UTF_8));
    }

    private static String decodeShaderSourceString(long strings, long lengths, int i) {
        final long strAddr = MemoryUtilities.memGetAddress(strings + (long) i * Pointer.POINTER_SIZE);
        final int len = lengths == MemoryUtilities.NULL ? -1 : MemoryUtilities.memGetInt(lengths + (long) i * 4);
        return len < 0 ? MemoryUtilities.memUTF8(strAddr) : MemoryUtilities.memUTF8(strAddr, len);
    }

    public static void nglShaderSource(int shader, int count, long strings, long lengths) {
        if (count <= 0) return;
        final CharSequence source;
        if (count == 1) {
            source = decodeShaderSourceString(strings, lengths, 0);
        } else {
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++) sb.append(decodeShaderSourceString(strings, lengths, i));
            source = sb.toString();
        }
        glShaderSource(shader, source);
    }

    public static void glShaderSource(int shader, CharSequence[] sources) {
        int totalLen = 0;
        for (CharSequence s : sources) totalLen += s.length();
        final StringBuilder sb = new StringBuilder(totalLen);
        for (CharSequence s : sources) sb.append(s);
        glShaderSource(shader, sb.toString());
    }

    /** Check shader type to determine if fragment output transformation is needed. */
    private static boolean isFragmentShader(int shader) {
        return RENDER_BACKEND.getShaderi(shader, GL20.GL_SHADER_TYPE) == GL20.GL_FRAGMENT_SHADER;
    }

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels) {
        type = remapPixelTypeForGLES(format, type);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordComplexCommand(TexSubImage2DCmd.fromByteBuffer(target, level, xoffset, yoffset, width, height, format, type, pixels, ctx().pixelUnpackState));
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        recordGpuCommand(GpuCommandType.TEX_SUB_IMAGE_2D, GpuCommandPhase.BEGIN, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        suspendPixelUnpackBuffer();
        if (shouldUseDSA(target)) {
            // Use DSA to upload directly to the texture - keeps GL binding state unchanged
            RenderSystem.textureSubImage2D(getBoundTextureForServerState(), target, level, xoffset, yoffset, width, height, format, type, pixels);
        } else {
            // Non-main thread or proxy texture - use direct GL call
            RENDER_BACKEND.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
        }
        restorePixelUnpackBuffer();
        recordGpuCommand(GpuCommandType.TEX_SUB_IMAGE_2D, GpuCommandPhase.END, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_SUB_IMAGE_2D);
        TextureInfoCache.INSTANCE.onTexSubImage2D(target, level);
        maybeGenerateMipmap(target, level);

    }

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, IntBuffer pixels) {
        type = remapPixelTypeForGLES(format, type);
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordComplexCommand(TexSubImage2DCmd.fromIntBuffer(target, level, xoffset, yoffset, width, height, format, type, pixels, ctx().pixelUnpackState));
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        recordGpuCommand(GpuCommandType.TEX_SUB_IMAGE_2D, GpuCommandPhase.BEGIN, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        suspendPixelUnpackBuffer();
        if (shouldUseDSA(target)) {
            RenderSystem.textureSubImage2D(getBoundTextureForServerState(), target, level, xoffset, yoffset, width, height, format, type, pixels);
        } else {
            // Non-main thread or proxy texture - use direct GL call
            RENDER_BACKEND.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
        }
        restorePixelUnpackBuffer();
        recordGpuCommand(GpuCommandType.TEX_SUB_IMAGE_2D, GpuCommandPhase.END, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_SUB_IMAGE_2D);
        TextureInfoCache.INSTANCE.onTexSubImage2D(target, level);
        maybeGenerateMipmap(target, level);

    }

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, long pixels_buffer_offset) {
        type = remapPixelTypeForGLES(format, type);
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glTexSubImage2D with buffer offset in display lists not yet supported");
        }
        recordGpuCommand(GpuCommandType.TEX_SUB_IMAGE_2D, GpuCommandPhase.BEGIN, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        RENDER_BACKEND.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels_buffer_offset);
        recordGpuCommand(GpuCommandType.TEX_SUB_IMAGE_2D, GpuCommandPhase.END, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_SUB_IMAGE_2D);
        TextureInfoCache.INSTANCE.onTexSubImage2D(target, level);
        maybeGenerateMipmap(target, level);

    }

    public static void glTexSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset, int width, int height, int depth, int format, int type, ByteBuffer pixels) {
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glTexSubImage3D in display lists not yet implemented - if you see this, please report!");
        }
        recordGpuCommand(GpuCommandType.TEX_SUB_IMAGE_3D, GpuCommandPhase.BEGIN, target, packDimensions(width, height));
        suspendPixelUnpackBuffer();
        RENDER_BACKEND.texSubImage3D(target, level, xoffset, yoffset, zoffset, width, height, depth, format, type, pixels);
        restorePixelUnpackBuffer();
        recordGpuCommand(GpuCommandType.TEX_SUB_IMAGE_3D, GpuCommandPhase.END, target, packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.TEX_SUB_IMAGE_3D);
    }

    public static void glCopyTexImage1D(int target, int level, int internalFormat, int x, int y, int width, int border) {
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glCopyTexImage1D in display lists not yet implemented - if you see this, please report!");
        }
        RENDER_BACKEND.copyTexImage1D(target, level, internalFormat, x, y, width, border);
    }

    public static void glCopyTexImage2D(int target, int level, int internalFormat, int x, int y, int width, int height, int border) {
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glCopyTexImage2D in display lists not yet implemented - if you see this, please report!");
        }
        recordGpuCommand(GpuCommandType.COPY_TEX_IMAGE_2D, GpuCommandPhase.BEGIN, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        RENDER_BACKEND.copyTexImage2D(target, level, internalFormat, x, y, width, height, border);
        recordGpuCommand(GpuCommandType.COPY_TEX_IMAGE_2D, GpuCommandPhase.END, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.COPY_TEX_IMAGE_2D);
    }

    public static void glCopyTexSubImage1D(int target, int level, int xoffset, int x, int y, int width) {
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glCopyTexSubImage1D in display lists not yet implemented - if you see this, please report!");
        }
        RENDER_BACKEND.copyTexSubImage1D(target, level, xoffset, x, y, width);
    }

    public static void glCopyTexSubImage2D(int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glCopyTexSubImage2D in display lists not yet implemented - if you see this, please report!");
        }
        recordGpuCommand(GpuCommandType.COPY_TEX_SUB_IMAGE_2D, GpuCommandPhase.BEGIN, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        RENDER_BACKEND.copyTexSubImage2D(target, level, xoffset, yoffset, x, y, width, height);
        recordGpuCommand(GpuCommandType.COPY_TEX_SUB_IMAGE_2D, GpuCommandPhase.END, getBoundTextureForGpuDiagnostics(), packDimensions(width, height));
        gpuCheckpoint(GpuCommandType.COPY_TEX_SUB_IMAGE_2D);
    }

    public static void glCopyTexSubImage3D(int target, int level, int xoffset, int yoffset, int zoffset, int x, int y, int width, int height) {
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glCopyTexSubImage3D in display lists not yet implemented - if you see this, please report!");
        }
        RENDER_BACKEND.copyTexSubImage3D(target, level, xoffset, yoffset, zoffset, x, y, width, height);
    }

    public static void glCullFace(int mode) {
        final RecordMode recordMode = DisplayListManager.getRecordMode();
        if (recordMode != RecordMode.NONE) {
            DisplayListManager.recordCullFace(mode);
            if (recordMode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || ctx().polygonState.getCullFaceMode() != mode) {
            if (caching) {
                mod(ctx().polygonState).setCullFaceMode(mode);
            }
            RENDER_BACKEND.cullFace(mode);
        }
    }

    public static void glFrontFace(int mode) {
        final RecordMode recordMode = DisplayListManager.getRecordMode();
        if (recordMode != RecordMode.NONE) {
            DisplayListManager.recordFrontFace(mode);
            if (recordMode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || ctx().polygonState.getFrontFace() != mode) {
            if (caching) {
                mod(ctx().polygonState).setFrontFace(mode);
            }
            RENDER_BACKEND.frontFace(mode);
        }
    }

    public static void glHint(int target, int hint) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordHint(target, hint);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final int shift = hintShift(target);
        final boolean tracked = shift >= 0 && isHintMode(hint);
        if (tracked) {
            final IntegerStateStack hints = ctx().hintState;
            final int code = hint == GL11.GL_FASTEST ? 1 : hint == GL11.GL_NICEST ? 2 : 0;
            final int packed = hints.getValue() & ~(3 << shift) | code << shift;
            if (packed != hints.getValue()) mod(hints).setValue(packed);
        }
        // The removed targets (perspective correction, point smooth, fog, generate mipmap) would raise
        // GL_INVALID_ENUM on the core profile; a value the driver rejects is still forwarded so it reports
        // the error instead of the call being dropped.
        if (!isDriverHint(target) || !isHintMode(hint)) {
            RENDER_BACKEND.hint(target, hint);
        }
    }

    /** Whether {@code glHint} accepts the target on the core profile. */
    private static boolean isDriverHint(int target) {
        return switch (target) {
            case GL11.GL_PERSPECTIVE_CORRECTION_HINT, GL11.GL_POINT_SMOOTH_HINT, GL11.GL_FOG_HINT,
                 GL14.GL_GENERATE_MIPMAP_HINT -> false;
            default -> true;
        };
    }

    /** Bit offset of a hint target in the packed {@link GLContextState#hintState}, or -1 when untracked. */
    private static int hintShift(int target) {
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

    private static boolean isHintMode(int hint) {
        return hint == GL11.GL_FASTEST || hint == GL11.GL_NICEST || hint == GL11.GL_DONT_CARE;
    }

    /** Hint mode stored for {@code target} in the packed hint state. */
    private static int hintMode(GLContextState glCtx, int target) {
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

    private static void applyHintState(GLContextState glCtx) {
        for (final int target : DRIVER_HINTS) {
            RENDER_BACKEND.hint(target, hintMode(glCtx, target));
        }
    }

    public static void glLineStipple(int factor, short pattern) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordLineStipple(factor, pattern);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        mod(ctx().lineState).setStippleFactor(factor);
        mod(ctx().lineState).setStipplePattern(pattern);
    }

    public static void glPointSize(float size) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordPointSize(size);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || ctx().pointState.getSize() != size) {
            if (caching) {
                mod(ctx().pointState).setSize(size);
            }
            RENDER_BACKEND.pointSize(size);
        }
    }

    public static void glPolygonMode(int face, int polygonMode) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordPolygonMode(face, polygonMode);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        // Track front/back separately in cache (Mesa compat semantics), but always issue GL_FRONT_AND_BACK to the driver (core profile constraint).
        final boolean caching = isCachingEnabled();
        final boolean needsUpdate;
        if (face == GL11.GL_FRONT) {
            needsUpdate = BYPASS_CACHE || !caching || ctx().polygonState.getFrontMode() != polygonMode;
            if (caching && needsUpdate) mod(ctx().polygonState).setFrontMode(polygonMode);
        } else if (face == GL11.GL_BACK) {
            needsUpdate = BYPASS_CACHE || !caching || ctx().polygonState.getBackMode() != polygonMode;
            if (caching && needsUpdate) mod(ctx().polygonState).setBackMode(polygonMode);
        } else { // GL_FRONT_AND_BACK
            needsUpdate = BYPASS_CACHE || !caching || ctx().polygonState.getFrontMode() != polygonMode || ctx().polygonState.getBackMode() != polygonMode;
            if (caching && needsUpdate) {
                mod(ctx().polygonState).setFrontMode(polygonMode);
                mod(ctx().polygonState).setBackMode(polygonMode);
            }
        }
        if (needsUpdate) {
            RENDER_BACKEND.polygonMode(GL11.GL_FRONT_AND_BACK, polygonMode);
        }
    }

    public static void glPolygonOffset(float factor, float units) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordPolygonOffset(factor, units);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || ctx().polygonState.getOffsetFactor() != factor || ctx().polygonState.getOffsetUnits() != units) {
            if (caching) {
                mod(ctx().polygonState).setOffsetFactor(factor);
                mod(ctx().polygonState).setOffsetUnits(units);
            }
            RENDER_BACKEND.polygonOffset(factor, units);
        }
    }

    public static void glPolygonOffsetClamp(float factor, float units, float clamp) {
        if (clamp == 0.0f) {
            glPolygonOffset(factor, units);
            return;
        }
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glPolygonOffsetClamp in display lists not yet implemented - if you see this, please report!");
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || ctx().polygonState.getOffsetFactor() != factor || ctx().polygonState.getOffsetUnits() != units || ctx().polygonState.getOffsetClamp() != clamp) {
            if (caching) {
                mod(ctx().polygonState).setOffsetFactor(factor);
                mod(ctx().polygonState).setOffsetUnits(units);
                mod(ctx().polygonState).setOffsetClamp(clamp);
            }
            RENDER_BACKEND.polygonOffsetClamp(factor, units, clamp);
        }
    }

    public static void glReadBuffer(int mode) {
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glReadBuffer in display lists not yet implemented - if you see this, please report!");
        }
        RENDER_BACKEND.readBuffer(mode);
    }

    public static void glScissor(int x, int y, int width, int height) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordScissor(x, y, width, height);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        RENDER_BACKEND.scissor(x, y, width, height);
        if (isCachingEnabled()) {
            mod(ctx().scissorState).setScissor(x, y, width, height);
        }
    }

    public static void glStencilFunc(int func, int ref, int mask) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordStencilFunc(func, ref, mask);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || ctx().stencilState.getFuncFront() != func || ctx().stencilState.getRefFront() != ref || ctx().stencilState.getValueMaskFront() != mask) {
            if (caching) {
                mod(ctx().stencilState).setFunc(func, ref, mask);
            }
            RENDER_BACKEND.stencilFunc(func, ref, mask);
        }
    }

    public static void glStencilMask(int mask) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordStencilMask(mask);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || ctx().stencilState.getWriteMaskFront() != mask) {
            if (caching) {
                mod(ctx().stencilState).setWriteMask(mask);
            }
            RENDER_BACKEND.stencilMask(mask);
        }
    }

    public static void glStencilOp(int fail, int zfail, int zpass) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordStencilOp(fail, zfail, zpass);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || ctx().stencilState.getFailOpFront() != fail || ctx().stencilState.getZFailOpFront() != zfail || ctx().stencilState.getZPassOpFront() != zpass) {
            if (caching) {
                mod(ctx().stencilState).setOp(fail, zfail, zpass);
            }
            RENDER_BACKEND.stencilOp(fail, zfail, zpass);
        }
    }

    public static void glPixelStorei(int pname, int param) {
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glPixelStorei in display lists not yet implemented - if you see this, please report!");
        }
        if (isCachingEnabled()) {
            if (PixelStoreState.isPack(pname)) {
                ctx().pixelPackState = ctx().pixelPackState.with(pname, param);
            } else {
                ctx().pixelUnpackState = ctx().pixelUnpackState.with(pname, param);
            }
        }
        RENDER_BACKEND.pixelStorei(pname, param);
    }

    public static void glPixelStoref(int pname, float param) {
        glPixelStorei(pname, param < 0 ? -Math.round(-param) : Math.round(param));
    }

    public static void glCallLists(IntBuffer lists) {
        while (lists.hasRemaining()) {
            final int listId = lists.get() + ctx().listBase;
            glCallList(listId);
        }
    }

    public static void glCallLists(ShortBuffer lists) {
        while (lists.hasRemaining()) {
            final int listId = (lists.get() & 0xFFFF) + ctx().listBase;
            glCallList(listId);
        }
    }

    public static void glCallLists(ByteBuffer lists) {
        while (lists.hasRemaining()) {
            final int listId = (lists.get() & 0xFF) + ctx().listBase;
            glCallList(listId);
        }
    }

    public static void glListBase(int base) {
        ctx().listBase = base;
    }

    public static void glClipPlane(int plane, DoubleBuffer equation) {
        if (DisplayListManager.isRecording()) {
            final int pos = equation.position();
            DisplayListManager.recordClipPlane(plane, equation.get(pos), equation.get(pos + 1), equation.get(pos + 2), equation.get(pos + 3));
            return;
        }
        final int index = plane - GL11.GL_CLIP_PLANE0;
        if (index < 0 || index >= MAX_CLIP_PLANES) return;
        final int pos = equation.position();
        ctx().clipPlaneState.setPlane(index, equation.get(pos), equation.get(pos + 1), equation.get(pos + 2), equation.get(pos + 3), ctx().modelViewMatrix);
        ctx().clipPlaneGeneration++;
    }

    /** Returns true if any GL_CLIP_PLANE0..7 is currently enabled. */
    public static boolean anyClipPlaneEnabled() {
        return ctx().clipPlaneEnabledMask != 0;
    }

    public static void glClearStencil(int s) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordClearStencil(s);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        if (BYPASS_CACHE || !caching || ctx().stencilState.getClearValue() != s) {
            if (caching) {
                mod(ctx().stencilState).setClearValue(s);
            }
            RENDER_BACKEND.clearStencil(s);
        }
    }

    // Draw Buffer Commands (GL 2.0+)
    public static void glDrawBuffers(int buffer) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDrawBuffers(1, buffer);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        RENDER_BACKEND.drawBuffers(buffer);
    }

    public static void glDrawBuffers(IntBuffer bufs) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDrawBuffers(bufs.remaining(), bufs);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        RENDER_BACKEND.drawBuffers(bufs);
    }

    public static void glDrawBuffers(int[] bufs) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordDrawBuffers(bufs.length, IntBuffer.wrap(bufs));
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        RENDER_BACKEND.drawBuffers(bufs);
    }

    public static void glSampleCoverage(float value, boolean invert) {
        if (DisplayListManager.isRecording()) {
            throw new UnsupportedOperationException("glSampleCoverage in display lists not yet implemented - if you see this, please report!");
        }
        RENDER_BACKEND.sampleCoverage(value, invert);
    }

    // Stencil Separate Functions (GL 2.0+)
    public static void glStencilFuncSeparate(int face, int func, int ref, int mask) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordStencilFuncSeparate(face, func, ref, mask);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        boolean needsUpdate = BYPASS_CACHE || !caching;
        if (!needsUpdate) {
            if (face == GL11.GL_FRONT || face == GL11.GL_FRONT_AND_BACK) {
                needsUpdate = ctx().stencilState.getFuncFront() != func || ctx().stencilState.getRefFront() != ref || ctx().stencilState.getValueMaskFront() != mask;
            }
            if (!needsUpdate && (face == GL11.GL_BACK || face == GL11.GL_FRONT_AND_BACK)) {
                needsUpdate = ctx().stencilState.getFuncBack() != func || ctx().stencilState.getRefBack() != ref || ctx().stencilState.getValueMaskBack() != mask;
            }
        }
        if (needsUpdate) {
            if (caching) {
                if (face == GL11.GL_FRONT || face == GL11.GL_FRONT_AND_BACK) {
                    mod(ctx().stencilState).setFuncFront(func);
                    mod(ctx().stencilState).setRefFront(ref);
                    mod(ctx().stencilState).setValueMaskFront(mask);
                }
                if (face == GL11.GL_BACK || face == GL11.GL_FRONT_AND_BACK) {
                    mod(ctx().stencilState).setFuncBack(func);
                    mod(ctx().stencilState).setRefBack(ref);
                    mod(ctx().stencilState).setValueMaskBack(mask);
                }
            }
            RENDER_BACKEND.stencilFuncSeparate(face, func, ref, mask);
        }
    }

    public static void glStencilMaskSeparate(int face, int mask) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordStencilMaskSeparate(face, mask);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        boolean needsUpdate = BYPASS_CACHE || !caching;
        if (!needsUpdate) {
            if (face == GL11.GL_FRONT || face == GL11.GL_FRONT_AND_BACK) {
                needsUpdate = ctx().stencilState.getWriteMaskFront() != mask;
            }
            if (!needsUpdate && (face == GL11.GL_BACK || face == GL11.GL_FRONT_AND_BACK)) {
                needsUpdate = ctx().stencilState.getWriteMaskBack() != mask;
            }
        }
        if (needsUpdate) {
            if (caching) {
                if (face == GL11.GL_FRONT || face == GL11.GL_FRONT_AND_BACK) {
                    mod(ctx().stencilState).setWriteMaskFront(mask);
                }
                if (face == GL11.GL_BACK || face == GL11.GL_FRONT_AND_BACK) {
                    mod(ctx().stencilState).setWriteMaskBack(mask);
                }
            }
            RENDER_BACKEND.stencilMaskSeparate(face, mask);
        }
    }

    public static void glStencilOpSeparate(int face, int sfail, int dpfail, int dppass) {
        final RecordMode mode = DisplayListManager.getRecordMode();
        if (mode != RecordMode.NONE) {
            DisplayListManager.recordStencilOpSeparate(face, sfail, dpfail, dppass);
            if (mode == RecordMode.COMPILE) {
                return;
            }
        }
        final boolean caching = isCachingEnabled();
        boolean needsUpdate = BYPASS_CACHE || !caching;
        if (!needsUpdate) {
            if (face == GL11.GL_FRONT || face == GL11.GL_FRONT_AND_BACK) {
                needsUpdate = ctx().stencilState.getFailOpFront() != sfail || ctx().stencilState.getZFailOpFront() != dpfail || ctx().stencilState.getZPassOpFront() != dppass;
            }
            if (!needsUpdate && (face == GL11.GL_BACK || face == GL11.GL_FRONT_AND_BACK)) {
                needsUpdate = ctx().stencilState.getFailOpBack() != sfail || ctx().stencilState.getZFailOpBack() != dpfail || ctx().stencilState.getZPassOpBack() != dppass;
            }
        }
        if (needsUpdate) {
            if (caching) {
                if (face == GL11.GL_FRONT || face == GL11.GL_FRONT_AND_BACK) {
                    mod(ctx().stencilState).setFailOpFront(sfail);
                    mod(ctx().stencilState).setZFailOpFront(dpfail);
                    mod(ctx().stencilState).setZPassOpFront(dppass);
                }
                if (face == GL11.GL_BACK || face == GL11.GL_FRONT_AND_BACK) {
                    mod(ctx().stencilState).setFailOpBack(sfail);
                    mod(ctx().stencilState).setZFailOpBack(dpfail);
                    mod(ctx().stencilState).setZPassOpBack(dppass);
                }
            }
            RENDER_BACKEND.stencilOpSeparate(face, sfail, dpfail, dppass);
        }
    }

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

    public static int glCreateBuffers() {
        return RENDER_BACKEND.createBuffers();
    }

    public static void glCreateBuffers(IntBuffer buffers) {
        for (int i = buffers.position(); i < buffers.limit(); i++) buffers.put(i, RENDER_BACKEND.createBuffers());
    }

    public static void glCreateBuffers(int[] buffers) {
        for (int i = 0; i < buffers.length; i++) buffers[i] = RENDER_BACKEND.createBuffers();
    }

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
    public static void nglBufferData(int target, long size, long data, int usage) {
        if (data == 0L) {
            glBufferData(target, size, usage);
            return;
        }
        glBufferData(target, MemoryUtilities.memByteBuffer(data, checkedSize(size)), usage);
    }
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
    public static ByteBuffer glMapBuffer(int target, int access) { return RENDER_BACKEND.mapBuffer(target, access); }
    public static ByteBuffer glMapBuffer(int target, int access, ByteBuffer old_buffer) { return glMapBuffer(target, access); }
    public static ByteBuffer glMapBuffer(int target, int access, long length, ByteBuffer old_buffer) { return RENDER_BACKEND.mapBuffer(target, access, length, old_buffer); }
    public static ByteBuffer glMapBufferRange(int target, long offset, long length, int access) { return RENDER_BACKEND.mapBufferRange(target, offset, length, access); }
    public static ByteBuffer glMapBufferRange(int target, long offset, long length, int access, ByteBuffer old_buffer) { return glMapBufferRange(target, offset, length, access); }
    public static long nglMapBuffer(int target, int access) {
        final ByteBuffer buf = RENDER_BACKEND.mapBuffer(target, access);
        return buf == null ? 0L : MemoryUtilities.memAddress0(buf);
    }
    public static boolean glUnmapBuffer(int target) { return RENDER_BACKEND.unmapBuffer(target); }
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
    public static void glGetBufferParameteriv(int target, int pname, IntBuffer params) { params.put(params.position(), glGetBufferParameteri(target, pname)); }
    public static void glGetBufferParameteriv(int target, int pname, int[] params) { params[0] = glGetBufferParameteri(target, pname); }
    public static void glBufferStorage(int target, java.nio.ByteBuffer data, int flags) { RENDER_BACKEND.bufferStorage(target, data, flags); }
    public static void glBufferStorage(int target, long size, int flags) { RENDER_BACKEND.bufferStorage(target, size, flags); }
    public static void glBufferStorage(int target, ShortBuffer data, int flags) { glBufferStorage(target, MemoryUtilities.memByteBuffer(data), flags); }
    public static void glBufferStorage(int target, IntBuffer data, int flags) { glBufferStorage(target, MemoryUtilities.memByteBuffer(data), flags); }
    public static void glBufferStorage(int target, FloatBuffer data, int flags) { glBufferStorage(target, MemoryUtilities.memByteBuffer(data), flags); }
    public static void glBufferStorage(int target, DoubleBuffer data, int flags) { glBufferStorage(target, MemoryUtilities.memByteBuffer(data), flags); }
    public static void glBufferStorage(int target, short[] data, int flags) {
        final ByteBuffer copy = copyOf(data);
        glBufferStorage(target, copy, flags);
        MemoryUtilities.memFree(copy);
    }
    public static void glBufferStorage(int target, int[] data, int flags) {
        final ByteBuffer copy = copyOf(data);
        glBufferStorage(target, copy, flags);
        MemoryUtilities.memFree(copy);
    }
    public static void glBufferStorage(int target, float[] data, int flags) {
        final ByteBuffer copy = copyOf(data);
        glBufferStorage(target, copy, flags);
        MemoryUtilities.memFree(copy);
    }
    public static void glBufferStorage(int target, double[] data, int flags) {
        final ByteBuffer copy = copyOf(data);
        glBufferStorage(target, copy, flags);
        MemoryUtilities.memFree(copy);
    }

    public static void glNamedBufferData(int buffer, long size, int usage) { RENDER_BACKEND.namedBufferData(buffer, size, usage); }
    public static void glNamedBufferData(int buffer, ByteBuffer data, int usage) { RENDER_BACKEND.namedBufferData(buffer, data, usage); }
    public static void glNamedBufferData(int buffer, FloatBuffer data, int usage) { RENDER_BACKEND.namedBufferData(buffer, data, usage); }
    public static void glNamedBufferData(int buffer, ShortBuffer data, int usage) { glNamedBufferData(buffer, MemoryUtilities.memByteBuffer(data), usage); }
    public static void glNamedBufferData(int buffer, IntBuffer data, int usage) { glNamedBufferData(buffer, MemoryUtilities.memByteBuffer(data), usage); }
    public static void glNamedBufferData(int buffer, LongBuffer data, int usage) { glNamedBufferData(buffer, MemoryUtilities.memByteBuffer(data), usage); }
    public static void glNamedBufferData(int buffer, DoubleBuffer data, int usage) { glNamedBufferData(buffer, MemoryUtilities.memByteBuffer(data), usage); }
    public static void glNamedBufferData(int buffer, short[] data, int usage) {
        final ByteBuffer copy = copyOf(data);
        glNamedBufferData(buffer, copy, usage);
        MemoryUtilities.memFree(copy);
    }
    public static void glNamedBufferData(int buffer, int[] data, int usage) {
        final ByteBuffer copy = copyOf(data);
        glNamedBufferData(buffer, copy, usage);
        MemoryUtilities.memFree(copy);
    }
    public static void glNamedBufferData(int buffer, long[] data, int usage) {
        final ByteBuffer copy = copyOf(data);
        glNamedBufferData(buffer, copy, usage);
        MemoryUtilities.memFree(copy);
    }
    public static void glNamedBufferData(int buffer, float[] data, int usage) {
        final ByteBuffer copy = copyOf(data);
        glNamedBufferData(buffer, copy, usage);
        MemoryUtilities.memFree(copy);
    }
    public static void glNamedBufferData(int buffer, double[] data, int usage) {
        final ByteBuffer copy = copyOf(data);
        glNamedBufferData(buffer, copy, usage);
        MemoryUtilities.memFree(copy);
    }

    public static void glNamedBufferSubData(int buffer, long offset, ByteBuffer data) { RENDER_BACKEND.namedBufferSubData(buffer, offset, data); }
    public static void glNamedBufferSubData(int buffer, long offset, ShortBuffer data) { glNamedBufferSubData(buffer, offset, MemoryUtilities.memByteBuffer(data)); }
    public static void glNamedBufferSubData(int buffer, long offset, IntBuffer data) { glNamedBufferSubData(buffer, offset, MemoryUtilities.memByteBuffer(data)); }
    public static void glNamedBufferSubData(int buffer, long offset, LongBuffer data) { glNamedBufferSubData(buffer, offset, MemoryUtilities.memByteBuffer(data)); }
    public static void glNamedBufferSubData(int buffer, long offset, FloatBuffer data) { glNamedBufferSubData(buffer, offset, MemoryUtilities.memByteBuffer(data)); }
    public static void glNamedBufferSubData(int buffer, long offset, DoubleBuffer data) { glNamedBufferSubData(buffer, offset, MemoryUtilities.memByteBuffer(data)); }
    public static void glNamedBufferSubData(int buffer, long offset, short[] data) {
        final ByteBuffer copy = copyOf(data);
        glNamedBufferSubData(buffer, offset, copy);
        MemoryUtilities.memFree(copy);
    }
    public static void glNamedBufferSubData(int buffer, long offset, int[] data) {
        final ByteBuffer copy = copyOf(data);
        glNamedBufferSubData(buffer, offset, copy);
        MemoryUtilities.memFree(copy);
    }
    public static void glNamedBufferSubData(int buffer, long offset, long[] data) {
        final ByteBuffer copy = copyOf(data);
        glNamedBufferSubData(buffer, offset, copy);
        MemoryUtilities.memFree(copy);
    }
    public static void glNamedBufferSubData(int buffer, long offset, float[] data) {
        final ByteBuffer copy = copyOf(data);
        glNamedBufferSubData(buffer, offset, copy);
        MemoryUtilities.memFree(copy);
    }
    public static void glNamedBufferSubData(int buffer, long offset, double[] data) {
        final ByteBuffer copy = copyOf(data);
        glNamedBufferSubData(buffer, offset, copy);
        MemoryUtilities.memFree(copy);
    }

    public static void nglMultiDrawElementsBaseVertex(int mode, long count, int type, long indices, int primcount, long basevertex) {
        RENDER_BACKEND.multiDrawElementsBaseVertex(mode, count, type, indices, primcount, basevertex);
    }

    private static int checkedSize(long size) {
        if (size < 0 || size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Buffer range does not fit a ByteBuffer wrapper: " + size);
        }
        return (int) size;
    }

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

    public static boolean glIsVertexArray(int array) {
        return RENDER_BACKEND.isVertexArray(array);
    }

    public static void glBindVertexArrayAPPLE(int array) {
        glBindVertexArray(array);
    }

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


    public static void glBindFramebuffer(int target, int framebuffer) {
        if (target == GL30.GL_FRAMEBUFFER) {
            if (ctx().drawFramebuffer == framebuffer && ctx().readFramebuffer == framebuffer) return;
            if (ctx().drawFramebuffer != framebuffer) ctx().drawFramebufferGeneration++;
            ctx().drawFramebuffer = framebuffer;
            ctx().readFramebuffer = framebuffer;
        } else if (target == GL30.GL_DRAW_FRAMEBUFFER) {
            if (ctx().drawFramebuffer == framebuffer) return;
            ctx().drawFramebufferGeneration++;
            ctx().drawFramebuffer = framebuffer;
        } else if (target == GL30.GL_READ_FRAMEBUFFER) {
            if (ctx().readFramebuffer == framebuffer) return;
            ctx().readFramebuffer = framebuffer;
        }
        recordGpuCommand(GpuCommandType.FRAMEBUFFER_BIND, target, framebuffer);
        RENDER_BACKEND.bindFramebuffer(target, framebuffer);
    }

    public static void glDeleteFramebuffers(int framebuffer) {
        if (ctx().drawFramebuffer == framebuffer) ctx().drawFramebuffer = 0;
        if (ctx().readFramebuffer == framebuffer) ctx().readFramebuffer = 0;
        RENDER_BACKEND.deleteFramebuffers(framebuffer);
    }

    public static void glDeleteFramebuffers(IntBuffer framebuffers) {
        for (int i = framebuffers.position(); i < framebuffers.limit(); i++) glDeleteFramebuffers(framebuffers.get(i));
    }

    public static void glDeleteFramebuffers(int[] framebuffers) {
        for (int framebuffer : framebuffers) glDeleteFramebuffers(framebuffer);
    }

    public static int glGenFramebuffers() {
        return RENDER_BACKEND.genFramebuffers();
    }

    public static void glGenFramebuffers(IntBuffer framebuffers) {
        requireContext("glGenFramebuffers");
        for (int i = framebuffers.position(); i < framebuffers.limit(); i++) framebuffers.put(i, RENDER_BACKEND.genFramebuffers());
    }

    public static void glGenFramebuffers(int[] framebuffers) {
        requireContext("glGenFramebuffers");
        for (int i = 0; i < framebuffers.length; i++) framebuffers[i] = RENDER_BACKEND.genFramebuffers();
    }

    public static int glCheckFramebufferStatus(int target) {
        return RENDER_BACKEND.checkFramebufferStatus(target);
    }

    public static int glGetFramebufferAttachmentParameteri(int target, int attachment, int pname) {
        return RENDER_BACKEND.getFramebufferAttachmentParameteri(target, attachment, pname);
    }

    public static void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        RENDER_BACKEND.framebufferTexture2D(target, attachment, textarget, texture, level);
    }

    public static void glFramebufferTexture(int target, int attachment, int texture, int level) { RENDER_BACKEND.framebufferTexture(target, attachment, texture, level); }

    public static int glGenRenderbuffers() { return RENDER_BACKEND.genRenderbuffers(); }

    public static void glGenRenderbuffers(IntBuffer renderbuffers) {
        requireContext("glGenRenderbuffers");
        for (int i = renderbuffers.position(); i < renderbuffers.limit(); i++) renderbuffers.put(i, RENDER_BACKEND.genRenderbuffers());
    }

    public static void glGenRenderbuffers(int[] renderbuffers) {
        requireContext("glGenRenderbuffers");
        for (int i = 0; i < renderbuffers.length; i++) renderbuffers[i] = RENDER_BACKEND.genRenderbuffers();
    }

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

    public static void glFramebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) { RENDER_BACKEND.framebufferRenderbuffer(target, attachment, renderbuffertarget, renderbuffer); }

    public static boolean glIsTexture(int texture) { return RENDER_BACKEND.isTexture(texture); }

    public static boolean glIsFramebuffer(int framebuffer) { return RENDER_BACKEND.isFramebuffer(framebuffer); }

    public static boolean glIsRenderbuffer(int renderbuffer) { return RENDER_BACKEND.isRenderbuffer(renderbuffer); }

    public static boolean glIsSampler(int sampler) { return RENDER_BACKEND.isSampler(sampler); }

    public static boolean glIsQuery(int query) { return RENDER_BACKEND.isQuery(query); }

    public static boolean glIsProgram(int obj) {
        return RENDER_BACKEND.isProgram(obj);
    }

    public static boolean glIsShader(int obj) {
        return RENDER_BACKEND.isShader(obj);
    }

    public static long glFenceSync(int condition, int flags) { return RENDER_BACKEND.fenceSync(condition, flags); }

    public static int glClientWaitSync(long sync, int flags, long timeout) { return RENDER_BACKEND.clientWaitSync(sync, flags, timeout); }

    public static void glDeleteSync(long sync) {
        RENDER_BACKEND.deleteSync(sync);
    }

    public static void glWaitSync(long sync, int flags, long timeout) { RENDER_BACKEND.waitSync(sync, flags, timeout); }

    public static int glGetSynci(long sync, int pname, IntBuffer length) { return RENDER_BACKEND.getSynci(sync, pname, length); }

    public static void glGenQueries(IntBuffer ids) { RENDER_BACKEND.genQueries(ids); }

    public static void glGenQueries(int[] ids) {
        requireContext("glGenQueries");
        for (int i = 0; i < ids.length; i++) ids[i] = RENDER_BACKEND.genQueries();
    }

    public static int glGenQueries() {
        requireContext("glGenQueries");
        return RENDER_BACKEND.genQueries();
    }

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

    public static void glBindBufferBase(int target, int index, int buffer) { RENDER_BACKEND.bindBufferBase(target, index, buffer); }

    public static int glGetUniformBlockIndex(int program, CharSequence name) { return RENDER_BACKEND.getUniformBlockIndex(program, name); }

    public static int glGetUniformBlockIndex(int program, ByteBuffer name) { return RENDER_BACKEND.getUniformBlockIndex(program, name); }

    public static void glUniformBlockBinding(int program, int blockIndex, int binding) { RENDER_BACKEND.uniformBlockBinding(program, blockIndex, binding); }

    public static void glFlushMappedBufferRange(int target, long offset, long length) { RENDER_BACKEND.flushMappedBufferRange(target, offset, length); }

    public static void glClearBufferData(int target, int internalformat, int format, int type, ByteBuffer data) { RENDER_BACKEND.clearBufferData(target, internalformat, format, type, data); }

    public static void glSpecializeShader(int shader, CharSequence entryPoint, IntBuffer constantIndex, IntBuffer constantValue) {
        RENDER_BACKEND.specializeShader(shader, entryPoint, constantIndex, constantValue);
    }

    public static void glBindFragDataLocation(int program, int colorNumber, CharSequence name) { RENDER_BACKEND.bindFragDataLocation(program, colorNumber, name); }

    public static void glGetActiveAttrib(int program, int index, IntBuffer length, IntBuffer size, IntBuffer type, ByteBuffer name) {
        RENDER_BACKEND.getActiveAttrib(program, index, length, size, type, name);
    }

    public static void glGetActiveAttrib(int program, int index, int[] length, int[] size, int[] type, ByteBuffer name) {
        RENDER_BACKEND.getActiveAttrib(program, index, length, size, type, name);
    }

    public static String glGetActiveAttrib(int program, int index, int maxLength, IntBuffer sizeType) {
        return RENDER_BACKEND.getActiveAttrib(program, index, maxLength, sizeType);
    }

    public static String glGetActiveAttrib(int program, int index, int maxLength, IntBuffer size, IntBuffer type) {
        final IntBuffer sizeType = BufferUtils.createIntBuffer(2);
        final String name = RENDER_BACKEND.getActiveAttrib(program, index, maxLength, sizeType);
        if (size != null) size.put(size.position(), sizeType.get(0));
        if (type != null) type.put(type.position(), sizeType.get(1));
        return name;
    }

    public static String glGetActiveAttrib(int program, int index, IntBuffer size, IntBuffer type) {
        return glGetActiveAttrib(program, index, glGetProgrami(program, GL20.GL_ACTIVE_ATTRIBUTE_MAX_LENGTH), size, type);
    }

    public static void glObjectLabel(int identifier, int name, CharSequence label) { RENDER_BACKEND.objectLabel(identifier, name, label); }

    public static String glGetObjectLabel(int identifier, int name, int bufSize) { return RENDER_BACKEND.getObjectLabel(identifier, name); }

    public static void glPushDebugGroup(int source, int id, CharSequence message) { RENDER_BACKEND.pushDebugGroup(source, id, message); }

    public static void glPopDebugGroup() { RENDER_BACKEND.popDebugGroup(); }

    public static void glDebugMessageInsert(int source, int type, int id, int severity, CharSequence buf) { RENDER_BACKEND.debugMessageInsert(source, type, id, severity, buf); }

    public static void glDebugMessageControl(int source, int type, int severity, IntBuffer ids, boolean enabled) { RENDER_BACKEND.debugMessageControl(source, type, severity, ids, enabled); }

    public static int glGetDebugMessageLog(int count, IntBuffer sources, IntBuffer types, IntBuffer ids, IntBuffer severities, IntBuffer lengths, ByteBuffer messageLog) {
        return RENDER_BACKEND.getDebugMessageLog(count, sources, types, ids, severities, lengths, messageLog);
    }

    public static void registerDebugMessageListener(GLDebugMessageListener listener, long userParam) {
        RENDER_BACKEND.debugMessageCallback(listener, userParam);
    }

    public static void glDebugMessageCallback(KHRDebugCallback callback) { GLSMDraws.glDebugMessageCallback(callback); }

    /**
     * Redirect target for {@code org.lwjgl.opengl.GLUtil.setupDebugMessageCallback(PrintStream)}.
     * GLUtil is core-excluded from transformation, so the call sites in mod classes (Distant
     * Horizons' debug logger) are rewritten to this entry point instead.
     */
    public static Callback setupDebugMessageCallback(PrintStream stream) {
        return GLUtil.setupDebugMessageCallback(stream);
    }

    public static void glGenerateMipmap(int target) {
        recordGpuCommand(GpuCommandType.GENERATE_MIPMAP, GpuCommandPhase.BEGIN, getBoundTextureForGpuDiagnostics(), target);
        RENDER_BACKEND.generateMipmap(target);
        recordGpuCommand(GpuCommandType.GENERATE_MIPMAP, GpuCommandPhase.END, getBoundTextureForGpuDiagnostics(), target);
        gpuCheckpoint(GpuCommandType.GENERATE_MIPMAP);
    }

    public static void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
        final boolean cacheTrusted = isGpuDiagnosticCacheTrusted();
        final int diagnosticReadFramebuffer = GpuCommandDiagnostics.trustedValue(cacheTrusted, ctx().readFramebuffer);
        final int diagnosticDrawFramebuffer = GpuCommandDiagnostics.trustedValue(cacheTrusted, ctx().drawFramebuffer);
        recordGpuCommand(
            GpuCommandType.BLIT_FRAMEBUFFER,
            GpuCommandPhase.BEGIN,
            diagnosticReadFramebuffer,
            diagnosticDrawFramebuffer
        );
        RENDER_BACKEND.blitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
        recordGpuCommand(
            GpuCommandType.BLIT_FRAMEBUFFER,
            GpuCommandPhase.END,
            diagnosticReadFramebuffer,
            diagnosticDrawFramebuffer
        );
        gpuCheckpoint(GpuCommandType.BLIT_FRAMEBUFFER);
    }

    public static void setActiveTexture(int textureUnit) {
        glActiveTexture(textureUnit);
    }

    public static void glMultiTexCoord2f(int target, float s, float t) { GLSMCurrentAttribs.glMultiTexCoord2f(target, s, t); }

    public static void glMultiTexCoord2d(int target, double s, double t) {
        glMultiTexCoord2f(target, (float) s, (float) t);
    }

    public static void glMultiTexCoord2s(int target, short s, short t) {
        glMultiTexCoord2f(target, s, t);
    }

    public static void setLightmapTextureCoords(int unit, float x, float y) {
        if (unit == GL13.GL_TEXTURE1) {
            final GLContextState glCtx = ctx();
            glCtx.lastBrightnessX = x;
            glCtx.lastBrightnessY = y;
            glCtx.dirtyLightmapAttrib = true;
            if (GLSMHooks.LIGHTMAP_COORDS.hasListeners()) {
                GLSMHooks.lightmapCoordsEvent.x = x;
                GLSMHooks.lightmapCoordsEvent.y = y;
                GLSMHooks.LIGHTMAP_COORDS.post(GLSMHooks.lightmapCoordsEvent);
            }
        }
    }

    private static void checkMismatch(int cap) {
        final int cached = GLStateManager.glGetInteger(cap);
        final int actual = RENDER_BACKEND.getInteger(cap);
        if (actual != cached) {
            throw new IllegalStateException("GLSM Mismatch! cap=" + cap + " cached=" + cached + " actual=" + actual);
        }
    }

    public static boolean isFramebufferEnabled() {
        return initConfig != null && initConfig.isFramebufferSupported() && initConfig.isFboEnabled();
    }

    private static final boolean FFP_WARN_ON_UNSUPPORTED = Boolean.parseBoolean(System.getProperty("angelica.ffp.warnOnUnsupported", "false"));

    private static final Set<String> warnedFFPFunctions = new HashSet<>();

    /**
     * Guards against FFP features not supported in core profile.
     * Default: throws UnsupportedOperationException.
     * With {@code -Dangelica.ffp.warnOnUnsupported=true}: warns once per function (diagnostic mode).
     * @return true (always — caller should skip the GL call)
     */
    private static boolean guardUnsupportedFFP(String function, String explanation) {
        if (!FFP_WARN_ON_UNSUPPORTED) {
            throw new UnsupportedOperationException(function + ": " + explanation);
        }
        if (warnedFFPFunctions.add(function)) {
            LOGGER.warn("{}: {}", function, explanation, new Throwable("Stack trace"));
        }
        return true;
    }

    /**
     * In core profile, GL_CLAMP (0x2900) is removed. Remap to GL_CLAMP_TO_EDGE for wrap modes. Semantically equivalent when texture border is not used (border
     * textures are also removed in core).
     */
    public static int remapTexClamp(int pname, int param) {
        if (param == GL11.GL_CLAMP && (pname == GL11.GL_TEXTURE_WRAP_S || pname == GL11.GL_TEXTURE_WRAP_T || pname == GL12.GL_TEXTURE_WRAP_R)) {
            return GL12.GL_CLAMP_TO_EDGE;
        }
        return param;
    }

    public static float remapTexClamp(int pname, float param) {
        if ((int) param == GL11.GL_CLAMP && (pname == GL11.GL_TEXTURE_WRAP_S || pname == GL11.GL_TEXTURE_WRAP_T || pname == GL12.GL_TEXTURE_WRAP_R)) {
            return (float) GL12.GL_CLAMP_TO_EDGE;
        }
        return param;
    }

    public static void remapTexClampBuffer(int pname, IntBuffer params) {
        if (params.remaining() >= 1) {
            final int val = params.get(params.position());
            final int remapped = remapTexClamp(pname, val);
            if (remapped != val) params.put(params.position(), remapped);
        }
    }

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

    public static void glTexEnvi(int target, int pname, int param) {
        handleTexEnvScalar(target, pname, param);
    }

    public static void glTexEnvf(int target, int pname, float param) {
        handleTexEnvScalar(target, pname, param);
    }

    public static void glTexEnv(int target, int pname, FloatBuffer params) {
        if (target == GL11.GL_TEXTURE_ENV && pname == GL11.GL_TEXTURE_ENV_COLOR && params.remaining() >= 4) {
            final int pos = params.position();
            final var envState = ctx().textures.getTexEnvState(ctx().activeTextureUnit.getValue());
            envState.beforeModify();
            envState.envColorR = params.get(pos);
            envState.envColorG = params.get(pos + 1);
            envState.envColorB = params.get(pos + 2);
            envState.envColorA = params.get(pos + 3);
            ctx().fragmentGeneration++;
            return;
        }
        if (target == GL14.GL_TEXTURE_FILTER_CONTROL) {
            guardUnsupportedFFP(
                    "glTexEnv",
                    "LOD bias via buffer glTexEnv is not supported; use glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_LOD_BIAS, value) instead.");
            return;
        }
        // Silently ignore other buffer glTexEnv calls (some mods pass combine params via buffers)
    }

    public static void glTexEnv(int target, int pname, IntBuffer params) {
        if (target == GL11.GL_TEXTURE_ENV) {
            handleTexEnvScalar(target, pname, (float) params.get(params.position()));
        }
    }

    /**
     * Scalar glTexEnvi/glTexEnvf dispatch. glTexEnv is removed in core profile. GL_TEXTURE_FILTER_CONTROL (LOD bias) is remapped to glTexParameterf. All
     * GL_TEXTURE_ENV parameters including GL_COMBINE sub-params are tracked in per-unit TexEnvState.
     */
    private static void handleTexEnvScalar(int target, int pname, float param) {
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

    public static float glGetTexEnvf(int target, int pname) {
        return (float) glGetTexEnvi(target, pname);
    }

    public static int glGetTexEnvi(int target, int pname) {
        if (target != GL11.GL_TEXTURE_ENV) return 0;

        final var envState = ctx().textures.getTexEnvState(ctx().activeTextureUnit.getValue());
        return switch (pname) {
            case GL11.GL_TEXTURE_ENV_MODE -> envState.mode;
            case GL13.GL_COMBINE_RGB -> envState.combineRgb;
            case GL13.GL_COMBINE_ALPHA -> envState.combineAlpha;
            case GL13.GL_SOURCE0_RGB -> envState.sourceRgb[0];
            case GL13.GL_SOURCE1_RGB -> envState.sourceRgb[1];
            case GL13.GL_SOURCE2_RGB -> envState.sourceRgb[2];
            case GL13.GL_SOURCE0_ALPHA -> envState.sourceAlpha[0];
            case GL13.GL_SOURCE1_ALPHA -> envState.sourceAlpha[1];
            case GL13.GL_SOURCE2_ALPHA -> envState.sourceAlpha[2];
            case GL13.GL_OPERAND0_RGB -> envState.operandRgb[0];
            case GL13.GL_OPERAND1_RGB -> envState.operandRgb[1];
            case GL13.GL_OPERAND2_RGB -> envState.operandRgb[2];
            case GL13.GL_OPERAND0_ALPHA -> envState.operandAlpha[0];
            case GL13.GL_OPERAND1_ALPHA -> envState.operandAlpha[1];
            case GL13.GL_OPERAND2_ALPHA -> envState.operandAlpha[2];
            case GL13.GL_RGB_SCALE -> (int) envState.scaleRgb;
            case GL11.GL_ALPHA_SCALE -> (int) envState.scaleAlpha;
            default -> 0;
        };
    }

    public static void glGetTexEnv(int target, int pname, IntBuffer params) {
        if (target != GL11.GL_TEXTURE_ENV) return;
        if (pname == GL11.GL_TEXTURE_ENV_COLOR) {
            final var envState = ctx().textures.getTexEnvState(ctx().activeTextureUnit.getValue());
            params.put(params.position(), (int) (envState.envColorR * 2147483647.0f));
            params.put(params.position() + 1, (int) (envState.envColorG * 2147483647.0f));
            params.put(params.position() + 2, (int) (envState.envColorB * 2147483647.0f));
            params.put(params.position() + 3, (int) (envState.envColorA * 2147483647.0f));
        } else {
            params.put(params.position(), glGetTexEnvi(target, pname));
        }
    }

    public static void glGetTexEnv(int target, int pname, FloatBuffer params) {
        if (target != GL11.GL_TEXTURE_ENV) return;
        if (pname == GL11.GL_TEXTURE_ENV_COLOR) {
            final var envState = ctx().textures.getTexEnvState(ctx().activeTextureUnit.getValue());
            params.put(params.position(), envState.envColorR);
            params.put(params.position() + 1, envState.envColorG);
            params.put(params.position() + 2, envState.envColorB);
            params.put(params.position() + 3, envState.envColorA);
        } else {
            params.put(params.position(), (float) glGetTexEnvi(target, pname));
        }
    }

    private static final float[] texGenTempPlane = new float[4];

    public static void glTexGeni(int coord, int pname, int param) {
        if (!isCachingEnabled()) return;
        if (pname == GL11.GL_TEXTURE_GEN_MODE) {
            final int unit = ctx().activeTextureUnit.getValue();
            ctx().textures.getTexGenState(unit).setMode(coord, param);
            ctx().texGenGeneration++;
        }
    }

    public static void glTexGenf(int coord, int pname, float param) {
        // Single-float form only valid for GL_TEXTURE_GEN_MODE
        glTexGeni(coord, pname, (int) param);
    }

    public static void glTexGend(int coord, int pname, double param) {
        glTexGeni(coord, pname, (int) param);
    }

    public static void glTexGen(int coord, int pname, FloatBuffer params) {
        if (!isCachingEnabled()) return;
        final int unit = ctx().activeTextureUnit.getValue();
        final var texGenState = ctx().textures.getTexGenState(unit);
        final int pos = params.position();
        switch (pname) {
            case GL11.GL_TEXTURE_GEN_MODE -> {
                texGenState.setMode(coord, (int) params.get(pos));
                ctx().texGenGeneration++;
            }
            case GL11.GL_OBJECT_PLANE -> {
                texGenTempPlane[0] = params.get(pos);
                texGenTempPlane[1] = params.get(pos + 1);
                texGenTempPlane[2] = params.get(pos + 2);
                texGenTempPlane[3] = params.get(pos + 3);
                texGenState.setObjectPlane(coord, texGenTempPlane);
                ctx().texGenGeneration++;
            }
            case GL11.GL_EYE_PLANE -> {
                texGenTempPlane[0] = params.get(pos);
                texGenTempPlane[1] = params.get(pos + 1);
                texGenTempPlane[2] = params.get(pos + 2);
                texGenTempPlane[3] = params.get(pos + 3);
                texGenState.setEyePlane(coord, texGenTempPlane, ctx().modelViewMatrix);
                ctx().texGenGeneration++;
            }
        }
    }

    public static void glTexGen(int coord, int pname, DoubleBuffer params) {
        if (!isCachingEnabled()) return;
        final int pos = params.position();
        texGenTempPlane[0] = (float) params.get(pos);
        texGenTempPlane[1] = (float) params.get(pos + 1);
        texGenTempPlane[2] = (float) params.get(pos + 2);
        texGenTempPlane[3] = (float) params.get(pos + 3);
        final int unit = ctx().activeTextureUnit.getValue();
        final var texGenState = ctx().textures.getTexGenState(unit);
        switch (pname) {
            case GL11.GL_TEXTURE_GEN_MODE -> {
                texGenState.setMode(coord, (int) params.get(pos));
                ctx().texGenGeneration++;
            }
            case GL11.GL_OBJECT_PLANE -> {
                texGenState.setObjectPlane(coord, texGenTempPlane);
                ctx().texGenGeneration++;
            }
            case GL11.GL_EYE_PLANE -> {
                texGenState.setEyePlane(coord, texGenTempPlane, ctx().modelViewMatrix);
                ctx().texGenGeneration++;
            }
        }
    }

    public static void glTexGen(int coord, int pname, IntBuffer params) {
        if (!isCachingEnabled()) return;
        if (pname == GL11.GL_TEXTURE_GEN_MODE) {
            final int unit = ctx().activeTextureUnit.getValue();
            ctx().textures.getTexGenState(unit).setMode(coord, params.get(params.position()));
            ctx().texGenGeneration++;
        }
    }

    public static int glGetTexGeni(int coord, int pname) {
        if (pname == GL11.GL_TEXTURE_GEN_MODE) {
            return ctx().textures.getTexGenState(ctx().activeTextureUnit.getValue()).getMode(coord);
        }
        return 0;
    }

    public static float glGetTexGenf(int coord, int pname) {
        return (float) glGetTexGeni(coord, pname);
    }

    public static double glGetTexGend(int coord, int pname) {
        return glGetTexGeni(coord, pname);
    }

    public static void glGetTexGen(int coord, int pname, IntBuffer params) {
        if (pname == GL11.GL_TEXTURE_GEN_MODE) {
            params.put(params.position(), glGetTexGeni(coord, pname));
        }
    }

    public static void glGetTexGen(int coord, int pname, FloatBuffer params) {
        final var state = ctx().textures.getTexGenState(ctx().activeTextureUnit.getValue());
        if (pname == GL11.GL_TEXTURE_GEN_MODE) {
            params.put(params.position(), (float) state.getMode(coord));
        } else if (pname == GL11.GL_OBJECT_PLANE) {
            final float[] plane = state.getObjectPlane(coord);
            params.put(params.position(), plane[0]);
            params.put(params.position() + 1, plane[1]);
            params.put(params.position() + 2, plane[2]);
            params.put(params.position() + 3, plane[3]);
        } else if (pname == GL11.GL_EYE_PLANE) {
            final float[] plane = state.getEyePlane(coord);
            params.put(params.position(), plane[0]);
            params.put(params.position() + 1, plane[1]);
            params.put(params.position() + 2, plane[2]);
            params.put(params.position() + 3, plane[3]);
        }
    }

    public static void glGetTexGen(int coord, int pname, DoubleBuffer params) {
        final var state = ctx().textures.getTexGenState(ctx().activeTextureUnit.getValue());
        if (pname == GL11.GL_TEXTURE_GEN_MODE) {
            params.put(params.position(), state.getMode(coord));
        } else if (pname == GL11.GL_OBJECT_PLANE) {
            final float[] plane = state.getObjectPlane(coord);
            params.put(params.position(), plane[0]);
            params.put(params.position() + 1, plane[1]);
            params.put(params.position() + 2, plane[2]);
            params.put(params.position() + 3, plane[3]);
        } else if (pname == GL11.GL_EYE_PLANE) {
            final float[] plane = state.getEyePlane(coord);
            params.put(params.position(), plane[0]);
            params.put(params.position() + 1, plane[1]);
            params.put(params.position() + 2, plane[2]);
            params.put(params.position() + 3, plane[3]);
        }
    }

    public static void glPolygonStipple(ByteBuffer pattern) {
        guardUnsupportedFFP("glPolygonStipple", "glPolygonStipple is not available in GL 3.3 core profile.");
    }

    public static void glAccum(int op, float value) {
        guardUnsupportedFFP("glAccum", "The accumulation buffer is not available in GL 3.3 core profile.");
    }

    public static void glFeedbackBuffer(int type, FloatBuffer buffer) {
        FeedbackManager.glFeedbackBuffer(type, buffer);
    }

    public static int glRenderMode(int mode) { return GLSMDraws.glRenderMode(mode); }

    public static void glPassThrough(float token) {
        FeedbackManager.glPassThrough(token);
    }

    private static boolean selectWarned = false;

    public static void glSelectBuffer(IntBuffer buffer) {
        if (!selectWarned) {
            LOGGER.warn("glSelectBuffer: selection mode not emulated");
            selectWarned = true;
        }
    }

    public static void glInitNames() { /* no-op: ignored outside GL_SELECT */ }

    public static void glPushName(int name) { /* no-op */ }

    public static void glPopName() { /* no-op */ }

    public static void glLoadName(int name) { /* no-op */ }

    public static void glLinkProgram(int program) {
        if (ShaderManager.getInstance().isEnabled()) {
            generateVertexShaderIfNeeded(program);
        }
        long linkEpoch = CompatUniformManager.beforeLinkProgram(program);
        RENDER_BACKEND.linkProgram(program);
        CompatUniformManager.onLinkProgram(program, linkEpoch);
    }

    private static final IntBuffer SHADER_BUF = BufferUtils.createIntBuffer(8);

    /**
     * If a program has a fragment shader but no vertex shader, generate a passthrough vertex shader from the fragment's in declarations and attach it.
     */
    private static void generateVertexShaderIfNeeded(int program) {
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

    private static long programLifetimeGeneration;

    public static void glDeleteProgram(int program) {
        if (program == 0) return;
        CompatUniformManager.onDeleteProgram(program);

        // GL defers deletion of a bound program until unbind; the program cache entry stays set so the
        // cache matches GL_CURRENT_PROGRAM and the next glUseProgram is not skipped as redundant.
        if (GLSMHooks.PROGRAM_DELETE.hasListeners()) {
            GLSMHooks.programDeleteEvent.program = program;
            GLSMHooks.PROGRAM_DELETE.post(GLSMHooks.programDeleteEvent);
        }
        RENDER_BACKEND.deleteProgram(program);
        programLifetimeGeneration++;
    }

    public static int glCreateShader(int type) {
        return RENDER_BACKEND.createShader(type);
    }

    public static void glCompileShader(int shader) {
        RENDER_BACKEND.compileShader(shader);
    }

    public static int glCreateProgram() {
        final int program = RENDER_BACKEND.createProgram();
        programLifetimeGeneration++;
        return program;
    }

    public static void glAttachShader(int program, int shader) {
        RENDER_BACKEND.attachShader(program, shader);
    }

    public static void glDetachShader(int program, int shader) {
        RENDER_BACKEND.detachShader(program, shader);
    }

    public static void glDeleteShader(int shader) {
        RENDER_BACKEND.deleteShader(shader);
    }

    public static void glBindAttribLocation(int program, int index, CharSequence name) {
        RENDER_BACKEND.bindAttribLocation(program, index, name);
    }

    public static void glValidateProgram(int program) {
        RENDER_BACKEND.validateProgram(program);
    }

    public static int glGetShaderi(int shader, int pname) {
        return RENDER_BACKEND.getShaderi(shader, pname);
    }

    public static String glGetShaderInfoLog(int shader, int maxLength) {
        return RENDER_BACKEND.getShaderInfoLog(shader, maxLength);
    }

    public static String glGetShaderInfoLog(int shader) {
        return RENDER_BACKEND.getShaderInfoLog(shader);
    }

    public static void glGetShaderInfoLog(int shader, IntBuffer length, ByteBuffer infoLog) {
        RENDER_BACKEND.getShaderInfoLog(shader, length, infoLog);
    }

    public static void glGetShaderInfoLog(int shader, int[] length, ByteBuffer infoLog) {
        RENDER_BACKEND.getShaderInfoLog(shader, length, infoLog);
    }

    public static int glGetProgrami(int program, int pname) {
        return RENDER_BACKEND.getProgrami(program, pname);
    }

    public static String glGetProgramInfoLog(int program, int maxLength) {
        return RENDER_BACKEND.getProgramInfoLog(program, maxLength);
    }

    public static String glGetProgramInfoLog(int program) {
        return RENDER_BACKEND.getProgramInfoLog(program);
    }

    public static void glGetProgramInfoLog(int program, IntBuffer length, ByteBuffer infoLog) {
        RENDER_BACKEND.getProgramInfoLog(program, length, infoLog);
    }

    public static void glGetProgramInfoLog(int program, int[] length, ByteBuffer infoLog) {
        RENDER_BACKEND.getProgramInfoLog(program, length, infoLog);
    }

    public static void glBindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access, int format) { RENDER_BACKEND.bindImageTexture(unit, texture, level, layered, layer, access, format); }
    public static void glMemoryBarrier(int barriers) { RENDER_BACKEND.memoryBarrier(barriers); }
    public static void glCopyImageSubData(int srcName, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ,
                                           int dstName, int dstTarget, int dstLevel, int dstX, int dstY, int dstZ,
                                           int srcWidth, int srcHeight, int srcDepth) {
        recordGpuCommand(GpuCommandType.COPY_IMAGE_SUB_DATA, GpuCommandPhase.BEGIN, srcName, dstName);
        RENDER_BACKEND.copyImageSubData(srcName, srcTarget, srcLevel, srcX, srcY, srcZ,
            dstName, dstTarget, dstLevel, dstX, dstY, dstZ, srcWidth, srcHeight, srcDepth);
        recordGpuCommand(GpuCommandType.COPY_IMAGE_SUB_DATA, GpuCommandPhase.END, srcName, dstName);
        gpuCheckpoint(GpuCommandType.COPY_IMAGE_SUB_DATA);
    }

    public static void glDispatchCompute(int x, int y, int z) {
        recordGpuCommand(GpuCommandType.DISPATCH_COMPUTE, x, y << 16 | z & 0xFFFF);
        RENDER_BACKEND.dispatchCompute(x, y, z);
    }

    public static int glGetUniformLocation(int program, CharSequence name) {
        int loc = RENDER_BACKEND.getUniformLocation(program, name);
        if (loc == -1 && TEXTURE.contentEquals(name)) {
            loc = RENDER_BACKEND.getUniformLocation(program, TEXTURE_RENAMED);
        }
        return loc;
    }

    public static int glGetUniformLocation(int program, ByteBuffer name) {
        int loc = RENDER_BACKEND.getUniformLocation(program, name);
        if (loc == -1 && isTextureBuffer(name)) {
            loc = RENDER_BACKEND.getUniformLocation(program, TEXTURE_RENAMED);
        }
        return loc;
    }

    public static int glGetAttribLocation(int program, CharSequence name) {
        int loc = RENDER_BACKEND.getAttribLocation(program, name);
        if (loc == -1 && TEXTURE.contentEquals(name)) {
            loc = RENDER_BACKEND.getAttribLocation(program, TEXTURE_RENAMED);
        }
        return loc;
    }

    public static int glGetAttribLocation(int program, ByteBuffer name) {
        int loc = RENDER_BACKEND.getAttribLocation(program, name);
        if (loc == -1 && isTextureBuffer(name)) {
            loc = RENDER_BACKEND.getAttribLocation(program, TEXTURE_RENAMED);
        }
        return loc;
    }

    private static final ByteBuffer TEXTURE_BYTES = ByteBuffer.wrap(TEXTURE.getBytes(StandardCharsets.US_ASCII));

    private static boolean isTextureBuffer(ByteBuffer buf) {
        final int len = buf.remaining();
        if (len != 7 && len != 8) return false;
        // I believe the driver accepts null terminated or not
        if (len == 8 && buf.get(buf.position() + 7) != 0) return false;
        return buf.slice(buf.position(), 7).equals(TEXTURE_BYTES);
    }

    public static void glDeleteObjectARB(int obj) {
        if (RENDER_BACKEND.isShader(obj)) {
            RENDER_BACKEND.deleteShader(obj);
        } else {
            glDeleteProgram(obj);
        }
    }

    public static int glGetHandleARB(int pname) {
        return pname == ARBShaderObjects.GL_PROGRAM_OBJECT_ARB ? glGetInteger(GL20.GL_CURRENT_PROGRAM) : RENDER_BACKEND.getInteger(pname);
    }

    public static void glGetObjectParameterARB(int obj, int pname, IntBuffer params) {
        final var backend = RENDER_BACKEND;
        if (backend.isShader(obj)) {
            params.put(params.position(), backend.getShaderi(obj, pname));
        } else {
            backend.getProgramiv(obj, pname, params);
        }
    }

    public static void glGetObjectParameterARB(int obj, int pname, FloatBuffer params) {
        final var backend = RENDER_BACKEND;
        params.put(params.position(), (float) (backend.isShader(obj) ? backend.getShaderi(obj, pname) : backend.getProgrami(obj, pname)));
    }

    public static int glGetObjectParameteriARB(int obj, int pname) {
        final var backend = RENDER_BACKEND;
        return backend.isShader(obj) ? backend.getShaderi(obj, pname) : backend.getProgrami(obj, pname);
    }

    public static String glGetInfoLogARB(int obj, int maxLength) {
        final var backend = RENDER_BACKEND;
        return backend.isShader(obj) ? backend.getShaderInfoLog(obj, maxLength) : backend.getProgramInfoLog(obj, maxLength);
    }

    public static void glGetInfoLogARB(int obj, IntBuffer length, ByteBuffer infoLog) {
        final var backend = RENDER_BACKEND;
        if (backend.isShader(obj)) {
            backend.getShaderInfoLog(obj, length, infoLog);
        } else {
            backend.getProgramInfoLog(obj, length, infoLog);
        }
    }

    public static void glUniform1f(int location, float v0) {
        RENDER_BACKEND.uniform1f(location, v0);
    }

    public static void glUniform2f(int location, float v0, float v1) {
        RENDER_BACKEND.uniform2f(location, v0, v1);
    }

    public static void glUniform3f(int location, float v0, float v1, float v2) {
        RENDER_BACKEND.uniform3f(location, v0, v1, v2);
    }

    public static void glUniform4f(int location, float v0, float v1, float v2, float v3) {
        RENDER_BACKEND.uniform4f(location, v0, v1, v2, v3);
    }

    public static void glUniform1i(int location, int v0) {
        RENDER_BACKEND.uniform1i(location, v0);
    }

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

    public static void glUniform2i(int location, int v0, int v1) {
        RENDER_BACKEND.uniform2i(location, v0, v1);
    }

    public static void glUniform3i(int location, int v0, int v1, int v2) {
        RENDER_BACKEND.uniform3i(location, v0, v1, v2);
    }

    public static void glUniform4i(int location, int v0, int v1, int v2, int v3) {
        RENDER_BACKEND.uniform4i(location, v0, v1, v2, v3);
    }

    public static void glUniform1(int location, FloatBuffer values) {
        RENDER_BACKEND.uniform1fv(location, values);
    }

    public static void glUniform1(int location, IntBuffer values) {
        RENDER_BACKEND.uniform1iv(location, values);
    }

    public static void glUniform2(int location, FloatBuffer values) {
        RENDER_BACKEND.uniform2(location, values);
    }

    public static void glUniform2(int location, IntBuffer values) {
        RENDER_BACKEND.uniform2iv(location, values);
    }

    public static void glUniform3(int location, FloatBuffer values) {
        RENDER_BACKEND.uniform3(location, values);
    }

    public static void glUniform3(int location, IntBuffer values) {
        RENDER_BACKEND.uniform3iv(location, values);
    }

    public static void glUniform4(int location, FloatBuffer values) {
        RENDER_BACKEND.uniform4(location, values);
    }

    public static void glUniform4(int location, IntBuffer values) {
        RENDER_BACKEND.uniform4iv(location, values);
    }

    public static void glUniform1(int location, float[] values) {
        RENDER_BACKEND.uniform1fv(location, values);
    }

    public static void glUniform3(int location, float[] values) {
        RENDER_BACKEND.uniform3fv(location, values);
    }

    public static void glUniform4(int location, float[] values) {
        RENDER_BACKEND.uniform4fv(location, values);
    }

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

    public static void glUniformMatrix2(int location, boolean transpose, FloatBuffer matrices) {
        RENDER_BACKEND.uniformMatrix2(location, transpose, matrices);
    }

    public static void glUniformMatrix3(int location, boolean transpose, FloatBuffer matrices) {
        RENDER_BACKEND.uniformMatrix3(location, transpose, matrices);
    }

    public static void glUniformMatrix4(int location, boolean transpose, FloatBuffer matrices) {
        RENDER_BACKEND.uniformMatrix4(location, transpose, matrices);
    }

    public static void glUniformMatrix3(int location, boolean transpose, float[] matrices) {
        RENDER_BACKEND.uniformMatrix3(location, transpose, matrices);
    }

    public static void glUniformMatrix4(int location, boolean transpose, float[] matrices) {
        RENDER_BACKEND.uniformMatrix4(location, transpose, matrices);
    }

    public static void glGetActiveUniform(int program, int index, IntBuffer length, IntBuffer size, IntBuffer type, ByteBuffer name) {
        RENDER_BACKEND.getActiveUniform(program, index, length, size, type, name);
    }

    public static void glGetActiveUniform(int program, int index, int[] length, int[] size, int[] type, ByteBuffer name) {
        RENDER_BACKEND.getActiveUniform(program, index, length, size, type, name);
    }

    public static String glGetActiveUniform(int program, int index, int maxLength) {
        // Legacy convenience form returning the active uniform name. Third-party mods
        // (e.g. HammerLib) call GL20.glGetActiveUniform(program, index, maxLength) ->
        // String, which the GL redirector rewrites to this class, so the same signature
        // must exist here or the call fails with NoSuchMethodError during mod init.
        IntBuffer sizeType = BufferUtils.createIntBuffer(2);
        return RENDER_BACKEND.getActiveUniform(program, index, maxLength, sizeType);
    }

    public static void glGetAttachedShaders(int program, IntBuffer count, IntBuffer shaders) {
        RENDER_BACKEND.getAttachedShaders(program, count, shaders);
    }

    public static void glGetAttachedShaders(int program, int[] count, int[] shaders) {
        RENDER_BACKEND.getAttachedShaders(program, count, shaders);
    }

    public static String glGetShaderSource(int shader, int maxLength) {
        return RENDER_BACKEND.getShaderSource(shader, maxLength);
    }

    public static String glGetShaderSource(int shader) {
        return glGetShaderSource(shader, glGetShaderi(shader, GL20.GL_SHADER_SOURCE_LENGTH));
    }

    public static void glGetShaderSource(int shader, IntBuffer length, ByteBuffer source) {
        RENDER_BACKEND.getShaderSource(shader, length, source);
    }

    public static void glGetShaderSource(int shader, int[] length, ByteBuffer source) {
        RENDER_BACKEND.getShaderSource(shader, length, source);
    }

    public static void glGetUniform(int program, int location, FloatBuffer params) {
        RENDER_BACKEND.getUniformfv(program, location, params);
    }

    public static void glGetUniform(int program, int location, IntBuffer params) {
        RENDER_BACKEND.getUniformiv(program, location, params);
    }

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

    public static void glPushAttrib() {
        glPushAttrib(GL11.GL_ENABLE_BIT);
    }

    public static void blendFunc(GlStateManager.SourceFactor srcFactor,
                                 GlStateManager.DestFactor dstFactor) {
        glBlendFunc(srcFactor.factor, dstFactor.factor);
    }

    public static void glBlendFunc(GlStateManager.SourceFactor srcFactor,
                                   GlStateManager.DestFactor dstFactor) {
        glBlendFunc(srcFactor.factor, dstFactor.factor);
    }

    public static void tryBlendFuncSeparate(GlStateManager.SourceFactor srcFactor,
                                            GlStateManager.DestFactor dstFactor,
                                            GlStateManager.SourceFactor srcFactorAlpha,
                                            GlStateManager.DestFactor dstFactorAlpha) {
        tryBlendFuncSeparate(srcFactor.factor, dstFactor.factor, srcFactorAlpha.factor, dstFactorAlpha.factor);
    }

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

    public static void glCullFace(GlStateManager.CullFace cullFace) {
        glCullFace(cullFace.mode);
    }

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

    public static void glLogicOp(GlStateManager.LogicOp logicOperation) {
        glLogicOp(logicOperation.opcode);
    }

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

    public static void glScalef(double x, double y, double z) {
        glScaled(x, y, z);
    }

    public static void glTranslatef(double x, double y, double z) {
        glTranslated(x, y, z);
    }

    /**
     * Double-precision angle form of {@link #glRotatef(float, float, float, float)}.
     *
     * <p>The GLSM redirector rewrites vanilla {@code GlStateManager.rotate} calls to this class by
     * method name while preserving the caller's descriptor. HBM's Nuclear Tech (Community Edition)
     * calls the {@code rotate(double, float, float, float)} overload from its tile entity item
     * renderers (issue #64); without this overload the rewritten call resolved to nothing and the
     * game crashed with {@code NoSuchMethodError}.</p>
     */
    public static void glRotatef(double angle, float x, float y, float z) {
        glRotatef((float) angle, x, y, z);
    }

    public static void glRotatef(org.lwjgl.util.vector.Quaternion quaternion) {
        FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
        float x = quaternion.x;
        float y = quaternion.y;
        float z = quaternion.z;
        float w = quaternion.w;
        buffer.put(1.0F - 2.0F * y * y - 2.0F * z * z);
        buffer.put(2.0F * x * y + 2.0F * w * z);
        buffer.put(2.0F * x * z - 2.0F * w * y);
        buffer.put(0.0F);
        buffer.put(2.0F * x * y - 2.0F * w * z);
        buffer.put(1.0F - 2.0F * x * x - 2.0F * z * z);
        buffer.put(2.0F * y * z + 2.0F * w * x);
        buffer.put(0.0F);
        buffer.put(2.0F * x * z + 2.0F * w * y);
        buffer.put(2.0F * y * z - 2.0F * w * x);
        buffer.put(1.0F - 2.0F * x * x - 2.0F * y * y);
        buffer.put(0.0F);
        buffer.put(0.0F);
        buffer.put(0.0F);
        buffer.put(0.0F);
        buffer.put(1.0F);
        buffer.flip();
        glMultMatrix(buffer);
    }

    public static void glColor4f(float red, float green, float blue) { GLSMCurrentAttribs.glColor4f(red, green, blue); }

    public static void glTexCoordPointer(int size, int type, int stride, int pointer) { GLSMVertexInput.glTexCoordPointer(size, type, stride, pointer); }

    public static void glVertexPointer(int size, int type, int stride, int pointer) { GLSMVertexInput.glVertexPointer(size, type, stride, pointer); }

    public static void glColorPointer(int size, int type, int stride, int pointer) {
        glColorPointer(size, type, stride, (long) pointer);
    }

    public static void enableBlendProfile(GlStateManager.Profile profile) {
        profile.apply();
    }

    public static void disableBlendProfile(GlStateManager.Profile profile) {
        profile.clean();
    }

    /**
     * Reads a texture into the bound pixel-pack buffer at a byte offset. Unlike the client-buffer
     * overloads, this must preserve the PBO binding so that offset zero is not a null CPU pointer.
     */
    public static void glGetTexImage(int target, int level, int format, int type, long pixelBufferOffset) {
        RENDER_BACKEND.getTexImage(target, level, format, type, pixelBufferOffset);
    }

    /**
     * Re-apply every cached GL state value to the driver. Used after a display-context
     * migration (the new context starts with driver defaults) and available to
     * {@link RenderBackend#onFrameBegin()} for consuming the splash state seed.
     */
    public static void replayStateToBackend() {
        final GLContextState glCtx = ctx();

        final List<BooleanStateStack> booleanStates = glCtx.allBooleanStates;
        for (int i = 0; i < booleanStates.size(); i++) {
            booleanStates.get(i).applyToBackend();
        }

        RENDER_BACKEND.bindVertexArray(glCtx.boundVAO != 0 ? glCtx.boundVAO : glCtx.defaultVAO);
        RENDER_BACKEND.bindBuffer(GL15.GL_ARRAY_BUFFER, glCtx.boundVBO);
        RENDER_BACKEND.useProgram(glCtx.activeProgram);
        glCtx.ffp.invalidateProgram();

        RENDER_BACKEND.depthFunc(glCtx.depthState.getFunc());
        RENDER_BACKEND.depthMask(glCtx.depthState.isMaskEnabled());
        RENDER_BACKEND.clearDepth(glCtx.depthState.getClearValue());

        issueBlendFunc(glCtx.blendState);
        RENDER_BACKEND.blendEquationSeparate(glCtx.blendState.getEquationRgb(), glCtx.blendState.getEquationAlpha());
        RENDER_BACKEND.blendColor(glCtx.blendState.getBlendColorR(), glCtx.blendState.getBlendColorG(), glCtx.blendState.getBlendColorB(), glCtx.blendState.getBlendColorA());
        RENDER_BACKEND.colorMask(glCtx.colorMask.red, glCtx.colorMask.green, glCtx.colorMask.blue, glCtx.colorMask.alpha);
        RENDER_BACKEND.clearColor(glCtx.clearColor.getRed(), glCtx.clearColor.getGreen(), glCtx.clearColor.getBlue(), glCtx.clearColor.getAlpha());
        RENDER_BACKEND.logicOp(glCtx.logicOpMode.getValue());

        RENDER_BACKEND.stencilFuncSeparate(GL11.GL_FRONT, glCtx.stencilState.getFuncFront(), glCtx.stencilState.getRefFront(), glCtx.stencilState.getValueMaskFront());
        RENDER_BACKEND.stencilFuncSeparate(GL11.GL_BACK, glCtx.stencilState.getFuncBack(), glCtx.stencilState.getRefBack(), glCtx.stencilState.getValueMaskBack());
        RENDER_BACKEND.stencilOpSeparate(GL11.GL_FRONT, glCtx.stencilState.getFailOpFront(), glCtx.stencilState.getZFailOpFront(), glCtx.stencilState.getZPassOpFront());
        RENDER_BACKEND.stencilOpSeparate(GL11.GL_BACK, glCtx.stencilState.getFailOpBack(), glCtx.stencilState.getZFailOpBack(), glCtx.stencilState.getZPassOpBack());
        RENDER_BACKEND.stencilMaskSeparate(GL11.GL_FRONT, glCtx.stencilState.getWriteMaskFront());
        RENDER_BACKEND.stencilMaskSeparate(GL11.GL_BACK, glCtx.stencilState.getWriteMaskBack());
        RENDER_BACKEND.clearStencil(glCtx.stencilState.getClearValue());

        RENDER_BACKEND.viewport(glCtx.viewportState.x, glCtx.viewportState.y, glCtx.viewportState.width, glCtx.viewportState.height);
        RENDER_BACKEND.depthRange(glCtx.viewportState.depthRangeNear, glCtx.viewportState.depthRangeFar);
        RENDER_BACKEND.scissor(glCtx.scissorState.x, glCtx.scissorState.y, glCtx.scissorState.width, glCtx.scissorState.height);
        RENDER_BACKEND.lineWidth(Math.max(lineWidthMin, Math.min(lineWidthMax, glCtx.lineState.getWidth())));
        applyPointState(glCtx);
        applyHintState(glCtx);

        RENDER_BACKEND.polygonMode(GL11.GL_FRONT_AND_BACK, glCtx.polygonState.getFrontMode());
        applyPolygonOffset(glCtx);
        RENDER_BACKEND.cullFace(glCtx.polygonState.getCullFaceMode());
        RENDER_BACKEND.frontFace(glCtx.polygonState.getFrontFace());
    }

    private static void applyPolygonOffset(GLContextState glCtx) {
        final float clamp = glCtx.polygonState.getOffsetClamp();
        if (clamp == 0.0f) {
            RENDER_BACKEND.polygonOffset(glCtx.polygonState.getOffsetFactor(), glCtx.polygonState.getOffsetUnits());
        } else {
            RENDER_BACKEND.polygonOffsetClamp(glCtx.polygonState.getOffsetFactor(), glCtx.polygonState.getOffsetUnits(), clamp);
        }
    }

    public static boolean isPoppingAttributes() {
        return ctx().poppingAttributes;
    }

    public static int getMvGeneration() {
        return ctx().mvGeneration;
    }

    public static int getProjGeneration() {
        return ctx().projGeneration;
    }

    public static int getTexMatrixGeneration() {
        return ctx().texMatrixGeneration;
    }

    public static int getColorMatrixGeneration() {
        return ctx().colorMatrixGeneration;
    }

    public static int getLightingGeneration() {
        return ctx().lightingGeneration;
    }

    public static int getFragmentGeneration() {
        return ctx().fragmentGeneration;
    }

    public static int getColorGeneration() {
        return ctx().colorGeneration;
    }

    public static int getProgramGeneration() {
        return ctx().programGeneration;
    }

    public static int getDrawFramebufferGeneration() {
        return ctx().drawFramebufferGeneration;
    }

    public static float getOverlayR() {
        return ctx().overlayR;
    }

    public static float getOverlayG() {
        return ctx().overlayG;
    }

    public static float getOverlayB() {
        return ctx().overlayB;
    }

    public static float getOverlayA() {
        return ctx().overlayA;
    }

    public static float getShaderColorR() {
        return ctx().shaderColorR;
    }

    public static float getShaderColorG() {
        return ctx().shaderColorG;
    }

    public static float getShaderColorB() {
        return ctx().shaderColorB;
    }

    public static float getShaderColorA() {
        return ctx().shaderColorA;
    }

    public static int getClipPlaneGeneration() {
        return ctx().clipPlaneGeneration;
    }

    public static int getTexGenGeneration() {
        return ctx().texGenGeneration;
    }

    public static int getAttribDepth() {
        return ctx().attribDepth;
    }

    public static int getMaxBoundTextureUnit() {
        return ctx().maxBoundTextureUnit;
    }

    public static IntegerStateStack getActiveTextureUnitStack() {
        return ctx().activeTextureUnit;
    }

    public static IntegerStateStack getProgramStack() {
        return ctx().programStack;
    }

    public static IntegerStateStack getShadeModelState() {
        return ctx().shadeModelState;
    }

    public static TextureUnitArray getTextures() {
        return ctx().textures;
    }

    public static ImageUnitArray getImageUnits() {
        return ctx().imageUnits;
    }

    public static ImageUnitBinding getImageUnitBinding(int unit) {
        return ctx().imageUnits.get(unit);
    }

    private static int imageBindingOrBackend(int pname, int index) {
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

    public static int glGetInteger(int pname, int index) {
        return imageBindingOrBackend(pname, index);
    }

    public static int glGetIntegeri(int pname, int index) {
        return imageBindingOrBackend(pname, index);
    }

    public static void glGetIntegeri(int pname, int index, IntBuffer params) {
        glGetIntegeri_v(pname, index, params);
    }

    public static void glGetIntegeri_v(int pname, int index, IntBuffer params) {
        params.put(params.position(), imageBindingOrBackend(pname, index));
    }

    public static void glGetIntegeri_v(int pname, int index, int[] params) {
        params[0] = imageBindingOrBackend(pname, index);
    }

    public static boolean glGetBooleani(int pname, int index) {
        return imageBindingOrBackend(pname, index) != 0;
    }

    public static void glGetBooleani_v(int pname, int index, ByteBuffer params) {
        params.put(params.position(), (byte) (imageBindingOrBackend(pname, index) != 0 ? GL11.GL_TRUE : GL11.GL_FALSE));
    }

    public static void glGetBooleani(int pname, int index, ByteBuffer params) {
        glGetBooleani_v(pname, index, params);
    }

    public static void glGetBoolean(int pname, int index, ByteBuffer params) {
        glGetBooleani_v(pname, index, params);
    }

    public static BlendStateStack getBlendState() {
        return ctx().blendState;
    }

    public static BooleanStateStack getBlendMode() {
        return ctx().blendMode;
    }

    public static BooleanStateStack getScissorTest() {
        return ctx().scissorTest;
    }

    public static ScissorStateStack getScissorState() {
        return ctx().scissorState;
    }

    public static DepthStateStack getDepthState() {
        return ctx().depthState;
    }

    public static BooleanStateStack getDepthTest() {
        return ctx().depthTest;
    }

    public static FogStateStack getFogState() {
        return ctx().fogState;
    }

    public static BooleanStateStack getFogMode() {
        return ctx().fogMode;
    }

    public static Color4Stack getColor() {
        return ctx().color;
    }

    public static Color4Stack getSecondaryColor() {
        return ctx().secondaryColor;
    }

    public static BooleanStateStack getColorSumState() {
        return ctx().colorSumState;
    }

    public static Color4Stack getClearColor() {
        return ctx().clearColor;
    }

    public static ColorMaskStack getColorMask() {
        return ctx().colorMask;
    }

    public static IntegerStateStack getDrawBuffer() {
        return ctx().drawBuffer;
    }

    public static IntegerStateStack getLogicOpMode() {
        return ctx().logicOpMode;
    }

    public static BooleanStateStack getCullState() {
        return ctx().cullState;
    }

    public static AlphaStateStack getAlphaState() {
        return ctx().alphaState;
    }

    public static BooleanStateStack getAlphaTest() {
        return ctx().alphaTest;
    }

    public static BooleanStateStack getLightingState() {
        return ctx().lightingState;
    }

    public static BooleanStateStack getRescaleNormalState() {
        return ctx().rescaleNormalState;
    }

    public static BooleanStateStack getNormalizeState() {
        return ctx().normalizeState;
    }

    public static BooleanStateStack getDitherState() {
        return ctx().ditherState;
    }

    public static BooleanStateStack getStencilTest() {
        return ctx().stencilTest;
    }

    public static BooleanStateStack getLineSmoothState() {
        return ctx().lineSmoothState;
    }

    public static BooleanStateStack getLineStippleState() {
        return ctx().lineStippleState;
    }

    public static BooleanStateStack getPointSmoothState() {
        return ctx().pointSmoothState;
    }

    public static BooleanStateStack getPolygonSmoothState() {
        return ctx().polygonSmoothState;
    }

    public static BooleanStateStack getPolygonStippleState() {
        return ctx().polygonStippleState;
    }

    public static BooleanStateStack getMultisampleState() {
        return ctx().multisampleState;
    }

    public static BooleanStateStack getSampleAlphaToCoverageState() {
        return ctx().sampleAlphaToCoverageState;
    }

    public static BooleanStateStack getSampleAlphaToOneState() {
        return ctx().sampleAlphaToOneState;
    }

    public static BooleanStateStack getSampleCoverageState() {
        return ctx().sampleCoverageState;
    }

    public static BooleanStateStack getColorLogicOpState() {
        return ctx().colorLogicOpState;
    }

    public static BooleanStateStack getIndexLogicOpState() {
        return ctx().indexLogicOpState;
    }

    public static BooleanStateStack getPolygonOffsetPointState() {
        return ctx().polygonOffsetPointState;
    }

    public static BooleanStateStack getPolygonOffsetLineState() {
        return ctx().polygonOffsetLineState;
    }

    public static BooleanStateStack getPolygonOffsetFillState() {
        return ctx().polygonOffsetFillState;
    }

    public static LineStateStack getLineState() {
        return ctx().lineState;
    }

    public static PointStateStack getPointState() {
        return ctx().pointState;
    }

    public static IntegerStateStack getHintState() {
        return ctx().hintState;
    }

    public static BooleanStateStack getPointSpriteState() {
        return ctx().pointSpriteState;
    }

    public static BooleanStateStack getVertexProgramTwoSideState() {
        return ctx().vertexProgramTwoSideState;
    }

    public static BooleanStateStack getEdgeFlagState() {
        return ctx().edgeFlagState;
    }

    public static PolygonStateStack getPolygonState() {
        return ctx().polygonState;
    }

    public static StencilStateStack getStencilState() {
        return ctx().stencilState;
    }

    public static BooleanStateStack getAutoNormalState() {
        return ctx().autoNormalState;
    }

    public static BooleanStateStack getMap1Color4State() {
        return ctx().map1Color4State;
    }

    public static BooleanStateStack getMap1IndexState() {
        return ctx().map1IndexState;
    }

    public static BooleanStateStack getMap1NormalState() {
        return ctx().map1NormalState;
    }

    public static BooleanStateStack getMap1TextureCoord1State() {
        return ctx().map1TextureCoord1State;
    }

    public static BooleanStateStack getMap1TextureCoord2State() {
        return ctx().map1TextureCoord2State;
    }

    public static BooleanStateStack getMap1TextureCoord3State() {
        return ctx().map1TextureCoord3State;
    }

    public static BooleanStateStack getMap1TextureCoord4State() {
        return ctx().map1TextureCoord4State;
    }

    public static BooleanStateStack getMap1Vertex3State() {
        return ctx().map1Vertex3State;
    }

    public static BooleanStateStack getMap1Vertex4State() {
        return ctx().map1Vertex4State;
    }

    public static BooleanStateStack getMap2Color4State() {
        return ctx().map2Color4State;
    }

    public static BooleanStateStack getMap2IndexState() {
        return ctx().map2IndexState;
    }

    public static BooleanStateStack getMap2NormalState() {
        return ctx().map2NormalState;
    }

    public static BooleanStateStack getMap2TextureCoord1State() {
        return ctx().map2TextureCoord1State;
    }

    public static BooleanStateStack getMap2TextureCoord2State() {
        return ctx().map2TextureCoord2State;
    }

    public static BooleanStateStack getMap2TextureCoord3State() {
        return ctx().map2TextureCoord3State;
    }

    public static BooleanStateStack getMap2TextureCoord4State() {
        return ctx().map2TextureCoord4State;
    }

    public static BooleanStateStack getMap2Vertex3State() {
        return ctx().map2Vertex3State;
    }

    public static BooleanStateStack getMap2Vertex4State() {
        return ctx().map2Vertex4State;
    }

    public static BooleanStateStack[] getClipPlaneStates() {
        return ctx().clipPlaneStates;
    }

    public static ClipPlaneState getClipPlaneState() {
        return ctx().clipPlaneState;
    }

    public static MatrixModeStack getMatrixMode() {
        return ctx().matrixMode;
    }

    public static Matrix4fStack getModelViewMatrix() {
        return ctx().modelViewMatrix;
    }

    public static Matrix4fStack getProjectionMatrix() {
        return ctx().projectionMatrix;
    }

    public static Matrix4fStack getColorMatrix() {
        return ctx().colorMatrix;
    }

    public static BooleanStateStack[] getLightStates() {
        return ctx().lightStates;
    }

    public static LightStateStack[] getLightDataStates() {
        return ctx().lightDataStates;
    }

    public static BooleanStateStack getColorMaterial() {
        return ctx().colorMaterial;
    }

    public static IntegerStateStack getColorMaterialFace() {
        return ctx().colorMaterialFace;
    }

    public static IntegerStateStack getColorMaterialParameter() {
        return ctx().colorMaterialParameter;
    }

    public static LightModelStateStack getLightModel() {
        return ctx().lightModel;
    }

    public static MaterialStateStack getFrontMaterial() {
        return ctx().frontMaterial;
    }

    public static MaterialStateStack getBackMaterial() {
        return ctx().backMaterial;
    }

    public static ViewPortStateStack getViewportState() {
        return ctx().viewportState;
    }

    public static int getActiveProgram() {
        return ctx().activeProgram;
    }

    public static int getListBase() {
        return ctx().listBase;
    }

    public static int getBoundVBO() {
        return ctx().boundVBO;
    }

    public static int getBoundEBO() {
        return ctx().vaos.boundEBO;
    }

    public static int getBoundVAO() {
        return ctx().boundVAO;
    }

    public static int getDrawFramebuffer() {
        return ctx().drawFramebuffer;
    }

    public static int getReadFramebuffer() {
        return ctx().readFramebuffer;
    }

    public static PixelStoreState getPixelUnpackState() {
        return ctx().pixelUnpackState;
    }
}
