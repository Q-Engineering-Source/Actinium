package com.gtnewhorizons.angelica.render;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudShapeTest {

    @Test
    void alphaBelowTheMinimumIsNotACloudCell() {
        final CloudShape translucent = new CloudShape(2, 2, uniformTexture(2, 2, rgba(0xFF, 0xFF, 0xFF, 9)));
        assertEquals(0, translucent.cellFlagsAt(0, 0));
        assertEquals(0, translucent.cellFlagsAt(1, 1));
        assertEquals(0, translucent.plateRects.length);

        final CloudShape faint = new CloudShape(2, 2, uniformTexture(2, 2, rgba(0xFF, 0xFF, 0xFF, 10)));
        assertEquals(CloudShape.OPAQUE, faint.cellFlagsAt(0, 0));
        assertEquals(CloudShape.OPAQUE, faint.cellFlagsAt(1, 1));
    }

    @Test
    void fullyOpaqueTextureIsOnePlateWithoutExposedEdges() {
        final CloudShape shape = shape("###", "###", "###");
        for (int z = 0; z < 3; z++) {
            for (int x = 0; x < 3; x++) {
                assertEquals(CloudShape.OPAQUE, shape.cellFlagsAt(x, z), "at " + x + "," + z);
            }
        }
        assertEquals(0, shape.westRuns.length);
        assertEquals(0, shape.eastRuns.length);
        assertEquals(0, shape.northRuns.length);
        assertEquals(0, shape.southRuns.length);
        assertEquals(4, shape.plateRects.length);
        assertEquals(0, shape.plateRects[0]);
        assertEquals(0, shape.plateRects[1]);
        assertEquals(3, shape.plateRects[2]);
        assertEquals(3, shape.plateRects[3]);
    }

    @Test
    void neighbourLookupWrapsAcrossBothSeams() {
        final CloudShape square = shape(
            ".###",
            "####",
            "####",
            "####"
        );
        assertEquals(CloudShape.EAST_EMPTY, square.cellFlagsAt(3, 0) & CloudShape.EAST_EMPTY);
        assertEquals(CloudShape.WEST_EMPTY, square.cellFlagsAt(1, 0) & CloudShape.WEST_EMPTY);
        assertEquals(CloudShape.SOUTH_EMPTY, square.cellFlagsAt(0, 3) & CloudShape.SOUTH_EMPTY);
        assertEquals(CloudShape.NORTH_EMPTY, square.cellFlagsAt(0, 1) & CloudShape.NORTH_EMPTY);
        assertEquals(CloudShape.OPAQUE, square.cellFlagsAt(2, 0));
        assertEquals(square.cellFlagsAt(3, 0), square.cellFlagsAt(-1, 0));
        assertEquals(square.cellFlagsAt(1, 0), square.cellFlagsAt(5, 4));

        final CloudShape tall = shape(
            ".##",
            "###",
            "###",
            "###",
            "###"
        );
        assertEquals(CloudShape.EAST_EMPTY, tall.cellFlagsAt(2, 0) & CloudShape.EAST_EMPTY);
        assertEquals(CloudShape.WEST_EMPTY, tall.cellFlagsAt(1, 0) & CloudShape.WEST_EMPTY);
        assertEquals(CloudShape.SOUTH_EMPTY, tall.cellFlagsAt(0, 4) & CloudShape.SOUTH_EMPTY);
        assertEquals(CloudShape.NORTH_EMPTY, tall.cellFlagsAt(0, 1) & CloudShape.NORTH_EMPTY);
        assertEquals(tall.cellFlagsAt(2, 0), tall.cellFlagsAt(-1, 0));
        assertEquals(tall.cellFlagsAt(0, 4), tall.cellFlagsAt(0, -1));
        assertEquals(tall.cellFlagsAt(1, 0), tall.cellFlagsAt(4, 5));
    }

    @Test
    void plateRectsCoverEveryOpaqueCellExactlyOnce() {
        final String[] rows = {
            "#.#.#",
            "##.##",
            ".....",
            "#####"
        };
        final CloudShape shape = shape(rows);
        final int width = rows[0].length();
        final int[] coverage = new int[rows.length * width];
        for (int rect = 0; rect < shape.plateRects.length; rect += 4) {
            final int minX = shape.plateRects[rect];
            final int minZ = shape.plateRects[rect + 1];
            final int rectW = shape.plateRects[rect + 2];
            final int rectH = shape.plateRects[rect + 3];
            assertTrue(rectW > 0 && rectH > 0, "empty rect at index " + rect);
            for (int dz = 0; dz < rectH; dz++) {
                for (int dx = 0; dx < rectW; dx++) {
                    coverage[(minZ + dz) * width + minX + dx]++;
                }
            }
        }
        for (int z = 0; z < rows.length; z++) {
            for (int x = 0; x < width; x++) {
                assertEquals(rows[z].charAt(x) == '#' ? 1 : 0, coverage[z * width + x], "at " + x + "," + z);
            }
        }
    }

    @Test
    void coarsePlatesRequireAnEvenTexture() {
        assertNull(shape("###", "###").coarsePlateRects);
        assertNull(shape("##", "##", "##").coarsePlateRects);
        assertNotNull(shape("####", "####").coarsePlateRects);
    }

    @Test
    void coarsePlatesOnlyCoverMajorityFilledBlocks() {
        final String[] rows = {
            "##..",
            "#...",
            "..##",
            "...#"
        };
        final CloudShape shape = shape(rows);
        final int width = rows[0].length();
        final int[] coverage = new int[rows.length * width];
        assertNotNull(shape.coarsePlateRects);
        for (int rect = 0; rect < shape.coarsePlateRects.length; rect += 4) {
            final int minX = shape.coarsePlateRects[rect];
            final int minZ = shape.coarsePlateRects[rect + 1];
            final int rectW = shape.coarsePlateRects[rect + 2];
            final int rectH = shape.coarsePlateRects[rect + 3];
            assertEquals(0, minX % 2, "coarse rect starts on an odd column");
            assertEquals(0, minZ % 2, "coarse rect starts on an odd row");
            assertEquals(0, rectW % 2, "coarse rect has an odd width");
            assertEquals(0, rectH % 2, "coarse rect has an odd height");
            for (int dz = 0; dz < rectH; dz++) {
                for (int dx = 0; dx < rectW; dx++) {
                    coverage[(minZ + dz) * width + minX + dx]++;
                }
            }
        }
        for (int z = 0; z < rows.length; z++) {
            for (int x = 0; x < width; x++) {
                final int expected = majorityFilledBlock(rows, x, z) ? 1 : 0;
                assertEquals(expected, coverage[z * width + x], "at " + x + "," + z);
            }
        }
    }

    @Test
    void wallRunsReportEveryExposedEdgeOnce() {
        final String[] rows = {
            "#.#",
            "###",
            ".#."
        };
        final CloudShape shape = shape(rows);
        assertRunsCoverFlaggedCells(shape, shape.westRuns, CloudShape.WEST_EMPTY, false, rows);
        assertRunsCoverFlaggedCells(shape, shape.eastRuns, CloudShape.EAST_EMPTY, false, rows);
        assertRunsCoverFlaggedCells(shape, shape.northRuns, CloudShape.NORTH_EMPTY, true, rows);
        assertRunsCoverFlaggedCells(shape, shape.southRuns, CloudShape.SOUTH_EMPTY, true, rows);
    }

    @Test
    void isolatedCellExposesAllFourSides() {
        final CloudShape shape = shape(
            "...",
            ".#.",
            "..."
        );
        assertEquals(
            CloudShape.OPAQUE | CloudShape.WEST_EMPTY | CloudShape.EAST_EMPTY
                | CloudShape.NORTH_EMPTY | CloudShape.SOUTH_EMPTY,
            shape.cellFlagsAt(1, 1)
        );
        assertEquals(3, shape.westRuns.length);
        assertEquals(1, shape.westRuns[2]);
        assertEquals(1, shape.westRuns[0]);
        assertEquals(1, shape.westRuns[1]);
        assertEquals(3, shape.northRuns.length);
        assertEquals(1, shape.northRuns[2]);
        assertEquals(0, shape.cellFlagsAt(0, 0));
    }

    @Test
    void opaqueTexelsAllWhiteRequiresFullyOpaqueWhiteTexels() {
        assertTrue(shape("#", "#").opaqueTexelsAllWhite);

        final CloudShape grey = new CloudShape(2, 2, uniformTexture(2, 2, rgba(0x80, 0x80, 0x80, 0xFF)));
        assertFalse(grey.opaqueTexelsAllWhite);

        final CloudShape translucentWhite = new CloudShape(2, 2, uniformTexture(2, 2, rgba(0xFF, 0xFF, 0xFF, 200)));
        assertFalse(translucentWhite.opaqueTexelsAllWhite);
        assertEquals(CloudShape.OPAQUE, translucentWhite.cellFlagsAt(0, 0));
    }

    private static void assertRunsCoverFlaggedCells(CloudShape shape, int[] runs, int emptyBit, boolean alongX, String[] rows) {
        final int width = rows[0].length();
        final boolean[] seen = new boolean[rows.length * width];
        int covered = 0;
        for (int run = 0; run < runs.length; run += 3) {
            final int startX = runs[run];
            final int startZ = runs[run + 1];
            final int length = runs[run + 2];
            assertTrue(length > 0, "empty run at index " + run);
            for (int step = 0; step < length; step++) {
                final int x = alongX ? startX + step : startX;
                final int z = alongX ? startZ : startZ + step;
                assertTrue((shape.cellFlagsAt(x, z) & emptyBit) != 0, "run covers a filled side at " + x + "," + z);
                assertFalse(seen[z * width + x], "cell appears in two runs at " + x + "," + z);
                seen[z * width + x] = true;
                covered++;
            }
        }

        int flagged = 0;
        for (int z = 0; z < rows.length; z++) {
            for (int x = 0; x < width; x++) {
                if ((shape.cellFlagsAt(x, z) & emptyBit) != 0) flagged++;
            }
        }
        assertEquals(flagged, covered);
    }

    private static boolean majorityFilledBlock(String[] rows, int x, int z) {
        final int blockX = (x / 2) * 2;
        final int blockZ = (z / 2) * 2;
        int filled = 0;
        for (int dz = 0; dz < 2; dz++) {
            for (int dx = 0; dx < 2; dx++) {
                if (rows[blockZ + dz].charAt(blockX + dx) == '#') filled++;
            }
        }
        return filled >= 2;
    }

    private static CloudShape shape(String... rows) {
        final int height = rows.length;
        final int width = rows[0].length();
        final ByteBuffer buffer = ByteBuffer.allocate(width * height * 4);
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                if (rows[z].charAt(x) == '#') buffer.putInt((z * width + x) * 4, rgba(0xFF, 0xFF, 0xFF, 0xFF));
            }
        }
        return new CloudShape(width, height, buffer);
    }

    private static ByteBuffer uniformTexture(int width, int height, int color) {
        final ByteBuffer buffer = ByteBuffer.allocate(width * height * 4);
        for (int cell = 0; cell < width * height; cell++) {
            buffer.putInt(cell * 4, color);
        }
        return buffer;
    }

    private static int rgba(int red, int green, int blue, int alpha) {
        return (red << 24) | (green << 16) | (blue << 8) | alpha;
    }
}
