package com.dhj.actinium.compat.celeritasdynamiclights;

import net.minecraft.util.math.BlockPos;

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
final class DynamicLightProviderImpl implements DynamicLightProvider {

    /**
     * The addon's static accessor returning the mod instance the merge method is called on. Present
     * with the same name and signature in addon 1.2.10 and 1.2.11.
     */
    private static final String INSTANCE_LOOKUP = "get";

    /**
     * The addon's position merge, the only member that knows how a level is applied. Present with
     * the same signature in addon 1.2.10 and 1.2.11; older versions lack it, and the lookup then
     * reports the missing method instead of merging.
     */
    private static final String MERGE_METHOD = "getLightmapWithDynamicLight";

    /** The addon's static accessor returning the mod instance the merge method is called on. */
    private final Method instanceLookup;

    /** The addon's position merge, the only member that knows how a level is applied. */
    private final Method merge;

    private DynamicLightProviderImpl(Method instanceLookup, Method merge) {
        this.instanceLookup = instanceLookup;
        this.merge = merge;
    }

    /**
     * Resolves the addon API from its class.
     *
     * @param api the {@code SodiumDynamicLights} class, already confirmed to be present
     * @throws NoSuchMethodException when the installed addon predates the position merge method
     */
    static DynamicLightProvider create(Class<?> api) throws NoSuchMethodException {
        final Method merge = api.getMethod(MERGE_METHOD, BlockPos.class, int.class);
        return new DynamicLightProviderImpl(api.getMethod(INSTANCE_LOOKUP), merge);
    }

    @Override
    public int mergeDynamicLight(BlockPos pos, int packedLight) {
        try {
            final Object lights = instanceLookup.invoke(null);
            if (lights == null) {
                CeleritasDynamicLightsCompat.disable(this, "SodiumDynamicLights.get() returned null", null);
                return packedLight;
            }
            final Object merged = merge.invoke(lights, pos, packedLight);
            if (!(merged instanceof Integer level)) {
                CeleritasDynamicLightsCompat.disable(
                    this,
                    MERGE_METHOD + " returned " + merged + " instead of an int",
                    null
                );
                return packedLight;
            }
            return level;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            CeleritasDynamicLightsCompat.disable(this, MERGE_METHOD + " failed for " + pos, failure);
            return packedLight;
        }
    }
}
