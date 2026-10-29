package com.dhj.actinium.texture;

import net.minecraft.client.resources.data.AnimationMetadataSection;

import java.util.List;

/**
 * Interpolates animated sprite frames without losing alpha changes or bleeding invisible RGB.
 * Adapted from Angelica's InterpolatedIcon alpha interpolation (01ff612d).
 * The original InterpolatedIcon was created by ganymedes01 for Et Futurum and
 * ported to NotFine and cleaned up by Roadhog360.
 *
 * @author ganymedes01
 * @author Roadhog360
 */
public final class SpriteInterpolation {
    /** Defines whether ARGB bytes describe opacity or independent texture data. */
    public enum Mode {
        ALPHA_WEIGHTED_RGB,
        INDEPENDENT_CHANNELS
    }

    private SpriteInterpolation() {
    }

    /**
     * Produces the current animation's interpolated mip levels, reusing the previous output.
     * Returns null when vanilla would not upload an interpolated frame.
     */
    public static int[][] interpolate(List<int[][]> frames, AnimationMetadataSection metadata,
                                      int frameCounter, int tickCounter, int[][] output) {
        return interpolate(frames, metadata, frameCounter, tickCounter, output, Mode.ALPHA_WEIGHTED_RGB);
    }

    /** Interpolates an animated pixel chain, optionally treating alpha as an independent data channel. */
    public static int[][] interpolate(List<int[][]> frames, AnimationMetadataSection metadata,
                                      int frameCounter, int tickCounter, int[][] output,
                                      Mode mode) {
        if (mode == null) {
            throw new NullPointerException("mode");
        }
        int currentFrame = metadata.getFrameIndex(frameCounter);
        int frameCount = metadata.getFrameCount() == 0 ? frames.size() : metadata.getFrameCount();
        int nextFrame = metadata.getFrameIndex((frameCounter + 1) % frameCount);
        if (currentFrame == nextFrame || nextFrame < 0 || nextFrame >= frames.size()) {
            return null;
        }

        int[][] current = frames.get(currentFrame);
        int[][] next = frames.get(nextFrame);
        if (output == null || output.length != current.length) {
            output = new int[current.length][];
        }
        double nextWeight = tickCounter / (double) metadata.getFrameTimeSingle(frameCounter);
        for (int mip = 0; mip < current.length; mip++) {
            if (output[mip] == null || output[mip].length != current[mip].length) {
                output[mip] = new int[current[mip].length];
            }
            if (mip < next.length && current[mip].length == next[mip].length) {
                for (int pixel = 0; pixel < current[mip].length; pixel++) {
                    output[mip][pixel] = mode == Mode.INDEPENDENT_CHANNELS
                            ? mixChannelsIndependently(current[mip][pixel], next[mip][pixel], nextWeight)
                            : mix(current[mip][pixel], next[mip][pixel], nextWeight);
                }
            }
        }
        return output;
    }

    /** Mixes ARGB pixels using interpolated alpha as the weight of each color contribution. */
    static int mix(int current, int next, double nextWeight) {
        double currentWeight = 1.0 - nextWeight;
        double currentAlpha = (current >>> 24) * currentWeight;
        double nextAlpha = (next >>> 24) * nextWeight;
        double alpha = currentAlpha + nextAlpha;
        double colorCurrentWeight = alpha == 0.0 ? currentWeight : currentAlpha / alpha;
        double colorNextWeight = alpha == 0.0 ? nextWeight : nextAlpha / alpha;
        int red = (int) Math.round(((current >>> 16) & 255) * colorCurrentWeight
                + ((next >>> 16) & 255) * colorNextWeight);
        int green = (int) Math.round(((current >>> 8) & 255) * colorCurrentWeight
                + ((next >>> 8) & 255) * colorNextWeight);
        int blue = (int) Math.round((current & 255) * colorCurrentWeight + (next & 255) * colorNextWeight);
        return (int) Math.round(alpha) << 24 | red << 16 | green << 8 | blue;
    }

    /** Interpolates ARGB channels as independent data rather than treating alpha as pixel opacity. */
    static int mixChannelsIndependently(int current, int next, double nextWeight) {
        final double currentWeight = 1.0 - nextWeight;
        final int alpha = interpolateChannel(current >>> 24, next >>> 24, currentWeight, nextWeight);
        final int red = interpolateChannel((current >>> 16) & 255, (next >>> 16) & 255, currentWeight, nextWeight);
        final int green = interpolateChannel((current >>> 8) & 255, (next >>> 8) & 255, currentWeight, nextWeight);
        final int blue = interpolateChannel(current & 255, next & 255, currentWeight, nextWeight);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static int interpolateChannel(int current, int next, double currentWeight, double nextWeight) {
        return (int) Math.round(current * currentWeight + next * nextWeight);
    }
}
