package com.dhj.actinium.render.terrain.voxelization;

import net.coderbot.iris.celeritas.vertices.ExtendedChunkVertexType;
import net.coderbot.iris.celeritas.vertices.TerrainVertexFormatRequirements;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VoxelRangeTableTest {
    private final VoxelRangeTable table = new VoxelRangeTable();

    @AfterEach
    void release() {
        this.table.free();
    }

    @Test
    void adjacentRangesShareOneEntryAndGapsRetainTheirInvocationPrefix() {
        this.table.beginRegion(17, 1, 2, 3);
        this.table.addRange(20, 4);
        this.table.addRange(24, 8);
        this.table.addRange(44, 4);

        ByteBuffer entries = this.table.entries();
        assertEquals(2, this.table.entryCount());
        assertEquals(16, this.table.vertexTotal(0));
        assertEquals(20, entries.getInt(0));
        assertEquals(0, entries.getInt(4));
        assertEquals(44, entries.getInt(8));
        assertEquals(12, entries.getInt(12));
    }

    @Test
    void regionBoundariesResetInvocationPrefixesEvenForAdjacentVboOffsets() {
        this.table.beginRegion(17, 1, 2, 3);
        this.table.addRange(8, 4);
        this.table.beginRegion(21, 4, 5, 6);
        this.table.addRange(12, 8);

        assertEquals(2, this.table.entryCount());
        assertEquals(0, this.table.rangeBase(0));
        assertEquals(1, this.table.rangeBase(1));
        assertEquals(1, this.table.rangeCount(1));
        assertEquals(8, this.table.vertexTotal(1));
        assertEquals(0, this.table.entries().getInt(12));
        assertEquals(21, this.table.vertexBuffer(1));
        assertEquals(4, this.table.offsetX(1));
        assertEquals(5, this.table.offsetY(1));
        assertEquals(6, this.table.offsetZ(1));
    }

    @Test
    void growthAndPassReuseKeepEveryInvocationMapping() {
        int ranges = 1100;
        for (int region = 0; region < 20; region++) {
            this.table.beginRegion(region + 1, region, 0, 0);
            for (int range = 0; range < ranges; range++) {
                this.table.addRange(range * 12, 4);
            }
        }
        ByteBuffer entries = this.table.entries();
        for (int region = 0; region < 20; region++) {
            assertEquals(region * ranges, this.table.rangeBase(region));
            assertEquals(ranges * 4, this.table.vertexTotal(region));
            for (int range = 0; range < ranges; range++) {
                int offset = (region * ranges + range) * 8;
                assertEquals(range * 12, entries.getInt(offset));
                assertEquals(range * 4, entries.getInt(offset + 4));
            }
        }

        this.table.clear();
        this.table.beginRegion(7, 0, 0, 0);
        this.table.addRange(100, 12);
        assertEquals(1, this.table.regionCount());
        assertEquals(1, this.table.entryCount());
        assertEquals(8, this.table.entries().remaining());
        assertEquals(100, this.table.entries().getInt(0));
        assertEquals(0, this.table.entries().getInt(4));
    }

    @Test
    void computeLayoutAcceptsCompleteVerticesAndRejectsPrunedVertices() {
        assertDoesNotThrow(() -> ShadowVoxelizerImpl.requireComputeVertexFormat(
            new ExtendedChunkVertexType().getVertexFormat()));
        assertThrows(IllegalStateException.class, () -> ShadowVoxelizerImpl.requireComputeVertexFormat(
            new ExtendedChunkVertexType(TerrainVertexFormatRequirements.of()).getVertexFormat()));
    }
}
