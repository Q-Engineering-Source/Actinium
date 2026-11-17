package com.dhj.actinium.mixin.features.tesr;

import com.gtnewhorizons.angelica.rendering.tesr.ModelPartBatcher;
import com.gtnewhorizons.angelica.rendering.tesr.PendingTesrDraws;
import com.gtnewhorizons.angelica.rendering.tesr.TesrBatchRenderer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.coderbot.iris.gl.MatrixStack;
import net.coderbot.iris.pipeline.ShadowRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Runs the shadow pass block entities through the TESR batch.
 *
 * <p>Shadow-pass block entities are drawn straight from the collected lists, not through the world
 * renderer, so the shadow pass needs its own batch bracket. Its own pass index keeps the retained
 * groups apart from the two main-view passes: shadow draws only request depth and can use the
 * shadow-specific instanced variants.</p>
 */
@Mixin(ShadowRenderer.class)
public abstract class MixinShadowRendererTesr {

    @WrapMethod(method = "renderTileEntities")
    private void actinium$batchShadowTesr(Object bufferSource, MatrixStack modelView, double cameraX, double cameraY,
                                          double cameraZ, float partialTicks, boolean hasEntityFrustum,
                                          Operation<Void> original) {
        TesrBatchRenderer.INSTANCE.beginPass(TesrBatchRenderer.PASS_SHADOW, modelView.peek().getModel(),
            cameraX, cameraY, cameraZ);
        ModelPartBatcher.INSTANCE.begin(ModelPartBatcher.Mode.BLOCK_ENTITIES, true);
        PendingTesrDraws.beginPass();
        try {
            original.call(bufferSource, modelView, cameraX, cameraY, cameraZ, partialTicks, hasEntityFrustum);
        } finally {
            PendingTesrDraws.endPass();
            ModelPartBatcher.INSTANCE.flush();
            TesrBatchRenderer.INSTANCE.flush();
        }
    }
}
