package com.gtnewhorizons.angelica.render;

import org.joml.Matrix4fc;

/**
 * Pure geometry of the 2D cloud disc: how a world position maps onto cloud cells, how those cells are
 * grouped into wedges for the draw order, and how far out a wall or a coarse plate still contributes
 * visible pixels.
 *
 * <p>Every member is a stateless function of its arguments, so the renderer can evaluate the disc
 * description once per frame without keeping bookkeeping state. Distances are expressed in cloud cells;
 * one cell spans the layer cell width in blocks.
 */
final class CloudDisc {

    /**
     * Cloud cells along one chunk edge. The renderer splits the disc into per chunk branches so each
     * branch keeps its index count small.
     */
    static final int CELLS_PER_CHUNK = 8;
    /** Half size of the always kept margin around the camera in cells, the bound anchors are rejected against. */
    static final int MARGIN_CELLS = 16;
    /**
     * Angular slices of the disc. The value is fixed because {@link #wedgeOf(int, int)} folds each
     * quadrant into eight slices, which only partitions the circle evenly at 32 slices.
     */
    static final int WEDGE_COUNT = 32;
    /** Conversion used by callers that spread per wedge work over the visible angular span. */
    static final double WEDGES_PER_RADIAN = WEDGE_COUNT / (2.0 * Math.PI);
    /**
     * Radius in cells inside which every cell is drawn whatever the camera looks at. It also bounds the
     * drift the wedge order can absorb, which {@link #driftAngle(double)} relies on.
     */
    static final int ALWAYS_DRAWN_CELLS = Math.max(CELLS_PER_CHUNK, 4 * MARGIN_CELLS);
    /** Smallest on screen height, in pixels, at which a cloud wall side is still visible enough to build. */
    private static final double WALL_CUT_PIXELS = 0.625;
    /** Smallest on screen size, in pixels, at which a plate is still visible enough to build at cell resolution. */
    private static final double PLATE_LOD_PIXELS = 0.35;
    /** Tangent of 11.25 degrees, the first slice boundary inside a quadrant. */
    private static final double TAN_11_25_DEG = 0.19891236737965800; // √(4+2√2)−√2−1
    /** Tangent of 22.5 degrees, the second slice boundary inside a quadrant. */
    private static final double TAN_22_5_DEG = 0.41421356237309503; // √2−1
    /** Tangent of 33.75 degrees, the third slice boundary inside a quadrant. */
    private static final double TAN_33_75_DEG = 0.66817863791929892; // √(4−2√2)+1−√2
    /**
     * Quantization step for both LOD cut distances. Snapping to a step keeps the built geometry stable
     * while the camera walks, instead of rebuilding the near field for every pixel of scale change.
     */
    private static final int DISTANCE_STEP_CELLS = 32;

    static {
        if (WEDGE_COUNT != 32) {
            throw new AssertionError("wedgeOf splits four quadrants into eight; WEDGE_COUNT must be 32");
        }
    }

    private CloudDisc() {
    }

    /**
     * Converts a world coordinate into the fractional cell coordinate the patch mesher indexes with.
     *
     * @param worldPosition block coordinate along one horizontal axis
     * @param cellWidthBlocks edge length of one cloud cell in blocks
     * @param offset per layer offset in cells, so layer scrolling shifts the cell grid
     */
    static double cellCoordinate(double worldPosition, float cellWidthBlocks, double offset) {
        return worldPosition / cellWidthBlocks + offset;
    }

    /**
     * Fractional texture coordinate of a cell index. Anchors go negative as the camera drifts towards
     * negative world coordinates, so the index is wrapped into the texture instead of producing a
     * negative coordinate that would sample outside the cloud texture.
     */
    static float textureOffset(int anchor, int textureSize) {
        return Math.floorMod(anchor, textureSize) * (1.0f / textureSize);
    }

    /**
     * Tests whether a drift anchor is close enough to the camera to be built at all. Distant anchors are
     * rejected with cheap range checks before the squared distance is formed.
     */
    static boolean withinMargin(int driftX, int driftZ) {
        // Reject distant anchors before squaring
        return driftX >= -MARGIN_CELLS && driftX <= MARGIN_CELLS && driftZ >= -MARGIN_CELLS && driftZ <= MARGIN_CELLS && driftX * driftX + driftZ * driftZ <= MARGIN_CELLS * MARGIN_CELLS;
    }

    /**
     * Tests cell membership of the disc around the camera cell. The squares are formed in {@code long}
     * so large radii cannot overflow, and the box bound rejects disc corners cheaply.
     */
    static boolean withinDisc(long driftX, long driftZ, int radiusCells) {
        return driftX >= -radiusCells && driftX <= radiusCells && driftZ >= -radiusCells && driftZ <= radiusCells && driftX * driftX + driftZ * driftZ <= (long) radiusCells * radiusCells;
    }

    /**
     * Pixel scale in the vertical direction for a view described by its field of view. Used when the
     * renderer only knows the configured FOV and not the full projection matrix.
     *
     * @return pixels per radian, or zero for a FOV that cannot describe a perspective view
     */
    static double pixelsPerRadian(int displayHeight, float fovDegrees) {
        if (!(fovDegrees > 0.0f) || fovDegrees >= 180.0f) return 0.0;
        return (displayHeight * 0.5) / Math.tan(Math.toRadians(fovDegrees) * 0.5);
    }

