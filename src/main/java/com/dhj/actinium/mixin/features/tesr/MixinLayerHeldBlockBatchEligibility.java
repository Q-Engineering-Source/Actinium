package com.dhj.actinium.mixin.features.tesr;

import com.gtnewhorizons.angelica.rendering.tesr.ModelPartBatcher;
import com.gtnewhorizons.angelica.rendering.tesr.TesrBrackets;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.entity.layers.LayerHeldBlock;
import net.minecraft.entity.monster.EntityEnderman;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LayerHeldBlock.class)
public abstract class MixinLayerHeldBlockBatchEligibility {

    @WrapMethod(method = "doRenderLayer(Lnet/minecraft/entity/monster/EntityEnderman;FFFFFFF)V")
    private void actinium$accountHeldBlock(EntityEnderman entity, float limbSwing, float limbSwingAmount,
                                           float partialTicks, float ageInTicks, float netHeadYaw, float headPitch,
                                           float scale, Operation<Void> original) {
        if (ModelPartBatcher.INSTANCE.entitiesActive()) {
            TesrBrackets.renderImmediateEntityLayer(
                () -> original.call(entity, limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch, scale));
        } else {
            original.call(entity, limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch, scale);
        }
    }
}
