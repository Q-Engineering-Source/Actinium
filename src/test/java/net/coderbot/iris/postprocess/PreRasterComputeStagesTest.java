package net.coderbot.iris.postprocess;

import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.testutil.Reflect;
import net.coderbot.iris.pipeline.transform.PatchShaderType;
import net.coderbot.iris.pipeline.transform.PreRasterComputeDispatcher;
import net.coderbot.iris.pipeline.transform.RwImageStoreExtractor;
import net.coderbot.iris.pipeline.transform.RwImageStoreExtractor.RwExtractMode;
import net.coderbot.iris.pipeline.transform.ShaderTransformer;
import net.coderbot.iris.pipeline.transform.TransformPatcher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the registration, dispatch, and teardown of the pre-raster compute stages with the compute
 * source the extractor really produces, and a stand-in program in place of the GL one.
 */
class PreRasterComputeStagesTest {

    private static final String COMPOSITE_VSH = String.join("\n",
        "#version 460 core",
        "writeonly uniform uimage2D state_img;",
        "uniform mat4 gl_ModelViewProjectionMatrix;",
        "void main() {",
        "    gl_Position = ftransform();",
        "    imageStore(state_img, ivec2(gl_VertexID, 0), uvec4(1u));",
        "}",
        "");

    private static final String COMPOSITE_FSH = String.join("\n",
        "#version 460 core",
        "writeonly uniform uimage2D out_img;",
        "in vec2 texcoord;",
        "out vec4 outColor;",
        "void main() {",
        "    ivec2 px = ivec2(gl_FragCoord.xy);",
        "    imageStore(out_img, px, uvec4(uint(px.x), uint(px.y), 0u, 0u));",
        "    outColor = vec4(0.0, 0.0, 0.0, 1.0);",
        "}",
        "");

    private static final String VOXEL_VSH = String.join("\n",
        "#version 460 core",
        "#extension GL_ARB_shader_image_load_store : enable",
        "in vec4 iris_Vertex;",
        "in vec4 iris_MultiTexCoord0;",
        "attribute vec4 mc_Entity;",
        "attribute vec4 at_midBlock;",
        "writeonly uniform uimage3D voxel_img;",
        "void main() {",
        "    gl_Position = vec4(iris_Vertex.xyz, 1.0);",
        "    imageStore(voxel_img, ivec3(at_midBlock.xyz), uvec4(uint(int(mc_Entity.x + 0.5)), 0u, 0u, 0u));",
        "}",
        "");

    /** Shaped like a real pack's prepare pass, i.e. the input a backend actually transforms. */
    private static final String GL_PATH_VSH = String.join("\n",
        "#version 130",
        "#extension GL_ARB_shader_image_load_store : enable",
        "uniform float frameTime;",
        "layout(r32i) uniform iimage2D endcrystal_img;",
        "varying vec2 texCoord;",
        "void main() {",
        "    texCoord = gl_MultiTexCoord0.xy;",
        "    gl_Position = ftransform();",
        "    imageStore(endcrystal_img, ivec2(0, 0), ivec4(int(10000.0 * frameTime)));",
        "}",
        "");

    private static final String GL_PATH_FSH = String.join("\n",
        "#version 130",
        "varying vec2 texCoord;",
        "void main() { gl_FragData[0] = vec4(texCoord, 0.0, 1.0); }",
        "");

    @BeforeAll
    static void initShaderTransformer() throws Exception {
        Reflect.setStatic(RenderSystem.class, "maxGlslVersion", 460);
        ShaderTransformer.init();
    }

    @BeforeEach
    void clearTransformCaches() {
        TransformPatcher.clearCache();
    }

    private static String extractedCompositeVertexCompute() {
        final RwImageStoreExtractor.Result result = RwImageStoreExtractor.tryExtract(COMPOSITE_VSH, PatchShaderType.VERTEX, "prepare", RwExtractMode.COMPOSITE_VSH);
        assertNotNull(result);
        assertNotNull(result.computeSource());
        return result.computeSource();
    }

