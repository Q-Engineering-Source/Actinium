package com.dhj.actinium.compat.celeritasdynamiclights;

import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Entry point for the optional dynamic light merge of Celeritas Dynamic Lights.
 *
 * <p>The addon merges its dynamic light into the combined light of the tile entity dispatcher, the
 * particle brightness and the held item lightmap on its own. The neighbour brightness computation in
 * {@code TileEntityLight} replaces the dispatcher's value with a packed light sampled from the world,
 * so it merges the addon's level again through this facade.</p>
 *
 * <p>The addon class is resolved once, on first use. A client without the addon never loads it: the
 * lookup fails with {@link ClassNotFoundException} and every later call returns the packed light it
 * was given.</p>
 */
public final class CeleritasDynamicLightsCompat {

    private static final String API_CLASS = "toni.sodiumdynamiclights.SodiumDynamicLights";

    private static final Logger LOGGER = LogManager.getLogger("ActiniumDynamicLightsCompat");

    private static final DynamicLightProvider PROVIDER = resolveProvider();

    private CeleritasDynamicLightsCompat() {
    }

    /**
     * Merges the dynamic light level at {@code pos} into a packed lightmap value.
     *
     * @param pos         the position the lightmap was sampled at
     * @param packedLight the sky and block light the renderer would use without dynamic lights
     * @return the packed light including the dynamic level, or {@code packedLight} unchanged when
     *         the addon is absent, predates the merge method, or failed once already
     */
    public static int mergeDynamicLight(BlockPos pos, int packedLight) {
        return PROVIDER.mergeDynamicLight(pos, packedLight);
    }

    private static DynamicLightProvider resolveProvider() {
        try {
            final Class<?> api = Class.forName(API_CLASS, false, CeleritasDynamicLightsCompat.class.getClassLoader());
            return ReflectiveDynamicLightProvider.resolve(api);
        } catch (ClassNotFoundException absent) {
            LOGGER.debug("Celeritas Dynamic Lights is not installed; dynamic light stays out of the tile entity brightness");
            return ReflectiveDynamicLightProvider.disabled();
        } catch (NoSuchMethodException staleVersion) {
            LOGGER.error(
                "Celeritas Dynamic Lights is installed but SodiumDynamicLights.getLightmapWithDynamicLight(BlockPos, int)"
                    + " is missing, so the tile entity dynamic light merge stays off for this launch",
                staleVersion
            );
            return ReflectiveDynamicLightProvider.disabled();
        }
    }
}
