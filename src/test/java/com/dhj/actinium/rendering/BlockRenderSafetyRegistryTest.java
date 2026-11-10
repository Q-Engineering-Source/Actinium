package com.dhj.actinium.rendering;

import com.dhj.actinium.runtime.ActiniumRuntime;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the local render-thread criterion of the deferred meshing path: a block keeps its
 * off-thread build unless the player listed it in the configuration, and the list is parsed
 * strictly so a typo cannot silently disable the criterion for the whole pack.
 *
 * <p>The configured ids are read from the real {@link ActiniumRuntime#options()} and restored after
 * every case, so the assertions describe the same lookup the mesher performs.</p>
 */
class BlockRenderSafetyRegistryTest {
    @BeforeAll
    static void bootstrapRegistries() {
        Bootstrap.register();
    }

    @Test
    void blocksStayOnTheBuildThreadByDefault() {
        withConfiguredIds(List.of(), () -> {
            assertTrue(BlockRenderSafetyRegistry.canRenderOffThread(Blocks.STONE));
            assertTrue(BlockRenderSafetyRegistry.canRenderOffThread(Blocks.CHEST));
        });
    }

    @Test
    void configuredBlocksAreCapturedOnTheRenderThread() {
        withConfiguredIds(List.of("minecraft:stone"), () -> {
            assertFalse(BlockRenderSafetyRegistry.canRenderOffThread(Blocks.STONE));
            assertTrue(BlockRenderSafetyRegistry.canRenderOffThread(Blocks.DIRT));
        });
    }

    @Test
    void severalConfiguredBlocksAreAllDeferred() {
        withConfiguredIds(List.of("minecraft:stone", "minecraft:chest"), () -> {
            assertFalse(BlockRenderSafetyRegistry.canRenderOffThread(Blocks.STONE));
            assertFalse(BlockRenderSafetyRegistry.canRenderOffThread(Blocks.CHEST));
            assertTrue(BlockRenderSafetyRegistry.canRenderOffThread(Blocks.DIRT));
        });
    }

    @Test
    void unregisteredBlocksCannotBeListedAndStayOnTheBuildThread() {
        Block unregistered = new Block(Material.ROCK);
        assertNull(unregistered.getRegistryName());

        withConfiguredIds(List.of("minecraft:stone"),
                () -> assertTrue(BlockRenderSafetyRegistry.canRenderOffThread(unregistered)));
    }

    @Test
    void parseAcceptsCommaAndWhitespaceSeparatedIds() {
        assertEquals(List.of("minecraft:stone", "minecraft:dirt", "create:cogwheel"),
                BlockRenderSafetyRegistry.parseBlockIds(" minecraft:stone, minecraft:dirt\ncreate:cogwheel "));
    }

    @Test
    void parseDeduplicatesAndKeepsTheFirstOccurrenceOrder() {
        assertEquals(List.of("minecraft:dirt", "minecraft:stone"),
                BlockRenderSafetyRegistry.parseBlockIds("minecraft:dirt minecraft:stone,minecraft:dirt"));
    }

    @Test
    void blankInputMeansNoConfiguredBlocks() {
        assertTrue(BlockRenderSafetyRegistry.parseBlockIds("   ").isEmpty());
        assertTrue(BlockRenderSafetyRegistry.parseBlockIds("").isEmpty());
    }

    @Test
    void parseRejectsMalformedIds() {
        for (String invalid : List.of("minecraft", "minecraft:stone:brick", "Minecraft:Stone",
                "minecraft:", ":stone", "minecraft:stone, minecraft", "mine craft:stone")) {
            assertThrows(IllegalArgumentException.class, () -> BlockRenderSafetyRegistry.parseBlockIds(invalid),
                    "expected '" + invalid + "' to be rejected");
        }
    }

    @Test
    void replacingTheConfiguredListUpdatesTheCriterion() {
        withConfiguredIds(List.of(),
                () -> assertTrue(BlockRenderSafetyRegistry.canRenderOffThread(Blocks.STONE)));
        withConfiguredIds(List.of("minecraft:stone"),
                () -> assertFalse(BlockRenderSafetyRegistry.canRenderOffThread(Blocks.STONE)));
        withConfiguredIds(List.of("minecraft:dirt"),
                () -> assertTrue(BlockRenderSafetyRegistry.canRenderOffThread(Blocks.STONE)));
    }

    @Test
    void formattingRoundTripsThroughParsing() {
        List<String> ids = List.of("minecraft:stone", "create:cogwheel");

        assertEquals(ids, BlockRenderSafetyRegistry.parseBlockIds(BlockRenderSafetyRegistry.formatBlockIds(ids)));
    }

    private static void withConfiguredIds(List<String> ids, Runnable assertions) {
        List<String> previous = new ArrayList<>(ActiniumRuntime.options().performance.deferredBlockIds);

        try {
            ActiniumRuntime.options().performance.deferredBlockIds = new ArrayList<>(ids);
            assertions.run();
        } finally {
            ActiniumRuntime.options().performance.deferredBlockIds = previous;
        }
    }
}
