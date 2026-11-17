package com.dhj.actinium.compat.celeritasdynamiclights;

import net.minecraft.util.math.BlockPos;

/**
 * Source of the dynamic light contribution Celeritas Dynamic Lights adds to a position.
 *
 * <p>The addon is optional and never a build dependency, so callers depend on this interface while
 * the only implementation obtains it through reflection. The merge itself belongs to the addon: this
 * interface carries the packed lightmap in and the addon's own result out, which keeps the dynamic
 * level computation and the max rule in the addon instead of duplicating them here.</p>
 */
public interface DynamicLightProvider {

    /**
     * Provider used whenever the addon cannot supply a level: absent at runtime, older than the
     * merge method, or already failed at a call. It returns every packed light it is given.
     */
    DynamicLightProvider NONE = (pos, packedLight) -> packedLight;

    /**
     * Merges the dynamic light reported at {@code pos} into a packed lightmap value.
     *
     * @param pos         the position the lightmap was sampled at
     * @param packedLight the sky and block light the renderer would use without dynamic lights
     * @return the packed light including the dynamic level at {@code pos}, or {@code packedLight}
     *         unchanged when the addon reports no level there or the provider is disabled
     */
    int mergeDynamicLight(BlockPos pos, int packedLight);
}