    /**
     * Largest on screen pixel scale of a projection matrix, taken from its two clip space axes so the
     * LOD cuts follow the axis the camera magnifies most. Returns zero for projections without a
     * perspective divide (orthographic) and for degenerate or non finite matrices.
     */
    static double pixelsPerRadian(int displayWidth, int displayHeight, Matrix4fc projection) {
        if (displayWidth <= 0 || displayHeight <= 0) return 0.0;
        final float perspectiveW = projection.m23();
        if (!Float.isFinite(perspectiveW) || perspectiveW >= 0.0f || projection.m33() != 0.0f) return 0.0;

        final double scale = -0.5 / perspectiveW;
        final double a = displayWidth * projection.m00() * scale;
        final double b = displayWidth * projection.m10() * scale;
        final double c = displayHeight * projection.m01() * scale;
        final double d = displayHeight * projection.m11() * scale;
        if (!Double.isFinite(a) || !Double.isFinite(b) || !Double.isFinite(c) || !Double.isFinite(d)) {
            return 0.0;
        }
        if (b == 0.0 && c == 0.0) return Math.max(Math.abs(a), Math.abs(d));
        final double column0 = a * a + c * c;
        final double column1 = b * b + d * d;
        final double cross = a * b + c * d;
        final double largest = 0.5 * (column0 + column1
            + Math.hypot(column0 - column1, 2.0 * cross));
        final double pixels = Math.sqrt(largest);
        return Double.isFinite(pixels) ? pixels : 0.0;
    }

    /**
     * Distance in cells up to which wall sides are built. Walls behind the plate field are skipped, and
     * a scale of zero or a disabled plate field disables the cut entirely so every wall is kept.
     *
     * @return the cut distance in cells, or {@link Integer#MAX_VALUE} when walls must not be cut
     */
    static int wallCutCells(double pixelsPerRadian, boolean platesInFront) {
        if (!platesInFront || pixelsPerRadian <= 0.0) return Integer.MAX_VALUE;

        return roundUpToStep(pixelsPerRadian / (3.0 * WALL_CUT_PIXELS));
    }

    /**
     * Distance in cells up to which plates are built from cell sized rectangles instead of the half
     * resolution coarse rectangles. A taller cloud offset needs a larger fine radius because the same
     * cell covers fewer pixels.
     *
     * @return the fine plate radius in cells, or {@link Integer#MAX_VALUE} when coarse plates cannot be used
     */
    static int plateLodCells(double pixelsPerRadian, float cloudBaseRelativeY, float cellWidthBlocks, boolean coarseCoverUsable) {
        if (!coarseCoverUsable || pixelsPerRadian <= 0.0) return Integer.MAX_VALUE;

        final double heightBlocks = Math.max(1.0, Math.abs(cloudBaseRelativeY));
        return roundUpToStep(Math.sqrt(heightBlocks * pixelsPerRadian / (cellWidthBlocks * PLATE_LOD_PIXELS)));
    }

    /**
     * Rounds a cell distance up to the next LOD step and saturates instead of overflowing, so callers can
     * compare the result against cell counts without a separate bound.
     */
    private static int roundUpToStep(double cells) {
        final long steps = (long) (cells / DISTANCE_STEP_CELLS) + 1;
        return (int) Math.min(steps * DISTANCE_STEP_CELLS, Integer.MAX_VALUE);
    }

    /**
     * Wedge index of a cell. The point is folded into one quadrant and then into one of eight equal angle
     * slices, which gives 32 adjacent wedges around the camera for a per wedge draw order.
     */
    static int wedgeOf(int x, int z) {
        int foldedX = -x;
        int foldedZ = -z;
        if (foldedX == 0 && foldedZ == 0) return WEDGE_COUNT / 2;

        final int quadrant;
        if (foldedX > 0 && foldedZ >= 0) {
            quadrant = 0;
        } else if (foldedX <= 0 && foldedZ > 0) {
            quadrant = 1;
            final int oldX = foldedX;
            foldedX = foldedZ;
            foldedZ = -oldX;
        } else if (foldedX < 0) {
            quadrant = 2;
            foldedX = -foldedX;
            foldedZ = -foldedZ;
        } else {
            quadrant = 3;
            final int oldX = foldedX;
            foldedX = -foldedZ;
            foldedZ = oldX;
        }

        final int withinQuadrant;
        if (foldedZ < foldedX) {
            if (foldedZ < TAN_11_25_DEG * foldedX) withinQuadrant = 0;
            else if (foldedZ < TAN_22_5_DEG * foldedX) withinQuadrant = 1;
            else if (foldedZ < TAN_33_75_DEG * foldedX) withinQuadrant = 2;
            else withinQuadrant = 3;
        } else {
            if (foldedX > TAN_33_75_DEG * foldedZ) withinQuadrant = 4;
            else if (foldedX > TAN_22_5_DEG * foldedZ) withinQuadrant = 5;
            else if (foldedX > TAN_11_25_DEG * foldedZ) withinQuadrant = 6;
            else withinQuadrant = 7;
        }

        return quadrant * 8 + withinQuadrant;
    }

    /**
     * Extra angular span the wedge order has to cover on one side when the camera drifts off the cell
     * grid by the given distance. The span widens until the near field is culled entirely, at which
     * point a quarter turn is enough because the disc is symmetric through the camera cell.
     */
    static double driftAngle(double driftCells) {
        final double nearestCulledCells = ALWAYS_DRAWN_CELLS - driftCells;
        return nearestCulledCells <= driftCells ? Math.PI / 2.0 : Math.asin(driftCells / nearestCulledCells);
    }
}
