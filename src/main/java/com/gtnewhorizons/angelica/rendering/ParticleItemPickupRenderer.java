package com.gtnewhorizons.angelica.rendering;

import net.coderbot.iris.layer.GbufferPrograms;

public final class ParticleItemPickupRenderer {

    private ParticleItemPickupRenderer() {}

    public static void render(Runnable entityDraw, boolean shaderPackInUse) {
        final boolean reopen = ParticleRunSplitter.beforeNestedDraw();
        if (shaderPackInUse) {
            GbufferPrograms.beginEntities();
        }
        try {
            entityDraw.run();
        } finally {
            if (shaderPackInUse) {
                GbufferPrograms.endEntities();
            }
            ParticleRunSplitter.afterNestedDraw(reopen);
        }
    }
}
