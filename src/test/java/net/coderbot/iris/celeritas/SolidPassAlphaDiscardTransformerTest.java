package net.coderbot.iris.celeritas;

import com.gtnewhorizons.angelica.glsm.GlslTransformUtils;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.junit.jupiter.api.Test;
import org.taumc.glsl.ShaderParser;
import org.taumc.glsl.grammar.GLSLLexer;
import org.taumc.glsl.grammar.GLSLParser;
import org.taumc.glsl.grammar.GLSLParserBaseListener;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolidPassAlphaDiscardTransformerTest {
    private static final Pattern VERSION_PATTERN = Pattern.compile("#version\\s+(\\d+)");

    /** Derivative's {@code shaders/program/Gbuffers/Terrain.frag} alpha guard. */
    private static final String TERRAIN_FRAGMENT = """
        #version 330 core

        uniform sampler2D tex;
        in vec2 texcoord;
        in vec4 tint;
        layout(location = 0) out vec3 albedoData;
        layout(location = 1) out vec3 normalData;
        layout(location = 2) out vec4 materialData;

        void main() {
            vec4 albedo = tint;
            albedo *= texture(tex, texcoord);
            if (albedo.a < 0.1) { discard; return; }
            albedoData = albedo.rgb;
            normalData = vec3(0.0, 0.0, 1.0);
            materialData = vec4(0.0, 0.0, 0.0, albedo.a);
        }
        """;

    private static final String TERRAIN_FRAGMENT_WITHOUT_GUARD = """
        #version 330 core

        uniform sampler2D tex;
        in vec2 texcoord;
        in vec4 tint;
        layout(location = 0) out vec3 albedoData;
        layout(location = 1) out vec3 normalData;
        layout(location = 2) out vec4 materialData;

        void main() {
            vec4 albedo = tint;
            albedo *= texture(tex, texcoord);
            albedoData = albedo.rgb;
            normalData = vec3(0.0, 0.0, 1.0);
            materialData = vec4(0.0, 0.0, 0.0, albedo.a);
        }
        """;

    @Test
    void removesDerivativeAlphaDiscardGuardFromSolidPass() {
        String stripped = SolidPassAlphaDiscardTransformer.forPass(IrisTerrainPass.GBUFFER_SOLID, TERRAIN_FRAGMENT);

        assertSameTree(TERRAIN_FRAGMENT_WITHOUT_GUARD, stripped);
        assertEquals(0, discards(stripped).size(), stripped);
    }

    @Test
    void keepsAlphaDiscardGuardForCutoutPasses() {
        for (IrisTerrainPass pass : List.of(IrisTerrainPass.GBUFFER_CUTOUT, IrisTerrainPass.SHADOW_CUTOUT,
            IrisTerrainPass.GBUFFER_TRANSLUCENT, IrisTerrainPass.SHADOW_TRANSLUCENT)) {
            String patched = SolidPassAlphaDiscardTransformer.forPass(pass, TERRAIN_FRAGMENT);

            assertSameTree(pass + " must keep the pack guard", TERRAIN_FRAGMENT, patched);
            assertEquals(1, selections(patched).size(), pass + ": " + patched);
            assertEquals(1, discards(patched).size(), pass + ": " + patched);
        }
    }

    @Test
    void removesAlphaDiscardGuardFromShadowPass() {
        String stripped = SolidPassAlphaDiscardTransformer.forPass(IrisTerrainPass.SHADOW, TERRAIN_FRAGMENT);

        assertSameTree(TERRAIN_FRAGMENT_WITHOUT_GUARD, stripped);
    }

    @Test
    void removesBracelessAlphaDiscardGuard() {
        String shader = """
            #version 330 core
            uniform sampler2D tex;
            out vec4 fragColor;
            void main() {
                vec4 albedo = texture(tex, vec2(0.0));
                if (albedo.a < 0.1) discard;
                fragColor = albedo;
            }
            """;
        String expected = """
            #version 330 core
            uniform sampler2D tex;
            out vec4 fragColor;
            void main() {
                vec4 albedo = texture(tex, vec2(0.0));
                fragColor = albedo;
            }
            """;

        assertSameTree(expected, SolidPassAlphaDiscardTransformer.forPass(IrisTerrainPass.GBUFFER_SOLID, shader));
    }

    @Test
    void removesGuardReadingAnAlphaIdentifier() {
        String shader = """
            #version 330 core
            uniform float alpha;
            out vec4 fragColor;
            void main() {
                if (alpha < 0.5) { discard; }
                fragColor = vec4(1.0);
            }
            """;
        String expected = """
            #version 330 core
            uniform float alpha;
            out vec4 fragColor;
            void main() {
                fragColor = vec4(1.0);
            }
            """;

        assertSameTree(expected, SolidPassAlphaDiscardTransformer.forPass(IrisTerrainPass.GBUFFER_SOLID, shader));
    }

    @Test
    void keepsDiscardThatDoesNotReadAlpha() {
        String shader = """
            #version 330 core
            in vec3 viewPos;
            out vec4 fragColor;
            void main() {
                if (viewPos.y < 0.0) {
                    discard;
                }
                fragColor = vec4(1.0);
            }
            """;

        assertSameTree(shader, SolidPassAlphaDiscardTransformer.forPass(IrisTerrainPass.GBUFFER_SOLID, shader));
    }

    @Test
    void keepsGuardWhoseThenBranchDoesMoreThanDiscard() {
        String shader = """
            #version 330 core
            out vec4 fragColor;
            void main() {
                vec4 albedo = vec4(1.0);
                if (albedo.a < 0.1) {
                    albedo.rgb = vec3(0.0);
                    discard;
                }
                fragColor = albedo;
            }
            """;

        assertSameTree(shader, SolidPassAlphaDiscardTransformer.forPass(IrisTerrainPass.GBUFFER_SOLID, shader));
    }

    @Test
    void keepsGuardWithElseBranchDoingOtherWork() {
        String shader = """
            #version 330 core
            out vec4 fragColor;
            void main() {
                vec4 albedo = vec4(1.0);
                if (albedo.a < 0.1) {
                    discard;
                    return;
                } else {
                    albedo.rgb *= 0.5;
                }
                fragColor = albedo;
            }
            """;

        assertSameTree(shader, SolidPassAlphaDiscardTransformer.forPass(IrisTerrainPass.GBUFFER_SOLID, shader));
    }

    @Test
    void stripsAlphaDiscardFromFragmentWithReservedWordIdentifiers() {
        String shader = """
            #version 330 core
            uniform sampler2D tex;
            float sample;
            out vec4 fragColor;
            void main() {
                vec4 albedo = texture(tex, vec2(0.0)) * sample;
                if (albedo.a < 0.1) { discard; return; }
                fragColor = albedo;
            }
            """;

        String stripped = SolidPassAlphaDiscardTransformer.forPass(IrisTerrainPass.GBUFFER_SOLID, shader);

        assertEquals(stripped, GlslTransformUtils.restoreReservedWords(stripped),
            "emitted shader still holds renamed keywords");
        ShaderParser.ParsedShader parsed = parseRenamed(stripped);
        assertEquals(0, parsed.parser().getNumberOfSyntaxErrors(), stripped);
        assertEquals(0, parsed.preParser().getNumberOfSyntaxErrors(), stripped);
        assertEquals(List.of("tex", "sample", "fragColor", "albedo"), declaredVariableNames(parsed.full()), stripped);
        assertEquals(0, discards(parsed.full()).size(), stripped);
    }

    @Test
    void failsFastOnUnparseableFragment() {
        String shader = """
            #version 330 core
            out vec4 fragColor;
            void main() {
                if (albedo.a < 0.1) { discard; return; }
                fragColor = vec4(1.0;
            }
            """;

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> SolidPassAlphaDiscardTransformer.forPass(IrisTerrainPass.GBUFFER_SOLID, shader));

        assertTrue(thrown.getMessage().contains(IrisTerrainPass.GBUFFER_SOLID.name()), thrown.getMessage());
    }

    @Test
    void strippedSourceKeepsVersionDeclarationsAndStaysParseable() {
        String stripped = SolidPassAlphaDiscardTransformer.forPass(IrisTerrainPass.GBUFFER_SOLID, TERRAIN_FRAGMENT);

        ShaderParser.ParsedShader parsed = ShaderParser.parseShader(stripped);
        assertEquals(0, parsed.parser().getNumberOfSyntaxErrors(), stripped);
        assertEquals(versionDirectives(TERRAIN_FRAGMENT), versionDirectives(stripped));
        assertEquals(declaredVariableNames(parse(TERRAIN_FRAGMENT)), declaredVariableNames(parsed.full()), stripped);
        assertEquals(List.of("main"), functions(stripped).stream()
            .map(function -> function.function_prototype().IDENTIFIER().getText())
            .toList(), stripped);
    }

    @Test
    void keepsPreambleDirectivesWhenStripping() {
        String shader = """
            #version 330 core
            #extension GL_ARB_shader_texture_lod : enable

            uniform sampler2D tex;
            out vec4 fragColor;

            void main() {
                vec4 albedo = texture(tex, vec2(0.0));
                if (albedo.a < 0.1) { discard; return; }
                fragColor = albedo;
            }
            """;

        String stripped = SolidPassAlphaDiscardTransformer.forPass(IrisTerrainPass.GBUFFER_SOLID, shader);

        assertEquals(1, directives(stripped, GLSLLexer.VERSION_DIRECTIVE), stripped);
        assertEquals(directives(shader, GLSLLexer.EXTENSION_DIRECTIVE), directives(stripped, GLSLLexer.EXTENSION_DIRECTIVE), stripped);
        assertEquals(0, discards(stripped).size(), stripped);
    }

    @Test
    void keepsGuardReadingIdentifierThatMerelyStartsWithAlpha() {
        String shader = guardFixture("alphaData < 0.5");

        String patched = SolidPassAlphaDiscardTransformer.forPass(IrisTerrainPass.GBUFFER_SOLID, shader);

        assertSameTree(shader, patched);
        assertEquals(1, selections(patched).size(), patched);
        assertEquals(1, discards(patched).size(), patched);
    }

    @Test
    void stripsGuardsReadingVariablesNamedForAlpha() {
        for (String condition : List.of("alpha < 0.5", "colorAlpha < 0.5", "color_alpha < 0.5")) {
            String shader = guardFixture(condition);

            String patched = SolidPassAlphaDiscardTransformer.forPass(IrisTerrainPass.GBUFFER_SOLID, shader);

            assertEquals(0, selections(patched).size(), condition + "\n" + patched);
            assertEquals(0, discards(patched).size(), condition + "\n" + patched);
        }
    }

    private static String guardFixture(String condition) {
        return "#version 330 core\n"
            + "uniform sampler2D tex;\n"
            + "uniform float alpha;\n"
            + "uniform float colorAlpha;\n"
            + "uniform float color_alpha;\n"
            + "in float alphaData;\n"
            + "out vec4 fragColor;\n"
            + "void main() {\n"
            + "    vec4 albedo = texture(tex, vec2(0.0));\n"
            + "    if (" + condition + ") { discard; return; }\n"
            + "    fragColor = albedo;\n"
            + "}\n";
    }

    @Test
    void leavesSourceWithoutAlphaGuardByteIdentical() {
        String stripped = SolidPassAlphaDiscardTransformer.forPass(IrisTerrainPass.GBUFFER_SOLID, TERRAIN_FRAGMENT_WITHOUT_GUARD);

        assertEquals(TERRAIN_FRAGMENT_WITHOUT_GUARD, stripped);
    }

    private static void assertSameTree(String expectedSource, String actualSource) {
        assertEquals(print(expectedSource), print(actualSource), actualSource);
    }

    private static void assertSameTree(String message, String expectedSource, String actualSource) {
        assertEquals(print(expectedSource), print(actualSource), message + "\n" + actualSource);
    }

    private static String print(String source) {
        ShaderParser.ParsedShader parsed = ShaderParser.parseShader(source);
        assertEquals(0, parsed.parser().getNumberOfSyntaxErrors(), source);
        return GlslTransformUtils.getFormattedShader(parsed.full(), "");
    }

    private static List<GLSLParser.Selection_statementContext> selections(String source) {
        List<GLSLParser.Selection_statementContext> found = new ArrayList<>();
        ParseTreeWalker.DEFAULT.walk(new GLSLParserBaseListener() {
            @Override
            public void enterSelection_statement(GLSLParser.Selection_statementContext context) {
                found.add(context);
            }
        }, parse(source));
        return found;
    }

    private static List<GLSLParser.Jump_statementContext> discards(String source) {
        return discards(parse(source));
    }

    private static List<GLSLParser.Jump_statementContext> discards(ParseTree tree) {
        List<GLSLParser.Jump_statementContext> found = new ArrayList<>();
        ParseTreeWalker.DEFAULT.walk(new GLSLParserBaseListener() {
            @Override
            public void enterJump_statement(GLSLParser.Jump_statementContext context) {
                if (context.DISCARD() != null) {
                    found.add(context);
                }
            }
        }, tree);
        return found;
    }

    private static List<String> declaredVariableNames(ParseTree tree) {
        List<String> names = new ArrayList<>();
        ParseTreeWalker.DEFAULT.walk(new GLSLParserBaseListener() {
            @Override
            public void enterTypeless_declaration(GLSLParser.Typeless_declarationContext context) {
                names.add(context.IDENTIFIER().getText());
            }
        }, tree);
        return names;
    }

    private static ShaderParser.ParsedShader parseRenamed(String source) {
        Matcher version = VERSION_PATTERN.matcher(source);
        assertTrue(version.find(), "fixture has no #version directive: " + source);
        ShaderParser.ParsedShader parsed = ShaderParser.parseShader(
            GlslTransformUtils.renameReservedWords(source, Integer.parseInt(version.group(1))));
        GlslTransformUtils.restoreReservedWordsInTree(parsed.full());
        return parsed;
    }

    private static List<GLSLParser.Function_definitionContext> functions(String source) {
        List<GLSLParser.Function_definitionContext> found = new ArrayList<>();
        ParseTreeWalker.DEFAULT.walk(new GLSLParserBaseListener() {
            @Override
            public void enterFunction_definition(GLSLParser.Function_definitionContext context) {
                found.add(context);
            }
        }, parse(source));
        return found;
    }

    private static ParseTree parse(String source) {
        ShaderParser.ParsedShader parsed = ShaderParser.parseShader(source);
        assertEquals(0, parsed.parser().getNumberOfSyntaxErrors(), source);
        return parsed.full();
    }

    private static long versionDirectives(String source) {
        long count = directives(source, GLSLLexer.VERSION_DIRECTIVE);
        assertTrue(count > 0, "expected a #version directive in: " + source);
        return count;
    }

    private static long directives(String source, int tokenType) {
        long count = 0;
        for (Token token : GlslTransformUtils.quietLexer(source).getAllTokens()) {
            if (token.getType() == tokenType) {
                count++;
            }
        }
        return count;
    }
}
