package com.dhj.actinium.mixin.vintage.fpsreducer;

import com.dhj.actinium.render.fps.FpsReducer;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Counts menu input as activity for the idle timer. */
@Mixin(GuiScreen.class)
public class MixinGuiScreenFpsReducerInput {

    @ModifyExpressionValue(
        method = "handleInput",
        at = {
            @At(value = "INVOKE", target = "Lorg/lwjgl/input/Mouse;next()Z", remap = false),
            @At(value = "INVOKE", target = "Lorg/lwjgl/input/Keyboard;next()Z", remap = false)
        }
    )
    private boolean actinium$onInputNext(boolean original) {
        if (original) FpsReducer.onInput();
        return original;
    }
}
