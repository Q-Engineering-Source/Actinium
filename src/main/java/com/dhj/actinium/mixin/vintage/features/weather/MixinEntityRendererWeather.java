package com.dhj.actinium.mixin.vintage.features.weather;

import com.dhj.actinium.render.WeatherRenderer;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Takes over the vanilla rain, snow and rain particle passes.
 *
 * <p>Both injections only ask the renderer whether it handled the pass. Everything the pass does,
 * including the decision to leave the vanilla path in place for Dynamic Surroundings, lives in
 * {@link WeatherRenderer}.</p>
 */
@Mixin(EntityRenderer.class)
public class MixinEntityRendererWeather {

    @Inject(method = "renderRainSnow(F)V", at = @At("HEAD"), cancellable = true)
    private void actinium$renderRainSnow(float partialTicks, CallbackInfo ci) {
        if (WeatherRenderer.render(partialTicks)) {
            ci.cancel();
        }
    }

    @Inject(method = "addRainParticles()V", at = @At("HEAD"), cancellable = true)
    private void actinium$addRainParticles(CallbackInfo ci) {
        if (WeatherRenderer.spawnRainParticles()) {
            ci.cancel();
        }
    }
}
