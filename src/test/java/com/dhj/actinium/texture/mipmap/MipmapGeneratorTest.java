package com.dhj.actinium.texture.mipmap;

import dhj.embeddedt.embeddium.api.util.ColorARGB;
import dhj.embeddedt.embeddium.impl.texture.MipmapHelper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MipmapGeneratorTest {

    private static final int OPAQUE_RED = ColorARGB.pack(255, 0, 0, 255);
    private static final int OPAQUE_GREEN = ColorARGB.pack(0, 255, 0, 255);
    private static final int OPAQUE_BLUE = ColorARGB.pack(0, 0, 255, 255);
    private static final int OPAQUE_WHITE = ColorARGB.pack(255, 255, 255, 255);
    private static final int TRANSPARENT = ColorARGB.pack(0, 0, 0, 0);

    /** Colours of the solid 2x2 texel blocks the sprite-sized tests are built from. */
    private static final int[] BLOCK_PALETTE = {OPAQUE_RED, OPAQUE_GREEN, OPAQUE_BLUE, OPAQUE_WHITE};

    @Test
    void mipLevelZeroReturnsTheInputBase() {
        final int[] base = {OPAQUE_RED, OPAQUE_GREEN, OPAQUE_BLUE, OPAQUE_WHITE};

        final int[][] levels = MipmapGenerator.generateMipLevels(0, 2, new int[][] {base},
            MipmapStrategy.MEAN, false);

        assertEquals(1, levels.length);
        assertSame(base, levels[0]);
    }

    /**
     * Without fully transparent texels the generator only walks the level chain, so one level has to be the
     * weighted average of the four texels below it and the base image must come back untouched.
     */
    @Test
    void meanLevelIsTheWeightedAverageOfTheFourTexelsBelow() {
        final int[] base = {OPAQUE_RED, OPAQUE_GREEN, OPAQUE_BLUE, OPAQUE_WHITE};
        final int[] expectedBase = base.clone();

        final int[][] levels = MipmapGenerator.generateMipLevels(1, 2, new int[][] {base},
            MipmapStrategy.MEAN, false);

        assertArrayEquals(expectedBase, base);
        assertEquals(2, levels.length);
        assertEquals(1, levels[1].length);
        assertEquals(
            MipmapHelper.weightedAverageColor(
                MipmapHelper.weightedAverageColor(OPAQUE_RED, OPAQUE_GREEN),
                MipmapHelper.weightedAverageColor(OPAQUE_BLUE, OPAQUE_WHITE)),
            levels[1][0]);
    }

    /** AUTO, and a null request, both mean "cutout when the sprite is cut out, plain filtering otherwise". */
    @Test
    void autoResolvesFromTheTransparencyScan() {
        final int[] transparentBase = {OPAQUE_WHITE, TRANSPARENT, TRANSPARENT, TRANSPARENT};
        final int[] opaqueBase = {OPAQUE_WHITE, OPAQUE_RED, OPAQUE_GREEN, OPAQUE_BLUE};

        assertLevelsEqual(
            MipmapGenerator.generateMipLevels(1, 2, new int[][] {transparentBase.clone()}, MipmapStrategy.CUTOUT, true),
            MipmapGenerator.generateMipLevels(1, 2, new int[][] {transparentBase.clone()}, MipmapStrategy.AUTO, true));
        assertLevelsEqual(
            MipmapGenerator.generateMipLevels(1, 2, new int[][] {transparentBase.clone()}, MipmapStrategy.CUTOUT, true),
            MipmapGenerator.generateMipLevels(1, 2, new int[][] {transparentBase.clone()}, null, true));

        assertLevelsEqual(
            MipmapGenerator.generateMipLevels(1, 2, new int[][] {opaqueBase.clone()}, MipmapStrategy.MEAN, false),
            MipmapGenerator.generateMipLevels(1, 2, new int[][] {opaqueBase.clone()}, MipmapStrategy.AUTO, false));
    }

    @Test
    void cutoutStrategyDetectionFollowsTheResolvedMode() {
        assertTrue(MipmapGenerator.isCutoutStrategy(null, true));
        assertFalse(MipmapGenerator.isCutoutStrategy(null, false));
        assertTrue(MipmapGenerator.isCutoutStrategy(MipmapStrategy.CUTOUT, true));
        assertTrue(MipmapGenerator.isCutoutStrategy(MipmapStrategy.STRICT_CUTOUT, false));
        assertTrue(MipmapGenerator.isCutoutStrategy(MipmapStrategy.DARK_CUTOUT, false));
        assertFalse(MipmapGenerator.isCutoutStrategy(MipmapStrategy.MEAN, true));
    }

    /**
     * Foliage wants its halo to take a dark colour rather than the leaf colour, so the transparent texels are
     * repainted with three quarters of the sprite's darkest opaque colour, still fully transparent. The repaint
     * is in place: the caller keeps this array as the sprite's level 0.
     */
    @Test
    void darkCutoutRepaintsTransparentTexelsInPlace() {
        final int dark = ColorARGB.pack(80, 40, 20, 255);
        final int[] base = {dark, ColorARGB.pack(200, 100, 50, 255), TRANSPARENT, TRANSPARENT};

        final int[][] levels = MipmapGenerator.generateMipLevels(1, 2, new int[][] {base},
            MipmapStrategy.DARK_CUTOUT, true);

        final int expectedFill = ColorARGB.pack(60, 30, 15, 0);
        assertSame(base, levels[0]);
        assertEquals(dark, base[0]);
        assertEquals(expectedFill, base[2]);
        assertEquals(expectedFill, base[3]);
    }

    /**
     * The cutout modes first flood the nearest opaque colour into the transparent texels, so downsampling along
     * an edge blends the sprite's own colour instead of the atlas blank space.
     */
    @Test
    void cutoutSolidifiesTransparentTexelsWithTheNearestOpaqueColour() {
        final int[] base = {ColorARGB.pack(10, 20, 30, 255), TRANSPARENT, TRANSPARENT, TRANSPARENT};

        final int[][] levels = MipmapGenerator.generateMipLevels(1, 2, new int[][] {base},
            MipmapStrategy.CUTOUT, true);

        final int filled = ColorARGB.pack(10, 20, 30, 0);
        assertEquals(filled, base[1]);
        assertEquals(filled, base[2]);
        assertEquals(filled, base[3]);
    }

    /** Levels the caller already holds are used as they are, and the base is then left alone. */
    @Test
    void providedLevelsAreReusedAndSkipTheSolidifyPass() {
        final int[] base = {ColorARGB.pack(10, 20, 30, 255), TRANSPARENT, TRANSPARENT, TRANSPARENT};
        final int[] provided = {ColorARGB.pack(7, 7, 7, 200)};

        final int[][] levels = MipmapGenerator.generateMipLevels(1, 2, new int[][] {base, provided},
            MipmapStrategy.CUTOUT, true);

        assertEquals(2, levels.length);
        assertSame(base, levels[0]);
        assertSame(provided, levels[1]);
        assertEquals(TRANSPARENT, base[1]);
    }

    @Test
    void everyLevelHalvesTheDimensions() {
        final int[] base = new int[16];
        for (int i = 0; i < base.length; i++) {
            base[i] = OPAQUE_WHITE;
        }

        final int[][] levels = MipmapGenerator.generateMipLevels(2, 4, new int[][] {base},
            MipmapStrategy.MEAN, false);

        assertEquals(3, levels.length);
        assertEquals(16, levels[0].length);
        assertEquals(4, levels[1].length);
        assertEquals(1, levels[2].length);
    }

    /**
     * A sprite a mod shipped at a size that cannot support the configured level count keeps the whole chain:
     * 12x12 asked for four levels gives 12 &gt;&gt; 4 = 0, and the atlas upload stops at the first such level.
     * Vanilla allocates it as a quarter of the level above and leaves it zero-filled, which is what the
     * uploader and the per-frame accessors expect to find there.
     */
    @Test
    void levelsPastTheSpriteSizeCollapseToAQuarterOfTheLevelAbove() {
        assertCollapsedChainIsSafe(12, 12, 4);
        assertCollapsedChainIsSafe(8, 8, 4);
        assertCollapsedChainIsSafe(32, 8, 4);
        assertCollapsedChainIsSafe(1, 1, 4);
    }

    /**
     * The cutout pass measures the base level's coverage and then rescales every level against it, so the
     * sprite has to walk the whole chain without measuring a level that has no texels left. A small sprite
     * with transparent texels is the shape the dev client crashed on, and level 3 here is the 1x1 level whose
     * coverage falls back to its single texel.
     */
    @Test
    void cutoutSpritesWithTransparencySurviveCollapsedLevels() {
        final int[] base = solidBlocks(12, 12);
        base[0] = TRANSPARENT;
        base[1] = TRANSPARENT;
        base[12] = TRANSPARENT;
        base[13] = TRANSPARENT;

        final int[][] levels = assertDoesNotThrow(() -> MipmapGenerator.generateMipLevels(4, 12,
            new int[][] {base}, MipmapStrategy.CUTOUT, true));

        assertEquals(5, levels.length);
        assertEquals(36, levels[1].length);
        assertEquals(9, levels[2].length);
        assertEquals(1, levels[3].length);
        assertEquals(0, levels[4].length);
    }

    /**
     * The levels a non power of two sprite can support have to keep the box filter of the level below: level 1
     * shows each solid 2x2 block's own colour, level 2 the average of the four blocks of its 4x4 region.
     */
    @Test
    void theLevelsOfA12x12SpriteStayTheBoxFilterOfTheLevelBelow() {
        final int[][] levels = MipmapGenerator.generateMipLevels(4, 12, new int[][] {solidBlocks(12, 12)},
            MipmapStrategy.MEAN, false);

        assertEquals(5, levels.length);
        assertEquals(36, levels[1].length);
        assertEquals(9, levels[2].length);
        assertEquals(1, levels[3].length);
        assertEquals(0, levels[4].length);

        assertEquals(levelOneBlock(1, 1), levels[1][1 + 6]);
        assertEquals(levelOneBlock(5, 5), levels[1][5 + 5 * 6]);
        assertEquals(
            MipmapHelper.weightedAverageColor(
                MipmapHelper.weightedAverageColor(levelOneBlock(0, 0), levelOneBlock(1, 0)),
                MipmapHelper.weightedAverageColor(levelOneBlock(0, 1), levelOneBlock(1, 1))),
            levels[2][0]);
        assertEquals(
            MipmapHelper.weightedAverageColor(
                MipmapHelper.weightedAverageColor(levelOneBlock(2, 2), levelOneBlock(3, 2)),
                MipmapHelper.weightedAverageColor(levelOneBlock(2, 3), levelOneBlock(3, 3))),
            levels[2][1 + 3]);
    }

    /** Every level 1 texel of a solid-block sprite is the box filter of the one block it covers. */
    @Test
    void levelOneOfASolidBlockSpriteShowsEachBlocksOwnColour() {
        assertLevelOneBlocks(12, 12);
        assertLevelOneBlocks(32, 8);
    }

    /**
     * The coverage model decides which fraction of a level survives the alpha test, with bilinear filtering
     * approximated by supersampling each texel quad on an interior 4x4 grid.
     */
    @Test
    void alphaTestCoverageMeasuresTheSurvivingFraction() {
        final int[] opaque = {OPAQUE_WHITE, OPAQUE_WHITE, OPAQUE_WHITE, OPAQUE_WHITE};
        final int[] clear = {TRANSPARENT, TRANSPARENT, TRANSPARENT, TRANSPARENT};
        final int[] oneOpaque = {OPAQUE_WHITE, TRANSPARENT, TRANSPARENT, TRANSPARENT};

        assertEquals(1.0f, MipmapGenerator.alphaTestCoverage(opaque, 2, 0.5f, 1.0f), 1e-6f);
        assertEquals(0.0f, MipmapGenerator.alphaTestCoverage(clear, 2, 0.5f, 1.0f), 1e-6f);

        // Only 3 of the 16 sample points inside the single texel quad land above the half-alpha reference.
        assertEquals(0.1875f, MipmapGenerator.alphaTestCoverage(oneOpaque, 2, 0.5f, 1.0f), 1e-6f);

        // Judging against the strict reference admits more of the same image, which is the whole point of the
        // strict cutout mode: thin shapes would otherwise vanish at high mip levels.
        assertEquals(0.375f, MipmapGenerator.alphaTestCoverage(oneOpaque, 2, 0.3f, 1.0f), 1e-6f);
    }

    /**
     * A target the level cannot reach at the current alpha scale has to move the scale, and the repaint has to
     * leave texels that were already fully transparent alone.
     */
    @Test
    void scaleAlphaToCoverageRescalesTowardsTheTarget() {
        final int[] level = {
            ColorARGB.pack(1, 2, 3, 255),
            ColorARGB.pack(4, 5, 6, 128),
            TRANSPARENT,
            TRANSPARENT
        };

        MipmapGenerator.scaleAlphaToCoverage(level, 2, 0.0f, 0.5f);

        assertEquals(133, ColorARGB.unpackAlpha(level[0]));
        assertEquals(70, ColorARGB.unpackAlpha(level[1]));
        assertEquals(0, ColorARGB.unpackAlpha(level[2]));
        assertEquals(0, ColorARGB.unpackAlpha(level[3]));
        assertEquals(0.0f, MipmapGenerator.alphaTestCoverage(level, 2, 0.5f, 1.0f), 1e-6f);
    }

    /** The scale stays where the target is already met, which is what keeps an unchanged level from drifting. */
    @Test
    void scaleAlphaToCoverageKeepsTheScaleWhenTheTargetIsMet() {
        final int[] level = {ColorARGB.pack(1, 2, 3, 255), ColorARGB.pack(4, 5, 6, 200)};

        MipmapGenerator.scaleAlphaToCoverage(level, 1, MipmapGenerator.alphaTestCoverage(level, 1, 0.5f, 1.0f), 0.5f);

        assertEquals(255, ColorARGB.unpackAlpha(level[0]));
        assertEquals(206, ColorARGB.unpackAlpha(level[1]));
    }

    private static void assertLevelsEqual(int[][] expected, int[][] actual) {
        assertEquals(expected.length, actual.length);
        for (int level = 0; level < expected.length; level++) {
            assertArrayEquals(expected[level], actual[level], "level " + level);
        }
    }

    /** A sprite whose every 2x2 texel block is one solid palette colour. */
    private static int[] solidBlocks(int width, int height) {
        final int[] base = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                base[x + y * width] = blockColorAt(x / 2, y / 2);
            }
        }
        return base;
    }

    private static int blockColorAt(int blockX, int blockY) {
        return BLOCK_PALETTE[(blockX + blockY * 3) & 3];
    }

    /** What one solid block comes out as at level 1, folded exactly the way the generator folds its texels. */
    private static int levelOneBlock(int blockX, int blockY) {
        final int color = blockColorAt(blockX, blockY);
        return MipmapHelper.weightedAverageColor(
            MipmapHelper.weightedAverageColor(color, color),
            MipmapHelper.weightedAverageColor(color, color));
    }

    /**
     * Walks one sprite size through the whole chain with a filtering and a cutout mode, and checks every level:
     * the levels the sprite supports are its box filter size, and the ones past it are a quarter of the level
     * above and all zero.
     */
    private static void assertCollapsedChainIsSafe(int width, int height, int mipLevel) {
        for (final MipmapStrategy strategy : new MipmapStrategy[] {MipmapStrategy.MEAN, MipmapStrategy.CUTOUT}) {
            final int[][] levels = assertDoesNotThrow(() -> MipmapGenerator.generateMipLevels(mipLevel, width,
                new int[][] {solidBlocks(width, height)}, strategy, true),
                strategy + " on a " + width + "x" + height + " sprite");

            assertEquals(mipLevel + 1, levels.length);
            for (int level = 1; level <= mipLevel; level++) {
                final int levelWidth = width >> level;
                final int levelHeight = height >> level;
                if (levelWidth > 0 && levelHeight > 0) {
                    assertEquals(levelWidth * levelHeight, levels[level].length, "level " + level);
                    continue;
                }
                assertEquals(levels[level - 1].length >> 2, levels[level].length, "level " + level);
                assertArrayEquals(new int[levels[level].length], levels[level], "level " + level);
            }
        }
    }

    private static void assertLevelOneBlocks(int width, int height) {
        final int[][] levels = MipmapGenerator.generateMipLevels(4, width, new int[][] {solidBlocks(width, height)},
            MipmapStrategy.MEAN, false);

        final int levelWidth = width >> 1;
        for (int y = 0; y < height >> 1; y++) {
            for (int x = 0; x < levelWidth; x++) {
                assertEquals(levelOneBlock(x, y), levels[1][x + y * levelWidth], "block " + x + "," + y);
            }
        }
    }
}
