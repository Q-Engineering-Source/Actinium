package com.dhj.actinium.compat.neverenoughanimations;

import com.cleanroommc.neverenoughanimations.NEA;
import com.dhj.actinium.render.ItemVertexAlphaOverride;
import com.dhj.actinium.render.ItemVertexAlphaOverrides;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.gtnewhorizon.gtnhlib.compat.Mods;

/**
 * Bridges NeverEnoughAnimation's GUI open/close fade (mod id {@code neverenoughanimations}) into
 * Actinium's fast lit item path.
 *
 * <p>NeverEnoughAnimation scales the alpha of every item vertex it draws while a GUI fades,
 * through mixins on {@code LightUtil.renderQuadColor}, {@code BufferBuilder.color} and
 * {@code GlStateManager.color}. Actinium's fast lit-item path bypasses the vanilla vertex
 * pipeline, so the fade was either lost or baked into the display list at the alpha of the frame
 * that compiled it; chest GUI items stayed invisible while that cache entry lived (issue #145).
 * This source reports the fade multiplier so the fast path steps aside for the animation.</p>
 */
public final class NeverEnoughAnimationsAlphaOverride implements ItemVertexAlphaOverride {

    private static final NeverEnoughAnimationsAlphaOverride INSTANCE = new NeverEnoughAnimationsAlphaOverride();

    private NeverEnoughAnimationsAlphaOverride() {
    }

    /**
     * Installs the override when NeverEnoughAnimation is loaded. Must be called from the host mod's
     * init phase: it is a no-op otherwise, and it loads {@code NEA} only once the mod is known to be
     * present.
     */
    public static void install() {
        if (!Mods.NEVERENOUGHANIMATIONS) {
            return;
        }

        ItemVertexAlphaOverrides.register(INSTANCE);
        ActiniumRuntime.logger().info("NeverEnoughAnimation compatibility layer enabled");
    }

    @Override
    public float getAlphaMultiplier() {
        return NEA.isCurrentGuiAnimating() ? NEA.getCurrentOpenAnimationValue() : 1.0F;
    }
}
