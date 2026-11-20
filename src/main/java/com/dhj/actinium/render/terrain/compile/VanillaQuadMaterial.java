package com.dhj.actinium.render.terrain.compile;

import dhj.embeddedt.embeddium.api.shader.buffer.VanillaQuadContext;
import dhj.embeddedt.embeddium.api.util.ColorABGR;
import dhj.embeddedt.embeddium.impl.model.quad.properties.ModelQuadFacing;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import dhj.embeddedt.embeddium.impl.render.chunk.sprite.SpriteTransparencyLevel;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexEncoder;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.BlockRenderLayer;

/** Preserves the source-layer draw order of overlays emitted by the vanilla chunk buffer. */
final class VanillaQuadMaterial {
    private final Material material;
    private final RenderPassConfiguration<?> configuration;
    private final boolean optimize;
    private VanillaQuadContext block;
    private int facesAtBaseMaterial;

    VanillaQuadMaterial(Material material, RenderPassConfiguration<?> configuration, boolean optimize) {
        this.material = material;
        this.configuration = configuration;
        this.optimize = optimize;
    }

    Material choose(VanillaQuadContext context, ModelQuadFacing face, TextureAtlasSprite sprite,
                    ChunkVertexEncoder.Vertex[] quad) {
        SpriteTransparencyLevel transparency = null;
        if (this.optimize && this.material != this.configuration.defaultSolidMaterial()
                && (context == null || context.allowRenderPassOptimization())
                && sprite != null && sprite.getClass() == TextureAtlasSprite.class && !sprite.hasAnimationMetadata()) {
            transparency = SpriteTransparencyLevel.Holder.getTransparencyLevel(sprite);
        }
        return this.selectForFace(context, face, transparency, quad);
    }

    Material selectForFace(VanillaQuadContext context, ModelQuadFacing face, SpriteTransparencyLevel transparency,
                           ChunkVertexEncoder.Vertex[] quad) {
        if (!this.optimize || this.material == this.configuration.defaultSolidMaterial()
                || context != null && !context.allowRenderPassOptimization()) {
            return this.material;
        }
        if (context == null || !sameBlock(this.block, context)) {
            this.facesAtBaseMaterial = 0;
        }
        this.block = context;

        final int faceBit = 1 << face.ordinal();
        if ((this.facesAtBaseMaterial & faceBit) != 0) {
            return this.material;
        }

        final Material selected = select(this.material, this.configuration, transparency, quad);
        if (selected == this.material) {
            this.facesAtBaseMaterial |= faceBit;
        }
        return selected;
    }

    private static boolean sameBlock(VanillaQuadContext first, VanillaQuadContext second) {
        return first != null && (first == second || first.localPosX() == second.localPosX()
                && first.localPosY() == second.localPosY() && first.localPosZ() == second.localPosZ());
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

            // Use one earlier pass so opaque overlays cannot move ahead of alpha-tested base quads.
            return transparency == SpriteTransparencyLevel.OPAQUE || transparency == SpriteTransparencyLevel.TRANSPARENT
                    ? configuration.defaultCutoutMippedMaterial() : material;
        }

        return transparency == SpriteTransparencyLevel.OPAQUE ? configuration.defaultSolidMaterial() : material;
    }
}
