package com.gtnewhorizons.angelica.render;

import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.GLSMQueries;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import dhj.embeddedt.embeddium.impl.gl.shader.GlProgram;
import dhj.embeddedt.embeddium.impl.gl.shader.ShaderBindingContext;
import dhj.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformFloat;
import dhj.embeddedt.embeddium.impl.gl.shader.GlShader;
import dhj.embeddedt.embeddium.impl.gl.shader.ShaderType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the cloud meshes against a real context: the vertex layout the local Iris colour attribute
 * needs, the packed colour bytes, segmented upload, the wedge ordering the draw ranges use, the instanced
 * face layout and the release path.
 *
 * <p>The colour assertions read pixels from a test program that forwards attribute location 1, so a mesh
 * that stops writing the colour, writes the wrong shade or moves the attribute fails here rather than in
 * a lit scene.
 */
class CloudVertexMeshGLTest extends GLStateManagerIntegrationFixture {

    private static final int SIZE = 64;
    private static final int BACKGROUND = 0xFF000000;

    /** Stride of the textured layout: position, UV, colour, normal and shade. */
    private static final int STRIDE_WITH_UV = 28;
    private static final int UV_OFFSET = 12;
    private static final int COLOR_OFFSET_WITH_UV = 20;
    private static final int SHADE_OFFSET = 24;
    /** Stride of the UNTEXTURED layout, which drops the UV pair. */
    private static final int STRIDE_NO_UV = 20;
    private static final int COLOR_OFFSET_NO_UV = 16;
    private static final int SHADE_OFFSET_NO_UV = 12;

    private CloudVertexMesh mesh;
    private CloudFaceMesh faceMesh;

    @BeforeAll
    static void initializeGlsm() {
        installDefaultGlsmConfig();
    }

    @BeforeEach
    void setUp() {
        WindowBackends.useSdl();
        GLStateManager.bindDisplayContext(new Object(), context);
        GLStateManager.recreateDefaultVertexArray();

        GLSMRasterizer.glViewport(0, 0, SIZE, SIZE);
        GLSMEnable.glDisable(GL11.GL_DEPTH_TEST);
        GLSMEnable.glDisable(GL11.GL_BLEND);
        GLSMEnable.glDisable(GL11.GL_CULL_FACE);

        mesh = new CloudVertexMesh();
        mesh.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        faceMesh = new CloudFaceMesh();
    }

    @AfterEach
    void tearDown() {
        mesh.delete();
        faceMesh.delete();
        GLSMEnable.glDisable(GL11.GL_DEPTH_TEST);
        GLSMVertexInput.glBindVertexArray(0);
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GLSMBuffers.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
        while (GL11.glGetError() != GL11.GL_NO_ERROR) {
            // Drain driver errors so the next test starts clean.
        }
    }

    @Test
    void planeQuadDrawsThePackedVertexColour() {
        mesh.setColor(0.8F, 0.4F, 0.2F, 1.0F);
        mesh.buildFast(shape(2, 2), 1, 0.0F, 0.0F, false);
        mesh.uploadBuilt();
        assertTrue(mesh.built(), "the plane quad must upload");

        drawWithColourProgram(0, mesh.quadCount(), 1.0F, 0.0F);
        final int[] pixels = readRegion();

        assertEquals(pack(204, 102, 51, 255), pixels[32 * SIZE + 32], "attribute location 1 carries the colour");
        assertEquals(pack(204, 102, 51, 255), pixels[8 * SIZE + 48], "the plane quad covers the whole disc");

        mesh.setColor(0.2F, 0.6F, 1.0F, 1.0F);
        mesh.buildFast(shape(2, 2), 1, 0.0F, 0.0F, false);
        mesh.uploadBuilt();
        drawWithColourProgram(0, mesh.quadCount(), 1.0F, 0.0F);
        assertEquals(pack(51, 153, 255, 255), readRegion()[32 * SIZE + 32], "the attribute follows setColor");
    }

