package com.dhj.actinium.mixin.features.tesr;

import com.gtnewhorizons.angelica.rendering.tesr.TesrBrackets;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.coderbot.iris.gl.MatrixStack;
import net.coderbot.iris.pipeline.ShadowRenderer;
import org.spongepowered.asm.mixin.Mixin;

/** Hands the shadow pass block entities to the shadow batch bracket. */
@Mixin(ShadowRenderer.class)
public abstract class MixinShadowRendererTesr {

    @WrapMethod(method = "renderTileEntities")
    private void actinium$batchShadowTesr(Object bufferSource, MatrixStack modelView, double cameraX, double cameraY,
                                          double cameraZ, float partialTicks, boolean hasEntityFrustum,
                                          Operation<Void> original) {
        TesrBrackets.shadowBlockEntities(modelView.peek().getModel(), cameraX, cameraY, cameraZ,
            () -> original.call(bufferSource, modelView, cameraX, cameraY, cameraZ, partialTicks, hasEntityFrustum));
    }
}
