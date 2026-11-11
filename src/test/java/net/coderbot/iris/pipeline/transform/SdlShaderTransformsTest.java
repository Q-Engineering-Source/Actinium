package net.coderbot.iris.pipeline.transform;

import com.gtnewhorizons.angelica.glsm.GlslTransformUtils;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.shader.SpirvCompiler;
import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager;
import net.coderbot.iris.celeritas.vertices.ExtendedChunkVertexType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;
import org.lwjgl.util.shaderc.Shaderc;
import org.taumc.glsl.ShaderParser;
import org.taumc.glsl.Transformer;
import org.taumc.glsl.grammar.GLSLLexer;
import org.taumc.glsl.grammar.GLSLParser;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.lwjgl.system.MemoryUtil.memFree;

class SdlShaderTransformsTest {
    @AfterEach
    void clearImageCandidates() {
        SamplerToStorageImageRewriter.setActiveCandidates(Set.of());
    }

    @ParameterizedTest
    @EnumSource(value = PatchShaderType.class, names = {"FRAGMENT", "COMPUTE"})
    void integerFetchUsesAStorageImageInGraphicsAndCompute(PatchShaderType stage) {
        SamplerToStorageImageRewriter.setActiveCandidates(Set.of(
            new SamplerToStorageImageRewriter.Candidate("voxelSampler", "voxelImage", "r32ui", 3, false)));
        Transformer transformer = parse("""
            #version 460 core
            uniform usampler3D voxelSampler;
            layout(rgba32f) uniform image2D resultImage;
            void main() {
                uvec4 value = texelFetch(voxelSampler, ivec3(0), 0);
                imageStore(resultImage, ivec2(0), vec4(value));
            }
            """);
        SamplerToStorageImageRewriter.transformGrouped(Map.of(stage, transformer), null);

        assertEquals(0, transformer.findType("voxelSampler"));
        assertEquals(GLSLLexer.UIMAGE3D, transformer.findType("voxelImage"));
        String source = format(transformer);
        assertEquals(List.of("imageLoad"), calls(source).stream().filter(name -> name.equals("imageLoad") || name.equals("texelFetch")).toList());
        if (stage == PatchShaderType.COMPUTE) {
            assertCompiles(source.replace("#version 460 core", "#version 460 core\nlayout(local_size_x = 1) in;"), GL43.GL_COMPUTE_SHADER);
        }
    }

    @Test
    void filteredIntegerSamplingIsRejectedBeforeMutatingTheProgram() {
        SamplerToStorageImageRewriter.setActiveCandidates(Set.of(
            new SamplerToStorageImageRewriter.Candidate("voxelSampler", "voxelImage", "r32ui", 3, false)));
        Transformer vertex = parse("uniform usampler3D voxelSampler; void main() { gl_Position = vec4(texture(voxelSampler, vec3(0.0))); }");
        Transformer fragment = parse("uniform usampler3D voxelSampler; out vec4 color; void main() { color = vec4(texelFetch(voxelSampler, ivec3(0), 0)); }");

        assertThrows(IllegalArgumentException.class, () -> SamplerToStorageImageRewriter.transformGrouped(
            Map.of(PatchShaderType.VERTEX, vertex, PatchShaderType.FRAGMENT, fragment), null));

        assertEquals(GLSLLexer.USAMPLER3D, vertex.findType("voxelSampler"));
        assertEquals(GLSLLexer.USAMPLER3D, fragment.findType("voxelSampler"));
        assertEquals(0, fragment.findType("voxelImage"));
    }

