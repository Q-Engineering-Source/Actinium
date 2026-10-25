package com.dhj.actinium.mixin.mod.scannable;

import com.dhj.actinium.compat.scannable.ScannableShaderCompat;
import li.cil.scannable.client.renderer.ScannerRenderer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Fixes the depth-copy texture format of Scannable's shader-mod path (issue #94 follow-up):
 * {@code installDepthTexture}'s OPTIFINE branch allocates the depth copy with
 * {@code (GL_R32F, GL_RED, GL_UNSIGNED_BYTE)}, but {@code GL_R32F} only accepts
 * {@code GL_FLOAT} or {@code GL_HALF_FLOAT} as the pixel type, so the allocation fails with
 * GL_INVALID_OPERATION, the copy pass draws into an incomplete framebuffer and the scan wave
 * samples undefined values (mismatched scan area, most visible over wide open surfaces such
 * as grass-bound scans). Switch the pixel type to {@code GL_FLOAT} so the copy actually
 * carries depth; the copy shader already writes plain floats, and {@code GL_R32F} is
 * color-renderable in the core-profile contexts Actinium targets.
 */
@Mixin(value = ScannerRenderer.class, remap = false)
public abstract class MixinScannerRenderer {

    /**
     * Redirects the pixel type of the R32F depth-copy allocation to {@code GL_FLOAT}. Other
     * allocations routed through the same {@code createTexture} helper (the depth textures of the
     * INJECT/RENDER paths) use depth formats with valid types and are left untouched.
     */
    @ModifyArgs(
        method = "installDepthTexture(Lnet/minecraft/client/shader/Framebuffer;)V",
        at = @At(
            value = "INVOKE",
            target = "Lli/cil/scannable/client/renderer/ScannerRenderer;createTexture(IIIII)I"
        )
    )
    private void actinium$fixCopyDepthTextureType(Args args) {
        if ((Integer) args.get(2) == GL30.GL_R32F) {
            ScannableShaderCompat.logCopyTextureFix();
            args.set(4, GL11.GL_FLOAT);
        }
    }
}
