package net.coderbot.iris.pipeline.transform;

import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.coderbot.iris.Iris;
import net.coderbot.iris.gbuffer_overrides.matching.InputAvailability;
import net.coderbot.iris.gl.texture.TextureType;
import net.coderbot.iris.helpers.Tri;
import net.coderbot.iris.pipeline.transform.parameter.AttributeParameters;
import net.coderbot.iris.pipeline.transform.parameter.CeleritasTerrainParameters;
import net.coderbot.iris.pipeline.transform.parameter.ComputeParameters;
import net.coderbot.iris.pipeline.transform.parameter.DHParameters;
import net.coderbot.iris.pipeline.transform.parameter.Parameters;
import net.coderbot.iris.pipeline.transform.parameter.TextureStageParameters;
import net.coderbot.iris.pipeline.AdaptiveShadowBoundsStats;
import net.coderbot.iris.shaderpack.texture.TextureStage;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class TransformPatcher {

    private static final int MAX_CACHE_ENTRIES = 400;
    private static final Map<TransformPatcher.CacheKey, Map<PatchShaderType, String>> cache = new LinkedHashMap<>(MAX_CACHE_ENTRIES + 1, .75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, Map<PatchShaderType, String>> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    private static final boolean useCache = true;

    private enum CacheDomain {
        GRAPHICS("graphics"),
        COMPUTE("compute");

        private final String logLabel;

        CacheDomain(String logLabel) {
            this.logLabel = logLabel;
        }
    }

    private static final class CacheKey {
        final Parameters parameters;
        final String vertex;
        final String geometry;
        final String tessControl;
        final String tessEval;
        final String fragment;
        final String compute;
        final boolean adaptiveShadowBoundsInstrumentation;
        final int adaptiveShadowBoundsBinding;

        public CacheKey(Parameters parameters, String vertex, String geometry, String tessControl, String tessEval, String fragment) {
            this.parameters = parameters;
            this.vertex = vertex;
            this.geometry = geometry;
            this.tessControl = tessControl;
            this.tessEval = tessEval;
            this.fragment = fragment;
            this.compute = null;
            this.adaptiveShadowBoundsInstrumentation = AdaptiveShadowBoundsStats.isInstrumentationEnabled();
            this.adaptiveShadowBoundsBinding = this.adaptiveShadowBoundsInstrumentation
                ? AdaptiveShadowBoundsStats.getActiveBinding() : -1;
        }

        public CacheKey(Parameters parameters, String compute) {
            this.parameters = parameters;
            this.vertex = null;
            this.geometry = null;
            this.tessControl = null;
            this.tessEval = null;
            this.fragment = null;
            this.compute = compute;
            this.adaptiveShadowBoundsInstrumentation = AdaptiveShadowBoundsStats.isInstrumentationEnabled();
            this.adaptiveShadowBoundsBinding = this.adaptiveShadowBoundsInstrumentation
                ? AdaptiveShadowBoundsStats.getActiveBinding() : -1;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((fragment == null) ? 0 : fragment.hashCode());
            result = prime * result + ((geometry == null) ? 0 : geometry.hashCode());
            result = prime * result + ((tessControl == null) ? 0 : tessControl.hashCode());
            result = prime * result + ((tessEval == null) ? 0 : tessEval.hashCode());
            result = prime * result + ((parameters == null) ? 0 : parameters.hashCode());
            result = prime * result + ((vertex == null) ? 0 : vertex.hashCode());
            result = prime * result + ((compute == null) ? 0 : compute.hashCode());
            result = prime * result + (adaptiveShadowBoundsInstrumentation ? 1 : 0);
            result = prime * result + adaptiveShadowBoundsBinding;
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            final TransformPatcher.CacheKey other = (TransformPatcher.CacheKey) obj;
            return Objects.equals(fragment, other.fragment)
                && Objects.equals(geometry, other.geometry)
                && Objects.equals(tessControl, other.tessControl)
                && Objects.equals(tessEval, other.tessEval)
                && Objects.equals(parameters, other.parameters)
                && Objects.equals(vertex, other.vertex)
                && Objects.equals(compute, other.compute)
                && adaptiveShadowBoundsInstrumentation == other.adaptiveShadowBoundsInstrumentation
                && adaptiveShadowBoundsBinding == other.adaptiveShadowBoundsBinding;
        }
    }

    private static Map<PatchShaderType, String> transform(String vertex, String geometry, String tessControl, String tessEval, String fragment, Parameters parameters) {
        if (vertex == null && geometry == null && tessControl == null && tessEval == null && fragment == null) {
            return null;
        }

        final CacheKey key = new CacheKey(parameters, vertex, geometry, tessControl, tessEval, fragment);
        final boolean logCacheEvents = GLSMPerfDebug.isEnabled();
        if (useCache) {
            final Map<PatchShaderType, String> cached = getCached(key, CacheDomain.GRAPHICS, logCacheEvents);
            if (cached != null) {
                return cached;
            }
        }

        final long transformStart = logCacheEvents ? System.nanoTime() : 0L;
        final Map<PatchShaderType, String> transformed = ShaderTransformer.transform(vertex, geometry, tessControl, tessEval, fragment, parameters);
        if (!useCache) {
            return finishWithoutCache(transformed, CacheDomain.GRAPHICS, transformStart, logCacheEvents);
        }

        return cacheResult(key, transformed, CacheDomain.GRAPHICS, transformStart, logCacheEvents);
    }

    static Map<PatchShaderType, String> transformCompute(String compute, Parameters parameters) {
        if (compute == null) {
            return null;
        }

        final CacheKey key = new CacheKey(parameters, compute);
        final boolean logCacheEvents = GLSMPerfDebug.isEnabled();
        if (useCache) {
            final Map<PatchShaderType, String> cached = getCached(key, CacheDomain.COMPUTE, logCacheEvents);
            if (cached != null) {
                return cached;
            }
        }

        final long transformStart = logCacheEvents ? System.nanoTime() : 0L;
        final Map<PatchShaderType, String> transformed = ShaderTransformer.transformCompute(compute, parameters);
        if (!useCache) {
            return finishWithoutCache(transformed, CacheDomain.COMPUTE, transformStart, logCacheEvents);
        }

        return cacheResult(key, transformed, CacheDomain.COMPUTE, transformStart, logCacheEvents);
    }

    private static Map<PatchShaderType, String> getCached(CacheKey key, CacheDomain domain, boolean logCacheEvents) {
        final Map<PatchShaderType, String> cached;
        synchronized (cache) {
            cached = cache.get(key);
        }
        if (cached != null && logCacheEvents) {
            Iris.logger.info("[ShaderTransformCache] {} hit cacheSize={}", domain.logLabel, cacheSize());
        }
        return cached;
    }

    private static Map<PatchShaderType, String> cacheResult(CacheKey key, Map<PatchShaderType, String> transformed,
                                                             CacheDomain domain, long transformStart, boolean logCacheEvents) {
        final Map<PatchShaderType, String> immutable = immutableResult(transformed);
        final Map<PatchShaderType, String> result;
        final boolean reused;
        final int size;
        synchronized (cache) {
            // Another transform may have completed while this caller was parsing GLSL.
            final Map<PatchShaderType, String> existing = cache.get(key);
            if (existing != null) {
                result = existing;
                reused = true;
            } else {
                cache.put(key, immutable);
                result = immutable;
                reused = false;
            }
            size = cache.size();
        }
        if (logCacheEvents) {
            final long transformNanos = System.nanoTime() - transformStart;
            Iris.logger.info("[ShaderTransformCache] {} {} transformMs={} cacheSize={}",
                domain.logLabel, reused ? "raceReuse" : "miss", transformNanos / 1_000_000.0, size);
        }
        return result;
    }

    private static Map<PatchShaderType, String> finishWithoutCache(Map<PatchShaderType, String> transformed,
                                                                     CacheDomain domain, long transformStart, boolean logCacheEvents) {
        final Map<PatchShaderType, String> immutable = immutableResult(transformed);
        if (logCacheEvents) {
            final long transformNanos = System.nanoTime() - transformStart;
            Iris.logger.info("[ShaderTransformCache] {} uncached transformMs={}", domain.logLabel,
                transformNanos / 1_000_000.0);
        }
        return immutable;
    }

    private static int cacheSize() {
        synchronized (cache) {
            return cache.size();
        }
    }

    private static Map<PatchShaderType, String> immutableResult(Map<PatchShaderType, String> transformed) {
        final EnumMap<PatchShaderType, String> copy = new EnumMap<>(PatchShaderType.class);
        copy.putAll(transformed);
        return Collections.unmodifiableMap(copy);
    }

    public static Map<PatchShaderType, String> patchAttributes(String vertex, String geometry, String tessControl, String tessEval, String fragment, InputAvailability inputs) {
        return transform(vertex, geometry, tessControl, tessEval, fragment, new AttributeParameters(Patch.ATTRIBUTES, geometry != null, inputs));
    }

    public static Map<PatchShaderType, String> patchAttributes(String vertex, String geometry, String fragment, InputAvailability inputs) {
        return patchAttributes(vertex, geometry, null, null, fragment, inputs);
    }

    public static Map<PatchShaderType, String> patchCeleritasTerrain(String vertex, String geometry, String fragment) {
        return transform(vertex, geometry, null, null, fragment, new CeleritasTerrainParameters(Patch.CELERITAS_TERRAIN));
    }

    public static Map<PatchShaderType, String> patchComposite(String vertex, String geometry, String fragment) {
        return patchComposite(vertex, geometry, null, null, fragment, TextureStage.COMPOSITE_AND_FINAL, null);
    }

    public static Map<PatchShaderType, String> patchComposite(String vertex, String geometry, String tessControl, String tessEval, String fragment, TextureStage stage, Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap) {
        return transform(vertex, geometry, tessControl, tessEval, fragment, new TextureStageParameters(Patch.COMPOSITE, stage, textureMap));
    }


    public static Map<PatchShaderType, String> patchDHTerrain(
        String name, String vertex, String tessControl, String tessEval, String geometry, String fragment,
        Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap) {
        return transform(vertex, geometry, tessControl, tessEval, fragment,
            new DHParameters(Patch.DH_TERRAIN, textureMap));
    }


    public static Map<PatchShaderType, String> patchDHGeneric(
        String name, String vertex, String tessControl, String tessEval, String geometry, String fragment,
        Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap) {
        return transform(vertex, geometry, tessControl, tessEval, fragment,
            new DHParameters(Patch.DH_GENERIC, textureMap));

    }

    public static Map<PatchShaderType, String> patchComposite(String vertex, String geometry, String fragment, TextureStage stage, Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap) {
        return patchComposite(vertex, geometry, null, null, fragment, stage, textureMap);
    }

    public static String patchCompute(String name, String compute, TextureStage stage, Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap) {
        Map<PatchShaderType, String> result = transformCompute(compute, new ComputeParameters(Patch.COMPUTE, stage, textureMap));
        return result != null ? result.get(PatchShaderType.COMPUTE) : null;
    }

    public static void clearCache() {
        final int cachedEntries;
        synchronized (cache) {
            cachedEntries = cache.size();
            cache.clear();
        }
        if (GLSMPerfDebug.isEnabled()) {
            Iris.logger.info("[ShaderTransformCache] cleared entries={}", cachedEntries);
        }
        ShaderTransformer.clearSessionState();
    }
}