    @ParameterizedTest
    @CsvSource({"1,int,0", "2,ivec2,ivec2(0)", "3,ivec3,ivec3(0)"})
    void integerTextureSizeUsesTheMatchingImageDimension(int dimension, String sizeType, String coordinates) {
        SamplerToStorageImageRewriter.setActiveCandidates(Set.of(
            new SamplerToStorageImageRewriter.Candidate("voxelSampler", "voxelImage", "r32ui", dimension, false)));
        String sizeValue = dimension == 1 ? "size" : "size.x";
        Transformer transformer = parse("#version 460 core\nlayout(local_size_x = 1) in;\n"
            + "uniform usampler" + dimension + "D voxelSampler;\n"
            + "layout(rgba32f) uniform image2D resultImage;\n"
            + "void main() { " + sizeType + " size = textureSize(voxelSampler, 0); "
            + "uvec4 value = texelFetch(voxelSampler, " + coordinates + ", 0) + uvec4(uint(" + sizeValue + ")); "
            + "imageStore(resultImage, ivec2(0), vec4(value)); }");

        SamplerToStorageImageRewriter.transformGrouped(Map.of(PatchShaderType.COMPUTE, transformer), null);

        String source = format(transformer);
        assertEquals(List.of("imageSize"), calls(source).stream().filter(name -> name.equals("imageSize") || name.equals("textureSize")).toList());
        assertEquals(0, transformer.findType("voxelSampler"));
        assertCompiles(source, GL43.GL_COMPUTE_SHADER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "lod"})
    void nonzeroOrDynamicLodIsRejectedBeforeStorageImageConversion(String lod) {
        SamplerToStorageImageRewriter.setActiveCandidates(Set.of(
            new SamplerToStorageImageRewriter.Candidate("voxelSampler", "voxelImage", "r32ui", 3, false)));
        for (String call : List.of("textureSize(voxelSampler, " + lod + ")", "texelFetch(voxelSampler, ivec3(0), " + lod + ")")) {
            Transformer transformer = parse("uniform usampler3D voxelSampler; uniform int lod; void main() { " + call + "; }");

            assertThrows(IllegalArgumentException.class, () -> SamplerToStorageImageRewriter.transformGrouped(Map.of(PatchShaderType.COMPUTE, transformer), null));

            assertEquals(GLSLLexer.USAMPLER3D, transformer.findType("voxelSampler"));
            assertEquals(0, transformer.findType("voxelImage"));
        }
    }

    @Test
    void existingWritableImageIsReusedForIntegerSamplerReads() {
        SamplerToStorageImageRewriter.setActiveCandidates(Set.of(
            new SamplerToStorageImageRewriter.Candidate("voxelSampler", "voxelImage", "r32ui", 3, false)));
        Transformer transformer = parse("""
            #version 460 core
            layout(local_size_x = 1) in;
            uniform usampler3D voxelSampler;
            layout(r32ui) writeonly uniform uimage3D voxelImage;
            void main() {
                ivec3 size = textureSize(voxelSampler, 0);
                uvec4 value = texelFetch(voxelSampler, ivec3(0), 0);
                imageStore(voxelImage, size - 1, value);
            }
            """);

        SamplerToStorageImageRewriter.transformGrouped(Map.of(PatchShaderType.COMPUTE, transformer), null);

        assertEquals(0, transformer.findType("voxelSampler"));
        assertEquals(GLSLLexer.UIMAGE3D, transformer.findType("voxelImage"));
        assertCompiles(format(transformer), GL43.GL_COMPUTE_SHADER);
    }

    @Test
    void localAccumulatorIsInitializedAndExplicitInitializersAndArraysArePreserved() {
        Transformer transformer = parse("""
            #version 460 core
            layout(local_size_x = 1) in;
            layout(rgba32f) uniform image2D target;
            void main() {
                vec4 sum, bias = vec4(2.0);
                float weights[2];
                weights[0] = 0.25;
                weights[1] = 0.75;
                for (int i = 0; i < 2; i++) sum += bias * weights[i];
                imageStore(target, ivec2(0), sum);
            }
            """);
        LocalZeroInitTransformer.transform(transformer);
        String source = format(transformer);
        GLSLParser.Translation_unitContext tree = ShaderParser.parseShader(source).full();

        assertEquals("vec4(0)", declarator(tree, "sum").initializer().getText());
        assertEquals("vec4(2.0)", declarator(tree, "bias").initializer().getText());
        assertNull(declarator(tree, "weights").initializer());
        assertNotNull(declarator(tree, "weights").array_specifier());
        assertCompiles(source, GL43.GL_COMPUTE_SHADER);
    }

