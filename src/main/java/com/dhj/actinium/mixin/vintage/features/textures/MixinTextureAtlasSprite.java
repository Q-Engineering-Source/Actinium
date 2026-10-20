package com.dhj.actinium.mixin.vintage.features.textures;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.dhj.actinium.texture.SpriteInterpolation;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.data.AnimationMetadataSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import com.dhj.actinium.texture.SpriteExtension;

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

    private boolean celeritas$isActive = false;

    /**
     * @author Actinium
     * @reason Preserve animated alpha and keep invisible frame colors out of visible pixels.
     */
    @Overwrite
    private void updateAnimationInterpolated() {
        int[][] interpolated = SpriteInterpolation.interpolate(framesTextureData, animationMetadata,
                frameCounter, tickCounter, interpolatedFrameData);
        if (interpolated != null) {
            interpolatedFrameData = interpolated;
            TextureUtil.uploadTextureMipmap(interpolated, width, height, originX, originY, false, false);
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
}
