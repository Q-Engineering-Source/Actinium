package com.dhj.actinium.render.terrain.compile.task;

import com.dhj.actinium.runtime.ActiniumRuntime;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkBuilderMeshingTaskDeferredScanTest {
    @BeforeAll
    static void bootstrapRegistries() {
        Bootstrap.register();
    }

    @Test
    void routesCellsWithAConfiguredHostOrStoredFluidToTheRenderThread() {
        final var settings = ActiniumRuntime.options().performance;
        final var previous = settings.deferredBlockIds;
        settings.deferredBlockIds = List.of("minecraft:water", "minecraft:chest");
        try {
            // Four neighboring cells: stored water, stored lava, an empty host, and a deferred host.
            final int x = -16, y = 32, z = -32;
            final long[] mask = new long[64];
            ChunkBuilderMeshingTask.SectionBlockLookup hosts = (bx, by, bz) -> {
                if (by != y || bz != z) return Blocks.AIR;
                return switch (bx - x) {
                    case 0, 1, 2 -> Blocks.STONE;
                    case 3 -> Blocks.CHEST;
                    default -> Blocks.AIR;
                };
            };
            ChunkBuilderMeshingTask.SectionBlockLookup fluids = (bx, by, bz) -> {
                if (by != y || bz != z) return null;
                return switch (bx - x) {
                    case 0, 4 -> Blocks.WATER;
                    case 1 -> Blocks.LAVA;
                    default -> null;
                };
            };

            assertTrue(ChunkBuilderMeshingTask.prescanDeferred(hosts, fluids, x, y, z, mask));
            assertTrue(ChunkBuilderMeshingTask.isDeferred(mask, x, y, z), "stored water defers the entire cell");
            assertFalse(ChunkBuilderMeshingTask.isDeferred(mask, x + 1, y, z), "unconfigured stored fluid stays on the worker");
            assertFalse(ChunkBuilderMeshingTask.isDeferred(mask, x + 2, y, z), "ordinary host stays on the worker");
            assertTrue(ChunkBuilderMeshingTask.isDeferred(mask, x + 3, y, z), "configured host still defers without a fluid");
            assertFalse(ChunkBuilderMeshingTask.isDeferred(mask, x + 4, y, z), "air cells are not rendered");

            assertTrue(ChunkBuilderMeshingTask.prescanDeferred(hosts, null, x, y, z, mask));
            assertFalse(ChunkBuilderMeshingTask.isDeferred(mask, x, y, z), "without Fluidlogged the reused mask only tracks hosts");
            assertTrue(ChunkBuilderMeshingTask.isDeferred(mask, x + 3, y, z));
        } finally {
            settings.deferredBlockIds = previous;
        }
    }
}
