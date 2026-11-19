package net.coderbot.iris.gbuffer_overrides.matching;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.states.BlendState;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

/** Decides whether the current vanilla draw uses the standard translucent blend mode. */
public final class TranslucentBlendMatcher {
    private static final BlendState scratch = new BlendState();

    private TranslucentBlendMatcher() {
    }

    /**
     * Resolves translucency for the current draw, preferring an explicit declaration over the
     * live blend state so recorded geometry keeps the pass it was compiled for.
     */
    public static boolean resolveTranslucency(@Nullable Boolean declared) {
        return declared != null ? declared : matchesCurrentState();
    }

    public static boolean matchesCurrentState() {
        if (!GLStateManager.isEffectiveBlendEnabled()) {
            return false;
        }
        GLStateManager.getEffectiveBlendState(scratch);
        return scratch.getSrcRgb() == GL11.GL_SRC_ALPHA
            && scratch.getDstRgb() == GL11.GL_ONE_MINUS_SRC_ALPHA;
    }
}
