package com.gtnewhorizons.angelica.glsm.ffp;

import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
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
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FfpExtendedAttribsGLTest extends GLStateManagerIntegrationFixture {

    private static final int STRIDE = 20;
    private static final float MID_U = 0.25F;
    private static final float MID_V = 0.75F;

    private int vao;
    private int vbo;

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
        GLSMHooks.immediateExtendedHandler = new FixedExtendedHandler();

        vao = GLSMVertexInput.glGenVertexArrays();
        vbo = GLSMBuffers.glGenBuffers();
        GLSMVertexInput.glBindVertexArray(vao);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, triangle(), GL15.GL_STATIC_DRAW);
        GLSMVertexInput.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GLSMVertexInput.glVertexPointer(3, GL11.GL_FLOAT, STRIDE, 0L);
        GLSMVertexInput.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GLSMVertexInput.glTexCoordPointer(2, GL11.GL_FLOAT, STRIDE, 12L);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    @AfterEach
    void tearDown() {
        GLSMVertexInput.glBindVertexArray(0);
        FfpExtendedAttribs.reset();
        if (vbo != 0) GLSMBuffers.glDeleteBuffers(vbo);
        if (vao != 0) GLSMVertexInput.glDeleteVertexArrays(vao);
        GLSMHooks.immediateExtendedHandler = null;
        GLStateManager.glViewport(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT);
        while (GL11.glGetError() != GL11.GL_NO_ERROR) { }
    }

    @Test
    void rebuildsAndBindsTheExtendedAttributeBuffer() {
        assertTrue(FfpExtendedAttribs.maybeBind(GLStateManager.ctx(), GL11.GL_TRIANGLES, 0, 3),
            "the conventional textured VBO must take the extended-attribute path");

        final VAOManager.Attrib mid = GLStateManager.ctx().vaos.attrib(ImmediateExtendedAttribHandler.LOC_MID_TEX);
        final VAOManager.Attrib tangent = GLStateManager.ctx().vaos.attrib(ImmediateExtendedAttribHandler.LOC_TANGENT);
        assertTrue(mid.enabled, "the rebuilt mid-texture array must be enabled");
        assertTrue(tangent.enabled, "the rebuilt tangent array must be enabled");
        assertNotEquals(0, mid.vboId, "the rebuild must allocate a scratch VBO");
        assertEquals(mid.vboId, tangent.vboId, "both attributes share the scratch record");

        final ByteBuffer data = BufferUtils.createByteBuffer(3 * ImmediateExtendedAttribHandler.EXT_STRIDE);
        final int savedVbo = GLSMBuffers.getBoundVBO();
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, mid.vboId);
        GLSMBuffers.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, data);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedVbo);
        for (int vertex = 0; vertex < 3; vertex++) {
            assertEquals(MID_U, data.getFloat(vertex * ImmediateExtendedAttribHandler.EXT_STRIDE), 1.0e-6F);
            assertEquals(MID_V, data.getFloat(vertex * ImmediateExtendedAttribHandler.EXT_STRIDE + 4), 1.0e-6F);
        }
    }

    private static ByteBuffer triangle() {
        final ByteBuffer data = BufferUtils.createByteBuffer(3 * STRIDE);
        for (float[] vertex : new float[][] {
            {-1.0F, -1.0F, 0.0F, 0.0F, 0.0F},
            {3.0F, -1.0F, 0.0F, 4.0F, 0.0F},
            {-1.0F, 3.0F, 0.0F, 0.0F, 4.0F}
        }) {
            for (float value : vertex) data.putFloat(value);
        }
        return data.flip();
    }

    private static final class FixedExtendedHandler implements ImmediateExtendedAttribHandler {
        @Override
        public boolean wantsExtended() {
            return true;
        }

        @Override
        public boolean wantsTangent() {
            return false;
        }

        @Override
        public void buildPacked(long srcBase, int stride, int posOffset, int texOffset, int normalOffset,
                                int vertexCount, int vertsPerPrim, long dstAddr, int dstStride) {
            for (int vertex = 0; vertex < vertexCount; vertex++) {
                final long dst = dstAddr + (long) vertex * dstStride;
                MemoryUtil.memPutFloat(dst, MID_U);
                MemoryUtil.memPutFloat(dst + 4, MID_V);
                MemoryUtil.memPutInt(dst + 8, 0);
            }
        }
    }
}
