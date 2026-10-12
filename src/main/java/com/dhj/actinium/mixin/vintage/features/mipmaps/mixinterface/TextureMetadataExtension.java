package com.dhj.actinium.mixin.vintage.features.mipmaps.mixinterface;

import com.dhj.actinium.texture.mipmap.MipmapStrategy;

/**
 * The mipmap strategy declared by a sprite's {@code .mcmeta} {@code texture} section.
 *
 * <p>A resource pack needs a way to name the downsampling a specific texture wants, since the built-in
 * fallbacks can only guess from the sprite name. The declaration travels on the parsed metadata section,
 * which is the one object both the serializer and the atlas loader can reach without re-reading the pack.
 */
public interface TextureMetadataExtension {

    /** Sets the strategy read from the {@code mipmap_strategy} key; null when the key is absent or unknown. */
    void angelica$setMipmapStrategy(MipmapStrategy strategy);

    /** The declared strategy, or null when this texture did not declare one. */
    MipmapStrategy angelica$getMipmapStrategy();
}
