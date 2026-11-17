package toni.sodiumdynamiclights;

import net.minecraft.util.math.BlockPos;

/**
 * Stand-in for the Celeritas Dynamic Lights API class, present only in the test source set.
 *
 * <p>{@code CeleritasDynamicLightsCompat} resolves the addon by class name because the addon is not
 * a build dependency, so this class is what that lookup finds while the bridge tests run. Its merge
 * mirrors the addon's: the dynamic level replaces the packed block light when it is higher, the sky
 * light is kept, the lightmap sub-level bits are dropped, and a level of zero returns the packed
 * value verbatim.</p>
 */
public final class SodiumDynamicLights {

    private static final SodiumDynamicLights INSTANCE = new SodiumDynamicLights();

    private static int dynamicLightLevel;

    private SodiumDynamicLights() {
    }

    /** Returns the addon instance the bridge reaches the merge method through. */
    public static SodiumDynamicLights get() {
        return INSTANCE;
    }

    /** Sets the level this double reports at every position. */
    public static void setDynamicLightLevel(int level) {
        dynamicLightLevel = level;
    }

    /** Returns the packed lightmap merged with the configured dynamic level. */
    public int getLightmapWithDynamicLight(BlockPos pos, int lightmap) {
        if (dynamicLightLevel <= 0) {
            return lightmap;
        }
        final int skyLight = (lightmap >> 20) & 0xF;
        final int blockLight = Math.max((lightmap >> 4) & 0xF, dynamicLightLevel);
        return skyLight << 20 | blockLight << 4;
    }
}
