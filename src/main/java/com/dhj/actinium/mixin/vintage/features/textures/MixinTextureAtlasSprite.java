package com.dhj.actinium.mixin.vintage.features.textures;

import com.dhj.actinium.texture.SpriteExtension;
import com.dhj.actinium.texture.SpriteInterpolation;
import com.dhj.actinium.texture.atlas.AtlasLoadSession;
import dhj.embeddedt.embeddium.api.texture.AlphaIndependentSprite;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dhj.embeddedt.embeddium.impl.texture.SpritePadding;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.data.AnimationMetadataSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Mixin(TextureAtlasSprite.class)
public abstract class MixinTextureAtlasSprite implements SpriteExtension {
    @Shadow protected List<int[][]> framesTextureData;
    @Shadow protected int[][] interpolatedFrameData;
    @Shadow private AnimationMetadataSection animationMetadata;
    @Shadow protected int frameCounter;
    @Shadow protected int tickCounter;
    @Shadow protected int width;
    @Shadow protected int height;
    @Shadow protected int originX;
    @Shadow protected int originY;

    @Unique
    private boolean celeritas$isActive = false;

    @Unique
    private int angelica$gutterWidth;

    /**
     * @author Actinium
     * @reason Preserve animated alpha and keep invisible frame colors out of visible pixels.
     */
    @Overwrite
    private void updateAnimationInterpolated() {
        final SpriteInterpolation.Mode mode = this instanceof AlphaIndependentSprite
                ? SpriteInterpolation.Mode.INDEPENDENT_CHANNELS
                : SpriteInterpolation.Mode.ALPHA_WEIGHTED_RGB;
        int[][] interpolated = SpriteInterpolation.interpolate(framesTextureData, animationMetadata,
                frameCounter, tickCounter, interpolatedFrameData, mode);
        if (interpolated != null) {
            interpolatedFrameData = interpolated;
            SpritePadding.uploadPadded(interpolated, width, height, originX, originY,
                    this.angelica$gutterWidth, false, false);
        }
    }

    @ModifyVariable(method = "initSprite", at = @At("HEAD"), ordinal = 2, argsOnly = true)
    private int angelica$insetOriginX(int x) {
        return x + this.angelica$gutterWidth;
    }

    @ModifyVariable(method = "initSprite", at = @At("HEAD"), ordinal = 3, argsOnly = true)
    private int angelica$insetOriginY(int y) {
        return y + this.angelica$gutterWidth;
    }

    /** Animated sprites upload frames again after stitching and must keep the same padded layout. */
    @Redirect(
        method = "updateAnimation",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureUtil;uploadTextureMipmap([[IIIIIZZ)V"))
    private void angelica$uploadPaddedFrame(int[][] frameData, int width, int height, int originX, int originY,
        boolean blur, boolean clamp) {
        SpritePadding.uploadPadded(frameData, width, height, originX, originY,
                this.angelica$gutterWidth, blur, clamp);
    }

    /** Reuses decoded image data produced by the atlas prefetch lane. */
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

        final BufferedImage prefetched = session.takeImage((TextureAtlasSprite) (Object) this);
        if (prefetched != null) {
            return prefetched;
        }

        final BufferedImage image = original.call(stream);
        if (session.wantsDecoder()) {
            session.onMainDecode(prefetchedStream -> original.call(prefetchedStream));
        }
        return image;
    }

    /** Releases a prefetched BufferedImage after vanilla copies it into the sprite's frame data. */
    @Inject(method = "loadSpriteFrames", at = @At("RETURN"))
    private void angelica$releasePrefetchedImage(IResource resource, int mipLevels, CallbackInfo ci) {
        final AtlasLoadSession session = AtlasLoadSession.active();
        if (session != null) {
            session.finishImageLoad((TextureAtlasSprite) (Object) this);
        }
    }

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

    @Override
    public int angelica$getGutterWidth() {
        return this.angelica$gutterWidth;
    }

    @Override
    public void angelica$setGutterWidth(int gutter) {
        this.angelica$gutterWidth = gutter;
    }
}
