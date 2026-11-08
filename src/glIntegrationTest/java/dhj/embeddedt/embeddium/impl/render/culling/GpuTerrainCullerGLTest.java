package dhj.embeddedt.embeddium.impl.render.culling;

import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMBlend;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import dhj.embeddedt.embeddium.impl.gl.arena.GlBufferSegment;
import dhj.embeddedt.embeddium.impl.gl.attribute.GlVertexAttribute;
import dhj.embeddedt.embeddium.impl.gl.attribute.GlVertexAttributeBinding;
import dhj.embeddedt.embeddium.impl.gl.attribute.GlVertexAttributeFormat;
import dhj.embeddedt.embeddium.impl.gl.array.GlVertexArray;
import dhj.embeddedt.embeddium.impl.gl.buffer.GlBufferUsage;
import dhj.embeddedt.embeddium.impl.gl.buffer.GlMutableBuffer;
import dhj.embeddedt.embeddium.impl.gl.device.CommandList;
import dhj.embeddedt.embeddium.impl.gl.device.DirectMultiDrawBatch;
import dhj.embeddedt.embeddium.impl.gl.device.DrawCommandList;
import dhj.embeddedt.embeddium.impl.gl.device.MultiDrawBatch;
import dhj.embeddedt.embeddium.impl.gl.device.RenderDevice;
import dhj.embeddedt.embeddium.impl.gl.shader.GlProgram;
import dhj.embeddedt.embeddium.impl.gl.shader.GlShader;
import dhj.embeddedt.embeddium.impl.gl.shader.ShaderType;
import dhj.embeddedt.embeddium.impl.gl.tessellation.GlPrimitiveType;
import dhj.embeddedt.embeddium.impl.gl.tessellation.GlVertexArrayTessellation;
import dhj.embeddedt.embeddium.impl.gl.tessellation.TessellationBinding;
import dhj.embeddedt.embeddium.impl.gl.util.VertexRange;
import dhj.embeddedt.embeddium.impl.model.quad.properties.ModelQuadFacing;
import dhj.embeddedt.embeddium.impl.render.chunk.ChunkRenderMatrices;
import dhj.embeddedt.embeddium.impl.render.chunk.LocalSectionIndex;
import dhj.embeddedt.embeddium.impl.render.chunk.MultiDrawMode;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.data.SectionRenderDataStorage;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderList;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.ChunkRenderListIterable;
import dhj.embeddedt.embeddium.impl.render.chunk.lists.RenderVisualsService;
import dhj.embeddedt.embeddium.impl.render.chunk.multidraw.BatchAssembler;
import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegionTestFactory;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.impl.CompactChunkVertex;
import dhj.embeddedt.embeddium.impl.render.viewport.CameraTransform;
import dhj.embeddedt.embeddium.impl.runtime.EmbeddiumRuntimeOptions;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.mitchej123.lwjgl.LWJGLServiceProvider.LWJGL;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Compares the compute-culled draw of a region against the CPU-assembled draw of the same region, on a real driver and
 * with real tessellation.
 *
 * <p>The fixture holds one unsorted solid pass and two sorted passes with different section sets and different quad
 * counts. The sorted passes of the local render configuration (water and translucent) are separate passes: a command
 * range shared between them would either drop the second pass's sections or draw the first pass's geometry twice. The
 * unsorted pass merges its facings and reads the shared quad index buffer from element zero, while the sorted passes
 * read the index range of their own section. The camera is placed so that some sections straddle the side planes of the
 * frustum, which is the case that lost terrain at the screen edge when the padding was dropped from the plane
 * equations.
 */
class GpuTerrainCullerGLTest extends GLStateManagerIntegrationFixture {
    private static final QuadPrimitiveType PRIMITIVE_TYPE = QuadPrimitiveType.TRIANGULATED;
    private static final int BLOCK_GEOMETRY_FLAG = 1 << RenderVisualsService.HAS_BLOCK_GEOMETRY;
    private static final int FBO_SIZE = 128;
    private static final int REGION_CHUNK_RADIUS = 8;
    private static final float EYE_X = 64.0f;
    private static final float EYE_Y = 8.0f;
    private static final float EYE_Z = 200.0f;
    private static final float SECTION_INSET = 1.0f;
    /** Depth offset of the unsorted pass's quads, which keeps its depth writes ahead of the sorted passes' geometry. */
    private static final float UNSORTED_DEPTH_BIAS = -4.0f;

    /** The facing the sorted fixture passes populate, which is the single run a sorted pass draws per section. */
    private static final ModelQuadFacing[] UNASSIGNED_FACING = { ModelQuadFacing.UNASSIGNED };
    /**
     * The facings of the unsorted fixture pass. Two non-adjacent facings make the pass draw two runs per section, so
     * the second run reads the shared index buffer from an element the section's own vertex base is not at; a pass
     * that kept the sorted index layout would name a non-zero index base there.
     */
    private static final ModelQuadFacing[] TWO_DISTANT_FACINGS = { ModelQuadFacing.POS_X, ModelQuadFacing.UNASSIGNED };

