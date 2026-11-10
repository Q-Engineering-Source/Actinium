package com.gtnewhorizons.angelica.render;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudPlateCoverTest {

    @Test
    void everyRectStaysInsideTheDisc() {
        final int radius = 40;
        final CloudPlateCover cover = cover(fullOpaque(8, 8), 0, 0, radius, radius, false);
        assertTrue(cover.count() > 0, "the cover is empty");
        for (int rect = 0; rect < cover.count(); rect++) {
            assertTrue(cover.minX(rect) <= cover.maxX(rect), "inverted x bounds in rect " + rect);
            assertTrue(cover.minZ(rect) <= cover.maxZ(rect), "inverted z bounds in rect " + rect);
            final int farthestX = Math.max(Math.abs(cover.minX(rect)), Math.abs(cover.maxX(rect)));
            final int farthestZ = Math.max(Math.abs(cover.minZ(rect)), Math.abs(cover.maxZ(rect)));
            assertTrue(
                farthestX * farthestX + farthestZ * farthestZ <= radius * radius,
                "rect " + rect + " leaves the disc"
            );
        }
    }

    @Test
    void discCellsAreCoveredExactlyOnce() {
        final int radius = 40;
        final CloudPlateCover cover = cover(fullOpaque(8, 8), 0, 0, radius, radius, false);
        assertEquals(discCells(radius), cells(cover));
    }

    @Test
    void anchorTranslationOnlyMovesTheCoverageWindow() {
        final CloudShape shape = fullOpaque(8, 8);
        final int radius = 40;
        final Set<Long> origin = cells(cover(shape, 0, 0, radius, radius, false));

        assertEquals(origin, cells(cover(shape, 8, 8, radius, radius, false)));
        assertEquals(origin, cells(cover(shape, -8, -8, radius, radius, false)));
        assertEquals(origin, cells(cover(shape, -3, 5, radius, radius, false)));
    }

    @Test
    void coarseLodKeepsTheSeamCoveredExactlyOnce() {
        final int textureWidth = 8;
        final int radius = 48;
        final CloudPlateCover cover = cover(fullOpaque(textureWidth, textureWidth), 0, 0, radius, 8, false);
        // The fine box and the coarse far field must tile the disc together: a gap loses clouds at the
        // seam and an overlap draws the same cells twice.
        assertEquals(discCells(radius), cells(cover));
    }

    @Test
    void coarseCoverMergesCellsTheCellResolutionPassKeepsApart() {
        final int textureSize = 16;
        final int radius = 48;
        final CloudShape shape = checkerboard(textureSize);

        final CloudPlateCover cellResolution = cover(shape, 0, 0, radius, radius, false);
        int widestCellRect = 0;
        for (int rect = 0; rect < cellResolution.count(); rect++) {
            widestCellRect = Math.max(widestCellRect, cellResolution.maxX(rect) - cellResolution.minX(rect) + 1);
        }

        final CloudPlateCover coarse = cover(shape, 0, 0, radius, 8, false);
        int widestCoarseRect = 0;
        for (int rect = 0; rect < coarse.count(); rect++) {
            widestCoarseRect = Math.max(widestCoarseRect, coarse.maxX(rect) - coarse.minX(rect) + 1);
        }
        assertTrue(
            widestCoarseRect > widestCellRect,
            "coarse rects stay at " + widestCoarseRect + " cells wide, cell resolution at " + widestCellRect
        );
    }

    @Test
    void wedgeBucketsGroupTheDrawOrder() {
        final int radius = 128;
        final CloudPlateCover cover = cover(fullOpaque(8, 8), 0, 0, radius, radius, true);
        assertTrue(cover.count() > 0, "the cover is empty");

        final boolean[] listed = new boolean[cover.count()];
        int previousBucket = 0;
        boolean grouped = false;
        for (int order = 0; order < cover.count(); order++) {
            final int rect = cover.orderedRect(order);
            assertTrue(rect >= 0 && rect < cover.count(), "draw order points outside the rect list");
            assertFalse(listed[rect], "rect " + rect + " is listed twice");
            listed[rect] = true;

            final int bucket = cover.wedgeBucket(rect);
            assertTrue(bucket >= 0 && bucket <= CloudDisc.WEDGE_COUNT + 1, "bucket " + bucket + " is out of range");
            assertTrue(bucket >= previousBucket, "buckets are not grouped in draw order");
            previousBucket = bucket;
            grouped |= bucket > 0;
        }
        assertTrue(grouped, "no rect was assigned to a wedge");
    }

    @Test
    void ungroupedBuildKeepsTheSameRectsInInsertionOrder() {
        final CloudShape shape = fullOpaque(8, 8);
        final int radius = 128;
        final CloudPlateCover ungrouped = cover(shape, 0, 0, radius, radius, false);
        assertEquals(cover(shape, 0, 0, radius, radius, true).count(), ungrouped.count());
        for (int rect = 0; rect < ungrouped.count(); rect++) {
            assertEquals(0, ungrouped.wedgeBucket(rect));
        }
    }

    private static CloudPlateCover cover(CloudShape shape, int anchorX, int anchorZ, int radiusCells, int lodCells, boolean groupByWedge) {
        final CloudPlateCover cover = new CloudPlateCover();
        cover.build(shape, anchorX, anchorZ, radiusCells, radiusCells * radiusCells, lodCells, groupByWedge);
        return cover;
    }

    private static Set<Long> cells(CloudPlateCover cover) {
        final Set<Long> cells = new HashSet<>();
        for (int rect = 0; rect < cover.count(); rect++) {
            for (int z = cover.minZ(rect); z <= cover.maxZ(rect); z++) {
                for (int x = cover.minX(rect); x <= cover.maxX(rect); x++) {
                    assertTrue(cells.add(cellKey(x, z)), "cell " + x + "," + z + " is covered twice");
                }
            }
        }
        return cells;
    }

    private static Set<Long> discCells(int radiusCells) {
        final Set<Long> cells = new HashSet<>();
        for (int z = -radiusCells; z <= radiusCells; z++) {
            for (int x = -radiusCells; x <= radiusCells; x++) {
                if (x * x + z * z <= radiusCells * radiusCells) cells.add(cellKey(x, z));
            }
        }
        return cells;
    }

    private static long cellKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static CloudShape fullOpaque(int width, int height) {
        final ByteBuffer buffer = ByteBuffer.allocate(width * height * 4);
        for (int cell = 0; cell < width * height; cell++) {
            buffer.putInt(cell * 4, 0xFFFFFFFF);
        }
        return new CloudShape(width, height, buffer);
    }

    @Test
    void sparsePatternKeepsItsPhaseAcrossPositiveAndNegativeAnchors() {
        final int textureSize = 8;
        final int patternX = 3;
        final int patternZ = 2;
        final int radius = 40;
        final CloudShape shape = singleCell(textureSize, patternX, patternZ);
        final Long originPhase = cellKey(Math.floorMod(patternX, textureSize), Math.floorMod(patternZ, textureSize));
        assertEquals(Set.of(originPhase), residues(cover(shape, 0, 0, radius, radius, false), textureSize));

        final int[][] anchors = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {5, -3}, {-7, 6}};
        for (int[] anchor : anchors) {
            final Set<Long> expected = Set.of(cellKey(
                Math.floorMod(patternX - anchor[0], textureSize),
                Math.floorMod(patternZ - anchor[1], textureSize)
            ));
            assertEquals(
                expected,
                residues(cover(shape, anchor[0], anchor[1], radius, radius, false), textureSize),
                "anchor " + anchor[0] + "," + anchor[1]
            );
        }

        // Whole texture periods leave the phase untouched, including negative anchors that cross two periods.
        final int[][] periods = {{textureSize, textureSize}, {-textureSize, textureSize}, {-2 * textureSize, 3 * textureSize}};
        for (int[] anchor : periods) {
            assertEquals(
                Set.of(originPhase),
                residues(cover(shape, anchor[0], anchor[1], radius, radius, false), textureSize),
                "anchor " + anchor[0] + "," + anchor[1]
            );
        }
    }

    private static Set<Long> residues(CloudPlateCover cover, int textureSize) {
        final Set<Long> residues = new HashSet<>();
        for (int rect = 0; rect < cover.count(); rect++) {
            for (int z = cover.minZ(rect); z <= cover.maxZ(rect); z++) {
                for (int x = cover.minX(rect); x <= cover.maxX(rect); x++) {
                    residues.add(cellKey(Math.floorMod(x, textureSize), Math.floorMod(z, textureSize)));
                }
            }
        }
        return residues;
    }

    private static CloudShape singleCell(int size, int cellX, int cellZ) {
        final ByteBuffer buffer = ByteBuffer.allocate(size * size * 4);
        buffer.putInt((cellZ * size + cellX) * 4, 0xFFFFFFFF);
        return new CloudShape(size, size, buffer);
    }

    private static CloudShape checkerboard(int size) {
        final ByteBuffer buffer = ByteBuffer.allocate(size * size * 4);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                if (((x + z) & 1) == 0) buffer.putInt((z * size + x) * 4, 0xFFFFFFFF);
            }
        }
        return new CloudShape(size, size, buffer);
    }
}
