package com.dhj.actinium.mixin.vintage.core.crash;

import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = {"net/minecraftforge/fml/client/SplashProgress$1"})
public class MixinSplashProgressCallable {
    @Inject(method = "call()Ljava/lang/String;", at = @At("HEAD"), cancellable = true)
    private void checkContext(CallbackInfoReturnable<String> cir) {
        boolean isContextAvailable;
        try {
            // Asks Display whether this thread has a live GL context. Reading it off a drawable
            // (Display.getDrawable().isCurrent()) is not portable: a host running LWJGLY owns the
            // context itself and hands out no LWJGL2 drawable, so that call would NPE and report
            // "no context" even in a working game, hiding the GL info in every crash report.
            isContextAvailable = Display.isCreated() && Display.isCurrent();
        } catch (Exception e) {
            isContextAvailable = false;
        }
        if (!isContextAvailable) {
            cir.setReturnValue("No context available");
        }
    }
}
