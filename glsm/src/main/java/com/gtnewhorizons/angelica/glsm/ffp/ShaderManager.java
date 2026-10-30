package com.gtnewhorizons.angelica.glsm.ffp;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormat;
import com.gtnewhorizons.angelica.glsm.CompatUniformManager;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.QuadConverter;
import com.gtnewhorizons.angelica.glsm.debug.GLSMDebug;
import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;
import com.gtnewhorizons.angelica.glsm.hooks.DeferredBlendHandler;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.stacks.Vec3fStack;
import com.gtnewhorizons.angelica.glsm.stacks.Vec4fStack;
import com.gtnewhorizons.angelica.glsm.states.VertexAttribState;
import com.gtnewhorizons.angelica.glsm.streaming.TessellatorStreamingDrawer;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMInitConfig;
import lombok.Getter;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.file.Paths;
import java.util.Arrays;

import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

/**
 * FFP shader manager
 */
public class ShaderManager {

    private static final class Holder {

        static final ShaderManager INSTANCE = new ShaderManager();
    }

    private static final boolean DEBUG_DRAW_LOGS = Boolean.getBoolean("actinium.glsm.verboseDrawLogs");

    private final ShaderCache cache = new ShaderCache();
    private final Uniforms uniforms = new Uniforms();
    private final Int2IntOpenHashMap vaoVertexFlags = new Int2IntOpenHashMap();

    @Getter
    private boolean active = false;
    private Program currentProgram = null;
    private long currentVertexKeyPacked = Long.MIN_VALUE;
    private final long[] currentFKScratch = new long[FragmentKey.MAX_UNITS];
    private final long[] currentFKPacked = new long[FragmentKey.MAX_UNITS];
    private int currentFKLen = 0;

    @Getter private static final Vector3f currentNormal = new Vector3f(0.0f, 0.0f, 1.0f);
    private static final Vector4f[] currentTexCoords = {
        new Vector4f(0.0f, 0.0f, 0.0f, 1.0f),
        new Vector4f(0.0f, 0.0f, 0.0f, 1.0f),
        new Vector4f(0.0f, 0.0f, 0.0f, 1.0f),
        new Vector4f(0.0f, 0.0f, 0.0f, 1.0f),
    };
    @Getter private static final Vec3fStack normalStack = new Vec3fStack(currentNormal);
    @Getter private static final Vec4fStack texCoordStack = new Vec4fStack(currentTexCoords[0]);
    @Getter private static int normalGeneration;
    @Getter private static int texCoordGeneration;
    @Getter private boolean enabled = false;
    private int currentVertexFlags = VertexFlags.TEXTURE_BIT | VertexFlags.COLOR_BIT | VertexFlags.NORMAL_BIT;

    private ShaderManager() {
        vaoVertexFlags.defaultReturnValue(-1);
        if (Boolean.parseBoolean(System.getProperty("angelica.dumpShaders", "false"))) {
            cache.setDumpDir(Paths.get("ffp_shaders"));
        }
    }

    public static ShaderManager getInstance() {
        return Holder.INSTANCE;
    }

    public void enable() {
        warmUp();
        enabled = true;

        VertexFormat.registerSetupBufferStateOverride((format, offset) -> {
            currentVertexFlags = format.getVertexFlags();
            vaoVertexFlags.put(GLStateManager.getBoundVAO(), currentVertexFlags);
            return false;
        });

        GLStateManager.LOGGER.info("FFP shader emulation enabled");
    }

    // Force loading of classes before the SplashThread kicks off
    // Ensure it's GL free
    private static void warmUp() {
        try {
            final long[] fkScratch = new long[FragmentKey.MAX_UNITS];
            final int fkLen = FragmentKey.packFromState(fkScratch);
            final int fragMask = FragmentKey.unitMaskFromPacked(fkScratch, fkLen);
            final long vkPacked = VertexKey.packFromState(true, true, true, true, fragMask);
            final VertexKey vk = VertexKey.fromPacked(vkPacked);
            VertexShaderGenerator.generate(vk);
            FragmentShaderGenerator.generate(FragmentKey.fromPacked(fkScratch, fkLen));
            GeometryShaderGenerator.generate(vk);
            final Class<?>[] touched = { Program.class, ShaderCache.class, TessellatorStreamingDrawer.class, QuadConverter.class };
            for (Class<?> c : touched) {
                c.getName();
            }
        } catch (Throwable t) {
            GLStateManager.LOGGER.warn("FFP warmup failed; draw-path classes will resolve lazily", t);
        }
    }

    public void disable() {
        enabled = false;
    }

