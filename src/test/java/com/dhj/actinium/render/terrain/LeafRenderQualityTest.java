package com.dhj.actinium.render.terrain;

import dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions.GraphicsQuality;
import net.minecraft.block.BlockLeaves;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.util.BlockRenderLayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeafRenderQualityTest {
    private boolean leavesWereFancy;
    private boolean leaves2WereFancy;

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.register();
    }

    @BeforeEach
    void rememberLeavesState() {
        leavesWereFancy = isFancy(Blocks.LEAVES);
        leaves2WereFancy = isFancy(Blocks.LEAVES2);
    }

    @AfterEach
    void restoreLeavesState() {
        Blocks.LEAVES.setGraphicsLevel(leavesWereFancy);
        Blocks.LEAVES2.setGraphicsLevel(leaves2WereFancy);
    }

    @Test
    void defaultQualityFollowsFastVanillaGraphics() {
        LeafRenderQuality.apply(GraphicsQuality.DEFAULT, false);

        assertLeavesFast();
    }

    @Test
    void defaultQualityFollowsFancyVanillaGraphics() {
        LeafRenderQuality.apply(GraphicsQuality.DEFAULT, true);

        assertLeavesFancy();
    }

    @Test
    void fancyQualityOverridesFastVanillaGraphics() {
        LeafRenderQuality.apply(GraphicsQuality.FANCY, false);

        assertLeavesFancy();
    }

    @Test
    void fastQualityOverridesFancyVanillaGraphics() {
        LeafRenderQuality.apply(GraphicsQuality.FAST, true);

        assertLeavesFast();
    }

    private static void assertLeavesFast() {
        assertFast("leaves", Blocks.LEAVES);
        assertFast("leaves2", Blocks.LEAVES2);
    }

    private static void assertLeavesFancy() {
        assertFancy("leaves", Blocks.LEAVES);
        assertFancy("leaves2", Blocks.LEAVES2);
    }

    private static void assertFast(String name, BlockLeaves leaves) {
        assertEquals(BlockRenderLayer.SOLID, leaves.getRenderLayer(), name + " must render as full cubes");
        assertTrue(leaves.isOpaqueCube(leaves.getDefaultState()), name + " must occlude neighbouring faces");
    }

    private static void assertFancy(String name, BlockLeaves leaves) {
        assertEquals(BlockRenderLayer.CUTOUT_MIPPED, leaves.getRenderLayer(), name + " must render with cutout holes");
        assertFalse(leaves.isOpaqueCube(leaves.getDefaultState()), name + " must keep inner faces visible");
    }

    private static boolean isFancy(BlockLeaves leaves) {
        return leaves.getRenderLayer() == BlockRenderLayer.CUTOUT_MIPPED;
    }
}
