package com.dhj.actinium.mixin.vintage.features.mipmaps;

import com.dhj.actinium.mixin.vintage.features.mipmaps.mixinterface.TextureMetadataExtension;
import com.dhj.actinium.texture.SpriteExtension;
import com.dhj.actinium.texture.atlas.AtlasLoadStats;
import com.dhj.actinium.texture.mipmap.MipmapGenerator;
import com.dhj.actinium.texture.mipmap.MipmapStrategies;
import com.dhj.actinium.texture.mipmap.MipmapStrategy;
import com.dhj.actinium.texture.mipmap.SpriteMipmaps;
import dhj.embeddedt.embeddium.impl.render.chunk.sprite.SpriteTransparencyLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.data.TextureMetadataSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Classifies a sprite's transparency and routes its mipmap generation through {@link MipmapGenerator}.
 *
 * <p>The strategy arrives in two ways: a {@code mipmap_strategy} key on the sprite's {@code .mcmeta}, or the
 * name-based fallback in {@link MipmapStrategies}. Both are read when the sprite's frames are loaded, which is
 * the last moment before generation that has the sprite and its resource together.
 */
@Mixin(TextureAtlasSprite.class)
public abstract class MixinTextureAtlasSprite implements SpriteExtension, SpriteTransparencyLevel.Holder {

    @Unique
    private static final String METADATA_TEXTURE = "texture";

    @Shadow
    protected List<int[][]> framesTextureData;

    @Shadow
    @Final
    private String iconName;

    @Unique
    private SpriteTransparencyLevel embeddium$transparencyLevel = SpriteTransparencyLevel.TRANSLUCENT;

    @Unique
    private MipmapStrategy celeritas$mipmapStrategy;

    @Unique
    private boolean celeritas$mipmapStrategyExplicit;

    @Unique
    private boolean celeritas$hasTransparentTexels;

    @Inject(method = "generateMipmaps", at = @At("HEAD"))
    private void celeritas$processSprite(int mipLevel, CallbackInfo ci) {
        final SpriteMipmaps.Transparency transparency = SpriteMipmaps.scan(this.framesTextureData);
        this.embeddium$transparencyLevel = transparency.level();
        this.celeritas$hasTransparentTexels = transparency.hasTransparentTexels();
        // The atlas build report counts the mode each sprite's levels were really generated with, which is
        // what makes the ported strategies visible as "these sprites got CUTOUT, those got MEAN".
        AtlasLoadStats.recordStrategy(
            MipmapGenerator.effectiveStrategy(celeritas$resolvedStrategy(), this.celeritas$hasTransparentTexels));
    }

    /**
     * Reads this sprite's mipmap strategy, before its levels are built.
     *
     * <p>{@code TextureMap.generateMipmaps} fetches the sprite's resource, hands it to {@code loadSpriteFrames}
     * and only then calls {@code generateMipmaps}, so this is the one hook that has both the sprite and its
     * resource, on the main thread, ahead of generation. The level-building call itself is offloaded to a
     * worker by the parallel atlas load, so it is too late to read metadata there.
     *
     * <p>A malformed {@code texture} section makes {@code getMetadata} throw, and the atlas load then fails.
     * That matches the fail-fast policy: the section is normally only read for standalone textures, so this is
     * the first place a broken one would be noticed for an atlas sprite.
     */
    @Inject(method = "loadSpriteFrames", at = @At("HEAD"))
    private void celeritas$assignMipmapStrategy(IResource resource, int mipLevels, CallbackInfo ci) {
        final MipmapStrategy declared = celeritas$declaredStrategy(resource);
        if (declared != null) {
            this.celeritas$mipmapStrategy = declared;
            this.celeritas$mipmapStrategyExplicit = true;
            return;
        }

        this.celeritas$mipmapStrategy = MipmapStrategies.strategyFor(this.iconName);
        this.celeritas$mipmapStrategyExplicit = false;
    }

    @Unique
    private static MipmapStrategy celeritas$declaredStrategy(IResource resource) {
        if (resource == null) {
            return null;
        }
        final TextureMetadataSection section = resource.getMetadata(METADATA_TEXTURE);
        if (section == null) {
            return null;
        }
        return ((TextureMetadataExtension) section).angelica$getMipmapStrategy();
    }

    /** The strategy to generate with, or null to let the generator resolve it from the transparency scan. */
    @Unique
    private MipmapStrategy celeritas$resolvedStrategy() {
        return SpriteMipmaps.resolve(this.celeritas$mipmapStrategy, this.celeritas$mipmapStrategyExplicit,
            this.celeritas$hasTransparentTexels);
    }

    /** Vanilla's only level generator; replacing it here is what retires {@code TextureUtil.blendColors}. */
    @Redirect(
        method = "generateMipmaps",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureUtil;generateMipmapData(II[[I)[[I"))
    private int[][] celeritas$generateMipmapData(int mipLevel, int width, int[][] currentMips) {
        return MipmapGenerator.generateMipLevels(mipLevel, width, currentMips,
            celeritas$resolvedStrategy(), this.celeritas$hasTransparentTexels);
    }

    @Override
    public SpriteTransparencyLevel embeddium$getTransparencyLevel() {
        return this.embeddium$transparencyLevel;
    }
}
