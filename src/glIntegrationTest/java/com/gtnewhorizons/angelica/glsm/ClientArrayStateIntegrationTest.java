package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the fixed-function client array entry points against a real context: the query pnames answer the
 * conventional arrays GLSM tracked, a generic attribute shadows the conventional one only while enabled,
 * the driver receives the pointers GLSM decided on, and {@code glPushClientAttrib} restores client array
 * state including the buffer bindings, without ever re-issuing a pointer into a deleted buffer.
 */
class ClientArrayStateIntegrationTest extends GLStateManagerIntegrationFixture {
    private static final int POSITION = 0;
    private static final int UV1 = 3;
    private static final int UV3 = 6;

    @BeforeEach
    void bindDisplay() {
        WindowBackends.useSdl();
        GLStateManager.bindDisplayContext(new Object(), context);
        GLStateManager.recreateDefaultVertexArray();
    }

    @AfterEach
    void resetClientState() {
        GLStateManager.glBindVertexArray(0);
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GLStateManager.glClientActiveTexture(GL13.GL_TEXTURE0);
        GLStateManager.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        GLStateManager.glDisableClientState(GL11.GL_COLOR_ARRAY);
        GLStateManager.glDisableClientState(GL11.GL_NORMAL_ARRAY);
        GLStateManager.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GLStateManager.glDisableClientState(GL11.GL_EDGE_FLAG_ARRAY);
        GLStateManager.glDisableClientState(GL14.GL_FOG_COORDINATE_ARRAY);
        GLStateManager.glDisableClientState(GL14.GL_SECONDARY_COLOR_ARRAY);
        while (GLStateManager.glGetInteger(GL11.GL_CLIENT_ATTRIB_STACK_DEPTH) > 0) {
            GLStateManager.glPopClientAttrib();
        }
        drainDriverErrors();
    }

