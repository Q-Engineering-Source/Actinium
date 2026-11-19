package net.coderbot.iris.pipeline;

import com.gtnewhorizons.angelica.glsm.GLSMBlend;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import com.gtnewhorizons.angelica.glsm.ffp.InstancedAttribs;
import com.gtnewhorizons.angelica.glsm.ffp.Instancing;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import net.coderbot.batchedentityrendering.impl.AngelicaBufferSource;
import net.coderbot.iris.gbuffer_overrides.matching.InputAvailability;
import net.coderbot.iris.gl.shader.GlShader;
import net.coderbot.iris.gl.shader.ProgramCreator;
import net.coderbot.iris.gl.shader.ShaderType;
import net.coderbot.iris.pipeline.transform.PatchShaderType;
import net.coderbot.iris.pipeline.transform.ShaderTransformer;
import net.coderbot.iris.pipeline.transform.TransformPatcher;
import net.coderbot.iris.uniforms.CapturedRenderingState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiles and links the instanced attribute variants of a pack program against a real context, so the
 * declarations the transformer writes are checked by the driver and not only by the text of the source.
 */
class IrisInstancingGLTest extends GLStateManagerIntegrationFixture {

    private static final int SIZE = 8;
    private static final InputAvailability TEX_LM = InputAvailability.of(true, true);

    private static final String CUBE_VERTEX = """
        #version 120
        attribute vec4 mc_Entity;
        attribute vec4 at_tangent;
        attribute vec4 mc_midTexCoord;
        varying vec2 texcoord;
        varying vec2 lmcoord;
        varying vec4 color;
        varying float mats;
        varying vec3 nrm;
        varying vec3 tang;
        varying vec2 midtex;
        uniform int entityId;
        uniform int blockEntityId;
        uniform vec4 entityColor;
        void main() {
            texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
            lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
            color = gl_Color + entityColor + vec4(float(entityId + blockEntityId));
            mats = mc_Entity.x;
            nrm = normalize(gl_NormalMatrix * gl_Normal);
            tang = normalize(gl_NormalMatrix * at_tangent.xyz) * at_tangent.w;
            midtex = mc_midTexCoord.st;
            gl_Position = ftransform();
        }
        """;

    private static final String CUBE_FRAGMENT = """
        #version 120
        varying vec2 texcoord;
        varying vec4 color;
        varying vec2 lmcoord;
        varying float mats;
        varying vec3 nrm;
        varying vec3 tang;
        varying vec2 midtex;
        uniform sampler2D tex;
        uniform int entityId;
        uniform vec4 entityColor;
        void main() {
            gl_FragData[0] = texture2D(tex, texcoord) * (color + vec4(mats, lmcoord, nrm.x)) * entityColor
                + vec4(tang, midtex) + vec4(float(entityId));
        }
        """;

    private static final String TEMPLATE_VERTEX = """
        #version 120
        uniform int entityId;
        varying float mats;
        void main() {
            mats = float(entityId);
            gl_Position = ftransform();
        }
        """;

    private static final String TEMPLATE_GEOMETRY = """
        #version 150
        layout(triangles) in;
        layout(triangle_strip, max_vertices = 3) out;
        void main() {
            for (int i = 0; i < 3; i++) {
                gl_Position = gl_in[i].gl_Position;
                EmitVertex();
            }
            EndPrimitive();
        }
        """;

    private static final String TEMPLATE_FRAGMENT = """
        #version 120
        uniform int entityId;
        uniform vec4 entityColor;
        void main() {
            gl_FragData[0] = entityColor + vec4(float(entityId));
        }
        """;

    private static final String TINT_VERTEX = """
        #version 120
        void main() {
            gl_Position = vec4(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0, 0.0, 1.0);
        }
        """;

    private static final String TINT_FRAGMENT = """
        #version 120
        uniform vec4 entityColor;
        void main() {
            gl_FragData[0] = vec4(mix(vec3(0, 0, 1), entityColor.rgb, entityColor.a), 1);
        }
        """;

