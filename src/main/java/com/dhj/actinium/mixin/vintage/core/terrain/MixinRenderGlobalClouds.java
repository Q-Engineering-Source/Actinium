package com.dhj.actinium.mixin.vintage.core.terrain;

import com.gtnewhorizons.angelica.render.CloudRenderer;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderGlobal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes {@code RenderGlobal.renderClouds} to {@link CloudRenderer}.
 *
 * <p>The injection sits at the head of the method and cancels only when the renderer owns the pass. A Forge
 * cloud handler that draws its own clouds leaves the vanilla body in place, so FML keeps its first chance to
 * run that handler before anything else and the custom renderer still draws its clouds.</p>
 */
@Mixin(RenderGlobal.class)
public class MixinRenderGlobalClouds {

    @Shadow
    private WorldClient world;
    @Shadow
    private int cloudTickCounter;

    @Inject(method = "renderClouds(FIDDD)V", at = @At("HEAD"), cancellable = true)
    private void actinium$renderClouds(float partialTicks, int pass, double x, double y, double z, CallbackInfo ci) {
        if (CloudRenderer.getCloudRenderer().render(cloudTickCounter, partialTicks, world)) {
            ci.cancel();
        }
    }
}
