package com.dhj.actinium.render.terrain.deferred;

import dhj.embeddedt.embeddium.api.shader.BlockRenderLayer;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkMeshFormats;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Pins the material a captured deferred block is encoded with: a block that a shader pack moved onto
 * another layer keeps the material that was recorded for that layer, while a block that stayed on
 * its own layers and a captured fluid keep the material of the layer being captured.
 *
 * <p>The pass configuration here is the real one the mesher reads, built from real
 * {@link TerrainRenderPass} and {@link Material} instances; only the layers this behavior depends on
 * are filled in.</p>
 */
class DeferredBlockMaterialTest {
    private static final Material SOLID = material(pass("solid"), false);
    private static final Material CUTOUT = material(pass("cutout"), true);
    private static final Material CUTOUT_MIPPED = material(pass("cutout_mipped"), true);

    @BeforeAll
    static void bootstrapRegistries() {
        Bootstrap.register();
    }

    @Test
    void aShaderOverrideKeepsTheMaterialRecordedForItsLayer() {
        DeferredBlock shaderOverride = new DeferredBlock(0, 0, 0, Blocks.STONE, 1, CUTOUT);

        assertSame(CUTOUT, DeferredBlock.captureMaterial(shaderOverride, SOLID),
                "the shader pack's material must beat the material of the captured layer");
    }

    @Test
    void aBlockWithoutAnOverrideKeepsTheCapturedLayersMaterial() {
        DeferredBlock plain = new DeferredBlock(0, 0, 0, Blocks.STONE, 1, null);

        assertSame(SOLID, DeferredBlock.captureMaterial(plain, SOLID),
                "a block that stayed on its own layers is encoded with the captured layer's material");
    }

    @Test
    void aCapturedFluidKeepsTheCapturedLayersMaterial() {
        assertSame(SOLID, DeferredBlock.captureMaterial(null, SOLID),
                "a fluid has no deferred block, so it keeps the material of the layer being captured");
    }

    @Test
    void aShaderLayerResolvesToTheMaterialOfItsVanillaLayer() {
        RenderPassConfiguration<net.minecraft.util.BlockRenderLayer> configuration = configuration();

        assertSame(CUTOUT, DeferredBlock.shaderLayerMaterial(configuration, BlockRenderLayer.CUTOUT),
                "the shader layer maps to the pass configuration's vanilla layer key");
        assertSame(CUTOUT_MIPPED, DeferredBlock.shaderLayerMaterial(configuration, BlockRenderLayer.CUTOUT_MIPPED));
    }

    private static RenderPassConfiguration<net.minecraft.util.BlockRenderLayer> configuration() {
        Map<net.minecraft.util.BlockRenderLayer, Material> materials = Map.of(
                net.minecraft.util.BlockRenderLayer.SOLID, SOLID,
                net.minecraft.util.BlockRenderLayer.CUTOUT, CUTOUT,
                net.minecraft.util.BlockRenderLayer.CUTOUT_MIPPED, CUTOUT_MIPPED
        );
        Map<net.minecraft.util.BlockRenderLayer, Collection<TerrainRenderPass>> stages = Map.of(
                net.minecraft.util.BlockRenderLayer.SOLID, List.of(SOLID.pass),
                net.minecraft.util.BlockRenderLayer.CUTOUT, List.of(CUTOUT.pass),
                net.minecraft.util.BlockRenderLayer.CUTOUT_MIPPED, List.of(CUTOUT_MIPPED.pass)
        );

        return new RenderPassConfiguration<>(materials, stages, SOLID, CUTOUT_MIPPED, CUTOUT_MIPPED);
    }

    private static TerrainRenderPass pass(String name) {
        return TerrainRenderPass.builder()
                .name(name)
                .fragmentDiscard(true)
                .primitiveType(QuadPrimitiveType.TRIANGULATED)
                .vertexType(ChunkMeshFormats.COMPACT)
                .build();
    }

    private static Material material(TerrainRenderPass pass, boolean mipped) {
        return new Material(pass, AlphaCutoffParameter.ZERO, mipped);
    }
}