    public synchronized void activate() {
        if (active) return;
        active = true;
        updateVariant(
            (currentVertexFlags & VertexFlags.COLOR_BIT) != 0,
            (currentVertexFlags & VertexFlags.NORMAL_BIT) != 0,
            (currentVertexFlags & VertexFlags.TEXTURE_BIT) != 0,
            (currentVertexFlags & VertexFlags.BRIGHTNESS_BIT) != 0
        );
        uploadUniforms();
    }

    public synchronized void deactivate() {
        active = false;
    }

    public void preDraw(boolean hasColor, boolean hasNormal, boolean hasTexCoord, boolean hasLightmap) {
        final boolean perfDebugEnabled = GLSMPerfDebug.isEnabled();
        final long perfStart = perfDebugEnabled ? GLSMPerfDebug.begin(GLSMPerfDebug.Stage.FFP_PREDRAW) : 0L;
        GLStateManager.flushDeferredVertexAttribs();
        final DeferredBlendHandler bh = GLSMHooks.blendHandler;
        if (bh != null) bh.flushDeferredBlend();

        if (!active) {
            if (enabled) {
                final int currentProgramId = GLStateManager.getActiveProgram();
                if (currentProgramId != 0) {
                    CompatUniformManager.onUseProgram(currentProgramId);
                    notifyDrawObserver(hasColor, hasNormal, hasTexCoord, hasLightmap);
                    if (perfDebugEnabled) {
                        GLSMPerfDebug.end(GLSMPerfDebug.Stage.FFP_PREDRAW, perfStart);
                    }
                    return;
                }
                active = true;
            } else {
                notifyDrawObserver(hasColor, hasNormal, hasTexCoord, hasLightmap);
                if (perfDebugEnabled) {
                    GLSMPerfDebug.end(GLSMPerfDebug.Stage.FFP_PREDRAW, perfStart);
                }
                return;
            }
        }

        final int fkLen = FragmentKey.packFromState(currentFKScratch);
        final int fragUnitMask = FragmentKey.unitMaskFromPacked(currentFKScratch, fkLen);
        final long vkPacked = VertexKey.packFromState(hasColor, hasNormal, hasTexCoord, hasLightmap, fragUnitMask);

        if (!isCurrentVariant(currentVertexKeyPacked, currentFKPacked, currentFKLen, vkPacked, currentFKScratch, fkLen)) {
            commitVariant(vkPacked, fkLen);
        }

        uploadUniforms();
        notifyDrawObserver(hasColor, hasNormal, hasTexCoord, hasLightmap);
        if (perfDebugEnabled) {
            GLSMPerfDebug.end(GLSMPerfDebug.Stage.FFP_PREDRAW, perfStart);
        }
    }

    private static void notifyDrawObserver(
        boolean hasColor,
        boolean hasNormal,
        boolean hasTexCoord,
        boolean hasLightmap
    ) {
        final var observer = GLSMHooks.drawCallObserver;
        if (observer == null) {
            return;
        }

        int vertexFlags = 0;
        if (hasColor) vertexFlags |= VertexFlags.COLOR_BIT;
        if (hasNormal) vertexFlags |= VertexFlags.NORMAL_BIT;
        if (hasTexCoord) vertexFlags |= VertexFlags.TEXTURE_BIT;
        if (hasLightmap) vertexFlags |= VertexFlags.BRIGHTNESS_BIT;
        observer.beforeDraw(vertexFlags);
    }

    public synchronized void preDraw(int vertexFlags) {
        currentVertexFlags = vertexFlags;
        if (DEBUG_DRAW_LOGS) {
            GLSMDebug.logFfpPreDraw(vertexFlags);
        }
        preDraw(
            (vertexFlags & VertexFlags.COLOR_BIT) != 0,
            (vertexFlags & VertexFlags.NORMAL_BIT) != 0,
            (vertexFlags & VertexFlags.TEXTURE_BIT) != 0,
            (vertexFlags & VertexFlags.BRIGHTNESS_BIT) != 0
        );
    }

    public void preDraw() {
        // Draw entry points reached without an explicit VertexFormat (raw GL draws from
        // third-party mods, e.g. HBM-CE's VAO path) must not trust the globally tracked
        // currentVertexFlags: client-state calls during model upload can leak bits (e.g.
        // COLOR_BIT) into it without a matching VAO attribute, which makes the shader read
        // default (0,0,0,1) for the missing attribute and render black. Derive from the
        // currently bound VAO's actual attribute enablement instead.
        preDraw(VertexAttribState.currentClientArrayVertexFlags());
    }

