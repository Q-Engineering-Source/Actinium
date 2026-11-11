package net.coderbot.iris.pipeline.transform;

import com.gtnewhorizons.angelica.glsm.GlslTransformUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.taumc.glsl.ShaderParser;
import org.taumc.glsl.Transformer;
import org.taumc.glsl.grammar.GLSLLexer;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamplerAliasDeduplicatorTest {
    @ParameterizedTest
    @CsvSource({"tex,gtexture", "depthtex0,gdepthtex", "dhDepthTex0,dhDepthTex"})
    void mergesAliasesAcrossStagesAndPreservesIndependentSamplers(String canonical, String alias) {
        Transformer vertex = parse("uniform sampler2D " + alias + ";\n"
            + "void main() { gl_Position = texture(" + alias + ", vec2(0.5)); }");
        Transformer fragment = parse("uniform sampler2D " + canonical + ";\n"
            + "uniform sampler2D " + alias + ";\n"
            + "uniform sampler2D customSampler;\n"
            + "out vec4 color;\nvoid main() { color = texture(" + alias + ", vec2(0.5))"
            + " + texture(" + canonical + ", vec2(0.5)) + texture(customSampler, vec2(0.5)); }");
        Map<PatchShaderType, Transformer> stages = new EnumMap<>(PatchShaderType.class);
        stages.put(PatchShaderType.VERTEX, vertex);
        stages.put(PatchShaderType.FRAGMENT, fragment);

        SamplerAliasDeduplicator.transformGrouped(stages, null);

        for (Transformer stage : stages.values()) {
            assertEquals(GLSLLexer.SAMPLER2D, stage.findType(canonical));
            assertEquals(0, stage.findType(alias));
            assertFalse(stage.hasVariable(alias));
            assertTrue(stage.hasVariable(canonical));
            stage.mutateTree(tree -> {
                Transformer reparsed = parse(GlslTransformUtils.getFormattedShader(tree, ""));
                assertEquals(GLSLLexer.SAMPLER2D, reparsed.findType(canonical));
                assertEquals(0, reparsed.findType(alias));
            });
        }
        assertEquals(GLSLLexer.SAMPLER2D, fragment.findType("customSampler"));
        assertTrue(fragment.hasVariable("customSampler"));
    }

    private static Transformer parse(String source) {
        return new Transformer(ShaderParser.parseShader(source).full());
    }
}
