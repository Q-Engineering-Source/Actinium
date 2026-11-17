package com.dhj.actinium.mixin.features.tesr;

import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.rendering.tesr.BatchEligibility;
import com.gtnewhorizons.angelica.rendering.tesr.ModelPartBatcher;
import net.minecraft.client.model.ModelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Feeds the cube geometry of a model part to the entity batch instead of the compiled display list.
 *
 * <p>The call site is the display list draw, which already runs after the part's translate and
 * rotation, so the batch places the part with the modelview the vanilla code built. When the batch
 * cannot take the part, the recorded draw count tells {@code BatchEligibility} that this renderer
 * mixes immediate draws with batched parts.</p>
 *
 * <p>{@code ModelRendererIrisMixin} replaces the whole body of {@code render} and
 * {@code renderWithRotation} when a switch makes it responsible for the part, in which case this
 * redirect never runs. Only one of the two paths draws a given part.</p>
 */
@Mixin(value = ModelRenderer.class, priority = 1500)
public abstract class MixinModelRenderer {

    @Redirect(
        method = {"render", "renderWithRotation"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;callList(I)V")
    )
    private void actinium$batchPart(int list, float scale) {
        if (!ModelPartBatcher.partDraw((ModelRenderer) (Object) this, scale, null)) {
            final long drawsBefore = GLSMDraws.drawCalls;
            GLSMDraws.glCallList(list);
            BatchEligibility.onPartFallback(drawsBefore, GLSMDraws.drawCalls);
        }
    }
}
