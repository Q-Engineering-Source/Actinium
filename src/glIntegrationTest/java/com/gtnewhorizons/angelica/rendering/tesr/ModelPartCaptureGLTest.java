package com.gtnewhorizons.angelica.rendering.tesr;

import com.dhj.actinium.config.ActiniumRuntimeOptions;
import com.dhj.actinium.render.TessellatorDrawRouter;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.gtnewhorizon.gtnhlib.client.renderer.RuntimeOptionsBridge;
import com.gtnewhorizons.angelica.api.tesr.ModelPartBatch;
import com.gtnewhorizons.angelica.glsm.GLSMBlend;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.GLSMMatrices;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import com.gtnewhorizons.angelica.glsm.ITessellatorData;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBox;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Captures a legacy {@link ModelBox} part and checks the shared builder the capture borrows.
 *
 * <p>A box writes its faces through the shared tessellator: each face opens the builder, emits its
 * vertices and draws it. The harness applies no mixins, so the box below hands each face's
 * draw to {@link TessellatorDrawRouter} at the point where {@code MixinTessellator} would, and the
 * batch capture forwards it into its own tessellator.</p>
 */
class ModelPartCaptureGLTest extends GLStateManagerIntegrationFixture {

    private static final ResourceLocation ATLAS = new ResourceLocation("minecraft", "textures/atlas/blocks.png");
    private static final float SCALE = 0.0625F;
    private static final int FACES = 6;

    private static final float[][] CORNERS = {
        {0.0F, 0.0F, 0.0F}, {1.0F, 0.0F, 0.0F}, {1.0F, 1.0F, 0.0F}, {0.0F, 1.0F, 0.0F},
    };

    /** The builder's vertices in the shared layout, reporting the drawing state the box opened. */
    private static final class BufferView implements ITessellatorData {
        private final BufferBuilder buffer;
        private final int[] vertices = new int[4 * SharedVertexLayout.VERTEX_INTS];
        private boolean drawing = true;
        private int[] storage = vertices;

        BufferView(BufferBuilder buffer) {
            this.buffer = buffer;
        }

        @Override
        public boolean isDrawing() {
            return drawing;
        }

        @Override
        public void setDrawing(boolean drawing) {
            if (!drawing) {
                // The batch closes the builder it takes. The host mixin writes the private flag directly;
                // without mixins the same close has to go through the builder's own finishDrawing(), which
                // keeps the vertices written so far for the read that follows.
                buffer.finishDrawing();
            }
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
            drawing = false;
            buffer.reset();
        }
    }

    private static final class TestModel extends ModelBase {
    }

    /** A box that draws its six faces the way {@code TexturedQuad} does: one builder draw per face. */
    private static final class RoutedBox extends ModelBox {
        int faces;
        int trianglesAt = -1;

        RoutedBox(ModelRenderer renderer) {
            super(renderer, 0, 0, 0.0F, 0.0F, 0.0F, 1, 1, 1, 0.0F);
        }

        @Override
        public void render(BufferBuilder renderer, float scale) {
            for (int face = 0; face < FACES; face++) {
                final boolean triangles = face == trianglesAt;
                renderer.begin(triangles ? GL11.GL_TRIANGLES : GL11.GL_QUADS,
                    DefaultVertexFormats.OLDMODEL_POSITION_TEX_NORMAL);
                final int corners = triangles ? 3 : CORNERS.length;
                for (int corner = 0; corner < corners; corner++) {
                    final float[] xyz = CORNERS[corner];
                    renderer.pos(xyz[0] * scale, xyz[1] * scale, xyz[2] * scale)
                        .tex(corner == 1 || corner == 2 ? 1.0F : 0.0F, corner < 2 ? 0.0F : 1.0F)
                        .normal(0.0F, 0.0F, 1.0F)
                        .endVertex();
                }
                final BufferView view = new BufferView(renderer);
                SharedVertexLayout.pack(renderer.getByteBuffer(), renderer.getVertexFormat(),
                    renderer.getVertexCount(), view.vertices);
                TessellatorDrawRouter.draw(renderer, view);
                faces++;
            }
        }
    }

    private boolean savedBatching;
    private ModelRenderer part;
    private RoutedBox box;

