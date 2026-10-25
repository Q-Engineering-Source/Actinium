package com.dhj.actinium.mixin.mod.scannable;

import com.dhj.actinium.compat.scannable.ScannableShaderCompat;
import li.cil.scannable.integration.optifine.ProxyOptiFine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes Scannable's shader-mod probes to Actinium (issue #94).
 *
 * <p>Scannable drives its scan-wave rendering off two OptiFine queries. With no OptiFine
 * present it falls back to mode INJECT, which swaps the main framebuffer's depth attachment
 * via the vanilla {@code Framebuffer.depthBuffer} renderbuffer, a buffer
 * {@code FramebufferIrisMixin} never creates (depth lives in a shared texture): the restore
 * detaches the attachment and terrain loses depth testing (see-through ground); with a
 * shader pack the same code additionally churns the gbuffer depth attachment and draws the
 * scan quad into Iris render targets (white screen with ghosting). Answering "shader pack
 * loaded" here selects Scannable's dedicated OPTIFINE path, which copies the provided depth
 * texture into a private FBO after world rendering and never touches the main framebuffer's
 * attachments; {@link ScannableShaderCompat} supplies the shared main-framebuffer depth
 * texture, which carries the world depth both with and without a shader pack.
 */
@Mixin(value = ProxyOptiFine.class, remap = false)
public abstract class MixinProxyOptiFine {

    /**
     * Reports Actinium's pipeline as the active shader pack so Scannable uses its
     * attachment-safe {@code OPTIFINE} path. An OptiFine-provided "yes" passes through
     * untouched; Actinium's environment does not coexist with OptiFine, so in practice this
     * only upgrades the reflected "no".
     */
    @Inject(method = "isShaderPackLoaded()Z", at = @At("RETURN"), cancellable = true)
    private void actinium$reportShaderPipeline(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && ScannableShaderCompat.canProvideDepthTexture()) {
            ScannableShaderCompat.logTakeover();
            cir.setReturnValue(true);
        }
    }

    /**
     * Feeds the shared main-framebuffer depth texture when the reflective OptiFine probe
     * yields none. Returned as-is otherwise, keeping an OptiFine-managed texture authoritative.
     */
    @Inject(method = "getDepthTexture()I", at = @At("RETURN"), cancellable = true)
    private void actinium$provideMainDepthTexture(CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValueI() == 0) {
            cir.setReturnValue(ScannableShaderCompat.mainDepthTextureId());
        }
    }
}