    @Test
    void clientArrayQueriesAnswerTheTrackedConventionalArray() {
        final int vao = GLStateManager.glGenVertexArrays();
        final ByteBuffer positions = BufferUtils.createByteBuffer(48);
        GLStateManager.glBindVertexArray(vao);
        try {
            GLStateManager.glVertexPointer(3, GL11.GL_FLOAT, 12, positions);
            GLStateManager.glEnableClientState(GL11.GL_VERTEX_ARRAY);

            assertEquals(3, GLStateManager.glGetInteger(GL11.GL_VERTEX_ARRAY_SIZE));
            assertEquals(GL11.GL_FLOAT, GLStateManager.glGetInteger(GL11.GL_VERTEX_ARRAY_TYPE));
            assertEquals(12, GLStateManager.glGetInteger(GL11.GL_VERTEX_ARRAY_STRIDE));
            assertEquals(0, GLStateManager.glGetInteger(GL15.GL_VERTEX_ARRAY_BUFFER_BINDING));
            assertTrue(GLStateManager.glGetBoolean(GL11.GL_VERTEX_ARRAY));
            assertTrue(GLStateManager.glIsEnabled(GL11.GL_VERTEX_ARRAY));
            assertEquals(GL11.GL_TRUE, GLStateManager.glGetInteger(GL11.GL_VERTEX_ARRAY));
            assertEquals(4, GLStateManager.glGetInteger(GL11.GL_COLOR_ARRAY_SIZE), "an unspecified array reports the compatibility default");
            assertTrue(GLStateManager.getVaoManager().hasAnyClientSideEnabledAttrib());

            final ByteBuffer pointer = GLStateManager.glGetPointer(GL11.GL_VERTEX_ARRAY_POINTER, 48);
            assertNotNull(pointer, "a client-side array reports the address GLSM captured for it");
            assertEquals(MemoryUtilities.memAddress(positions), MemoryUtilities.memAddress(pointer));

            final int vbo = GLStateManager.glGenBuffers();
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, 64L, GL15.GL_STATIC_DRAW);
            GLStateManager.glVertexPointer(2, GL11.GL_SHORT, 8, 4L);

            assertEquals(vbo, GLStateManager.glGetInteger(GL15.GL_VERTEX_ARRAY_BUFFER_BINDING));
            assertEquals(2, GLStateManager.glGetInteger(GL11.GL_VERTEX_ARRAY_SIZE));
            assertEquals(GL11.GL_SHORT, GLStateManager.glGetInteger(GL11.GL_VERTEX_ARRAY_TYPE));
            assertEquals(8, GLStateManager.glGetInteger(GL11.GL_VERTEX_ARRAY_STRIDE));
            assertEquals(vbo, GL20.glGetVertexAttribi(POSITION, GL20.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING));
            assertEquals(2, GL20.glGetVertexAttribi(POSITION, GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE));
            assertEquals(8, GL20.glGetVertexAttribi(POSITION, GL20.GL_VERTEX_ATTRIB_ARRAY_STRIDE));
            assertEquals(4L, GL20.glGetVertexAttribPointer(POSITION, GL20.GL_VERTEX_ATTRIB_ARRAY_POINTER));
            assertEquals(GL11.GL_TRUE, GL20.glGetVertexAttribi(POSITION, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED));
            assertFalse(GLStateManager.getVaoManager().hasAnyClientSideEnabledAttrib(), "a buffer backed array is not uploaded");
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GLStateManager.glDeleteBuffers(vbo);
        } finally {
            GLStateManager.glBindVertexArray(0);
            GLStateManager.glDeleteVertexArrays(vao);
        }
        drainDriverErrors();
    }

    @Test
    void genericAttributeShadowsTheConventionalArrayUntilItIsDisabled() {
        final int vao = GLStateManager.glGenVertexArrays();
        final int vbo = GLStateManager.glGenBuffers();
        final int conventionalVbo = GLStateManager.glGenBuffers();
        GLStateManager.glBindVertexArray(vao);
        try {
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, conventionalVbo);
            GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, 128L, GL15.GL_STATIC_DRAW);
            GLStateManager.glVertexPointer(3, GL11.GL_FLOAT, 12, 0L);
            GLStateManager.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            assertEquals(12, GL20.glGetVertexAttribi(POSITION, GL20.GL_VERTEX_ATTRIB_ARRAY_STRIDE));
            assertEquals(conventionalVbo, GL20.glGetVertexAttribi(POSITION, GL20.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING));

            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, 128L, GL15.GL_STATIC_DRAW);
            GLStateManager.glVertexAttribPointer(POSITION, 4, GL11.GL_FLOAT, false, 16, 0L);
            GLStateManager.glEnableVertexAttribArray(POSITION);

            assertEquals(16, GL20.glGetVertexAttribi(POSITION, GL20.GL_VERTEX_ATTRIB_ARRAY_STRIDE), "the enabled generic pointer feeds the location");
            assertEquals(4, GL20.glGetVertexAttribi(POSITION, GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE));
            assertEquals(vbo, GL20.glGetVertexAttribi(POSITION, GL20.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING));
            assertEquals(12, GLStateManager.glGetInteger(GL11.GL_VERTEX_ARRAY_STRIDE), "the conventional array keeps its own stride");
            assertEquals(3, GLStateManager.glGetInteger(GL11.GL_VERTEX_ARRAY_SIZE));
            assertEquals(conventionalVbo, GLStateManager.glGetInteger(GL15.GL_VERTEX_ARRAY_BUFFER_BINDING),
                "the conventional pnames keep answering the conventional array");
            assertTrue(GLStateManager.glIsEnabled(GL11.GL_VERTEX_ARRAY));

            GLStateManager.glDisableVertexAttribArray(POSITION);
            assertEquals(12, GL20.glGetVertexAttribi(POSITION, GL20.GL_VERTEX_ATTRIB_ARRAY_STRIDE), "disabling the generic attribute falls back to the conventional array");
            assertEquals(3, GL20.glGetVertexAttribi(POSITION, GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE));
            assertEquals(conventionalVbo, GL20.glGetVertexAttribi(POSITION, GL20.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING));
            assertEquals(GL11.GL_TRUE, GL20.glGetVertexAttribi(POSITION, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED));

            GLStateManager.glDisableClientState(GL11.GL_VERTEX_ARRAY);
            assertEquals(GL11.GL_FALSE, GL20.glGetVertexAttribi(POSITION, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED));
        } finally {
            GLStateManager.glBindVertexArray(0);
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GLStateManager.glDeleteBuffers(vbo);
            GLStateManager.glDeleteBuffers(conventionalVbo);
            GLStateManager.glDeleteVertexArrays(vao);
        }
        drainDriverErrors();
    }

    @Test
    void pushClientAttribRestoresConventionalArraysAndBufferBindings() {
        final int vao = GLStateManager.glGenVertexArrays();
        final int vbo = GLStateManager.glGenBuffers();
        final int ebo = GLStateManager.glGenBuffers();
        final int otherVbo = GLStateManager.glGenBuffers();
        GLStateManager.glBindVertexArray(vao);
        try {
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, 128L, GL15.GL_STATIC_DRAW);
            GLStateManager.glVertexPointer(3, GL11.GL_FLOAT, 12, 0L);
            GLStateManager.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GLStateManager.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
            GLStateManager.glClientActiveTexture(GL13.GL_TEXTURE1);
            GLStateManager.glTexCoordPointer(2, GL11.GL_FLOAT, 8, 0L);
            GLStateManager.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);

            GLStateManager.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
            assertEquals(1, GLStateManager.glGetInteger(GL11.GL_CLIENT_ATTRIB_STACK_DEPTH));

            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, otherVbo);
            GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, 128L, GL15.GL_STATIC_DRAW);
            GLStateManager.glVertexPointer(4, GL11.GL_FLOAT, 16, 0L);
            GLStateManager.glDisableClientState(GL11.GL_VERTEX_ARRAY);
            GLStateManager.glEnableClientState(GL11.GL_COLOR_ARRAY);
            GLStateManager.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            GLStateManager.glClientActiveTexture(GL13.GL_TEXTURE2);

            GLStateManager.glPopClientAttrib();
            assertEquals(0, GLStateManager.glGetInteger(GL11.GL_CLIENT_ATTRIB_STACK_DEPTH));

            assertEquals(3, GLStateManager.glGetInteger(GL11.GL_VERTEX_ARRAY_SIZE));
            assertEquals(12, GLStateManager.glGetInteger(GL11.GL_VERTEX_ARRAY_STRIDE));
            assertTrue(GLStateManager.glIsEnabled(GL11.GL_VERTEX_ARRAY));
            assertFalse(GLStateManager.glIsEnabled(GL11.GL_COLOR_ARRAY), "an array enabled after the push is disabled again");
            assertEquals(3, GL20.glGetVertexAttribi(POSITION, GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE), "the driver got the restored pointer back");
            assertEquals(12, GL20.glGetVertexAttribi(POSITION, GL20.GL_VERTEX_ATTRIB_ARRAY_STRIDE));
            assertEquals(GL11.GL_TRUE, GL20.glGetVertexAttribi(POSITION, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED));
            assertEquals(vbo, GL20.glGetVertexAttribi(POSITION, GL20.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING));
            assertEquals(vbo, GLStateManager.getBoundVBO());
            assertEquals(ebo, GLStateManager.getBoundEBO());
            assertEquals(GL13.GL_TEXTURE0 + 1, GLStateManager.glGetInteger(GL13.GL_CLIENT_ACTIVE_TEXTURE));
            assertEquals(2, GLStateManager.glGetInteger(GL11.GL_TEXTURE_COORD_ARRAY_SIZE), "the restored client active unit answers the texcoord pnames");
            assertEquals(2, GL20.glGetVertexAttribi(UV1, GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE));
        } finally {
            GLStateManager.glBindVertexArray(0);
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GLStateManager.glDeleteBuffers(vbo);
            GLStateManager.glDeleteBuffers(otherVbo);
            GLStateManager.glDeleteBuffers(ebo);
            GLStateManager.glDeleteVertexArrays(vao);
        }
        drainDriverErrors();
    }

    @Test
    void pushWithoutTheVertexArrayBitLeavesClientArraysAlone() {
        final int vao = GLStateManager.glGenVertexArrays();
        final int vbo = GLStateManager.glGenBuffers();
        GLStateManager.glBindVertexArray(vao);
        try {
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, 128L, GL15.GL_STATIC_DRAW);
            GLStateManager.glVertexPointer(3, GL11.GL_FLOAT, 12, 0L);
            GLStateManager.glEnableClientState(GL11.GL_VERTEX_ARRAY);

            GLStateManager.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT);
            GLStateManager.glVertexPointer(4, GL11.GL_FLOAT, 16, 0L);
            GLStateManager.glDisableClientState(GL11.GL_VERTEX_ARRAY);
            GLStateManager.glPopClientAttrib();

            assertEquals(4, GLStateManager.glGetInteger(GL11.GL_VERTEX_ARRAY_SIZE), "a push without the vertex array bit does not restore arrays");
            assertFalse(GLStateManager.glIsEnabled(GL11.GL_VERTEX_ARRAY));
        } finally {
            GLStateManager.glBindVertexArray(0);
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GLStateManager.glDeleteBuffers(vbo);
            GLStateManager.glDeleteVertexArrays(vao);
        }
        drainDriverErrors();
    }

    @Test
    void restoreSkipsPointersOfABufferDeletedWhilePushed() {
        final int vao = GLStateManager.glGenVertexArrays();
        final int savedVbo = GLStateManager.glGenBuffers();
        final int liveVbo = GLStateManager.glGenBuffers();
        GLStateManager.glBindVertexArray(vao);
        try {
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedVbo);
            GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, 128L, GL15.GL_STATIC_DRAW);
            GLStateManager.glVertexPointer(3, GL11.GL_FLOAT, 12, 0L);
            GLStateManager.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GLStateManager.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);

            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, liveVbo);
            GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, 128L, GL15.GL_STATIC_DRAW);
            GLStateManager.glVertexPointer(4, GL11.GL_FLOAT, 16, 0L);

            GLStateManager.glDeleteBuffers(savedVbo);
            GLStateManager.glPopClientAttrib();

            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "restoring a pointer into a deleted buffer must not reach the driver");
            assertEquals(4, GLStateManager.glGetInteger(GL11.GL_VERTEX_ARRAY_SIZE), "the state of the deleted buffer is left alone");
            assertEquals(16, GLStateManager.glGetInteger(GL11.GL_VERTEX_ARRAY_STRIDE));
            assertEquals(liveVbo, GLStateManager.glGetInteger(GL15.GL_VERTEX_ARRAY_BUFFER_BINDING));
            assertEquals(liveVbo, GL20.glGetVertexAttribi(POSITION, GL20.GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING));
            assertEquals(GL11.GL_TRUE, GL20.glGetVertexAttribi(POSITION, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED));
            assertTrue(GL30.glIsVertexArray(vao));
        } finally {
            GLStateManager.glBindVertexArray(0);
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GLStateManager.glDeleteBuffers(liveVbo);
            GLStateManager.glDeleteVertexArrays(vao);
        }
        drainDriverErrors();
    }

    @Test
    void restoreDropsTheSavedVertexArrayOnceItIsDeleted() {
        final int savedVao = GLStateManager.glGenVertexArrays();
        final int liveVao = GLStateManager.glGenVertexArrays();
        GLStateManager.glBindVertexArray(savedVao);
        try {
            GLStateManager.glVertexPointer(3, GL11.GL_FLOAT, 12, BufferUtils.createByteBuffer(48));
            GLStateManager.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GLStateManager.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);

            GLStateManager.glBindVertexArray(liveVao);
            GLStateManager.glDeleteVertexArrays(savedVao);
            GLStateManager.glPopClientAttrib();

            assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "restoring into a deleted vertex array must not reach the driver");
            assertEquals(liveVao, GLStateManager.getBoundVAO(), "the deleted vertex array is not rebound");
            assertFalse(GL30.glIsVertexArray(savedVao));
        } finally {
            GLStateManager.glBindVertexArray(0);
            GLStateManager.glDeleteVertexArrays(liveVao);
        }
        drainDriverErrors();
    }

    @Test
    void edgeFlagFogAndSecondaryColorArraysAnswerQueriesWithoutReachingTheDriver() {
        GLStateManager.glEdgeFlagPointer(4, BufferUtils.createByteBuffer(16));
        GLStateManager.glEnableClientState(GL11.GL_EDGE_FLAG_ARRAY);
        GLStateManager.glFogCoordPointer(GL11.GL_DOUBLE, BufferUtils.createDoubleBuffer(4));
        GLStateManager.glEnableClientState(GL14.GL_FOG_COORDINATE_ARRAY);
        GLStateManager.glSecondaryColorPointer(3, false, 0, BufferUtils.createByteBuffer(12));
        GLStateManager.glEnableClientState(GL14.GL_SECONDARY_COLOR_ARRAY);

        assertEquals(4, GLStateManager.glGetInteger(GL11.GL_EDGE_FLAG_ARRAY_STRIDE));
        assertEquals(GL11.GL_DOUBLE, GLStateManager.glGetInteger(GL14.GL_FOG_COORDINATE_ARRAY_TYPE));
        assertEquals(3, GLStateManager.glGetInteger(GL14.GL_SECONDARY_COLOR_ARRAY_SIZE));
        assertEquals(GL11.GL_BYTE, GLStateManager.glGetInteger(GL14.GL_SECONDARY_COLOR_ARRAY_TYPE));
        assertTrue(GLStateManager.glIsEnabled(GL11.GL_EDGE_FLAG_ARRAY));
        assertTrue(GLStateManager.glGetBoolean(GL14.GL_FOG_COORDINATE_ARRAY));
        assertFalse(GLStateManager.glIsEnabled(GL11.GL_VERTEX_ARRAY));

        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "arrays without an attribute location must not reach the driver");
        drainDriverErrors();
    }

    @Test
    void conventionalClientArraysReachTheDriverThroughTheSharedUpload() {
        final int vao = GLStateManager.glGenVertexArrays();
        final int vbo = GLStateManager.glGenBuffers();
        final ByteBuffer positions = BufferUtils.createByteBuffer(36);
        for (int i = 0; i < positions.capacity(); i++) positions.put(i, (byte) (i + 1));
        ShaderManager.getInstance().enable();
        GLStateManager.glBindVertexArray(vao);
        try {
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, 128L, GL15.GL_STATIC_DRAW);
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GLStateManager.glVertexPointer(3, GL11.GL_FLOAT, 12, positions);
            GLStateManager.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            assertTrue(GLStateManager.getVaoManager().hasAnyClientSideEnabledAttrib());

            GLStateManager.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

            assertEquals(0L, GL20.glGetVertexAttribPointer(POSITION, GL20.GL_VERTEX_ATTRIB_ARRAY_POINTER),
                "the draw uploads the client array into the shared stream buffer");
            assertEquals(3, GL20.glGetVertexAttribi(POSITION, GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE));
            assertEquals(12, GL20.glGetVertexAttribi(POSITION, GL20.GL_VERTEX_ATTRIB_ARRAY_STRIDE));
            assertEquals(3, GLStateManager.glGetInteger(GL11.GL_VERTEX_ARRAY_SIZE), "the upload leaves the tracked conventional array untouched");
        } finally {
            ShaderManager.getInstance().disable();
            GLStateManager.glBindVertexArray(0);
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GLStateManager.glDeleteBuffers(vbo);
            GLStateManager.glDeleteVertexArrays(vao);
        }
        drainDriverErrors();
    }

    @Test
    void texCoordArrayQueriesFollowTheClientActiveUnit() {
        final int vao = GLStateManager.glGenVertexArrays();
        final int vbo = GLStateManager.glGenBuffers();
        GLStateManager.glBindVertexArray(vao);
        try {
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GLStateManager.glBufferData(GL15.GL_ARRAY_BUFFER, 128L, GL15.GL_STATIC_DRAW);

            GLStateManager.glClientActiveTexture(GL13.GL_TEXTURE1);
            GLStateManager.glTexCoordPointer(2, GL11.GL_FLOAT, 8, 0L);
            GLStateManager.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            assertEquals(2, GLStateManager.glGetInteger(GL11.GL_TEXTURE_COORD_ARRAY_SIZE));
            assertEquals(8, GLStateManager.glGetInteger(GL11.GL_TEXTURE_COORD_ARRAY_STRIDE));
            assertEquals(2, GL20.glGetVertexAttribi(UV1, GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE));

            GLStateManager.glClientActiveTexture(GL13.GL_TEXTURE3);
            GLStateManager.glTexCoordPointer(4, GL11.GL_FLOAT, 16, 0L);
            assertEquals(4, GLStateManager.glGetInteger(GL11.GL_TEXTURE_COORD_ARRAY_SIZE));
            assertEquals(16, GLStateManager.glGetInteger(GL11.GL_TEXTURE_COORD_ARRAY_STRIDE));
            assertEquals(4, GL20.glGetVertexAttribi(UV3, GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE), "texture unit 3 feeds its own attribute location");
            assertEquals(16, GL20.glGetVertexAttribi(UV3, GL20.GL_VERTEX_ATTRIB_ARRAY_STRIDE));

            GLStateManager.glClientActiveTexture(GL13.GL_TEXTURE1);
            assertEquals(2, GLStateManager.glGetInteger(GL11.GL_TEXTURE_COORD_ARRAY_SIZE), "unit 1 keeps its own texcoord array");
            assertTrue(GLStateManager.glIsEnabled(GL11.GL_TEXTURE_COORD_ARRAY));

            GLStateManager.glClientActiveTexture(GL13.GL_TEXTURE0);
            assertEquals(GL11.GL_FLOAT, GLStateManager.glGetInteger(GL11.GL_TEXTURE_COORD_ARRAY_TYPE), "unit 0 never got a texcoord array");
            assertFalse(GLStateManager.glIsEnabled(GL11.GL_TEXTURE_COORD_ARRAY));
        } finally {
            GLStateManager.glBindVertexArray(0);
            GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GLStateManager.glDeleteBuffers(vbo);
            GLStateManager.glDeleteVertexArrays(vao);
        }
        drainDriverErrors();
    }

    private static void drainDriverErrors() {
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {
            // Drain stale errors so each test only reports the calls it made.
        }
    }
}
