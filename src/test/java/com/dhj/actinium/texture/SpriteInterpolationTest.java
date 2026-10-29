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
    void pbrDataChannelsInterpolateIndependentlyOfAlpha() {
        assertEquals(0x80806070,
                SpriteInterpolation.mixChannelsIndependently(0x402080c0, 0xc0e04020, 0.5));
        assertEquals(0x80800080, SpriteInterpolation.mixChannelsIndependently(0x00ff0000, 0xff0000ff, 0.5));
        assertEquals(0x800000ff, SpriteInterpolation.mix(0x00ff0000, 0xff0000ff, 0.5),
                "albedo alpha still weights its color so transparent RGB does not tint visible pixels");
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
    void animationKeepsAlbedoAndPbrChannelPoliciesDistinct() {
        AnimationMetadataSection metadata = new AnimationMetadataSection(
                List.of(new AnimationFrame(0, 4), new AnimationFrame(1, 4)), 1, 1, 4, true);
        List<int[][]> frames = List.of(new int[][]{{0x402080c0}}, new int[][]{{0xc0e04020}});

        int[][] pbrOutput = SpriteInterpolation.interpolate(frames, metadata, 0, 2, null,
                SpriteInterpolation.Mode.INDEPENDENT_CHANNELS);
        int[][] albedoOutput = SpriteInterpolation.interpolate(frames, metadata, 0, 2, null,
                SpriteInterpolation.Mode.ALPHA_WEIGHTED_RGB);

        assertArrayEquals(new int[]{0x80806070}, pbrOutput[0],
                "normal and specular alpha values are data and must not weight RGB interpolation");
        assertArrayEquals(new int[]{0x80b05048}, albedoOutput[0],
                "albedo keeps alpha-weighted RGB interpolation to avoid transparent-color bleed");
    }

    @Test
    void repeatedFrameDoesNotRequestAnUpload() {
        AnimationMetadataSection metadata = new AnimationMetadataSection(
                List.of(new AnimationFrame(0, 4), new AnimationFrame(0, 4)), 1, 1, 4, true);

        assertNull(SpriteInterpolation.interpolate(List.<int[][]>of(new int[][]{{0xffffffff}}),
                metadata, 0, 2, null));
    }
}
