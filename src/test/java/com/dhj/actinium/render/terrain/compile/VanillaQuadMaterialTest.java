package com.dhj.actinium.render.terrain.compile;

import dhj.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.sprite.SpriteTransparencyLevel;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkMeshFormats;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexEncoder;
import net.minecraft.util.BlockRenderLayer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;

class VanillaQuadMaterialTest {
    private final Material solid = material("solid");
    private final Material cutout = material("cutout");
    private final Material translucent = material("translucent");
    private final RenderPassConfiguration<?> configuration = new RenderPassConfiguration<>(
            Map.of(), Map.of(), solid, cutout, translucent);

    @Test
    void vertexTransparencyPreservesTranslucentMaterialForOpaqueAndCutoutSprites() {
        for (SpriteTransparencyLevel transparency : SpriteTransparencyLevel.values()) {
            for (int vertex = 0; vertex < 4; vertex++) {
                ChunkVertexEncoder.Vertex[] quad = opaqueQuad();
                quad[vertex].color = 0x80FFFFFF;
                assertSame(translucent, VanillaQuadMaterial.select(translucent, configuration, transparency, quad));
            }
        }
    }

    @Test
    void fullyTransparentVerticesAlsoPreserveTranslucentMaterial() {
        ChunkVertexEncoder.Vertex[] quad = opaqueQuad();
        quad[0].color = 0x00FFFFFF;
        assertSame(translucent, VanillaQuadMaterial.select(translucent, configuration, SpriteTransparencyLevel.OPAQUE, quad));
    }

    @Test
    void opaqueVerticesStillAllowSolidAndCutoutOptimizations() {
        assertSame(solid, VanillaQuadMaterial.select(translucent, configuration, SpriteTransparencyLevel.OPAQUE, opaqueQuad()));
        assertSame(cutout, VanillaQuadMaterial.select(translucent, configuration, SpriteTransparencyLevel.TRANSPARENT, opaqueQuad()));
        assertSame(translucent, VanillaQuadMaterial.select(translucent, configuration, SpriteTransparencyLevel.TRANSLUCENT, opaqueQuad()));
        assertSame(solid, VanillaQuadMaterial.select(cutout, configuration, SpriteTransparencyLevel.OPAQUE, opaqueQuad()));
    }

    @Test
    void ineligibleSpritesKeepTheirOriginalMaterial() {
        assertSame(translucent, VanillaQuadMaterial.select(translucent, configuration, null, opaqueQuad()));
        assertSame(cutout, VanillaQuadMaterial.select(cutout, configuration, null, opaqueQuad()));
    }

    @Test
    void fluidQuadsKeepDedicatedFluidMaterialAndOpaqueLayerMaterial() {
        Material fluid = material("fluid");
        RenderPassConfiguration<?> fluidConfiguration = new RenderPassConfiguration<>(
                Map.of(), Map.of(), solid, cutout, translucent, fluid);
        assertSame(fluid, VanillaQuadMaterial.selectFluid(translucent, fluidConfiguration, BlockRenderLayer.TRANSLUCENT));
        assertSame(cutout, VanillaQuadMaterial.selectFluid(cutout, fluidConfiguration, BlockRenderLayer.CUTOUT));
    }

    private static ChunkVertexEncoder.Vertex[] opaqueQuad() {
        ChunkVertexEncoder.Vertex[] quad = ChunkVertexEncoder.Vertex.uninitializedQuad();
        for (ChunkVertexEncoder.Vertex vertex : quad) {
            vertex.color = 0xFFFFFFFF;
        }
        return quad;
    }

    private static Material material(String name) {
        TerrainRenderPass pass = TerrainRenderPass.builder()
                .name(name)
                .primitiveType(QuadPrimitiveType.TRIANGULATED)
                .vertexType(ChunkMeshFormats.COMPACT)
                .build();
        return new Material(pass, AlphaCutoffParameter.ZERO, true);
    }
}
