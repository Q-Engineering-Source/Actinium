package com.dhj.actinium.mixin.features.iris;

import com.gtnewhorizons.angelica.rendering.tesr.TesrBrackets;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.coderbot.iris.debug.ShaderRegressionDebug;
import net.coderbot.iris.uniforms.CapturedRenderingState;
import net.coderbot.iris.uniforms.ItemIdManager;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderLivingBase.class)
public abstract class RenderLivingBaseIrisMixin<T extends EntityLivingBase> {
    @WrapOperation(method = "renderModel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/model/ModelBase;render(Lnet/minecraft/entity/Entity;FFFFFF)V"))
    private void actinium$accountMainModel(ModelBase model, Entity entity, float swing, float amount, float age,
                                           float yaw, float pitch, float scale, Operation<Void> original) {
        TesrBrackets.expectedDraws(() -> original.call(model, entity, swing, amount, age, yaw, pitch, scale));
    }

    @WrapOperation(
        method = {
            "setBrightness(Lnet/minecraft/entity/EntityLivingBase;FZ)Z",
            "func_177092_a(Lnet/minecraft/entity/EntityLivingBase;FZ)Z",
            "a(Lvp;FZ)Z"
        },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/RenderLivingBase;getColorMultiplier(Lnet/minecraft/entity/EntityLivingBase;FF)I"
        ),
        require = 0
    )
    private int actinium$captureEntityColor(
        RenderLivingBase<T> instance,
        T entity,
        float lightBrightness,
        float partialTicks,
        Operation<Integer> original
    ) {
        int color = original.call(instance, entity, lightBrightness, partialTicks);
        float a = (color >> 24 & 255) / 255.0F;
        float r = (color >> 16 & 255) / 255.0F;
        float g = (color >> 8 & 255) / 255.0F;
        float b = (color & 255) / 255.0F;
        CapturedRenderingState.INSTANCE.setCurrentEntityColor(r, g, b, a);
        ShaderRegressionDebug.logEntityColor("setBrightness-color-multiplier", entity, r, g, b, a);
        return color;
    }

    @Inject(
        method = {
            "doRender(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V",
            "func_76986_a(Lnet/minecraft/entity/EntityLivingBase;DDDFF)V",
            "a(Lvp;DDDFF)V"
        },
        at = @At("RETURN"),
        require = 0
    )
    private void actinium$resetEntityColorAtRenderEnd(
        T entity,
        double x,
        double y,
        double z,
        float entityYaw,
        float partialTicks,
        CallbackInfo ci
    ) {
        CapturedRenderingState.INSTANCE.setCurrentEntityColor(0.0F, 0.0F, 0.0F, 0.0F);
        ShaderRegressionDebug.logEntityColor("render-end-reset", entity, 0.0F, 0.0F, 0.0F, 0.0F);
    }

    @Inject(
        method = {
            "renderLayers(Lnet/minecraft/entity/EntityLivingBase;FFFFFFF)V",
            "func_177093_a(Lnet/minecraft/entity/EntityLivingBase;FFFFFFF)V",
            "a(Lvp;FFFFFFF)V"
        },
        at = @At("RETURN"),
        require = 0
    )
    private void actinium$resetItemIdAfterLayers(
        T entity,
        float limbSwing,
        float limbSwingAmount,
        float partialTicks,
        float ageInTicks,
        float netHeadYaw,
        float headPitch,
        float scaleIn,
        CallbackInfo ci
    ) {
        ItemIdManager.resetItemId();
    }
}
