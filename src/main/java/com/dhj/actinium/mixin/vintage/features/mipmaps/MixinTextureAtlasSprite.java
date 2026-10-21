package com.dhj.actinium.mixin.vintage.features.mipmaps;

import com.dhj.actinium.texture.SpriteExtension;
import com.dhj.actinium.texture.atlas.AtlasLoadSession;
import com.dhj.actinium.texture.atlas.AtlasLoadStats;
import com.dhj.actinium.texture.mipmap.MipmapGenerator;
import com.dhj.actinium.texture.mipmap.MipmapStrategies;
import com.dhj.actinium.texture.mipmap.MipmapStrategy;
import com.dhj.actinium.texture.mipmap.SpriteMipmapExtension;
import com.dhj.actinium.texture.mipmap.SpriteMipmaps;
import com.dhj.actinium.texture.mipmap.TextureMetadataExtension;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
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

/** Classifies sprite transparency and selects the configured mipmap strategy. */
@Mixin(TextureAtlasSprite.class)
public abstract class MixinTextureAtlasSprite implements SpriteExtension, SpriteMipmapExtension,
    SpriteTransparencyLevel.Holder {

    @Unique
    private static final String METADATA_TEXTURE = "texture";

    @Shadow
    protected List<int[][]> framesTextureData;

    @Shadow
    @Final
    private String iconName;

    @Unique
    private boolean celeritas$isActive;

    @Unique
    private SpriteTransparencyLevel embeddium$transparencyLevel = SpriteTransparencyLevel.TRANSLUCENT;

    @Unique
    private MipmapStrategy celeritas$mipmapStrategy;

    @Unique
    private boolean celeritas$mipmapStrategyExplicit;

    @Unique
    private boolean celeritas$hasTransparentTexels;

    @Unique
    private MipmapStrategy celeritas$generatedMipmapStrategy;

    @Inject(method = "generateMipmaps", at = @At("HEAD"))
    private void celeritas$processSprite(int mipLevel, CallbackInfo ci) {
        final SpriteMipmaps.Transparency transparency = SpriteMipmaps.analyzeForGeneration(
            this.framesTextureData, mipLevel, this.iconName);
        this.celeritas$hasTransparentTexels = transparency.hasTransparentTexels();
        this.embeddium$transparencyLevel = transparency.level();
        this.celeritas$generatedMipmapStrategy = MipmapGenerator.effectiveStrategy(
            celeritas$resolvedStrategy(), this.celeritas$hasTransparentTexels);

        if (this.framesTextureData == null) {
            return;
        }

        // Report calls made by a mod's custom loader separately from sprites the atlas session planned.
        final AtlasLoadSession session = AtlasLoadSession.active();
        if (session != null && !session.expects((TextureAtlasSprite) (Object) this)) {
            AtlasLoadStats.recordForeignMipmapCall();
        }
    }

    /** Captures mcmeta or name-based policy before mip levels are generated. */
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

    /** Resolves a requested strategy against cutout pixels and the current atlas. */
    @Unique
    private MipmapStrategy celeritas$resolvedStrategy() {
        return SpriteMipmaps.resolve(this.celeritas$mipmapStrategy, this.celeritas$mipmapStrategyExplicit,
            this.celeritas$hasTransparentTexels);
    }

    /** Replaces vanilla's mip generator with the atlas-aware strategy chain. */
    @Redirect(
        method = "generateMipmaps",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureUtil;generateMipmapData(II[[I)[[I"))
    private int[][] celeritas$generateMipmapData(int mipLevel, int width, int[][] currentMips) {
        return MipmapGenerator.generateMipLevels(mipLevel, width, currentMips,
            this.celeritas$generatedMipmapStrategy, this.celeritas$hasTransparentTexels);
    }

    @Override
    public MipmapStrategy celeritas$generatedMipmapStrategy() {
        return this.celeritas$generatedMipmapStrategy;
    }

    @Override
    public SpriteTransparencyLevel embeddium$getTransparencyLevel() {
        return this.embeddium$transparencyLevel;
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
        }
        return false;
    }

    @ModifyReturnValue(method = {"getMinU", "getInterpolatedU"}, at = @At("RETURN"))
    private float celeritas$markActiveWhenGettingCoords(float original) {
        this.celeritas$isActive = true;
        return original;
    }
}
