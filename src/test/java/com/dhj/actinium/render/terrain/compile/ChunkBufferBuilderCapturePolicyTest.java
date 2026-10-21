package com.dhj.actinium.render.terrain.compile;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkBufferBuilderCapturePolicyTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        Bootstrap.register();
    }

    @Test
    void nestedCaptureUsesTheInnerStateMaterialAndRestoresTheOuterFluidPolicy() {
        IBlockState outerState = Blocks.STONE.getDefaultState();
        IBlockState innerBlockState = Blocks.DIRT.getDefaultState();
        IBlockState innerFluidState = Blocks.WATER.getDefaultState();

        assertTrue(ChunkBufferBuilder.isFluidCapture(true, 1, outerState, outerState));
        assertFalse(ChunkBufferBuilder.isFluidCapture(true, 2, outerState, innerBlockState));
        assertTrue(ChunkBufferBuilder.isFluidCapture(true, 2, outerState, innerFluidState));
        assertTrue(ChunkBufferBuilder.isFluidCapture(true, 2, outerState, outerState));
        assertFalse(ChunkBufferBuilder.isFluidCapture(false, 1, outerState, innerBlockState));
    }
}