    private static final String FLAT_VERTEX_SHADER = "#version 330 core\n"
        + "layout(location = 0) in vec3 a_Pos;\n"
        + "void main() { gl_Position = vec4(a_Pos, 1.0); }\n";
    private static final String FLAT_FRAGMENT_SHADER = "#version 330 core\n"
        + "out vec4 fragColor;\n"
        + "void main() { fragColor = vec4(1.0); }\n";

    private static final RenderPassConfiguration<Object> PASS_CONFIGURATION =
        new RenderPassConfiguration<>(Map.of(), Map.of(), null, null, null, null);

    private final Map<TerrainRenderPass, PassScene> passScenes = new LinkedHashMap<>();
    private final Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(30.0), 1.0f, 0.1f, 1000.0f);
    private final Matrix4f modelView = new Matrix4f();
    private final CameraTransform camera = new CameraTransform(EYE_X, EYE_Y, EYE_Z);

    private CommandList commandList;
    private GlProgram<Void> program;
    private GlMutableBuffer vertexBuffer;
    private RenderRegion region;
    private ChunkRenderListIterable renderLists;
    private GpuDrivenChunkCuller drivenCuller;
    private SectionMetaBuffer meta;
    private GpuTerrainCuller culler;
    private Target cpuTarget;
    private Target gpuTarget;
    private int commandSlots;
    private MultiDrawMode previousMultiDrawMode;

    @BeforeAll
    static void requireComputeContext() {
        assumeTrue(GL.getCapabilities().OpenGL43, "compute-driven culling needs a GL 4.3 context");
        assumeTrue(RenderSystem.supportsCompute(), "context reports no compute shader support");
        assumeTrue(MultiDrawModeResolver.supportsMultiDrawIndirect(), "context reports no multi-draw-indirect support");
    }

    @BeforeEach
    void buildScene() {
        // The compute path only publishes sections to the section mirror while it is both selected and available, and
        // availability needs the indirect multidraw mode the compute batches are drawn with.
        this.previousMultiDrawMode = EmbeddiumRuntimeOptions.chunkMultiDrawMode();
        EmbeddiumRuntimeOptions.setChunkMultiDrawMode(() -> MultiDrawMode.INDIRECT);
        GpuCulling.setMode(GpuCullingMode.COMPUTE);

        RenderDevice.INSTANCE.makeActive();
        this.commandList = RenderDevice.INSTANCE.createCommandList();
        this.program = createFlatProgram();

        this.region = RenderRegionTestFactory.create(0, 0, 0, 0,
            new CompactChunkVertex().getVertexFormat().getStride());
        final TerrainRenderPass water = sortedPass("water", TerrainRenderPass.Semantic.WATER);
        final TerrainRenderPass translucent = sortedPass("translucent", TerrainRenderPass.Semantic.TRANSLUCENT);
        final TerrainRenderPass solid = unsortedPass("solid", TerrainRenderPass.Semantic.SOLID);

        // The storage publishes its sections into the shared mirror itself, so the culler has to read that mirror for
        // the slot cache and the metadata rows to describe the same section.
        this.meta = GpuCulling.sectionMeta();
        this.drivenCuller = new GpuDrivenChunkCuller();
        this.culler = new GpuTerrainCuller(this.drivenCuller, this.meta);

        final GeometryBuilder geometry = new GeometryBuilder(this.projection);
        final ChunkRenderList renderList = new ChunkRenderList(this.region);
        // Row 2 is where the top plane of this camera pose passes tangentially through the section grid, so the first
        // sorted pass covers the sections whose padded box is the only reason they stay.
        addPass(water, sections(3, 1, 1), renderList, geometry, 0.0f, UNASSIGNED_FACING);
        addPass(translucent, sections(1, 2, 2), renderList, geometry, 0.0f, UNASSIGNED_FACING);
        // The unsorted pass holds a third section set and draws its quads in front of the sorted passes' geometry, so
        // its depth writes land where no earlier pass has written. Its two distant facings make every section draw two
        // runs, which is where a pass that kept the sorted index layout names a non-zero index base.
        addPass(solid, sections(2, 1, 1), renderList, geometry, UNSORTED_DEPTH_BIAS, TWO_DISTANT_FACINGS);

        this.vertexBuffer = geometry.upload(this.commandList);
        for (PassScene scene : this.passScenes.values()) {
            scene.tessellation = tessellation(scene.indexBuffer);
        }

        for (PassScene scene : this.passScenes.values()) {
            this.commandSlots += scene.gpuCommandCount;
        }

        this.renderLists = new TestRenderLists(renderList, this.passScenes.keySet());
        this.cpuTarget = new Target();
        this.gpuTarget = new Target();
    }

    @AfterEach
    void tearDown() {
        try {
            if (this.culler != null) {
                this.culler.delete();
            }
        } finally {
            try {
                if (this.meta != null) {
                    GpuCulling.onWorldUnload();
                }
                if (this.drivenCuller != null) {
                    this.drivenCuller.shutdown();
                }
                for (PassScene scene : this.passScenes.values()) {
                    scene.storage.delete();
                    if (scene.tessellation != null) {
                        scene.tessellation.delete(this.commandList);
                    }
                    this.commandList.deleteBuffer(scene.indexBuffer);
                }
                if (this.vertexBuffer != null) {
                    this.commandList.deleteBuffer(this.vertexBuffer);
                }
                if (this.cpuTarget != null) {
                    this.cpuTarget.close();
                }
                if (this.gpuTarget != null) {
                    this.gpuTarget.close();
                }
                if (this.program != null) {
                    this.program.delete();
                }
            } finally {
                RenderDevice.INSTANCE.makeInactive();
                if (this.previousMultiDrawMode != null) {
                    EmbeddiumRuntimeOptions.setChunkMultiDrawMode(() -> this.previousMultiDrawMode);
                }
                GpuCulling.setMode(GpuCullingMode.CPU_ONLY);
            }
        }
    }

    @Test
    void computeCulledDrawsOfEveryPassMatchTheCpuAssembledDraws() {
        final ChunkRenderMatrices matrices = new ChunkRenderMatrices(this.projection, this.modelView);

        this.culler.prepareAllPasses(matrices, this.renderLists, this.camera, this.camera, false);
        assertTrue(this.culler.isComputeActiveThisPass(), "the culler did not switch to the compute path");

        final List<PassScene> scenes = new ArrayList<>(this.passScenes.values());
        assertEquals(1, scenes.stream().filter(scene -> !scene.sorted).count(),
            "the fixture must draw one unsorted pass, whose commands read the shared index buffer");
        assertTrue(scenes.stream().filter(scene -> scene.sorted).count() > 1,
            "the fixture must keep more than one sorted pass, whose commands read their own index ranges");
        assertTrue(scenes.get(0).gpuCommandCount > GpuDrivenChunkCuller.LOCAL_SIZE_X,
            "the first sorted pass must hold more sections than a single work group covers");
        assertTrue(scenes.get(1).gpuCommandCount < scenes.get(0).gpuCommandCount,
            "the two sorted passes must hold different section sets, or a shared command range cannot be detected");

        assertScreenEdgeSectionIsCovered();

        this.cpuTarget.clear();
        this.gpuTarget.clear();
        GLSMPrograms.glUseProgram(this.program.handle());

        for (Map.Entry<TerrainRenderPass, PassScene> entry : this.passScenes.entrySet()) {
            this.cpuTarget.bind();
            drawCpuPass(entry.getKey(), entry.getValue());
            this.gpuTarget.bind();
            drawGpuPass(matrices, entry.getKey(), entry.getValue());
            assertDepthParity(entry.getKey().name());
        }
    }

    private void drawCpuPass(TerrainRenderPass pass, PassScene scene) {
        final MultiDrawBatch assembled = BatchAssembler.fillRegion(this.region, scene.storage, scene.renderList,
            this.camera, pass, false, MultiDrawMode.DIRECT);
        assertNotNull(assembled, "the CPU assembler found no commands for " + pass.name());
        final DirectMultiDrawBatch batch = (DirectMultiDrawBatch) assembled;
        try {
            assertCpuCommandsMatchTheFixture(pass, scene, batch);
            batch.upload(this.commandList);
            // The production redirector rewrites both the direct and the indirect draw into GLSM, so the reference has
            // to enter through the same draw entry; the harness has no redirector, which would otherwise leave the two
            // paths under different state handling.
            try (DrawCommandList ignored = this.commandList.beginTessellating(scene.tessellation)) {
                GLSMDraws.glMultiDrawElementsBaseVertex(GlPrimitiveType.TRIANGLES.getId(), batch.pElementCount,
                    GL11.GL_UNSIGNED_INT, batch.pElementPointer, batch.size(), batch.pBaseVertex);
            }
            assertEquals(0, GL11.glGetError(), "the CPU draw of " + pass.name() + " raised a GL error");
        } finally {
            batch.delete();
        }
    }

    private void drawGpuPass(ChunkRenderMatrices matrices, TerrainRenderPass pass, PassScene scene) {
        this.culler.beginRenderPass(matrices, this.renderLists, pass, this.camera, this.camera, false);

        final MultiDrawBatch batch = this.culler.batchForRegion(this.region);
        assertNotNull(batch, "the compute culler produced no commands for " + pass.name());
        try {
            assertEquals(scene.gpuCommandCount, batch.size(),
                "the compute culler prepared " + batch.size() + " commands for " + pass.name()
                    + " instead of the " + scene.gpuCommandCount + " its own runs hold");
            batch.execute(this.commandList, scene.tessellation, GlPrimitiveType.TRIANGLES);
            assertIndirectCommandsMatchTheFrustum(pass, scene);
        } finally {
            this.culler.endPass();
        }
    }

    /**
     * Verifies the CPU reference draws exactly the fixture's sections, so a parity failure can be attributed to the
     * compute path instead of to a broken reference.
     */
    private void assertCpuCommandsMatchTheFixture(TerrainRenderPass pass, PassScene scene, DirectMultiDrawBatch batch) {
        assertEquals(scene.cpuCommandCount, batch.size(),
            "the CPU reference assembled " + batch.size() + " commands for " + pass.name()
                + " instead of the " + scene.cpuCommandCount + " its sections hold");
        for (int command = 0; command < batch.size(); command++) {
            final int baseVertex = MemoryUtilities.memGetInt(batch.pBaseVertex + ((long) command << 2));
            final int elementCount = MemoryUtilities.memGetInt(batch.pElementCount + ((long) command << 2));
            final long elementPointer = MemoryUtilities.memGetAddress(batch.pElementPointer
                + ((long) command << Integer.numberOfTrailingZeros(LWJGL.getPointerSize())));
            final int indexBase = (int) (elementPointer / 4L);
            assertEquals(scene.elementsPerSection, elementCount,
                "every CPU command of " + pass.name() + " covers its section's quads");
            assertTrue(scene.vertexOffsets.containsValue(baseVertex),
                "CPU command " + command + " of " + pass.name() + " names base vertex " + baseVertex + ", which no section owns");
            if (scene.sorted) {
                assertTrue(scene.indexOffsets.containsValue(indexBase),
                    "CPU command " + command + " of " + pass.name() + " names index base " + indexBase + ", which no section owns");
            } else {
                assertEquals(0, indexBase, "CPU command " + command + " of the unsorted pass " + pass.name()
                    + " must read the shared index buffer from element zero");
            }
        }
    }

    /**
     * Compares the commands the dispatch wrote for every section against the padded frustum test the plane equations
     * implement: a section the compute path keeps must carry one instance per facing run and its whole element count,
     * and a section outside the padded frustum must carry none.
     *
     * <p>The commands are matched by their base vertex, which falls inside the section's own vertex range, so the check
     * does not depend on the order the pass appended its entries in and covers every run of a section.
     */
    private void assertIndirectCommandsMatchTheFrustum(TerrainRenderPass pass, PassScene scene) {
        final int indirectBuffer = GL11.glGetInteger(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING);
        assertNotEquals(0, indirectBuffer, "the compute draw of " + pass.name() + " must bind its indirect buffer");

        final int commandBytes = GpuDrivenChunkCuller.INDIRECT_COMMAND_BYTES;
        final ByteBuffer commands = ByteBuffer.allocateDirect(this.commandSlots * commandBytes).order(ByteOrder.nativeOrder());
        GLSMBuffers.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, indirectBuffer);
        GLSMBuffers.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, commands);

        final Matrix4f mvp = this.projection.mul(this.modelView, new Matrix4f());
        final FrustumIntersection reference = new FrustumIntersection().set(mvp);
        final ByteBuffer mirror = this.meta.getMirrorForReadOnly();
        assertNotNull(mirror, "the section mirror must hold the fixture's sections");
        final int[] slots = scene.storage.getSlotCache();
        assertNotNull(slots, "the fixture must publish its sections to the shared mirror");

        for (Map.Entry<Integer, Integer> section : scene.sections.entrySet()) {
            final int sectionIndex = section.getKey();
            final int originX = this.region.getOriginX() + (LocalSectionIndex.unpackX(sectionIndex) << 4);
            final int originY = this.region.getOriginY() + (LocalSectionIndex.unpackY(sectionIndex) << 4);
            final int originZ = this.region.getOriginZ() + (LocalSectionIndex.unpackZ(sectionIndex) << 4);
            final int mirrored = slots[sectionIndex] * SectionMetaBuffer.BYTES_PER_SECTION;
            assertEquals(originX, mirror.getInt(mirrored), "the mirror row of section index " + sectionIndex + " holds another section's x");
            assertEquals(originY, mirror.getInt(mirrored + 4), "the mirror row of section index " + sectionIndex + " holds another section's y");
            assertEquals(originZ, mirror.getInt(mirrored + 8), "the mirror row of section index " + sectionIndex + " holds another section's z");

            final boolean visible = visible(reference, originX, originY, originZ, FrustumExtractor.SECTION_PADDING - 1e-3);
            final int vertexOffset = scene.vertexOffsets.get(sectionIndex);
            // A run of the section names a base vertex inside the section's own vertex range, which is what identifies
            // the section's commands without depending on the order the pass appended them in.
            final int vertexEnd = vertexOffset + scene.verticesPerSection;
            int keptCommands = 0;
            int keptElements = 0;
            for (int command = 0; command < this.commandSlots; command++) {
                final int offset = command * commandBytes;
                final int baseVertex = commands.getInt(offset + 12);
                if (baseVertex < vertexOffset || baseVertex >= vertexEnd || commands.getInt(offset + 4) == 0) {
                    continue;
                }
                keptCommands++;
                keptElements += commands.getInt(offset);
                if (scene.sorted) {
                    assertEquals(scene.indexOffsets.get(sectionIndex), commands.getInt(offset + 8),
                        "section (" + originX + ", " + originY + ", " + originZ + ") of " + pass.name()
                            + " must read its own index range");
                } else {
                    assertEquals(0, commands.getInt(offset + 8),
                        "section (" + originX + ", " + originY + ", " + originZ + ") of the unsorted pass " + pass.name()
                            + " must read the shared index buffer from element zero");
                }
            }

            assertEquals(visible ? scene.runsPerSection : 0, keptCommands,
                "section (" + originX + ", " + originY + ", " + originZ + ") of " + pass.name() + " has "
                    + keptCommands + " live commands but the padded frustum test says " + (visible ? "visible" : "culled"));
            if (visible) {
                assertEquals(scene.elementsPerSection, keptElements,
                    "section (" + originX + ", " + originY + ", " + originZ + ") of " + pass.name()
                        + " must draw its whole element count");
            }
        }
    }

    /**
     * Requires the fixture to contain a section whose exact box is outside the frustum while its padded box reaches
     * inside it. That is the section the compute path used to drop at the screen edge, so the parity draws below have
     * to cover one.
     */
    private void assertScreenEdgeSectionIsCovered() {
        final Matrix4f mvp = this.projection.mul(this.modelView, new Matrix4f());
        final FrustumIntersection reference = new FrustumIntersection().set(mvp);
        int straddling = 0;
        for (int sectionIndex : this.passScenes.values().iterator().next().sections.keySet()) {
            final int originX = this.region.getOriginX() + (LocalSectionIndex.unpackX(sectionIndex) << 4);
            final int originY = this.region.getOriginY() + (LocalSectionIndex.unpackY(sectionIndex) << 4);
            final int originZ = this.region.getOriginZ() + (LocalSectionIndex.unpackZ(sectionIndex) << 4);
            final boolean insideExact = visible(reference, originX, originY, originZ, 0.0);
            final boolean insidePadded = visible(reference, originX, originY, originZ, FrustumExtractor.SECTION_PADDING - 1e-3);
            if (!insideExact && insidePadded) {
                straddling++;
            }
        }
        assertTrue(straddling > 0, "the fixture has no section straddling a side plane, so it does not cover the screen edge");
    }

    private boolean visible(FrustumIntersection reference, int originX, int originY, int originZ, double padding) {
        return reference.testAab(
            (float) (originX - padding - this.camera.x),
            (float) (originY - padding - this.camera.y),
            (float) (originZ - padding - this.camera.z),
            (float) (originX + 16.0 + padding - this.camera.x),
            (float) (originY + 16.0 + padding - this.camera.y),
            (float) (originZ + 16.0 + padding - this.camera.z));
    }

    private void assertDepthParity(String passName) {
        final FloatBuffer cpu = this.cpuTarget.readDepth();
        final FloatBuffer gpu = this.gpuTarget.readDepth();
        final int covered = coveredPixels(cpu);
        assertTrue(covered > 0, passName + ": the CPU reference drew nothing, so the fixture does not test drawing");
        assertEquals(covered, coveredPixels(gpu),
            passName + ": the compute-culled draw covers a different number of pixels than the CPU draw");
        for (int i = 0; i < cpu.capacity(); i++) {
            if (cpu.get(i) != gpu.get(i)) {
                assertEquals(cpu.get(i), gpu.get(i),
                    passName + ": depth differs at (" + (i % FBO_SIZE) + "," + (i / FBO_SIZE)
                        + "); the compute culler drew different geometry");
            }
        }
    }

    /**
     * Loads one pass into the region: a storage, a metadata entry per section, its index buffer, and the number of
     * commands each draw path produces for it.
     *
     * <p>A sorted pass gives every section its own index range in the sorted index buffer. An unsorted pass merges the
     * facings of a section into runs against one quad index buffer that every section shares from element zero, so its
     * draws name no per-section index base at all. The two paths therefore agree on the vertices and the element counts
     * a section draws, but not on how many commands they need for it.
     *
     * @param zBias   depth offset of this pass's quads, which lets one pass draw in front of another
     * @param facings the populated facings of every section, in the order their vertices are laid out
     */
    private void addPass(TerrainRenderPass pass, Map<Integer, Integer> quadsBySection, ChunkRenderList renderList,
                         GeometryBuilder geometry, float zBias, ModelQuadFacing[] facings) {
        final SectionRenderDataStorage storage = this.region.createStorage(pass, PASS_CONFIGURATION);
        final boolean sorted = pass.isSorted();
        final int indicesPerQuad = PRIMITIVE_TYPE.getIndexBufferElementsPerPrimitive();
        final int verticesPerQuad = PRIMITIVE_TYPE.getVerticesPerPrimitive();
        final int quadsPerFacing = Collections.max(quadsBySection.values());
        for (int quads : quadsBySection.values()) {
            assertEquals(quadsPerFacing, quads,
                "every section of one pass must hold the same number of quads, or one shared index buffer cannot serve it");
        }

        final int indexElements = (sorted ? quadsBySection.size() : 1) * quadsPerFacing * indicesPerQuad;
        final ByteBuffer indices = BufferUtils.createByteBuffer(indexElements * 4).order(ByteOrder.nativeOrder());
        // The draw adds the section's vertex base, so the index buffer keeps the quad's vertices relative to it. An
        // unsorted pass repeats that pattern once for the whole pass, because every section starts at element zero.
        for (int quad = 0; quad < (sorted ? quadsBySection.size() * quadsPerFacing : quadsPerFacing); quad++) {
            final int base = (quad % quadsPerFacing) * verticesPerQuad;
            indices.putInt(base).putInt(base + 1).putInt(base + 2)
                .putInt(base + 2).putInt(base + 3).putInt(base);
        }

        final int verticesPerSection = quadsPerFacing * facings.length * verticesPerQuad;
        final int elementsPerSection = quadsPerFacing * facings.length * indicesPerQuad;
        int indexCursor = 0;
        int cpuCommandCount = 0;
        int gpuCommandCount = 0;
        final Map<Integer, Integer> vertexOffsets = new LinkedHashMap<>();
        final Map<Integer, Integer> indexOffsets = new LinkedHashMap<>();

        for (Map.Entry<Integer, Integer> section : quadsBySection.entrySet()) {
            final int sectionIndex = section.getKey();
            final int vertexOffset = geometry.addSection(sectionIndex, quadsPerFacing, zBias, facings);
            vertexOffsets.put(sectionIndex, vertexOffset);

            if (sorted) {
                indexOffsets.put(sectionIndex, indexCursor);
            }

            final Map<ModelQuadFacing, VertexRange> ranges = new LinkedHashMap<>();
            int facingVertexOffset = vertexOffset;
            for (ModelQuadFacing facing : facings) {
                ranges.put(facing, new VertexRange(facingVertexOffset, quadsPerFacing * verticesPerQuad));
                facingVertexOffset += quadsPerFacing * verticesPerQuad;
            }

            storage.setMeshes(sectionIndex, new TestSegment(vertexOffset, verticesPerSection),
                sorted ? new TestSegment(indexCursor, elementsPerSection) : null, ranges);
            indexCursor += elementsPerSection;

            final int sliceMask = storage.getSliceMask(sectionIndex);
            assertTrue(sliceMask != 0, "section " + sectionIndex + " of " + pass.name() + " must hold geometry");
            final int[] slots = storage.getSlotCache();
            assertNotNull(slots, "publishing a section must fill the slot cache of " + pass.name());
            assertTrue(slots[sectionIndex] >= 0,
                "section " + sectionIndex + " of " + pass.name() + " must hold a slot in the shared section mirror");
            // The sorted emitter writes one command per populated facing; the unsorted emitter merges the whole section
            // into one span and the compute path splits it into one command per facing run again.
            cpuCommandCount += sorted ? Integer.bitCount(sliceMask) : 1;
            gpuCommandCount += sorted
                ? Integer.bitCount(sliceMask)
                : BatchAssembler.runCount(BatchAssembler.packRuns(sliceMask & ModelQuadFacing.ALL));
        }
        indices.flip();

        final GlMutableBuffer indexBuffer = this.commandList.createMutableBuffer();
        this.commandList.uploadData(indexBuffer, indices, GlBufferUsage.STATIC_DRAW);

        for (int sectionIndex : quadsBySection.keySet()) {
            if (!hasGeometry(renderList, sectionIndex)) {
                renderList.add(sectionIndex, BLOCK_GEOMETRY_FLAG);
            }
        }

        this.passScenes.put(pass, new PassScene(storage, indexBuffer, renderList, sorted, cpuCommandCount,
            gpuCommandCount, BatchAssembler.runCount(BatchAssembler.packRuns(facingMask(facings))),
            elementsPerSection, verticesPerSection, quadsBySection, vertexOffsets, indexOffsets));
    }

    private static int facingMask(ModelQuadFacing[] facings) {
        int mask = 0;
        for (ModelQuadFacing facing : facings) {
            mask |= 1 << facing.ordinal();
        }
        return mask;
    }

    /**
     * The rows of the region this pass covers, with every {@code columnStep}-th column of each row. The second sorted
     * pass uses fewer rows and wider spacing and the unsorted pass a third row count, so no two passes can share a
     * command range.
     */
    private static Map<Integer, Integer> sections(int rows, int columnStep, int quads) {
        final Map<Integer, Integer> sections = new LinkedHashMap<>();
        for (int chunkY = 0; chunkY < rows; chunkY++) {
            for (int chunkX = 0; chunkX < REGION_CHUNK_RADIUS; chunkX += columnStep) {
                for (int chunkZ = 0; chunkZ < REGION_CHUNK_RADIUS; chunkZ++) {
                    sections.put(LocalSectionIndex.pack(chunkX, chunkY, chunkZ), quads);
                }
            }
        }
        return sections;
    }

    private static boolean hasGeometry(ChunkRenderList renderList, int sectionIndex) {
        final byte[] sections = renderList.getSectionsWithGeometry();
        for (int i = 0; i < renderList.getSectionsWithGeometryCount(); i++) {
            if ((sections[i] & 0xFF) == sectionIndex) {
                return true;
            }
        }
        return false;
    }

    private static TerrainRenderPass sortedPass(String name, TerrainRenderPass.Semantic semantic) {
        return TerrainRenderPass.builder()
            .name(name)
            .semantic(semantic)
            .primitiveType(PRIMITIVE_TYPE)
            .vertexType(new CompactChunkVertex())
            .useReverseOrder(true)
            .useTranslucencySorting(true)
            .writesDepth(false)
            .build();
    }

    private static TerrainRenderPass unsortedPass(String name, TerrainRenderPass.Semantic semantic) {
        return TerrainRenderPass.builder()
            .name(name)
            .semantic(semantic)
            .primitiveType(PRIMITIVE_TYPE)
            .vertexType(new CompactChunkVertex())
            .useReverseOrder(false)
            .useTranslucencySorting(false)
            .writesDepth(true)
            .build();
    }

    private GlVertexArrayTessellation tessellation(GlMutableBuffer indices) {
        final GlVertexAttribute position = new GlVertexAttribute(GlVertexAttributeFormat.FLOAT, "a_Pos", 3, false, 0, 12, false);
        final TessellationBinding[] bindings = {
            TessellationBinding.forVertexBuffer(this.vertexBuffer, new GlVertexAttributeBinding[] { new GlVertexAttributeBinding(0, position) }),
            TessellationBinding.forElementBuffer(indices),
        };
        final GlVertexArrayTessellation tessellation = new GlVertexArrayTessellation(new GlVertexArray(), bindings);
        tessellation.init(this.commandList);
        return tessellation;
    }

    private static GlProgram<Void> createFlatProgram() {
        final GlShader vertex = new GlShader(ShaderType.VERTEX, "flat_vertex", FLAT_VERTEX_SHADER);
        final GlShader fragment = new GlShader(ShaderType.FRAGMENT, "flat_fragment", FLAT_FRAGMENT_SHADER);
        try {
            return GlProgram.builder("flat").attachShader(vertex).attachShader(fragment).link(context -> null);
        } finally {
            vertex.delete();
            fragment.delete();
        }
    }

    private static int coveredPixels(FloatBuffer depth) {
        int covered = 0;
        for (int i = 0; i < depth.capacity(); i++) {
            if (depth.get(i) < 1.0f) {
                covered++;
            }
        }
        return covered;
    }

    /**
     * One pass of the fixture: its storage, its index buffer, the sections it covers and what each draw path produces
     * for them. The two paths agree on the vertices and element counts a section draws, but a sorted pass emits one
     * command per populated facing while the unsorted emitter merges a section into one span. A sorted scene keeps the
     * index range of every section; an unsorted scene shares one index buffer, so it records no per-section index
     * range at all.
     */
    private static final class PassScene {
        private final SectionRenderDataStorage storage;
        private final GlMutableBuffer indexBuffer;
        private final ChunkRenderList renderList;
        private final boolean sorted;
        private final int cpuCommandCount;
        private final int gpuCommandCount;
        private final int runsPerSection;
        private final int elementsPerSection;
        private final int verticesPerSection;
        private final Map<Integer, Integer> sections;
        private final Map<Integer, Integer> vertexOffsets;
        private final Map<Integer, Integer> indexOffsets;
        private GlVertexArrayTessellation tessellation;

        private PassScene(SectionRenderDataStorage storage, GlMutableBuffer indexBuffer, ChunkRenderList renderList,
                          boolean sorted, int cpuCommandCount, int gpuCommandCount, int runsPerSection,
                          int elementsPerSection, int verticesPerSection, Map<Integer, Integer> sections,
                          Map<Integer, Integer> vertexOffsets, Map<Integer, Integer> indexOffsets) {
            this.storage = storage;
            this.indexBuffer = indexBuffer;
            this.renderList = renderList;
            this.sorted = sorted;
            this.cpuCommandCount = cpuCommandCount;
            this.gpuCommandCount = gpuCommandCount;
            this.runsPerSection = runsPerSection;
            this.elementsPerSection = elementsPerSection;
            this.verticesPerSection = verticesPerSection;
            this.sections = sections;
            this.vertexOffsets = vertexOffsets;
            this.indexOffsets = indexOffsets;
        }
    }

    private static final class TestRenderLists implements ChunkRenderListIterable {
        private final ChunkRenderList list;
        private final Set<TerrainRenderPass> passes;

        private TestRenderLists(ChunkRenderList list, Set<TerrainRenderPass> passes) {
            this.list = list;
            this.passes = Set.copyOf(passes);
        }

        @Override
        public Iterator<ChunkRenderList> iterator(boolean reverse) {
            return List.of(this.list).iterator();
        }

        @Override
        public int getNumRegions() {
            return 1;
        }

        @Override
        public ChunkRenderList getRegion(int index) {
            if (index != 0) {
                throw new IndexOutOfBoundsException("single-region fixture: " + index);
            }
            return this.list;
        }

        @Override
        public boolean hasPass(TerrainRenderPass pass) {
            return this.passes.contains(pass);
        }

        @Override
        public Set<TerrainRenderPass> getPasses() {
            return this.passes;
        }
    }

    /**
     * Builds the vertex data of every section. Sections are laid out along the region grid, with a quad inside the box
     * on the side that faces the camera. The flat test shader passes positions straight through, so the builder projects
     * them into clip space itself; visibility is still decided per section by the culler's metadata, not by these
     * vertices. The per-pass depth offset moves one pass's quads along the view axis so that its depth writes land in
     * front of the geometry the other passes already drew.
     */
    private static final class GeometryBuilder {
        private final FloatBuffer vertices =
            BufferUtils.createFloatBuffer(REGION_CHUNK_RADIUS * REGION_CHUNK_RADIUS * 3 * 4 * 3 * 8);
        private final Matrix4f projection;
        private final Vector4f scratch = new Vector4f();
        private int vertexCount;

        private GeometryBuilder(Matrix4f projection) {
            this.projection = projection;
        }

        private int addSection(int sectionIndex, int quadsPerFacing, float zBias, ModelQuadFacing[] facings) {
            final float originX = LocalSectionIndex.unpackX(sectionIndex) * 16.0f - EYE_X;
            final float originY = LocalSectionIndex.unpackY(sectionIndex) * 16.0f - EYE_Y;
            final float originZ = LocalSectionIndex.unpackZ(sectionIndex) * 16.0f - EYE_Z;
            final int firstVertex = this.vertexCount;

            // The metadata lays a section's vertices out in facing order, so the vertices of each facing form one block.
            for (int facing = 0; facing < facings.length; facing++) {
                for (int quad = 0; quad < quadsPerFacing; quad++) {
                    final float z = originZ + SECTION_INSET + quad + zBias;
                    final float minX = originX + SECTION_INSET;
                    final float minY = originY + SECTION_INSET + quad;
                    final float maxX = originX + 16.0f - SECTION_INSET;
                    final float maxY = minY + 4.0f;
                    putVertex(minX, minY, z);
                    putVertex(maxX, minY, z);
                    putVertex(maxX, maxY, z);
                    putVertex(minX, maxY, z);
                }
            }
            return firstVertex;
        }

        private void putVertex(float x, float y, float z) {
            this.scratch.set(x, y, z, 1.0f).mul(this.projection);
            final float w = this.scratch.w;
            if (w <= 0.0f) {
                throw new IllegalStateException("quad vertex behind the camera: w=" + w);
            }
            this.vertices.put(this.scratch.x / w).put(this.scratch.y / w).put(this.scratch.z / w);
            this.vertexCount++;
        }

        private GlMutableBuffer upload(CommandList commandList) {
            this.vertices.flip();
            final ByteBuffer bytes = BufferUtils.createByteBuffer(this.vertices.remaining() * 4).order(ByteOrder.nativeOrder());
            bytes.asFloatBuffer().put(this.vertices);
            final GlMutableBuffer buffer = commandList.createMutableBuffer();
            commandList.uploadData(buffer, bytes, GlBufferUsage.STATIC_DRAW);
            return buffer;
        }
    }

    /**
     * A depth-plus-colour framebuffer the two draw paths render into, so their depth buffers can be compared.
     */
    private static final class Target implements AutoCloseable {
        private final int framebuffer;
        private final int depthTexture;
        private final int colorTexture;

        private Target() {
            this.depthTexture = GLSMTextures.glGenTextures();
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, this.depthTexture);
            GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F, FBO_SIZE, FBO_SIZE, 0,
                GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null);
            GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

            this.colorTexture = GLSMTextures.glGenTextures();
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, this.colorTexture);
            GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, FBO_SIZE, FBO_SIZE, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);

            this.framebuffer = GLSMFramebuffer.glGenFramebuffers();
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.framebuffer);
            GLSMFramebuffer.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D,
                this.depthTexture, 0);
            GLSMFramebuffer.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                this.colorTexture, 0);
            assertEquals(GL30.GL_FRAMEBUFFER_COMPLETE, GLSMFramebuffer.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER),
                "the parity framebuffer is incomplete");
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        }

        private void clear() {
            this.bind();
            GLSMDepthStencilAlpha.glClearDepth(1.0);
            GLSMBlend.glClearColor(0f, 0f, 0f, 0f);
            GLSMFramebuffer.glClear(GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT);
        }

        /**
         * Makes this target the draw target with the depth state both paths render under.
         */
        private void bind() {
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.framebuffer);
            GLSMRasterizer.glViewport(0, 0, FBO_SIZE, FBO_SIZE);
            GLSMEnable.glEnable(GL11.GL_DEPTH_TEST);
            GLSMDepthStencilAlpha.glDepthFunc(GL11.GL_LESS);
            GLSMDepthStencilAlpha.glDepthMask(true);
            GLSMRasterizer.disableCull();
        }

        private FloatBuffer readDepth() {
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, this.framebuffer);
            final FloatBuffer depth = BufferUtils.createFloatBuffer(FBO_SIZE * FBO_SIZE);
            GLSMFramebuffer.glReadPixels(0, 0, FBO_SIZE, FBO_SIZE, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
            return depth;
        }

        @Override
        public void close() {
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GLSMFramebuffer.glDeleteFramebuffers(this.framebuffer);
            GLSMTextures.glDeleteTextures(this.depthTexture);
            GLSMTextures.glDeleteTextures(this.colorTexture);
        }
    }

    /**
     * A buffer segment whose allocation the test does not own a GL arena for. The metadata layout only reads the
     * offset, so releasing the segment must stay a no-op instead of reaching an arena that does not exist.
     */
    private static final class TestSegment extends GlBufferSegment {
        private TestSegment(int offset, int length) {
            super(null, offset, length);
        }

        @Override
        public void delete() {
        }
    }
}
