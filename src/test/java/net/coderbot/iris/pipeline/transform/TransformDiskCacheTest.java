package net.coderbot.iris.pipeline.transform;

import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.shader.ShaderDiskCache;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.coderbot.iris.gbuffer_overrides.matching.InputAvailability;
import net.coderbot.iris.gl.texture.TextureType;
import net.coderbot.iris.helpers.Tri;
import net.coderbot.iris.pipeline.AdaptiveShadowBoundsStats;
import net.coderbot.iris.pipeline.transform.parameter.AttributeParameters;
import net.coderbot.iris.pipeline.transform.parameter.ComputeParameters;
import net.coderbot.iris.pipeline.transform.parameter.Parameters;
import net.coderbot.iris.pipeline.transform.parameter.TextureStageParameters;
import net.coderbot.iris.shaderpack.texture.TextureStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the transform disk layer through the production {@link TransformPatcher} entry points.
 *
 * <p>The stored payload is fabricated in the tests that verify reads: returning the fabricated source proves the
 * patcher consulted the entry instead of running the AST transform, which no assertion on transformed output alone
 * could establish.
 */
class TransformDiskCacheTest {
    /** Graphics shader pair used by the composite patch. */
    private static final String VERTEX = """
        #version 120
        varying vec2 texcoord;
        void main() {
            texcoord = gl_MultiTexCoord0.xy;
            gl_Position = ftransform();
        }
        """;
    private static final String FRAGMENT = """
        #version 120
        varying vec2 texcoord;
        void main() {
            gl_FragColor = vec4(texcoord, 0.0, 1.0);
        }
        """;
    private static final String COMPUTE = """
        #version 430
        layout(local_size_x = 1) in;
        uniform sampler2D colortex0;
        void main() {
            vec4 color = texture(colortex0, vec2(0.0));
        }
        """;

    /** Sentinels that cannot be produced by the AST pipeline, used to prove the read path is wired. */
    private static final String STORED_VERTEX = "/* stored vertex */";
    private static final String STORED_FRAGMENT = "/* stored fragment */";
    private static final String STORED_COMPUTE = "/* stored compute */";

    /** Supplies isolated cache roots without touching the user cache. */
    @TempDir
    Path temporaryDirectory;

    /** Publishes the deterministic GLSL capability the headless transform needs. */
    @BeforeAll
    static void provideHeadlessGlslCapability() {
        RenderSystem.initializeGlslCapabilityForTesting(460);
    }

    /** Starts every scenario with no configured cache and no in-memory results. */
    @BeforeEach
    void resetCaches() {
        ShaderDiskCache.disable();
        TransformPatcher.clearCache();
    }

    /** Restores the shared capability fields and drops the configured generation after each scenario. */
    @AfterEach
    void resetCapabilities() {
        ShaderDiskCache.disable();
        TransformPatcher.clearCache();
        RenderSystem.initializeGlslCapabilityForTesting(460);
        RenderSystem.initializeShaderFeatureSupportForTesting(false, false);
        RenderSystem.initializeClipCullDistanceSupportForTesting(false);
    }

    /** Confirms a graphics transform is written where the next session can read it. */
    @Test
    void storesFreshGraphicsTransformForTheNextSession() {
        ShaderDiskCache.configure(temporaryDirectory, "transform-write");

        final Map<PatchShaderType, String> patched = TransformPatcher.patchComposite(VERTEX, null, FRAGMENT);
        final Map<String, String> stored = ShaderDiskCache.getStrings(compositeKey(VERTEX, FRAGMENT));

        assertNotNull(stored, "the transform result must be written to the disk layer");
        assertEquals(patched.get(PatchShaderType.VERTEX), stored.get("VERTEX"));
        assertEquals(patched.get(PatchShaderType.FRAGMENT), stored.get("FRAGMENT"));
    }

    /** Confirms an existing entry is served instead of running the AST transform again. */
    @Test
    void servesStoredEntryBeforeTransforming() {
        ShaderDiskCache.configure(temporaryDirectory, "transform-read");
        ShaderDiskCache.putStrings(compositeKey(VERTEX, FRAGMENT),
            Map.of("VERTEX", STORED_VERTEX, "FRAGMENT", STORED_FRAGMENT));

        final Map<PatchShaderType, String> patched = TransformPatcher.patchComposite(VERTEX, null, FRAGMENT);

        assertEquals(STORED_VERTEX, patched.get(PatchShaderType.VERTEX));
        assertEquals(STORED_FRAGMENT, patched.get(PatchShaderType.FRAGMENT));
    }

