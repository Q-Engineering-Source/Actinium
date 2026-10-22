package com.dhj.actinium.debug;

import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildOutput;
import dhj.embeddedt.embeddium.impl.render.chunk.data.BuiltSectionMeshParts;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.block.material.Material;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkMeshInputDiagnosticsTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        Bootstrap.register();
    }

    @Test
    void reservesOnlyLargeDebugEnabledOutputsAndStopsAtRendererBudget() {
        ChunkMeshInputDiagnostics.TraceBudget budget = new ChunkMeshInputDiagnostics.TraceBudget();

        assertEquals(0L, ChunkMeshInputDiagnostics.tryReserveTraceId(
            ChunkMeshInputDiagnostics.MIN_OUTPUT_BYTES - 1L, budget, true));
        assertEquals(0L, ChunkMeshInputDiagnostics.tryReserveTraceId(
            ChunkMeshInputDiagnostics.MIN_OUTPUT_BYTES, budget, false));
        assertEquals(0, budget.reservedEvents());

        long firstTraceId = ChunkMeshInputDiagnostics.tryReserveTraceId(
            ChunkMeshInputDiagnostics.MIN_OUTPUT_BYTES, budget, true);
        assertTrue(firstTraceId > 0L);
        for (int index = 1; index < ChunkMeshInputDiagnostics.MAX_EVENTS_PER_RENDERER; index++) {
            assertTrue(ChunkMeshInputDiagnostics.tryReserveTraceId(
                ChunkMeshInputDiagnostics.MIN_OUTPUT_BYTES, budget, true) > 0L);
        }

        assertEquals(ChunkMeshInputDiagnostics.MAX_EVENTS_PER_RENDERER, budget.reservedEvents());
        assertEquals(0L, ChunkMeshInputDiagnostics.tryReserveTraceId(
            ChunkMeshInputDiagnostics.MIN_OUTPUT_BYTES, budget, true));
        assertEquals(ChunkMeshInputDiagnostics.MAX_EVENTS_PER_RENDERER, budget.reservedEvents());
    }

    @Test
    void mapsNegativeSectionCoordinatesToNegativeBlockOrigins() {
        assertEquals(-64, ChunkMeshInputDiagnostics.sectionBlockOrigin(-4));
        assertEquals(-16, ChunkMeshInputDiagnostics.sectionBlockOrigin(-1));
        assertEquals(0, ChunkMeshInputDiagnostics.sectionBlockOrigin(0));
    }

    @Test
    void recordsRegistryMetadataAndBothStateIdEncodingsSeparately() {
        IBlockState state = Blocks.STONE_SLAB.getStateFromMeta(1);

        ChunkMeshInputDiagnostics.StateIdentity identity = ChunkMeshInputDiagnostics.identifyState(state);

        assertEquals("minecraft:stone_slab", identity.registryName());
        assertEquals(1, identity.metadata());
        assertEquals(Block.getStateId(state), identity.vanillaStateId());
        assertEquals(Block.BLOCK_STATE_IDS.get(state), identity.internalStateId());
        assertEquals(0, identity.readFailures());
    }

    @Test
    void treatsAnUnregisteredInternalStateIdAsUnknown() {
        IBlockState state = new Block(Material.ROCK).getDefaultState();

        ChunkMeshInputDiagnostics.StateIdentity identity = ChunkMeshInputDiagnostics.identifyState(state);

        assertEquals("unregistered", identity.registryName());
        assertEquals(0, identity.metadata());
        assertNull(identity.vanillaStateId());
        assertNull(identity.internalStateId());
    }

    @Test
    void treatsANameAssignedButUnregisteredBlockAsUnknownForVanillaStateId() {
        Block block = new Block(Material.ROCK);
        block.setRegistryName(new ResourceLocation("minecraft", "actinium_unregistered_test_block"));

        ChunkMeshInputDiagnostics.StateIdentity identity =
            ChunkMeshInputDiagnostics.identifyState(block.getDefaultState());

        assertEquals("minecraft:actinium_unregistered_test_block", identity.registryName());
        assertNull(identity.vanillaStateId());
        assertNull(identity.internalStateId());
    }

    @Test
    void topStatesComeOnlyFromObservedInputsAndPreserveStateMetadata() {
        IBlockState slab = Blocks.STONE_SLAB.getStateFromMeta(1);
        ChunkMeshInputDiagnostics.InputAccumulator accumulator = new ChunkMeshInputDiagnostics.InputAccumulator();
        accumulator.accept(Blocks.AIR.getDefaultState());
        accumulator.accept(slab);
        accumulator.accept(slab);
        accumulator.accept(Blocks.STONE.getDefaultState());

        ChunkMeshInputDiagnostics.SectionSummary summary = accumulator.snapshot();

        assertEquals(4, summary.blocks());
        assertEquals(0, summary.unknownStates());
        assertEquals(3, summary.nonAir());
        assertEquals(3, summary.distinctStates());
        assertEquals(3, summary.topStates().size());
        assertEquals(4, summary.opaqueTrue() + summary.opaqueFalse() + summary.opaqueUnknown());
        assertEquals(4, summary.fullCubeTrue() + summary.fullCubeFalse() + summary.fullCubeUnknown());
        assertEquals(4, summary.renderTypeCounts().values().stream().mapToInt(Integer::intValue).sum()
            + summary.renderTypeUnknown());
        assertEquals("minecraft:stone_slab", summary.topStates().get(0).identity().registryName());
        assertEquals(1, summary.topStates().get(0).identity().metadata());
        assertEquals(2, summary.topStates().get(0).count());
        assertEquals(List.of("minecraft:stone_slab", "minecraft:air", "minecraft:stone"),
            summary.topStates().stream().map(state -> state.identity().registryName()).toList());
    }

    @Test
    void buildTraceIsCarriedToAcceptedForUploadAndRecheckedAgainstCurrentDebugGate() {
        long traceId = ChunkMeshInputDiagnostics.tryReserveTraceId(
            ChunkMeshInputDiagnostics.MIN_OUTPUT_BYTES, new ChunkMeshInputDiagnostics.TraceBudget(), true);
        var output = new ChunkBuildOutput(null, null,
            new Reference2ReferenceOpenHashMap<TerrainRenderPass, BuiltSectionMeshParts>(), 42, traceId);
        var unsampledOutput = new ChunkBuildOutput(null, null,
            new Reference2ReferenceOpenHashMap<TerrainRenderPass, BuiltSectionMeshParts>(), 42);

        assertEquals(traceId, output.meshInputTraceId);
        assertTrue(output.shouldLogAcceptedForUpload(true));
        assertFalse(output.shouldLogAcceptedForUpload(false));
        assertFalse(unsampledOutput.shouldLogAcceptedForUpload(true));
    }
}
