package com.dhj.actinium.mixin.vintage.features.options;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.settings.GameSettings;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.dhj.actinium.runtime.ActiniumRuntime;

@Mixin(EntityRenderer.class)
public class MixinEntityRenderer {
    /**
     * Skips the pass while no view entity exists: renderWorld backfills it from the player, but the
     * world can render for a few frames before the player is assigned, and every consumer in the
     * pass (updateFogColor first) dereferences it without a null check.
     */
    @Inject(method = "renderWorldPass(IFJ)V", at = @At("HEAD"), cancellable = true)
    private void actinium$skipWorldPassWithoutViewEntity(int pass, float partialTicks, long finishTimeNano, CallbackInfo ci) {
        if (Minecraft.getMinecraft().getRenderViewEntity() == null) {
            ci.cancel();
        }
    }

    @Redirect(method = "renderRainSnow", at = @At(value = "FIELD", target = "Lnet/minecraft/client/settings/GameSettings;fancyGraphics:Z", opcode = Opcodes.GETFIELD))
    private boolean redirectGetFancyWeather(GameSettings instance) {
        return ActiniumRuntime.options().quality.weatherQuality.isFancy(Minecraft.getMinecraft().gameSettings.fancyGraphics);
    }
}