    /** Confirms a compute entry is stored and restored through its own key family. */
    @Test
    void storesAndServesComputeEntries() {
        final ComputeParameters parameters = new ComputeParameters(Patch.COMPUTE, TextureStage.SHADOWCOMP, null);
        ShaderDiskCache.configure(temporaryDirectory, "transform-compute");

        final Map<PatchShaderType, String> transformed = TransformPatcher.transformCompute(COMPUTE, parameters);
        assertNotNull(ShaderDiskCache.getStrings(computeKey(COMPUTE, parameters)));

        TransformPatcher.clearCache();
        ShaderDiskCache.putStrings(computeKey(COMPUTE, parameters),
            Map.of(PatchShaderType.COMPUTE.name(), STORED_COMPUTE));

        final Map<PatchShaderType, String> restored = TransformPatcher.transformCompute(COMPUTE, parameters);

        assertEquals(STORED_COMPUTE, restored.get(PatchShaderType.COMPUTE));
        assertNotEquals(STORED_COMPUTE, transformed.get(PatchShaderType.COMPUTE));
    }

    /** Confirms the same inputs restore identical source after the in-memory cache is dropped. */
    @Test
    void restoredEntryMatchesTheSameTransform() {
        ShaderDiskCache.configure(temporaryDirectory, "transform-session");
        final Map<PatchShaderType, String> first = TransformPatcher.patchComposite(VERTEX, null, FRAGMENT);

        ShaderDiskCache.disable();
        ShaderDiskCache.configure(temporaryDirectory, "transform-session");
        TransformPatcher.clearCache();

        final Map<PatchShaderType, String> second = TransformPatcher.patchComposite(VERTEX, null, FRAGMENT);

        assertEquals(first.get(PatchShaderType.VERTEX), second.get(PatchShaderType.VERTEX));
        assertEquals(first.get(PatchShaderType.FRAGMENT), second.get(PatchShaderType.FRAGMENT));
    }

    /** Confirms a malformed payload is dropped and the transform is recomputed. */
    @Test
    void malformedPayloadFallsBackToTransforming() {
        ShaderDiskCache.configure(temporaryDirectory, "transform-corrupt");
        final ShaderDiskCache.Key key = compositeKey(VERTEX, FRAGMENT);
        assertTrue(ShaderDiskCache.put(key, new byte[] {0, 0, 0, 5, 1}));

        final Map<PatchShaderType, String> patched = TransformPatcher.patchComposite(VERTEX, null, FRAGMENT);

        assertTrue(patched.get(PatchShaderType.FRAGMENT).contains("#version"), patched.get(PatchShaderType.FRAGMENT));
        final Map<String, String> rewritten = ShaderDiskCache.getStrings(key);
        assertNotNull(rewritten, "the recomputed result must replace the discarded entry");
        assertEquals(patched.get(PatchShaderType.FRAGMENT), rewritten.get("FRAGMENT"));
    }

    /** Confirms an entry carrying a stage name this build does not know is dropped instead of failing. */
    @Test
    void unknownStageEntryIsDiscarded() {
        ShaderDiskCache.configure(temporaryDirectory, "transform-unknown-stage");
        final ShaderDiskCache.Key key = compositeKey(VERTEX, FRAGMENT);
        ShaderDiskCache.putStrings(key, Map.of("NOT_A_STAGE", STORED_VERTEX));

        final Map<PatchShaderType, String> patched = TransformPatcher.patchComposite(VERTEX, null, FRAGMENT);

        assertTrue(patched.get(PatchShaderType.VERTEX).contains("#version"), patched.get(PatchShaderType.VERTEX));
        final Map<String, String> rewritten = ShaderDiskCache.getStrings(key);
        assertNotNull(rewritten, "the undecodable entry must be replaced by a real result");
        assertEquals(patched.get(PatchShaderType.VERTEX), rewritten.get("VERTEX"));
    }

