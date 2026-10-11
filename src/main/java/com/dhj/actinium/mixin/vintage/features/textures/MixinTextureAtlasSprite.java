package com.dhj.actinium.mixin.vintage.features.textures;

import com.dhj.actinium.texture.SpriteExtension;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dhj.embeddedt.embeddium.impl.texture.SpritePadding;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(TextureAtlasSprite.class)
public abstract class MixinTextureAtlasSprite implements SpriteExtension {
    private boolean celeritas$isActive = false;

    @Unique
    private int angelica$gutterWidth;

    @Override
    public void celeritas$markActive() {
        this.celeritas$isActive = true;
    }

    @Override
    public boolean celeritas$shouldUpdate() {
        if (this.celeritas$isActive) {
            this.celeritas$isActive = false;
            return true;
        } else {
            return false;
        }
    }

    @ModifyReturnValue(method = { "getMinU", "getInterpolatedU" }, at = @At("RETURN"))
    private float markActiveWhenGettingCoords(float original) {
        this.celeritas$isActive = true;
        return original;
    }

    /**
     * The stitcher hands out slot origins, but the sprite's content sits one gutter inside its slot once the
     * slot has been widened for {@link SpritePadding}, so the UVs have to come from the inset origin.
     */
    @ModifyVariable(method = "initSprite", at = @At("HEAD"), ordinal = 2, argsOnly = true)
    private int angelica$insetOriginX(int x) {
        return x + this.angelica$gutterWidth;
    }

    @ModifyVariable(method = "initSprite", at = @At("HEAD"), ordinal = 3, argsOnly = true)
    private int angelica$insetOriginY(int y) {
        return y + this.angelica$gutterWidth;
    }

    /**
     * Animated sprites re-upload every frame from the atlas origin, so they need the same replicated border
     * as the initial upload.
     */
    @Redirect(
        method = { "updateAnimation", "updateAnimationInterpolated" },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureUtil;uploadTextureMipmap([[IIIIIZZ)V"))
    private void angelica$uploadPaddedFrame(int[][] frameData, int width, int height, int originX, int originY,
        boolean blur, boolean clamp) {
        SpritePadding.uploadPadded(frameData, width, height, originX, originY,
            this.angelica$gutterWidth, blur, clamp);
    }

    @Override
    public int angelica$getGutterWidth() {
        return this.angelica$gutterWidth;
    }

    @Override
    public void angelica$setGutterWidth(int gutter) {
        this.angelica$gutterWidth = gutter;
    }
}
