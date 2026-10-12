package com.dhj.actinium.render.terrain;

import com.google.common.collect.ImmutableListMultimap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.BlockRenderLayer;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.parameters.AlphaCutoffParameter;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexType;
import dhj.embeddedt.embeddium.impl.texture.SpritePadding;
import dhj.embeddedt.embeddium.impl.texture.TextureFilterMode;
import com.dhj.actinium.runtime.ActiniumRuntime;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class VintageRenderPassConfigurationBuilder {

    private static TerrainRenderPass.PipelineState blocksTextureState(boolean mipped) {
        return new TerrainRenderPass.PipelineState() {
            @Override
            public void setup() {
                apply(mipped);
            }

            @Override
            public void clear() {
                apply(mipped);
            }

            private void apply(boolean mippedValue) {
                // Forcefully reset the mipmap state to the expected value for terrain. Mods sometimes manage to corrupt it.
                Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
                ((AbstractTexture) Minecraft.getMinecraft().getTextureManager().getTexture(TextureMap.LOCATION_BLOCKS_TEXTURE)).setBlurMipmapDirect(false, mippedValue);
            }
        };
    }

    private static TerrainRenderPass.TerrainRenderPassBuilder builderForRenderType(BlockRenderLayer chunkRenderType, ChunkVertexType vertexType, boolean mipped) {
        var extraDefines = new HashMap<String, String>();

        if (ActiniumRuntime.options().quality.chunkFadeInDuration > 0) {
            extraDefines.put("CHUNK_FADE_IN_DURATION_MS", String.valueOf(ActiniumRuntime.options().quality.chunkFadeInDuration));
        }

        addFilteringDefines(extraDefines);

        return TerrainRenderPass.builder().extraDefines(extraDefines).pipelineState(blocksTextureState(mipped)).vertexType(vertexType).primitiveType(QuadPrimitiveType.TRIANGULATED);
    }

    /**
     * Adds the texture-filtering defines every terrain pass shares.
     *
     * <p>{@code USE_TEXEL_SNAP} is what selects between the shader picking the level itself and the texture's
     * own filter state doing it, so it has to be set from the same resolution the terrain sampler uses.
     * {@code TERRAIN_GUTTER} is the width of the replicated border the anisotropic taps have to stay inside,
     * and {@code TERRAIN_NO_MIPS} tells the shader there is no mip chain to select from. All of them are sized
     * from the mip level count the block atlas really ended up with, not from the configured one.
     */
    private static void addFilteringDefines(Map<String, String> extraDefines) {
        final int mipmapLevels = TerrainTextureFiltering.terrainMipmapLevels();
        final TextureFilterMode mode = TerrainTextureFiltering.effectiveTextureFilterMode();

        if (mode.usesRgss()) {
            extraDefines.put("USE_RGSS", "");
        }
        if (TerrainTextureFiltering.usesTerrainTexelSnap()) {
            extraDefines.put("USE_TEXEL_SNAP", "");
        }
        if (mode.usesAnisotropy()) {
            extraDefines.put("USE_ANISOTROPIC", "");
            extraDefines.put("TERRAIN_GUTTER", SpritePadding.gutterFor(mipmapLevels, true) + ".0");
        }
        if (mipmapLevels <= 0) {
            extraDefines.put("TERRAIN_NO_MIPS", "");
        }
    }

    public static RenderPassConfiguration<BlockRenderLayer> build(ChunkVertexType vertexType) {
        // Mipmapped sampling is only valid while mipmaps are actually enabled; otherwise the atlas
        // has a single level and the mipmap-tagged materials would sample an incomplete texture and
        // get discarded on alpha-test. The configuration is rebuilt on every resource reload, so this
        // reflects the current mipmap setting.
        boolean mipEnabled = Minecraft.getMinecraft().gameSettings.mipmapLevels > 0;

        // First, build the main passes
        TerrainRenderPass solidPass, cutoutMippedPass, translucentPass, fluidPass;

        solidPass = builderForRenderType(BlockRenderLayer.SOLID, vertexType, mipEnabled)
                .name("solid")
                .fragmentDiscard(false)
                .useReverseOrder(false)
                .semantic(TerrainRenderPass.Semantic.SOLID)
                .writesDepth(true)
                .build();
        cutoutMippedPass = builderForRenderType(BlockRenderLayer.CUTOUT_MIPPED, vertexType, mipEnabled)
                .name("cutout_mipped")
                .fragmentDiscard(true)
                .useReverseOrder(false)
                .semantic(TerrainRenderPass.Semantic.CUTOUT)
                .writesDepth(true)
                .build();
        fluidPass = builderForRenderType(BlockRenderLayer.TRANSLUCENT, vertexType, mipEnabled)
                .name("water")
                .fragmentDiscard(false)
                .useReverseOrder(true)
                .semantic(TerrainRenderPass.Semantic.WATER)
                .writesDepth(true)
                .useTranslucencySorting(ActiniumRuntime.options().performance.useTranslucentFaceSorting)
                .build();
        translucentPass = builderForRenderType(BlockRenderLayer.TRANSLUCENT, vertexType, mipEnabled)
                .name("translucent")
                .fragmentDiscard(false)
                .useReverseOrder(true)
                .semantic(TerrainRenderPass.Semantic.TRANSLUCENT)
                // Vanilla 1.12.2 wraps the whole translucent stage (terrain plus the pass-1
                // tile-entity re-render such as EnderIO tank fluid) in depthMask(false), see
                // EntityRenderer around lines 1539/1564. Writing depth here makes translucent
                // terrain (e.g. the tank glass) occlude pass-1 TESR geometry drawn afterwards,
                // which hid EnderIO tank fluid on both the fixed-function and shader paths (#58).
                // The earlier flip to true (64b9c32/da83c59) rested on the incorrect premise
                // that vanilla keeps the depth mask on for translucent terrain.
                .writesDepth(false)
                .useTranslucencySorting(ActiniumRuntime.options().performance.useTranslucentFaceSorting)
                .build();

        ImmutableListMultimap.Builder<BlockRenderLayer, TerrainRenderPass> vanillaRenderStages = ImmutableListMultimap.builder();

        // Build the materials for the vanilla render passes
        Material solidMaterial, cutoutMaterial, cutoutMippedMaterial, translucentMaterial, fluidMaterial;
        solidMaterial = new Material(solidPass, AlphaCutoffParameter.ZERO, mipEnabled);
        translucentMaterial = new Material(translucentPass, AlphaCutoffParameter.ZERO, mipEnabled);
        fluidMaterial = new Material(fluidPass, AlphaCutoffParameter.ZERO, mipEnabled);
        // The mip levels of a cutout sprite are alpha-rescaled to keep the base level's tested coverage, and
        // that calibration is done against a half-alpha reference, so the cutoff has to match it. The plain
        // cutout layer has no such rescaling and keeps vanilla's tenth.
        cutoutMippedMaterial = new Material(cutoutMippedPass, AlphaCutoffParameter.HALF, mipEnabled);

        vanillaRenderStages.put(BlockRenderLayer.SOLID, solidPass);
        vanillaRenderStages.put(BlockRenderLayer.TRANSLUCENT, fluidPass);
        vanillaRenderStages.put(BlockRenderLayer.TRANSLUCENT, translucentPass);

        if (ActiniumRuntime.options().performance.useRenderPassConsolidation) {
            // The cutout layer does not use the mip chain in vanilla, so it carries a large negative mip bias
            // and lands on level 0.
            cutoutMaterial = new Material(cutoutMippedPass, AlphaCutoffParameter.ONE_TENTH, false);
            vanillaRenderStages.put(BlockRenderLayer.CUTOUT, cutoutMippedPass);
            vanillaRenderStages.put(BlockRenderLayer.CUTOUT_MIPPED, cutoutMippedPass);
        } else {
            TerrainRenderPass cutoutPass;

            cutoutPass = builderForRenderType(BlockRenderLayer.CUTOUT, vertexType, mipEnabled)
                    .name("cutout")
                    .fragmentDiscard(true)
                    .useReverseOrder(false)
                    .semantic(TerrainRenderPass.Semantic.CUTOUT)
                    .writesDepth(true)
                    .build();

            cutoutMaterial = new Material(cutoutPass, AlphaCutoffParameter.ONE_TENTH, false);
            vanillaRenderStages.put(BlockRenderLayer.CUTOUT, cutoutPass);
            vanillaRenderStages.put(BlockRenderLayer.CUTOUT_MIPPED, cutoutMippedPass);
        }

        // Now build the material map
        Map<BlockRenderLayer, Material> renderTypeToMaterialMap = new Reference2ReferenceOpenHashMap<>(4,
                Reference2ReferenceOpenHashMap.VERY_FAST_LOAD_FACTOR);

        renderTypeToMaterialMap.put(BlockRenderLayer.SOLID, solidMaterial);
        renderTypeToMaterialMap.put(BlockRenderLayer.CUTOUT, cutoutMaterial);
        renderTypeToMaterialMap.put(BlockRenderLayer.CUTOUT_MIPPED, cutoutMippedMaterial);
        renderTypeToMaterialMap.put(BlockRenderLayer.TRANSLUCENT, translucentMaterial);

        for (BlockRenderLayer layer : BlockRenderLayer.values()) {
            if (!renderTypeToMaterialMap.containsKey(layer)) {
                ActiniumRuntime.logger().warn("Falling back to cutout-like behavior for custom block render layer '{}'", layer);
                TerrainRenderPass pass = builderForRenderType(layer, vertexType, mipEnabled).name(layer.name().toLowerCase(Locale.ROOT)).fragmentDiscard(true).useReverseOrder(false).semantic(TerrainRenderPass.Semantic.CUTOUT).writesDepth(true).build();
                Material material = new Material(pass, AlphaCutoffParameter.ONE_TENTH, mipEnabled);
                vanillaRenderStages.put(layer, pass);
                renderTypeToMaterialMap.put(layer, material);
            }
        }

        var vanillaRenderStageMap = vanillaRenderStages.build();

        logFiltering(mipEnabled);

        return new RenderPassConfiguration<>(renderTypeToMaterialMap,
                vanillaRenderStageMap.asMap(),
                solidMaterial,
                cutoutMippedMaterial,
                translucentMaterial,
                fluidMaterial);
    }

    /**
     * Reports the terrain filtering this build applied. The configuration is rebuilt on every resource
     * reload and on entering a world, which is exactly when a changed filtering or mipmap option - or a
     * different driver's anisotropy support - takes effect, so this line is the filtering mode's switch log.
     */
    private static void logFiltering(boolean mipEnabled) {
        final TextureFilterMode mode = TerrainTextureFiltering.effectiveTextureFilterMode();
        final int mipmapLevels = TerrainTextureFiltering.terrainMipmapLevels();
        ActiniumRuntime.logger().info(
            "Terrain texture filtering: mode={} anisotropy={}x texelSnap={} rgss={} mipmaps={} atlasMipLevels={} gutter={} mipEnabled={}",
            mode, TerrainTextureFiltering.resolvedAnisotropicFiltering(), TerrainTextureFiltering.usesTerrainTexelSnap(),
            mode.usesRgss(), mipmapLevels > 0, mipmapLevels, SpritePadding.gutterFor(mipmapLevels, true), mipEnabled);
    }
}

