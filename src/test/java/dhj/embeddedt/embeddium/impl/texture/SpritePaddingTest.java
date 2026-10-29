package dhj.embeddedt.embeddium.impl.texture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memAddress;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the replicated-edge padding that widens a sprite's stitcher slot.
 */
class SpritePaddingTest {

    private static final int[] GUTTERS = { 0, 1, 2, 4 };
    private static final int DST_X = 3;
    private static final int DST_Y = 2;
    private static final int MARGIN = 5;
    private static final byte SENTINEL = (byte) 0xCD;

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

    @ParameterizedTest(name = "gutter {0} at level {1} is {2}")
    @CsvSource({"16, 0, 16", "16, 1, 8", "16, 2, 4", "2, 0, 2", "1, 1, 0"})
    void gutterHalvesWithTheLevel(int gutter, int level, int expected) {
        assertEquals(expected, SpritePadding.gutterForLevel(gutter, level));
    }

    @Test
    void clippedHalvesReassembleWhole() {
        final Random rnd = new Random(987654321);

        for (int level = 0; level <= 4; level++) {
            for (int baseGutter : GUTTERS) {
                final int fLevel = level;
                final Level fixture = new Level(rnd, level, baseGutter);
                final int splitRow = fixture.paddedH <= 1 ? 0 : 1 + rnd.nextInt(fixture.paddedH - 1);
                final int size = fixture.strideBytes * fixture.totalHeight;

                for (boolean swapRB : new boolean[] { false, true }) {
                    final NativeBuffer whole = new NativeBuffer(size);
                    SpritePadding.writePaddedLevel(fixture.src, fixture.levelW, fixture.levelH, fixture.gutter,
                        whole.address, fixture.strideBytes, DST_X, DST_Y, DST_Y, DST_Y + fixture.paddedH, swapRB);

                    final NativeBuffer split = new NativeBuffer(size);
                    SpritePadding.writePaddedLevel(fixture.src, fixture.levelW, fixture.levelH, fixture.gutter,
                        split.address, fixture.strideBytes, DST_X, DST_Y, DST_Y, DST_Y + splitRow, swapRB);
                    SpritePadding.writePaddedLevel(fixture.src, fixture.levelW, fixture.levelH, fixture.gutter,
                        split.address, fixture.strideBytes, DST_X, DST_Y, DST_Y + splitRow, DST_Y + fixture.paddedH,
                        swapRB);

                    assertTrue(Arrays.equals(whole.snapshot(), split.snapshot()),
                        () -> "split reassembly mismatch level=" + fLevel + " gutter=" + baseGutter
                            + " swapRB=" + swapRB + " splitRow=" + splitRow);
                }
            }
        }
    }

    /** A stripe write must stay inside its own rows, because neighbouring stripes are written concurrently. */
    @Test
    void bytesOutsideRectAreUntouched() {
        final Random rnd = new Random(555111333);

        for (int level = 0; level <= 4; level++) {
            for (int baseGutter : GUTTERS) {
                final int fLevel = level;
                final Level fixture = new Level(rnd, level, baseGutter);
                final NativeBuffer nb = new NativeBuffer(fixture.strideBytes * fixture.totalHeight);

                SpritePadding.writePaddedLevel(fixture.src, fixture.levelW, fixture.levelH, fixture.gutter,
                    nb.address, fixture.strideBytes, DST_X, DST_Y, DST_Y, DST_Y + fixture.paddedH, false);
                final byte[] actual = nb.snapshot();

                for (int row = 0; row < fixture.totalHeight; row++) {
                    final boolean insideRow = row >= DST_Y && row < DST_Y + fixture.paddedH;
                    for (int col = 0; col < fixture.strideBytes; col++) {
                        final boolean insideCol = col >= DST_X * 4 && col < (DST_X + fixture.paddedW) * 4;
                        if (!(insideRow && insideCol)) {
                            final int index = row * fixture.strideBytes + col;
                            final int fRow = row;
                            final int fCol = col;
                            assertTrue(actual[index] == SENTINEL,
                                () -> "byte outside rect touched at row=" + fRow + " col=" + fCol
                                    + " level=" + fLevel + " gutter=" + baseGutter);
                        }
                    }
                }
            }
        }
    }