    /** Confirms the layer stays inert while no cache root is configured. */
    @Test
    void disabledCacheSkipsTheDiskLayer() {
        assertNull(compositeKey(VERTEX, FRAGMENT));

        final Map<PatchShaderType, String> patched = TransformPatcher.patchComposite(VERTEX, null, FRAGMENT);

        assertTrue(patched.get(PatchShaderType.FRAGMENT).contains("#version"));
    }

    /** Confirms every transform input moves the key, so no two configurations share an entry. */
    @Test
    void keyCoversEveryTransformInput() {
        ShaderDiskCache.configure(temporaryDirectory, "transform-keys");
        final TextureStageParameters composite =
            new TextureStageParameters(Patch.COMPOSITE, TextureStage.COMPOSITE_AND_FINAL, null);
        final String base = graphicsKey(composite, VERTEX, null, FRAGMENT).hex();

        assertNotEquals(base, graphicsKey(composite, VERTEX.replace("texcoord", "uv"), null, FRAGMENT).hex(),
            "vertex source");
        assertNotEquals(base, graphicsKey(composite, VERTEX, null,
            FRAGMENT.replace("vec4(texcoord", "vec4(texcoord * 0.5")).hex(), "fragment source");
        assertNotEquals(base, graphicsKey(composite, VERTEX, "/* geometry */", FRAGMENT).hex(), "geometry presence");
        assertNotEquals(base, graphicsKey(
            new TextureStageParameters(Patch.COMPOSITE, TextureStage.DEFERRED, null), VERTEX, null, FRAGMENT).hex(),
            "texture stage");
        assertNotEquals(base, graphicsKey(
            new TextureStageParameters(Patch.COMPOSITE, TextureStage.COMPOSITE_AND_FINAL, textureMap(false)),
            VERTEX, null, FRAGMENT).hex(), "texture map");
        assertNotEquals(base, graphicsKey(new AttributeParameters(Patch.ATTRIBUTES, false,
            new InputAvailability(true, true)), VERTEX, null, FRAGMENT).hex(), "parameter family");

        final String attributes = graphicsKey(new AttributeParameters(Patch.ATTRIBUTES, false,
            new InputAvailability(true, true)), VERTEX, null, FRAGMENT).hex();
        assertNotEquals(attributes, graphicsKey(new AttributeParameters(Patch.ATTRIBUTES, true,
            new InputAvailability(true, true)), VERTEX, null, FRAGMENT).hex(), "geometry parameter");
        assertNotEquals(attributes, graphicsKey(new AttributeParameters(Patch.ATTRIBUTES, false,
            new InputAvailability(false, true)), VERTEX, null, FRAGMENT).hex(), "texture input availability");
        assertNotEquals(attributes, graphicsKey(new AttributeParameters(Patch.ATTRIBUTES, false,
            new InputAvailability(true, false)), VERTEX, null, FRAGMENT).hex(), "lightmap input availability");
        assertNotEquals(attributes, graphicsKey(new AttributeParameters(Patch.ATTRIBUTES, false,
            new InputAvailability(true, true, false)), VERTEX, null, FRAGMENT).hex(), "color input availability");

        assertNotEquals(base, TransformDiskCache.graphicsKey(VERTEX, null, null, null, FRAGMENT, composite, true, 3).hex(),
            "adaptive shadow instrumentation");
        assertNotEquals(
            TransformDiskCache.graphicsKey(VERTEX, null, null, null, FRAGMENT, composite, true, 3).hex(),
            TransformDiskCache.graphicsKey(VERTEX, null, null, null, FRAGMENT, composite, true, 4).hex(),
            "adaptive shadow binding");

        RenderSystem.initializeGlslCapabilityForTesting(450);
        assertNotEquals(base, graphicsKey(composite, VERTEX, null, FRAGMENT).hex(), "max GLSL version");
        RenderSystem.initializeGlslCapabilityForTesting(460);

        RenderSystem.initializeShaderFeatureSupportForTesting(true, false);
        assertNotEquals(base, graphicsKey(composite, VERTEX, null, FRAGMENT).hex(), "shader storage support");
        RenderSystem.initializeShaderFeatureSupportForTesting(false, true);
        assertNotEquals(base, graphicsKey(composite, VERTEX, null, FRAGMENT).hex(), "image load/store support");
        RenderSystem.initializeShaderFeatureSupportForTesting(false, false);

        RenderSystem.initializeClipCullDistanceSupportForTesting(true);
        assertNotEquals(base, graphicsKey(composite, VERTEX, null, FRAGMENT).hex(), "clip distance support");
    }

