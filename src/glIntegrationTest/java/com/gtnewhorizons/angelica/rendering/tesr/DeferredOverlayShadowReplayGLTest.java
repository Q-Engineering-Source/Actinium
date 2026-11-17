package com.gtnewhorizons.angelica.rendering.tesr;

import com.dhj.actinium.mixinterface.features.iris.RenderLivingBaseOverlayAccess;
import com.gtnewhorizons.angelica.glsm.GLSMCurrentAttribs;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMMatrices;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMConfig;
import com.gtnewhorizons.angelica.rendering.DeferredEntityOverlay;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;
import net.minecraft.entity.EntityLivingBase;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the overlay a shadow-pass replay carries into the main view.
 *
 * <p>When the shadow pass recorded an entity, the main view skips that entity's renderer entirely,
 * so {@code renderLayers} never runs and nothing would queue the aura. The shadow pass therefore
 * records the layer it drew inline, and replaying the entity queues that record for the deferred
 * flush, which draws the aura once with depth writes off.</p>
 */
class DeferredOverlayShadowReplayGLTest extends GLStateManagerIntegrationFixture {

    private static class RecordingLayer implements LayerRenderer<EntityLivingBase> {
        final List<float[]> calls = new ArrayList<>();
        final Matrix4f matrixDuringCall = new Matrix4f();
        boolean replayingDuringCall;
        boolean depthMaskDuringCall;

        @Override
        public void doRenderLayer(EntityLivingBase entity, float limbSwing, float limbSwingAmount, float partialTicks,
                                  float ageInTicks, float netHeadYaw, float headPitch, float scale) {
            calls.add(new float[] {limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch, scale});
            matrixDuringCall.set(GLSMMatrices.getModelViewMatrix());
            replayingDuringCall = DeferredEntityOverlay.isReplaying();
            depthMaskDuringCall = GLSMDepthStencilAlpha.isEffectiveDepthMaskEnabled();
        }

        @Override
        public boolean shouldCombineTextures() {
            return false;
        }
    }

    private static final class TestAccess implements RenderLivingBaseOverlayAccess {
        final ModelBase model = new TestModel();

        @Override
        public ModelBase actinium$mainModel() {
            return model;
        }

        @Override
        public boolean actinium$setBrightness(EntityLivingBase entity, float partialTicks, boolean combineTextures) {
            return false;
        }

        @Override
        public void actinium$unsetBrightness() {
        }
    }

    private static final class TestModel extends ModelBase {
    }

    private TestAccess renderer;
    private RecordingLayer layer;
    private EntityRenderCommands commands;

    @BeforeAll
    static void registerTestLayer() {
        DeferredEntityOverlay.register(RecordingLayer.class, entity -> true);
    }

    @BeforeEach
    void setUp() {
        WindowBackends.useSdl();
        installDefaultGlsmConfig();
        GLStateManager.markSplashComplete("DeferredOverlayShadowReplayGLTest");
        GLStateManager.bindDisplayContext(new Object(), context);
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMMatrices.glLoadIdentity();
        GLSMCurrentAttribs.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GLSMDepthStencilAlpha.glDepthMask(true);
        renderer = new TestAccess();
        layer = new RecordingLayer();
        commands = new EntityRenderCommands();
    }

    @AfterEach
    void tearDown() {
        commands.clear();
        DeferredEntityOverlay.clear();
        ModelPartBatcher.INSTANCE.beginReplay(new Matrix4f());
        GLSMConfig.forceDepthMaskOff = false;
        GLSMDepthStencilAlpha.glDepthMask(true);
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMMatrices.glLoadIdentity();
    }

    @Test
    void queuedShadowRecordIsReplayedByTheDeferredFlush() {
        GLSMMatrices.glTranslatef(4.0F, 5.0F, 6.0F);

        final DeferredEntityOverlay.DeferredEntry recorded = DeferredEntityOverlay.captureShadowOverlay(
            renderer, layer, null, 11.0F, 12.0F, 13.0F, 14.0F, 15.0F, 16.0F, 0.0625F);
        assertNotNull(recorded);

        // Recording is not deferring: the shadow pass drew the layer inline and the flush has nothing yet.
        DeferredEntityOverlay.renderAll();
        assertEquals(0, layer.calls.size());

        // The main view replays the entity's commands; the overlay command queues the record instead of
        // drawing it there, and the flush draws it once in the main view.
        final Matrix4f mainFromShadow = new Matrix4f().translate(10.0F, 20.0F, 30.0F);
        ModelPartBatcher.INSTANCE.beginReplay(mainFromShadow);
        commands.overlay(recorded);
        commands.replay(0, 1);
        assertEquals(0, layer.calls.size());

        DeferredEntityOverlay.renderAll();

        assertEquals(1, layer.calls.size());
        final float[] call = layer.calls.get(0);
        assertEquals(11.0F, call[0], 0.0F);
        assertEquals(12.0F, call[1], 0.0F);
        assertEquals(13.0F, call[2], 0.0F);
        assertEquals(14.0F, call[3], 0.0F);
        assertEquals(15.0F, call[4], 0.0F);
        assertEquals(16.0F, call[5], 0.0F);
        assertEquals(0.0625F, call[6], 0.0F);
        assertEquals(14.0F, layer.matrixDuringCall.m30(), 0.0001F);
        assertEquals(25.0F, layer.matrixDuringCall.m31(), 0.0001F);
        assertEquals(36.0F, layer.matrixDuringCall.m32(), 0.0001F);
        assertTrue(layer.replayingDuringCall);
        assertFalse(layer.depthMaskDuringCall);
    }
}
