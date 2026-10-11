package com.dhj.actinium.mixin.vintage.features.pacing;

import com.dhj.actinium.render.pacing.FpsReducer;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.SoundCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps the reducer's master volume reduction applied when the player moves the volume slider.
 *
 * <p>The slider writes the player's own value, which the reducer must remember as the value to
 * scale; the sound engine, however, must be handed the scaled one. Redirecting the hand-off rather
 * than the stored value keeps both views consistent.
 */
@Mixin(GameSettings.class)
public class MixinGameSettingsFpsReducerVolume {

    @Redirect(
            method = "setSoundLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/audio/SoundHandler;setSoundLevel(Lnet/minecraft/util/SoundCategory;F)V"
            )
    )
    private void actinium$applyReducerVolume(SoundHandler handler, SoundCategory category, float level) {
        handler.setSoundLevel(category, category == SoundCategory.MASTER ? FpsReducer.scaleVolume(level) : level);
    }
}
