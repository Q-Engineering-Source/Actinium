package com.dhj.actinium.mixin.features.tesr;

import com.dhj.actinium.render.ArmorGlintBatching;
import com.gtnewhorizons.angelica.rendering.GlintClock;
import com.gtnewhorizons.angelica.rendering.tesr.GlintCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LayerArmorBase.class)
public abstract class MixinLayerArmorBaseGlint {
    @Shadow @Final protected static ResourceLocation ENCHANTED_ITEM_GLINT_RES;
    @Shadow private boolean skipRenderGlint;

    @WrapOperation(method = "renderArmorLayer", at = @At(value = "INVOKE", ordinal = 1,
        target = "Lnet/minecraft/client/model/ModelBase;render(Lnet/minecraft/entity/Entity;FFFFFF)V"))
    private void actinium$captureArmorBase(ModelBase model, Entity entity, float swing, float amount, float age,
                                           float yaw, float pitch, float scale, Operation<Void> original,
                                           @Local ItemStack stack, @Share("armorBase") LocalRef<ArmorGlintBatching> base) {
        if (skipRenderGlint || !stack.isItemEnchanted()) {
            original.call(model, entity, swing, amount, age, yaw, pitch, scale);
            return;
        }
        base.set(ArmorGlintBatching.captureBase(model, entity,
            () -> original.call(model, entity, swing, amount, age, yaw, pitch, scale)));
    }

    @WrapOperation(method = "renderArmorLayer", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/entity/layers/LayerArmorBase;renderEnchantedGlint(Lnet/minecraft/client/renderer/entity/RenderLivingBase;Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/client/model/ModelBase;FFFFFFF)V"))
    private void actinium$queueArmorGlint(RenderLivingBase<?> renderer, EntityLivingBase entity, ModelBase model,
                                         float swing, float amount, float partialTicks, float age, float yaw, float pitch,
                                         float scale, Operation<Void> original, @Share("armorBase") LocalRef<ArmorGlintBatching> base) {
        ArmorGlintBatching captured = base.get();
        if (captured == null) {
            original.call(renderer, entity, model, swing, amount, partialTicks, age, yaw, pitch, scale);
            return;
        }
        captured.renderGlint(model, entity, ENCHANTED_ITEM_GLINT_RES,
            () -> original.call(renderer, entity, model, swing, amount, partialTicks, age, yaw, pitch, scale));
    }

    @WrapMethod(method = "renderEnchantedGlint")
    private static void actinium$applyWorldGlintOption(RenderLivingBase<?> renderer, EntityLivingBase entity, ModelBase model,
                                                      float swing, float amount, float partialTicks, float age, float yaw,
                                                      float pitch, float scale, Operation<Void> original) {
        if (!ArmorGlintBatching.isWorldArmor() || ArmorGlintBatching.isWorldGlintEnabled()) {
            original.call(renderer, entity, model, swing, amount, partialTicks, age, yaw, pitch, scale);
        }
    }

    @WrapOperation(method = "renderEnchantedGlint", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GlStateManager;translate(FFF)V"))
    private static void actinium$useFrameGlintPhase(float x, float y, float z, Operation<Void> original) {
        if (ArmorGlintBatching.isWorldArmor()) {
            GlintClock.translateArmorGlint();
        } else {
            original.call(x, y, z);
        }
    }

    @WrapOperation(method = "renderEnchantedGlint", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/model/ModelBase;render(Lnet/minecraft/entity/Entity;FFFFFF)V"))
    private static void actinium$drawArmorGlintLayer(ModelBase model, Entity entity, float swing, float amount, float age,
                                                    float yaw, float pitch, float scale, Operation<Void> original,
                                                    @Local int layer, @Share("firstLayer") LocalRef<GlintCapture> firstLayer) {
        if (ArmorGlintBatching.isQueued(model, entity)) return;
        if (layer == 0) {
            firstLayer.set(ArmorGlintBatching.captureFirstLayer(model,
                () -> original.call(model, entity, swing, amount, age, yaw, pitch, scale)));
        } else if (!ArmorGlintBatching.replaySecondLayer(firstLayer.get())) {
            original.call(model, entity, swing, amount, age, yaw, pitch, scale);
        }
    }
}
