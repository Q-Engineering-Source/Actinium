package com.dhj.actinium.render.terrain.compile;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DispatchedBlockModelTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        Bootstrap.register();
    }

    @Test
    void extendsTheDispatcherStateOnceAndQueriesTheSelectedModelWithThatState() {
        IBakedModel selectedModel = new StateTrackingModel();
        IBlockState dispatcherState = Blocks.STONE.getDefaultState();
        IBlockState extendedState = Blocks.GRASS.getDefaultState();
        BlockPos pos = new BlockPos(4, 8, 12);
        AtomicInteger extensionCalls = new AtomicInteger();

        DispatchedBlockModel prepared = new DispatchedBlockModel();
        prepared.prepare(selectedModel, dispatcherState, null, pos, (state, blockAccess, position) -> {
            extensionCalls.incrementAndGet();
            assertSame(dispatcherState, state);
            assertSame(pos, position);
            return extendedState;
        });

        prepared.getQuads(EnumFacing.UP, 123L);

        assertEquals(1, extensionCalls.get());
        assertSame(selectedModel, prepared.model());
        assertSame(dispatcherState, prepared.dispatcherState());
        assertSame(extendedState, prepared.extendedState());
        assertSame(extendedState, ((StateTrackingModel) selectedModel).queriedState);
        prepared.clear();
    }

    private static final class StateTrackingModel implements IBakedModel {
        private IBlockState queriedState;

        @Override
        public List<BakedQuad> getQuads(IBlockState state, EnumFacing side, long rand) {
            this.queriedState = state;
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
