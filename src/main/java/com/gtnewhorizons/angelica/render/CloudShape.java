package com.gtnewhorizons.angelica.render;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Decoded form of a cloud texture: which texels are cloud cells, the greedy rectangles the plate field is
 * built from, and the exposed cell sides merged into wall runs.
 *
 * <p>The texture repeats in both directions, so every neighbour lookup wraps. Two derived forms are kept
 * per shape: plate rectangles at cell resolution and, for even sized textures, rectangles at half
 * resolution used far from the camera.
 */
final class CloudShape {
    /** Marks the texel as a cloud cell; the other bits record the sides that face empty space. */
    static final int OPAQUE = 1 << 4;
    /** Set when the west neighbour of a cell is empty, so the cell exposes a wall side there. */
    static final int WEST_EMPTY = 1;
    /** Set when the east neighbour of a cell is empty. */
    static final int EAST_EMPTY = 1 << 1;
    /** Set when the north neighbour of a cell is empty. */
    static final int NORTH_EMPTY = 1 << 2;
    /** Set when the south neighbour of a cell is empty. */
    static final int SOUTH_EMPTY = 1 << 3;
    /** Lowest alpha value that still counts as a cloud cell; fainter texels are treated as holes. */
    private static final int MIN_OPAQUE_ALPHA = 10;
    /** Texture width in cells. */
    final int width;
    /** Texture height in cells. */
    final int height;
    /**
     * Greedy opaque rectangles at cell resolution, packed as {@code (minX, minZ, width, height)} per
     * rectangle. They tile every cloud cell exactly once.
     */
    final int[] plateRects;
    /**
     * Half resolution rectangles packed like {@link #plateRects}, or {@code null} when the texture has an
     * odd dimension and cannot be halved. Coordinates stay in cell space.
     */
    final int[] coarsePlateRects;
    /** Exposed west sides merged into vertical runs, packed as {@code (x, z, length)}. */
    final int[] westRuns;
    /** Exposed east sides merged into vertical runs, packed as {@code (x, z, length)}. */
    final int[] eastRuns;
    /** Exposed north sides merged into horizontal runs, packed as {@code (x, z, length)}. */
    final int[] northRuns;
    /** Exposed south sides merged into horizontal runs, packed as {@code (x, z, length)}. */
    final int[] southRuns;
    /**
     * True when every cloud texel is fully opaque white, which lets the renderer skip the per vertex
     * texture colour modulation.
     */
    final boolean opaqueTexelsAllWhite;
    /** Bit mask used to wrap lookups on the power of two fast path. */
    private final int widthMask, heightMask;
    /** Whether both dimensions are powers of two, so wrapping can use a mask instead of a division. */
    private final boolean sizeIsPowerOfTwo;
    /** Per cell flag bits, zero for holes. Indexed without wrapping; see {@link #cellFlagsAt(int, int)}. */
    private final byte[] cellFlags;

    CloudShape(int width, int height, ByteBuffer rgba) {
        this.width = width;
        this.height = height;
        this.widthMask = width - 1;
        this.heightMask = height - 1;
        this.sizeIsPowerOfTwo = (width & widthMask) == 0 && (height & heightMask) == 0;
        final int cellCount = width * height;
        final boolean[] opaque = new boolean[cellCount];
        boolean allWhite = true;
        for (int i = 0; i < cellCount; i++) {
            final int alpha = rgba.get(i * 4 + 3) & 0xFF;
            if (alpha < MIN_OPAQUE_ALPHA) continue;
            opaque[i] = true;
            if (allWhite && (alpha != 0xFF
                || (rgba.get(i * 4) & 0xFF) != 0xFF
                || (rgba.get(i * 4 + 1) & 0xFF) != 0xFF
                || (rgba.get(i * 4 + 2) & 0xFF) != 0xFF)) {
                allWhite = false;
            }
        }
        this.opaqueTexelsAllWhite = allWhite;

        final byte[] cellFlags = new byte[cellCount];
        for (int z = 0; z < height; z++) {
            final int rowStart = z * width;
            final int northRowStart = Math.floorMod(z - 1, height) * width;
            final int southRowStart = Math.floorMod(z + 1, height) * width;
            for (int x = 0; x < width; x++) {
                if (!opaque[rowStart + x]) continue;
                int flagBits = OPAQUE;
                if (!opaque[rowStart + Math.floorMod(x - 1, width)]) flagBits |= WEST_EMPTY;
                if (!opaque[rowStart + Math.floorMod(x + 1, width)]) flagBits |= EAST_EMPTY;
                if (!opaque[northRowStart + x]) flagBits |= NORTH_EMPTY;
                if (!opaque[southRowStart + x]) flagBits |= SOUTH_EMPTY;
                cellFlags[rowStart + x] = (byte) flagBits;
            }
        }
        this.cellFlags = cellFlags;
        this.plateRects = buildPlateRects(opaque, width, height);
        this.coarsePlateRects = (width % 2 == 0 && height % 2 == 0) ? buildCoarsePlateRects(opaque, width, height) : null;

        this.westRuns = buildRunsAlongZ(cellFlags, width, height, WEST_EMPTY);
        this.eastRuns = buildRunsAlongZ(cellFlags, width, height, EAST_EMPTY);
        this.northRuns = buildRunsAlongX(cellFlags, width, height, NORTH_EMPTY);
        this.southRuns = buildRunsAlongX(cellFlags, width, height, SOUTH_EMPTY);
    }

