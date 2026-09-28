package com.dhj.actinium.mixin.features.iris;

import com.dhj.actinium.compat.mekanism.MekanismGlowBlendCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Routes Mekanism glow passes through the alpha blend state they request. */
@Mixin(targets = "mekanism.client.render.MekanismRenderer", remap = false)
public class MekanismGlowBlendMixin {
    @Inject(
        method = "enableGlow(I)Lmekanism/client/render/MekanismRenderer$GlowInfo;",
        at = @At("RETURN"),
        remap = false
    )
    private static void actinium$beginGlowBlend(int glow, CallbackInfoReturnable<?> cir) {
        MekanismGlowBlendCompat.INSTANCE.begin();
    }

    @Inject(
        method = "disableGlow(Lmekanism/client/render/MekanismRenderer$GlowInfo;)V",
        at = @At("RETURN"),
        remap = false
    )
    private static void actinium$endGlowBlend(CallbackInfo ci) {
        MekanismGlowBlendCompat.INSTANCE.end();
    }
}
