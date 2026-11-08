package dhj.embeddedt.embeddium.impl.render.culling;

import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import dhj.embeddedt.embeddium.impl.gl.arena.GlBufferSegment;
import dhj.embeddedt.embeddium.impl.gl.util.VertexRange;
import dhj.embeddedt.embeddium.impl.model.quad.properties.ModelQuadFacing;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.QuadPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.data.SectionRenderDataStorage;
import dhj.embeddedt.embeddium.impl.render.viewport.CameraTransform;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL43;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drives the compute cull backend against a real driver. The cull program has to link, a dispatch has to turn the
 * uploaded section metadata into the indirect commands the draw call reads for each output slot it is given, and the
 * indexed buffer bindings and program the dispatch borrows have to come back exactly as the caller left them, because
 * the terrain draw path keeps its own buffers bound between passes.
 */
class GpuDrivenChunkCullerGLTest extends GLStateManagerIntegrationFixture {

    private static final int VISIBLE_SSBO_BINDING = 0;
    private static final int META_SSBO_BINDING = 1;
    private static final int INDIRECT_SSBO_BINDING = 2;
    private static final int FRUSTUM_UBO_BINDING = 1;

    private static final int SECTION_QUADS = 3;
    private static final int SECOND_OUTPUT_BASE = 7;

    private static final QuadPrimitiveType PRIMITIVE_TYPE = QuadPrimitiveType.TRIANGULATED;

    @BeforeAll
    static void requireComputeContext() {
        assumeTrue(GL.getCapabilities().OpenGL43, "compute-driven culling needs a GL 4.3 context");
        assumeTrue(RenderSystem.supportsCompute(), "context reports no compute shader support");
    }

    @Test
    void dispatchTurnsSectionMetadataIntoDrawCommands() {
        try (CullFixture fixture = new CullFixture()) {
            fixture.dispatch();

            final ByteBuffer commands = fixture.readCommands(1);
            assertEquals(0, GL11.glGetError(), "the dispatch must not raise a GL error");
            assertEquals(SECTION_QUADS * PRIMITIVE_TYPE.getIndexBufferElementsPerPrimitive(), commands.getInt(0),
                "the command must cover every element of the section's visible facing");
            assertEquals(1, commands.getInt(4), "a visible section draws once");
            assertEquals(0, commands.getInt(8), "the unsorted shared index buffer starts at its first element");
            assertEquals(0, commands.getInt(12), "the section's vertices start at the vertex buffer origin");
        }
    }

    @Test
    void everyVisibleEntryWritesToItsOwnOutputSlot() {
        try (CullFixture fixture = new CullFixture()) {
            fixture.dispatch();

            final ByteBuffer commands = fixture.readCommands(SECOND_OUTPUT_BASE + 1);
            final int commandBytes = GpuDrivenChunkCuller.INDIRECT_COMMAND_BYTES;
            final int elementCount = SECTION_QUADS * PRIMITIVE_TYPE.getIndexBufferElementsPerPrimitive();
            assertEquals(elementCount, commands.getInt(0), "the first entry's slot carries its command");
            assertEquals(elementCount, commands.getInt(SECOND_OUTPUT_BASE * commandBytes),
                "the second entry's output slot carries its own command instead of overwriting the first");
            assertEquals(1, commands.getInt(SECOND_OUTPUT_BASE * commandBytes + 4),
                "the second entry must draw once as well");
        }
    }

