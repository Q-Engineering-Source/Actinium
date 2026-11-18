package com.dhj.actinium.mixin.features.tesr;

import com.gtnewhorizons.angelica.glsm.ITessellatorData;
import com.gtnewhorizons.angelica.rendering.tesr.ModelPartBatcher;
import com.gtnewhorizons.angelica.rendering.tesr.VanillaEntityEffects;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Render.class)
public abstract class MixinRenderEntityShadowBatch {
    @Shadow @Final protected RenderManager renderManager;
    @Shadow protected float shadowSize;
    @Shadow protected float shadowOpaque;

    @Inject(method = "doRenderShadowAndFire(Lnet/minecraft/entity/Entity;DDDFF)V", at = @At("HEAD"))
    private void actinium$preserveMainViewShadow(Entity entity, double x, double y, double z, float yaw,
                                                float partialTicks, CallbackInfo ci) {
        VanillaEntityEffects.preserveMainViewShadow(renderManager, entity, shadowSize, shadowOpaque);
    }

    @WrapOperation(
        method = "renderShadow(Lnet/minecraft/entity/Entity;DDDFF)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;draw()V")
    )
    private void actinium$batchShadow(Tessellator tessellator, Operation<Void> original) {
        if (!ModelPartBatcher.INSTANCE.queueEntityEffect((ITessellatorData) tessellator.getBuffer(),
            VanillaEntityEffects.SHADOW_TEXTURE, false)) {
            original.call(tessellator);
        }
    }
}
