package com.dhj.actinium.world.biome;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

import java.util.Arrays;
import java.util.function.ToIntFunction;

/** Caches the sky tint at the four corners of the camera's current world cell. */
public final class SkyColorCache {
    private final ToIntFunction<BlockPos> sampler;
    private final BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();
    private final double[][] colors = new double[4][3];
    private long lastTick;
    private int lastX;
    private int lastY;
    private int lastZ;
    private int lastRadius;

    public SkyColorCache(World world) {
        this(pos -> {
            Biome biome = world.getBiome(pos);
            return biome.getSkyColorByTemp(biome.getTemperature(pos));
        });
    }

    SkyColorCache(ToIntFunction<BlockPos> sampler) {
        this.sampler = sampler;
    }

    public int getColor(long tick, Entity camera, float partialTicks, int radius) {
        return getColor(tick,
                MathHelper.clampedLerp(camera.lastTickPosX, camera.posX, partialTicks),
                MathHelper.clampedLerp(camera.lastTickPosY, camera.posY, partialTicks),
                MathHelper.clampedLerp(camera.lastTickPosZ, camera.posZ, partialTicks), radius);
    }

    int getColor(long tick, double x, double y, double z, int radius) {
        int blockX = MathHelper.floor(x);
        int blockY = MathHelper.floor(y);
        int blockZ = MathHelper.floor(z);
        if (tick != lastTick || blockX != lastX || blockY != lastY || blockZ != lastZ || radius != lastRadius) {
            updateAverages(blockX, blockY, blockZ, radius);
            lastTick = tick;
            lastX = blockX;
            lastY = blockY;
            lastZ = blockZ;
            lastRadius = radius;
        }

        double dx = x - blockX;
        double dz = z - blockZ;
        int color = 0;
        for (int channel = 0; channel < 3; channel++) {
            double north = colors[0][channel] + (colors[1][channel] - colors[0][channel]) * dx;
            double south = colors[2][channel] + (colors[3][channel] - colors[2][channel]) * dx;
            color = (color << 8) | (int) Math.round(north + (south - north) * dz);
        }
        return color;
    }

    private void updateAverages(int x, int y, int z, int radius) {
        for (double[] color : colors) {
            Arrays.fill(color, 0);
        }

        // The four sampling windows overlap, so visit their union once.
        for (int dx = -radius; dx <= radius + 1; dx++) {
            for (int dz = -radius; dz <= radius + 1; dz++) {
                int color = sampler.applyAsInt(samplePos.setPos(x + dx, y, z + dz));
                int red = (color >> 16) & 255;
                int green = (color >> 8) & 255;
                int blue = color & 255;
                if (dz <= radius) {
                    if (dx <= radius) add(colors[0], red, green, blue);
                    if (dx > -radius) add(colors[1], red, green, blue);
                }
                if (dz > -radius) {
                    if (dx <= radius) add(colors[2], red, green, blue);
                    if (dx > -radius) add(colors[3], red, green, blue);
                }
            }
        }
        int diameter = 2 * radius + 1;
        double count = diameter * diameter;
        for (double[] color : colors) {
            for (int channel = 0; channel < color.length; channel++) {
                color[channel] /= count;
            }
        }
    }

    private static void add(double[] sum, int red, int green, int blue) {
        sum[0] += red;
        sum[1] += green;
        sum[2] += blue;
    }
}
