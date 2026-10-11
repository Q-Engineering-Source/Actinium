package com.dhj.actinium.mixin.mod.littletiles;

import com.creativemd.creativecore.client.rendering.RenderBox;
import com.creativemd.creativecore.client.rendering.model.CreativeModelPipeline;
import com.dhj.actinium.compat.littletiles.LittleTilesCompat;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.client.model.pipeline.VertexLighterFlat;
import org.spongepowered.asm.mixin.Mixin;

import java.util.BitSet;
import java.util.List;

/** Captures each LittleTiles constituent state around its face-buffer writes. */
@Mixin(value = CreativeModelPipeline.class, remap = false)
public abstract class MixinCreativeModelPipeline {
    @WrapMethod(
            method = "renderBlockFaceSmooth(Lnet/minecraft/world/IBlockAccess;"
                    + "Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/math/BlockPos;"
                    + "Lnet/minecraft/client/renderer/BufferBuilder;Lnet/minecraft/util/BlockRenderLayer;"
                    + "Ljava/util/List;[FLnet/minecraft/util/EnumFacing;Ljava/util/BitSet;"
                    + "Ljava/lang/Object;Lcom/creativemd/creativecore/client/rendering/RenderBox;)V"
    )
    private static void actinium$renderSmoothWithContext(
            IBlockAccess world,
            IBlockState state,
            BlockPos pos,
            BufferBuilder buffer,
            BlockRenderLayer layer,
            List<BakedQuad> quads,
            float[] brightness,
            EnumFacing facing,
            BitSet faceSet,
            Object ambientOcclusionFace,
            RenderBox cube,
            Operation<Void> original
    ) {
        LittleTilesCompat.beginEmbeddedBlockRender(buffer, state, pos);
        try {
            original.call(world, state, pos, buffer, layer, quads, brightness, facing, faceSet, ambientOcclusionFace, cube);
        } finally {
            LittleTilesCompat.endEmbeddedBlockRender(buffer);
        }
    }

    @WrapMethod(
            method = "renderBlockFaceFlat(Lnet/minecraft/world/IBlockAccess;"
                    + "Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/math/BlockPos;"
                    + "Lnet/minecraft/client/renderer/BufferBuilder;Lnet/minecraft/util/BlockRenderLayer;"
                    + "Ljava/util/List;Lnet/minecraft/util/EnumFacing;Ljava/util/BitSet;"
                    + "Lcom/creativemd/creativecore/client/rendering/RenderBox;Ljava/lang/Object;)V"
    )
    private static void actinium$renderFlatWithContext(
            IBlockAccess world,
            IBlockState state,
            BlockPos pos,
            BufferBuilder buffer,
            BlockRenderLayer layer,
            List<BakedQuad> quads,
            EnumFacing facing,
            BitSet faceSet,
            RenderBox cube,
            Object renderEnvironment,
            Operation<Void> original
    ) {
        LittleTilesCompat.beginEmbeddedBlockRender(buffer, state, pos);
        try {
            original.call(world, state, pos, buffer, layer, quads, facing, faceSet, cube, renderEnvironment);
        } finally {
            LittleTilesCompat.endEmbeddedBlockRender(buffer);
        }
    }

    @WrapMethod(
            method = "overwriteColor(Lnet/minecraft/world/IBlockAccess;"
                    + "Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/math/BlockPos;"
                    + "Lnet/minecraft/client/renderer/BufferBuilder;Lnet/minecraft/util/BlockRenderLayer;"
                    + "Lnet/minecraft/client/renderer/block/model/BakedQuad;"
                    + "Lcom/creativemd/creativecore/client/rendering/RenderBox;Ljava/lang/Object;"
                    + "Lnet/minecraftforge/client/model/pipeline/VertexLighterFlat;)V"
    )
    private static void actinium$separateLittleTilesAo(
            IBlockAccess world,
            IBlockState state,
            BlockPos pos,
            BufferBuilder buffer,
            BlockRenderLayer layer,
            BakedQuad quad,
            RenderBox cube,
            Object ambientOcclusionFace,
            VertexLighterFlat lighter,
            Operation<Void> original
    ) {
        boolean separateAo = LittleTilesCompat.shouldWriteSeparateAo();
        int[] colorsBeforeOverwrite = separateAo
                ? LittleTilesCompat.snapshotCurrentQuadColors(buffer)
                : null;
        float[] aoFactors = separateAo
                ? LittleTilesCompat.snapshotSeparateAoFactors(
                        ambientOcclusionFace,
                        lighter,
                        quad,
                        colorsBeforeOverwrite
                )
                : null;

        original.call(world, state, pos, buffer, layer, quad, cube, ambientOcclusionFace, lighter);
        if (separateAo) {
            LittleTilesCompat.moveCurrentQuadAoToAlpha(
                    buffer,
                    quad,
                    aoFactors
            );
        }
    }
}
