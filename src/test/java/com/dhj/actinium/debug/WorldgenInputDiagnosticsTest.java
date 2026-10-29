package com.dhj.actinium.debug;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.world.DimensionType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldgenInputDiagnosticsTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        Bootstrap.register();
    }

    @Test
    void sampleIsStableAndSelectsOneChunkPerNegativeAndPositiveFootprint() {
        long seed = 0x47a5c93d2L;
        int dimension = DimensionType.OVERWORLD.getId();

        for (int regionX : new int[] {-3, -1, 0, 2}) {
            for (int regionZ : new int[] {-2, -1, 1, 3}) {
                int selectedCount = 0;
                for (int chunkX = regionX * 8; chunkX < regionX * 8 + 8; chunkX++) {
                    for (int chunkZ = regionZ * 8; chunkZ < regionZ * 8 + 8; chunkZ++) {
                        boolean first = WorldgenInputDiagnostics.isSampledChunk(
                            seed, dimension, chunkX, chunkZ);
                        assertEquals(first, WorldgenInputDiagnostics.isSampledChunk(
                            seed, dimension, chunkX, chunkZ));
                        if (first) {
                            selectedCount++;
                        }
                    }
                }

                assertEquals(1, selectedCount, "one sample in footprint " + regionX + "," + regionZ);
            }
        }
    }

    @Test
    void sectionAccumulatorCountsRealAirStoneAndSlabStates() {
        WorldgenInputDiagnostics.SectionAccumulator accumulator =
            new WorldgenInputDiagnostics.SectionAccumulator();
        accumulator.accept(Blocks.AIR.getDefaultState());
        accumulator.accept(Blocks.STONE.getDefaultState());
        accumulator.accept(Blocks.STONE_SLAB.getDefaultState());
        accumulator.accept(Blocks.STONE_SLAB.getDefaultState());

        WorldgenInputDiagnostics.SectionSummary summary = accumulator.snapshot();

        assertEquals(4, summary.blocks());
        assertEquals(3, summary.nonAir());
        assertEquals(0, summary.unknownState());
        assertEquals(0, summary.unknownMaterial());
        assertEquals(1, summary.opaqueTrue());
        assertEquals(3, summary.opaqueFalse());
        assertEquals(1, summary.fullCubeTrue());
        assertEquals(3, summary.fullCubeFalse());
        assertEquals(0, summary.opaqueUnknown());
        assertEquals(0, summary.fullCubeUnknown());
        assertEquals(0, summary.blockReadFailures());
        assertEquals(0, summary.registryNameReadFailures());
        assertEquals(0, summary.materialReadFailures());
        assertEquals(0, summary.metadataReadFailures());
        assertEquals(0, summary.opaqueReadFailures());
        assertEquals(0, summary.fullCubeReadFailures());
        assertEquals(0, summary.renderTypeReadFailures());
        assertEquals(Map.of(
            EnumBlockRenderType.LIQUID, 0,
            EnumBlockRenderType.MODEL, 3,
            EnumBlockRenderType.ENTITYBLOCK_ANIMATED, 0,
            EnumBlockRenderType.INVISIBLE, 1
        ), summary.renderTypeCounts());
        assertEquals(0, summary.renderTypeUnknown());

        assertTrue(summary.topStates().stream().anyMatch(state ->
            "minecraft:stone_slab".equals(state.registryName()) && state.count() == 2));
        assertTrue(summary.topStates().stream().anyMatch(state ->
            "minecraft:stone".equals(state.registryName()) && state.count() == 1));
        assertTrue(summary.topStates().stream().anyMatch(state ->
            "minecraft:air".equals(state.registryName()) && state.count() == 1));
    }
}
