package com.dhj.actinium.render.terrain.compile;

import dhj.embeddedt.embeddium.api.util.ColorABGR;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import dhj.embeddedt.embeddium.impl.render.chunk.sprite.SpriteTransparencyLevel;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexEncoder;
import net.minecraft.util.BlockRenderLayer;

final class VanillaQuadMaterial {
    private VanillaQuadMaterial() {
    }

    static Material selectFluid(Material material, RenderPassConfiguration<?> configuration, BlockRenderLayer layer) {
        return layer == BlockRenderLayer.TRANSLUCENT ? configuration.defaultFluidMaterial() : material;
    }

    static Material select(Material material, RenderPassConfiguration<?> configuration,
                           SpriteTransparencyLevel transparency, ChunkVertexEncoder.Vertex[] quad) {
        if (material == configuration.defaultTranslucentMaterial()) {
            for (ChunkVertexEncoder.Vertex vertex : quad) {
                if (ColorABGR.unpackAlpha(vertex.color) != 255) {
                    return material;
                }
            }
        }

        if (transparency == SpriteTransparencyLevel.OPAQUE) {
            return configuration.defaultSolidMaterial();
        }
        if (material == configuration.defaultTranslucentMaterial()
                && transparency == SpriteTransparencyLevel.TRANSPARENT) {
            return configuration.defaultCutoutMippedMaterial();
        }
        return material;
    }
}
