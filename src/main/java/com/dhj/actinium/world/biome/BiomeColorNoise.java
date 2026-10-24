package com.dhj.actinium.world.biome;

import net.minecraft.world.biome.BiomeColorHelper;

import com.dhj.actinium.runtime.ActiniumRuntime;

/**
 * Stateless deterministic 2D value noise applied to biome-tinted block colors (issue #56, modeled
 * after Ambient Environment with three deliberate corrections). The noise is applied after blending,
 * to the final blended color keyed to the block's own position, because Ambient Environment injects
 * it inside the blend loop where the blend average cancels most of the per-position variation out.
 * Offsets are bidirectional: the scale factor is multiplicative {@code 1 +/- intensity}, so the noisy
 * color stays centered on the blended mean instead of mixing only towards black. The fade uses the
 * quintic smoothstep {@code 6t^5 - 15t^4 + 10t^3}, so neighboring blocks never show a hard seam.
 *
 * Integer lattice points are hashed to pseudo-random values in [0, 1) with a splitmix64-style mixer
 * (allocation free and purely functional, so chunk build threads and the main thread can sample
 * concurrently), and the four corners of the enclosing cell are bilinearly interpolated. Two octaves
 * are summed at roughly 2:1 weights (0.65 / 0.35), the second at double frequency, and the result is
 * remapped from [0, 1] to [-1, 1].
 */
public final class BiomeColorNoise {

    /**
     * Fixed noise seed. Deliberately not derived from the world: the noise is purely a local
     * surface texture, not world generation, so an identical pattern across worlds is harmless
     * (and unnoticeable), while a fixed seed keeps the rendered result and the cache behavior
     * reproducible across sessions.
     */
    private static final long SEED = 0x5EEDB10C41C0FF3EL;

    /**
     * Base frequency of the first octave: one noise cell spans 16 blocks, giving the tint a
     * gentle low-frequency mottling that stays visible even under large biome blend radii.
     */
    private static final double BASE_FREQUENCY = 1.0D / 16.0D;

    /**
     * Weights of the two octaves; roughly 2:1 in favor of the low frequency so the large-scale
     * pattern dominates while the doubled-frequency octave adds finer texture.
     */
    private static final double FIRST_OCTAVE_WEIGHT = 0.65D;
    private static final double SECOND_OCTAVE_WEIGHT = 0.35D;

    private BiomeColorNoise() {
    }

    /**
     * Samples the biome color noise field at the given world position.
     *
     * @return deterministic noise value in [-1, 1] for this position
     */
    public static float sample(float x, float z) {
        // Widen to double before scaling so very large block coordinates (tens of millions) do
        // not lose their low bits to float precision.
        double noiseX = x * BASE_FREQUENCY;
        double noiseZ = z * BASE_FREQUENCY;

        double combined = FIRST_OCTAVE_WEIGHT * valueNoise(noiseX, noiseZ)
                + SECOND_OCTAVE_WEIGHT * valueNoise(noiseX * 2.0D, noiseZ * 2.0D);

        // combined is in [0, 1]; remap to the symmetric [-1, 1] range.
        return (float) (combined * 2.0D - 1.0D);
    }

    /**
     * Scales each RGB channel of the given color by {@code 1 + amplitude * noise}, rounding and
     * clamping every channel into [0, 255] so saturated channels never wrap around.
     *
     * <p>The alpha byte is passed through unchanged: cached tint colors may arrive without the
     * 0xFF alpha byte set (plain 0x00RRGGBB), so the alpha bits must never be re-derived here.</p>
     *
     * @param color     ARGB (or RGB) tint color
     * @param amplitude configured intensity in [0, 1]; values &le; 0 disable the modification
     * @param noise     noise value in [-1, 1], usually from {@link #sample(float, float)}
     * @return the modified color, or the input color unchanged when there is nothing to apply
     */
    public static int apply(int color, float amplitude, float noise) {
        if (amplitude <= 0.0F || noise == 0.0F) {
            return color;
        }

        float factor = 1.0F + amplitude * noise;
        int red = roundAndClamp((color >> 16 & 0xFF) * factor);
        int green = roundAndClamp((color >> 8 & 0xFF) * factor);
        int blue = roundAndClamp((color & 0xFF) * factor);

        return (color & 0xFF000000) | red << 16 | green << 8 | blue;
    }