    /** Confirms the texture map is folded in by content, not by hash iteration order. */
    @Test
    void textureMapOrderDoesNotChangeTheKey() {
        ShaderDiskCache.configure(temporaryDirectory, "transform-order");
        final TextureStageParameters first = new TextureStageParameters(Patch.COMPOSITE,
            TextureStage.COMPOSITE_AND_FINAL, textureMap(false));
        final TextureStageParameters second = new TextureStageParameters(Patch.COMPOSITE,
            TextureStage.COMPOSITE_AND_FINAL, textureMap(true));

        assertEquals(graphicsKey(first, VERTEX, null, FRAGMENT).hex(),
            graphicsKey(second, VERTEX, null, FRAGMENT).hex());
    }

    /** Confirms the graphics and compute families never share an entry. */
    @Test
    void computeKeysDoNotCollideWithGraphicsKeys() {
        ShaderDiskCache.configure(temporaryDirectory, "transform-families");
        final TextureStageParameters composite =
            new TextureStageParameters(Patch.COMPOSITE, TextureStage.COMPOSITE_AND_FINAL, null);
        final ComputeParameters compute =
            new ComputeParameters(Patch.COMPUTE, TextureStage.COMPOSITE_AND_FINAL, null);

        assertNotEquals(graphicsKey(composite, VERTEX, null, FRAGMENT).hex(),
            TransformDiskCache.computeKey(VERTEX, compute, false, -1).hex());
    }

    /** Builds the key the composite patch produces for the given fragment/vertex pair. */
    private static ShaderDiskCache.Key compositeKey(String vertex, String fragment) {
        return graphicsKey(new TextureStageParameters(Patch.COMPOSITE, TextureStage.COMPOSITE_AND_FINAL, null),
            vertex, null, fragment);
    }

    /** Builds a graphics key from the parameters and stages with the instrumentation captured from the transform. */
    private static ShaderDiskCache.Key graphicsKey(Parameters parameters, String vertex, String geometry,
                                                   String fragment) {
        final boolean instrumentation = AdaptiveShadowBoundsStats.isInstrumentationEnabled();
        final int binding = instrumentation ? AdaptiveShadowBoundsStats.getActiveBinding() : -1;
        return TransformDiskCache.graphicsKey(vertex, geometry, null, null, fragment, parameters, instrumentation,
            binding);
    }

    /** Builds a compute key from the parameters with the instrumentation captured from the transform. */
    private static ShaderDiskCache.Key computeKey(String compute, Parameters parameters) {
        final boolean instrumentation = AdaptiveShadowBoundsStats.isInstrumentationEnabled();
        final int binding = instrumentation ? AdaptiveShadowBoundsStats.getActiveBinding() : -1;
        return TransformDiskCache.computeKey(compute, parameters, instrumentation, binding);
    }

    /**
     * Builds a texture stage map with two sampler bindings.
     *
     * <p>{@code reversed} inserts the same two entries in the opposite order, which changes the backing map's
     * iteration order without changing its content.
     */
    private static Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap(boolean reversed) {
        final Tri<String, TextureType, TextureStage> first =
            new Tri<>("colortex0", TextureType.TEXTURE_2D, TextureStage.COMPOSITE_AND_FINAL);
        final Tri<String, TextureType, TextureStage> second =
            new Tri<>("colortex1", TextureType.TEXTURE_2D, TextureStage.COMPOSITE_AND_FINAL);
        final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap =
            new Object2ObjectOpenHashMap<>();
        if (reversed) {
            textureMap.put(second, "secondTexture");
            textureMap.put(first, "firstTexture");
        } else {
            textureMap.put(first, "firstTexture");
            textureMap.put(second, "secondTexture");
        }
        return textureMap;
    }
}
