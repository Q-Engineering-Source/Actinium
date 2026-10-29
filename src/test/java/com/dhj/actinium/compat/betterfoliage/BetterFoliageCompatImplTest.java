package com.dhj.actinium.compat.betterfoliage;

import com.dhj.actinium.render.terrain.compile.ChunkModelRouting;
import com.dhj.actinium.render.terrain.compile.ChunkModelRoutingImpl;
import com.dhj.actinium.render.terrain.compile.ChunkRenderInvocation;
import com.dhj.actinium.render.terrain.compile.VintageChunkBuildContext;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BetterFoliageCompatImplTest {
    private static final BlockRenderLayer MIPPED_TARGET = BlockRenderLayer.CUTOUT_MIPPED;
    private static final BlockRenderLayer PLAIN_CUTOUT = BlockRenderLayer.CUTOUT;
    private static final BakedQuad QUAD = new BakedQuad(new int[0], -1, null, null, true, DefaultVertexFormats.ITEM);
    private static final Operation<Boolean> FAIL_IF_ORIGINAL = args -> {
        throw new AssertionError("Fast dispatcher route must not call the vanilla model renderer");
    };

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        Bootstrap.register();
    }

    @Test
    void solidOnlyBlockEmitsSixQuadsAcrossNativeAndSyntheticCutoutLayers() {
        IBlockState solidState = Blocks.STONE.getDefaultState();
        IBakedModel sixFaceModel = new SixFaceModel();
        ChunkModelRouting routing = new ChunkModelRoutingImpl();
        BlockPos pos = new BlockPos(1, 2, 3);

        int emittedQuads = 0;
        int routedBaseModels = 0;
        for (BlockRenderLayer layer : List.of(BlockRenderLayer.SOLID, MIPPED_TARGET)) {
            if (!BetterFoliageCompatImpl.shouldRenderBaseBlockInLayer(
                    solidState, layer, MIPPED_TARGET, PLAIN_CUTOUT)) {
                continue;
            }

            TestChunkBuffer buffer = new TestChunkBuffer(layer);
            assertTrue(routing.render(
                    null,
                    null,
                    sixFaceModel,
                    solidState,
                    pos,
                    buffer,
                    true,
                    FAIL_IF_ORIGINAL
            ));
            routedBaseModels++;
            emittedQuads += buffer.emittedQuads;
        }

        assertEquals(1, routedBaseModels);
        assertEquals(6, emittedQuads);
    }

    @Test
    void baseRenderPredicatePreservesBothCutoutPairingDirectionsAndShortCircuits() {
        LayerSpecificBlock cutoutBlock = new LayerSpecificBlock(PLAIN_CUTOUT);
        assertTrue(BetterFoliageCompatImpl.shouldRenderBaseBlockInLayer(
                cutoutBlock.getDefaultState(), MIPPED_TARGET, MIPPED_TARGET, PLAIN_CUTOUT));
        assertEquals(List.of(MIPPED_TARGET, PLAIN_CUTOUT), cutoutBlock.queriedLayers);

        LayerSpecificBlock mippedBlock = new LayerSpecificBlock(MIPPED_TARGET);
        assertTrue(BetterFoliageCompatImpl.shouldRenderBaseBlockInLayer(
                mippedBlock.getDefaultState(), PLAIN_CUTOUT, PLAIN_CUTOUT, MIPPED_TARGET));
        assertEquals(List.of(PLAIN_CUTOUT, MIPPED_TARGET), mippedBlock.queriedLayers);

        LayerSpecificBlock cutoutOnlyBlock = new LayerSpecificBlock(PLAIN_CUTOUT);
        assertFalse(BetterFoliageCompatImpl.shouldRenderBaseBlockInLayer(
                cutoutOnlyBlock.getDefaultState(), MIPPED_TARGET, PLAIN_CUTOUT, MIPPED_TARGET));
        assertEquals(List.of(MIPPED_TARGET), cutoutOnlyBlock.queriedLayers);

        LayerSpecificBlock nativeCutoutBlock = new LayerSpecificBlock(PLAIN_CUTOUT);
        assertTrue(BetterFoliageCompatImpl.shouldRenderBaseBlockInLayer(
                nativeCutoutBlock.getDefaultState(), PLAIN_CUTOUT, MIPPED_TARGET, MIPPED_TARGET));
        assertEquals(List.of(PLAIN_CUTOUT), nativeCutoutBlock.queriedLayers);

        LayerSpecificBlock targetCutoutBlock = new LayerSpecificBlock(MIPPED_TARGET);
        assertTrue(BetterFoliageCompatImpl.shouldRenderBaseBlockInLayer(
                targetCutoutBlock.getDefaultState(), MIPPED_TARGET, MIPPED_TARGET, PLAIN_CUTOUT));
        assertEquals(List.of(MIPPED_TARGET), targetCutoutBlock.queriedLayers);
    }

    private static final class SixFaceModel implements IBakedModel {
        @Override
        public List<BakedQuad> getQuads(IBlockState state, EnumFacing side, long rand) {
            return side == null ? List.of() : List.of(QUAD);
        }

        @Override
        public boolean isAmbientOcclusion() {
            return false;
        }

        @Override
        public boolean isGui3d() {
            return true;
        }

        @Override
        public boolean isBuiltInRenderer() {
            return false;
        }

        @Override
        public TextureAtlasSprite getParticleTexture() {
            return null;
        }

        @Override
        public ItemCameraTransforms getItemCameraTransforms() {
            return null;
        }

        @Override
        public ItemOverrideList getOverrides() {
            return null;
        }
    }

    private static final class LayerSpecificBlock extends Block {
        private final EnumSet<BlockRenderLayer> renderLayers = EnumSet.noneOf(BlockRenderLayer.class);
        private final List<BlockRenderLayer> queriedLayers = new ArrayList<>();

        private LayerSpecificBlock(BlockRenderLayer... renderLayers) {
            super(Material.ROCK);
            for (BlockRenderLayer renderLayer : renderLayers) {
                this.renderLayers.add(renderLayer);
            }
        }

        @Override
        public boolean canRenderInLayer(IBlockState state, BlockRenderLayer layer) {
            this.queriedLayers.add(layer);
            return this.renderLayers.contains(layer);
        }
    }

    private static final class TestChunkBuffer extends BufferBuilder implements ChunkRenderInvocation {
        private final BlockRenderLayer layer;
        private int dispatcherDepth;
        private int emittedQuads;

        private TestChunkBuffer(BlockRenderLayer layer) {
            super(256);
            this.layer = layer;
        }

        @Override
        public VintageChunkBuildContext actinium$buildContext() {
            return null;
        }

        @Override
        public BlockRenderLayer actinium$renderLayer() {
            return this.layer;
        }

        @Override
        public void actinium$beginInvocation(boolean allowFastRenderer, boolean allowRenderPassOptimization,
                                             boolean fluidRender, IBlockState invocationState) {
        }

        @Override
        public void actinium$endInvocation() {
        }

        @Override
        public boolean actinium$isInvocationActive() {
            return true;
        }

        @Override
        public boolean actinium$enterDispatcherCall() {
            return this.dispatcherDepth++ == 0;
        }

        @Override
        public void actinium$exitDispatcherCall() {
            this.dispatcherDepth--;
        }

        @Override
        public boolean actinium$canRenderFastModel(IBlockAccess blockAccess, boolean outermostCall) {
            return outermostCall;
        }

        @Override
        public boolean actinium$renderFastModel(IBakedModel model, IBlockState state, BlockPos pos,
                                                IBlockAccess blockAccess, boolean checkSides) {
            for (EnumFacing face : EnumFacing.VALUES) {
                this.emittedQuads += model.getQuads(state, face, 0L).size();
            }
            return this.emittedQuads > 0;
        }

        @Override
        public void actinium$beginVanillaCapture(BufferBuilder buffer, BlockPos pos, IBlockState state) {
        }

        @Override
        public void actinium$endVanillaCapture(BufferBuilder buffer) {
        }
    }
}
