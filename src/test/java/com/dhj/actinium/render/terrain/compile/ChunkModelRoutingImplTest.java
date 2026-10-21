package com.dhj.actinium.render.terrain.compile;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BlockModelRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkModelRoutingImplTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        Bootstrap.register();
    }

    private final ChunkModelRouting routing = new ChunkModelRoutingImpl();

    @Test
    void passesDispatcherSelectedModelAndStateToFastRenderer() {
        TestChunkBuffer buffer = new TestChunkBuffer(true);
        IBakedModel model = new TestBakedModel();
        IBlockState state = Blocks.STONE.getDefaultState();
        BlockPos pos = new BlockPos(1, 2, 3);

        boolean rendered = this.routing.render(null, null, model, state, pos, buffer, false,
                args -> failOriginal());

        assertTrue(rendered);
        assertEquals(1, buffer.fastCalls);
        assertEquals(0, buffer.originalCaptureDepth);
        assertSame(model, buffer.fastModel);
        assertSame(state, buffer.fastState);
        assertSame(pos, buffer.fastPos);
        assertFalse(buffer.fastCheckSides);
    }

    @Test
    void leavesNonChunkBuffersOnTheOriginalDispatcherRoute() {
        BufferBuilder buffer = new BufferBuilder(256);
        boolean[] originalCalled = {false};

        boolean rendered = this.routing.render(null, null, new TestBakedModel(), Blocks.STONE.getDefaultState(),
                new BlockPos(0, 0, 0), buffer, true, args -> {
                    originalCalled[0] = true;
                    return true;
                });

        assertTrue(rendered);
        assertTrue(originalCalled[0]);
    }

    @Test
    void restoresVanillaCaptureWhenOriginalReturnsOrThrows() {
        TestChunkBuffer buffer = new TestChunkBuffer(false);
        Operation<Boolean> original = args -> {
            assertEquals(1, buffer.originalCaptureDepth);
            return false;
        };

        assertFalse(this.routing.render(null, null, new TestBakedModel(), Blocks.STONE.getDefaultState(),
                new BlockPos(0, 0, 0), buffer, true, original));
        assertEquals(0, buffer.originalCaptureDepth);

        Operation<Boolean> throwingOriginal = args -> {
            assertEquals(1, buffer.originalCaptureDepth);
            throw new IllegalStateException("model failed");
        };
        assertThrows(IllegalStateException.class,
                () -> this.routing.render(null, null, new TestBakedModel(), Blocks.STONE.getDefaultState(),
                        new BlockPos(0, 0, 0), buffer, true, throwingOriginal));
        assertEquals(0, buffer.originalCaptureDepth);
        assertEquals(0, buffer.dispatcherDepth);
    }

    @Test
    void nestedDispatcherCallUsesVanillaAndRestoresTheOuterInvocation() {
        TestChunkBuffer buffer = new TestChunkBuffer(true);
        buffer.nestedRouting = this.routing;

        assertTrue(this.routing.render(null, null, new TestBakedModel(), Blocks.STONE.getDefaultState(),
                new BlockPos(0, 0, 0), buffer, true, args -> failOriginal()));

        assertEquals(1, buffer.fastCalls);
        assertEquals(1, buffer.nestedOriginalCalls);
        assertEquals(0, buffer.originalCaptureDepth);
        assertEquals(0, buffer.dispatcherDepth);
    }

    private static Boolean failOriginal() {
        throw new AssertionError("Fast rendering must not call the vanilla renderer");
    }

    private static final class TestChunkBuffer extends BufferBuilder implements ChunkRenderInvocation {
        private final boolean allowFast;
        private int dispatcherDepth;
        private int originalCaptureDepth;
        private int fastCalls;
        private int nestedOriginalCalls;
        private IBakedModel fastModel;
        private IBlockState fastState;
        private BlockPos fastPos;
        private boolean fastCheckSides;
        private ChunkModelRouting nestedRouting;

        private TestChunkBuffer(boolean allowFast) {
            super(256);
            this.allowFast = allowFast;
        }

        @Override
        public VintageChunkBuildContext actinium$buildContext() {
            return null;
        }

        @Override
        public BlockRenderLayer actinium$renderLayer() {
            return BlockRenderLayer.SOLID;
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
            return this.allowFast && outermostCall;
        }

        @Override
        public boolean actinium$renderFastModel(IBakedModel model, IBlockState state, BlockPos pos,
                                                IBlockAccess blockAccess, boolean checkSides) {
            this.fastCalls++;
            this.fastModel = model;
            this.fastState = state;
            this.fastPos = pos;
            this.fastCheckSides = checkSides;

            if (this.nestedRouting != null) {
                this.nestedRouting.render(null, null, new TestBakedModel(), state, pos, this, true, args -> {
                    this.nestedOriginalCalls++;
                    assertEquals(1, this.originalCaptureDepth);
                    return false;
                });
            }

            return true;
        }

        @Override
        public void actinium$beginVanillaCapture(BufferBuilder buffer, BlockPos pos, IBlockState state) {
            this.originalCaptureDepth++;
        }

        @Override
        public void actinium$endVanillaCapture(BufferBuilder buffer) {
            this.originalCaptureDepth--;
        }
    }

    private static final class TestBakedModel implements IBakedModel {
        @Override
        public List<BakedQuad> getQuads(IBlockState state, EnumFacing side, long rand) {
            return List.of();
        }

        @Override
        public boolean isAmbientOcclusion() {
            return false;
        }

        @Override
        public boolean isGui3d() {
            return false;
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
}
