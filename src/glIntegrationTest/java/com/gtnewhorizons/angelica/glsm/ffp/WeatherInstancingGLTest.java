package com.gtnewhorizons.angelica.glsm.ffp;

import com.dhj.actinium.render.WeatherRenderer;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.GLSMLighting;
import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.GLSMMatrices;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Draws weather columns through the FFP instanced variant against a real context.
 *
 * <p>One column is uploaded as a 48 byte instance record and drawn as a four corner triangle fan.
 * The vertex shader has to expand the column into the world-space quad, sample the rain or snow
 * texture with the scrolled coordinate and write the transparency the fallback would write, so the
 * pixels of the frame are the assertion: the quad covers its own area, its alpha follows the rain
 * strength and the snow flag, the scroll matches the vanilla sign, the lightmap is read as
 * {@code (sky, block)} through the lightmap texture matrix, legacy lighting does not disturb the
 * transparency, and the camera column collapses instead of producing NaN geometry.</p>
 */
class WeatherInstancingGLTest extends GLStateManagerIntegrationFixture {

    private static final int SIZE = 64;
    private static final int BACKGROUND = 0xFF000000;

    /** Rain alpha of the column at the sampled distance: (1 - 0.25) * 0.5 + 0.5. */
    private static final int RAIN_ALPHA = 223;

    private static final int STRIDE = WeatherInstancedAttribs.STRIDE;

    /** Raw lightmap coordinate of sky light 4 and block light 12, in the units vanilla packs. */
    private static final float RAW_SKY = 4 * 16.0F;
    private static final float RAW_BLOCK = 12 * 16.0F;

    /** Texel the lightmap texture matrix maps the sky coordinate to. */
    private static final int LIGHTMAP_SKY_TEXEL = 4;
    private static final int LIGHTMAP_BLOCK_TEXEL = 12;

    /** Every texel step encodes 17 levels, so a read back channel names the sampled texel. */
    private static final int TEXEL_STEP = 17;

    private int instanceBuffer;
    private int rainTexture;
    private int lightmapTexture;

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

        ShaderManager.enable();
        ShaderManager.getInstance().activate();
        GLSMRasterizer.glViewport(0, 0, SIZE, SIZE);
        GLSMMatrices.glMatrixMode(GL11.GL_PROJECTION);
        GLSMMatrices.glLoadIdentity();
        GLSMMatrices.glOrtho(-1.0, 1.0, -1.0, 1.0, -1.0, 1.0);
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMMatrices.glLoadIdentity();
        GLSMEnable.glDisable(GL11.GL_DEPTH_TEST);
        GLSMEnable.glDisable(GL11.GL_BLEND);
        GLSMEnable.glDisable(GL11.GL_CULL_FACE);
        GLSMEnable.glDisable(GL11.GL_LIGHTING);

        rainTexture = whiteTexture();
        lightmapTexture = lightmapTexture();
        bindRainTexture(rainTexture);

