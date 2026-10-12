package com.dhj.actinium.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import dhj.embeddedt.embeddium.impl.util.collections.quadtree.QuadTree;

public interface TextureMapExtension {
    QuadTree<TextureAtlasSprite> celeritas$getQuadTree();

    TextureAtlasSprite celeritas$findFromUV(float u, float v);

    int celeritas$getAtlasWidth();

    int celeritas$getAtlasHeight();

    /**
     * The border every sprite of the loaded atlas was padded with, in texels. Zero when padding is off.
     * Sizing a buffer from it is what lets a sampler tap the replicated edge it needs without crossing
     * into the neighbouring sprite.
     */
    int celeritas$getAtlasGutter();
}
