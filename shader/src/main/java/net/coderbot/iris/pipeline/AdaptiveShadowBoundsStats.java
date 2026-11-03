package net.coderbot.iris.pipeline;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.coderbot.iris.Iris;
import dhj.embeddedt.embeddium.impl.gl.debug.GLDebug;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL43;

import java.nio.IntBuffer;
import java.util.Locale;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;

/**
 * Owns the optional GPU counters used to measure adaptive shadow bounds checks.
 *
 * <p>The counters are allocated only for a debug-enabled pipeline and use an SSBO binding that
 * is not occupied by the shader pack. The transformer reads the active binding before compiling
 * fragment shaders, while this class reads and resets the counters once per perf report.</p>
 */
public final class AdaptiveShadowBoundsStats {
    private static final int COUNTER_COUNT = 6;
    private static final int TEXTURE_CALLS = 0;
    private static final int TEXTURE_REJECTED = 1;
    private static final int FILTERED_CALLS = 2;
    private static final int FILTERED_REJECTED = 3;
    private static final int TEXTURE_SAMPLES_SAVED = 4;
    private static final int FILTERED_SAMPLES_SAVED = 5;
    private static final String INSTANCE_NAME = "actiniumShadowBoundsStats";

    private static final AdaptiveShadowBoundsStats DISABLED = new AdaptiveShadowBoundsStats(-1, false);
    private static volatile AdaptiveShadowBoundsStats active = DISABLED;

    private final boolean enabled;
    private final int binding;
    private final IntBuffer readback;
    private final IntBuffer zeroes;
    private int bufferId;
    private boolean destroyed;

