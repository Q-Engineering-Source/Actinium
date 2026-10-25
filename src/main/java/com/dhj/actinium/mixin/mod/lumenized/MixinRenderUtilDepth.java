package com.dhj.actinium.mixin.mod.lumenized;

import gregtech.client.utils.RenderUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * The vanilla main framebuffer uses a plain GL_DEPTH_COMPONENT24 renderbuffer; the
 * non-stencil branch of Lumenized's hookDepthBuffer binds it to GL_DEPTH_STENCIL_ATTACHMENT,
 * which does not match its format and leaves the bloom FBO without a usable depth
 * attachment, so every bloom pass draws through walls. Re-binding the same renderbuffer to
 * GL_DEPTH_ATTACHMENT (the format it actually has) is idempotent with the first bind in the
 * method and makes depth testing work.
 */
@Mixin(value = RenderUtil.class, remap = false)
public abstract class MixinRenderUtilDepth {
    @ModifyArg(
        method = "hookDepthBuffer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/OpenGlHelper;glFramebufferRenderbuffer(IIII)V",
            ordinal = 2,
            remap = true
        ),
        index = 1,
        remap = false
    )
    private static int actinium$useMatchingDepthAttachment(int attachment) {
        return 36096;
    }
}
