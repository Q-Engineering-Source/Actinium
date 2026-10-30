package net.coderbot.iris.pipeline.transform;

import com.gtnewhorizons.angelica.glsm.RenderSystem;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.coderbot.iris.gl.texture.TextureType;
import net.coderbot.iris.helpers.Tri;
import net.coderbot.iris.pipeline.transform.parameter.ComputeParameters;
import net.coderbot.iris.shaderpack.texture.TextureStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransformPatcherCacheTest {
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

    private static Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMapForStages() {
        final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap = new Object2ObjectOpenHashMap<>();
        textureMap.put(new Tri<>("colortex0", TextureType.TEXTURE_2D, TextureStage.PREPARE), "prepareTexture");
        textureMap.put(new Tri<>("colortex0", TextureType.TEXTURE_2D, TextureStage.SHADOWCOMP), "shadowTexture");
        return textureMap;
    }

    @BeforeAll
    static void provideHeadlessGlslCapability() {
        RenderSystem.initializeGlslCapabilityForTesting(460);
    }

    @BeforeEach
    void clearTransformCacheBeforeTest() {
        TransformPatcher.clearCache();
    }

    @AfterEach
    void clearTransformCacheAfterTest() {
        TransformPatcher.clearCache();
    }

    @Test
    void graphicsResultsAreCachedImmutableAndClearedTogether() {
        Map<PatchShaderType, String> first = TransformPatcher.patchComposite(VERTEX, null, FRAGMENT);
        Map<PatchShaderType, String> second = TransformPatcher.patchComposite(VERTEX, null, FRAGMENT);

        assertSame(first, second);
        assertThrows(UnsupportedOperationException.class,
            () -> first.put(PatchShaderType.COMPUTE, "unexpected mutation"));

        TransformPatcher.clearCache();

        Map<PatchShaderType, String> afterClear = TransformPatcher.patchComposite(VERTEX, null, FRAGMENT);
        assertNotSame(first, afterClear);
        assertEquals(first.get(PatchShaderType.FRAGMENT), afterClear.get(PatchShaderType.FRAGMENT));
    }

    @Test
    void computeResultsUseTheSameCacheAndClearBoundary() {
        Map<PatchShaderType, String> first = TransformPatcher.transformCompute(
            COMPUTE, new ComputeParameters(Patch.COMPUTE, TextureStage.SHADOWCOMP, null));
        Map<PatchShaderType, String> second = TransformPatcher.transformCompute(
            COMPUTE, new ComputeParameters(Patch.COMPUTE, TextureStage.SHADOWCOMP, null));

        assertSame(first, second);

        TransformPatcher.clearCache();

        Map<PatchShaderType, String> afterClear = TransformPatcher.transformCompute(
            COMPUTE, new ComputeParameters(Patch.COMPUTE, TextureStage.SHADOWCOMP, null));
        assertNotSame(first, afterClear);
        assertEquals(first.get(PatchShaderType.COMPUTE), afterClear.get(PatchShaderType.COMPUTE));
    }

    @Test
    void cacheKeySeparatesSourceAndTransformParameters() {
        Map<PatchShaderType, String> base = TransformPatcher.patchComposite(
            VERTEX, null, null, null, FRAGMENT, TextureStage.PREPARE, null);
        Map<PatchShaderType, String> changedVertex = TransformPatcher.patchComposite(
            VERTEX.replace("texcoord", "texcoordChanged"), null, null, null, FRAGMENT, TextureStage.PREPARE, null);
        Map<PatchShaderType, String> changedFragment = TransformPatcher.patchComposite(
            VERTEX, null, null, null, FRAGMENT.replace("vec4(texcoord", "vec4(texcoord * 0.5"), TextureStage.PREPARE, null);
        Map<PatchShaderType, String> changedStage = TransformPatcher.patchComposite(
            VERTEX, null, null, null, FRAGMENT, TextureStage.DEFERRED, null);

        assertNotSame(base, changedVertex);
        assertNotSame(base, changedFragment);
        assertNotSame(base, changedStage);

        Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap = textureMapForStages();
        String computePrepare = TransformPatcher.patchCompute("cache_test", COMPUTE, TextureStage.PREPARE, textureMap);
        String computeShadow = TransformPatcher.patchCompute("cache_test", COMPUTE, TextureStage.SHADOWCOMP, textureMap);
        assertNotNull(computePrepare);
        assertNotNull(computeShadow);
        assertTrue(computePrepare.contains("prepareTexture"), computePrepare);
        assertTrue(computeShadow.contains("shadowTexture"), computeShadow);
    }

    @Test
    void failedComputeTransformResetsParameterType() {
        ComputeParameters parameters = new ComputeParameters(Patch.COMPUTE, TextureStage.SHADOWCOMP, null);

        assertThrows(IllegalArgumentException.class,
            () -> TransformPatcher.transformCompute("void main() {}", parameters));

        assertNull(parameters.type);
    }

    @Test
    void concurrentMissesPublishOneGraphicsResult() throws Exception {
        int workerCount = 8;
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(workerCount)) {
            try {
                List<Future<Map<PatchShaderType, String>>> futures = new ArrayList<>();
                for (int i = 0; i < workerCount; i++) {
                    futures.add(executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        return TransformPatcher.patchComposite(VERTEX, null, FRAGMENT);
                    }));
                }

                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();

                Map<PatchShaderType, String> expected = futures.getFirst().get(30, TimeUnit.SECONDS);
                for (int i = 1; i < futures.size(); i++) {
                    assertSame(expected, futures.get(i).get(30, TimeUnit.SECONDS));
                }
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }
}
