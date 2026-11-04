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
import com.gtnewhorizons.angelica.glsm.hooks.GLSMInitConfig;
import com.gtnewhorizons.angelica.glsm.stacks.StackIdAllocator;
import com.gtnewhorizons.angelica.glsm.stacks.Vec3fStack;
import com.gtnewhorizons.angelica.glsm.stacks.Vec4fStack;
import com.gtnewhorizons.angelica.glsm.streaming.TessellatorStreamingDrawer;
import lombok.Getter;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.file.Paths;
import java.util.Arrays;

import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;

public final class ShaderManager {
    private static final boolean DEBUG_DRAW_LOGS = Boolean.getBoolean("actinium.glsm.verboseDrawLogs");

    private ShaderCache cache = new ShaderCache();
    private final Uniforms uniforms = new Uniforms();

    @Getter
    private boolean active;
    private Program currentProgram;
    private long currentVertexKeyPacked = Long.MIN_VALUE;
    private final long[] currentFKScratch = new long[FragmentKey.MAX_UNITS];
    private final long[] currentFKPacked = new long[FragmentKey.MAX_UNITS];
    private int currentFKLen;

    public final Vector3f currentNormal = new Vector3f(0.0f, 0.0f, 1.0f);
    public final Vector4f[] currentTexCoords = {
        new Vector4f(0.0f, 0.0f, 0.0f, 1.0f),
        new Vector4f(0.0f, 0.0f, 0.0f, 1.0f),
        new Vector4f(0.0f, 0.0f, 0.0f, 1.0f),
        new Vector4f(0.0f, 0.0f, 0.0f, 1.0f),
    };
    public final Vec3fStack normalStack = new Vec3fStack(currentNormal, StackIdAllocator.nextId());
    public final Vec4fStack texCoordStack = new Vec4fStack(currentTexCoords[0], StackIdAllocator.nextId());
    public int normalGeneration;
    public int texCoordGeneration;

    @Getter
    private static boolean enabled;
    private static boolean vertexFormatOverrideRegistered;

    public ShaderManager() {
        configureCacheDump();
    }

    private void configureCacheDump() {
        if (Boolean.parseBoolean(System.getProperty("angelica.dumpShaders", "false"))) {
            cache.setDumpDir(Paths.get("ffp_shaders"));
        }
    }

    public static ShaderManager getInstance() {
        return GLStateManager.getFfp();
    }

    public static Vec3fStack getNormalStack() {
        return getInstance().normalStack;
    }

    public static Vec4fStack getTexCoordStack() {
        return getInstance().texCoordStack;
    }

    public static synchronized void enable() {
        warmUp();
        enabled = true;
        if (!vertexFormatOverrideRegistered) {
            VertexFormat.registerSetupBufferStateOverride((format, offset) -> {
                GLStateManager.getVaoManager().setFormatVertexFlags(format.getVertexFlags());
                return false;
            });
            vertexFormatOverrideRegistered = true;
        }
        GLStateManager.LOGGER.info("FFP shader emulation enabled");
    }

    private static void warmUp() {
        try {
            final long[] fkScratch = new long[FragmentKey.MAX_UNITS];
            final int fkLen = FragmentKey.packFromState(fkScratch);
            final int fragMask = FragmentKey.unitMaskFromPacked(fkScratch, fkLen);
            final long vkPacked = VertexKey.packFromState(true, true, true, true, fragMask);
            final VertexKey vk = VertexKey.fromPacked(vkPacked);
            VertexShaderGenerator.generate(vk);
            FragmentShaderGenerator.generate(FragmentKey.fromPacked(fkScratch, fkLen));
            final Class<?>[] touched = { Program.class, ShaderCache.class, TessellatorStreamingDrawer.class, QuadConverter.class };
            for (Class<?> c : touched) c.getName();
        } catch (Throwable t) {
            GLStateManager.LOGGER.warn("FFP warmup failed; draw-path classes will resolve lazily", t);
        }
    }

    public static void disable() {
        enabled = false;
    }

    public synchronized void activate() {
        if (active) return;
        active = true;
        final int vertexFlags = GLStateManager.getVaoManager().getVertexFlags();
        updateVariant(
            (vertexFlags & VertexFlags.COLOR_BIT) != 0,
            (vertexFlags & VertexFlags.NORMAL_BIT) != 0,
            (vertexFlags & VertexFlags.TEXTURE_BIT) != 0,
            (vertexFlags & VertexFlags.BRIGHTNESS_BIT) != 0
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
                    if (perfDebugEnabled) GLSMPerfDebug.end(GLSMPerfDebug.Stage.FFP_PREDRAW, perfStart);
                    return;
                }
                active = true;
            } else {
                notifyDrawObserver(hasColor, hasNormal, hasTexCoord, hasLightmap);
                if (perfDebugEnabled) GLSMPerfDebug.end(GLSMPerfDebug.Stage.FFP_PREDRAW, perfStart);
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
        if (perfDebugEnabled) GLSMPerfDebug.end(GLSMPerfDebug.Stage.FFP_PREDRAW, perfStart);
    }

