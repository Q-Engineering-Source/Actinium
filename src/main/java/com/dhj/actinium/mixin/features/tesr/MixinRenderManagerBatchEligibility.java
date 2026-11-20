package com.dhj.actinium.mixin.features.tesr;

import com.gtnewhorizons.angelica.rendering.tesr.TesrBrackets;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RenderManager.class)
public abstract class MixinRenderManagerBatchEligibility {
    @Shadow
    private boolean renderOutlines;

    @WrapOperation(
        method = "renderEntity",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/Render;doRender(Lnet/minecraft/entity/Entity;DDDFF)V"
        )
    )
    private void actinium$observeDoRender(Render<Entity> render, Entity entity, double x, double y, double z,
                                          float yaw, float partialTicks, Operation<Void> original) {
        if (this.renderOutlines) {
            original.call(render, entity, x, y, z, yaw, partialTicks);
            return;
        }
        TesrBrackets.renderEntity(render, entity, partialTicks,
            () -> original.call(render, entity, x, y, z, yaw, partialTicks));
    }
}
