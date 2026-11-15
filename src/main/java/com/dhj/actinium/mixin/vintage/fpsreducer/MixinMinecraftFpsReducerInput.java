package com.dhj.actinium.mixin.vintage.fpsreducer;

import com.dhj.actinium.render.fps.FpsReducer;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Counts game input as activity for the idle timer. Mouse activity is polled by the reducer; this
 * covers the keyboard side, which reaches the game only through {@code runTick}'s event loop.
 */
@Mixin(Minecraft.class)
public class MixinMinecraftFpsReducerInput {

    @ModifyExpressionValue(
        method = "runTick",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Keyboard;next()Z", remap = false)
    )
    private boolean actinium$onInputNext(boolean original) {
        if (original) FpsReducer.onInput();
        return original;
    }
}
