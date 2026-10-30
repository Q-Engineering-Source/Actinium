package com.dhj.actinium.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import dhj.embeddedt.embeddium.impl.util.collections.quadtree.QuadTree;

public interface TextureMapExtension {
    QuadTree<TextureAtlasSprite> celeritas$getQuadTree();

    TextureAtlasSprite celeritas$findFromUV(float u, float v);

    int celeritas$getAtlasWidth();

    int celeritas$getAtlasHeight();
}
