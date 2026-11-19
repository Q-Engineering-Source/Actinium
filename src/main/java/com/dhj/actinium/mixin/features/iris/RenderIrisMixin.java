package com.dhj.actinium.mixin.features.iris;

import com.gtnewhorizons.angelica.rendering.tesr.TesrBrackets;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.objects.Object2IntFunction;
import net.coderbot.iris.Iris;
import net.coderbot.iris.block_rendering.BlockRenderingSettings;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import net.coderbot.iris.shaderpack.materialmap.NamespacedId;
import net.coderbot.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Render.class)
public class RenderIrisMixin {
    @Unique
    private static final NamespacedId ACTINIUM$NAME_TAG_ID = new NamespacedId("minecraft", "name_tag");
    @Unique
    private static final NamespacedId ACTINIUM$ENTITY_FLAME_ID = new NamespacedId("minecraft", "entity_flame");

    @Unique
    private int actinium$previousEntityId = Integer.MIN_VALUE;
    @Unique
    private int actinium$previousFlameEntityId = Integer.MIN_VALUE;

    @Inject(method = "renderShadow(Lnet/minecraft/entity/Entity;DDDFF)V", at = @At("HEAD"), cancellable = true)
    private void actinium$cancelVanillaShadowForIris(
        Entity entity,
        double x,
        double y,
        double z,
        float shadowAlpha,
        float partialTicks,
        CallbackInfo ci
    ) {
        WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        if (pipeline != null && pipeline.shouldDisableVanillaEntityShadows()) {
            ci.cancel();
        }
    }

    @Inject(
        method = "renderLivingLabel(Lnet/minecraft/entity/Entity;Ljava/lang/String;DDDI)V",
        at = @At("HEAD")
    )
    private void actinium$setNameTagEntityId(
        Entity entity,
        String name,
        double x,
        double y,
        double z,
        int maxDistance,
        CallbackInfo ci
    ) {
        Object2IntFunction<NamespacedId> entityIdMap = BlockRenderingSettings.INSTANCE.getEntityIds();
        if (entityIdMap == null) {
            return;
        }

        this.actinium$previousEntityId = CapturedRenderingState.INSTANCE.getCurrentRenderedEntity();
        CapturedRenderingState.INSTANCE.setCurrentEntity(Math.max(0, entityIdMap.getInt(ACTINIUM$NAME_TAG_ID)));
    }

    @Inject(
        method = "renderLivingLabel(Lnet/minecraft/entity/Entity;Ljava/lang/String;DDDI)V",
        at = @At("RETURN")
    )
    private void actinium$restoreNameTagEntityId(
        Entity entity,
        String name,
        double x,
        double y,
        double z,
        int maxDistance,
        CallbackInfo ci
    ) {
        if (this.actinium$previousEntityId != Integer.MIN_VALUE) {
            CapturedRenderingState.INSTANCE.setCurrentEntity(this.actinium$previousEntityId);
            this.actinium$previousEntityId = Integer.MIN_VALUE;
        }
    }

    @WrapOperation(method = "renderLivingLabel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/EntityRenderer;drawNameplate(Lnet/minecraft/client/gui/FontRenderer;Ljava/lang/String;FFFIFFZZ)V"))
    private void actinium$accountNameplate(FontRenderer font, String name, float x, float y, float z, int offset,
                                           float viewYaw, float viewPitch, boolean reversePitch, boolean sneaking,
                                           Operation<Void> original) {
        TesrBrackets.renderNameplate(() -> original.call(font, name, x, y, z, offset, viewYaw, viewPitch, reversePitch, sneaking));
    }

    @Inject(method = "renderEntityOnFire(Lnet/minecraft/entity/Entity;DDDF)V", at = @At("HEAD"))
    private void actinium$setFlameEntityId(Entity entity, double x, double y, double z, float partialTicks, CallbackInfo ci) {
        Object2IntFunction<NamespacedId> entityIdMap = BlockRenderingSettings.INSTANCE.getEntityIds();
        if (entityIdMap == null) {
            return;
        }

        this.actinium$previousFlameEntityId = CapturedRenderingState.INSTANCE.getCurrentRenderedEntity();
        CapturedRenderingState.INSTANCE.setCurrentEntity(Math.max(0, entityIdMap.getInt(ACTINIUM$ENTITY_FLAME_ID)));
    }

    @Inject(method = "renderEntityOnFire(Lnet/minecraft/entity/Entity;DDDF)V", at = @At("RETURN"))
    private void actinium$restoreFlameEntityId(Entity entity, double x, double y, double z, float partialTicks, CallbackInfo ci) {
        if (this.actinium$previousFlameEntityId != Integer.MIN_VALUE) {
            CapturedRenderingState.INSTANCE.setCurrentEntity(this.actinium$previousFlameEntityId);
            this.actinium$previousFlameEntityId = Integer.MIN_VALUE;
        }
    }
}
