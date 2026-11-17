package com.dhj.actinium.compat.celeritasdynamiclights;

import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;

/**
 * Merges the dynamic light level of Celeritas Dynamic Lights through the addon's own API.
 *
 * <p>{@code toni.sodiumdynamiclights.SodiumDynamicLights} exists only while the addon is installed
 * and is not a build dependency, so no compiled class can name it. The addon declares no service
 * file and no interface for this query, and the only caller is a per-frame lightmap lookup, so there
 * is no compile-time or event-driven entry point to use instead. The two members are therefore
 * looked up once through reflection, and nothing of the addon is touched when it is absent.</p>
 *
 * <p>{@code getLightmapWithDynamicLight(BlockPos, int)} performs the merge itself: it takes the
 * maximum of the dynamic level and the packed block light, keeps the sky light and drops the
 * lightmap sub-level bits. This class only forwards the packed value and returns that result
 * unchanged, so applying it twice yields the same packed light as applying it once.</p>
 */
final class ReflectiveDynamicLightProvider implements DynamicLightProvider {

    private static final String INSTANCE_LOOKUP = "get";
    private static final String MERGE_METHOD = "getLightmapWithDynamicLight";

    private static final Logger LOGGER = LogManager.getLogger("ActiniumDynamicLightsCompat");

    private volatile Method instanceLookup;
    private volatile Method merge;

    private ReflectiveDynamicLightProvider(Method instanceLookup, Method merge) {
        this.instanceLookup = instanceLookup;
        this.merge = merge;
    }

    /**
     * Resolves the addon API from its class.
     *
     * @param api the {@code SodiumDynamicLights} class, already confirmed to be present
     * @throws NoSuchMethodException when the installed addon predates the position merge method
     */
    static DynamicLightProvider resolve(Class<?> api) throws NoSuchMethodException {
        final Method merge = api.getMethod(MERGE_METHOD, BlockPos.class, int.class);
        return new ReflectiveDynamicLightProvider(api.getMethod(INSTANCE_LOOKUP), merge);
    }

    /** Returns a provider that reports every lookup as unchanged. */
    static DynamicLightProvider disabled() {
        return new ReflectiveDynamicLightProvider(null, null);
    }

    @Override
    public int mergeDynamicLight(BlockPos pos, int packedLight) {
        final Method lookup = this.instanceLookup;
        final Method combine = this.merge;
        if (lookup == null || combine == null) {
            return packedLight;
        }
        try {
            final Object lights = lookup.invoke(null);
            if (lights == null) {
                disable("SodiumDynamicLights.get() returned null", null);
                return packedLight;
            }
            final Object merged = combine.invoke(lights, pos, packedLight);
            if (!(merged instanceof Integer level)) {
                disable(MERGE_METHOD + " returned " + merged + " instead of an int", null);
                return packedLight;
            }
            return level;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            disable(MERGE_METHOD + " failed for " + pos, failure);
            return packedLight;
        }
    }

    /**
     * Stops calling the addon after the first failure and reports it once.
     *
     * <p>Both method handles are cleared before the log line is written, so the per-frame lookup
     * returns the packed light without entering the reflection path again.</p>
     */
    private void disable(String detail, Throwable failure) {
        this.instanceLookup = null;
        this.merge = null;
        LOGGER.error("Disabling the Celeritas Dynamic Lights bridge for this launch: {}", detail, failure);
    }
}