    /** The staging sink and the scratch array sink have to produce the same picture, including the BGRA swap. */
    @Test
    void intArraySinkMatchesNativeSink() {
        final Random rnd = new Random(24681012);
        final int gutter = 4;
        final int levels = 3;
        final int width = 32;
        final int height = 24;

        final int[][] frameData = new int[levels][];
        for (int level = 0; level < levels; level++) {
            final int[] data = new int[(width >> level) * (height >> level)];
            for (int i = 0; i < data.length; i++) {
                data[i] = rnd.nextInt();
            }
            frameData[level] = data;
        }

        final int[][] padded = SpritePadding.padFrame(frameData, width, height, gutter, null);

        for (int level = 0; level < levels; level++) {
            final int fLevel = level;
            final int lw = width >> level;
            final int lh = height >> level;
            final int lg = SpritePadding.gutterForLevel(gutter, level);
            final int paddedW = lw + 2 * lg;
            final int paddedH = lh + 2 * lg;
            final int strideBytes = paddedW * 4;

            final NativeBuffer nb = new NativeBuffer(strideBytes * paddedH);
            SpritePadding.writePaddedLevel(frameData[level], lw, lh, lg, nb.address, strideBytes, 0, 0, 0, paddedH, false);

            final int[] fromArray = padded[level];
            final byte[] expected = new byte[strideBytes * paddedH];
            for (int py = 0; py < paddedH; py++) {
                for (int px = 0; px < paddedW; px++) {
                    putLittleEndian(expected, py * strideBytes + px * 4, fromArray[py * paddedW + px]);
                }
            }

            assertTrue(Arrays.equals(expected, nb.snapshot()), () -> "int[] vs native sink mismatch level=" + fLevel);

            final NativeBuffer nbSwapped = new NativeBuffer(strideBytes * paddedH);
            SpritePadding.writePaddedLevel(frameData[level], lw, lh, lg, nbSwapped.address, strideBytes, 0, 0, 0,
                paddedH, true);

            final byte[] expectedSwapped = new byte[strideBytes * paddedH];
            for (int py = 0; py < paddedH; py++) {
                for (int px = 0; px < paddedW; px++) {
                    final int v = fromArray[py * paddedW + px];
                    final int swapped = (v & 0xFF00FF00) | ((v >> 16) & 0xFF) | ((v & 0xFF) << 16);
                    putLittleEndian(expectedSwapped, py * strideBytes + px * 4, swapped);
                }
            }

            assertTrue(Arrays.equals(expectedSwapped, nbSwapped.snapshot()),
                () -> "int[] vs native sink (swapRB) mismatch level=" + fLevel);
        }
    }

    private static void putLittleEndian(byte[] dst, int offset, int value) {
        dst[offset] = (byte) value;
        dst[offset + 1] = (byte) (value >>> 8);
        dst[offset + 2] = (byte) (value >>> 16);
        dst[offset + 3] = (byte) (value >>> 24);
    }

    private static final class Level {

        final int[] src;
        final int levelW;
        final int levelH;
        final int gutter;
        final int paddedW;
        final int paddedH;
        final int strideBytes;
        final int totalHeight;

        Level(Random rnd, int level, int baseGutter) {
            this.levelW = 1 + rnd.nextInt(64);
            this.levelH = 1 + rnd.nextInt(64);
            this.gutter = SpritePadding.gutterForLevel(baseGutter, level);
            this.src = new int[levelW * levelH];
            for (int i = 0; i < src.length; i++) {
                src[i] = rnd.nextInt();
            }
            this.paddedW = levelW + 2 * gutter;
            this.paddedH = levelH + 2 * gutter;
            this.strideBytes = (DST_X + paddedW + MARGIN) * 4;
            this.totalHeight = DST_Y + paddedH + MARGIN;
        }
    }

    private static final class NativeBuffer {

        final ByteBuffer buffer;
        final long address;

        NativeBuffer(int size) {
            this.buffer = ByteBuffer.allocateDirect(size);
            for (int i = 0; i < size; i++) {
                this.buffer.put(i, SENTINEL);
            }
            this.address = memAddress(this.buffer, 0);
        }

        byte[] snapshot() {
            final byte[] out = new byte[buffer.capacity()];
            for (int i = 0; i < out.length; i++) {
                out[i] = buffer.get(i);
            }
            return out;
        }
    }
}
