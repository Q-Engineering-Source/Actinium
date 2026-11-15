package com.gtnewhorizons.angelica.glsm.hooks;

import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormat;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.VertexFormatElement;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import org.lwjgl.opengl.GL11;

/**
 * Host-supplied builder for the {@code mc_midTexCoord} and {@code at_tangent} attributes.
 *
 * <p>Shader packs read both attributes per vertex, but mod geometry that reaches a draw call as
 * {@code glVertexPointer}/{@code glTexCoordPointer} over a VBO carries neither. The FFP layer
 * rebuilds them into a scratch buffer through this interface, so the host owns the decision of
 * which programs need the attributes and how a primitive's vertices derive them.</p>
 */
public interface ImmediateExtendedAttribHandler {

    /** Rebuilt vertex size: two floats of mid texture coordinate, then a packed normalised tangent. */
    int EXT_STRIDE = 12;

    /** Attribute location of {@code mc_midTexCoord}, matching the shader-pack attribute binding. */
    int LOC_MID_TEX = 12;

    /** Attribute location of {@code at_tangent}, matching the shader-pack attribute binding. */
    int LOC_TANGENT = 13;

    /** Attribute location of the per-vertex entity, block entity and item ids. */
    int LOC_ENTITY_IDS = 15;

    /** Rebuilt layout of the per-vertex id attribute: three packed ids plus one pad element. */
    int ENTITY_IDS_STRIDE = 8;

    /** Whether the program in use declares either rebuilt attribute, so the rebuild is worth its cost. */
    boolean wantsExtended();

    /** Whether {@code at_tangent} must carry a real tangent instead of the neutral one. */
    boolean wantsTangent();

    /** Whether the program in use reads the per-vertex entity ids. */
    boolean wantsEntityIds();

    /**
     * Fills the rebuilt attribute range for one draw.
     *
     * @param srcBase address of the first source vertex
     * @param stride source vertex stride in bytes
     * @param posOffset byte offset of the position inside a source vertex
     * @param texOffset byte offset of the primary texture coordinate inside a source vertex
     * @param normalOffset byte offset of the packed normal inside a source vertex, or {@code -1} when absent
     * @param vertexCount number of vertices to produce
     * @param vertsPerPrim vertices per primitive, used to keep per-primitive values constant
     * @param dstAddr address of the destination range
     * @param dstStride destination stride in bytes, at least {@link #EXT_STRIDE}
     */
    void buildPacked(long srcBase, int stride, int posOffset, int texOffset, int normalOffset,
                     int vertexCount, int vertsPerPrim, long dstAddr, int dstStride);

    /** Vertices per primitive for the draw modes the rebuild supports, or {@code 0} for any other mode. */
    static int extPrimVerts(int drawMode) {
        if (drawMode == GL11.GL_QUADS) return 4;
        if (drawMode == GL11.GL_TRIANGLES) return 3;
        return 0;
    }

    /** Vertices per primitive, or {@code 0} when the vertex count does not fill whole primitives. */
    static int extPrimVerts(int drawMode, int vertexCount) {
        final int vertsPerPrim = extPrimVerts(drawMode);
        return vertsPerPrim != 0 && vertexCount > 0 && vertexCount % vertsPerPrim == 0 ? vertsPerPrim : 0;
    }

    /** @return byte offset of the primary texture coordinate inside a vertex, or {@code -1} when absent */
    static int texOffset(VertexFormat format) {
        int offset = 0;
        for (VertexFormatElement element : format.getElements()) {
            if (element.getUsage() == VertexFormatElement.Usage.PRIMARY_UV) return offset;
            offset += element.getByteSize();
        }
        return -1;
    }

    /** @return byte offset of the packed normal inside a vertex, or {@code -1} when absent */
    static int normalOffset(VertexFormat format) {
        int offset = 0;
        for (VertexFormatElement element : format.getElements()) {
            if (element.getUsage() == VertexFormatElement.Usage.NORMAL) return offset;
            offset += element.getByteSize();
        }
        return -1;
    }

    /** Points the per-vertex id attribute into a buffer whose first vertex starts at {@code offset}. */
    static void setupEntityIdsPointer(long offset, int stride) {
        GLSMVertexInput.glEnableVertexAttribArray(LOC_ENTITY_IDS);
        GLSMVertexInput.glVertexAttribPointer(LOC_ENTITY_IDS, 4, GL11.GL_UNSIGNED_SHORT, true, stride, offset);
    }

    /** Points both rebuilt attributes into a buffer whose first rebuilt vertex starts at {@code baseOffset}. */
    static void setupExtAttribPointers(long baseOffset, int stride) {
        GLSMVertexInput.glEnableVertexAttribArray(LOC_MID_TEX);
        GLSMVertexInput.glVertexAttribPointer(LOC_MID_TEX, 2, GL11.GL_FLOAT, false, stride, baseOffset);
        GLSMVertexInput.glEnableVertexAttribArray(LOC_TANGENT);
        GLSMVertexInput.glVertexAttribPointer(LOC_TANGENT, 4, GL11.GL_BYTE, true, stride, baseOffset + 8);
    }
}
