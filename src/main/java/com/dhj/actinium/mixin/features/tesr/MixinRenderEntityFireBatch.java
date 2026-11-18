package com.dhj.actinium.mixin.features.tesr;

import com.gtnewhorizons.angelica.glsm.ITessellatorData;
import com.gtnewhorizons.angelica.rendering.tesr.ModelPartBatcher;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.texture.TextureMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Render.class)
public abstract class MixinRenderEntityFireBatch {
    @WrapOperation(
        method = "renderEntityOnFire(Lnet/minecraft/entity/Entity;DDDF)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;draw()V")
    )
    private void actinium$batchFire(Tessellator tessellator, Operation<Void> original) {
        if (!ModelPartBatcher.INSTANCE.queueEntityEffect((ITessellatorData) tessellator.getBuffer(),
            TextureMap.LOCATION_BLOCKS_TEXTURE, true)) {
            original.call(tessellator);
        }
    }
}
