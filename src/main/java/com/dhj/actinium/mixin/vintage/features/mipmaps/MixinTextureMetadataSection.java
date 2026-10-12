package com.dhj.actinium.mixin.vintage.features.mipmaps;

import com.dhj.actinium.mixin.vintage.features.mipmaps.mixinterface.TextureMetadataExtension;
import com.dhj.actinium.texture.mipmap.MipmapStrategy;
import net.minecraft.client.resources.data.TextureMetadataSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Carries the mipmap strategy a sprite's {@code .mcmeta} declared for it. */
@Mixin(TextureMetadataSection.class)
public class MixinTextureMetadataSection implements TextureMetadataExtension {

    @Unique
    private MipmapStrategy angelica$mipmapStrategy;

    @Override
    public void angelica$setMipmapStrategy(MipmapStrategy strategy) {
        this.angelica$mipmapStrategy = strategy;
    }

    @Override
    public MipmapStrategy angelica$getMipmapStrategy() {
        return this.angelica$mipmapStrategy;
    }
}
