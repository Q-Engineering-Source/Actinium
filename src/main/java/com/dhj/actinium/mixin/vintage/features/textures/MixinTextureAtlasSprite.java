package com.dhj.actinium.mixin.vintage.features.textures;

import com.dhj.actinium.texture.SpriteExtension;
import com.dhj.actinium.texture.atlas.AtlasLoadSession;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dhj.embeddedt.embeddium.impl.texture.SpritePadding;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

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

    /**
     * Takes the image a worker decoded for this sprite instead of decoding it here again, and while the
     * session is still waiting for one registers this call as the decoding function its workers should use.
     */
    @WrapOperation(
        method = "loadSpriteFrames",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureUtil;readBufferedImage(Ljava/io/InputStream;)Ljava/awt/image/BufferedImage;"))
    private BufferedImage angelica$takePrefetchedImage(InputStream stream, Operation<BufferedImage> original)
        throws IOException {
        final AtlasLoadSession session = AtlasLoadSession.active();
        if (session == null) {
            return original.call(stream);
        }

        final BufferedImage prefetched = session.takeImage((TextureAtlasSprite) (Object) this, stream);
        if (prefetched != null) {
            return prefetched;
        }

        final BufferedImage image = original.call(stream);
        if (session.wantsDecoder()) {
            session.onMainDecode(prefetchedStream -> original.call(prefetchedStream));
        }
        return image;
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