    private static final String ID_FRAGMENT = """
        #version 120
        uniform int entityId, blockEntityId;
        uniform int currentRenderedItemId;
        uniform int expectedId;
        // a parameter and a local sharing the id names keep their own values
        bool isFortyTwo(int entityId) { return entityId == 42; }
        void main() {
            bool matches = entityId == expectedId && blockEntityId == expectedId && currentRenderedItemId == expectedId;
            bool shadowed = isFortyTwo(42);
            { int blockEntityId = 7; shadowed = shadowed && blockEntityId == 7; }
            gl_FragData[0] = matches && shadowed ? vec4(0, 1, 0, 1) : vec4(1, 0, 0, 1);
        }
        """;

    private static final String TES_VERTEX = """
        #version 400
        uniform int entityId;
        uniform vec4 entityColor;
        out vec4 tint;
        void main() {
            tint = entityColor + vec4(float(entityId));
            gl_Position = ftransform();
        }
        """;

    private static final String TES_CONTROL = """
        #version 400
        layout(vertices = 3) out;
        in vec4 tint[];
        out vec4 tintTCS[];
        void main() {
            gl_TessLevelOuter[0] = 1.0;
            gl_TessLevelOuter[1] = 1.0;
            gl_TessLevelOuter[2] = 1.0;
            gl_TessLevelInner[0] = 1.0;
            tintTCS[gl_InvocationID] = tint[gl_InvocationID];
            gl_out[gl_InvocationID].gl_Position = gl_in[gl_InvocationID].gl_Position;
        }
        """;

    private static final String TES_EVAL = """
        #version 400
        layout(triangles) in;
        in vec4 tintTCS[];
        out vec4 tintTES;
        void main() {
            gl_Position = gl_in[0].gl_Position;
            tintTES = tintTCS[0];
        }
        """;

    private static final String TES_FRAGMENT = """
        #version 400
        uniform int entityId;
        uniform vec4 entityColor;
        in vec4 tintTES;
        void main() {
            gl_FragData[0] = tintTES + entityColor + vec4(float(entityId));
        }
        """;

    private static int previousMaxGlslVersion;

