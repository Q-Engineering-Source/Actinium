package com.dhj.actinium.compat.celeritasdynamiclights;

import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Entry point for the optional dynamic light merge of Celeritas Dynamic Lights.
 *
 * <p>The addon merges its dynamic light into the combined light of the tile entity dispatcher, the
 * particle brightness and the held item lightmap on its own. The tile entity light path recomputes or
 * re-caches that value, so it asks this facade to merge the addon's level into whatever packed light
 * it is about to return.</p>
 *
 * <p>The addon class is resolved once, on first use. A client without the addon never loads it: the
 * lookup fails with {@link ClassNotFoundException} and every later call returns the packed light it
 * was given.</p>
 */
public final class CeleritasDynamicLightsCompat {

    private static final String API_CLASS = "toni.sodiumdynamiclights.SodiumDynamicLights";

    /**
     * Logging owner of the bridge. Shared with the implementation so the resolution failure and the
     * call failure report under the same label.
     */
    static final Logger LOGGER = LogManager.getLogger("ActiniumDynamicLightsCompat");

    /**
     * The provider every merge is delegated to. A provider that fails during a call is replaced by
     * {@link DynamicLightProvider#NONE}, so a broken addon is called at most until its first failure.
     */
    private static volatile DynamicLightProvider provider = resolveProvider();

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
        return provider.mergeDynamicLight(pos, packedLight);
    }

    /**
     * Replaces a provider that failed during a call with {@link DynamicLightProvider#NONE} and
     * reports the failure once.
     *
     * @param failed the provider that reported the failure; a call that arrives after another thread
     *               already replaced it is ignored so the failure is logged once
     */
    static synchronized void disable(DynamicLightProvider failed, String detail, Throwable cause) {
        if (provider != failed) {
            return;
        }
        provider = DynamicLightProvider.NONE;
        LOGGER.error("Disabling the Celeritas Dynamic Lights bridge for this launch: {}", detail, cause);
    }

    private static DynamicLightProvider resolveProvider() {
        try {
            final Class<?> api = Class.forName(API_CLASS, false, CeleritasDynamicLightsCompat.class.getClassLoader());
            return DynamicLightProviderImpl.create(api);
        } catch (ClassNotFoundException absent) {
            LOGGER.debug("Celeritas Dynamic Lights is not installed; dynamic light stays out of the tile entity brightness");
            return DynamicLightProvider.NONE;
        } catch (NoSuchMethodException staleVersion) {
            LOGGER.error(
                "Celeritas Dynamic Lights is installed but SodiumDynamicLights.getLightmapWithDynamicLight(BlockPos, int)"
                    + " is missing, so the tile entity dynamic light merge stays off for this launch",
                staleVersion
            );
            return DynamicLightProvider.NONE;
        } catch (LinkageError brokenJar) {
            LOGGER.error(
                "Celeritas Dynamic Lights is installed but " + API_CLASS + " cannot be linked,"
                    + " so the tile entity dynamic light merge stays off for this launch",
                brokenJar
            );
            return DynamicLightProvider.NONE;
        }
    }
}