    @Test
    void interiorFacesBakeTheFaceShadeIntoTheVertexColour() {
        mesh.setColor(0.8F, 0.4F, 0.2F, 0.5F);
        mesh.buildInterior(shape(1, 1), 0, 0, 0.5, 0.0F, 0.0F, false, false);
        mesh.uploadBuilt();
        assertTrue(mesh.quadCount() >= 2, "the interior emits its faces");

        // Interior quads are emitted bottom, top, then the four sides, so quad 0 is shaded 0.7 and quad 1
        // is the unshaded top.
        drawWithColourProgram(0, 1, 2.0F, -1.0F);
        assertEquals(pack(143, 71, 36, 128), readRegion()[32 * SIZE + 32], "the bottom face shade is baked");

        drawWithColourProgram(1, 2, 2.0F, -1.0F);
        assertEquals(pack(204, 102, 51, 128), readRegion()[32 * SIZE + 32], "the top face keeps the plain colour");
    }

    @Test
    void packLayoutDrawsTheCurrentGenericColourWithoutRebuilding() {
        mesh.setColor(0.8F, 0.4F, 0.2F, 1.0F);
        mesh.build(shape(2, 2), 0, 0, 6, 36, 4.0, 0.0F, 0.0F, true, true, true,
            Integer.MAX_VALUE, Integer.MAX_VALUE, false, true);
        mesh.uploadBuilt();
        final int quads = mesh.quadCount();
        assertTrue(quads > 0, "the pack build emits geometry");

        mesh.bind();
        try {
            assertFalse(attributeEnabled(1), "the pack layout takes its colour from the generic attribute");
        } finally {
            mesh.unbind();
        }

        drawFaceWithColourProgram(1, 1.0F, 0.0F);
        assertEquals(pack(204, 102, 51, 255), readRegion()[32 * SIZE + 32], "the first generic colour is drawn");

        mesh.setColor(0.2F, 0.6F, 1.0F, 1.0F);
        assertEquals(quads, mesh.quadCount(), "setColor must not rebuild the geometry");
        drawFaceWithColourProgram(1, 1.0F, 0.0F);
        assertEquals(pack(51, 153, 255, 255), readRegion()[32 * SIZE + 32],
            "the second generic colour replaces the first without a rebuild");

        mesh.setColor(0.4F, 0.8F, 0.2F, 1.0F);
        drawFaceWithColourProgram(0, 1.0F, 0.0F);
        assertEquals(pack(71, 143, 36, 255), readRegion()[32 * SIZE + 32],
            "drawFace applies the face shade to the generic colour");
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void texturedLayoutExposesPositionColourUvAndShade() {
        mesh.build(shape(2, 2), 0, 0, 6, 36, 4.0, 0.0F, 0.0F, true, true, true,
            Integer.MAX_VALUE, Integer.MAX_VALUE, false, false);
        mesh.uploadBuilt();
        assertTrue(mesh.quadCount() > 0, "the plate build emits geometry");
        mesh.bind();
        try {
            assertAttribute(0, 3, GL11.GL_FLOAT, false, STRIDE_WITH_UV, 0L);
            assertAttribute(1, 4, GL11.GL_UNSIGNED_BYTE, true, STRIDE_WITH_UV, COLOR_OFFSET_WITH_UV);
            assertAttribute(2, 2, GL11.GL_FLOAT, false, STRIDE_WITH_UV, UV_OFFSET);
            assertAttribute(4, 3, GL11.GL_BYTE, true, STRIDE_WITH_UV, SHADE_OFFSET);
            assertAttribute(5, 4, GL11.GL_UNSIGNED_BYTE, true, STRIDE_WITH_UV, SHADE_OFFSET);
            assertTrue(attributeEnabled(1), "the colour attribute is enabled for Iris");
        } finally {
            mesh.unbind();
        }
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void untexturedLayoutDropsTheUvAttribute() {
        mesh.build(shape(2, 2), 0, 0, 6, 36, 4.0, 0.0F, 0.0F, true, true, true,
            Integer.MAX_VALUE, Integer.MAX_VALUE, true, false);
        mesh.uploadBuilt();
        mesh.bind();
        try {
            assertAttribute(0, 3, GL11.GL_FLOAT, false, STRIDE_NO_UV, 0L);
            assertAttribute(1, 4, GL11.GL_UNSIGNED_BYTE, true, STRIDE_NO_UV, COLOR_OFFSET_NO_UV);
            assertAttribute(4, 3, GL11.GL_BYTE, true, STRIDE_NO_UV, SHADE_OFFSET_NO_UV);
            assertAttribute(5, 4, GL11.GL_UNSIGNED_BYTE, true, STRIDE_NO_UV, SHADE_OFFSET_NO_UV);
            assertFalse(attributeEnabled(2), "the untextured layout has no UV attribute");
        } finally {
            mesh.unbind();
        }
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void segmentedUploadCompletesOnlyOnTheLastSlice() {
        // The first upload allocates and copies in one go; a rebuild into the existing buffer is the path
        // that can be sliced across frames.
        mesh.build(shape(2, 2), 0, 0, 6, 36, 4.0, 0.0F, 0.0F, true, true, true,
            Integer.MAX_VALUE, Integer.MAX_VALUE, false, false);
        mesh.uploadBuilt();
        mesh.build(shape(2, 2), 0, 0, 6, 36, 4.0, 0.0F, 0.0F, true, true, true,
            Integer.MAX_VALUE, Integer.MAX_VALUE, false, false);

        assertTrue(mesh.beginUpload(), "the rebuild reuses the vertex buffer");
        assertFalse(mesh.uploadSlice(64), "the first slice leaves the upload pending");
        assertTrue(mesh.uploadSlice(Integer.MAX_VALUE), "the remaining slice finishes the upload");
        assertTrue(mesh.built(), "the mesh is drawable after the last slice");
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void rebuildingWithoutGeometryAbandonsThePartialUpload() {
        mesh.build(shape(2, 2), 0, 0, 6, 36, 4.0, 0.0F, 0.0F, true, true, true,
            Integer.MAX_VALUE, Integer.MAX_VALUE, false, false);
        mesh.uploadBuilt();

        // A second build into the resident buffer leaves a partial upload in flight.
        mesh.build(shape(2, 2), 0, 0, 4, 16, 4.0, 0.0F, 0.0F, true, true, true,
            Integer.MAX_VALUE, Integer.MAX_VALUE, false, false);
        assertTrue(mesh.beginUpload(), "the rebuild reuses the vertex buffer");
        assertFalse(mesh.uploadSlice(64), "the first slice leaves the upload pending");

        // A texture without any cloud cell emits nothing, so the in flight upload must be dropped
        // instead of being resumed against the emit buffer the new build just rewound.
        mesh.build(emptyShape(2, 2), 0, 0, 4, 16, 4.0, 0.0F, 0.0F, true, true, true,
            Integer.MAX_VALUE, Integer.MAX_VALUE, false, false);

        assertFalse(mesh.beginUpload(), "a build without geometry has nothing to upload");
        assertTrue(mesh.uploadSlice(Integer.MAX_VALUE), "the abandoned upload is not resumed");
        assertFalse(mesh.built(), "the old geometry must not stay drawable");
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void aPartialUploadDoesNotSurviveTheNextValidBuild() {
        mesh.build(shape(2, 2), 0, 0, 6, 36, 4.0, 0.0F, 0.0F, true, true, true,
            Integer.MAX_VALUE, Integer.MAX_VALUE, false, false);
        mesh.uploadBuilt();

        mesh.build(shape(2, 2), 0, 0, 6, 36, 4.0, 0.0F, 0.0F, true, true, true,
            Integer.MAX_VALUE, Integer.MAX_VALUE, false, false);
        assertTrue(mesh.beginUpload(), "the rebuild reuses the vertex buffer");
        assertFalse(mesh.uploadSlice(64), "the partial upload is in flight");

        mesh.build(shape(2, 2), 0, 0, 4, 16, 4.0, 0.0F, 0.0F, true, true, true,
            Integer.MAX_VALUE, Integer.MAX_VALUE, false, false);
        assertTrue(mesh.beginUpload(), "the new build reuses the buffer again");
        assertTrue(mesh.uploadSlice(Integer.MAX_VALUE), "the new build uploads completely");

        final CloudVertexMesh reference = new CloudVertexMesh();
        try {
            reference.build(shape(2, 2), 0, 0, 4, 16, 4.0, 0.0F, 0.0F, true, true, true,
                Integer.MAX_VALUE, Integer.MAX_VALUE, false, false);
            reference.uploadBuilt();
            assertTrue(mesh.quadCount() > 0, "the new geometry is uploaded");
            assertEquals(reference.quadCount(), mesh.quadCount(), "only the last build survives");
            assertTrue(mesh.built(), "the mesh is drawable with the new geometry");
        } finally {
            reference.delete();
        }
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void reorderPublishesMonotonicWedgeRangesEndingAtTheQuadCount() {
        mesh.build(shape(2, 2), 0, 0, 6, 36, 4.0, 0.0F, 0.0F, true, true, true,
            Integer.MAX_VALUE, Integer.MAX_VALUE, false, false);
        mesh.uploadBuilt();
        assertTrue(mesh.orderStale(0, 0), "a fresh upload has no published order");

        mesh.reorder(0, 0);
        mesh.reorderUpload();

        final int[] plateStarts = mesh.plateStarts();
        final int[] wallStarts = mesh.wallStarts();
        for (int i = 1; i <= CloudDisc.WEDGE_COUNT; i++) {
            assertTrue(plateStarts[i] >= plateStarts[i - 1], "plate ranges must not go backwards");
            assertTrue(wallStarts[i] >= wallStarts[i - 1], "wall ranges must not go backwards");
        }
        assertEquals(mesh.quadCount(), wallStarts[CloudDisc.WEDGE_COUNT], "the last wall range ends at the quad count");
        assertFalse(mesh.orderStale(0, 0), "the published order matches the anchor");
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void faceMeshPublishesInstancedIntegerFaces() {
        faceMesh.build(checkerboard(2), 0, 0, 6, 36, Integer.MAX_VALUE);
        assertTrue(faceMesh.uploadedCount() > 0, "the wall runs stamp faces");
        assertEquals(6, faceMesh.interiorCount(), "an opaque anchor cell emits its six interior faces");
        assertEquals(faceMesh.uploadedCount(), faceMesh.wallStarts()[CloudDisc.WEDGE_COUNT],
            "the last wall range ends at the stamped face count");

        faceMesh.bind();
        try {
            assertAttribute(0, 1, GL11.GL_INT, false, 4, 0L);
            assertEquals(1, attributeDivisor(0), "one face record feeds one quad");
            assertEquals(1, attributei(0, GL30.GL_VERTEX_ATTRIB_ARRAY_INTEGER), "the face attribute is integer");
        } finally {
            faceMesh.unbind();
        }

        faceMesh.bind();
        faceMesh.drawRange(0, Math.min(4, faceMesh.uploadedCount()));
        faceMesh.unbind();
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());
    }

    @Test
    void deleteReleasesTheGlNamesAndIsIdempotent() {
        mesh.build(shape(2, 2), 0, 0, 6, 36, 4.0, 0.0F, 0.0F, true, true, true,
            Integer.MAX_VALUE, Integer.MAX_VALUE, false, false);
        mesh.uploadBuilt();
        mesh.bind();
        final int vao = GLSMQueries.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        mesh.unbind();
        assertTrue(GLSMVertexInput.glIsVertexArray(vao), "the mesh owns a vertex array");

        mesh.delete();

        assertFalse(GLSMVertexInput.glIsVertexArray(vao), "delete() releases the vertex array");
        assertFalse(mesh.built(), "the mesh reports itself empty after the release");
        mesh.delete();
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError());

        mesh.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        mesh.buildFast(shape(2, 2), 1, 0.0F, 0.0F, false);
        mesh.uploadBuilt();
        assertTrue(mesh.built(), "the mesh can be rebuilt after a release");
    }

    /** Uniforms of the colour test program. */
    private static final class ColourUniforms {
        private final GlUniformFloat scale;
        private final GlUniformFloat offset;

        private ColourUniforms(ShaderBindingContext context) {
            scale = context.bindUniform("u_Scale", GlUniformFloat::new);
            offset = context.bindUniform("u_Offset", GlUniformFloat::new);
        }

        private void set(float scale, float offset) {
            this.scale.setFloat(scale);
            this.offset.setFloat(offset);
        }
    }

    private void drawWithColourProgram(int startQuad, int endQuad, float scale, float offset) {
        final GlProgram<ColourUniforms> program = colourProgram();
        try {
            clear();
            program.bind();
            program.getInterface().set(scale, offset);
            mesh.bind();
            GLSMDraws.glDrawElements(GL11.GL_TRIANGLES, (endQuad - startQuad) * 6, GL11.GL_UNSIGNED_INT,
                (long) startQuad * 6L * 4L);
            mesh.unbind();
            program.unbind();
        } finally {
            program.delete();
        }
    }

    /** Draws one face through the mesh, which is where the pack path sets the generic colour. */
    private void drawFaceWithColourProgram(int face, float scale, float offset) {
        final GlProgram<ColourUniforms> program = colourProgram();
        try {
            clear();
            program.bind();
            program.getInterface().set(scale, offset);
            mesh.bind();
            mesh.drawFace(face);
            mesh.unbind();
            program.unbind();
        } finally {
            program.delete();
        }
    }

    /**
     * Reads attribute location 1 and paints it, so the vertex colour is visible in the frame buffer. The
     * scale and offset uniform map the quad of the test case onto the whole viewport.
     */
    private static GlProgram<ColourUniforms> colourProgram() {
        final String vert = String.join("\n",
            "#version 330 core",
            "layout(location = 0) in vec3 a_Position;",
            "layout(location = 1) in vec4 a_Color;",
            "out vec4 v_Color;",
            "uniform float u_Scale;",
            "uniform float u_Offset;",
            "void main() {",
            "    gl_Position = vec4(a_Position.x * u_Scale + u_Offset, a_Position.z * u_Scale + u_Offset, 0.0, 1.0);",
            "    v_Color = a_Color;",
            "}"
        );
        final String frag = String.join("\n",
            "#version 330 core",
            "in vec4 v_Color;",
            "out vec4 fragColor;",
            "void main() { fragColor = v_Color; }"
        );
        final GlShader vertex = new GlShader(ShaderType.VERTEX, "test:cloud_vertex_colour", vert);
        final GlShader fragment = new GlShader(ShaderType.FRAGMENT, "test:cloud_vertex_colour", frag);
        try {
            return GlProgram.builder("test:cloud_vertex_colour")
                .attachShader(vertex)
                .attachShader(fragment)
                .link(ColourUniforms::new);
        } finally {
            vertex.delete();
            fragment.delete();
        }
    }

    private static void assertAttribute(int index, int size, int type, boolean normalized, int stride, long offset) {
        assertEquals(size, attributei(index, GL20.GL_VERTEX_ATTRIB_ARRAY_SIZE), "size of attribute " + index);
        assertEquals(type, attributei(index, GL20.GL_VERTEX_ATTRIB_ARRAY_TYPE), "type of attribute " + index);
        assertEquals(normalized ? GL11.GL_TRUE : GL11.GL_FALSE,
            attributei(index, GL20.GL_VERTEX_ATTRIB_ARRAY_NORMALIZED), "normalized flag of attribute " + index);
        assertEquals(stride, attributei(index, GL20.GL_VERTEX_ATTRIB_ARRAY_STRIDE), "stride of attribute " + index);
        final PointerBuffer pointer = BufferUtils.createPointerBuffer(1);
        GL20.glGetVertexAttribPointerv(index, GL20.GL_VERTEX_ATTRIB_ARRAY_POINTER, pointer);
        assertEquals(offset, pointer.get(0), "offset of attribute " + index);
    }

    private static int attributei(int index, int pname) {
        final java.nio.IntBuffer value = BufferUtils.createIntBuffer(1);
        GL20.glGetVertexAttribiv(index, pname, value);
        return value.get(0);
    }

    private static int attributeDivisor(int index) {
        return attributei(index, GL33.GL_VERTEX_ATTRIB_ARRAY_DIVISOR);
    }

    private static boolean attributeEnabled(int index) {
        return attributei(index, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED) == GL11.GL_TRUE;
    }

    /** Texture without any cloud cell, so a build from it emits no geometry at all. */
    private static CloudShape emptyShape(int width, int height) {
        return new CloudShape(width, height, ByteBuffer.allocate(width * height * 4));
    }

    /** Two by two texture with two diagonal cloud cells, so the shape exposes wall sides. */
    private static CloudShape checkerboard(int size) {
        final ByteBuffer buffer = ByteBuffer.allocate(size * size * 4);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                if (((x + z) & 1) == 0) buffer.putInt((z * size + x) * 4, 0xFFFFFFFF);
            }
        }
        return new CloudShape(size, size, buffer);
    }

    private static CloudShape shape(int width, int height) {
        final ByteBuffer buffer = ByteBuffer.allocate(width * height * 4);
        for (int cell = 0; cell < width * height; cell++) {
            buffer.putInt(cell * 4, 0xFFFFFFFF);
        }
        return new CloudShape(width, height, buffer);
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

    private static int pack(int red, int green, int blue, int alpha) {
        return ((alpha & 0xFF) << 24) | ((blue & 0xFF) << 16) | ((green & 0xFF) << 8) | (red & 0xFF);
    }
}
