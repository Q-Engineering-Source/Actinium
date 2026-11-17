package com.gtnewhorizons.angelica.rendering.tesr;

import com.gtnewhorizons.angelica.compat.mojang.RenderLayer;

public final class TesrLifecycle {

    private TesrLifecycle() {}

    public static void reset() {
        TesrBatchRenderer.INSTANCE.clearRetained();
        ModelPartBatcher.INSTANCE.clear();
        EntityTextureAtlas.INSTANCE.clear();

        RenderLayer.clearInterningAndHooks();
        DrawState.clearInterning();
    }
}
