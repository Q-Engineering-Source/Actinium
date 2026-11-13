package com.gtnewhorizons.angelica.glsm.ffp;

import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.hooks.ImmediateExtendedAttribHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memGetFloat;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memPutFloat;
import static com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities.memPutInt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Draws textured geometry out of a VBO through the client-array entry points against a real context and checks the
 * rebuilt {@code mc_midTexCoord}/{@code at_tangent} attributes: the values come from the source range, reach the
 * shader through the draw call, and stop being rebuilt once the source buffer is deleted, rewritten or respecified
 * every frame.
 */
class FfpExtendedAttribsDrawPathGLTest extends GLStateManagerIntegrationFixture {

    private static final int SIZE = 32;
    private static final int STRIDE = 20;

    private final RecordingHandler handler = new RecordingHandler();

    private int vao;
    private int vbo;
    private int ebo;
    private int program;

    @BeforeAll
    static void initializeGlsm() {
        installDefaultGlsmConfig();
    }

    @BeforeEach
    void setUp() {
        WindowBackends.useSdl();
        GLStateManager.bindDisplayContext(new Object(), context);
        GLStateManager.recreateDefaultVertexArray();
        ShaderManager.disable();
        GLSMEnable.glDisable(GL11.GL_DEPTH_TEST);
        GLSMEnable.glDisable(GL11.GL_BLEND);
        GLSMEnable.glDisable(GL11.GL_CULL_FACE);
        GL11.glViewport(0, 0, SIZE, SIZE);
        FfpExtendedAttribs.reset();
        GLSMHooks.immediateExtendedHandler = handler;
        program = linkProgram();
        GLSMPrograms.glUseProgram(program);
        vao = GLSMVertexInput.glGenVertexArrays();
        GLSMVertexInput.glBindVertexArray(vao);
    }

    @AfterEach
    void tearDown() {
        GLSMPrograms.glUseProgram(0);
        if (program != 0) GL20.glDeleteProgram(program);
        if (vbo != 0) GLSMBuffers.glDeleteBuffers(vbo);
        if (ebo != 0) GLSMBuffers.glDeleteBuffers(ebo);
        GLSMVertexInput.glBindVertexArray(0);
        if (vao != 0) GLSMVertexInput.glDeleteVertexArrays(vao);
        FfpExtendedAttribs.reset();
        GLSMHooks.immediateExtendedHandler = null;
        GLStateManager.getVaoManager().setVertexFlags(0);
        GLStateManager.glViewport(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT);
        while (GL11.glGetError() != GL11.GL_NO_ERROR) { }
    }

    @Test
    void drawArraysRebuildsTheAttributesFromTheSourceBuffer() {
        vbo = uploadVertices(triangleData());
        pointTexturedArray();

        clear();
        GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

        assertEquals(1, handler.builds, "the textured VBO must take the rebuild path");
        assertEquals(3, handler.lastVertsPerPrim, "triangles rebuild one value set per triangle");
        final int centre = centrePixel();
        assertByte(unorm(0.25F), centre & 0xFF, "mid U comes from the source texture coordinates");
        assertByte(unorm(0.25F), centre >> 8 & 0xFF, "mid V comes from the source texture coordinates");
        assertByte(255, centre >> 16 & 0xFF, "the tangent faces along the source positions' face normal");
    }

    @Test
    void quadArraysRebuildPerPrimitiveValues() {
        vbo = uploadVertices(quadData());
        pointTexturedArray();

        clear();
        GLSMDraws.glDrawArrays(GL11.GL_QUADS, 0, 4);

        assertEquals(1, handler.builds, "the quad draw must take the rebuild path");
        assertEquals(4, handler.lastVertsPerPrim, "quads rebuild one value set per quad");
        final int centre = centrePixel();
        assertByte(unorm(0.5F), centre & 0xFF, "the mid U is the centre of the quad's source coordinates");
        assertByte(unorm(0.5F), centre >> 8 & 0xFF, "the mid V is the centre of the quad's source coordinates");
        assertByte(255, centre >> 16 & 0xFF, "the tangent faces along the source positions' face normal");
    }