    @Test
    void dispatchRestoresTheIndexedBufferBindingsItBorrows() {
        final int callerVisible = GLSMBuffers.glGenBuffers();
        final int callerMeta = GLSMBuffers.glGenBuffers();
        final int callerIndirect = GLSMBuffers.glGenBuffers();
        final int callerFrustum = GLSMBuffers.glGenBuffers();
        try (CullFixture fixture = new CullFixture()) {
            for (int buffer : new int[] { callerVisible, callerMeta, callerIndirect }) {
                GLSMBuffers.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
                GLSMBuffers.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, 64L, GL15.GL_DYNAMIC_DRAW);
            }
            GLSMBuffers.glBindBuffer(GL31.GL_UNIFORM_BUFFER, callerFrustum);
            GLSMBuffers.glBufferData(GL31.GL_UNIFORM_BUFFER, FrustumExtractor.UBO_SIZE_BYTES, GL15.GL_DYNAMIC_DRAW);

            RenderSystem.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, VISIBLE_SSBO_BINDING, callerVisible);
            RenderSystem.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, META_SSBO_BINDING, callerMeta);
            RenderSystem.bindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, INDIRECT_SSBO_BINDING, callerIndirect);
            RenderSystem.bindBufferBase(GL31.GL_UNIFORM_BUFFER, FRUSTUM_UBO_BINDING, callerFrustum);
            final int callerProgram = GLSMPrograms.getActiveProgram();

            fixture.culler.beginDispatches(fixture.visibleBuffer, fixture.indirectBuffer);

            assertEquals(fixture.visibleBuffer, RenderSystem.getIndexedBufferBinding(GL43.GL_SHADER_STORAGE_BUFFER, VISIBLE_SSBO_BINDING),
                "the dispatch must bind its own visible list");
            assertEquals(fixture.indirectBuffer, RenderSystem.getIndexedBufferBinding(GL43.GL_SHADER_STORAGE_BUFFER, INDIRECT_SSBO_BINDING),
                "the dispatch must bind its own indirect command buffer");
            assertNotEquals(callerMeta, RenderSystem.getIndexedBufferBinding(GL43.GL_SHADER_STORAGE_BUFFER, META_SSBO_BINDING),
                "the dispatch must bind the section metadata buffer it uploaded to");
            assertNotEquals(callerFrustum, RenderSystem.getIndexedBufferBinding(GL31.GL_UNIFORM_BUFFER, FRUSTUM_UBO_BINDING),
                "the dispatch must bind the frustum uniform block it uploaded to");
            assertNotEquals(callerProgram, GLSMPrograms.getActiveProgram(), "the dispatch must run the cull program");

            fixture.culler.endDispatches();

            assertEquals(callerVisible, RenderSystem.getIndexedBufferBinding(GL43.GL_SHADER_STORAGE_BUFFER, VISIBLE_SSBO_BINDING),
                "the caller's visible binding must come back");
            assertEquals(callerMeta, RenderSystem.getIndexedBufferBinding(GL43.GL_SHADER_STORAGE_BUFFER, META_SSBO_BINDING),
                "the caller's metadata binding must come back");
            assertEquals(callerIndirect, RenderSystem.getIndexedBufferBinding(GL43.GL_SHADER_STORAGE_BUFFER, INDIRECT_SSBO_BINDING),
                "the caller's indirect binding must come back");
            assertEquals(callerFrustum, RenderSystem.getIndexedBufferBinding(GL31.GL_UNIFORM_BUFFER, FRUSTUM_UBO_BINDING),
                "the caller's frustum binding must come back");
            assertEquals(callerProgram, GLSMPrograms.getActiveProgram(), "the caller's program must come back");
            assertEquals(0, GL11.glGetError(), "restoring the bindings must not raise a GL error");
        } finally {
            for (int buffer : new int[] { callerVisible, callerMeta, callerIndirect, callerFrustum }) {
                GLSMBuffers.glDeleteBuffers(buffer);
            }
        }
    }

    /**
     * Runs one section through the compute cull step: metadata for the section, two visible entries naming it with
     * different output slots, a frustum uniform block that bypasses the plane test, and a dispatch over the entries.
     */
    private static final class CullFixture implements AutoCloseable {
        private final GpuDrivenChunkCuller culler = new GpuDrivenChunkCuller();
        private final SectionMetaBuffer meta = new SectionMetaBuffer();
        private final SectionRenderDataStorage storage = new SectionRenderDataStorage(PRIMITIVE_TYPE, false);
        private final int visibleBuffer = GLSMBuffers.glGenBuffers();
        private final int indirectBuffer = GLSMBuffers.glGenBuffers();
        private final ByteBuffer frustumUbo = FrustumExtractor.allocateUboByteBuffer();
        private final int outputSlots = SECOND_OUTPUT_BASE + 1;

        private CullFixture() {
            try {
                assertTrue(this.culler.ensureReady(), "chunk_cull.csh did not compile or link on this driver");

                this.storage.setMeshes(0, new TestSegment(0, SECTION_QUADS * PRIMITIVE_TYPE.getVerticesPerPrimitive()), null,
                    Map.of(ModelQuadFacing.UNASSIGNED, new VertexRange(0, SECTION_QUADS * PRIMITIVE_TYPE.getVerticesPerPrimitive())));
                // The walk hands the shader the section's own slice mask, so a visible entry names the facing the mesh
                // actually holds. Only UNASSIGNED is populated here, so its bit is the whole mask.
                final int sliceMask = this.storage.getSliceMask(0);
                assertEquals(1 << ModelQuadFacing.UNASSIGNED.ordinal(), sliceMask,
                    "the fixture section must publish exactly the UNASSIGNED facing");
                final int slot = this.meta.update(0, 0, 0, 0, 0, this.storage.getDataPointer(0), sliceMask,
                    this.storage.getStrategy(), this.storage.getPrimitiveType());
                assertTrue(slot >= 0, "the section metadata entry must take a slot");
                assertTrue(this.meta.syncIfDirty(this.culler::uploadSectionMeta), "the section metadata must reach the driver");

                final ByteBuffer visible = ByteBuffer.allocateDirect(2 * GpuDrivenChunkCuller.VISIBLE_ENTRY_BYTES)
                    .order(ByteOrder.nativeOrder());
                writeEntry(visible, 0, slot, 0, sliceMask);
                writeEntry(visible, 1, slot, SECOND_OUTPUT_BASE, sliceMask);
                GLSMBuffers.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.visibleBuffer);
                GLSMBuffers.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, 2L * GpuDrivenChunkCuller.VISIBLE_ENTRY_BYTES, GL15.GL_STREAM_DRAW);
                GLSMBuffers.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, visible.position(0).limit(2 * GpuDrivenChunkCuller.VISIBLE_ENTRY_BYTES));

                GLSMBuffers.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.indirectBuffer);
                GLSMBuffers.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,
                    (long) this.outputSlots * GpuDrivenChunkCuller.INDIRECT_COMMAND_BYTES, GL15.GL_STREAM_DRAW);

                FrustumExtractor.writeFrustum(new Matrix4f(), new Matrix4f(), new CameraTransform(0.0, 0.0, 0.0),
                    new Matrix4f(), this.frustumUbo);
                FrustumExtractor.patchControl(2, 0, this.frustumUbo);
                FrustumExtractor.patchBatchEntryBase(0, this.frustumUbo);
                FrustumExtractor.patchBypassFrustum(true, this.frustumUbo);
                FrustumExtractor.patchPrimitiveRatio(PRIMITIVE_TYPE.getVerticesPerPrimitive(),
                    PRIMITIVE_TYPE.getIndexBufferElementsPerPrimitive(), this.frustumUbo);
                this.culler.uploadFrustum(this.frustumUbo);
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        private void dispatch() {
            this.culler.beginDispatches(this.visibleBuffer, this.indirectBuffer);
            try {
                this.culler.dispatch(2);
            } finally {
                this.culler.endDispatches();
            }
        }

        private ByteBuffer readCommands(int count) {
            final ByteBuffer commands = ByteBuffer.allocateDirect(count * GpuDrivenChunkCuller.INDIRECT_COMMAND_BYTES)
                .order(ByteOrder.nativeOrder());
            GLSMBuffers.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, this.indirectBuffer);
            GLSMBuffers.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0L, commands);
            return commands;
        }

        private static void writeEntry(ByteBuffer entries, int index, int slot, int outputBase, int facingMask) {
            final int offset = index * GpuDrivenChunkCuller.VISIBLE_ENTRY_BYTES;
            entries.putInt(offset, slot);
            // The visible facing mask in the low byte, the packed output base in the rest.
            entries.putInt(offset + 4, (outputBase << 8) | (facingMask & 0xFF));
        }

        @Override
        public void close() {
            this.culler.shutdown();
            this.meta.shutdown();
            this.storage.delete();
            GLSMBuffers.glDeleteBuffers(this.visibleBuffer);
            GLSMBuffers.glDeleteBuffers(this.indirectBuffer);
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
