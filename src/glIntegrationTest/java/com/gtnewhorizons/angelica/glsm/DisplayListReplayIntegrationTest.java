package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisplayListReplayIntegrationTest extends GLStateManagerIntegrationFixture {
    private int list;
    private int vao;
    private int ebo;
    private int vbo;
    private int texture;

    @BeforeAll
    static void configureGlsm() {
        installDefaultGlsmConfig();
        routeVertexFormats(true);
    }

    @BeforeEach
    void setUp() {
        GLStateManager.bindDisplayContext(new Object(), context);
        GLStateManager.recreateDefaultVertexArray();
        ShaderManager.enable();
        ShaderManager.getInstance().activate();
        GLSMRasterizer.glViewport(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT);
        GLSMMatrices.glMatrixMode(GL11.GL_PROJECTION);
        GLSMMatrices.glLoadIdentity();
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMMatrices.glLoadIdentity();
        GLSMEnable.glDisable(GL11.GL_DEPTH_TEST);
        vao = GLSMVertexInput.glGenVertexArrays();
        GLSMVertexInput.glBindVertexArray(vao);
        ebo = GLSMBuffers.glGenBuffers();
        GLSMBuffers.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        ShortBuffer indices = BufferUtils.createShortBuffer(3).put(new short[] { 0, 1, 2 }).flip();
        GLSMBuffers.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
        list = GLSMDraws.glGenLists(1);
    }

    @AfterEach
    void tearDown() {
        if (DisplayListManager.isRecording()) GLSMDraws.glEndList();
        GLSMDraws.glDeleteLists(list, 1);
        GLSMVertexInput.glBindVertexArray(0);
        GLSMVertexInput.glDeleteVertexArrays(vao);
        GLSMBuffers.glDeleteBuffers(ebo);
        if (vbo != 0) GLSMBuffers.glDeleteBuffers(vbo);
        if (texture != 0) GLSMTextures.glDeleteTextures(texture);
        GLSMEnable.glDisable(GL11.GL_LIGHTING);
        GLSMEnable.glDisable(GL11.GL_LIGHT0);
        GLSMEnable.glDisable(GL11.GL_TEXTURE_2D);
        ShaderManager.getInstance().deactivate();
    }

    @Test
    void indexedReplayUsesCapturedNormalsAndTextureCoordinates() {
        setUpLitTexturedTriangle();
        clear();
        drawIndexedTriangle();
        int expected = centerPixel();
        assertTrue((expected >> 16 & 255) > 150 && (expected >>> 24) == 0,
            "the direct draw samples the green texel and receives directional light");

        compileIndexedTriangle();
        for (int i = 0; i < 2; i++) {
            clear();
            GLSMDraws.glCallList(list);
            assertEquals(expected, centerPixel(), "indexed display list draw " + (i + 1));
        }
    }

    @Test
    void indexedReplayRestoresCallerVertexArrayAndElementBuffer() {
        setUpLitTexturedTriangle();
        compileIndexedTriangle();

        GLSMDraws.glCallList(list);

        assertCallerVertexArray();
    }

    @ParameterizedTest
    @ValueSource(ints = { GL11.GL_TRIANGLES, GL11.GL_LINES })
    void immediateReplayRestoresCallerVertexArrayAndElementBuffer(int mode) {
        final boolean previousEmulation = GLStateManager.wideLineEmulationEnabled;
        GLStateManager.wideLineEmulationEnabled = true;
        GLSMRasterizer.glLineWidth(3);
        try {
            GLSMDraws.glNewList(list, GL11.GL_COMPILE);
            GLSMDraws.glBegin(mode);
            GLSMDraws.glVertex2f(-1, 0);
            GLSMDraws.glVertex2f(1, 0);
            if (mode == GL11.GL_TRIANGLES) GLSMDraws.glVertex2f(0, 1);
            GLSMDraws.glEnd();
            GLSMDraws.glEndList();

            GLSMDraws.glCallList(list);

            assertCallerVertexArray();
        } finally {
            GLSMRasterizer.glLineWidth(1);
            GLStateManager.wideLineEmulationEnabled = previousEmulation;
        }
    }

    private void assertCallerVertexArray() {
        assertEquals(vao, GLSMVertexInput.getBoundVAO(), "GLSM VAO binding");
        assertEquals(vao, GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING), "driver VAO binding");
        assertEquals(ebo, GLSMVertexInput.getBoundEBO(), "GLSM element-buffer binding");
        assertEquals(ebo, GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING), "driver element-buffer binding");
    }

    private void setUpLitTexturedTriangle() {
        vbo = GLSMBuffers.glGenBuffers();
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        FloatBuffer vertices = floats(
            -1, -1, 0, 0, 0, 1, 0.75f, 0.5f,
             1, -1, 0, 0, 0, 1, 0.75f, 0.5f,
             0,  1, 0, 0, 0, 1, 0.75f, 0.5f);
        GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
        GLSMVertexInput.glVertexPointer(3, GL11.GL_FLOAT, 32, 0L);
        GLSMVertexInput.glNormalPointer(GL11.GL_FLOAT, 32, 12L);
        GLSMVertexInput.glTexCoordPointer(2, GL11.GL_FLOAT, 32, 24L);
        GLSMVertexInput.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GLSMVertexInput.glEnableClientState(GL11.GL_NORMAL_ARRAY);
        GLSMVertexInput.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);

        texture = GLSMTextures.glGenTextures();
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        ByteBuffer texels = BufferUtils.createByteBuffer(8).put(new byte[] { -1, 0, 0, -1, 0, -1, 0, -1 }).flip();
        GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 2, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, texels);
        GLSMEnable.glEnable(GL11.GL_TEXTURE_2D);

        GLSMLighting.glLightModel(GL11.GL_LIGHT_MODEL_AMBIENT, floats(0, 0, 0, 1));
        GLSMLighting.glLight(GL11.GL_LIGHT0, GL11.GL_POSITION, floats(0, 0, 1, 0));
        GLSMLighting.glLight(GL11.GL_LIGHT0, GL11.GL_DIFFUSE, floats(1, 1, 1, 1));
        GLSMEnable.glEnable(GL11.GL_LIGHT0);
        GLSMEnable.glEnable(GL11.GL_LIGHTING);
        GLSMCurrentAttribs.glNormal3f(0, 0, -1);
        GLSMCurrentAttribs.glTexCoord2f(0.25f, 0.5f);
    }

    private void compileIndexedTriangle() {
        GLSMDraws.glNewList(list, GL11.GL_COMPILE);
        drawIndexedTriangle();
        GLSMDraws.glEndList();
    }

    private static void drawIndexedTriangle() {
        GLSMDraws.glDrawElements(GL11.GL_TRIANGLES, 3, GL11.GL_UNSIGNED_SHORT, 0L);
    }

    private static void clear() {
        GLStateManager.glClearColor(0, 0, 0, 1);
        GLSMFramebuffer.glClear(GL11.GL_COLOR_BUFFER_BIT);
    }

    private static int centerPixel() {
        ByteBuffer pixel = BufferUtils.createByteBuffer(Integer.BYTES);
        GLSMFramebuffer.glReadPixels(WINDOW_WIDTH / 2, WINDOW_HEIGHT / 2, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
        return pixel.order(ByteOrder.BIG_ENDIAN).getInt(0);
    }

    private static FloatBuffer floats(float... values) {
        return BufferUtils.createFloatBuffer(values.length).put(values).flip();
    }
}
