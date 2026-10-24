package com.dhj.actinium.world.biome;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeColorHelper;
import com.dhj.actinium.world.WorldSlice;
import com.dhj.actinium.runtime.ActiniumRuntime;

import dhj.embeddedt.embeddium.impl.util.position.PositionalSupplier;

// The base class lives in celeritas-common under the same simple name, so it can only be
// referenced here by its fully qualified name (a same-name import would be shadowed by this
// class declaration and resolve to itself).
public class BiomeColorCache extends dhj.embeddedt.embeddium.impl.biome.BiomeColorCache<Biome, BiomeColorHelper.ColorResolver> {
    private final BlockPos.MutableBlockPos biomeCursor = new BlockPos.MutableBlockPos();

    public BiomeColorCache(WorldSlice slice, int blendRadius) {
        this(slice::getBiome, blendRadius);
    }

    /**
     * Constructs a cache from an arbitrary biome data source. Motivation: unit tests need to
     * instantiate the cache to verify the noise hook overrides, but {@link WorldSlice} requires a
     * live {@code World} that does not exist in a test JVM. Production code keeps using the
     * slice-based constructor.
     */
    BiomeColorCache(PositionalSupplier<Biome> biomeData, int blendRadius) {
        super(biomeData, blendRadius);
    }

    @Override
    protected int resolveColor(BiomeColorHelper.ColorResolver colorResolver, Biome biome, int relativeX, int relativeY, int relativeZ) {
        return colorResolver.getColorAtPos(biome, biomeCursor.setPos(relativeX, relativeY, relativeZ));
    }

    /**
     * Enables the post-processing pass only when the noise can actually change a color: with the
     * toggle off, or every configured intensity zero, the noise is a mathematical identity, so
     * skipping the per-pixel pass keeps slice population free of the extra loop.
     */
    @Override
    protected boolean shouldPostProcessColors() {
        var quality = ActiniumRuntime.options().quality;
        return quality.useBiomeColorNoise
                && (quality.biomeColorNoiseGrassIntensity > 0.0F
                    || quality.biomeColorNoiseFoliageIntensity > 0.0F
                    || quality.biomeColorNoiseWaterIntensity > 0.0F);
    }

    /**
     * Applies the position-based biome color noise on the chunk build thread, after biome blending
     * has produced the final color. {@link BiomeColorNoise#applyForResolver} must stay a pure
     * function of its arguments (fixed seed, no shared mutable state), as required by the base
     * class hook.
     */
    @Override
    protected int postProcessColor(BiomeColorHelper.ColorResolver resolver, int worldX, int worldY, int worldZ, int color) {
        return BiomeColorNoise.applyForResolver(resolver, worldX, worldY, worldZ, color);
    }
}
