package com.dhj.actinium.mixin.mod.hbm;

import com.dhj.actinium.compat.hbm.HbmWeaponDepthCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps HBM's Sedna weapon renderers from clearing the depth buffer inside the shader hand pass.
 *
 * <p>Answering the two probes with the shader pipeline's own state instead of OptiFine's
 * absent one makes the renderer keep the world depth and compress the weapon projection
 * instead (see {@link HbmWeaponDepthCompat}).
 */
@Mixin(targets = "com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase", remap = false)
public abstract class MixinItemRenderWeaponBase {
    @Redirect(
        method = "setPerspectiveAndRender(Lnet/minecraft/item/ItemStack;F)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hbm/util/ShaderHelper;areShadersActive()Z"
        ),
        remap = false
    )
    private boolean actinium$keepWorldDepthInShaderHandPass() {
        return HbmWeaponDepthCompat.shouldSkipWeaponDepthClear();
    }

    @Redirect(
        method = "setPerspectiveAndRender(Lnet/minecraft/item/ItemStack;F)V",
        at = @At(
            value = "INVOKE",
            target = "Lcom/hbm/util/ShaderHelper;applyHandDepth()V"
        ),
        remap = false
    )
    private void actinium$applyShaderHandDepth() {
        HbmWeaponDepthCompat.applyShaderHandDepth();
    }
}
