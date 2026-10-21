package com.dhj.actinium.texture.mipmap;

/**
 * The mode a sprite's mip levels were really generated with.
 *
 * <p>The atlas build report counts sprites by mode, but the mode only becomes known while the sprite builds
 * its levels, and {@code generateMipmaps} is called by mod sprite loaders as well as by the atlas load. The
 * atlas session therefore reads the answer back off the sprites it prepared, instead of counting generation
 * calls as they arrive, which is what keeps a loader that builds its own levels out of the count.
 */
public interface SpriteMipmapExtension {

    /**
     * The mode {@link MipmapGenerator} built this sprite's levels with, or null when its levels have not been
     * generated yet.
     */
    MipmapStrategy celeritas$generatedMipmapStrategy();
}
