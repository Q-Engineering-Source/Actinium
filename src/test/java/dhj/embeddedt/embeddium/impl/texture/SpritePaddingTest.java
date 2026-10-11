package dhj.embeddedt.embeddium.impl.texture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the replicated-edge padding that widens a sprite's stitcher slot.
 */
class SpritePaddingTest {

    @ParameterizedTest(name = "gutter at {1} mip levels on the block atlas is {0}")
    @CsvSource({
        "1, 0, true",
        "4, 2, true",
        "16, 4, true"
    })
    void gutterIsPowerOfTwoMipWidth(int expected, int mipmapLevels, boolean terrainAtlas) {
        assertEquals(expected, SpritePadding.gutterFor(mipmapLevels, terrainAtlas));
    }

    /**
     * The gutter is halved once per mip level, and a slot's origin has to stay on a texel of every level, so
     * the gutter and the doubled border have to divide evenly by 2^mipmapLevels.
     */
    @ParameterizedTest(name = "gutter and slot border stay texel aligned at {0} mip levels")
    @CsvSource({"1", "2", "3", "4"})
    void gutterStaysAlignedAtEveryMipLevel(int mipmapLevels) {
        final int gutter = SpritePadding.gutterFor(mipmapLevels, true);
        final int border = 2 * gutter;

        for (int level = 0; level <= mipmapLevels; level++) {
            assertEquals(0, gutter % (1 << level), "the gutter must halve evenly at level " + level);
            assertEquals(0, border % (1 << level), "the slot border must halve evenly at level " + level);
        }

        assertTrue(gutter >= 1 << mipmapLevels, "the gutter must survive being halved once per level");
    }

    @Test
    void nonTerrainAtlasIsLeftUnpaddedWithoutMipmaps() {
        assertEquals(0, SpritePadding.gutterFor(0, false));
    }

    /**
     * The block atlas keeps a one texel gutter at mip 0 so grazing-angle and anisotropic taps land in a
     * replicated edge instead of the neighbouring sprite.
     */
    @Test
    void blockAtlasKeepsOneTexelAtMipZero() {
        assertEquals(1, SpritePadding.gutterFor(0, true));
    }

    @Test
    void padsContentWithReplicatedEdges() {
        final int[] level0 = {
            1, 2, 3,
            4, 5, 6,
            7, 8, 9
        };

        final int[][] padded = SpritePadding.padFrame(new int[][] {level0}, 3, 3, 1, null);
        final int[] result = padded[0];

        assertEquals(5 * 5, result.length, "a one texel gutter maps 3x3 onto 5x5");
        assertArrayEquals(new int[] {
            1, 1, 2, 3, 3,
            1, 1, 2, 3, 3,
            4, 4, 5, 6, 6,
            7, 7, 8, 9, 9,
            7, 7, 8, 9, 9
        }, result);
    }

    /**
     * Every mip level carries its own border, so a bilinear tap on a coarse level also stays inside the sprite.
     */
    @Test
    void padsEachMipLevelWithItsOwnBorder() {
        final int[] level0 = {
            1, 2, 3, 4,
            5, 6, 7, 8,
            9, 10, 11, 12,
            13, 14, 15, 16
        };
        final int[] level1 = {
            3, 5,
            11, 13
        };

        final int[][] padded = SpritePadding.padFrame(new int[][] {level0, level1}, 4, 4, 2, null);

        assertEquals(8 * 8, padded[0].length, "level 0 keeps its 2 texel gutter");
        assertEquals(4 * 4, padded[1].length, "the level 1 gutter halves with the level");
        assertEquals(1, padded[0][2 * 8 + 2], "level 0 content starts one gutter inside the padded row");
        assertEquals(3, padded[1][1 * 4 + 1], "level 1 content starts one border inside");
        assertEquals(3, padded[1][0], "the coarse border replicates the nearest content texel");
        assertEquals(13, padded[1][4 * 3 + 3], "the far corner replicates the far content corner");
    }

    @Test
    void leavesSourceFrameDataUntouched() {
        final int[] level0 = {1, 2, 3, 4};
        final int[][] source = {level0};

        SpritePadding.padFrame(source, 2, 2, 2, null);

        assertArrayEquals(new int[] {1, 2, 3, 4}, source[0]);
    }

    /**
     * The scratch array is reused between uploads, so a second frame has to overwrite the previous border
     * rather than keep it.
     */
    @Test
    void reusedScratchDropsThePreviousFrame() {
        final int[] first = {1, 1, 1, 1};
        final int[] second = {2, 2, 2, 2};

        final int[][] scratch = SpritePadding.padFrame(new int[][] {first}, 2, 2, 1, null);
        final int[][] reused = SpritePadding.padFrame(new int[][] {second}, 2, 2, 1, scratch);

        assertEquals(4 * 4, reused[0].length);
        assertArrayEquals(new int[] {
            2, 2, 2, 2,
            2, 2, 2, 2,
            2, 2, 2, 2,
            2, 2, 2, 2
        }, reused[0]);
    }
}
