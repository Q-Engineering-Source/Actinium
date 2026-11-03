package com.gtnewhorizons.angelica.glsm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBImaging;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the legacy {@code glGet*} pnames the core profile rejects and checks that every answer comes from
 * tracked state: the value read back must match what GLSM recorded, and the driver must not have seen the
 * query at all, which {@link #assertDriverAcceptedLastCalls()} verifies through {@code glGetError}.
 */
class LegacyQueryIntegrationTest extends GLStateManagerIntegrationFixture {

    private static final float FIXED_POINT_SCALE = 2147483647.0f;
    /** Marker left in a caller array so a query that writes past its own width is visible. */
    private static final int RESIDUE = 0x5EED;

    @BeforeEach
    void clearDriverErrors() {
        drainDriverErrors();
    }

    @AfterEach
    void restoreDefaults() {
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        GLSMTextures.glClientActiveTexture(GL13.GL_TEXTURE0);
        GLSMCurrentAttribs.glEdgeFlag(true);
        GLSMRasterizer.glPointParameterf(GL14.GL_POINT_FADE_THRESHOLD_SIZE, 1.0f);
        GLSMRasterizer.glPointParameteri(GL20.GL_POINT_SPRITE_COORD_ORIGIN, GL20.GL_UPPER_LEFT);
        GLSMDraws.glRenderMode(GL11.GL_RENDER);
        drainDriverErrors();
    }

    @Test
    void stackDepthsFollowMatrixPushes() {
        final int modelViewDepth = GLSMQueries.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH);
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMMatrices.glPushMatrix();
        assertEquals(modelViewDepth + 1, GLSMQueries.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH));
        GLSMMatrices.glPopMatrix();
        assertEquals(modelViewDepth, GLSMQueries.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH));

        GLSMMatrices.glMatrixMode(GL11.GL_PROJECTION);
        final int projectionDepth = GLSMQueries.glGetInteger(GL11.GL_PROJECTION_STACK_DEPTH);
        GLSMMatrices.glPushMatrix();
        assertEquals(projectionDepth + 1, GLSMQueries.glGetInteger(GL11.GL_PROJECTION_STACK_DEPTH));
        GLSMMatrices.glPopMatrix();

        GLSMMatrices.glMatrixMode(GL11.GL_COLOR);
        final int colorDepth = GLSMQueries.glGetInteger(ARBImaging.GL_COLOR_MATRIX_STACK_DEPTH);
        GLSMMatrices.glPushMatrix();
        assertEquals(colorDepth + 1, GLSMQueries.glGetInteger(ARBImaging.GL_COLOR_MATRIX_STACK_DEPTH));
        GLSMMatrices.glPopMatrix();

        final int maxModelViewDepth = GLSMQueries.glGetInteger(GL11.GL_MAX_MODELVIEW_STACK_DEPTH);
        final int maxProjectionDepth = GLSMQueries.glGetInteger(GL11.GL_MAX_PROJECTION_STACK_DEPTH);
        assertTrue(maxModelViewDepth > modelViewDepth, "GL_MAX_MODELVIEW_STACK_DEPTH must cover the base matrix");
        assertTrue(maxProjectionDepth > projectionDepth, "GL_MAX_PROJECTION_STACK_DEPTH must cover the base matrix");
        assertDriverAcceptedLastCalls();
    }

    @Test
    void textureStackDepthFollowsActiveUnit() {
        GLSMMatrices.glMatrixMode(GL11.GL_TEXTURE);
        try {
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE2);
            final int unitTwoDepth = GLSMQueries.glGetInteger(GL11.GL_TEXTURE_STACK_DEPTH);
            GLSMMatrices.glPushMatrix();
            assertEquals(unitTwoDepth + 1, GLSMQueries.glGetInteger(GL11.GL_TEXTURE_STACK_DEPTH));

            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            final int unitZeroDepth = GLSMQueries.glGetInteger(GL11.GL_TEXTURE_STACK_DEPTH);
            GLSMMatrices.glPushMatrix();
            assertEquals(unitZeroDepth + 1, GLSMQueries.glGetInteger(GL11.GL_TEXTURE_STACK_DEPTH));

            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE2);
            assertEquals(unitTwoDepth + 1, GLSMQueries.glGetInteger(GL11.GL_TEXTURE_STACK_DEPTH), "each unit keeps its own texture matrix stack");
            GLSMMatrices.glPopMatrix();
            assertEquals(unitTwoDepth, GLSMQueries.glGetInteger(GL11.GL_TEXTURE_STACK_DEPTH));

            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            assertEquals(unitZeroDepth + 1, GLSMQueries.glGetInteger(GL11.GL_TEXTURE_STACK_DEPTH));
            GLSMMatrices.glPopMatrix();
        } finally {
            GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        }
        assertDriverAcceptedLastCalls();
    }

    @Test
    void maxTextureCoordsBoundsClientActiveTexture() {
        final int maxTextureCoords = GLSMQueries.glGetInteger(GL20.GL_MAX_TEXTURE_COORDS);
        final int maxTextureUnits = GLSMQueries.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS);
        assertTrue(maxTextureUnits >= 2, "fixed-function emulation needs at least the base and lightmap UV units");
        assertTrue(maxTextureCoords >= maxTextureUnits,
            "every fixed-function texture unit must also have a client texture coordinate slot");

        GLSMTextures.glClientActiveTexture(GL13.GL_TEXTURE0 + maxTextureCoords - 1);
        assertEquals(maxTextureCoords - 1, GLStateManager.getClientActiveTextureUnit());
        assertEquals(GL13.GL_TEXTURE0 + maxTextureCoords - 1, GLSMQueries.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE));

        GLSMTextures.glClientActiveTexture(GL13.GL_TEXTURE0 + maxTextureCoords);
        assertEquals(maxTextureCoords - 1, GLStateManager.getClientActiveTextureUnit(), "units past GL_MAX_TEXTURE_COORDS must be rejected");
        assertDriverAcceptedLastCalls();
    }

    @Test
    void legacyLimitsAnswerWithoutTouchingTheDriver() {
        assertTrue(GLSMQueries.glGetInteger(GL11.GL_MAX_LIGHTS) >= 1);
        assertTrue(GLSMQueries.glGetInteger(GL11.GL_MAX_CLIP_PLANES) >= 1);
        assertTrue(GLSMQueries.glGetInteger(GL11.GL_MAX_ATTRIB_STACK_DEPTH) >= 1);
        assertTrue(GLSMQueries.glGetInteger(GL11.GL_MAX_CLIENT_ATTRIB_STACK_DEPTH) >= 1);
        assertTrue(GLSMQueries.glGetInteger(GL11.GL_MAX_TEXTURE_STACK_DEPTH) >= 2);
        assertTrue(GLSMQueries.glGetInteger(ARBImaging.GL_MAX_COLOR_MATRIX_STACK_DEPTH) >= 1);
        assertFalse(Float.isNaN(GLSMQueries.glGetFloat(GL14.GL_POINT_SIZE_MAX)), "an unset GL_POINT_SIZE_MAX reports the driver range");
        assertTrue(GLSMQueries.glGetFloat(GL14.GL_POINT_SIZE_MAX) >= 1.0f);
        assertDriverAcceptedLastCalls();
    }

    @Test
    void packedHintsAnswerIndependently() {
        GLSMRasterizer.glHint(GL11.GL_FOG_HINT, GL11.GL_NICEST);
        GLSMRasterizer.glHint(GL11.GL_POINT_SMOOTH_HINT, GL11.GL_FASTEST);
        try {
            assertEquals(GL11.GL_NICEST, GLSMQueries.glGetInteger(GL11.GL_FOG_HINT));
            assertEquals(GL11.GL_FASTEST, GLSMQueries.glGetInteger(GL11.GL_POINT_SMOOTH_HINT));
            assertEquals(GL11.GL_DONT_CARE, GLSMQueries.glGetInteger(GL11.GL_LINE_SMOOTH_HINT));
            assertEquals((float) GL11.GL_NICEST, GLSMQueries.glGetFloat(GL11.GL_FOG_HINT));
            assertTrue(GLSMQueries.glGetBoolean(GL11.GL_FOG_HINT));
            assertDriverAcceptedLastCalls();
        } finally {
            GLSMRasterizer.glHint(GL11.GL_FOG_HINT, GL11.GL_DONT_CARE);
            GLSMRasterizer.glHint(GL11.GL_POINT_SMOOTH_HINT, GL11.GL_DONT_CARE);
        }
    }

    @Test
    void hintBitRestoresPackedHints() {
        GLSMRasterizer.glHint(GL11.GL_FOG_HINT, GL11.GL_NICEST);
        try {
            GLSMAttribStack.glPushAttrib(GL11.GL_HINT_BIT);
            try {
                GLSMRasterizer.glHint(GL11.GL_FOG_HINT, GL11.GL_FASTEST);
                GLSMRasterizer.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
                assertEquals(GL11.GL_FASTEST, GLSMQueries.glGetInteger(GL11.GL_FOG_HINT));
            } finally {
                GLSMAttribStack.glPopAttrib();
            }
            assertEquals(GL11.GL_NICEST, GLSMQueries.glGetInteger(GL11.GL_FOG_HINT));
            assertEquals(GL11.GL_DONT_CARE, GLSMQueries.glGetInteger(GL11.GL_LINE_SMOOTH_HINT));
            assertDriverAcceptedLastCalls();
        } finally {
            GLSMRasterizer.glHint(GL11.GL_FOG_HINT, GL11.GL_DONT_CARE);
        }
    }

    @Test
    void pointParametersRoundTripAndRespectBufferPosition() {
        GLSMRasterizer.glPointParameterf(GL14.GL_POINT_SIZE_MIN, 2.0f);
        GLSMRasterizer.glPointParameterf(GL14.GL_POINT_SIZE_MAX, 8.0f);
        GLSMRasterizer.glPointParameterf(GL14.GL_POINT_FADE_THRESHOLD_SIZE, 0.25f);
        GLSMRasterizer.glPointParameteri(GL20.GL_POINT_SPRITE_COORD_ORIGIN, GL20.GL_LOWER_LEFT);
        final FloatBuffer attenuation = BufferUtils.createFloatBuffer(4);
        attenuation.put(1, 0.5f);
        attenuation.put(2, 0.25f);
        attenuation.put(3, 0.125f);
        attenuation.position(1);
        GLSMRasterizer.glPointParameter(GL14.GL_POINT_DISTANCE_ATTENUATION, attenuation);

        assertEquals(2.0f, GLSMQueries.glGetFloat(GL14.GL_POINT_SIZE_MIN));
        assertEquals(8.0f, GLSMQueries.glGetFloat(GL14.GL_POINT_SIZE_MAX));
        assertEquals(0.25f, GLSMQueries.glGetFloat(GL14.GL_POINT_FADE_THRESHOLD_SIZE));
        assertEquals(GL20.GL_LOWER_LEFT, GLSMQueries.glGetInteger(GL20.GL_POINT_SPRITE_COORD_ORIGIN));

        final FloatBuffer queried = BufferUtils.createFloatBuffer(6);
        queried.position(2);
        GLSMQueries.glGetFloat(GL14.GL_POINT_DISTANCE_ATTENUATION, queried);
        assertEquals(2, queried.position(), "the query must not move the caller position");
        assertEquals(0.0f, queried.get(0));
        assertEquals(0.0f, queried.get(1));
        assertEquals(0.5f, queried.get(2));
        assertEquals(0.25f, queried.get(3));
        assertEquals(0.125f, queried.get(4));

        final DoubleBuffer widened = BufferUtils.createDoubleBuffer(5);
        widened.position(1);
        GLSMQueries.glGetDouble(GL14.GL_POINT_DISTANCE_ATTENUATION, widened);
        assertEquals(0.5, widened.get(1), 1e-6);

        final FloatBuffer driverThreshold = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloatv(GL14.GL_POINT_FADE_THRESHOLD_SIZE, driverThreshold);
        assertEquals(0.25f, driverThreshold.get(0), "the tracked fade threshold must reach the driver");
        assertDriverAcceptedLastCalls();
    }

    @Test
    void pointBitRestoresPointParameters() {
        GLSMRasterizer.glPointParameterf(GL14.GL_POINT_FADE_THRESHOLD_SIZE, 0.25f);
        GLSMAttribStack.glPushAttrib(GL11.GL_POINT_BIT);
        try {
            GLSMRasterizer.glPointParameterf(GL14.GL_POINT_FADE_THRESHOLD_SIZE, 0.75f);
            GLSMRasterizer.glPointParameterf(GL14.GL_POINT_SIZE_MAX, 6.0f);
            GLSMRasterizer.glPointParameteri(GL20.GL_POINT_SPRITE_COORD_ORIGIN, GL20.GL_LOWER_LEFT);
            assertEquals(0.75f, GLSMQueries.glGetFloat(GL14.GL_POINT_FADE_THRESHOLD_SIZE));
        } finally {
            GLSMAttribStack.glPopAttrib();
        }
        assertEquals(0.25f, GLSMQueries.glGetFloat(GL14.GL_POINT_FADE_THRESHOLD_SIZE));
        assertEquals(GL20.GL_UPPER_LEFT, GLSMQueries.glGetInteger(GL20.GL_POINT_SPRITE_COORD_ORIGIN));
        final FloatBuffer driverThreshold = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloatv(GL14.GL_POINT_FADE_THRESHOLD_SIZE, driverThreshold);
        assertEquals(0.25f, driverThreshold.get(0), "the restored fade threshold must reach the driver");
        assertDriverAcceptedLastCalls();
    }

    @Test
    void edgeFlagIsTrackedAndRestoredByCurrentBit() {
        assertTrue(GLSMQueries.glGetBoolean(GL11.GL_EDGE_FLAG));
        GLSMAttribStack.glPushAttrib(GL11.GL_CURRENT_BIT);
        try {
            GLSMCurrentAttribs.glEdgeFlag(false);
            assertFalse(GLSMQueries.glGetBoolean(GL11.GL_EDGE_FLAG));
        } finally {
            GLSMAttribStack.glPopAttrib();
        }
        assertTrue(GLSMQueries.glGetBoolean(GL11.GL_EDGE_FLAG));

        GLSMCurrentAttribs.glEdgeFlag(false);
        final ByteBuffer buffer = BufferUtils.createByteBuffer(3);
        buffer.position(1);
        GLSMQueries.glGetBoolean(GL11.GL_EDGE_FLAG, buffer);
        assertEquals(1, buffer.position());
        assertEquals(0, buffer.get(0));
        assertEquals(GL11.GL_FALSE, buffer.get(1));
        assertDriverAcceptedLastCalls();
    }

    @Test
    void enableCapsAnswerPerTextureUnit() {
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE1);
        GLSMEnable.glEnable(GL13.GL_TEXTURE_CUBE_MAP);
        GLSMEnable.glEnable(GL20.GL_POINT_SPRITE);
        GLSMEnable.glEnable(GL20.GL_VERTEX_PROGRAM_TWO_SIDE);
        try {
            assertTrue(GLSMEnable.glIsEnabled(GL13.GL_TEXTURE_CUBE_MAP));
            assertTrue(GLSMQueries.glGetBoolean(GL13.GL_TEXTURE_CUBE_MAP));
            assertEquals(GL11.GL_TRUE, GLSMQueries.glGetInteger(GL13.GL_TEXTURE_CUBE_MAP));
            assertTrue(GLSMEnable.glIsEnabled(GL20.GL_POINT_SPRITE));
            assertTrue(GLSMEnable.glIsEnabled(GL20.GL_VERTEX_PROGRAM_TWO_SIDE));

            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            assertFalse(GLSMEnable.glIsEnabled(GL13.GL_TEXTURE_CUBE_MAP), "texture target enables are per unit");
            assertEquals(GL11.GL_FALSE, GLSMQueries.glGetInteger(GL13.GL_TEXTURE_CUBE_MAP));
        } finally {
            GLSMEnable.glDisable(GL13.GL_TEXTURE_CUBE_MAP);
            GLSMEnable.glDisable(GL20.GL_POINT_SPRITE);
            GLSMEnable.glDisable(GL20.GL_VERTEX_PROGRAM_TWO_SIDE);
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        }
        assertDriverAcceptedLastCalls();
    }

    @Test
    void matrixAndIntegerQueriesWriteAtCallerPosition() {
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMMatrices.glLoadIdentity();

        final FloatBuffer matrix = BufferUtils.createFloatBuffer(20);
        matrix.position(3);
        GLSMQueries.glGetFloat(GL11.GL_MODELVIEW_MATRIX, matrix);
        assertEquals(3, matrix.position(), "the query must not move the caller position");
        assertEquals(0.0f, matrix.get(0));
        assertEquals(1.0f, matrix.get(3));
        assertEquals(0.0f, matrix.get(4));
        assertEquals(1.0f, matrix.get(18));

        GLSMTextures.glClientActiveTexture(GL13.GL_TEXTURE1);
        final IntBuffer activeUnit = BufferUtils.createIntBuffer(4);
        activeUnit.position(2);
        GLSMQueries.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE, activeUnit);
        assertEquals(2, activeUnit.position());
        assertEquals(0, activeUnit.get(0));
        assertEquals(GL13.GL_TEXTURE0 + 1, activeUnit.get(2));
        assertDriverAcceptedLastCalls();
    }

    @Test
    void legacyPnamesConvertBetweenEntryPoints() {
        final int previousFogMode = GLSMQueries.glGetInteger(GL11.GL_FOG_MODE);
        final int previousShadeModel = GLSMQueries.glGetInteger(GL11.GL_SHADE_MODEL);
        final int previousAlphaFunction = GLSMQueries.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
        final float previousAlphaReference = GLSMQueries.glGetFloat(GL11.GL_ALPHA_TEST_REF);
        GLSMFog.glFogi(GL11.GL_FOG_MODE, GL11.GL_LINEAR);
        GLSMRasterizer.glShadeModel(GL11.GL_FLAT);
        GLSMDepthStencilAlpha.glAlphaFunc(GL11.GL_GREATER, 0.5f);
        try {
            assertEquals(GL11.GL_LINEAR, GLSMQueries.glGetInteger(GL11.GL_FOG_MODE));
            assertEquals((float) GL11.GL_LINEAR, GLSMQueries.glGetFloat(GL11.GL_FOG_MODE));
            assertTrue(GLSMQueries.glGetBoolean(GL11.GL_FOG_MODE));

            assertEquals(GL11.GL_FLAT, GLSMQueries.glGetInteger(GL11.GL_SHADE_MODEL));
            assertEquals((float) GL11.GL_FLAT, GLSMQueries.glGetFloat(GL11.GL_SHADE_MODEL));

            assertEquals(0.5f, GLSMQueries.glGetFloat(GL11.GL_ALPHA_TEST_REF));
            final int fixedPoint = GLSMQueries.glGetInteger(GL11.GL_ALPHA_TEST_REF);
            assertEquals(0.5f, fixedPoint / FIXED_POINT_SCALE, 1e-6f, "the integer flavour encodes the reference at fixed-point scale");
            assertDriverAcceptedLastCalls();
        } finally {
            GLSMFog.glFogi(GL11.GL_FOG_MODE, previousFogMode);
            GLSMRasterizer.glShadeModel(previousShadeModel);
            GLSMDepthStencilAlpha.glAlphaFunc(previousAlphaFunction, previousAlphaReference);
        }
    }

    @Test
    void currentTextureCoordsFollowTheActiveUnit() {
        GLSMAttribStack.glPushAttrib(GL11.GL_CURRENT_BIT);
        try {
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
            GLSMCurrentAttribs.glTexCoord4f(0.125f, 0.25f, 0.5f, 0.75f);
            final FloatBuffer unitZero = BufferUtils.createFloatBuffer(5);
            unitZero.position(1);
            GLSMQueries.glGetFloat(GL11.GL_CURRENT_TEXTURE_COORDS, unitZero);
            assertEquals(0.125f, unitZero.get(1));
            assertEquals(0.25f, unitZero.get(2));
            assertEquals(0.5f, unitZero.get(3));
            assertEquals(0.75f, unitZero.get(4));

            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE2);
            GLSMCurrentAttribs.glMultiTexCoord2f(GL13.GL_TEXTURE2, 0.5f, 0.625f);
            final float[] unitTwo = new float[4];
            GLSMQueries.glGetFloat(GL11.GL_CURRENT_TEXTURE_COORDS, unitTwo);
            assertEquals(0.5f, unitTwo[0]);
            assertEquals(0.625f, unitTwo[1]);
            assertEquals(0.0f, unitTwo[2]);
            assertEquals(1.0f, unitTwo[3]);
            assertDriverAcceptedLastCalls();
        } finally {
            GLSMAttribStack.glPopAttrib();
        }
    }

    @Test
    void renderModeQueryReportsTheSoftwareMode() {
        assertEquals(GL11.GL_RENDER, GLSMQueries.glGetInteger(GL11.GL_RENDER_MODE));
        GLSMDraws.glRenderMode(GL11.GL_FEEDBACK);
        try {
            assertEquals(GL11.GL_FEEDBACK, GLSMQueries.glGetInteger(GL11.GL_RENDER_MODE));
        } finally {
            GLSMDraws.glRenderMode(GL11.GL_RENDER);
        }
        assertEquals(GL11.GL_RENDER, GLSMQueries.glGetInteger(GL11.GL_RENDER_MODE));
        assertDriverAcceptedLastCalls();
    }

    @Test
    void lightAndMaterialIntQueriesConvertTrackedValues() {
        GLSMAttribStack.glPushAttrib(GL11.GL_LIGHTING_BIT);
        try {
            final FloatBuffer diffuse = BufferUtils.createFloatBuffer(4);
            diffuse.put(0.25f).put(0.5f).put(0.75f).put(1.0f).flip();
            GLSMLighting.glMaterial(GL11.GL_FRONT, GL11.GL_DIFFUSE, diffuse);
            GLSMLighting.glMaterialf(GL11.GL_FRONT, GL11.GL_SHININESS, 64.0f);
            final FloatBuffer position = BufferUtils.createFloatBuffer(4);
            position.put(1.0f).put(2.0f).put(3.0f).put(0.0f).flip();
            GLSMLighting.glLight(GL11.GL_LIGHT0, GL11.GL_POSITION, position);

            final IntBuffer material = BufferUtils.createIntBuffer(6);
            material.position(2);
            GLSMLighting.glGetMaterial(GL11.GL_FRONT, GL11.GL_DIFFUSE, material);
            assertEquals(2, material.position());
            assertEquals(0.25f, material.get(2) / FIXED_POINT_SCALE, 1e-6f);
            assertEquals(0.5f, material.get(3) / FIXED_POINT_SCALE, 1e-6f);
            assertEquals(0.75f, material.get(4) / FIXED_POINT_SCALE, 1e-6f);

            final IntBuffer shininess = BufferUtils.createIntBuffer(2);
            GLSMLighting.glGetMaterial(GL11.GL_FRONT, GL11.GL_SHININESS, shininess);
            assertEquals(64, shininess.get(0));

            final IntBuffer lightPosition = BufferUtils.createIntBuffer(5);
            lightPosition.position(1);
            GLSMLighting.glGetLight(GL11.GL_LIGHT0, GL11.GL_POSITION, lightPosition);
            assertEquals(1, lightPosition.get(1));
            assertEquals(2, lightPosition.get(2));
            assertEquals(3, lightPosition.get(3));
            assertEquals(0, lightPosition.get(4));

            final FloatBuffer floatMaterial = BufferUtils.createFloatBuffer(6);
            floatMaterial.position(2);
            GLSMLighting.glGetMaterial(GL11.GL_FRONT, GL11.GL_DIFFUSE, floatMaterial);
            assertEquals(0.25f, floatMaterial.get(2));
            assertEquals(0.75f, floatMaterial.get(4));
            assertDriverAcceptedLastCalls();
        } finally {
            GLSMAttribStack.glPopAttrib();
        }
    }

    @Test
    void attribStackDepthFollowsPushesAndCompiledStateScopes() {
        final int base = GLSMQueries.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH);
        GLSMAttribStack.glPushAttrib(GL11.GL_ENABLE_BIT);
        try {
            assertEquals(base + 1, GLSMQueries.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH));
        } finally {
            GLSMAttribStack.glPopAttrib();
        }
        assertEquals(base, GLSMQueries.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH));

        final int list = GLSMDraws.glGenLists(1);
        try {
            GLSMDraws.glNewList(list, GL11.GL_COMPILE);
            try {
                final int token = GLStateManager.pushState(StateSet.BLEND);
                assertEquals(base + 1, GLSMQueries.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH), "a query inside a compiled list reports the depth playback will see");
                GLStateManager.popStateTo(token);
                assertEquals(base, GLSMQueries.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH));
            } finally {
                if (DisplayListManager.isRecording()) {
                    GLSMDraws.glEndList();
                }
            }
            assertEquals(base, GLSMQueries.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH), "recording must not move the live attrib stack");
        } finally {
            GLSMDraws.glDeleteLists(list, 1);
        }
        assertDriverAcceptedLastCalls();
    }

    @Test
    void depthAndStencilBitsAnswerTheDrawFramebufferAttachments() {
        final int driverDepthBits = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL11.GL_DEPTH, GL30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE);
        final int driverStencilBits = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL11.GL_STENCIL, GL30.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE);
        assertEquals(driverDepthBits, GLSMQueries.glGetInteger(GL11.GL_DEPTH_BITS), "the default framebuffer answers the depth size the driver reports");
        assertEquals(driverStencilBits, GLSMQueries.glGetInteger(GL11.GL_STENCIL_BITS), "the default framebuffer answers the stencil size the driver reports");

        final int depthStencilFramebuffer = GLSMFramebuffer.glGenFramebuffers();
        final int colorBuffer = GLSMFramebuffer.glGenRenderbuffers();
        final int depthStencilBuffer = GLSMFramebuffer.glGenRenderbuffers();
        try {
            GLStateManager.glBindRenderbuffer(GL30.GL_RENDERBUFFER, colorBuffer);
            GLStateManager.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, WINDOW_WIDTH, WINDOW_HEIGHT);
            GLStateManager.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthStencilBuffer);
            GLStateManager.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, WINDOW_WIDTH, WINDOW_HEIGHT);
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, depthStencilFramebuffer);
            GLSMFramebuffer.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, colorBuffer);
            GLSMFramebuffer.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, depthStencilBuffer);
            assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, GLSMFramebuffer.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER));

            assertEquals(24, GLSMQueries.glGetInteger(GL11.GL_DEPTH_BITS), "the depth-stencil attachment reports its depth size");
            assertEquals(8, GLSMQueries.glGetInteger(GL11.GL_STENCIL_BITS), "the depth-stencil attachment reports its stencil size");
        } finally {
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GLSMFramebuffer.glDeleteFramebuffers(depthStencilFramebuffer);
            GLStateManager.glDeleteRenderbuffers(colorBuffer);
            GLStateManager.glDeleteRenderbuffers(depthStencilBuffer);
        }

        final int colorOnlyFramebuffer = GLSMFramebuffer.glGenFramebuffers();
        final int colorOnlyBuffer = GLSMFramebuffer.glGenRenderbuffers();
        try {
            GLStateManager.glBindRenderbuffer(GL30.GL_RENDERBUFFER, colorOnlyBuffer);
            GLStateManager.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, WINDOW_WIDTH, WINDOW_HEIGHT);
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, colorOnlyFramebuffer);
            GLSMFramebuffer.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, colorOnlyBuffer);
            assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, GLSMFramebuffer.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER));

            assertEquals(0, GLSMQueries.glGetInteger(GL11.GL_DEPTH_BITS), "a framebuffer without a depth attachment reports no depth bits");
            assertEquals(0, GLSMQueries.glGetInteger(GL11.GL_STENCIL_BITS), "a framebuffer without a stencil attachment reports no stencil bits");
        } finally {
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GLSMFramebuffer.glDeleteFramebuffers(colorOnlyFramebuffer);
            GLStateManager.glDeleteRenderbuffers(colorOnlyBuffer);
        }
        assertDriverAcceptedLastCalls();
    }

    @Test
    void integerArrayQueriesOnlyWriteTheElementsTheyProduce() {
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMMatrices.glLoadIdentity();

        final int[] previousViewport = new int[4];
        GLSMQueries.glGetInteger(GL11.GL_VIEWPORT, previousViewport);
        GLSMRasterizer.glViewport(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT);
        try {
            final int[] viewport = {RESIDUE, RESIDUE, RESIDUE, RESIDUE};
            GLSMQueries.glGetInteger(GL11.GL_VIEWPORT, viewport);
            assertEquals(0, viewport[0]);
            assertEquals(0, viewport[1]);
            assertEquals(WINDOW_WIDTH, viewport[2], "GL_VIEWPORT fills all four elements");
            assertEquals(WINDOW_HEIGHT, viewport[3]);
        } finally {
            GLSMRasterizer.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
        }

        final int[] scalar = {RESIDUE, RESIDUE};
        GLSMQueries.glGetInteger(GL11.GL_CULL_FACE_MODE, scalar);
        assertEquals(GLSMQueries.glGetInteger(GL11.GL_CULL_FACE_MODE), scalar[0]);
        assertEquals(RESIDUE, scalar[1], "a single-valued pname writes one element and leaves the array tail alone");

        GLSMAttribStack.glPushAttrib(GL11.GL_CURRENT_BIT);
        try {
            GLSMCurrentAttribs.glColor4f(0.5f, 0.25f, 0.0f, 1.0f);
            final int[] color = {RESIDUE, RESIDUE, RESIDUE, RESIDUE, RESIDUE, RESIDUE};
            GLSMQueries.glGetInteger(GL11.GL_CURRENT_COLOR, color);
            assertEquals(fixedPoint(0.5f), color[0]);
            assertEquals(fixedPoint(0.25f), color[1]);
            assertEquals(0, color[2]);
            assertEquals(fixedPoint(1.0f), color[3]);
            assertEquals(RESIDUE, color[4], "the array tail keeps the caller's value instead of an earlier query's");
            assertEquals(RESIDUE, color[5]);
        } finally {
            GLSMAttribStack.glPopAttrib();
        }

        final FloatBuffer attenuation = BufferUtils.createFloatBuffer(3);
        attenuation.put(3.0f).put(5.0f).put(7.0f).flip();
        GLSMRasterizer.glPointParameter(GL14.GL_POINT_DISTANCE_ATTENUATION, attenuation);
        try {
            final int[] shortArray = {RESIDUE, RESIDUE};
            GLSMQueries.glGetInteger(GL14.GL_POINT_DISTANCE_ATTENUATION, shortArray);
            assertEquals(3, shortArray[0]);
            assertEquals(5, shortArray[1], "a short array receives only the elements that fit");
        } finally {
            attenuation.clear();
            attenuation.put(1.0f).put(0.0f).put(0.0f).flip();
            GLSMRasterizer.glPointParameter(GL14.GL_POINT_DISTANCE_ATTENUATION, attenuation);
        }
        assertDriverAcceptedLastCalls();
    }

    private static int fixedPoint(float value) {
        return (int) (2147483647.0 * value);
    }

    private static void assertDriverAcceptedLastCalls() {
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "the driver received a legacy query it rejects");
    }

    private static void drainDriverErrors() {
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {
            // Drain stale errors so each assertion only sees the calls made by the test.
        }
    }
}