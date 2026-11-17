package com.dhj.actinium.mixin.features.tesr;

import com.gtnewhorizons.angelica.config.AngelicaConfig;
import com.gtnewhorizons.angelica.glsm.GLSMAttribStack;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.StateSet;
import com.gtnewhorizons.angelica.mixins.interfaces.IBatchEligibility;
import com.gtnewhorizons.angelica.rendering.tesr.BatchEligibility;
import com.gtnewhorizons.angelica.rendering.tesr.PassRebindGate;
import com.gtnewhorizons.angelica.rendering.tesr.TesrAttribution;
import com.gtnewhorizons.angelica.rendering.tesr.TesrBatchRenderer;
import com.gtnewhorizons.angelica.rendering.tesr.TesrProviderDispatch;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Runs every tile entity renderer inside the TESR batch scope and its eligibility bracket.
 *
 * <p>The scope makes the meshes queued by the renderer part of one batch that flushes with the
 * block entity pass, and the bracket records whether the renderer drew its own geometry alongside
 * them so a mixed renderer stops being batched after its first frame.</p>
 */
@Mixin(TileEntityRendererDispatcher.class)
public abstract class MixinTileEntityRendererDispatcherTesr {

    @WrapOperation(
        method = "render(Lnet/minecraft/tileentity/TileEntity;DDDFIF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/tileentity/TileEntitySpecialRenderer;render(Lnet/minecraft/tileentity/TileEntity;DDDFIF)V"
        )
    )
    private void actinium$renderTesrBatched(TileEntitySpecialRenderer<TileEntity> renderer, TileEntity te,
                                            double x, double y, double z, float partialTicks, int destroyStage,
                                            float alpha, Operation<Void> original) {
        TesrBatchRenderer.INSTANCE.beginScope();
        final int blendDepth = GLSMAttribStack.pushState(StateSet.BLEND);
        final Class<?> previousRenderable = TesrAttribution.currentRenderable;
        TesrAttribution.currentRenderable = te == null ? null : te.getClass();
        try {
            PassRebindGate.rebindIfDirty();
            if (TesrProviderDispatch.tryRender(renderer, te, x, y, z)) {
                return;
            }
            if (!AngelicaConfig.enableEntityBatching()) {
                original.call(renderer, te, x, y, z, partialTicks, destroyStage, alpha);
                return;
            }

            final IBatchEligibility holder = (IBatchEligibility) renderer;
            final byte state = holder.angelica$batchState();
            BatchEligibility.begin(state, GLSMDraws.drawCalls);
            try {
                original.call(renderer, te, x, y, z, partialTicks, destroyStage, alpha);
            } finally {
                final byte next = BatchEligibility.end(state, GLSMDraws.drawCalls);
                if (next != state) {
                    holder.angelica$setBatchState(next);
                    BatchEligibility.onStateChange(renderer, next);
                }
            }
        } finally {
            TesrAttribution.currentRenderable = previousRenderable;
            GLSMAttribStack.popStateTo(blendDepth);
            TesrBatchRenderer.INSTANCE.endScope();
        }
    }
}
