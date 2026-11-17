package com.dhj.actinium.mixin.features.tesr;

import com.gtnewhorizons.angelica.config.AngelicaConfig;
import com.gtnewhorizons.angelica.rendering.tesr.EntityShadowReplay;
import com.gtnewhorizons.angelica.rendering.tesr.ModelPartBatcher;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.coderbot.iris.pipeline.ShadowRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records the shadow pass entity draws the main pass replays.
 *
 * <p>The shadow pass visits every rendered entity in an order of its own, so it gets a batch of its
 * own and one record scope per entity. Only entities whose draws were proven replayable end up with
 * a record, and the main pass skips the renderer call for exactly those.</p>
 */
@Mixin(ShadowRenderer.class)
public abstract class MixinShadowRendererEntities {

    @Inject(method = "renderEntities", at = @At("HEAD"))
    private void actinium$beginEntityBatch(CallbackInfo ci) {
        ModelPartBatcher.INSTANCE.begin(ModelPartBatcher.Mode.ENTITIES, true);
    }

    @Inject(method = "renderEntities", at = @At("RETURN"))
    private void actinium$flushEntityBatch(CallbackInfo ci) {
        ModelPartBatcher.INSTANCE.flush();
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
        final boolean recording = AngelicaConfig.entityShadowReplayEnabled()
            && EntityShadowReplay.beginEntity(entity, partialTicks);
        boolean completed = false;
        try {
            original.call(manager, entity, partialTicks, fancy);
            completed = true;
        } finally {
            if (recording) {
                EntityShadowReplay.endEntity(completed);
            }
        }
    }
}