    /**
     * Applies the noise to a tint color, using the per-resolver intensity configured in the
     * quality settings. Vanilla's three color resolvers are recognized by identity (they are
     * private singletons in {@link BiomeColorHelper}, exposed to this mod through the access
     * transformer) and routed to their respective intensity fields.
     *
     * <p>Resolvers added by other mods are returned unchanged: their color semantics are unknown,
     * so guessing an intensity for them would be speculative. Callers that run on the biome color
     * cache path are expected to use this method too, keeping the noise decision in one place.</p>
     *
     * @param resolver the color resolver the tint was computed with
     * @param x        world x coordinate of the block
     * @param y        world y coordinate of the block; unused because the field is planar, kept
     *                 so every tint call site can pass the full position
     * @param z        world z coordinate of the block
     * @param color    tint color produced by the resolver
     * @return the noisy color, or the input color when the feature is disabled or the resolver is
     *         unknown
     */
    public static int applyForResolver(BiomeColorHelper.ColorResolver resolver, int x, int y, int z, int color) {
        if (!ActiniumRuntime.options().quality.useBiomeColorNoise) {
            return color;
        }

        float intensity;
        if (resolver == BiomeColorHelper.GRASS_COLOR) {
            intensity = ActiniumRuntime.options().quality.biomeColorNoiseGrassIntensity;
        } else if (resolver == BiomeColorHelper.FOLIAGE_COLOR) {
            intensity = ActiniumRuntime.options().quality.biomeColorNoiseFoliageIntensity;
        } else if (resolver == BiomeColorHelper.WATER_COLOR) {
            intensity = ActiniumRuntime.options().quality.biomeColorNoiseWaterIntensity;
        } else {
            return color;
        }

        return apply(color, intensity, sample(x, z));
    }

    /**
     * Evaluates 2D value noise at an arbitrary position in noise space. The four lattice corners
     * around the position are hashed independently and bilinearly interpolated using quintic fade
     * weights, which makes the field continuous (and C2-smooth across cell borders) everywhere.
     */
    private static double valueNoise(double x, double z) {
        int cellX = floor(x);
        int cellZ = floor(z);
        double fadeX = fade(x - cellX);
        double fadeZ = fade(z - cellZ);

        double v00 = corner(cellX, cellZ);
        double v10 = corner(cellX + 1, cellZ);
        double v01 = corner(cellX, cellZ + 1);
        double v11 = corner(cellX + 1, cellZ + 1);

        return lerp(lerp(v00, v10, fadeX), lerp(v01, v11, fadeX), fadeZ);
    }

    /**
     * Hashes an integer lattice point to a pseudo-random value in [0, 1). The coordinates are
     * mixed with distinct odd multipliers and pushed through the splitmix64 finalizer (xor /
     * multiply / shift cascade), producing a uniformly distributed 64-bit word whose top 53 bits
     * map losslessly onto the double mantissa range.
     */
    private static double corner(int x, int z) {
        long hash = SEED;
        hash ^= x * 0x9E3779B97F4A7C15L;
        hash ^= z * 0xC2B2AE3D27D4EB4FL;
        hash ^= hash >>> 30;
        hash *= 0xBF58476D1CE4E5B9L;
        hash ^= hash >>> 27;
        hash *= 0x94D049BB133111EBL;
        hash ^= hash >>> 31;

        return (hash >>> 11) * 0x1.0p-53;
    }

    /**
     * Quintic smoothstep {@code 6t^5 - 15t^4 + 10t^3}: slope and curvature both vanish at t=0
     * and t=1, which is what keeps the interpolated field seamless across cell borders.
     */
    private static double fade(double t) {
        return t * t * t * (t * (t * 6.0D - 15.0D) + 10.0D);
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }

    private static int roundAndClamp(float channel) {
        int rounded = Math.round(channel);
        return Math.min(255, Math.max(0, rounded));
    }
}
