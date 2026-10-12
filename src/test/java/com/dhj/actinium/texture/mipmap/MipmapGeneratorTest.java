package com.dhj.actinium.texture.mipmap;

import dhj.embeddedt.embeddium.api.util.ColorARGB;
import dhj.embeddedt.embeddium.impl.texture.MipmapHelper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
}
