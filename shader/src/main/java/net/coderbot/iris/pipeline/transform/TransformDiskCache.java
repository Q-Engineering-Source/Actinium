package net.coderbot.iris.pipeline.transform;

import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.shader.ShaderDiskCache;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.coderbot.iris.Iris;
import net.coderbot.iris.gl.texture.TextureType;
import net.coderbot.iris.helpers.Tri;
import net.coderbot.iris.pipeline.transform.parameter.AttributeParameters;
import net.coderbot.iris.pipeline.transform.parameter.CeleritasTerrainParameters;
import net.coderbot.iris.pipeline.transform.parameter.DHParameters;
import net.coderbot.iris.pipeline.transform.parameter.Parameters;
import net.coderbot.iris.pipeline.transform.parameter.TextureStageParameters;
import net.coderbot.iris.shaderpack.texture.TextureStage;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Persists {@link TransformPatcher} results across game sessions, one cache entry per transformed stage set.
 *
 * <p>The in-memory cache in {@link TransformPatcher} is keyed by live {@link Parameters} instances, whose hash
 * codes depend on identity hashes of enums and are therefore useless after a restart. This layer rebuilds the
 * same inputs as content-addressed strings so a second launch of the same shader pack can skip the whole AST
 * pipeline. It only stores results; {@link TransformPatcher} stays the single owner of the in-memory cache and of
 * the actual transform call, so a cache miss and a corrupted entry both fall back to the same code path as before.
 *
 * <p>Every input that changes the produced GLSL has to be part of the key. That means the five stage sources, the
 * patch and parameter family, every {@link Parameters} subclass field, the texture stage map, the adaptive shadow
 * bounds instrumentation captured by {@link TransformPatcher}, and the OpenGL capabilities that
 * {@link ShaderTransformer} reads through {@link RenderSystem} when it hoists and negotiates GLSL versions.
 * Adding a field to a {@link Parameters} subclass without extending {@link #appendParameters} changes transform
 * output without changing the key, so the parameter family is matched exhaustively instead of by a wildcard.
 */
final class TransformDiskCache {

    /** Cache layer holding AST transform results; kept apart from the preprocessing and program binary layers. */
    private static final String LAYER = "iris-transform";

    /**
     * Version of the persisted entry layout together with the transform semantics that produced it.
     *
     * <p>Bump this whenever a change alters transform output without altering any key input, for example a new
     * transformer pass or a changed injected snippet. Installed jars also change the cache build salt, but the salt
     * is not part of this contract and a bumped layer version keeps stale entries unreachable regardless.
     */
    private static final String FORMAT_VERSION = "iris-ast-v1";

    private TransformDiskCache() {
    }

    /**
     * Builds the disk key for the graphics stages, or returns {@code null} when the disk layer is unavailable.
     *
     * <p>{@code null} is the disabled state, not an error: the cache stays inert under a classes-directory
     * deployment, exactly like the preprocessing layer.
     *
     * @param vertex      vertex source, or {@code null} when the program has no vertex stage
     * @param geometry    geometry source, or {@code null}
     * @param tessControl tessellation control source, or {@code null}
     * @param tessEval    tessellation evaluation source, or {@code null}
     * @param fragment    fragment source, or {@code null}
     * @param parameters  transform parameters describing the patch and its per-program settings
     * @param instrumentation whether adaptive shadow bounds instrumentation was active for this transform
     * @param binding     active adaptive shadow bounds binding, or {@code -1} when instrumentation was inactive
     */
    static ShaderDiskCache.Key graphicsKey(String vertex, String geometry, String tessControl, String tessEval,
                                           String fragment, Parameters parameters, boolean instrumentation, int binding) {
        final ShaderDiskCache.Key key = newKey(parameters, instrumentation, binding);
        if (key == null) {
            return null;
        }
        key.str(vertex).str(geometry).str(tessControl).str(tessEval).str(fragment);
        return key;
    }

    /**
     * Builds the disk key for a compute stage, or returns {@code null} when the disk layer is unavailable.
     *
     * <p>Compute results live in their own entries because a compute program never shares a stage set with a
     * graphics program; the stage marker in the key states which family the entry belongs to.
     */
    static ShaderDiskCache.Key computeKey(String compute, Parameters parameters, boolean instrumentation, int binding) {
        final ShaderDiskCache.Key key = newKey(parameters, instrumentation, binding);
        if (key == null) {
            return null;
        }
        key.str(PatchShaderType.COMPUTE.name()).str(compute);
        return key;
    }

    /**
     * Reads a stored result, or returns {@code null} on a miss or on an entry this version cannot decode.
     *
     * <p>A stage name that does not exist in {@link PatchShaderType} can only come from a different source tree
     * sharing the cache directory. The entry is dropped and the caller recomputes, which keeps a stale cache from
     * failing the render path while still reporting the condition.
     */
    static Map<PatchShaderType, String> get(ShaderDiskCache.Key key) {
        if (key == null) {
            return null;
        }
        final Map<String, String> stored = ShaderDiskCache.getStrings(key);
        if (stored == null) {
            return null;
        }
        final EnumMap<PatchShaderType, String> result = new EnumMap<>(PatchShaderType.class);
        for (Map.Entry<String, String> entry : stored.entrySet()) {
            final PatchShaderType type;
            try {
                type = PatchShaderType.valueOf(entry.getKey());
            } catch (IllegalArgumentException exception) {
                ShaderDiskCache.remove(key);
                Iris.logger.warn("Discarding transform cache entry with unknown stage {}", entry.getKey());
                return null;
            }
            result.put(type, entry.getValue());
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * Stores a transform result for the next session.
     *
     * <p>The caller passes the map that was just published to the in-memory cache, so a racing caller's identical
     * result and this result write the same bytes under the same key.
     */
    static void put(ShaderDiskCache.Key key, Map<PatchShaderType, String> result) {
        if (key == null || result == null) {
            return;
        }
        final Map<String, String> values = new LinkedHashMap<>(result.size());
        for (Map.Entry<PatchShaderType, String> entry : result.entrySet()) {
            values.put(entry.getKey().name(), entry.getValue());
        }
        ShaderDiskCache.putStrings(key, values);
    }

    /**
     * Starts a key with the fields every transform shares: layer format, patch, parameter family and capabilities.
     *
     * <p>Returns {@code null} when the disk cache is disabled so callers skip both the hashing work and the lookup.
     */
    private static ShaderDiskCache.Key newKey(Parameters parameters, boolean instrumentation, int binding) {
        if (!ShaderDiskCache.isEnabled()) {
            return null;
        }
        final ShaderDiskCache.Key key = ShaderDiskCache.key(LAYER);
        key.str(FORMAT_VERSION);
        appendParameters(key, parameters);
        key.b(instrumentation).i(binding);
        appendCapabilities(key);
        return key;
    }

    /**
     * Records every {@link Parameters} field that changes transform output.
     *
     * <p>The parameter families are matched exhaustively on purpose. A new subclass silently falling through to a
     * shared branch would produce cache hits that return GLSL from a different configuration, so an unhandled type
     * fails immediately instead of degrading correctness.
     */
    private static void appendParameters(ShaderDiskCache.Key key, Parameters parameters) {
        key.str(parameters.patch.name());
        key.str(parameters.getClass().getName());
        final TextureStage stage = parameters.getTextureStage();
        key.str(stage == null ? null : stage.name());
        if (parameters instanceof AttributeParameters attribute) {
            key.b(attribute.hasGeometry);
            key.i(attribute.inputs == null ? -1 : attribute.inputs.pack());
            key.b(attribute.inputs != null && attribute.inputs.color);
        } else if (parameters instanceof TextureStageParameters) {
            // The texture stage and texture map above are the complete per-instance state of this family.
        } else if (parameters instanceof DHParameters) {
            // Distant Horizons parameters carry the texture map only; the patch and stage are already recorded.
        } else if (parameters instanceof CeleritasTerrainParameters) {
            // Celeritas terrain parameters carry no per-instance settings beyond the patch.
        } else {
            throw new IllegalStateException(
                "Transform disk key does not cover the parameters type " + parameters.getClass().getName());
        }
        appendTextureMap(key, parameters.getTextureMap());
    }

    /**
     * Records the texture stage map in a deterministic order.
     *
     * <p>The map decides which texture unit each stage binds, so its entries are part of the transform input. It is
     * sorted by a canonical rendering of the key triple because the underlying map iteration order is hash based and
     * would produce a different digest for the same content between runs.
     */
    private static void appendTextureMap(ShaderDiskCache.Key key,
                                         Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap) {
        if (textureMap == null || textureMap.isEmpty()) {
            key.i(0);
            return;
        }
        final TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<Tri<String, TextureType, TextureStage>, String> entry : textureMap.entrySet()) {
            final Tri<String, TextureType, TextureStage> triple = entry.getKey();
            sorted.put(triple.first() + '\u0000' + triple.second().name() + '\u0000' + triple.third().name(),
                entry.getValue());
        }
        key.i(sorted.size());
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            key.str(entry.getKey());
            key.str(entry.getValue());
        }
    }

    /**
     * Records the OpenGL capabilities that change transform output.
     *
     * <p>{@link ShaderTransformer} builds its version-hoisting table from these flags and negotiates the final GLSL
     * version against {@code maxGlslVersion}, and {@code CommonTransformer} emits clip distances only when the
     * capability is present. A cache entry produced on one driver must therefore never be reused on another.
     */
    private static void appendCapabilities(ShaderDiskCache.Key key) {
        key.i(RenderSystem.getMaxGlslVersion());
        key.b(RenderSystem.supportsSSBO());
        key.b(RenderSystem.supportsImageLoadStore());
        key.b(RenderSystem.hasClipCullDistance());
    }
}
