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
        GLStateManager.glMatrixMode(GL11.GL_MODELVIEW);
        GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);
        GLStateManager.glClientActiveTexture(GL13.GL_TEXTURE0);
        GLStateManager.glEdgeFlag(true);
        GLStateManager.glPointParameterf(GL14.GL_POINT_FADE_THRESHOLD_SIZE, 1.0f);
        GLStateManager.glPointParameteri(GL20.GL_POINT_SPRITE_COORD_ORIGIN, GL20.GL_UPPER_LEFT);
        GLStateManager.glRenderMode(GL11.GL_RENDER);
        drainDriverErrors();
    }

    @Test
    void stackDepthsFollowMatrixPushes() {
        final int modelViewDepth = GLStateManager.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH);
        GLStateManager.glMatrixMode(GL11.GL_MODELVIEW);
        GLStateManager.glPushMatrix();
        assertEquals(modelViewDepth + 1, GLStateManager.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH));
        GLStateManager.glPopMatrix();
        assertEquals(modelViewDepth, GLStateManager.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH));

        GLStateManager.glMatrixMode(GL11.GL_PROJECTION);
        final int projectionDepth = GLStateManager.glGetInteger(GL11.GL_PROJECTION_STACK_DEPTH);
        GLStateManager.glPushMatrix();
        assertEquals(projectionDepth + 1, GLStateManager.glGetInteger(GL11.GL_PROJECTION_STACK_DEPTH));
        GLStateManager.glPopMatrix();

        GLStateManager.glMatrixMode(GL11.GL_COLOR);
        final int colorDepth = GLStateManager.glGetInteger(ARBImaging.GL_COLOR_MATRIX_STACK_DEPTH);
        GLStateManager.glPushMatrix();
        assertEquals(colorDepth + 1, GLStateManager.glGetInteger(ARBImaging.GL_COLOR_MATRIX_STACK_DEPTH));
        GLStateManager.glPopMatrix();

        final int maxModelViewDepth = GLStateManager.glGetInteger(GL11.GL_MAX_MODELVIEW_STACK_DEPTH);
        final int maxProjectionDepth = GLStateManager.glGetInteger(GL11.GL_MAX_PROJECTION_STACK_DEPTH);
        assertTrue(maxModelViewDepth > modelViewDepth, "GL_MAX_MODELVIEW_STACK_DEPTH must cover the base matrix");
        assertTrue(maxProjectionDepth > projectionDepth, "GL_MAX_PROJECTION_STACK_DEPTH must cover the base matrix");
        assertDriverAcceptedLastCalls();
    }

    @Test
    void textureStackDepthFollowsActiveUnit() {
        GLStateManager.glMatrixMode(GL11.GL_TEXTURE);
        try {
            GLStateManager.glActiveTexture(GL13.GL_TEXTURE2);
            final int unitTwoDepth = GLStateManager.glGetInteger(GL11.GL_TEXTURE_STACK_DEPTH);
            GLStateManager.glPushMatrix();
            assertEquals(unitTwoDepth + 1, GLStateManager.glGetInteger(GL11.GL_TEXTURE_STACK_DEPTH));

            GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);
            final int unitZeroDepth = GLStateManager.glGetInteger(GL11.GL_TEXTURE_STACK_DEPTH);
            GLStateManager.glPushMatrix();
            assertEquals(unitZeroDepth + 1, GLStateManager.glGetInteger(GL11.GL_TEXTURE_STACK_DEPTH));

            GLStateManager.glActiveTexture(GL13.GL_TEXTURE2);
            assertEquals(unitTwoDepth + 1, GLStateManager.glGetInteger(GL11.GL_TEXTURE_STACK_DEPTH), "each unit keeps its own texture matrix stack");
            GLStateManager.glPopMatrix();
            assertEquals(unitTwoDepth, GLStateManager.glGetInteger(GL11.GL_TEXTURE_STACK_DEPTH));

            GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);
            assertEquals(unitZeroDepth + 1, GLStateManager.glGetInteger(GL11.GL_TEXTURE_STACK_DEPTH));
            GLStateManager.glPopMatrix();
        } finally {
            GLStateManager.glMatrixMode(GL11.GL_MODELVIEW);
            GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);
        }
        assertDriverAcceptedLastCalls();
    }

    @Test
    void maxTextureCoordsBoundsClientActiveTexture() {
        final int maxTextureCoords = GLStateManager.glGetInteger(GL20.GL_MAX_TEXTURE_COORDS);
        final int maxTextureUnits = GLStateManager.glGetInteger(GL13.GL_MAX_TEXTURE_UNITS);
        assertTrue(maxTextureUnits >= 2, "fixed-function emulation needs at least the base and lightmap UV units");
        assertTrue(maxTextureCoords >= maxTextureUnits,
            "every fixed-function texture unit must also have a client texture coordinate slot");

        GLStateManager.glClientActiveTexture(GL13.GL_TEXTURE0 + maxTextureCoords - 1);
        assertEquals(maxTextureCoords - 1, GLStateManager.getClientActiveTextureUnit());
        assertEquals(GL13.GL_TEXTURE0 + maxTextureCoords - 1, GLStateManager.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE));

        GLStateManager.glClientActiveTexture(GL13.GL_TEXTURE0 + maxTextureCoords);
        assertEquals(maxTextureCoords - 1, GLStateManager.getClientActiveTextureUnit(), "units past GL_MAX_TEXTURE_COORDS must be rejected");
        assertDriverAcceptedLastCalls();
    }

    @Test
    void legacyLimitsAnswerWithoutTouchingTheDriver() {
        assertTrue(GLStateManager.glGetInteger(GL11.GL_MAX_LIGHTS) >= 1);
        assertTrue(GLStateManager.glGetInteger(GL11.GL_MAX_CLIP_PLANES) >= 1);
        assertTrue(GLStateManager.glGetInteger(GL11.GL_MAX_ATTRIB_STACK_DEPTH) >= 1);
        assertTrue(GLStateManager.glGetInteger(GL11.GL_MAX_CLIENT_ATTRIB_STACK_DEPTH) >= 1);
        assertTrue(GLStateManager.glGetInteger(GL11.GL_MAX_TEXTURE_STACK_DEPTH) >= 2);
        assertTrue(GLStateManager.glGetInteger(ARBImaging.GL_MAX_COLOR_MATRIX_STACK_DEPTH) >= 1);
        assertFalse(Float.isNaN(GLStateManager.glGetFloat(GL14.GL_POINT_SIZE_MAX)), "an unset GL_POINT_SIZE_MAX reports the driver range");
        assertTrue(GLStateManager.glGetFloat(GL14.GL_POINT_SIZE_MAX) >= 1.0f);
        assertDriverAcceptedLastCalls();
    }

    @Test
    void packedHintsAnswerIndependently() {
        GLStateManager.glHint(GL11.GL_FOG_HINT, GL11.GL_NICEST);
        GLStateManager.glHint(GL11.GL_POINT_SMOOTH_HINT, GL11.GL_FASTEST);
        try {
            assertEquals(GL11.GL_NICEST, GLStateManager.glGetInteger(GL11.GL_FOG_HINT));
            assertEquals(GL11.GL_FASTEST, GLStateManager.glGetInteger(GL11.GL_POINT_SMOOTH_HINT));
            assertEquals(GL11.GL_DONT_CARE, GLStateManager.glGetInteger(GL11.GL_LINE_SMOOTH_HINT));
            assertEquals((float) GL11.GL_NICEST, GLStateManager.glGetFloat(GL11.GL_FOG_HINT));
            assertTrue(GLStateManager.glGetBoolean(GL11.GL_FOG_HINT));
            assertDriverAcceptedLastCalls();
        } finally {
            GLStateManager.glHint(GL11.GL_FOG_HINT, GL11.GL_DONT_CARE);
            GLStateManager.glHint(GL11.GL_POINT_SMOOTH_HINT, GL11.GL_DONT_CARE);
        }
    }

    @Test
    void hintBitRestoresPackedHints() {
        GLStateManager.glHint(GL11.GL_FOG_HINT, GL11.GL_NICEST);
        try {
            GLStateManager.glPushAttrib(GL11.GL_HINT_BIT);
            try {
                GLStateManager.glHint(GL11.GL_FOG_HINT, GL11.GL_FASTEST);
                GLStateManager.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
                assertEquals(GL11.GL_FASTEST, GLStateManager.glGetInteger(GL11.GL_FOG_HINT));
            } finally {
                GLStateManager.glPopAttrib();
            }
            assertEquals(GL11.GL_NICEST, GLStateManager.glGetInteger(GL11.GL_FOG_HINT));
            assertEquals(GL11.GL_DONT_CARE, GLStateManager.glGetInteger(GL11.GL_LINE_SMOOTH_HINT));
            assertDriverAcceptedLastCalls();
        } finally {
            GLStateManager.glHint(GL11.GL_FOG_HINT, GL11.GL_DONT_CARE);
        }
    }

    @Test
    void pointParametersRoundTripAndRespectBufferPosition() {
        GLStateManager.glPointParameterf(GL14.GL_POINT_SIZE_MIN, 2.0f);
        GLStateManager.glPointParameterf(GL14.GL_POINT_SIZE_MAX, 8.0f);
        GLStateManager.glPointParameterf(GL14.GL_POINT_FADE_THRESHOLD_SIZE, 0.25f);
        GLStateManager.glPointParameteri(GL20.GL_POINT_SPRITE_COORD_ORIGIN, GL20.GL_LOWER_LEFT);
        final FloatBuffer attenuation = BufferUtils.createFloatBuffer(4);
        attenuation.put(1, 0.5f);
        attenuation.put(2, 0.25f);
        attenuation.put(3, 0.125f);
        attenuation.position(1);
        GLStateManager.glPointParameter(GL14.GL_POINT_DISTANCE_ATTENUATION, attenuation);

        assertEquals(2.0f, GLStateManager.glGetFloat(GL14.GL_POINT_SIZE_MIN));
        assertEquals(8.0f, GLStateManager.glGetFloat(GL14.GL_POINT_SIZE_MAX));
        assertEquals(0.25f, GLStateManager.glGetFloat(GL14.GL_POINT_FADE_THRESHOLD_SIZE));
        assertEquals(GL20.GL_LOWER_LEFT, GLStateManager.glGetInteger(GL20.GL_POINT_SPRITE_COORD_ORIGIN));

        final FloatBuffer queried = BufferUtils.createFloatBuffer(6);
        queried.position(2);
        GLStateManager.glGetFloat(GL14.GL_POINT_DISTANCE_ATTENUATION, queried);
        assertEquals(2, queried.position(), "the query must not move the caller position");
        assertEquals(0.0f, queried.get(0));
        assertEquals(0.0f, queried.get(1));
        assertEquals(0.5f, queried.get(2));
        assertEquals(0.25f, queried.get(3));
        assertEquals(0.125f, queried.get(4));

        final DoubleBuffer widened = BufferUtils.createDoubleBuffer(5);
        widened.position(1);
        GLStateManager.glGetDouble(GL14.GL_POINT_DISTANCE_ATTENUATION, widened);
        assertEquals(0.5, widened.get(1), 1e-6);

        final FloatBuffer driverThreshold = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloatv(GL14.GL_POINT_FADE_THRESHOLD_SIZE, driverThreshold);
        assertEquals(0.25f, driverThreshold.get(0), "the tracked fade threshold must reach the driver");
        assertDriverAcceptedLastCalls();
    }

    @Test
    void pointBitRestoresPointParameters() {
        GLStateManager.glPointParameterf(GL14.GL_POINT_FADE_THRESHOLD_SIZE, 0.25f);
        GLStateManager.glPushAttrib(GL11.GL_POINT_BIT);
        try {
            GLStateManager.glPointParameterf(GL14.GL_POINT_FADE_THRESHOLD_SIZE, 0.75f);
            GLStateManager.glPointParameterf(GL14.GL_POINT_SIZE_MAX, 6.0f);
            GLStateManager.glPointParameteri(GL20.GL_POINT_SPRITE_COORD_ORIGIN, GL20.GL_LOWER_LEFT);
            assertEquals(0.75f, GLStateManager.glGetFloat(GL14.GL_POINT_FADE_THRESHOLD_SIZE));
        } finally {
            GLStateManager.glPopAttrib();
        }
        assertEquals(0.25f, GLStateManager.glGetFloat(GL14.GL_POINT_FADE_THRESHOLD_SIZE));
        assertEquals(GL20.GL_UPPER_LEFT, GLStateManager.glGetInteger(GL20.GL_POINT_SPRITE_COORD_ORIGIN));
        final FloatBuffer driverThreshold = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloatv(GL14.GL_POINT_FADE_THRESHOLD_SIZE, driverThreshold);
        assertEquals(0.25f, driverThreshold.get(0), "the restored fade threshold must reach the driver");
        assertDriverAcceptedLastCalls();
    }

    @Test
    void edgeFlagIsTrackedAndRestoredByCurrentBit() {
        assertTrue(GLStateManager.glGetBoolean(GL11.GL_EDGE_FLAG));
        GLStateManager.glPushAttrib(GL11.GL_CURRENT_BIT);
        try {
            GLStateManager.glEdgeFlag(false);
            assertFalse(GLStateManager.glGetBoolean(GL11.GL_EDGE_FLAG));
        } finally {
            GLStateManager.glPopAttrib();
        }
        assertTrue(GLStateManager.glGetBoolean(GL11.GL_EDGE_FLAG));

        GLStateManager.glEdgeFlag(false);
        final ByteBuffer buffer = BufferUtils.createByteBuffer(3);
        buffer.position(1);
        GLStateManager.glGetBoolean(GL11.GL_EDGE_FLAG, buffer);
        assertEquals(1, buffer.position());
        assertEquals(0, buffer.get(0));
        assertEquals(GL11.GL_FALSE, buffer.get(1));
        assertDriverAcceptedLastCalls();
    }

    @Test
    void enableCapsAnswerPerTextureUnit() {
        GLStateManager.glActiveTexture(GL13.GL_TEXTURE1);
        GLStateManager.glEnable(GL13.GL_TEXTURE_CUBE_MAP);
        GLStateManager.glEnable(GL20.GL_POINT_SPRITE);
        GLStateManager.glEnable(GL20.GL_VERTEX_PROGRAM_TWO_SIDE);
        try {
            assertTrue(GLStateManager.glIsEnabled(GL13.GL_TEXTURE_CUBE_MAP));
            assertTrue(GLStateManager.glGetBoolean(GL13.GL_TEXTURE_CUBE_MAP));
            assertEquals(GL11.GL_TRUE, GLStateManager.glGetInteger(GL13.GL_TEXTURE_CUBE_MAP));
            assertTrue(GLStateManager.glIsEnabled(GL20.GL_POINT_SPRITE));
            assertTrue(GLStateManager.glIsEnabled(GL20.GL_VERTEX_PROGRAM_TWO_SIDE));

            GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);
            assertFalse(GLStateManager.glIsEnabled(GL13.GL_TEXTURE_CUBE_MAP), "texture target enables are per unit");
            assertEquals(GL11.GL_FALSE, GLStateManager.glGetInteger(GL13.GL_TEXTURE_CUBE_MAP));
        } finally {
            GLStateManager.glDisable(GL13.GL_TEXTURE_CUBE_MAP);
            GLStateManager.glDisable(GL20.GL_POINT_SPRITE);
            GLStateManager.glDisable(GL20.GL_VERTEX_PROGRAM_TWO_SIDE);
            GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);
        }
        assertDriverAcceptedLastCalls();
    }

    @Test
    void matrixAndIntegerQueriesWriteAtCallerPosition() {
        GLStateManager.glMatrixMode(GL11.GL_MODELVIEW);
        GLStateManager.glLoadIdentity();

        final FloatBuffer matrix = BufferUtils.createFloatBuffer(20);
        matrix.position(3);
        GLStateManager.glGetFloat(GL11.GL_MODELVIEW_MATRIX, matrix);
        assertEquals(3, matrix.position(), "the query must not move the caller position");
        assertEquals(0.0f, matrix.get(0));
        assertEquals(1.0f, matrix.get(3));
        assertEquals(0.0f, matrix.get(4));
        assertEquals(1.0f, matrix.get(18));

        GLStateManager.glClientActiveTexture(GL13.GL_TEXTURE1);
        final IntBuffer activeUnit = BufferUtils.createIntBuffer(4);
        activeUnit.position(2);
        GLStateManager.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE, activeUnit);
        assertEquals(2, activeUnit.position());
        assertEquals(0, activeUnit.get(0));
        assertEquals(GL13.GL_TEXTURE0 + 1, activeUnit.get(2));
        assertDriverAcceptedLastCalls();
    }

    @Test
    void legacyPnamesConvertBetweenEntryPoints() {
        final int previousFogMode = GLStateManager.glGetInteger(GL11.GL_FOG_MODE);
        final int previousShadeModel = GLStateManager.glGetInteger(GL11.GL_SHADE_MODEL);
        final int previousAlphaFunction = GLStateManager.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
        final float previousAlphaReference = GLStateManager.glGetFloat(GL11.GL_ALPHA_TEST_REF);
        GLStateManager.glFogi(GL11.GL_FOG_MODE, GL11.GL_LINEAR);
        GLStateManager.glShadeModel(GL11.GL_FLAT);
        GLStateManager.glAlphaFunc(GL11.GL_GREATER, 0.5f);
        try {
            assertEquals(GL11.GL_LINEAR, GLStateManager.glGetInteger(GL11.GL_FOG_MODE));
            assertEquals((float) GL11.GL_LINEAR, GLStateManager.glGetFloat(GL11.GL_FOG_MODE));
            assertTrue(GLStateManager.glGetBoolean(GL11.GL_FOG_MODE));

            assertEquals(GL11.GL_FLAT, GLStateManager.glGetInteger(GL11.GL_SHADE_MODEL));
            assertEquals((float) GL11.GL_FLAT, GLStateManager.glGetFloat(GL11.GL_SHADE_MODEL));

            assertEquals(0.5f, GLStateManager.glGetFloat(GL11.GL_ALPHA_TEST_REF));
            final int fixedPoint = GLStateManager.glGetInteger(GL11.GL_ALPHA_TEST_REF);
            assertEquals(0.5f, fixedPoint / FIXED_POINT_SCALE, 1e-6f, "the integer flavour encodes the reference at fixed-point scale");
            assertDriverAcceptedLastCalls();
        } finally {
            GLStateManager.glFogi(GL11.GL_FOG_MODE, previousFogMode);
            GLStateManager.glShadeModel(previousShadeModel);
            GLStateManager.glAlphaFunc(previousAlphaFunction, previousAlphaReference);
        }
    }

    @Test
    void currentTextureCoordsFollowTheActiveUnit() {
        GLStateManager.glPushAttrib(GL11.GL_CURRENT_BIT);
        try {
            GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);
            GLStateManager.glTexCoord4f(0.125f, 0.25f, 0.5f, 0.75f);
            final FloatBuffer unitZero = BufferUtils.createFloatBuffer(5);
            unitZero.position(1);
            GLStateManager.glGetFloat(GL11.GL_CURRENT_TEXTURE_COORDS, unitZero);
            assertEquals(0.125f, unitZero.get(1));
            assertEquals(0.25f, unitZero.get(2));
            assertEquals(0.5f, unitZero.get(3));
            assertEquals(0.75f, unitZero.get(4));

            GLStateManager.glActiveTexture(GL13.GL_TEXTURE2);
            GLStateManager.glMultiTexCoord2f(GL13.GL_TEXTURE2, 0.5f, 0.625f);
            final float[] unitTwo = new float[4];
            GLStateManager.glGetFloat(GL11.GL_CURRENT_TEXTURE_COORDS, unitTwo);
            assertEquals(0.5f, unitTwo[0]);
            assertEquals(0.625f, unitTwo[1]);
            assertEquals(0.0f, unitTwo[2]);
            assertEquals(1.0f, unitTwo[3]);
            assertDriverAcceptedLastCalls();
        } finally {
            GLStateManager.glPopAttrib();
        }
    }

    @Test
    void renderModeQueryReportsTheSoftwareMode() {
        assertEquals(GL11.GL_RENDER, GLStateManager.glGetInteger(GL11.GL_RENDER_MODE));
        GLStateManager.glRenderMode(GL11.GL_FEEDBACK);
        try {
            assertEquals(GL11.GL_FEEDBACK, GLStateManager.glGetInteger(GL11.GL_RENDER_MODE));
        } finally {
            GLStateManager.glRenderMode(GL11.GL_RENDER);
        }
        assertEquals(GL11.GL_RENDER, GLStateManager.glGetInteger(GL11.GL_RENDER_MODE));
        assertDriverAcceptedLastCalls();
    }

    @Test
    void lightAndMaterialIntQueriesConvertTrackedValues() {
        GLStateManager.glPushAttrib(GL11.GL_LIGHTING_BIT);
        try {
            final FloatBuffer diffuse = BufferUtils.createFloatBuffer(4);
            diffuse.put(0.25f).put(0.5f).put(0.75f).put(1.0f).flip();
            GLStateManager.glMaterial(GL11.GL_FRONT, GL11.GL_DIFFUSE, diffuse);
            GLStateManager.glMaterialf(GL11.GL_FRONT, GL11.GL_SHININESS, 64.0f);
            final FloatBuffer position = BufferUtils.createFloatBuffer(4);
            position.put(1.0f).put(2.0f).put(3.0f).put(0.0f).flip();
            GLStateManager.glLight(GL11.GL_LIGHT0, GL11.GL_POSITION, position);

            final IntBuffer material = BufferUtils.createIntBuffer(6);
            material.position(2);
            GLStateManager.glGetMaterial(GL11.GL_FRONT, GL11.GL_DIFFUSE, material);
            assertEquals(2, material.position());
            assertEquals(0.25f, material.get(2) / FIXED_POINT_SCALE, 1e-6f);
            assertEquals(0.5f, material.get(3) / FIXED_POINT_SCALE, 1e-6f);
            assertEquals(0.75f, material.get(4) / FIXED_POINT_SCALE, 1e-6f);

            final IntBuffer shininess = BufferUtils.createIntBuffer(2);
            GLStateManager.glGetMaterial(GL11.GL_FRONT, GL11.GL_SHININESS, shininess);
            assertEquals(64, shininess.get(0));

            final IntBuffer lightPosition = BufferUtils.createIntBuffer(5);
            lightPosition.position(1);
            GLStateManager.glGetLight(GL11.GL_LIGHT0, GL11.GL_POSITION, lightPosition);
            assertEquals(1, lightPosition.get(1));
            assertEquals(2, lightPosition.get(2));
            assertEquals(3, lightPosition.get(3));
            assertEquals(0, lightPosition.get(4));

            final FloatBuffer floatMaterial = BufferUtils.createFloatBuffer(6);
            floatMaterial.position(2);
            GLStateManager.glGetMaterial(GL11.GL_FRONT, GL11.GL_DIFFUSE, floatMaterial);
            assertEquals(0.25f, floatMaterial.get(2));
            assertEquals(0.75f, floatMaterial.get(4));
            assertDriverAcceptedLastCalls();
        } finally {
            GLStateManager.glPopAttrib();
        }
    }

    @Test
    void attribStackDepthFollowsPushesAndCompiledStateScopes() {
        final int base = GLStateManager.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH);
        GLStateManager.glPushAttrib(GL11.GL_ENABLE_BIT);
        try {
            assertEquals(base + 1, GLStateManager.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH));
        } finally {
            GLStateManager.glPopAttrib();
        }
        assertEquals(base, GLStateManager.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH));

        final int list = GLStateManager.glGenLists(1);
        try {
            GLStateManager.glNewList(list, GL11.GL_COMPILE);
            try {
                final int token = GLStateManager.pushState(StateSet.BLEND);
                assertEquals(base + 1, GLStateManager.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH), "a query inside a compiled list reports the depth playback will see");
                GLStateManager.popStateTo(token);
                assertEquals(base, GLStateManager.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH));
            } finally {
                if (DisplayListManager.isRecording()) {
                    GLStateManager.glEndList();
                }
            }
            assertEquals(base, GLStateManager.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH), "recording must not move the live attrib stack");
        } finally {
            GLStateManager.glDeleteLists(list, 1);
        }
        assertDriverAcceptedLastCalls();
    }

    @Test
    void depthAndStencilBitsAnswerTheDrawFramebufferAttachments() {
        final int driverDepthBits = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL11.GL_DEPTH, GL30.GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE);
        final int driverStencilBits = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL11.GL_STENCIL, GL30.GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE);
        assertEquals(driverDepthBits, GLStateManager.glGetInteger(GL11.GL_DEPTH_BITS), "the default framebuffer answers the depth size the driver reports");
        assertEquals(driverStencilBits, GLStateManager.glGetInteger(GL11.GL_STENCIL_BITS), "the default framebuffer answers the stencil size the driver reports");

        final int depthStencilFramebuffer = GLStateManager.glGenFramebuffers();
        final int colorBuffer = GLStateManager.glGenRenderbuffers();
        final int depthStencilBuffer = GLStateManager.glGenRenderbuffers();
        try {
            GLStateManager.glBindRenderbuffer(GL30.GL_RENDERBUFFER, colorBuffer);
            GLStateManager.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, WINDOW_WIDTH, WINDOW_HEIGHT);
            GLStateManager.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthStencilBuffer);
            GLStateManager.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL30.GL_DEPTH24_STENCIL8, WINDOW_WIDTH, WINDOW_HEIGHT);
            GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, depthStencilFramebuffer);
            GLStateManager.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, colorBuffer);
            GLStateManager.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_STENCIL_ATTACHMENT, GL30.GL_RENDERBUFFER, depthStencilBuffer);
            assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, GLStateManager.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER));

            assertEquals(24, GLStateManager.glGetInteger(GL11.GL_DEPTH_BITS), "the depth-stencil attachment reports its depth size");
            assertEquals(8, GLStateManager.glGetInteger(GL11.GL_STENCIL_BITS), "the depth-stencil attachment reports its stencil size");
        } finally {
            GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GLStateManager.glDeleteFramebuffers(depthStencilFramebuffer);
            GLStateManager.glDeleteRenderbuffers(colorBuffer);
            GLStateManager.glDeleteRenderbuffers(depthStencilBuffer);
        }

        final int colorOnlyFramebuffer = GLStateManager.glGenFramebuffers();
        final int colorOnlyBuffer = GLStateManager.glGenRenderbuffers();
        try {
            GLStateManager.glBindRenderbuffer(GL30.GL_RENDERBUFFER, colorOnlyBuffer);
            GLStateManager.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, WINDOW_WIDTH, WINDOW_HEIGHT);
            GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, colorOnlyFramebuffer);
            GLStateManager.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, colorOnlyBuffer);
            assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, GLStateManager.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER));

            assertEquals(0, GLStateManager.glGetInteger(GL11.GL_DEPTH_BITS), "a framebuffer without a depth attachment reports no depth bits");
            assertEquals(0, GLStateManager.glGetInteger(GL11.GL_STENCIL_BITS), "a framebuffer without a stencil attachment reports no stencil bits");
        } finally {
            GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GLStateManager.glDeleteFramebuffers(colorOnlyFramebuffer);
            GLStateManager.glDeleteRenderbuffers(colorOnlyBuffer);
        }
        assertDriverAcceptedLastCalls();
    }

    @Test
    void integerArrayQueriesOnlyWriteTheElementsTheyProduce() {
        GLStateManager.glMatrixMode(GL11.GL_MODELVIEW);
        GLStateManager.glLoadIdentity();

        final int[] previousViewport = new int[4];
        GLStateManager.glGetInteger(GL11.GL_VIEWPORT, previousViewport);
        GLStateManager.glViewport(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT);
        try {
            final int[] viewport = {RESIDUE, RESIDUE, RESIDUE, RESIDUE};
            GLStateManager.glGetInteger(GL11.GL_VIEWPORT, viewport);
            assertEquals(0, viewport[0]);
            assertEquals(0, viewport[1]);
            assertEquals(WINDOW_WIDTH, viewport[2], "GL_VIEWPORT fills all four elements");
            assertEquals(WINDOW_HEIGHT, viewport[3]);
        } finally {
            GLStateManager.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
        }

        final int[] scalar = {RESIDUE, RESIDUE};
        GLStateManager.glGetInteger(GL11.GL_CULL_FACE_MODE, scalar);
        assertEquals(GLStateManager.glGetInteger(GL11.GL_CULL_FACE_MODE), scalar[0]);
        assertEquals(RESIDUE, scalar[1], "a single-valued pname writes one element and leaves the array tail alone");

        GLStateManager.glPushAttrib(GL11.GL_CURRENT_BIT);
        try {
            GLStateManager.glColor4f(0.5f, 0.25f, 0.0f, 1.0f);
            final int[] color = {RESIDUE, RESIDUE, RESIDUE, RESIDUE, RESIDUE, RESIDUE};
            GLStateManager.glGetInteger(GL11.GL_CURRENT_COLOR, color);
            assertEquals(fixedPoint(0.5f), color[0]);
            assertEquals(fixedPoint(0.25f), color[1]);
            assertEquals(0, color[2]);
            assertEquals(fixedPoint(1.0f), color[3]);
            assertEquals(RESIDUE, color[4], "the array tail keeps the caller's value instead of an earlier query's");
            assertEquals(RESIDUE, color[5]);
        } finally {
            GLStateManager.glPopAttrib();
        }

        final FloatBuffer attenuation = BufferUtils.createFloatBuffer(3);
        attenuation.put(3.0f).put(5.0f).put(7.0f).flip();
        GLStateManager.glPointParameter(GL14.GL_POINT_DISTANCE_ATTENUATION, attenuation);
        try {
            final int[] shortArray = {RESIDUE, RESIDUE};
            GLStateManager.glGetInteger(GL14.GL_POINT_DISTANCE_ATTENUATION, shortArray);
            assertEquals(3, shortArray[0]);
            assertEquals(5, shortArray[1], "a short array receives only the elements that fit");
        } finally {
            attenuation.clear();
            attenuation.put(1.0f).put(0.0f).put(0.0f).flip();
            GLStateManager.glPointParameter(GL14.GL_POINT_DISTANCE_ATTENUATION, attenuation);
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