    @Test
    void vertexReplayPreservesAtomicWritesAndCollectsDrawInputs() {
        String source = """
            #version 460 core
            layout(location = 3) in vec3 position;
            layout(r32ui) uniform uimage2D marks;
            void main() {
                imageAtomicAdd(marks, ivec2(position.xy), 1u);
                gl_Position = vec4(position, 1.0);
            }
            """;
        EnumMap<PatchShaderType, String> stages = new EnumMap<>(PatchShaderType.class);
        stages.put(PatchShaderType.VERTEX, source);
        ShaderTransformer.extractRwImageStores(stages, Patch.ATTRIBUTES, image -> true);

        String replay = stages.get(PatchShaderType.COMPUTE);
        assertEquals(RwImageStoreExtractor.RwExtractMode.VERTEX_REPLAY, RwImageStoreExtractor.parseSentinel(replay));
        assertEquals(List.of("marks"), RwImageStoreExtractor.parseVertexReplayImages(replay));
        assertEquals(List.of(new RwImageStoreExtractor.VertexInput("position", "vec3", 3)), RwImageStoreExtractor.parseVertexReplayInputs(replay));
        assertFalse(calls(stages.get(PatchShaderType.VERTEX)).stream().anyMatch(name -> name.startsWith("imageAtomic")));
        assertTrue(calls(replay).stream().anyMatch(name -> name.equals("imageAtomicAdd")));
        assertCompiles(stages.get(PatchShaderType.VERTEX), GL20.GL_VERTEX_SHADER);
        assertCompiles(replay, GL43.GL_COMPUTE_SHADER);
    }

    @ParameterizedTest
    @EnumSource(value = PatchShaderType.class, names = {"VERTEX", "FRAGMENT"})
    void compositeImageWritesGetAnExecutableComputePrepass(PatchShaderType stage) {
        String input = stage == PatchShaderType.VERTEX ? "gl_VertexID" : "int(gl_FragCoord.x)";
        String output = stage == PatchShaderType.VERTEX ? "gl_Position = vec4(0.0);" : "";
        String source = "#version 460 core\nlayout(rgba32f) uniform image2D marks;\nvoid main() { imageStore(marks, ivec2(" + input + ", 0), vec4(1.0)); " + output + " }";
        EnumMap<PatchShaderType, String> stages = new EnumMap<>(PatchShaderType.class);
        stages.put(stage, source);

        ShaderTransformer.extractRwImageStores(stages, Patch.COMPOSITE, image -> true);

        String compute = stages.get(PatchShaderType.COMPUTE);
        assertNotNull(compute);
        assertEquals(stage == PatchShaderType.VERTEX ? RwImageStoreExtractor.RwExtractMode.COMPOSITE_VSH : RwImageStoreExtractor.RwExtractMode.COMPOSITE_FSH,
            RwImageStoreExtractor.parseSentinel(compute));
        assertTrue(calls(compute).stream().anyMatch(name -> name.equals("imageStore")));
        assertFalse(calls(stages.get(stage)).stream().anyMatch(name -> name.equals("imageStore")));
        assertCompiles(compute, GL43.GL_COMPUTE_SHADER);
    }

