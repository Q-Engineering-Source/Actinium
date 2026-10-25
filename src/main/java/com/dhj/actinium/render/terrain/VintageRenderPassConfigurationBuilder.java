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

        return TerrainRenderPass.builder().extraDefines(extraDefines).pipelineState(blocksTextureState(mipped)).vertexType(vertexType).primitiveType(QuadPrimitiveType.TRIANGULATED);
    }

    public static RenderPassConfiguration<BlockRenderLayer> build(ChunkVertexType vertexType) {
        // Mipmapped sampling is only valid while mipmaps are actually enabled; otherwise the atlas
        // has a single level and the mipmap-tagged materials would sample an incomplete texture and
        // get discarded on alpha-test. The configuration is rebuilt on every resource reload, so this
        // reflects the current mipmap setting.
        boolean mipEnabled = Minecraft.getMinecraft().gameSettings.mipmapLevels > 0;

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

        Material solidMaterial, cutoutMaterial, cutoutMippedMaterial, translucentMaterial, fluidMaterial;
        solidMaterial = new Material(solidPass, AlphaCutoffParameter.ZERO, mipEnabled);
        translucentMaterial = new Material(translucentPass, AlphaCutoffParameter.ZERO, mipEnabled);
        fluidMaterial = new Material(fluidPass, AlphaCutoffParameter.ZERO, mipEnabled);
        cutoutMippedMaterial = new Material(cutoutMippedPass, AlphaCutoffParameter.ONE_TENTH, mipEnabled);

        vanillaRenderStages.put(BlockRenderLayer.SOLID, solidPass);
        vanillaRenderStages.put(BlockRenderLayer.TRANSLUCENT, fluidPass);
        vanillaRenderStages.put(BlockRenderLayer.TRANSLUCENT, translucentPass);

        // Vanilla samples BlockRenderLayer.CUTOUT at mip level 0 while CUTOUT_MIPPED is mipmapped.
        // Both layers share the consolidated pass here, so the material carries that bit (#191).
        if (ActiniumRuntime.options().performance.useRenderPassConsolidation) {
            cutoutMaterial = new Material(cutoutMippedPass, AlphaCutoffParameter.ONE_TENTH, false);
            vanillaRenderStages.put(BlockRenderLayer.CUTOUT, cutoutMippedPass);
            vanillaRenderStages.put(BlockRenderLayer.CUTOUT_MIPPED, cutoutMippedPass);
        } else {
            TerrainRenderPass cutoutPass;

            cutoutPass = builderForRenderType(BlockRenderLayer.CUTOUT, vertexType, false)
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

        return new RenderPassConfiguration<>(renderTypeToMaterialMap,
                vanillaRenderStageMap.asMap(),
                solidMaterial,
                cutoutMippedMaterial,
                translucentMaterial,
                fluidMaterial);
    }
}

