package com.gtnewhorizons.angelica.glsm.ffp;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMCurrentAttribs;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.GLSMMatrices;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Draws GL_LINES through the FFP line-quad path against a real context. The widened quad must survive a segment
 * that crosses the near plane, keep a constant width, cover its first pixel but not its last, and stay visible
 * with face culling on in both directions.
 */
class WideLineClipGLTest extends GLStateManagerIntegrationFixture {

    private static final int SIZE = 64;
    private static final double NEAR = 0.05;
    private static final float LINE_WIDTH = 3.0f;
    private static final int BACKGROUND = 0xFF000000;

    private static final float[] IN_FRONT = { 0f, -0.9875f, -2f };
    private static final float[] BEHIND = { 0f, 0.8f, 2f };

    private boolean savedEmulation;
    private int vao;
    private int vbo;

    @BeforeAll
    static void initializeGlsm() {
        installDefaultGlsmConfig();
        routeVertexFormats(true);
    }

    @BeforeEach
    void setUp() {
        WindowBackends.useSdl();
        GLStateManager.bindDisplayContext(new Object(), context);
        GLStateManager.recreateDefaultVertexArray();

        savedEmulation = GLStateManager.wideLineEmulationEnabled;
        GLStateManager.wideLineEmulationEnabled = true;

        ShaderManager.enable();
        ShaderManager.getInstance().activate();
        GLSMRasterizer.glViewport(0, 0, SIZE, SIZE);
        GLSMMatrices.glMatrixMode(GL11.GL_PROJECTION);
        GLSMMatrices.glLoadIdentity();
        GLSMMatrices.glFrustum(-NEAR, NEAR, -NEAR, NEAR, NEAR, 100.0);
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMMatrices.glLoadIdentity();
        GLSMEnable.glDisable(GL11.GL_DEPTH_TEST);
        GLSMRasterizer.glLineWidth(LINE_WIDTH);
    }

    @AfterEach
    void tearDown() {
        GLStateManager.wideLineEmulationEnabled = savedEmulation;
        GLSMRasterizer.glLineWidth(1.0f);
        if (vbo != 0) { GLSMBuffers.glDeleteBuffers(vbo); vbo = 0; }
        if (vao != 0) { GLSMVertexInput.glDeleteVertexArrays(vao); vao = 0; }
        GLSMMatrices.glMatrixMode(GL11.GL_PROJECTION);
        GLSMMatrices.glLoadIdentity();
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        final ShaderManager sm = ShaderManager.getInstance();
        if (sm.isActive()) sm.deactivate();
        GLSMVertexInput.glBindVertexArray(0);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GLSMEnable.glDisable(GL11.GL_CULL_FACE);
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {}
    }

    @Test
    void lineEndingBehindTheCameraKeepsItsWidth() {
        assertConstantWidth(IN_FRONT, BEHIND);
    }

    @Test
    void lineStartingBehindTheCameraKeepsItsWidth() {
        assertConstantWidth(BEHIND, IN_FRONT);
    }

    @Test
    void lineFullyBehindTheCameraDrawsNothing() {
        final int[] pixels = drawLine(new float[] { 0f, -1f, 2f }, BEHIND);
        for (int px : pixels) {
            assertEquals(BACKGROUND, px);
        }
    }

    @Test
    void wideLineCoversItsFirstPixelButNotItsLast() {
        orthographic();
        GLSMRasterizer.glLineWidth(2.0f);
        final float y = pixelCenter(16);
        final int[] pixels = drawLineOnce(new float[] { pixelCenter(40), y, 0f }, new float[] { pixelCenter(20), y, 0f });
        assertEquals(2, litInColumn(pixels, 40), "first pixel of a right-to-left line");
        assertEquals(0, litInColumn(pixels, 20), "last pixel of a right-to-left line");
        assertEquals(2, litInColumn(pixels, 21), "pixel next to the last");
    }

    @Test
    void cullingKeepsWideLinesInBothDirections() {
        orthographic();
        GLSMRasterizer.glLineWidth(2.0f);
        GLSMEnable.glEnable(GL11.GL_CULL_FACE);
        try {
            final int[] rightward = drawLineOnce(new float[] { -0.8f, 0f, 0f }, new float[] { 0.8f, 0f, 0f });
            final int[] leftward = drawLineOnce(new float[] { 0.8f, 0f, 0f }, new float[] { -0.8f, 0f, 0f });
            assertEquals(2, litInColumn(rightward, SIZE / 2), "left-to-right line with culling on");
            assertEquals(2, litInColumn(leftward, SIZE / 2), "right-to-left line with culling on");
            assertTrue(GLSMRasterizer.getCullState().isEnabled(), "the game's cull state survives");
        } finally {
            GLSMEnable.glDisable(GL11.GL_CULL_FACE);
        }
    }

    private static void orthographic() {
        GLSMMatrices.glMatrixMode(GL11.GL_PROJECTION);
        GLSMMatrices.glLoadIdentity();
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
    }

    private static float pixelCenter(int pixel) {
        return (pixel + 0.5f) * 2.0f / SIZE - 1.0f;
    }

    private static int litInColumn(int[] pixels, int x) {
        int lit = 0;
        for (int row = 0; row < SIZE; row++) {
            if (pixels[row * SIZE + x] != BACKGROUND) lit++;
        }
        return lit;
    }

    private int[] drawLineOnce(float[] start, float[] end) {
        final int[] pixels = drawLine(start, end);
        GLSMBuffers.glDeleteBuffers(vbo);
        GLSMVertexInput.glDeleteVertexArrays(vao);
        vbo = 0;
        vao = 0;
        return pixels;
    }

    private void assertConstantWidth(float[] start, float[] end) {
        final int[] pixels = drawLine(start, end);
        for (int row = 0; row < SIZE / 4; row++) {
            int lit = 0;
            for (int x = 0; x < SIZE; x++) {
                if (pixels[row * SIZE + x] != BACKGROUND) lit++;
            }
            final int y = row, count = lit;
            assertTrue(count > 0 && count <= LINE_WIDTH + 1, () -> "row " + y + " is " + count + " pixels wide");
        }
    }

    private int[] drawLine(float[] start, float[] end) {
        vao = GLSMVertexInput.glGenVertexArrays();
        GLSMVertexInput.glBindVertexArray(vao);
        vbo = GLSMBuffers.glGenBuffers();
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        final FloatBuffer positions = BufferUtils.createFloatBuffer(6);
        positions.put(start).put(end).flip();
        GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, positions, GL15.GL_STATIC_DRAW);
        GLSMVertexInput.glEnableVertexAttribArray(0);
        GLSMVertexInput.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0L);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        clear();
        GLSMCurrentAttribs.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        GLSMDraws.glDrawArrays(GL11.GL_LINES, 0, 2);
        GLSMVertexInput.glBindVertexArray(0);
        return readRegion();
    }

    private static void clear() {
        GLStateManager.glClearColor(0f, 0f, 0f, 1f);
        GLSMFramebuffer.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
    }

    private static int[] readRegion() {
        final ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
        GLSMFramebuffer.glReadPixels(0, 0, SIZE, SIZE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        final int[] out = new int[SIZE * SIZE];
        for (int i = 0; i < out.length; i++) {
            out[i] = pixels.getInt(i * 4);
        }
        return out;
    }
}