        instanceBuffer = GLSMBuffers.glGenBuffers();
    }

    @AfterEach
    void tearDown() {
        WeatherQuadMesh.delete();
        if (instanceBuffer != 0) { GLSMBuffers.glDeleteBuffers(instanceBuffer); instanceBuffer = 0; }
        GLSMEnable.glDisable(GL11.GL_LIGHTING);
        disableLightmapUnit();
        bindRainTexture(0);
        GLSMEnable.glDisable(GL11.GL_TEXTURE_2D);
        if (rainTexture != 0) { GL11.glDeleteTextures(rainTexture); rainTexture = 0; }
        if (lightmapTexture != 0) { GL11.glDeleteTextures(lightmapTexture); lightmapTexture = 0; }
        GLSMPrograms.setFfpInstancing(Instancing.NONE);
        final ShaderManager sm = ShaderManager.getInstance();
        if (sm.isActive()) sm.deactivate();
        GLSMVertexInput.glBindVertexArray(0);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GLSMMatrices.glMatrixMode(GL11.GL_PROJECTION);
        GLSMMatrices.glLoadIdentity();
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMMatrices.glLoadIdentity();
        while (GL11.glGetError() != GL11.GL_NO_ERROR) { }
    }

    @Test
    void theActiveBackendDrawsInstances() {
        assertTrue(BackendManager.RENDER_BACKEND.supportsInstancedDrawing(),
            "the instanced weather path needs a backend that draws instances: "
                + BackendManager.RENDER_BACKEND.getName());
    }

    @Test
    void rainColumnCoversItsQuadWithTheVanillaTransparency() {
        final int[] pixels = drawColumn(rainInstance(0.0F, -0.5F, -0.5F, 0.5F), 1.0F, 0.0F);

        assertEquals(RAIN_ALPHA, alphaAt(pixels, 48, 32), 2, "centre of the column");
        assertEquals(255, redAt(pixels, 48, 32), "the rain texture is sampled");
        assertEquals(BACKGROUND, pixels[8 * SIZE + 8], "outside the column");
    }

    @Test
    void transparencyFollowsTheRainStrengthUniform() {
        final int full = alphaAt(drawColumn(rainInstance(0.0F, -0.5F, -0.5F, 0.5F), 1.0F, 0.0F), 48, 32);
        final int half = alphaAt(drawColumn(rainInstance(0.0F, -0.5F, -0.5F, 0.5F), 0.5F, 0.0F), 48, 32);

        assertEquals(RAIN_ALPHA, full, 2);
        assertEquals(full / 2, half, 3, "the strength reaches the shader through WeatherParams");
    }

    @Test
    void snowColumnUsesTheSnowTransparency() {
        final int rain = alphaAt(drawColumn(rainInstance(0.0F, -0.5F, -0.5F, 0.5F), 1.0F, 0.0F), 48, 32);
        final int snow = alphaAt(drawColumn(snowInstance(0.0F, -0.5F, -0.5F, 0.5F), 1.0F, 0.0F), 48, 32);

        assertEquals(RAIN_ALPHA, rain, 2);
        assertEquals(184, snow, 3, "(1 - 0.25) * 0.3 + 0.5");
        assertTrue(snow < rain, "snow is drawn less opaque than rain");
    }

    @Test
    void legacyLightingKeepsTheWeatherTransparency() {
        GLSMEnable.glEnable(GL11.GL_LIGHTING);
        try {
            final int[] pixels = drawColumn(rainInstance(0.0F, -0.5F, -0.5F, 0.5F), 1.0F, 0.0F);

            assertEquals(RAIN_ALPHA, alphaAt(pixels, 48, 32), 2,
                "the weather colour does not depend on the lighting state");
            assertEquals(255, redAt(pixels, 48, 32));
        } finally {
            GLSMEnable.glDisable(GL11.GL_LIGHTING);
        }
    }

    @Test
    void separateSpecularDoesNotLeakIntoTheWeatherColour() {
        // Lighting plus GL_SEPARATE_SPECULAR_COLOR makes the fragment stage declare and add
        // v_SpecularColor. The weather variant never runs the lighting branch, so it has to write a
        // zero specular itself instead of leaving the varying undefined.
        GLSMEnable.glEnable(GL11.GL_LIGHTING);
        GLSMLighting.glLightModeli(GL12.GL_LIGHT_MODEL_COLOR_CONTROL, GL12.GL_SEPARATE_SPECULAR_COLOR);
        try {
            final int[] pixels = drawColumn(rainInstance(0.0F, -0.5F, -0.5F, 0.5F), 1.0F, 0.0F);

            assertEquals(RAIN_ALPHA, alphaAt(pixels, 48, 32), 2, "transparency survives the lit pass");
            assertEquals(255, redAt(pixels, 48, 32), "the weather colour is not replaced by lighting");
        } finally {
            GLSMLighting.glLightModeli(GL12.GL_LIGHT_MODEL_COLOR_CONTROL, GL12.GL_SINGLE_COLOR);
            GLSMEnable.glDisable(GL11.GL_LIGHTING);
        }
    }

    @Test
    void lightmapIsSampledAsSkyThenBlockThroughTheTextureMatrix() {
        enableLightmapUnit();

        final int[] pixels = drawColumn(rainInstance(0.0F, -0.5F, -0.5F, 0.5F, RAW_BLOCK, RAW_SKY), 1.0F, 0.0F);

        assertEquals(LIGHTMAP_SKY_TEXEL * TEXEL_STEP, redAt(pixels, 48, 32),
            "the sky coordinate selects the texture column");
        assertEquals(LIGHTMAP_BLOCK_TEXEL * TEXEL_STEP, greenAt(pixels, 48, 32),
            "the block coordinate selects the texture row");
        assertEquals(RAIN_ALPHA, alphaAt(pixels, 48, 32), 2, "the lightmap does not change the transparency");
    }

    @Test
    void rainScrollFollowsTheVanillaSign() {
        bindRainTexture(gradientTexture());

        final int scrolled = redAt(drawColumn(rainInstance(0.0F, -0.5F, -0.5F, 0.5F), 1.0F, 2.0F), 48, 32);
        final int unscrolled = redAt(drawColumn(rainInstance(0.0F, -0.5F, -0.5F, 0.5F), 1.0F, 0.0F), 48, 32);

        // V = -(mod(2 + 0, 32) + 0) / 32 * (3 + 0.5) = -0.21875, which repeats to 0.78125 and selects row 12.
        assertEquals(12 * TEXEL_STEP, scrolled, "the scroll moves the texture the vanilla way");
        assertEquals(0, unscrolled, "without a scroll the column samples the first row");
    }

    @Test
    void cameraColumnCollapsesWithoutProducingNaN() {
        final int[] pixels = drawColumn(rainInstance(0.0F, 0.0F, -0.5F, 0.5F), 1.0F, 0.0F);

        for (int pixel : pixels) {
            assertEquals(BACKGROUND, pixel, "a zero length column must not paint anything");
        }
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void clearReleasesTheQuadMesh() {
        final int vao = WeatherQuadMesh.vao();
        assertTrue(GLSMVertexInput.glIsVertexArray(vao), "the mesh exists before the release");

        WeatherRenderer.clear();

        assertFalse(GLSMVertexInput.glIsVertexArray(vao), "the world unload releases the quad mesh");
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    private int[] drawColumn(ByteBuffer instance, float rainStrength, float rainScroll) {
        final ByteBuffer data = BufferUtils.createByteBuffer(STRIDE);
        data.put(instance).flip();
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceBuffer);
        GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        WeatherQuadMesh.bindInstanceBuffer(instanceBuffer);

        WeatherParams.set(0.0F, 0.0F, 0.0F, 1.0F, 0.5F, 0.5F, 0.0F, 0.0F, rainScroll, 0.0F, rainStrength);
        clear();
        GLSMVertexInput.glBindVertexArray(WeatherQuadMesh.vao());
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceBuffer);
        WeatherInstancedAttribs.pointInstanceAttribs(0L);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GLStateManager.getVaoManager().setVertexFlags(WeatherQuadMesh.VERTEX_FLAGS);
        GLSMPrograms.setFfpInstancing(Instancing.WEATHER);
        GLSMDraws.glDrawArraysInstanced(GL11.GL_TRIANGLE_FAN, 0, WeatherQuadMesh.VERTEX_COUNT, 1);
        GLSMPrograms.setFfpInstancing(Instancing.NONE);
        GLSMVertexInput.glBindVertexArray(0);
        return readRegion();
    }

    private static ByteBuffer rainInstance(float columnX, float columnZ, float bottom, float top) {
        return instance(columnX, columnZ, bottom, top, 0.0F, 240.0F, 240.0F);
    }

    private static ByteBuffer rainInstance(float columnX, float columnZ, float bottom, float top,
                                          float blockLight, float skyLight) {
        return instance(columnX, columnZ, bottom, top, 0.0F, blockLight, skyLight);
    }

    private static ByteBuffer snowInstance(float columnX, float columnZ, float bottom, float top) {
        return instance(columnX, columnZ, bottom, top, 1.0F, 240.0F, 240.0F);
    }

    private static ByteBuffer instance(float columnX, float columnZ, float bottom, float top, float snow,
                                       float blockLight, float skyLight) {
        final ByteBuffer data = BufferUtils.createByteBuffer(STRIDE);
        data.putFloat(columnX).putFloat(columnZ).putFloat(bottom).putFloat(top);
        data.putFloat(0.5F).putFloat(0.0F).putFloat(0.5F).putFloat(0.0F);
        data.putFloat(blockLight).putFloat(skyLight).putFloat(0.0F).putFloat(snow);
        return data.flip();
    }

    private void bindRainTexture(int texture) {
        GLStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        if (texture != 0) {
            GLSMEnable.glEnable(GL11.GL_TEXTURE_2D);
        } else {
            GLSMEnable.glDisable(GL11.GL_TEXTURE_2D);
        }
    }

    /**
     * Binds a 16 texel lightmap with the vanilla lightmap texture matrix: the packed coordinate is
     * scaled by {@code 1 / 256} and offset by half a texel, so raw light 4 reads texel 4.
     */
    private void enableLightmapUnit() {
        GLStateManager.setActiveTexture(GL13.GL_TEXTURE1);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, lightmapTexture);
        GLSMEnable.glEnable(GL11.GL_TEXTURE_2D);
        GLSMMatrices.glMatrixMode(GL11.GL_TEXTURE);
        GLSMMatrices.glLoadIdentity();
        GLSMMatrices.glScalef(1.0F / 256.0F, 1.0F / 256.0F, 1.0F / 256.0F);
        GLSMMatrices.glTranslatef(8.0F, 8.0F, 8.0F);
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLStateManager.setActiveTexture(GL13.GL_TEXTURE0);
    }

    private void disableLightmapUnit() {
        GLStateManager.setActiveTexture(GL13.GL_TEXTURE1);
        GLSMEnable.glDisable(GL11.GL_TEXTURE_2D);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GLSMMatrices.glMatrixMode(GL11.GL_TEXTURE);
        GLSMMatrices.glLoadIdentity();
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLStateManager.setActiveTexture(GL13.GL_TEXTURE0);
    }

    private static int whiteTexture() {
        final ByteBuffer white = BufferUtils.createByteBuffer(16 * 16 * 4);
        for (int i = 0; i < 16 * 16; i++) {
            white.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) 255);
        }
        white.flip();
        return upload(white);
    }

    /**
     * 16 by 16 texture whose red channel is the column and green channel the row, so a read back pixel
     * names the texel the lightmap coordinate selected.
     */
    private static int lightmapTexture() {
        final ByteBuffer data = BufferUtils.createByteBuffer(16 * 16 * 4);
        for (int row = 0; row < 16; row++) {
            for (int column = 0; column < 16; column++) {
                data.put((byte) (column * TEXEL_STEP)).put((byte) (row * TEXEL_STEP)).put((byte) 0).put((byte) 255);
            }
        }
        data.flip();
        return upload(data);
    }

    /**
     * 16 by 16 texture whose red channel is the row, used to read back the sampled texture V.
     */
    private static int gradientTexture() {
        final ByteBuffer data = BufferUtils.createByteBuffer(16 * 16 * 4);
        for (int row = 0; row < 16; row++) {
            for (int column = 0; column < 16; column++) {
                data.put((byte) (row * TEXEL_STEP)).put((byte) 0).put((byte) 0).put((byte) 255);
            }
        }
        data.flip();
        return upload(data);
    }

    /**
     * Creates a 16 by 16 texture. Binding goes through GLSM because the FFP fragment key treats an
     * enabled unit without a tracked binding as disabled, and a raw bind would leave the texture out
     * of the shader.
     */
    private static int upload(ByteBuffer data) {
        final int id = GL11.glGenTextures();
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 16, 16, 0, GL11.GL_RGBA,
            GL11.GL_UNSIGNED_BYTE, data);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        return id;
    }

    private static void clear() {
        GLStateManager.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
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

    private static int alphaAt(int[] pixels, int x, int y) {
        return pixels[y * SIZE + x] >>> 24 & 0xFF;
    }

    private static int redAt(int[] pixels, int x, int y) {
        return pixels[y * SIZE + x] & 0xFF;
    }

    private static int greenAt(int[] pixels, int x, int y) {
        return pixels[y * SIZE + x] >> 8 & 0xFF;
    }
}
