package com.dhj.actinium.mixin.features.iris;

import com.dhj.actinium.mixinterface.features.iris.RenderLivingBaseOverlayAccess;
import com.gtnewhorizons.angelica.rendering.DeferredEntityOverlay;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Skips the layer calls the deferred overlay takes over.
 *
 * <p>1.12.2 drives armor and auras through {@link LayerRenderer} instances instead of the 1.7.10
 * {@code renderPassModel}. The capture point is the {@code doRenderLayer} call itself, so the real
 * renderer and the real layer instance are recorded and the same layer is re-invoked at flush
 * time; nothing is substituted for it. Only registered layers of registered entity types are
 * skipped, every other layer keeps vanilla's inline path.</p>
 */
@Mixin(RenderLivingBase.class)
public abstract class RenderLivingBaseDeferredOverlayMixin implements RenderLivingBaseOverlayAccess {

    @WrapWithCondition(
        method = "renderLayers",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/layers/LayerRenderer;doRenderLayer(Lnet/minecraft/entity/EntityLivingBase;FFFFFFF)V",
            ordinal = 0
        )
    )
    private boolean actinium$deferLayer(
        LayerRenderer<?> layer,
        EntityLivingBase entity,
        float limbSwing,
        float limbSwingAmount,
        float partialTicks,
        float ageInTicks,
        float netHeadYaw,
        float headPitch,
        float scale
    ) {
        return !DeferredEntityOverlay.maybeDefer(this, layer, entity,
            limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch, scale,
            ((AccessorRenderOutlines) this).actinium$isRenderOutlines());
    }

    @Override
    public ModelBase actinium$mainModel() {
        return ((RenderLivingBase<?>) (Object) this).getMainModel();
    }

    @Override
    public boolean actinium$setBrightness(EntityLivingBase entity, float partialTicks, boolean combineTextures) {
        return ((AccessorRenderLivingBaseBrightness) this).actinium$invokeSetBrightness(entity, partialTicks, combineTextures);
    }

    @Override
    public void actinium$unsetBrightness() {
        ((AccessorRenderLivingBaseBrightness) this).actinium$invokeUnsetBrightness();
    }
}