    private static String extractedCompositeFragmentCompute() {
        final RwImageStoreExtractor.Result result = RwImageStoreExtractor.tryExtract(COMPOSITE_FSH, PatchShaderType.FRAGMENT, "composite");
        assertNotNull(result);
        assertNotNull(result.computeSource());
        return result.computeSource();
    }

    /** Stands in for a built pre-pass, which would otherwise dispatch into a live GL context. */
    private static final class RecordingProgram implements PreRasterComputeProgram {
        final String name;
        final String source;
        final List<String> dispatches = new ArrayList<>();
        int resolveTargetSizeLoc = PreRasterComputeDispatcher.LOC_UNRESOLVED;
        int destroyCount;

        RecordingProgram(String name, String source) {
            this.name = name;
            this.source = source;
        }

        @Override
        public int getProgramId() {
            return 7;
        }

        @Override
        public int dispatch(RwExtractMode mode, int cachedTargetSizeLoc, int width, int height) {
            dispatches.add(mode + " " + cachedTargetSizeLoc + " " + width + "x" + height);
            return resolveTargetSizeLoc;
        }

        @Override
        public void destroy() {
            destroyCount++;
        }
    }

    private static final class RecordingFactory implements PreRasterComputeStages.Factory {
        final List<RecordingProgram> built = new ArrayList<>();

        @Override
        public PreRasterComputeProgram create(String name, String computeSource) {
            final RecordingProgram program = new RecordingProgram(name, computeSource);
            built.add(program);
            return program;
        }
    }

    @Test
    void registersThePrePassExtractedFromACompositeShader() {
        final RecordingFactory factory = new RecordingFactory();
        final PreRasterComputeStages stages = new PreRasterComputeStages();

        assertEquals(0, stages.register("composite", extractedCompositeFragmentCompute(), factory));
        assertEquals(1, stages.register("prepare", extractedCompositeVertexCompute(), factory));

        assertEquals(2, factory.built.size());
        assertEquals("composite", factory.built.get(0).name);
        assertTrue(factory.built.get(0).source.startsWith("// _vg_mode: composite-fsh"), factory.built.get(0).source);
        assertEquals("prepare", factory.built.get(1).name);
        assertTrue(factory.built.get(1).source.startsWith("// _vg_mode: composite-vsh"), factory.built.get(1).source);
    }

    @Test
    void registersNothingWhenTheProgramStrippedNoImageWrites() {
        final RecordingFactory factory = new RecordingFactory();
        final PreRasterComputeStages stages = new PreRasterComputeStages();

        assertEquals(PreRasterComputeStages.NONE, stages.register("deferred", null, factory));
        assertEquals(PreRasterComputeStages.NONE, stages.register("deferred", "void main() {}", factory));

        assertTrue(factory.built.isEmpty());
    }

    @Test
    void registersNothingForTheExtractionsOtherStagesDrive() {
        final RecordingFactory factory = new RecordingFactory();
        final PreRasterComputeStages stages = new PreRasterComputeStages();

        final RwImageStoreExtractor.Result chunk = RwImageStoreExtractor.tryExtract(VOXEL_VSH, PatchShaderType.VERTEX, "shadow");
        assertNotNull(chunk);
        assertEquals(RwExtractMode.CHUNK, chunk.mode());
        assertEquals(PreRasterComputeStages.NONE, stages.register("shadow", chunk.computeSource(), factory));
        assertEquals(PreRasterComputeStages.NONE, stages.register("gbuffers_terrain", "// _vg_mode: vertex-replay\nvoid main() {}\n", factory));

        assertTrue(factory.built.isEmpty());
    }

    @Test
    void dispatchesEachPrePassWithItsOwnModeAndTargetSize() {
        final RecordingFactory factory = new RecordingFactory();
        final PreRasterComputeStages stages = new PreRasterComputeStages();
        final int vshSlot = stages.register("prepare", extractedCompositeVertexCompute(), factory);
        final int fshSlot = stages.register("composite", extractedCompositeFragmentCompute(), factory);

        stages.dispatch(vshSlot, 1920, 1080);
        stages.dispatch(fshSlot, 1920, 1080);

        assertEquals(List.of("COMPOSITE_VSH " + PreRasterComputeDispatcher.LOC_UNRESOLVED + " 1920x1080"), factory.built.get(0).dispatches);
        assertEquals(List.of("COMPOSITE_FSH " + PreRasterComputeDispatcher.LOC_UNRESOLVED + " 1920x1080"), factory.built.get(1).dispatches);
    }

