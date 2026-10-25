package com.dhj.actinium.mixin.mod.lumenized;

import gregtech.client.utils.DepthTextureUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes Lumenized's depth handling to the shared main-framebuffer depth buffer instead of
 * its private depth-texture pass: under Actinium's render stack that pass renders into the
 * wrong framebuffer (the chunk renderer re-binds its own target), so the depth texture stays
 * empty and the bloom draws through everything. Sharing the main framebuffer depth
 * (hookDepthBuffer) uses the real, complete world depth instead.
 */
@Mixin(value = DepthTextureUtil.class, remap = false)
public abstract class MixinDepthTextureUtil {
    @Inject(method = "isUseDefaultFBO", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$forceSharedDepthBuffer(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }

    @Inject(method = "shouldRenderDepthTexture", at = @At("HEAD"), cancellable = true, remap = false)
    private static void actinium$disableDepthTexturePass(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
