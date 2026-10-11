package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.shader.ShaderDiskCache;
import com.gtnewhorizons.angelica.glsm.testutil.Reflect;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.taumc.glsl.ShaderParser;
import org.taumc.glsl.grammar.GLSLLexer;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatShaderTransformerTest {

    @Test
    void portalGunStyleFragmentDropsGlesPrecisionGuardForDesktopCore() {
        String source = """
            #ifdef GL_ES
            precision mediump float;
            #endif

            uniform float closeAlpha;
            #define PI 3.1415926535897932384626433832795

            void main(void) {
                vec2 coord = gl_TexCoord[0].xy;
                gl_FragColor = vec4(coord, sin(PI * closeAlpha), 1.0);
            }
            """;

        String transformed = CompatShaderTransformer.transform(source, true);
        ShaderInspection inspection = inspectShader(transformed);

        assertEquals(0, countTokens(transformed, GLSLLexer.PRECISION), transformed);
        assertEquals(0, inspection.preprocessorSyntaxErrors, transformed);
        assertEquals(0, inspection.shaderSyntaxErrors, transformed);
        assertEquals(0, countTokens(transformed, GLSLLexer.IFDEF_DIRECTIVE), transformed);
        assertEquals(0, countTokens(transformed, GLSLLexer.ENDIF_DIRECTIVE), transformed);
        assertEquals(1, countTokens(transformed, GLSLLexer.DEFINE_DIRECTIVE), transformed);
        assertEquals(1, countTokens(transformed, GLSLLexer.VERSION_DIRECTIVE), transformed);
        assertOrdered(transformed, "#version", "#define PI", "uniform float closeAlpha");
    }

    @Test
    void ordinaryConditionalPreprocessorDirectivesRemainBalanced() {
        String source = """
            #define USE_BLUE 1
            #ifdef USE_BLUE
            #define PORTAL_COLOR vec4(0.0, 0.5, 1.0, 1.0)
            #else
            #define PORTAL_COLOR vec4(1.0, 0.5, 0.0, 1.0)
            #endif
            #define SCALE_COLOR(color) \\
                ((color) * 0.5)

            void main(void) {
                gl_FragColor = SCALE_COLOR(PORTAL_COLOR);
            }
            """;

        String transformed = CompatShaderTransformer.transform(source, true);
        ShaderInspection inspection = inspectShader(transformed);

        assertEquals(0, inspection.preprocessorSyntaxErrors, transformed);
        assertEquals(0, inspection.shaderSyntaxErrors, transformed);
        assertEquals(1, countTokens(transformed, GLSLLexer.IFDEF_DIRECTIVE), transformed);
        assertEquals(1, countTokens(transformed, GLSLLexer.ELSE_DIRECTIVE), transformed);
        assertEquals(1, countTokens(transformed, GLSLLexer.ENDIF_DIRECTIVE), transformed);
        assertEquals(4, countTokens(transformed, GLSLLexer.DEFINE_DIRECTIVE), transformed);
        assertEquals(1, countTokens(transformed, GLSLLexer.MACRO_ESC_NEWLINE), transformed);
        assertOrdered(
            transformed,
            "#define USE_BLUE",
            "#ifdef USE_BLUE",
            "#else",
            "#endif",
            "#define SCALE_COLOR",
            "void main"
        );
    }

    @Test
    void commentedGlesPrecisionGuardIsRemovedAsOneBlock() {
        String source = """
            #ifdef GL_ES // mobile profile
            precision highp int; // default integer precision
            #endif // GL_ES

            void main() {
                gl_FragColor = vec4(1.0);
            }
            """;

        String transformed = CompatShaderTransformer.transform(source, true);
        ShaderInspection inspection = inspectShader(transformed);

        assertEquals(0, inspection.preprocessorSyntaxErrors, transformed);
        assertEquals(0, inspection.shaderSyntaxErrors, transformed);
        assertEquals(0, countTokens(transformed, GLSLLexer.PRECISION), transformed);
        assertEquals(0, countTokens(transformed, GLSLLexer.IFDEF_DIRECTIVE), transformed);
        assertEquals(0, countTokens(transformed, GLSLLexer.ENDIF_DIRECTIVE), transformed);
    }

    @Test
    void conditionalGlslSelectsTheTakenBranchUnderEvaluation() {
        String source = """
            #ifdef USE_PORTAL_COLOR
            varying vec4 portalColor;
            #else
            varying vec4 fallbackColor;
            #endif

            void main() {
                gl_FragColor = fallbackColor;
            }
            """;

        String transformed = CompatShaderTransformer.transform(source, true);

        // USE_PORTAL_COLOR is undefined, so the #else branch is kept (and transformed) while the
        // dead branch is dropped; previously this shader fell back to version-fixup-only output
        // that could not compile under core profile.
        assertFalse(transformed.contains("portalColor"), transformed);
        assertTrue(transformed.contains("fallbackColor"), transformed);
        assertTrue(transformed.contains("actinium_FragData0"), transformed);
    }

    @Test
    void defineAndUndefKeepTheirPointInSource() {
        String source = """
            #define PORTAL_SCALE 1.0
            const float firstScale = PORTAL_SCALE;
            #undef PORTAL_SCALE
            #define PORTAL_SCALE 2.0
            const float secondScale = PORTAL_SCALE;

            void main() {
                gl_FragColor = vec4(firstScale, secondScale, 0.0, 1.0);
            }
            """;

        String transformed = CompatShaderTransformer.transform(source, true);

        assertEquals("#version 330 core\n" + source, transformed);
        assertOrdered(
            transformed,
            "#define PORTAL_SCALE 1.0",
            "const float firstScale",
            "#undef PORTAL_SCALE",
            "#define PORTAL_SCALE 2.0",
            "const float secondScale"
        );
    }

    @Test
    void directiveTextInsideBlockCommentIsNotActivated() {
        String source = """
            /*
            #define HIDDEN_PORTAL_COLOR vec4(1.0)
            */
            #define ACTIVE_PORTAL_COLOR vec4(0.0)

            void main() {
                gl_FragColor = ACTIVE_PORTAL_COLOR;
            }
            """;

        String transformed = CompatShaderTransformer.transform(source, true);
        ShaderInspection inspection = inspectShader(transformed);

        assertEquals(0, inspection.preprocessorSyntaxErrors, transformed);
        assertEquals(0, inspection.shaderSyntaxErrors, transformed);
        assertEquals(1, countTokens(transformed, GLSLLexer.DEFINE_DIRECTIVE), transformed);
        assertOrdered(transformed, "#version", "#define ACTIVE_PORTAL_COLOR", "void main");
    }

    @Test
    void extensionAndContinuedMacroStayInPreambleOrder() {
        String source = """
            #version 120
            #extension GL_ARB_texture_rectangle : enable
            #define HALF_COLOR(color) \\
                ((color) * 0.5)

            void main() {
                gl_FragColor = HALF_COLOR(vec4(1.0));
            }
            """;

        String transformed = CompatShaderTransformer.transform(source, true);
        ShaderInspection inspection = inspectShader(transformed);

        assertEquals(0, inspection.preprocessorSyntaxErrors, transformed);
        assertEquals(0, inspection.shaderSyntaxErrors, transformed);
        assertEquals(1, countTokens(transformed, GLSLLexer.MACRO_ESC_NEWLINE), transformed);
        assertOrdered(
            transformed,
            "#version 330 core",
            "#extension GL_ARB_texture_rectangle : enable",
            "#define HALF_COLOR",
            "((color) * 0.5)",
            "void main"
        );
    }

    @Test
    void betterPortalsPortalVertexShaderTransformsToCoreProfile() {
        String transformed = CompatShaderTransformer.transform(
            resource("/compat_shaders/betterportals_render_portal.vsh"), false);

        // The taken desktop branch's FFP builtins are replaced by Actinium uniforms/attributes.
        assertTrue(hasIdentifier(transformed, "actinium_ModelViewMatrix"), transformed);
        assertTrue(hasIdentifier(transformed, "actinium_ProjectionMatrix"), transformed);
        assertTrue(hasIdentifier(transformed, "actinium_Vertex"), transformed);
        assertTrue(hasIdentifier(transformed, "actinium_FogFragCoord"), transformed);
        assertFalse(hasIdentifier(transformed, "gl_ModelViewMatrix"), transformed);
        assertFalse(hasIdentifier(transformed, "gl_Vertex"), transformed);

        // The GLES-only branch is dropped: no explicit ESSL attribute/uniform path may leak in.
        assertFalse(hasIdentifier(transformed, "bpModelViewMatrix"), transformed);
        assertFalse(hasIdentifier(transformed, "Position"), transformed);

        // BP_SKIP_FIXED_CLIP resolves to 1 at the target version, so the gl_ClipVertex assignment
        // (which has no core-profile equivalent) is excluded by the shader's own guard.
        assertFalse(hasIdentifier(transformed, "gl_ClipVertex"), transformed);
    }

    @Test
    void betterPortalsPortalFragmentShaderTransformsToCoreProfile() {
        String transformed = CompatShaderTransformer.transform(
            resource("/compat_shaders/betterportals_render_portal.fsh"), true);

        assertFalse(hasIdentifier(transformed, "gl_FogFragCoord"), transformed);
        assertFalse(hasIdentifier(transformed, "bpFogFragCoord"), transformed);
        assertFalse(hasIdentifier(transformed, "texture2D"), transformed);

        // Forge's ShaderManager resolves these uniforms by name at runtime; dropping or
        // renaming any of them silently breaks the portal surface (a missing "sampler"
        // uniform left the remote view unsampled in production).
        assertTrue(hasIdentifier(transformed, "sampler"), transformed);
        assertTrue(hasIdentifier(transformed, "screenSize"), transformed);
        assertTrue(hasIdentifier(transformed, "fogDensity"), transformed);
        assertTrue(hasIdentifier(transformed, "fogColor"), transformed);
        assertTrue(hasIdentifier(transformed, "opacity"), transformed);
        // The declaration alone is not enough: the sampler must stay in use inside main,
        // otherwise the compiler deactivates the uniform and the runtime lookup fails.
        assertOrdered(transformed, "uniform sampler2D sampler", "void main", "sampler");
    }

    @Test
    void chocolateQuestRepouredSphereFragmentShaderTransformsToCoreProfile() {
        // CQR ships these shaders with CRLF line endings; .gitattributes normalizes test
        // resources to LF, so the CRLF form is constructed explicitly.
        String source = """
            #version 110

            uniform samplerCube cubemap;
            uniform vec4 color;
            uniform bool useTexture;

            void main() {
              if (useTexture) {
                gl_FragColor = textureCube(cubemap, gl_TexCoord[0].stp) * color;
              } else {
                gl_FragColor = color;
              }
            }
            """.replace("\n", "\r\n");

        String transformed = CompatShaderTransformer.transform(source, true);
        ShaderInspection inspection = inspectShader(transformed);

        assertEquals(0, inspection.preprocessorSyntaxErrors, transformed);
        assertEquals(0, inspection.shaderSyntaxErrors, transformed);
        assertFalse(transformed.contains("<missing"), transformed);

        // textureCube is renamed to the core texture() builtin before parsing; the grammar
        // lexes it as a keyword token and silently recovers from it in call position otherwise.
        assertFalse(hasIdentifier(transformed, "textureCube"), transformed);
        assertCall(transformed, "texture", "cubemap");

        // The mod's own uniforms must stay declared before main; a recovered broken AST used to
        // displace them into the function body, which the driver then rejected.
        assertOrdered(transformed, "uniform vec4 color", "uniform bool useTexture", "void main");

        assertFalse(hasIdentifier(transformed, "gl_FragColor"), transformed);
        assertTrue(hasIdentifier(transformed, "actinium_FragData0"), transformed);
        assertFalse(hasIdentifier(transformed, "gl_TexCoord"), transformed);
        assertTrue(hasIdentifier(transformed, "actinium_TexCoord0"), transformed);
    }

    @Test
    void chocolateQuestRepouredSphereVertexShaderTransformsToCoreProfile() {
        String source = """
            #version 110

            void main() {
              gl_Position = gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex;
              gl_TexCoord[0].stp = gl_Vertex.xyz;
            }
            """.replace("\n", "\r\n");

        String transformed = CompatShaderTransformer.transform(source, false);
        ShaderInspection inspection = inspectShader(transformed);

        assertEquals(0, inspection.preprocessorSyntaxErrors, transformed);
        assertEquals(0, inspection.shaderSyntaxErrors, transformed);
        assertFalse(transformed.contains("<missing"), transformed);

        assertTrue(hasIdentifier(transformed, "actinium_ProjectionMatrix"), transformed);
        assertTrue(hasIdentifier(transformed, "actinium_ModelViewMatrix"), transformed);
        assertTrue(hasIdentifier(transformed, "actinium_Vertex"), transformed);
        assertTrue(hasIdentifier(transformed, "actinium_TexCoord0"), transformed);
        assertFalse(hasIdentifier(transformed, "gl_ProjectionMatrix"), transformed);
        assertFalse(hasIdentifier(transformed, "gl_ModelViewMatrix"), transformed);
        assertFalse(hasIdentifier(transformed, "gl_Vertex"), transformed);
        assertFalse(hasIdentifier(transformed, "gl_TexCoord"), transformed);
    }

    static Stream<Arguments> parseBreakingTextureFunctions() {
        // The grammar lexes these legacy builtins as keyword tokens but rejects them in
        // function-call position; each must be renamed to texture() before parsing.
        // Samplers stay undeclared: the check is syntactic (grammar has no semantic analysis).
        return Stream.of(
            Arguments.of("texture1D", "tex", "0.0"),
            Arguments.of("textureCube", "cubemap", "vec3(0.0)"),
            Arguments.of("texture2DRect", "rectTex", "vec2(0.0)"),
            Arguments.of("texture1DArray", "arrayTex", "vec2(0.0)"),
            Arguments.of("texture2DArray", "arrayTex", "vec3(0.0)"),
            Arguments.of("textureCubeArray", "cubeArrayTex", "vec4(0.0)")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parseBreakingTextureFunctions")
    void parseBreakingLegacyTextureFunctionsAreRenamedBeforeParsing(String functionName, String samplerName, String argument) {
        String source = "#version 110\n\nvoid main() {\n    gl_FragColor = "
            + functionName + "(" + samplerName + ", " + argument + ");\n}\n";

        String transformed = CompatShaderTransformer.transform(source, true);
        ShaderInspection inspection = inspectShader(transformed);

        assertEquals(0, inspection.preprocessorSyntaxErrors, transformed);
        assertEquals(0, inspection.shaderSyntaxErrors, transformed);
        assertFalse(hasIdentifier(transformed, functionName), transformed);
        assertCall(transformed, "texture", samplerName);
    }

    @Test
    void syntacticallyInvalidShaderFallsBackToVersionFixup() {
        // Missing semicolon: ANTLR recovers silently, so without a fail-fast check the
        // transformer would emit broken GLSL that the driver rejects (the CQR crash mode).
        String source = """
            #version 110

            void main() {
                gl_FragColor = vec4(1.0)
            }
            """;

        String transformed = CompatShaderTransformer.transform(source, true);

        assertEquals(source.replace("#version 110", "#version 330 core"), transformed);
    }

    @Test
    void legacyTextureFunctionAloneTriggersTransformation() {
        // No gl_* builtins here: the legacy texture call itself must engage the transform,
        // otherwise the unrenamed builtin leaks unchanged to the core-profile driver.
        String source = "#version 110\n\nvoid main() {\n    vec4 c = textureCube(cubemap, vec3(0.0));\n}\n";

        String transformed = CompatShaderTransformer.transform(source, true);
        ShaderInspection inspection = inspectShader(transformed);

        assertEquals(0, inspection.preprocessorSyntaxErrors, transformed);
        assertEquals(0, inspection.shaderSyntaxErrors, transformed);
        assertTrue(transformed.contains("#version 330 core"), transformed);
        assertFalse(hasIdentifier(transformed, "textureCube"), transformed);
        assertCall(transformed, "texture", "cubemap");
    }

    @Test
    void shadow1DFamilyIsWrappedAndRenamedLikeShadow2D() {
        String source = """
            #version 110

            void main() {
                float a = shadow1D(shadowMap, vec3(0.0)).x;
                float b = shadow2DProj(shadowMap2, vec4(0.0)).x;
            }
            """;

        String transformed = CompatShaderTransformer.transform(source, true);
        ShaderInspection inspection = inspectShader(transformed);

        assertEquals(0, inspection.preprocessorSyntaxErrors, transformed);
        assertEquals(0, inspection.shaderSyntaxErrors, transformed);
        assertFalse(hasIdentifier(transformed, "shadow1D"), transformed);
        assertFalse(hasIdentifier(transformed, "shadow2DProj"), transformed);
        // Legacy shadow lookups return vec4; the core texture/textureProj equivalents return
        // float, so the transformer must keep the vec4 wrapping around the renamed call.
        assertTrue(Pattern.compile("vec4\\s*\\(\\s*texture\\s*\\(").matcher(transformed).find(), transformed);
        assertTrue(Pattern.compile("vec4\\s*\\(\\s*textureProj\\s*\\(").matcher(transformed).find(), transformed);
    }

    /** Identifier-level check: comments preserved in the preamble must not trip the assertions. */
    private static boolean hasIdentifier(String source, String name) {
        GLSLLexer lexer = new GLSLLexer(CharStreams.fromString(source));
        for (Token token = lexer.nextToken(); token.getType() != Token.EOF; token = lexer.nextToken()) {
            if (token.getType() == GLSLLexer.IDENTIFIER && name.equals(token.getText())) {
                return true;
            }
        }
        return false;
    }

    private static String resource(String name) {
        try (var stream = CompatShaderTransformerTest.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IllegalStateException("Missing test resource: " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read test resource: " + name, e);
        }
    }

    private static int countTokens(String source, int tokenType) {
        GLSLLexer lexer = new GLSLLexer(CharStreams.fromString(source));
        int count = 0;
        for (Token token = lexer.nextToken(); token.getType() != Token.EOF; token = lexer.nextToken()) {
            if (token.getType() == tokenType) {
                count++;
            }
        }
        return count;
    }

    private static ShaderInspection inspectShader(String source) {
        ShaderParser.ParsedShader shader = ShaderParser.parseShader(source);
        return new ShaderInspection(shader.preParser().getNumberOfSyntaxErrors(), shader.parser().getNumberOfSyntaxErrors());
    }

    private static void assertCall(String source, String functionName, String firstArgument) {
        Pattern callPattern = Pattern.compile(
            "\\b" + Pattern.quote(functionName) + "\\s*\\(\\s*" + Pattern.quote(firstArgument) + "\\b");
        assertTrue(
            callPattern.matcher(source).find(),
            "Expected call " + functionName + "(" + firstArgument + ", ...) in:\n" + source);
    }

    private static void assertOrdered(String source, String... fragments) {
        int previousOffset = -1;
        for (String fragment : fragments) {
            int offset = source.indexOf(fragment, previousOffset + 1);
            assertTrue(offset > previousOffset, "Expected '" + fragment + "' after offset " + previousOffset + " in:\n" + source);
            previousOffset = offset;
        }
    }

    private record ShaderInspection(
        int preprocessorSyntaxErrors,
        int shaderSyntaxErrors
    ) {}

    @Test
    void diskCacheHitReplacesTransform(@TempDir Path dir) {
        final String src = """
            #version 120
            void main() {
                gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
            }
            """;
        try {
            ShaderDiskCache.configure(dir, "t");
            CompatShaderTransformer.clearCache();
            final String first = CompatShaderTransformer.transform(src, false);
            assertTrue(first.contains("actinium_ProjectionMatrix"));
            CompatShaderTransformer.clearCache();
            assertEquals(first, CompatShaderTransformer.transform(src, false));
            CompatShaderTransformer.clearCache();
            ShaderDiskCache.putString(CompatShaderTransformer.diskKey(src, false), "POISON");
            assertEquals("POISON", CompatShaderTransformer.transform(src, false));
        } finally {
            CompatShaderTransformer.clearCache();
            Reflect.setStatic(ShaderDiskCache.class, "root", null);
        }
    }
}