    private static void notifyDrawObserver(boolean hasColor, boolean hasNormal, boolean hasTexCoord, boolean hasLightmap) {
        final var observer = GLSMHooks.drawCallObserver;
        if (observer == null) return;
        int vertexFlags = 0;
        if (hasColor) vertexFlags |= VertexFlags.COLOR_BIT;
        if (hasNormal) vertexFlags |= VertexFlags.NORMAL_BIT;
        if (hasTexCoord) vertexFlags |= VertexFlags.TEXTURE_BIT;
        if (hasLightmap) vertexFlags |= VertexFlags.BRIGHTNESS_BIT;
        observer.beforeDraw(vertexFlags);
    }

    public synchronized void preDraw(int vertexFlags) {
        setCurrentVertexFlags(vertexFlags);
        if (DEBUG_DRAW_LOGS) GLSMDebug.logFfpPreDraw(vertexFlags);
        preDraw(
            (vertexFlags & VertexFlags.COLOR_BIT) != 0,
            (vertexFlags & VertexFlags.NORMAL_BIT) != 0,
            (vertexFlags & VertexFlags.TEXTURE_BIT) != 0,
            (vertexFlags & VertexFlags.BRIGHTNESS_BIT) != 0
        );
    }

    public void preDraw() {
        preDraw(GLStateManager.getVaoManager().currentClientArrayVertexFlags());
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
        if (currentProgram == null) return;
        final boolean perfDebugEnabled = GLSMPerfDebug.isEnabled();
        final long perfStart = perfDebugEnabled ? GLSMPerfDebug.begin(GLSMPerfDebug.Stage.FFP_UNIFORMS) : 0L;
        uniforms.upload(currentProgram);
        if (perfDebugEnabled) GLSMPerfDebug.end(GLSMPerfDebug.Stage.FFP_UNIFORMS, perfStart);
    }

    public static Vector3f getCurrentNormal() {
        return getInstance().currentNormal;
    }

    public static Vector4f getCurrentTexCoord() {
        return getInstance().currentTexCoords[0];
    }

    public static Vector4f getCurrentTexCoord(int unit) {
        return getInstance().currentTexCoords[unit];
    }

    public static void setCurrentNormal(float x, float y, float z) {
        GLStateManager.recordExitNormal();
        final ShaderManager ffp = getInstance();
        ffp.normalStack.beforeModify();
        ffp.currentNormal.set(x, y, z);
        ffp.normalGeneration++;
    }

    public static void setCurrentTexCoord(float s, float t, float r, float q) {
        GLStateManager.recordExitTexCoord(0);
        final ShaderManager ffp = getInstance();
        ffp.texCoordStack.beforeModify();
        ffp.currentTexCoords[0].set(s, t, r, q);
        ffp.texCoordGeneration++;
    }

    public static void setCurrentTexCoord(int unit, float s, float t, float r, float q) {
        GLStateManager.recordExitTexCoord(unit);
        final ShaderManager ffp = getInstance();
        if (unit == 0) ffp.texCoordStack.beforeModify();
        ffp.currentTexCoords[unit].set(s, t, r, q);
        ffp.texCoordGeneration++;
    }

    public static int getNormalGeneration() {
        return getInstance().normalGeneration;
    }

    public static int getTexCoordGeneration() {
        return getInstance().texCoordGeneration;
    }

    public static void bumpNormalGeneration() {
        getInstance().normalGeneration++;
    }

    public static void bumpTexCoordGeneration() {
        getInstance().texCoordGeneration++;
    }

    public void enableClientVertexFlag(int flag) {
        GLStateManager.getVaoManager().enableClientVertexFlag(flag);
    }

    public void disableClientVertexFlag(int flag) {
        GLStateManager.getVaoManager().disableClientVertexFlag(flag);
    }

    public void clearClientVertexFlags() {
        GLStateManager.getVaoManager().setVertexFlags(0);
    }

    public int getCurrentVertexFlags() {
        return GLStateManager.getVaoManager().getVertexFlags();
    }

    public void setCurrentVertexFlags(int flags) {
        GLStateManager.getVaoManager().setVertexFlags(flags);
    }

    public void invalidateProgram() {
        currentProgram = null;
        currentVertexKeyPacked = Long.MIN_VALUE;
        currentFKLen = 0;
    }

    public void resetForContextMigration() {
        cache = new ShaderCache();
        configureCacheDump();
        invalidateProgram();
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
        return String.format(
            "FFP: %d programs (%d vert, %d frag variants)",
            cache.getProgramCount(), cache.getVertexVariantCount(), cache.getFragmentVariantCount());
    }
}
