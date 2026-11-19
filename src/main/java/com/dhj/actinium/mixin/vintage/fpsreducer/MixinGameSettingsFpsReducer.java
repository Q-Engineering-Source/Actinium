package com.dhj.actinium.mixin.vintage.fpsreducer;

import com.dhj.actinium.render.fps.FpsReducer;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.SoundCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Scales the master volume the game hands to the sound handler, so the reducer can lower it without
 * touching the value stored in {@link GameSettings}.
 */
@Mixin(GameSettings.class)
public class MixinGameSettingsFpsReducer {

    @Redirect(
        method = "setSoundLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/audio/SoundHandler;setSoundLevel(Lnet/minecraft/util/SoundCategory;F)V"
        )
    )
    private void actinium$scaleMasterVolume(SoundHandler handler, SoundCategory category, float level) {
        handler.setSoundLevel(category, category == SoundCategory.MASTER ? FpsReducer.scaleVolume(level) : level);
    }
}
