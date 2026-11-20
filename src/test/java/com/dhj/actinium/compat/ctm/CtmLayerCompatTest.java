package com.dhj.actinium.compat.ctm;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.BlockRenderLayer;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.MinecraftForgeClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CtmLayerCompatTest {
    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void queriesEachOriginalLayerOnceWhenTheDestinationIsSolid() {
        var originalLayer = MinecraftForgeClient.getRenderLayer();
        ForgeHooksClient.setRenderLayer(BlockRenderLayer.SOLID);
        try {
            List<BlockRenderLayer> queriedLayers = new ArrayList<>();
            CtmLayerCompat.renderSourceLayers(new LayeredBlock().getDefaultState(),
                    () -> queriedLayers.add(MinecraftForgeClient.getRenderLayer()));

            assertEquals(List.of(BlockRenderLayer.CUTOUT, BlockRenderLayer.TRANSLUCENT), queriedLayers);
            assertSame(BlockRenderLayer.SOLID, MinecraftForgeClient.getRenderLayer());
        } finally {
            ForgeHooksClient.setRenderLayer(originalLayer);
        }
    }

    @Test
    void restoresThePreviousLayerWhenTheModelThrows() {
        var originalLayer = MinecraftForgeClient.getRenderLayer();
        ForgeHooksClient.setRenderLayer(BlockRenderLayer.SOLID);
        try {
            var failure = new IllegalStateException("Failed to draw layered block");
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> CtmLayerCompat.renderSourceLayers(new LayeredBlock().getDefaultState(), () -> {
                        throw failure;
                    })));
            assertSame(BlockRenderLayer.SOLID, MinecraftForgeClient.getRenderLayer());
        } finally {
            ForgeHooksClient.setRenderLayer(originalLayer);
        }
    }

    private static final class LayeredBlock extends Block {
        private LayeredBlock() {
            super(Material.ROCK);
        }

        @Override
        public boolean canRenderInLayer(IBlockState state, BlockRenderLayer layer) {
            return MinecraftForgeClient.getRenderLayer() != BlockRenderLayer.SOLID
                    && (layer == BlockRenderLayer.CUTOUT || layer == BlockRenderLayer.TRANSLUCENT);
        }
    }
}