    @Test
    void indexedQuadElementsRebuildOneValueSetPerQuad() {
        vbo = uploadVertices(twoQuadData());
        pointTexturedArray();
        ebo = uploadQuadsIndices(2);

        clear();
        GLSMDraws.glDrawElements(GL11.GL_TRIANGLES, 12, GL11.GL_UNSIGNED_SHORT, 0L);

        assertEquals(1, handler.builds, "a quad-pattern element buffer must take the rebuild path");
        assertEquals(4, handler.lastVertsPerPrim, "the element buffer describes quads, so values stay per quad");
        assertEquals(8, handler.lastVertexCount, "two indexed quads rebuild eight vertices");
        assertEquals(0.25F, handler.midU(0), 1.0e-6F, "the first indexed quad keeps its own mid U");
        assertEquals(0.75F, handler.midV(0), 1.0e-6F, "the first indexed quad keeps its own mid V");
        assertEquals(0.75F, handler.midU(4), 1.0e-6F, "the second indexed quad keeps its own mid U");
        assertTrue((centrePixel() & 0xFFFFFF) != 0, "the rebuilt draw reaches the framebuffer");
    }

    @Test
    void respecifyingTheSourceBufferInvalidatesTheRebuiltRange() {
        vbo = uploadVertices(triangleData());
        pointTexturedArray();

        GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        assertEquals(1, handler.builds, "a second draw over unchanged source data reuses the rebuilt range");

        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GLSMBuffers.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, triangleData());
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        assertEquals(2, handler.builds, "the rewritten source buffer must be rebuilt");
        GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        assertEquals(2, handler.builds, "the rebuilt range stays cached for the next draw");
    }

    @Test
    void deletingTheSourceBufferInvalidatesTheRebuiltRange() {
        vbo = uploadVertices(triangleData());
        pointTexturedArray();
        GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        assertEquals(1, handler.builds, "the first draw builds the attributes");

        GLSMBuffers.glDeleteBuffers(vbo);
        vbo = uploadVertices(triangleData());
        pointTexturedArray();

        GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        assertEquals(2, handler.builds, "data derived from the deleted buffer cannot serve the new draw");
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "dropping the deleted source keeps the draw error free");
    }

    @Test
    void buffersRespecifiedEveryFrameStopBeingRebuilt() {
        vbo = uploadVertices(triangleData());
        pointTexturedArray();

        for (int frame = 0; frame < 8; frame++) {
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GLSMBuffers.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, triangleData());
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            FfpExtendedAttribs.endFrame();
        }
        assertEquals(8, handler.builds, "each frame's rewrite invalidates the range the previous frame built");

        GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        assertEquals(8, handler.builds, "a per-frame rewrite is a streaming source and stops being rebuilt");
    }

    @Test
    void geometryWithoutTextureCoordinatesIsLeftAlone() {
        vbo = uploadVertices(triangleData());
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GLSMVertexInput.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GLSMVertexInput.glVertexPointer(3, GL11.GL_FLOAT, STRIDE, 0L);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        assertFalse(FfpExtendedAttribs.maybeBind(GLStateManager.ctx(), GL11.GL_TRIANGLES, 0, 3),
            "a position-only array has no source texture coordinates to rebuild from");
        assertEquals(0, handler.builds);
    }

    @Test
    void partialPrimitivesAreLeftAlone() {
        vbo = uploadVertices(triangleData());
        pointTexturedArray();

        assertFalse(FfpExtendedAttribs.maybeBind(GLStateManager.ctx(), GL11.GL_TRIANGLES, 0, 2),
            "a vertex count that does not fill a primitive has no per-primitive values");
        assertFalse(FfpExtendedAttribs.maybeBind(GLStateManager.ctx(), GL11.GL_LINES, 0, 2),
            "a draw mode without per-primitive values is not rebuilt");
        assertEquals(0, handler.builds);
    }

    private void pointTexturedArray() {
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GLSMVertexInput.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GLSMVertexInput.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GLSMVertexInput.glVertexPointer(3, GL11.GL_FLOAT, STRIDE, 0L);
        GLSMVertexInput.glTexCoordPointer(2, GL11.GL_FLOAT, STRIDE, 12L);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    private int uploadVertices(ByteBuffer data) {
        final int buffer = GLSMBuffers.glGenBuffers();
        final int previous = GLSMBuffers.getBoundVBO();
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
        return buffer;
    }

    private int uploadQuadsIndices(int quadCount) {
        final ShortBuffer indices = BufferUtils.createShortBuffer(quadCount * 6);
        for (int quad = 0; quad < quadCount; quad++) {
            final short base = (short) (quad * 4);
            indices.put(base).put((short) (base + 1)).put((short) (base + 2))
                .put((short) (base + 2)).put((short) (base + 3)).put(base);
        }
        indices.flip();
        final int buffer = GLSMBuffers.glGenBuffers();
        GLSMBuffers.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, buffer);
        GLSMBuffers.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
        return buffer;
    }

    private static ByteBuffer triangleData() {
        return vertices(
            -1.0F, -1.0F, 0.0F, 0.0F, 0.0F,
            3.0F, -1.0F, 0.0F, 0.5F, 0.0F,
            -1.0F, 3.0F, 0.0F, 0.0F, 0.5F);
    }

    private static ByteBuffer quadData() {
        return vertices(
            -1.0F, -1.0F, 0.0F, 0.0F, 0.0F,
            1.0F, -1.0F, 0.0F, 1.0F, 0.0F,
            1.0F, 1.0F, 0.0F, 1.0F, 1.0F,
            -1.0F, 1.0F, 0.0F, 0.0F, 1.0F);
    }

    private static ByteBuffer twoQuadData() {
        return vertices(
            -1.0F, -1.0F, 0.0F, 0.0F, 0.5F,
            0.0F, -1.0F, 0.0F, 0.5F, 0.5F,
            0.0F, 1.0F, 0.0F, 0.5F, 1.0F,
            -1.0F, 1.0F, 0.0F, 0.0F, 1.0F,
            0.0F, -1.0F, 0.0F, 0.5F, 0.5F,
            1.0F, -1.0F, 0.0F, 1.0F, 0.5F,
            1.0F, 1.0F, 0.0F, 1.0F, 1.0F,
            0.0F, 1.0F, 0.0F, 0.5F, 1.0F);
    }

    private static ByteBuffer vertices(float... values) {
        final ByteBuffer data = BufferUtils.createByteBuffer(values.length * Float.BYTES);
        data.asFloatBuffer().put(values);
        return data;
    }

    private static void clear() {
        GLStateManager.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
        GLSMFramebuffer.glClear(GL11.GL_COLOR_BUFFER_BIT);
    }

    private static int centrePixel() {
        final ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        GLSMFramebuffer.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        return pixels.getInt((SIZE / 2 * SIZE + SIZE / 2) * 4);
    }

    private static int unorm(float value) {
        return Math.round(value * 255.0F);
    }

    private static void assertByte(int expected, int actual, String message) {
        assertTrue(Math.abs(expected - actual) <= 2, () -> message + ": expected " + expected + " but read " + actual);
    }

    private static int linkProgram() {
        final int vertex = compile(GL20.GL_VERTEX_SHADER, String.join("\n",
            "#version 330 core",
            "layout(location = 0) in vec3 position;",
            "layout(location = 2) in vec2 uv;",
            "layout(location = 12) in vec2 midTexCoord;",
            "layout(location = 13) in vec4 tangent;",
            "out vec4 color;",
            "void main() {",
            "    gl_Position = vec4(position, 1.0);",
            "    color = vec4(midTexCoord.x, midTexCoord.y, tangent.z * 0.5 + 0.5, 1.0);",
            "}"));
        final int fragment = compile(GL20.GL_FRAGMENT_SHADER, String.join("\n",
            "#version 330 core",
            "in vec4 color;",
            "out vec4 fragColor;",
            "void main() { fragColor = color; }"));
        final int linked = GL20.glCreateProgram();
        GL20.glAttachShader(linked, vertex);
        GL20.glAttachShader(linked, fragment);
        GL20.glLinkProgram(linked);
        if (GL20.glGetProgrami(linked, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
            throw new IllegalStateException(GL20.glGetProgramInfoLog(linked));
        }
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);
        return linked;
    }

    private static int compile(int type, String source) {
        final int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
            throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    /**
     * Derives the two attributes from the packed source range, and records what it read and wrote so the test can
     * check both the source decoding and the values handed to the draw.
     */
    private static final class RecordingHandler implements ImmediateExtendedAttribHandler {

        int builds;
        int lastVertsPerPrim;
        int lastVertexCount;
        private float[] midUs = new float[0];
        private float[] midVs = new float[0];

        float midU(int vertex) {
            return midUs[vertex];
        }

        float midV(int vertex) {
            return midVs[vertex];
        }

        @Override
        public boolean wantsExtended() {
            return true;
        }

        @Override
        public boolean wantsTangent() {
            return true;
        }

        @Override
        public void buildPacked(long srcBase, int stride, int posOffset, int texOffset, int normalOffset,
                                int vertexCount, int vertsPerPrim, long dstAddr, int dstStride) {
            builds++;
            lastVertsPerPrim = vertsPerPrim;
            lastVertexCount = vertexCount;
            midUs = new float[vertexCount];
            midVs = new float[vertexCount];

            for (int vertex = 0; vertex < vertexCount; vertex += vertsPerPrim) {
                float minU = Float.MAX_VALUE;
                float maxU = -Float.MAX_VALUE;
                float minV = Float.MAX_VALUE;
                float maxV = -Float.MAX_VALUE;
                for (int i = 0; i < vertsPerPrim; i++) {
                    final long source = srcBase + (long) (vertex + i) * stride;
                    final float u = memGetFloat(source + texOffset);
                    final float v = memGetFloat(source + texOffset + 4);
                    minU = Math.min(minU, u);
                    maxU = Math.max(maxU, u);
                    minV = Math.min(minV, v);
                    maxV = Math.max(maxV, v);
                }
                final float midU = (minU + maxU) * 0.5F;
                final float midV = (minV + maxV) * 0.5F;

                final long first = srcBase + (long) vertex * stride + posOffset;
                final long second = srcBase + (long) (vertex + 1) * stride + posOffset;
                final long third = srcBase + (long) (vertex + 2) * stride + posOffset;
                final float edgeX = memGetFloat(second) - memGetFloat(first);
                final float edgeY = memGetFloat(second + 4) - memGetFloat(first + 4);
                final float edgeZ = memGetFloat(second + 8) - memGetFloat(first + 8);
                final float sideX = memGetFloat(third) - memGetFloat(first);
                final float sideY = memGetFloat(third + 4) - memGetFloat(first + 4);
                final float sideZ = memGetFloat(third + 8) - memGetFloat(first + 8);
                final float normalX = edgeY * sideZ - edgeZ * sideY;
                final float normalY = edgeZ * sideX - edgeX * sideZ;
                final float normalZ = edgeX * sideY - edgeY * sideX;
                final float length = (float) Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
                final int tangent = pack(normalX / length, normalY / length, normalZ / length);

                for (int i = 0; i < vertsPerPrim; i++) {
                    final long destination = dstAddr + (long) (vertex + i) * dstStride;
                    memPutFloat(destination, midU);
                    memPutFloat(destination + 4, midV);
                    memPutInt(destination + 8, tangent);
                    midUs[vertex + i] = midU;
                    midVs[vertex + i] = midV;
                }
            }
        }

        private static int pack(float x, float y, float z) {
            return encode(x) | encode(y) << 8 | encode(z) << 16 | encode(1.0F) << 24;
        }

        private static int encode(float value) {
            return ((int) (value * 127.0F)) & 0xFF;
        }
    }
}
