package com.dhj.actinium.texture;

import net.minecraft.client.resources.data.AnimationFrame;
import net.minecraft.client.resources.data.AnimationMetadataSection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SpriteInterpolationTest {
    @Test
    void transparentFrameDoesNotTintTheVisibleFrame() {
        assertEquals(0x800000ff, SpriteInterpolation.mix(0x00ff0000, 0xff0000ff, 0.5));
        assertEquals(0x800000ff, SpriteInterpolation.mix(0xff0000ff, 0x00ff0000, 0.5));
    }

    @Test
    void partiallyTransparentColorsUseAlphaWeightedContributions() {
        assertEquals(0xc05500aa, SpriteInterpolation.mix(0x80ff0000, 0xff0000ff, 0.5));
    }

    @Test
    void opaqueAndFullyTransparentFramesHaveStableEndpoints() {
        assertEquals(0xff800080, SpriteInterpolation.mix(0xffff0000, 0xff0000ff, 0.5));
        assertEquals(0x00800080, SpriteInterpolation.mix(0x00ff0000, 0x000000ff, 0.5));
        assertEquals(0x80ff0000, SpriteInterpolation.mix(0x80ff0000, 0xff0000ff, 0.0));
        assertEquals(0xff0000ff, SpriteInterpolation.mix(0x80ff0000, 0xff0000ff, 1.0));
    }

    @Test
    void animationUsesFrameMetadataAndReusesAllMipLevels() {
        AnimationMetadataSection metadata = new AnimationMetadataSection(
                List.of(new AnimationFrame(1, 4), new AnimationFrame(0, 4)), 2, 2, 4, true);
        List<int[][]> frames = List.of(
                new int[][]{{0xff0000ff, 0xff0000ff}, {0xff0000ff}},
                new int[][]{{0x00ff0000, 0x00ff0000}, {0x00ff0000}});
        int[][] output = {{0, 0}, {0}};

        assertSame(output, SpriteInterpolation.interpolate(frames, metadata, 0, 2, output));
        assertArrayEquals(new int[]{0x800000ff, 0x800000ff}, output[0]);
        assertArrayEquals(new int[]{0x800000ff}, output[1]);
    }

    @Test
    void repeatedFrameDoesNotRequestAnUpload() {
        AnimationMetadataSection metadata = new AnimationMetadataSection(
                List.of(new AnimationFrame(0, 4), new AnimationFrame(0, 4)), 1, 1, 4, true);

        assertNull(SpriteInterpolation.interpolate(List.<int[][]>of(new int[][]{{0xffffffff}}),
                metadata, 0, 2, null));
    }
}