    @BeforeEach
    void setUp() {
        WindowBackends.useSdl();
        installDefaultGlsmConfig();
        GLStateManager.markSplashComplete("ModelPartCaptureGLTest");
        GLStateManager.bindDisplayContext(new Object(), context);
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMMatrices.glLoadIdentity();
        GLSMDepthStencilAlpha.enableDepthTest();
        GLSMDepthStencilAlpha.glDepthFunc(GL11.GL_LEQUAL);
        GLSMDepthStencilAlpha.glDepthMask(true);
        GLSMDepthStencilAlpha.enableAlphaTest();
        GLSMDepthStencilAlpha.glAlphaFunc(GL11.GL_GREATER, 0.1F);
        GLSMBlend.enableBlend();
        GLSMBlend.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        GLSMEnable.glEnable(GL11.GL_TEXTURE_2D);
        savedBatching = ActiniumRuntime.options().performance.enableEntityBatching;
        ActiniumRuntime.options().performance.enableEntityBatching = true;
        ShaderManager.enable();
        TesrBatchRenderer.INSTANCE.beginPass(TesrBatchRenderer.PASS_MAIN_0, new Matrix4f(), 0.0, 0.0, 0.0);
        ModelPartBatcher.INSTANCE.begin(ModelPartBatcher.Mode.BLOCK_ENTITIES);
        ModelPartBatcher.onTextureBind(ATLAS, GLSMTextures.getBoundTextureForServerState());
        part = new ModelRenderer(new TestModel(), 0, 0);
        box = new RoutedBox(part);
        part.cubeList.add(box);
    }

    @AfterEach
    void tearDown() {
        final BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        if (box.faces > 0 && buffer.getByteBuffer().limit() == buffer.getByteBuffer().capacity()) {
            // begin() widens the byte buffer and finishDrawing() narrows it again, so a builder that
            // stopped mid-capture leaves an unnarrowed buffer that still has to be closed. A builder
            // the capture never opened is left alone, so an early failure is not masked here.
            buffer.finishDrawing();
        }
        BatchEligibility.reset();
        ModelPartBatcher.INSTANCE.clear();
        TesrBatchRenderer.INSTANCE.clearRetained();
        ActiniumRuntime.options().performance.enableEntityBatching = savedBatching;
        ShaderManager.disable();
    }

    @Test
    void aLegacyModelPartIsCapturedAndLeavesTheSharedBuilderClosable() {
        assertTrue(BatchEligibility.begin(BatchEligibility.SAFE, GLSMDraws.drawCalls),
            "the batch has to be trusted for the part to be captured");

        assertTrue(ModelPartBatch.partDraw(part, SCALE), "the first draw of the part is captured and queued");
        assertEquals(FACES, box.faces, "the capture renders every face of the box");

        assertTrue(ModelPartBatch.partDraw(part, SCALE), "the second draw of the same part is queued");
        assertEquals(FACES, box.faces, "the second draw reuses the captured template instead of rendering the box");

        final BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        assertDoesNotThrow(() -> {
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_NORMAL);
            buffer.finishDrawing();
        }, "the capture must leave the shared builder closed and ready for the next draw");
    }

    @Test
    void aPartIsRefusedBeforeAnyFaceWhenDirectMemoryAccessIsOff() {
        final boolean savedDirectMemoryAccess = ActiniumRuntime.options().advanced.allowDirectMemoryAccess;
        ActiniumRuntime.options().advanced.allowDirectMemoryAccess = false;
        RuntimeOptionsBridge.setAllowDirectMemoryAccess(ActiniumRuntimeOptions::allowDirectMemoryAccess);
        try {
            assertTrue(BatchEligibility.begin(BatchEligibility.SAFE, GLSMDraws.drawCalls),
                "the batch has to be trusted for the part to be captured");

            assertFalse(ModelPartBatch.partDraw(part, SCALE),
                "a part whose faces cannot be forwarded into the capture stays out of the batch");
            assertEquals(0, box.faces, "the batch refuses the part before any face is rendered");
        } finally {
            ActiniumRuntime.options().advanced.allowDirectMemoryAccess = savedDirectMemoryAccess;
            RuntimeOptionsBridge.setAllowDirectMemoryAccess(null);
        }
    }

    @Test
    void aCaptureRejectsASecondPrimitiveType() {
        box.trianglesAt = 1;
        assertTrue(BatchEligibility.begin(BatchEligibility.SAFE, GLSMDraws.drawCalls),
            "the batch has to be trusted for the part to be captured");

        assertThrows(IllegalStateException.class, () -> ModelPartBatch.partDraw(part, SCALE),
            "one capture cannot hold a quad face and a triangle face at once");
        assertEquals(1, box.faces, "the quad face completes and the triangle face fails its draw");
    }
}
