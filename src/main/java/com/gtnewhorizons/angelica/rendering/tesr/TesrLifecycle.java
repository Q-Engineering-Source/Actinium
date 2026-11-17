package com.gtnewhorizons.angelica.rendering.tesr;

import com.gtnewhorizons.angelica.compat.mojang.RenderLayer;
import com.gtnewhorizons.angelica.rendering.particles.ParticleInstancer;

public final class TesrLifecycle {

    private TesrLifecycle() {}

    public static void reset() {
        TesrBatchRenderer.INSTANCE.clearRetained();
        ModelPartBatcher.INSTANCE.clear();
        EntityTextureAtlas.INSTANCE.freePages();
        // Rejection bookkeeping outlives the flag that gates the feature, so release it here too.
        EntityShadowReplay.clear();
        TesrLightCache.clear();
        BeaconBeamMeshes.clear();
        BatchEligibility.reset();
        ParticleInstancer.clear();

        RenderLayer.clearInterningAndHooks();
        DrawState.clearInterning();
    }
}