    private int vao;
    private int buffer;
    private final ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);

    @BeforeAll
    static void pinGlslVersion() {
        installDefaultGlsmConfig();
        previousMaxGlslVersion = RenderSystem.getMaxGlslVersion();
        RenderSystem.initializeGlslCapabilityForTesting(330);
        ShaderTransformer.init();
    }

    @AfterAll
    static void restoreGlslVersion() {
        RenderSystem.initializeGlslCapabilityForTesting(previousMaxGlslVersion);
        TransformPatcher.clearCache();
        ShaderTransformer.init();
    }

    @BeforeEach
    void setUp() {
        TransformPatcher.clearCache();
        WindowBackends.useSdl();
        GLStateManager.bindDisplayContext(new Object(), context);
        GLStateManager.recreateDefaultVertexArray();
        ShaderManager.disable();
        GLSMDepthStencilAlpha.disableDepthTest();
        GLSMBlend.disableBlend();
        GLSMRasterizer.disableCull();
        GLSMBlend.glColorMask(true, true, true, true);
        GLSMRasterizer.glViewport(0, 0, SIZE, SIZE);
        vao = GLSMVertexInput.glGenVertexArrays();
        GLSMVertexInput.glBindVertexArray(vao);
        buffer = GLSMBuffers.glGenBuffers();
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
    }

    @AfterEach
    void tearDown() {
        GLSMPrograms.glUseProgram(0);
        if (buffer != 0) GLSMBuffers.glDeleteBuffers(buffer);
        GLSMVertexInput.glBindVertexArray(0);
        if (vao != 0) GLSMVertexInput.glDeleteVertexArrays(vao);
        GLSMRasterizer.glViewport(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT);
        while (GL11.glGetError() != GL11.GL_NO_ERROR) { }
    }

    @Test
    void cubeVariantCompiles() {
        final Map<PatchShaderType, String> result = TransformPatcher.patchAttributesInstanced(
            CUBE_VERTEX, null, null, null, CUBE_FRAGMENT, TEX_LM, Instancing.CUBE);
        assertNotNull(result);
        final String vertex = result.get(PatchShaderType.VERTEX);
        final String fragment = result.get(PatchShaderType.FRAGMENT);
        assertNotNull(vertex);
        assertNotNull(fragment);
        assertTrue(vertex.startsWith("#version 330"), () -> "expected a 3.3 core vertex shader:\n" + vertex);
        compile(GL20.GL_VERTEX_SHADER, vertex);
        compile(GL20.GL_FRAGMENT_SHADER, fragment);
    }

    private static final String PARTICLE_VERTEX = """
        #version 120
        varying vec2 texcoord;
        varying vec2 lmcoord;
        varying vec4 color;
        varying vec3 nrm;
        uniform int entityId;
        void main() {
            texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
            lmcoord = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
            color = gl_Color;
            nrm = normalize(gl_NormalMatrix * gl_Normal);
            gl_Position = ftransform();
        }
        """;

    private static final String PARTICLE_FRAGMENT = """
        #version 120
        varying vec2 texcoord;
        varying vec2 lmcoord;
        varying vec4 color;
        varying vec3 nrm;
        uniform sampler2D tex;
        void main() {
            gl_FragData[0] = texture2D(tex, texcoord) * color + vec4(lmcoord, nrm.x, 1.0);
        }
        """;

    @Test
    void particleVariantCompilesAndLinks() {
        final Map<PatchShaderType, String> result = TransformPatcher.patchAttributesInstanced(
            PARTICLE_VERTEX, null, null, null, PARTICLE_FRAGMENT, TEX_LM, Instancing.PARTICLE);
        assertNotNull(result);
        final String vertex = result.get(PatchShaderType.VERTEX);
        assertNotNull(vertex);
        assertTrue(vertex.startsWith("#version 330"), () -> "expected a 3.3 core vertex shader: " + vertex);
        // The quad carries only the corner offset and its share of the texture rectangle; everything
        // else is rebuilt from the instance.
        assertTrue(vertex.contains("iris_ParticleOffset"), vertex);
        assertTrue(vertex.contains("iris_ParticleCorner"), vertex);
        assertTrue(vertex.contains("iris_InstCenterHalf"), vertex);
        assertTrue(vertex.contains("iris_InstUv"), vertex);
        assertTrue(vertex.contains("iris_InstColor"), vertex);
        assertTrue(vertex.contains("iris_InstLightmap"), vertex);
        // The printer spaces the expression out, so only the declaration shape is pinned here.
        assertTrue(vertex.contains("const vec3 iris_Normal"), vertex);
        assertTrue(vertex.contains("iris_Normal = vec3 ( 0.0 , 0.0 , 1.0 )"), vertex);
        assertFalse(vertex.contains("in vec3 iris_Normal"), "location 4 is not part of the particle quad");
        linkAndDiscard("particleVariant", result);
    }

    @Test
    void templateVariantCompilesAndLinksWithGeometryStage() {
        final Map<PatchShaderType, String> result = TransformPatcher.patchAttributesInstanced(
            TEMPLATE_VERTEX, TEMPLATE_GEOMETRY, null, null, TEMPLATE_FRAGMENT, TEX_LM, Instancing.TEMPLATE);
        assertNotNull(result);
        assertNotNull(result.get(PatchShaderType.GEOMETRY));
        linkAndDiscard("templateVariant", result);
    }

    @Test
    void highEntityBlockAndItemIdsReachTheFragmentShader() {
        final int[] ids = { -1, 0, 32767, 32768, 45020, 50020, 50072, 65534 };
        final ByteBuffer data = BufferUtils.createByteBuffer(InstancedAttribs.HEAD_SIZE);
        final int program = linkProgram("highIds", TransformPatcher.patchAttributesInstanced(
            TINT_VERTEX, null, null, null, ID_FRAGMENT, TEX_LM, Instancing.TEMPLATE));
        try {
            GLSMPrograms.glUseProgram(program);
            InstancedAttribs.pointHead(0, InstancedAttribs.HEAD_SIZE);
            GLSMVertexInput.glEnableVertexAttribArray(InstancedAttribs.LOC_ENTITY);
            GLSMVertexInput.glVertexAttribDivisor(InstancedAttribs.LOC_ENTITY, 1);
            for (int id : ids) {
                data.putLong(InstancedAttribs.OFFSET_ENTITY, InstancedAttribs.packEntityInfo(id, id, id));
                GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STREAM_DRAW);
                GLSMPrograms.glUniform1i(GLSMPrograms.glGetUniformLocation(program, "expectedId"), id);
                clear();
                GLSMDraws.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, 3, 1);
                assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "draw error for id " + id);
                final int[] pixel = readPixel();
                assertEquals(255, pixel[1], "material id " + id + " pixel " + Arrays.toString(pixel));
            }
        } finally {
            GLSMPrograms.glUseProgram(0);
            GLSMPrograms.glDeleteProgram(program);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Instancing.class, names = { "NONE", "TEMPLATE", "CUBE" })
    void freshEntityTintPreservesColorBeforeAndAfterAHurtScope(Instancing kind) {
        final CapturedRenderingState state = CapturedRenderingState.INSTANCE;
        final Map<PatchShaderType, String> transformed = kind == Instancing.NONE
            ? TransformPatcher.patchAttributes(TINT_VERTEX, null, null, null, TINT_FRAGMENT, TEX_LM)
            : TransformPatcher.patchAttributesInstanced(TINT_VERTEX, null, null, null, TINT_FRAGMENT, TEX_LM, kind);
        final int program = linkProgram("entityTint", transformed);
        final ByteBuffer data = BufferUtils.createByteBuffer(InstancedAttribs.HEAD_SIZE);

        try {
            GLSMPrograms.glUseProgram(program);
            final int entityColorLocation = GLSMPrograms.glGetUniformLocation(program, "entityColor");
            InstancedAttribs.pointHead(0, InstancedAttribs.HEAD_SIZE);
            GLSMVertexInput.glEnableVertexAttribArray(InstancedAttribs.LOC_OVERLAY);
            GLSMVertexInput.glVertexAttribDivisor(InstancedAttribs.LOC_OVERLAY, 1);

            state.pushCurrentEntityColor();
            try {
                state.setCurrentEntityColor(0, 0, 0, 0);
                assertTint(kind, state, entityColorLocation, data, new int[] { 0, 0, 255 });

                state.pushCurrentEntityColor();
                try {
                    state.setCurrentEntityColor(1, 0, 0, 0.5f);
                    assertTint(kind, state, entityColorLocation, data, new int[] { 128, 0, 127 });
                } finally {
                    state.popCurrentEntityColor();
                }

                assertTint(kind, state, entityColorLocation, data, new int[] { 0, 0, 255 });
            } finally {
                state.popCurrentEntityColor();
            }
        } finally {
            GLSMPrograms.glUseProgram(0);
            GLSMPrograms.glDeleteProgram(program);
        }
    }

    /**
     * Both the overlay attribute the instance head carries and the per-draw uniform are written from
     * the current entity color, so the same expectation holds for every layout.
     */
    private void assertTint(Instancing kind, CapturedRenderingState state, int entityColorLocation, ByteBuffer data, int[] expected) {
        final Vector4f color = state.getCurrentEntityColor();
        if (entityColorLocation >= 0) {
            GLSMPrograms.glUniform4f(entityColorLocation, color.x, color.y, color.z, color.w);
        }
        data.putInt(InstancedAttribs.OFFSET_OVERLAY, AngelicaBufferSource.packEntityColor(color));
        GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STREAM_DRAW);

        clear();
        if (kind == Instancing.NONE) {
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        } else {
            GLSMDraws.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, 3, 1);
        }
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "draw error");

        final int[] pixel = readPixel();
        for (int channel = 0; channel < 3; channel++) {
            assertEquals(expected[channel], pixel[channel], 2, kind + " tint pixel " + Arrays.toString(pixel));
        }
    }

    private void clear() {
        GLSMBlend.glClearColor(0, 0, 0, 1);
        GLSMFramebuffer.glClear(GL11.GL_COLOR_BUFFER_BIT);
    }

    private int[] readPixel() {
        pixels.clear();
        GLSMFramebuffer.glReadPixels(SIZE / 2, SIZE / 2, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        return new int[] { pixels.get(0) & 0xFF, pixels.get(1) & 0xFF, pixels.get(2) & 0xFF, pixels.get(3) & 0xFF };
    }

    @Test
    void tessellationVariantLinksTheEntityVaryingsWithoutAGeometryStage() {
        assumeTrue(GL.getCapabilities().OpenGL40, "tessellation stages need a GL 4.0 context");
        final int previous = RenderSystem.getMaxGlslVersion();
        RenderSystem.initializeGlslCapabilityForTesting(460);
        ShaderTransformer.init();
        TransformPatcher.clearCache();
        try {
            final Map<PatchShaderType, String> result = TransformPatcher.patchAttributesInstanced(
                TES_VERTEX, null, TES_CONTROL, TES_EVAL, TES_FRAGMENT, TEX_LM, Instancing.TEMPLATE);
            assertNotNull(result);
            assertNotNull(result.get(PatchShaderType.TESS_CONTROL));
            assertNotNull(result.get(PatchShaderType.TESS_EVAL));
            linkAndDiscard("templateTess", result);
        } finally {
            RenderSystem.initializeGlslCapabilityForTesting(previous);
            TransformPatcher.clearCache();
            ShaderTransformer.init();
        }
    }

    private static void linkAndDiscard(String name, Map<PatchShaderType, String> result) {
        GLSMPrograms.glDeleteProgram(linkProgram(name, result));
    }

    private static int linkProgram(String name, Map<PatchShaderType, String> result) {
        final String vertex = result.get(PatchShaderType.VERTEX);
        final String geometry = result.get(PatchShaderType.GEOMETRY);
        final String tessControl = result.get(PatchShaderType.TESS_CONTROL);
        final String tessEval = result.get(PatchShaderType.TESS_EVAL);
        final String fragment = result.get(PatchShaderType.FRAGMENT);
        assertNotNull(vertex);
        assertNotNull(fragment);

        final List<GlShader> shaders = new ArrayList<>(4);
        try {
            shaders.add(new GlShader(ShaderType.VERTEX, name + "Vertex", vertex));
            if (tessControl != null) {
                shaders.add(new GlShader(ShaderType.TESSELATION_CONTROL, name + "TessControl", tessControl));
            }
            if (tessEval != null) {
                shaders.add(new GlShader(ShaderType.TESSELATION_EVAL, name + "TessEval", tessEval));
            }
            if (geometry != null) {
                shaders.add(new GlShader(ShaderType.GEOMETRY, name + "Geometry", geometry));
            }
            shaders.add(new GlShader(ShaderType.FRAGMENT, name + "Fragment", fragment));
            return ProgramCreator.create(name, shaders.toArray(new GlShader[0]));
        } finally {
            for (GlShader shader : shaders) {
                shader.destroy();
            }
        }
    }

    private static void compile(int type, String source) {
        final int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        final boolean compiled = GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_TRUE;
        final String log = compiled ? "" : GL20.glGetShaderInfoLog(shader);
        GLSMPrograms.glDeleteShader(shader);
        if (!compiled) {
            throw new IllegalStateException(log);
        }
    }
}