    private AdaptiveShadowBoundsStats(int binding, boolean enabled) {
        this.binding = binding;
        this.enabled = enabled;
        if (enabled) {
            this.readback = BufferUtils.createIntBuffer(COUNTER_COUNT);
            this.zeroes = BufferUtils.createIntBuffer(COUNTER_COUNT);
            this.bufferId = GLSMBuffers.glGenBuffers();
            if (this.bufferId == 0) {
                throw new IllegalStateException("Failed to allocate adaptive shadow bounds statistics SSBO");
            }
            GLDebug.nameObject(GL43.GL_BUFFER, this.bufferId, "Actinium adaptive shadow bounds stats");
            GLSMBuffers.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.bufferId);
            GLSMBuffers.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,
                (long) COUNTER_COUNT * Integer.BYTES, GL15.GL_DYNAMIC_READ);
            GLSMBuffers.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, this.zeroes.duplicate());
            RenderSystem.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, this.bufferId);
            GLSMBuffers.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        } else {
            this.readback = null;
            this.zeroes = null;
        }
    }

    /**
     * Creates the per-pipeline counter buffer, reserving the highest free SSBO binding.
     *
     * @param occupiedBindings bindings declared by the shader pack
     * @return an enabled counter buffer when the debug path is supported, otherwise a disabled instance
     */
    public static AdaptiveShadowBoundsStats create(Int2ObjectMap<?> occupiedBindings) {
        if (!GLSMPerfDebug.isEnabled()) {
            return DISABLED;
        }
        if (!RenderSystem.supportsSSBO() || RenderSystem.getMaxGlslVersion() < 430) {
            Iris.logger.info("[AdaptiveShadowBounds] runtime stats disabled: GLSL 4.30 SSBO support is unavailable");
            return DISABLED;
        }

        final int binding = findFreeBinding(occupiedBindings);
        if (binding < 0) {
            Iris.logger.warn("[AdaptiveShadowBounds] runtime stats disabled: no free SSBO binding");
            return DISABLED;
        }
        Iris.logger.info("[AdaptiveShadowBounds] runtime stats enabled binding={}", binding);
        return new AdaptiveShadowBoundsStats(binding, true);
    }

    private static int findFreeBinding(Int2ObjectMap<?> occupiedBindings) {
        for (int binding = RenderSystem.getMaxSSBOBindings() - 1; binding >= 0; binding--) {
            if (!occupiedBindings.containsKey(binding)) {
                return binding;
            }
        }
        return -1;
    }

    /** Makes this pipeline's counter metadata visible to asynchronous shader transforms. */
    public static void activate(AdaptiveShadowBoundsStats stats) {
        active = stats != null ? stats : DISABLED;
    }

    /** Clears the active pipeline reference when its GL resources are destroyed. */
    public static void deactivate(AdaptiveShadowBoundsStats stats) {
        if (active == stats) {
            active = DISABLED;
        }
    }

    /** Returns whether transformed shaders should include the counter block. */
    public static boolean isInstrumentationEnabled() {
        return active.enabled;
    }

    /** Returns the binding selected for the active pipeline's counter block. */
    public static int getActiveBinding() {
        if (!active.enabled) {
            throw new IllegalStateException("Adaptive shadow bounds instrumentation is disabled");
        }
        return active.binding;
    }

    /** Supplies a token to the version scanner so injected std430 code hoists GLSL to 4.30. */
    public static String shaderVersionMarker() {
        return "std430";
    }

    /** Returns the GLSL declaration shared by all instrumented PCF helpers in one shader. */
    public static String declaration() {
        return declarationForBinding(getActiveBinding());
    }

    /** Returns a declaration for a caller that already selected a validated binding. */
    public static String declarationForBinding(int binding) {
        if (binding < 0) {
            throw new IllegalArgumentException("Adaptive shadow bounds SSBO binding must not be negative");
        }
        return "layout(std430, binding = " + binding + ") buffer ActiniumShadowBoundsStats {"
            + "uint textureCalls; uint textureRejected; uint filteredCalls; uint filteredRejected;"
            + "uint textureSamplesSaved; uint filteredSamplesSaved; } " + INSTANCE_NAME + ";";
    }

    /** Returns the call counter increment for a recognized helper. */
    public static String callCounter(String functionName) {
        return "atomicAdd(" + INSTANCE_NAME + "." + counterName(functionName, false) + ", 1u);";
    }

    /** Returns the rejection counter increment for a recognized helper. */
    public static String rejectedCounter(String functionName) {
        final String counter = counterName(functionName, true);
        final String savedCounter = functionName.equals("texture2DShadow2x2")
            ? "textureSamplesSaved"
            : "filteredSamplesSaved";
        final String savedSamples = functionName.equals("texture2DShadow2x2") ? "4u" : "1u";
        return "atomicAdd(" + INSTANCE_NAME + "." + counter + ", 1u);"
            + "atomicAdd(" + INSTANCE_NAME + "." + savedCounter + ", " + savedSamples + ");";
    }

    private static String counterName(String functionName, boolean rejected) {
        if (functionName.equals("texture2DShadow2x2")) {
            return rejected ? "textureRejected" : "textureCalls";
        }
        if (functionName.equals("SampleFilteredShadow")) {
            return rejected ? "filteredRejected" : "filteredCalls";
        }
        throw new IllegalArgumentException("Unsupported adaptive shadow bounds helper: " + functionName);
    }

    /** Reads the counters and clears them for the next perf interval. */
    public static String dumpStatsAndReset() {
        final AdaptiveShadowBoundsStats stats = active;
        return stats.enabled ? stats.dumpAndReset() : "";
    }

    private String dumpAndReset() {
        if (destroyed) {
            throw new IllegalStateException("Adaptive shadow bounds statistics buffer was destroyed");
        }

        RenderSystem.memoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);
        GLSMBuffers.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, bufferId);
        try {
            readback.clear();
            GLSMBuffers.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, readback);

            final long textureCalls = unsigned(readback.get(TEXTURE_CALLS));
            final long textureRejected = unsigned(readback.get(TEXTURE_REJECTED));
            final long filteredCalls = unsigned(readback.get(FILTERED_CALLS));
            final long filteredRejected = unsigned(readback.get(FILTERED_REJECTED));
            final long samplesSaved = unsigned(readback.get(TEXTURE_SAMPLES_SAVED))
                + unsigned(readback.get(FILTERED_SAMPLES_SAVED));
            final long calls = textureCalls + filteredCalls;
            final long rejected = textureRejected + filteredRejected;
            final double passRate = calls == 0L ? 100.0 : (calls - rejected) * 100.0 / calls;

            zeroes.clear();
            GLSMBuffers.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, zeroes);
            return "adaptiveShadowBounds[calls=" + calls
                + ",rejected=" + rejected
                + ",passRate=" + String.format(Locale.ROOT, "%.1f%%", passRate)
                + ",textureCalls=" + textureCalls
                + ",textureRejected=" + textureRejected
                + ",filteredCalls=" + filteredCalls
                + ",filteredRejected=" + filteredRejected
                + ",estimatedPcfSamplesSaved=" + samplesSaved + "]";
        } catch (RuntimeException exception) {
            Iris.logger.error("[AdaptiveShadowBounds] failed to read runtime statistics", exception);
            return "adaptiveShadowBounds[readbackError=true]";
        } finally {
            RenderSystem.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, bufferId);
            GLSMBuffers.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        }
    }

    private static long unsigned(int value) {
        return Integer.toUnsignedLong(value);
    }

    /** Releases the counter buffer owned by this pipeline. */
    public void destroy() {
        if (!enabled || destroyed) {
            return;
        }
        deactivate(this);
        RenderSystem.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, 0);
        GLSMBuffers.glDeleteBuffers(bufferId);
        bufferId = 0;
        destroyed = true;
    }
}
