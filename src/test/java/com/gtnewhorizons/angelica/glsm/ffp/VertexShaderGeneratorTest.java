package com.gtnewhorizons.angelica.glsm.ffp;

import com.gtnewhorizons.angelica.glsm.GlslTransformUtils;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.junit.jupiter.api.Test;
import org.taumc.glsl.ShaderParser;
import org.taumc.glsl.Transformer;
import org.taumc.glsl.grammar.GLSLLexer;
import org.taumc.glsl.grammar.GLSLParser;
import org.taumc.glsl.grammar.GLSLParserBaseListener;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VertexShaderGeneratorTest {
    private static final int BIT_HAS_VERTEX_TEX = 18;
    private static final int BIT_TEXTURE = 12;
    private static final int BIT_TEX_MATRIX = 37;
    private static final int BIT_TEXGEN_S = 23;
    private static final int BIT_TEXGEN_T = 26;
    private static final int BIT_TEXGEN_R = 29;
    private static final int BIT_UNIT2_TEX = 14;
    private static final int BIT_UNIT3_TEX = 15;
    private static final int BIT_UNIT23_UV_FROM_UNIT0 = 41;
    private static final int BIT_LINE_STIPPLE = 42;
    private static final int BIT_UNIT2_TEXMAT = 39;
    private static final int BIT_UNIT3_TEXMAT = 40;
    private static final int BIT_HAS_VERTEX_TEX2 = 43;
    private static final int BIT_HAS_VERTEX_TEX3 = 44;
    private static final int BIT_HAS_VERTEX_COLOR = 16;
    private static final int BIT_HAS_VERTEX_LIGHTMAP = 19;
    private static final int BIT_UNIT_TEX_BASE = 12;
    private static final int BIT_LIGHTING = 0;
    private static final int BIT_HAS_VERTEX_NORMAL = 17;

    @Test
    void particleLayoutRebuildsPositionColorAndLightmapFromTheInstance() {
        final long packed = (1L << BIT_TEXTURE) | (1L << BIT_HAS_VERTEX_TEX)
            | (1L << BIT_HAS_VERTEX_COLOR) | (1L << BIT_HAS_VERTEX_LIGHTMAP);
        final String shader = VertexShaderGenerator.generate(VertexKey.fromPacked(VertexKey.withInstancing(packed, Instancing.PARTICLE)));

        assertTrue(shader.contains("layout(location = 2) in vec2 a_TexCoord0;"), shader);
        assertFalse(shader.contains("in vec4 a_Color"), "the vertex colour slot belongs to the instance colour");
        assertTrue(shader.contains("in vec4 a_InstCenterHalf;"), shader);
        assertTrue(shader.contains("in vec4 a_InstUv;"), shader);
        assertTrue(shader.contains("in vec4 a_InstColor;"), shader);
        assertTrue(shader.contains("in vec2 a_InstLightmap;"), shader);
        assertFalse(shader.contains("a_TexCoord1"), "the vertex format has no lightmap element");
        assertFalse(shader.contains("a_Normal"), "the quad mesh has no normal element");
    }

    @Test
    void particleLayoutSourcesPositionColorAndLightmapFromTheInstance() {
        final long packed = (1L << BIT_TEXTURE) | (1L << BIT_HAS_VERTEX_TEX)
            | (1L << BIT_HAS_VERTEX_COLOR) | (1L << BIT_HAS_VERTEX_LIGHTMAP)
            | (1L << (BIT_UNIT_TEX_BASE + 1));
        final String shader = VertexShaderGenerator.generate(VertexKey.fromPacked(VertexKey.withInstancing(packed, Instancing.PARTICLE)));

        assertTrue(shader.contains("vec4 pos4 = vec4(a_InstCenterHalf.xyz + a_Position * a_InstCenterHalf.w, 1.0);"), shader);
        assertTrue(shader.contains("v_Color = a_InstColor;"), shader);
        assertTrue(shader.contains("mix(a_InstUv.x, a_InstUv.z, a_TexCoord0.x)"), shader);
        assertTrue(shader.contains("v_TexCoord1 = u_LightmapTextureMatrix * vec4(a_InstLightmap, 0.0, 1.0);"), shader);
    }

    @Test
    void particleLayoutUsesTheBillboardNormalInsteadOfAVertexNormal() {
        final long packed = (1L << BIT_LIGHTING) | (1L << BIT_TEXTURE) | (1L << BIT_HAS_VERTEX_TEX)
            | (1L << BIT_HAS_VERTEX_COLOR) | (1L << BIT_HAS_VERTEX_LIGHTMAP) | (1L << BIT_HAS_VERTEX_NORMAL);
        final String shader = VertexShaderGenerator.generate(VertexKey.fromPacked(VertexKey.withInstancing(packed, Instancing.PARTICLE)));

        assertTrue(shader.contains("vec3 normal = u_NormalMatrix * vec3(0.0, 0.0, 1.0);"), shader);
        assertFalse(shader.contains("a_Normal"), "the quad mesh has no normal attribute");
        assertFalse(shader.contains("u_CurrentNormal"), "the billboard normal is a constant, not the per-draw one");
    }

    @Test
    void lineStippleEmitsFlatLineStartVaryingAndViewportUniform() {
        String shader = VertexShaderGenerator.generate(VertexKey.fromPacked(1L << BIT_LINE_STIPPLE));

        assertTrue(shader.contains("flat out vec2 v_LineStart;"), shader);
        assertTrue(shader.contains("uniform vec4 u_Viewport;"), shader);
        assertTrue(shader.contains("v_LineStart = u_Viewport.xy + (ndc * 0.5 + 0.5) * u_Viewport.zw;"), shader);
    }

    @Test
    void units2And3EmitOwnVaryingsFedByCurrentTexCoordUniforms() {
        String shader = VertexShaderGenerator.generate(VertexKey.fromPacked((1L << BIT_UNIT2_TEX) | (1L << BIT_UNIT3_TEX)));

        assertTrue(shader.contains("out vec4 v_TexCoord2;"), shader);
        assertTrue(shader.contains("out vec4 v_TexCoord3;"), shader);
        assertTrue(shader.contains("uniform vec4 u_CurrentTexCoord2;"), shader);
        assertTrue(shader.contains("uniform vec4 u_CurrentTexCoord3;"), shader);
        assertTrue(shader.contains("v_TexCoord2 = u_CurrentTexCoord2;"), shader);
        assertTrue(shader.contains("v_TexCoord3 = u_CurrentTexCoord3;"), shader);
    }

    @Test
    void unit23UvFromUnit0SourcesVaryingsFromUnit0Attribute() {
        final long packed = (1L << BIT_UNIT23_UV_FROM_UNIT0) | (1L << BIT_HAS_VERTEX_TEX) | (1L << BIT_UNIT2_TEX);
        String shader = VertexShaderGenerator.generate(VertexKey.fromPacked(packed));

        assertTrue(shader.contains("v_TexCoord2 = a_TexCoord0;"), shader);
        assertFalse(shader.contains("u_CurrentTexCoord2"), shader);
    }

    @Test
    void units2And3WithPerVertexTexcoordsUseDedicatedAttributes() {
        final long packed = (1L << BIT_UNIT2_TEX) | (1L << BIT_UNIT3_TEX)
            | (1L << BIT_HAS_VERTEX_TEX2) | (1L << BIT_HAS_VERTEX_TEX3);
        String shader = VertexShaderGenerator.generate(VertexKey.fromPacked(packed));

        // Each unit gets its own attribute slot (locations 5 and 6) instead of a per-draw constant.
        assertTrue(shader.contains("layout(location = 5) in vec4 a_TexCoord2;"), shader);
        assertTrue(shader.contains("layout(location = 6) in vec4 a_TexCoord3;"), shader);
        assertTrue(shader.contains("v_TexCoord2 = a_TexCoord2;"), shader);
        assertTrue(shader.contains("v_TexCoord3 = a_TexCoord3;"), shader);
        assertFalse(shader.contains("u_CurrentTexCoord2"), shader);
        assertFalse(shader.contains("u_CurrentTexCoord3"), shader);
    }

    @Test
    void unit2PerVertexTexCoordMultipliesTextureMatrix() {
        final long packed = (1L << BIT_UNIT2_TEX) | (1L << BIT_UNIT2_TEXMAT) | (1L << BIT_HAS_VERTEX_TEX2);
        String shader = VertexShaderGenerator.generate(VertexKey.fromPacked(packed));

        assertTrue(shader.contains("uniform mat4 u_TextureMatrix2;"), shader);
        assertTrue(shader.contains("v_TexCoord2 = u_TextureMatrix2 * a_TexCoord2;"), shader);
    }

    @Test
    void extendedUnitAttributesAreHomogeneousVec4() {
        // A 2-float UV attribute must still yield q == 1.0: declaring a vec4 and reading it directly
        // lets OpenGL fill the missing z/w with 0/1 (the fragment stage divides coord.st / q).
        final long packed = (1L << BIT_UNIT3_TEX) | (1L << BIT_HAS_VERTEX_TEX3);
        VertexKey key = VertexKey.fromPacked(packed);
        String shader = VertexShaderGenerator.generate(key);
        Transformer transformer = new Transformer(ShaderParser.parseShader(shader).full());
        Map<String, GLSLParser.Single_declarationContext> inputs = transformer.findQualifiers(GLSLLexer.IN);

        assertEquals("vec4", typeOf(inputs.get("a_TexCoord3")));
    }

    @Test
    void primaryTextureAttributeAcceptsCompleteHomogeneousCoordinates() {
        VertexKey key = VertexKey.fromPacked(1L << BIT_HAS_VERTEX_TEX);
        String shader = VertexShaderGenerator.generate(key);
        Transformer transformer = new Transformer(ShaderParser.parseShader(shader).full());
        Map<String, GLSLParser.Single_declarationContext> inputs = transformer.findQualifiers(GLSLLexer.IN);
        TexCoordAssignmentListener assignment = inspectTexCoordAssignment(transformer);

        assertEquals("vec4", typeOf(inputs.get("a_TexCoord0")));
        assertEquals(1, assignment.assignments);
        assertEquals("a_TexCoord0", assignment.source);

        StringBuilder formatted = new StringBuilder();
        transformer.mutateTree(tree -> formatted.append(
            GlslTransformUtils.getFormattedShader(tree, "#version 330 core\n")
        ));
        ShaderParser.parseShader(formatted.toString()).full();
    }

    private static TexCoordAssignmentListener inspectTexCoordAssignment(Transformer transformer) {
        TexCoordAssignmentListener listener = new TexCoordAssignmentListener();
        transformer.mutateTree(tree -> ParseTreeWalker.DEFAULT.walk(listener, tree));
        return listener;
    }

    private static String typeOf(GLSLParser.Single_declarationContext declaration) {
        return declaration.fully_specified_type().type_specifier().type_specifier_nonarray().getText();
    }

    private static final class TexCoordAssignmentListener extends GLSLParserBaseListener {
        private int assignments;
        private String source;

        @Override
        public void enterAssignment_expression(GLSLParser.Assignment_expressionContext context) {
            if (context.assignment_operator() == null || !"v_TexCoord0".equals(context.unary_expression().getText())) {
                return;
            }

            assignments++;
            source = context.assignment_expression().getText();
        }
    }

    @Test
    void eyeLinearTexGenFeedsEveryPlaneFromSingleConstructor() {
        final long packed = (1L << BIT_TEXTURE) | (1L << BIT_TEX_MATRIX)
            | ((long) VertexKey.TG_EYE_LINEAR << BIT_TEXGEN_S)
            | ((long) VertexKey.TG_EYE_LINEAR << BIT_TEXGEN_T)
            | ((long) VertexKey.TG_EYE_LINEAR << BIT_TEXGEN_R);
        final String shader = VertexShaderGenerator.generate(VertexKey.fromPacked(packed));
        final Transformer transformer = new Transformer(ShaderParser.parseShader(shader).full());
        final Map<String, GLSLParser.Single_declarationContext> uniforms = transformer.findQualifiers(GLSLLexer.UNIFORM);
        final TexGenInitListener texGen = inspectTexGenInit(transformer);

        assertEquals("vec4", typeOf(uniforms.get("u_TexGenEyePlaneS")));
        assertEquals("vec4", typeOf(uniforms.get("u_TexGenEyePlaneT")));
        assertEquals("vec4", typeOf(uniforms.get("u_TexGenEyePlaneR")));

        // Every eye plane must be consumed by the single vec4 constructor. The previous
        // per-component writes to a pre-initialized texGenCoord let NVIDIA's driver
        // dead-code-eliminate u_TexGenEyePlaneS, collapsing BPR's end-portal starfield
        // into stripes.
        assertEquals(0, texGen.componentWrites);
        assertEquals(1, texGen.initializers);
        assertTrue(texGen.initializer.startsWith("vec4("));
        assertTrue(texGen.initializer.contains("dot(eyePos,u_TexGenEyePlaneS)"));
        assertTrue(texGen.initializer.contains("dot(eyePos,u_TexGenEyePlaneT)"));
        assertTrue(texGen.initializer.contains("dot(eyePos,u_TexGenEyePlaneR)"));
    }

    private static TexGenInitListener inspectTexGenInit(Transformer transformer) {
        TexGenInitListener listener = new TexGenInitListener();
        transformer.mutateTree(tree -> ParseTreeWalker.DEFAULT.walk(listener, tree));
        return listener;
    }

    private static final class TexGenInitListener extends GLSLParserBaseListener {
        private int componentWrites;
        private int initializers;
        private String initializer = "";

        @Override
        public void enterAssignment_expression(GLSLParser.Assignment_expressionContext context) {
            if (context.assignment_operator() != null && context.unary_expression().getText().startsWith("texGenCoord.")) {
                componentWrites++;
            }
        }

        @Override
        public void enterSingle_declaration(GLSLParser.Single_declarationContext context) {
            final GLSLParser.Typeless_declarationContext decl = context.typeless_declaration();
            if (decl != null && decl.IDENTIFIER() != null && "texGenCoord".equals(decl.IDENTIFIER().getText())
                && decl.initializer() != null) {
                initializers++;
                initializer = decl.initializer().getText();
            }
        }
    }
}
