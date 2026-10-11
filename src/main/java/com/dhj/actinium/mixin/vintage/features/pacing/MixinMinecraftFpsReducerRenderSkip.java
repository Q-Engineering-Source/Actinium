package com.dhj.actinium.mixin.vintage.features.pacing;

import com.dhj.actinium.render.pacing.FpsReducer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the world render while the reducer considers the window minimized.
 *
 * <p>A minimized window still costs a full frame of world rendering for an image nobody can see.
 * Skipping the render leaves the loop intact, so the client keeps ticking and pumping input at the
 * reducer's minimized frame rate; the pause-on-lost-focus check runs from the skip path instead.
 */
@Mixin(Minecraft.class)
public class MixinMinecraftFpsReducerRenderSkip {

    @Inject(
            method = "runGameLoop",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/EntityRenderer;updateCameraAndRender(FJ)V"
            ),
            cancellable = true
    )
    private void actinium$skipRenderWhenMinimized(CallbackInfo ci) {
        if (FpsReducer.skipRender()) {
            FpsReducer.onRenderSkipped();
            ci.cancel();
        }
    }
}
