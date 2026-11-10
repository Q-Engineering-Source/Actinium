package net.coderbot.iris.pipeline;

import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import com.gtnewhorizons.angelica.glsm.ffp.Instancing;
import com.gtnewhorizons.angelica.glsm.ffp.WeatherInstancedAttribs;
import com.gtnewhorizons.angelica.glsm.ffp.WeatherParams;
import com.gtnewhorizons.angelica.glsm.ffp.WeatherQuadMesh;
import net.coderbot.iris.gbuffer_overrides.matching.InputAvailability;
import net.coderbot.iris.pipeline.transform.PatchShaderType;
import net.coderbot.iris.pipeline.transform.TransformPatcher;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds the Iris weather instanced variant against a real context.
 *
 * <p>The pack program is replaced by a minimal weather-like vertex and fragment pair, but everything
 * else is the production path: the same {@link TransformPatcher} entry the pipeline calls, real GLSL
 * compilation and linking, the same corner mesh and instance attributes the fixed-function variant
 * uses, and the same {@link WeatherParams} values. The frame is then read back, so a variant that
 * compiles but feeds the wrong inputs cannot pass.</p>
 */
class WeatherInstancedProgramGLTest extends GLStateManagerIntegrationFixture {

    private static final int SIZE = 64;
    private static final int BACKGROUND = 0xFF000000;
    private static final int STRIDE = WeatherInstancedAttribs.STRIDE;
    private static final int RAIN_ALPHA = 223;

    private static final String VERTEX = String.join("\n",
        "#version 330 core",
        "out vec4 texcoord;",
        "out vec4 vColor;",
        "void main() {",
        "    gl_Position = ftransform();",
        "    texcoord = gl_MultiTexCoord0;",
        "    vColor = gl_Color;",
        "}",
        "");

    private static final String FRAGMENT = String.join("\n",
        "#version 330 core",
        "uniform sampler2D gtexture;",
        "in vec4 texcoord;",
        "in vec4 vColor;",
        "out vec4 fragColor;",
        "void main() {",
        "    fragColor = texture(gtexture, texcoord.st) * vColor;",
        "}",
        "");

    private int instanceBuffer;
    private int texture;

    @BeforeAll
    static void initializeGlsm() {
        installDefaultGlsmConfig();
        routeVertexFormats(true);
        // The transform negotiates against the cached GLSL capability, which the game fills from the
        // live context; the fixture only creates the context.
        RenderSystem.initializeGlslCapabilityForTesting(330);
    }

    @BeforeEach
    void setUp() {
        WindowBackends.useSdl();
        GLStateManager.bindDisplayContext(new Object(), context);
        GLStateManager.recreateDefaultVertexArray();
        GLSMRasterizer.glViewport(0, 0, SIZE, SIZE);
        GLSMEnable.glDisable(GL11.GL_DEPTH_TEST);
        GLSMEnable.glDisable(GL11.GL_BLEND);
        GLSMEnable.glDisable(GL11.GL_CULL_FACE);
        GLSMEnable.glDisable(GL11.GL_LIGHTING);
        texture = whiteTexture();
        GLSMEnable.glEnable(GL11.GL_TEXTURE_2D);
        instanceBuffer = GLSMBuffers.glGenBuffers();
    }

    @AfterEach
    void tearDown() {
        WeatherQuadMesh.delete();
        if (instanceBuffer != 0) { GLSMBuffers.glDeleteBuffers(instanceBuffer); instanceBuffer = 0; }
        if (texture != 0) { GL11.glDeleteTextures(texture); texture = 0; }
        GLSMEnable.glDisable(GL11.GL_TEXTURE_2D);
        GLSMVertexInput.glBindVertexArray(0);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        while (GL11.glGetError() != GL11.GL_NO_ERROR) { }
    }