    /** Greedily merges the given cells into the largest rectangles found row by row, tiling each cell once. */
    private static int[] buildPlateRects(boolean[] opaque, int width, int height) {
        final boolean[] uncovered = opaque.clone();
        int[] rects = new int[256];
        int count = 0;

        for (int z = 0; z < height; z++) {
            final int rowStart = z * width;
            for (int x = 0; x < width; x++) {
                if (!uncovered[rowStart + x]) continue;

                int rectW = 1;
                while (x + rectW < width && uncovered[rowStart + x + rectW]) rectW++;

                int rectH = 1;
                extend:
                while (z + rectH < height) {
                    final int nextRowStart = (z + rectH) * width;
                    for (int dx = 0; dx < rectW; dx++) {
                        if (!uncovered[nextRowStart + x + dx]) break extend;
                    }
                    rectH++;
                }

                for (int dz = 0; dz < rectH; dz++) {
                    final int coveredRowStart = (z + dz) * width;
                    for (int dx = 0; dx < rectW; dx++) uncovered[coveredRowStart + x + dx] = false;
                }

                if (count * 4 + 4 > rects.length) rects = Arrays.copyOf(rects, rects.length * 2);
                rects[count * 4] = x;
                rects[count * 4 + 1] = z;
                rects[count * 4 + 2] = rectW;
                rects[count * 4 + 3] = rectH;
                count++;
                x += rectW - 1;
            }
        }
        return Arrays.copyOf(rects, count * 4);
    }

    /**
     * Halves the texture by keeping a block only when at least two of its four cells are cloud cells, then
     * builds rectangles on the halved grid and scales them back into cell space.
     */
    private static int[] buildCoarsePlateRects(boolean[] opaque, int width, int height) {
        final int coarseWidth = width / 2;
        final int coarseHeight = height / 2;
        final boolean[] coarseOpaque = new boolean[coarseWidth * coarseHeight];
        for (int z = 0; z < coarseHeight; z++) {
            final int topRowStart = (z * 2) * width;
            final int bottomRowStart = (z * 2 + 1) * width;
            for (int x = 0; x < coarseWidth; x++) {
                int filled = 0;
                if (opaque[topRowStart + x * 2]) filled++;
                if (opaque[topRowStart + x * 2 + 1]) filled++;
                if (opaque[bottomRowStart + x * 2]) filled++;
                if (opaque[bottomRowStart + x * 2 + 1]) filled++;
                coarseOpaque[z * coarseWidth + x] = filled >= 2;
            }
        }

        final int[] rects = buildPlateRects(coarseOpaque, coarseWidth, coarseHeight);
        for (int i = 0; i < rects.length; i++) rects[i] *= 2;
        return rects;
    }

    /** Merges exposed sides of one column into runs while walking the texture, packing {@code (x, z, length)}. */
    private static int[] buildRunsAlongZ(byte[] cellFlags, int width, int height, int emptyBit) {
        int[] runs = new int[192];
        int count = 0;
        for (int x = 0; x < width; x++) {
            int z = 0;
            while (z < height) {
                if ((cellFlags[x + z * width] & emptyBit) == 0) {
                    z++;
                    continue;
                }
                int runLength = 1;
                while (z + runLength < height && (cellFlags[x + (z + runLength) * width] & emptyBit) != 0) runLength++;
                if (count * 3 + 3 > runs.length) runs = Arrays.copyOf(runs, runs.length * 2);
                runs[count * 3] = x;
                runs[count * 3 + 1] = z;
                runs[count * 3 + 2] = runLength;
                count++;
                z += runLength;
            }
        }
        return Arrays.copyOf(runs, count * 3);
    }

    /** Merges exposed sides of one row into runs while walking the texture, packing {@code (x, z, length)}. */
    private static int[] buildRunsAlongX(byte[] cellFlags, int width, int height, int emptyBit) {
        int[] runs = new int[192];
        int count = 0;
        for (int z = 0; z < height; z++) {
            final int rowStart = z * width;
            int x = 0;
            while (x < width) {
                if ((cellFlags[rowStart + x] & emptyBit) == 0) {
                    x++;
                    continue;
                }
                int runLength = 1;
                while (x + runLength < width && (cellFlags[rowStart + x + runLength] & emptyBit) != 0) runLength++;
                if (count * 3 + 3 > runs.length) runs = Arrays.copyOf(runs, runs.length * 2);
                runs[count * 3] = x;
                runs[count * 3 + 1] = z;
                runs[count * 3 + 2] = runLength;
                count++;
                x += runLength;
            }
        }
        return Arrays.copyOf(runs, count * 3);
    }

    /**
     * Flag bits of a cell with both axes wrapped into the texture, so callers can query any cell coordinate
     * of the repeating field without wrapping it themselves.
     */
    int cellFlagsAt(int x, int z) {
        final int wrappedX, wrappedZ;
        if (sizeIsPowerOfTwo) {
            wrappedX = x & widthMask;
            wrappedZ = z & heightMask;
        } else {
            wrappedX = Math.floorMod(x, width);
            wrappedZ = Math.floorMod(z, height);
        }
        return cellFlags[wrappedX + wrappedZ * width] & 0xFF;
    }
}
