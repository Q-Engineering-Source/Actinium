package com.dhj.actinium.mixin.mod.ccl;

import com.dhj.actinium.compat.ccl.GlStateTrackerSnapshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Compatibility bridge for CodeChickenLib's {@code GlStateTracker}.
 *
 * <p>CCL's tracker reads the vanilla {@code GlStateManager} field cache, which stays frozen
 * under the GLSM redirector; DE's per-frame HUD drawing then resets the real GL state
 * (blend/alpha/depth/cull/lighting) to those defaults every frame, corrupting clouds,
 * grass tinting and the main menu. This bridge reads and restores the real GLSM tracked
 * state instead ({@link GlStateTrackerSnapshot}); loaded only when CodeChickenLib is
 * present (see MixinLate).
 */
@Mixin(targets = "codechicken.lib.render.state.GlStateTracker")
public abstract class MixinGlStateTracker {

    /**
     * @author Actinium
     * @reason Save the real GLSM tracked state instead of the frozen vanilla field cache.
     */
    @Overwrite
    public static void pushState() {
        GlStateTrackerSnapshot.push();
    }

    /**
     * @author Actinium
     * @reason Restore the real GLSM tracked state instead of the frozen vanilla field cache.
     */
    @Overwrite
    public static void popState() {
        GlStateTrackerSnapshot.pop();
    }
}
