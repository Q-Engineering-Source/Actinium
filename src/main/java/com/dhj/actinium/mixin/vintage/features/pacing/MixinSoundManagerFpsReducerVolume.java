package com.dhj.actinium.mixin.vintage.features.pacing;

import com.dhj.actinium.render.pacing.FpsReducer;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps the reducer's master volume reduction applied when the sound engine (re)loads.
 *
 * <p>The engine's loader thread reads the player's master volume straight from the settings and
 * pushes it into the new engine, bypassing both the slider and the reducer. Scaling that read is
 * what stops a resource reload from restoring full volume while the game is in the background.
 */
@Mixin(targets = "net.minecraft.client.audio.SoundManager$1")
public class MixinSoundManagerFpsReducerVolume {

    @ModifyExpressionValue(
            method = "run()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/settings/GameSettings;getSoundLevel(Lnet/minecraft/util/SoundCategory;)F"
            )
    )
    private float actinium$scaleMasterOnReload(float original) {
        return FpsReducer.scaleVolume(original);
    }
}
