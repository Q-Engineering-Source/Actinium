package com.gtnewhorizons.angelica.glsm.ffp;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFlags;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The four corners every weather column is expanded from.
 *
 * <p>A weather draw supplies one corner per vertex and one column per instance. The corner lives in
 * the primary UV attribute: x picks which end of the billboard the vertex sits on, y picks the
 * bottom or the top edge. The vertex shader turns that into the world-space position, the texture
 * coordinates and the transparency, so the quad mesh itself carries no position data.</p>
 */
public final class WeatherQuadMesh {

    /** The quad only carries a texture coordinate; position and colour come from the instance. */
    public static final int VERTEX_FLAGS = VertexFlags.TEXTURE_BIT;

    /** Four corners drawn as one triangle fan per instance. */
    public static final int VERTEX_COUNT = 4;

    private static final float[] CORNERS = {0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 0.0F, 1.0F};

    private static int vao;
    private static int vbo;

    private WeatherQuadMesh() {
    }

    /**
     * Returns the corner VAO, creating it on first use.
     *
     * <p>The VAO also records the instance attribute pointers and their divisors, which is why a
     * draw only has to bind the instance buffer and re-point the three attributes at the segment it
     * reads.</p>
     *
     * @return the corner vertex array object
     */
    public static int vao() {
        if (vao != 0) return vao;

        final ByteBuffer data = ByteBuffer.allocateDirect(CORNERS.length * Float.BYTES).order(ByteOrder.nativeOrder());
        for (float corner : CORNERS) data.putFloat(corner);
        data.flip();

        vao = GLSMVertexInput.glGenVertexArrays();
        GLSMVertexInput.glBindVertexArray(vao);
        vbo = GLSMBuffers.glGenBuffers();
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);

        final int corner = VertexFormatElement.Usage.PRIMARY_UV.getAttributeLocation(0);
        GLSMVertexInput.glEnableVertexAttribArray(corner);
        GLSMVertexInput.glVertexAttribPointer(corner, 2, GL11.GL_FLOAT, false, 2 * Float.BYTES, 0L);
        WeatherInstancedAttribs.enableInstanceArrays();

        GLSMVertexInput.glBindVertexArray(0);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        return vao;
    }

    /**
     * Points the instance attributes of the corner VAO at one instance buffer.
     *
     * @param bufferId array buffer holding the instance records
     */
    public static void bindInstanceBuffer(int bufferId) {
        GLSMVertexInput.glBindVertexArray(vao());
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, bufferId);
        WeatherInstancedAttribs.pointInstanceAttribs(0L);
        GLSMVertexInput.glBindVertexArray(0);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    /**
     * Releases the corner mesh. Safe to call when nothing was created.
     */
    public static void delete() {
        if (vbo != 0) {
            GLSMBuffers.glDeleteBuffers(vbo);
            vbo = 0;
        }
        if (vao != 0) {
            GLSMVertexInput.glDeleteVertexArrays(vao);
            vao = 0;
        }
    }
}
