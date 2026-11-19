package com.dhj.actinium.mixin.features.tesr;

import com.gtnewhorizons.angelica.rendering.tesr.TesrBrackets;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.coderbot.iris.gl.MatrixStack;
import net.coderbot.iris.pipeline.ShadowRenderer;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Hands the shadow pass entity draws to the shadow entity brackets and their replay record. */
@Mixin(ShadowRenderer.class)
public abstract class MixinShadowRendererEntities {

    @WrapMethod(method = "renderEntities")
    private void actinium$batchShadowEntities(EntityRenderer levelRenderer, Frustum frustum, Object bufferSource,
                                              MatrixStack modelView, double cameraX, double cameraY, double cameraZ,
                                              float partialTicks, Operation<Void> original) {
        TesrBrackets.shadowEntities(() -> original.call(levelRenderer, frustum, bufferSource, modelView,
            cameraX, cameraY, cameraZ, partialTicks));
    }

    @WrapOperation(
        method = "renderEntities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/RenderManager;renderEntityStatic(Lnet/minecraft/entity/Entity;FZ)V"
        )
    )
    private void actinium$recordEntity(RenderManager manager, Entity entity, float partialTicks, boolean fancy,
                                       Operation<Void> original) {
        TesrBrackets.shadowEntity(entity, partialTicks, () -> original.call(manager, entity, partialTicks, fancy));
    }
}
