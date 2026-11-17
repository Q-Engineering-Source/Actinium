package net.coderbot.iris.pipeline.transform;

import com.gtnewhorizons.angelica.glsm.RenderSystem;
import net.coderbot.iris.gbuffer_overrides.matching.InputAvailability;

public final class TransformTestSupport {

    public static final InputAvailability TEX_LM = InputAvailability.of(true, true);

    /** Returns the previous value for {@link #restoreGlslVersion}. */
    public static int pinGlslVersion(int version) {
        final int previous = RenderSystem.getMaxGlslVersion();
        RenderSystem.initializeGlslCapabilityForTesting(version);
        ShaderTransformer.init();
        return previous;
    }

    public static void restoreGlslVersion(int previous) {
        RenderSystem.initializeGlslCapabilityForTesting(previous);
        clearCaches();
        ShaderTransformer.init();
    }

    public static void clearCaches() {
        TransformPatcher.clearCache();
    }

    /** All whitespace removed. */
    public static String squash(String s) {
        return s.replaceAll("\\s+", "");
    }
}