    @Test
    void cachesTheTargetSizeLocationTheFirstDispatchResolved() {
        final RecordingFactory factory = new RecordingFactory();
        final PreRasterComputeStages stages = new PreRasterComputeStages();
        final int slot = stages.register("composite", extractedCompositeFragmentCompute(), factory);
        final RecordingProgram program = factory.built.get(0);
        program.resolveTargetSizeLoc = 11;

        stages.dispatch(slot, 854, 480);
        stages.dispatch(slot, 854, 480);
        stages.dispatch(slot, 1280, 720);

        assertEquals(List.of(
            "COMPOSITE_FSH " + PreRasterComputeDispatcher.LOC_UNRESOLVED + " 854x480",
            "COMPOSITE_FSH 11 854x480",
            "COMPOSITE_FSH 11 1280x720"), program.dispatches);
    }

    @Test
    void keepsTheTargetSizeLocationOfEachSlotApart() {
        final RecordingFactory factory = new RecordingFactory();
        final PreRasterComputeStages stages = new PreRasterComputeStages();
        final int first = stages.register("prepare", extractedCompositeVertexCompute(), factory);
        final int second = stages.register("composite", extractedCompositeFragmentCompute(), factory);
        factory.built.get(0).resolveTargetSizeLoc = 3;

        stages.dispatch(first, 64, 64);
        stages.dispatch(second, 64, 64);
        stages.dispatch(first, 64, 64);

        assertEquals(List.of(
            "COMPOSITE_VSH " + PreRasterComputeDispatcher.LOC_UNRESOLVED + " 64x64",
            "COMPOSITE_VSH 3 64x64"), factory.built.get(0).dispatches);
        assertEquals(List.of("COMPOSITE_FSH " + PreRasterComputeDispatcher.LOC_UNRESOLVED + " 64x64"),
            factory.built.get(1).dispatches, "the second slot must not inherit the first slot's location");
    }

    @Test
    void destroysEveryRegisteredPrePassOnce() {
        final RecordingFactory factory = new RecordingFactory();
        final PreRasterComputeStages stages = new PreRasterComputeStages();
        final int slot = stages.register("composite", extractedCompositeFragmentCompute(), factory);

        stages.destroy();
        assertEquals(1, factory.built.get(0).destroyCount);

        stages.destroy();
        assertEquals(1, factory.built.get(0).destroyCount, "a second teardown has nothing left to release");
        assertThrows(IllegalStateException.class, () -> stages.dispatch(slot, 1, 1));
    }

    @Test
    void dispatchingASlotThatIsNotRegisteredFails() {
        final PreRasterComputeStages stages = new PreRasterComputeStages();

        assertThrows(IllegalStateException.class, () -> stages.dispatch(PreRasterComputeStages.NONE, 100, 100));
        assertThrows(IllegalStateException.class, () -> stages.dispatch(0, 100, 100));
    }

    @Test
    void theGlBackendHasNoPrePassToRegister() {
        // The GL backend stores to images directly: the transform strips nothing, so the renderer hands
        // the stages a null compute source and the composite pass draws exactly as it did before.
        final Map<PatchShaderType, String> transformed = TransformPatcher.patchComposite(GL_PATH_VSH, null, GL_PATH_FSH);
        assertFalse(transformed.containsKey(PatchShaderType.COMPUTE), "only the SDL GPU backend splits image writes out of a raster stage");

        final RecordingFactory factory = new RecordingFactory();
        final PreRasterComputeStages stages = new PreRasterComputeStages();
        assertEquals(PreRasterComputeStages.NONE, stages.register("prepare", transformed.get(PatchShaderType.COMPUTE), factory));
        assertTrue(factory.built.isEmpty());
    }
}
