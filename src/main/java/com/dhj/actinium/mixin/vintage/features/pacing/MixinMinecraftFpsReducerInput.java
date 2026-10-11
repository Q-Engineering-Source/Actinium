package com.dhj.actinium.mixin.vintage.features.pacing;

import com.dhj.actinium.render.pacing.FpsReducer;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Feeds real mouse and keyboard events into the reducer's idle timer.
 *
 * <p>The timer must only be reset by input the player actually produced. Hooking the head of the
 * tick would reset it unconditionally, because the client ticks every frame whether or not anyone
 * is at the keyboard, and the idle state could then never be reached.
 */
@Mixin(Minecraft.class)
public class MixinMinecraftFpsReducerInput {

    @ModifyExpressionValue(
            method = "runTickMouse",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;next()Z", remap = false)
    )
    private boolean actinium$onMouseInput(boolean original) {
        if (original) FpsReducer.onInput();
        return original;
    }

    @ModifyExpressionValue(
            method = "runTickKeyboard",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Keyboard;next()Z", remap = false)
    )
    private boolean actinium$onKeyboardInput(boolean original) {
        if (original) FpsReducer.onInput();
        return original;
    }
}