    @Test
    void chunkImageWritesUseTheCompleteLocalVertexFormat() {
        String source = """
            #version 460 core
            in vec3 a_PosId;
            in uint mc_Entity;
            layout(r32ui) uniform uimage2D marks;
            vec3 _vert_position;
            void main() {
                imageStore(marks, ivec2(a_PosId.xy), uvec4(mc_Entity));
                gl_Position = vec4(a_PosId, 1.0);
            }
            """;
        EnumMap<PatchShaderType, String> stages = new EnumMap<>(PatchShaderType.class);
        stages.put(PatchShaderType.VERTEX, source);

        ShaderTransformer.extractRwImageStores(stages, Patch.CELERITAS_TERRAIN, image -> true);

        String compute = stages.get(PatchShaderType.COMPUTE);
        assertEquals(RwImageStoreExtractor.RwExtractMode.CHUNK, RwImageStoreExtractor.parseSentinel(compute));
        int entityOffset = new ExtendedChunkVertexType().getVertexFormat().getAttribute("mc_Entity").getPointer();
        var assignments = GlslAstHelpers.collectAll(ShaderParser.parseShader(compute).full(), GLSLParser.Assignment_expressionContext.class);
        String expected = "mc_Entity=_vg_vbuf.data[base+" + (entityOffset / Integer.BYTES) + "u]";
        assertTrue(assignments.stream().anyMatch(assignment -> expected.equals(assignment.getText())));
        assertCompiles(compute, GL43.GL_COMPUTE_SHADER);
    }

    @Test
    void transformedTerrainHeaderAndEntityEncodingCompileInTheVoxelizationProgram() {
        RenderSystem.initializeGlslCapabilityForTesting(460);
        String vertex = """
            #version 430 core
            layout(r32ui) uniform uimage3D voxels;
            in vec4 mc_Entity;
            void main() {
                imageStore(voxels, ivec3(gl_Vertex.xyz), uvec4(uint(mc_Entity.x)));
                gl_Position = ftransform();
            }
            """;
        String fragment = """
            #version 430 core
            void main() { gl_FragData[0] = vec4(1.0); }
            """;
        EnumMap<PatchShaderType, String> stages = new EnumMap<>(PatchShaderType.class);
        stages.putAll(TransformPatcher.patchCeleritasTerrain(vertex, null, fragment));

        ShaderTransformer.extractRwImageStores(stages, Patch.CELERITAS_TERRAIN, image -> true);

        String compute = stages.get(PatchShaderType.COMPUTE);
        assertEquals(RwImageStoreExtractor.RwExtractMode.CHUNK, RwImageStoreExtractor.parseSentinel(compute));
        assertTrue(calls(compute).stream().anyMatch(name -> name.equals("imageStore")));
        assertFalse(calls(compute).stream().anyMatch(name -> name.equals("_vert_init")));
        assertCompiles(compute, GL43.GL_COMPUTE_SHADER);
    }

    private static GLSLParser.Typeless_declarationContext declarator(GLSLParser.Translation_unitContext tree, String name) {
        return GlslAstHelpers.collectAll(tree, GLSLParser.Typeless_declarationContext.class).stream()
            .filter(declaration -> declaration.IDENTIFIER() != null && declaration.IDENTIFIER().getText().equals(name))
            .findFirst().orElseThrow();
    }

    private static List<String> calls(String source) {
        return GlslAstHelpers.collectAll(ShaderParser.parseShader(source).full(), GLSLParser.Postfix_expressionContext.class).stream()
            .map(GlslAstHelpers::extractCallName).filter(name -> name != null).toList();
    }

    private static Transformer parse(String source) {
        return new Transformer(ShaderParser.parseShader(source).full());
    }

    private static String format(Transformer transformer) {
        StringBuilder out = new StringBuilder();
        transformer.mutateTree(tree -> out.append(GlslTransformUtils.getFormattedShader(tree, "#version 460 core\n")));
        return out.toString();
    }

    private static void assertCompiles(String source, int stage) {
        String transformed = ShaderManager.applyPrewarmTransformsFull(source, stage, null, null).source();
        int kind = stage == GL43.GL_COMPUTE_SHADER ? Shaderc.shaderc_compute_shader
            : stage == GL20.GL_VERTEX_SHADER ? Shaderc.shaderc_vertex_shader : Shaderc.shaderc_fragment_shader;
        SpirvCompiler.Result result = SpirvCompiler.compile(transformed, kind, "sdl-iris-transform", SpirvCompiler.Options.vulkanForced460Core(), false);
        assertNotNull(result.spirv(), () -> result.error() + "\n" + transformed);
        memFree(result.spirv());
    }
}