    @Test
    void weatherVariantTransformsCompilesAndDrawsTheColumn() {
        final Map<PatchShaderType, String> transformed = TransformPatcher.patchAttributes(
            VERTEX, null, FRAGMENT, new InputAvailability(true, true), Instancing.WEATHER);
        final Map<PatchShaderType, String> plain = TransformPatcher.patchAttributes(
            VERTEX, null, FRAGMENT, new InputAvailability(true, true));

        assertNotEquals(plain.get(PatchShaderType.VERTEX), transformed.get(PatchShaderType.VERTEX),
            "the instanced stage is not the plain one");
        assertEquals(plain.get(PatchShaderType.FRAGMENT), transformed.get(PatchShaderType.FRAGMENT),
            "only the vertex stage changes");

        final int program = link(transformed.get(PatchShaderType.VERTEX), transformed.get(PatchShaderType.FRAGMENT));
        GL20.glUseProgram(program);
        try {
            uploadMatrices(program);
            setWeatherParams();
            uploadWeatherParams(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "gtexture"), 0);

            final int[] pixels = drawColumn();
            final int centreAlpha = pixels[32 * SIZE + 48] >>> 24 & 0xFF;

            assertTrue(centreAlpha == RAIN_ALPHA, () -> "centre alpha " + centreAlpha
                + " (expected " + RAIN_ALPHA + ")"
                + "\n  params0=" + GL20.glGetUniformLocation(program, "iris_WeatherParams0")
                + " params1=" + GL20.glGetUniformLocation(program, "iris_WeatherParams1")
                + " params2=" + GL20.glGetUniformLocation(program, "iris_WeatherParams2")
                + " mv=" + GL20.glGetUniformLocation(program, "iris_ModelViewMatrix")
                + " corner=" + GL20.glGetAttribLocation(program, "iris_WeatherCorner")
                + " span=" + GL20.glGetAttribLocation(program, "iris_InstColumnSpan")
                + "\n--- transformed vertex ---\n" + transformed.get(PatchShaderType.VERTEX));
            assertEquals(255, pixels[32 * SIZE + 48] & 0xFF, "the texture is sampled");
            assertEquals(BACKGROUND, pixels[8 * SIZE + 8], "outside the column");
            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
        } finally {
            GL20.glUseProgram(0);
            GL20.glDeleteProgram(program);
        }
    }

    @Test
    void plainProgramCannotExpandTheCornerMesh() {
        // The plain transform feeds per-vertex attributes, so drawing the corner mesh through it can
        // not produce the column. This pins the variant being a distinct program rather than the
        // ordinary weather program with different bindings.
        final Map<PatchShaderType, String> plain = TransformPatcher.patchAttributes(
            VERTEX, null, FRAGMENT, new InputAvailability(true, true));
        final int program = link(plain.get(PatchShaderType.VERTEX), plain.get(PatchShaderType.FRAGMENT));
        GL20.glUseProgram(program);
        try {
            uploadMatrices(program);
            setWeatherParams();
            uploadWeatherParams(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "gtexture"), 0);

            final int[] pixels = drawColumn();

            for (int pixel : pixels) {
                assertEquals(BACKGROUND, pixel, "the plain program has no weather inputs to expand");
            }
        } finally {
            GL20.glUseProgram(0);
            GL20.glDeleteProgram(program);
        }
    }

    private int[] drawColumn() {
        final ByteBuffer data = BufferUtils.createByteBuffer(STRIDE);
        data.putFloat(0.0F).putFloat(-0.5F).putFloat(-0.5F).putFloat(0.5F);
        data.putFloat(0.5F).putFloat(0.0F).putFloat(0.5F).putFloat(0.0F);
        data.putFloat(240.0F).putFloat(240.0F).putFloat(0.0F).putFloat(0.0F);
        data.flip();
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceBuffer);
        GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        WeatherQuadMesh.bindInstanceBuffer(instanceBuffer);

        GLStateManager.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
        GLSMFramebuffer.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GLSMVertexInput.glBindVertexArray(WeatherQuadMesh.vao());
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceBuffer);
        WeatherInstancedAttribs.pointInstanceAttribs(0L);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GLSMDraws.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, WeatherQuadMesh.VERTEX_COUNT, 1);
        GLSMVertexInput.glBindVertexArray(0);

        final ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        GLSMFramebuffer.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        final int[] out = new int[SIZE * SIZE];
        for (int i = 0; i < out.length; i++) out[i] = pixels.getInt(i * 4);
        return out;
    }

    private static void uploadMatrices(int program) {
        final float[] identity = new float[16];
        new Matrix4f().identity().get(identity);
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "iris_ModelViewMatrix"), false, identity);
        final float[] ortho = new float[16];
        new Matrix4f().setOrtho(-1.0F, 1.0F, -1.0F, 1.0F, -1.0F, 1.0F).get(ortho);
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "iris_ProjectionMatrix"), false, ortho);
    }

    private static void setWeatherParams() {
        WeatherParams.set(0.0F, 0.0F, 0.0F, 1.0F, 0.5F, 0.5F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F);
    }

    private static void uploadWeatherParams(int program) {
        GL20.glUniform4f(GL20.glGetUniformLocation(program, "iris_WeatherParams0"),
            WeatherParams.translateX, WeatherParams.translateY, WeatherParams.translateZ, WeatherParams.invRadius);
        GL20.glUniform4f(GL20.glGetUniformLocation(program, "iris_WeatherParams1"),
            WeatherParams.cameraFracX, WeatherParams.cameraFracZ, WeatherParams.partialTicks, WeatherParams.age);
        GL20.glUniform4f(GL20.glGetUniformLocation(program, "iris_WeatherParams2"),
            WeatherParams.rainScroll, WeatherParams.snowScroll, WeatherParams.rainStrength, 0.0F);
    }

    private static int link(String vertex, String fragment) {
        final int vs = compile(GL20.GL_VERTEX_SHADER, vertex);
        final int fs = compile(GL20.GL_FRAGMENT_SHADER, fragment);
        final int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vs);
        GL20.glAttachShader(program, fs);
        GL20.glLinkProgram(program);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
            final String log = GL20.glGetProgramInfoLog(program);
            GL20.glDeleteProgram(program);
            throw new IllegalStateException("weather program link failed: " + log + "\n--- vertex ---\n" + vertex);
        }
        GL20.glDetachShader(program, vs);
        GL20.glDetachShader(program, fs);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        return program;
    }

    private static int compile(int type, String source) {
        final int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
            final String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("weather shader compile failed: " + log + "\n" + source);
        }
        return shader;
    }

    private static int whiteTexture() {
        final int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        final ByteBuffer white = BufferUtils.createByteBuffer(4);
        white.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255).flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, white);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        return id;
    }
}
