package com.dhj.actinium.mixin.vintage.features.pacing;

import com.dhj.actinium.render.pacing.FpsReducer;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Feeds mouse and keyboard events consumed by an open screen into the reducer's idle timer.
 *
 * <p>While a screen that does not accept direct user input is open the client no longer drains the
 * input queues itself; the screen does. Without this hook a player reading a menu would be treated
 * as idle.
 */
@Mixin(GuiScreen.class)
public class MixinGuiScreenFpsReducerInput {

    @ModifyExpressionValue(
            method = "handleInput",
            at = {
                @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;next()Z", remap = false),
                @At(value = "INVOKE", target = "Lorg/lwjgl/input/Keyboard;next()Z", remap = false)
            }
    )
    private boolean actinium$onScreenInput(boolean original) {
        if (original) FpsReducer.onInput();
        return original;
    }
}
