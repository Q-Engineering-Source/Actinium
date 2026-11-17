package com.gtnewhorizons.angelica.rendering.tesr;

import com.dhj.actinium.render.TessellatorDrawRouter;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMMatrices;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import com.gtnewhorizons.angelica.glsm.ITessellatorData;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Draws an open builder through an open batch.
 *
 * <p>The batch reads the vertices itself and resets the builder when it is done, so a draw that
 * reaches it still open is consumed; a draw that was finished first leaves the streaming path with
 * a builder that reports "not tesselating".</p>
 *
 * <p>The builder's eight-int vertex view comes from a mixin, which the test harness does not apply,
 * so the view is built here from the same vertices with {@link SharedVertexLayout}.</p>
 */
class TessellatorDrawRouterGLTest extends GLStateManagerIntegrationFixture {

    /** The builder's vertices in the shared layout, reporting the drawing state the test opened. */
    private static final class BufferView implements ITessellatorData {
        private final BufferBuilder buffer;
        private final int[] vertices = new int[4 * SharedVertexLayout.VERTEX_INTS];
        private boolean drawing = true;
        private int[] storage = vertices;
        int limitAtConsume = -1;

        BufferView(BufferBuilder buffer) {
            this.buffer = buffer;
        }

        @Override
        public boolean isDrawing() {
            return drawing;
        }

        @Override
        public void setDrawing(boolean drawing) {
            this.drawing = drawing;
        }

        @Override
        public int getVertexCount() {
            return buffer.getVertexCount();
        }

        @Override
        public int[] getRawBuffer() {
            return storage;
        }

        @Override
        public int getRawBufferIndex() {
            return buffer.getVertexCount() * SharedVertexLayout.VERTEX_INTS;
        }

        @Override
        public int getRawBufferSize() {
            return storage.length;
        }

        @Override
        public void setRawBufferSize(int size) {
        }

        @Override
        public void setRawBuffer(int[] buffer) {
            storage = buffer;
        }

        @Override
        public int getDrawMode() {
            return buffer.getDrawMode();
        }

        @Override
        public boolean hasTexture() {
            return true;
        }

        @Override
        public boolean hasColor() {
            return false;
        }

        @Override
        public boolean hasNormals() {
            return true;
        }

        @Override
        public boolean hasBrightness() {
            return false;
        }

        @Override
        public void angelica$reset() {
            // finishDrawing() narrows the byte buffer to the vertex data, so the limit records whether the
            // builder was still open when the batch took it.
            limitAtConsume = buffer.getByteBuffer().limit();
            drawing = false;
            buffer.reset();
        }
    }

    private boolean savedBatching;

    @BeforeEach
    void setUp() {
        WindowBackends.useSdl();
        installDefaultGlsmConfig();
        GLStateManager.markSplashComplete("TessellatorDrawRouterGLTest");
        GLStateManager.bindDisplayContext(new Object(), context);
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMMatrices.glLoadIdentity();
        GLSMDepthStencilAlpha.enableDepthTest();
        savedBatching = ActiniumRuntime.options().performance.enableEntityBatching;
        ActiniumRuntime.options().performance.enableEntityBatching = true;
        ShaderManager.enable();
        TesrBatchRenderer.INSTANCE.beginPass(TesrBatchRenderer.PASS_MAIN_0, new Matrix4f(), 0.0, 0.0, 0.0);
        ModelPartBatcher.INSTANCE.begin(ModelPartBatcher.Mode.BLOCK_ENTITIES);
    }

    @AfterEach
    void tearDown() {
        // The Tessellator instance is shared, so leave its builder closed for the next test.
        Tessellator.getInstance().getBuffer().reset();
        ModelPartBatcher.INSTANCE.flush();
        TesrBatchRenderer.INSTANCE.flush();
        ActiniumRuntime.options().performance.enableEntityBatching = savedBatching;
        ShaderManager.disable();
    }

    @Test
    void anOpenBatchConsumesTheBuilderItTakes() {
        assertTrue(ModelPartBatcher.isBatchingActive(), "the batch has to be open for this path to be exercised");

        final BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
        buffer.pos(0.0, 0.0, 0.0).tex(0.0, 0.0).normal(0.0F, 1.0F, 0.0F).endVertex();
        buffer.pos(1.0, 0.0, 0.0).tex(1.0, 0.0).normal(0.0F, 1.0F, 0.0F).endVertex();
        buffer.pos(1.0, 1.0, 0.0).tex(1.0, 1.0).normal(0.0F, 1.0F, 0.0F).endVertex();
        buffer.pos(0.0, 1.0, 0.0).tex(0.0, 1.0).normal(0.0F, 1.0F, 0.0F).endVertex();

        final BufferView view = new BufferView(buffer);
        SharedVertexLayout.pack(buffer.getByteBuffer(), buffer.getVertexFormat(), buffer.getVertexCount(),
            view.vertices);

        assertDoesNotThrow(() -> TessellatorDrawRouter.draw(buffer, view));

        assertEquals(buffer.getByteBuffer().capacity(), view.limitAtConsume,
            "the batch must take the builder before anything finishes the draw");
        assertFalse(view.isDrawing(), "the batch closes the builder it consumed");
        assertFalse(buffer.getVertexCount() > 0, "the batch resets the builder it consumed");
    }
}