    private void updateVariant(boolean hasColor, boolean hasNormal, boolean hasTexCoord, boolean hasLightmap) {
        final int fkLen = FragmentKey.packFromState(currentFKScratch);
        final int fragUnitMask = FragmentKey.unitMaskFromPacked(currentFKScratch, fkLen);
        final long vkPacked = VertexKey.packFromState(hasColor, hasNormal, hasTexCoord, hasLightmap, fragUnitMask);
        final boolean variantChanged = currentProgram == null
            || !isCurrentVariant(currentVertexKeyPacked, currentFKPacked, currentFKLen, vkPacked, currentFKScratch, fkLen);
        if (variantChanged) {
            commitVariant(vkPacked, fkLen);
        } else {
            RENDER_BACKEND.useProgram(currentProgram.getProgramId());
        }
    }

    static boolean isCurrentVariant(
        long currentVertexKey,
        long[] currentFragmentKey,
        int currentFragmentKeyLength,
        long candidateVertexKey,
        long[] candidateFragmentKey,
        int candidateFragmentKeyLength
    ) {
        return currentVertexKey == candidateVertexKey
            && Arrays.equals(
                currentFragmentKey,
                0,
                currentFragmentKeyLength,
                candidateFragmentKey,
                0,
                candidateFragmentKeyLength
            );
    }

    private void commitVariant(long vkPacked, int fkLen) {
        currentVertexKeyPacked = vkPacked;
        System.arraycopy(currentFKScratch, 0, currentFKPacked, 0, fkLen);
        currentFKLen = fkLen;
        currentProgram = cache.getOrCreate(vkPacked, currentFKPacked, currentFKLen);
        RENDER_BACKEND.useProgram(currentProgram.getProgramId());
    }

    private void uploadUniforms() {
        if (currentProgram != null) {
            final boolean perfDebugEnabled = GLSMPerfDebug.isEnabled();
            final long perfStart = perfDebugEnabled ? GLSMPerfDebug.begin(GLSMPerfDebug.Stage.FFP_UNIFORMS) : 0L;
            uniforms.upload(currentProgram);
            if (perfDebugEnabled) {
                GLSMPerfDebug.end(GLSMPerfDebug.Stage.FFP_UNIFORMS, perfStart);
            }
        }
    }

    public static Vector4f getCurrentTexCoord() { return currentTexCoords[0]; }
    public static Vector4f getCurrentTexCoord(int unit) { return currentTexCoords[unit]; }

    public static void setCurrentNormal(float x, float y, float z) {
        currentNormal.set(x, y, z);
        normalGeneration++;
    }

    public static void setCurrentTexCoord(float s, float t, float r, float q) {
        currentTexCoords[0].set(s, t, r, q);
        texCoordGeneration++;
    }

    public static void setCurrentTexCoord(int unit, float s, float t, float r, float q) {
        currentTexCoords[unit].set(s, t, r, q);
        texCoordGeneration++;
    }

    public static void bumpNormalGeneration() { normalGeneration++; }
    public static void bumpTexCoordGeneration() { texCoordGeneration++; }

    public void enableClientVertexFlag(int flag) {currentVertexFlags |= flag;}
    public void disableClientVertexFlag(int flag) {currentVertexFlags &= ~flag;}
    public void clearClientVertexFlags() {currentVertexFlags = 0;}
    public int getCurrentVertexFlags() {return currentVertexFlags;}
    public void setCurrentVertexFlags(int flags) {currentVertexFlags = flags;}

    public void onBindVertexArray(int vaoId) {
        final int flags = vaoVertexFlags.get(vaoId);
        if (flags != -1) {
            currentVertexFlags = flags;
        } else {
            // VAO never seen through the vanilla VertexFormat setup path (e.g. a third-party
            // VAO like HBM-CE's OBJ models): do not keep whatever flags a previous draw left
            // behind, derive them from the VAO's actual attributes so a later raw draw cannot
            // pick up leaked COLOR_BIT/NORMAL_BIT and render black.
            currentVertexFlags = VertexAttribState.currentClientArrayVertexFlags();
        }
    }

    public void onDeleteVertexArray(int vaoId) {
        vaoVertexFlags.remove(vaoId);
    }

    public void destroy() {
        cache.destroy();
        uniforms.destroy();
        final GLSMInitConfig config = GLStateManager.getInitConfig();
        if (config != null && config.getStreamingDrawerDestroy() != null) config.getStreamingDrawerDestroy().run();
        QuadConverter.destroy();
        active = false;
        currentProgram = null;
    }

    public String getDebugInfo() {
        return String.format("FFP: %d programs (%d vert, %d frag variants)",
            cache.getProgramCount(), cache.getVertexVariantCount(), cache.getFragmentVariantCount());
    }
}
