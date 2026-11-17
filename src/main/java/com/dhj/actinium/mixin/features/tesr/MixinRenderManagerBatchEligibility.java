package com.dhj.actinium.mixin.features.tesr;

import com.gtnewhorizons.angelica.config.AngelicaConfig;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.mixins.interfaces.IBatchEligibility;
import com.gtnewhorizons.angelica.rendering.tesr.BatchEligibility;
import com.gtnewhorizons.angelica.rendering.tesr.EntityShadowReplay;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Brackets every entity renderer call with its eligibility bracket and the shadow replay record.
 *
 * <p>The bracket proves a renderer batches cleanly: a renderer that draws its own geometry between
 * batched model parts reorders them, so the bracket demotes it after its first frame. The same
 * bracket marks the entity as the one whose draws the shadow pass is recording, which is what lets
 * the main pass replay those draws instead of re-running the renderer.</p>
 */
@Mixin(RenderManager.class)
public abstract class MixinRenderManagerBatchEligibility {

    @WrapOperation(
        method = "renderEntity",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/Render;doRender(Lnet/minecraft/entity/Entity;DDDFF)V"
        )
    )
    private void actinium$observeDoRender(Render<Entity> render, Entity entity, double x, double y, double z,
                                          float yaw, float partialTicks, Operation<Void> original) {
        final IBatchEligibility holder = (IBatchEligibility) render;
        final byte state = holder.angelica$batchState();
        final boolean recording = AngelicaConfig.entityShadowReplayEnabled()
            && EntityShadowReplay.beginRenderer(render, entity, partialTicks, state);
        BatchEligibility.begin(state, GLSMDraws.drawCalls);
        boolean completed = false;
        try {
            original.call(render, entity, x, y, z, yaw, partialTicks);
            completed = true;
        } finally {
            final byte next = BatchEligibility.end(state, GLSMDraws.drawCalls);
            if (recording) {
                EntityShadowReplay.endRenderer(completed, next);
            }
            if (next != state) {
                holder.angelica$setBatchState(next);
                BatchEligibility.onStateChange(render, next);
            }
        }
    }
}
