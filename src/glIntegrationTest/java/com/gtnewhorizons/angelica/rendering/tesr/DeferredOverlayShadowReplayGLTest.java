package com.gtnewhorizons.angelica.rendering.tesr;

import com.dhj.actinium.mixinterface.features.iris.RenderLivingBaseOverlayAccess;
import com.gtnewhorizons.angelica.glsm.GLSMCurrentAttribs;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMMatrices;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMConfig;
import com.gtnewhorizons.angelica.glsm.shadow.InternalShadowRenderingState;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the overlay a shadow-pass replay carries into the main view.
 *
 * <p>When the shadow pass recorded an entity, the main view skips that entity's renderer entirely,
 * so {@code renderLayers} never runs and nothing would queue the aura. The shadow pass therefore
 * records the layer it drew inline, and replaying the entity queues that record for the deferred
 * flush, which draws the aura once with depth writes off. The model parts that inline call drew must
 * stay out of the record, otherwise the replay would draw them and the flushed overlay on top of
 * them.</p>
 */
class DeferredOverlayShadowReplayGLTest extends GLStateManagerIntegrationFixture {

    private static final float LIMB_SWING = 11.0F;
    private static final float LIMB_SWING_AMOUNT = 12.0F;
    private static final float PARTIAL_TICKS = 13.0F;
    private static final float AGE_IN_TICKS = 14.0F;
    private static final float NET_HEAD_YAW = 15.0F;
    private static final float HEAD_PITCH = 16.0F;
    private static final float SCALE = 0.0625F;
    private static final float EPSILON = 0.0001F;

    private static class RecordingLayer implements LayerRenderer<EntityLivingBase> {
        final List<float[]> calls = new ArrayList<>();
        final Matrix4f matrixDuringCall = new Matrix4f();
        boolean replayingDuringCall;
        boolean depthMaskDuringCall;
        boolean suppressedDuringCall;

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

    /**
     * Mirrors what the mixin does with one layer call during the shadow pass.
     */
    private DeferredEntityOverlay.LayerAction runShadowLayer(LayerRenderer<?> target) {
        final DeferredEntityOverlay.LayerAction action = DeferredEntityOverlay.beginLayer(renderer, target, null,
            LIMB_SWING, LIMB_SWING_AMOUNT, PARTIAL_TICKS, AGE_IN_TICKS, NET_HEAD_YAW, HEAD_PITCH, SCALE, false);
        if (action == DeferredEntityOverlay.LayerAction.SKIP) {
            return action;
        }
        try {
            target.doRenderLayer(null, LIMB_SWING, LIMB_SWING_AMOUNT, PARTIAL_TICKS, AGE_IN_TICKS, NET_HEAD_YAW,
                HEAD_PITCH, SCALE);
        } finally {
            if (action == DeferredEntityOverlay.LayerAction.RUN_SUPPRESSED) {
                DeferredEntityOverlay.endLayer();
            }
        }
        return action;
    }

    @Test
    void queuedShadowRecordIsReplayedByTheDeferredFlush() {
        // Rotation plus translation, so the composition order is observable: the captured view is a
        // rotation about its own translated origin, and the shadow-to-main transform rotates then moves.
        GLSMMatrices.glTranslatef(4.0F, 5.0F, 6.0F);
        GLSMMatrices.glRotatef(90.0F, 0.0F, 1.0F, 0.0F);
        final Matrix4f capturedView = new Matrix4f().translate(4.0F, 5.0F, 6.0F)
            .rotateY((float) Math.PI / 2.0F);

        final DeferredEntityOverlay.DeferredEntry recorded = DeferredEntityOverlay.captureShadowOverlay(
            renderer, layer, null, LIMB_SWING, LIMB_SWING_AMOUNT, PARTIAL_TICKS, AGE_IN_TICKS, NET_HEAD_YAW,
            HEAD_PITCH, SCALE);
        assertNotNull(recorded);

        // Recording is not deferring: the shadow pass drew the layer inline and the flush has nothing yet.
        DeferredEntityOverlay.renderAll();
        assertEquals(0, layer.calls.size());

        // The main view replays the entity's commands; the overlay command queues the record instead of
        // drawing it there, and the flush draws it once in the main view.
        final Matrix4f mainFromShadow = new Matrix4f().rotateY((float) Math.PI / 2.0F).translate(10.0F, 0.0F, 0.0F);
        ModelPartBatcher.INSTANCE.beginReplay(mainFromShadow);
        commands.overlay(recorded);
        commands.replay(0, 1);
        assertEquals(0, layer.calls.size());

        DeferredEntityOverlay.renderAll();

        assertEquals(1, layer.calls.size());
        final Matrix4f expected = new Matrix4f(mainFromShadow).mul(capturedView);
        final Matrix4f otherOrder = new Matrix4f(capturedView).mul(mainFromShadow);
        assertTrue(layer.matrixDuringCall.equals(expected, EPSILON), () -> "expected " + expected + " but was " + layer.matrixDuringCall);
        assertFalse(layer.matrixDuringCall.equals(otherOrder, EPSILON), "the shadow-to-main transform is applied on the left");
        assertNotEquals(expected, otherOrder);
        assertTrue(layer.replayingDuringCall);
        assertFalse(layer.depthMaskDuringCall);
    }

    @Test
    void theInlineShadowLayerKeepsOnlyItsOverlayInTheRecord() {
        final EntityRenderCommands.DrawInputs inputs = new EntityRenderCommands.DrawInputs();
        final RecordingLayer probe = new RecordingLayer() {
            @Override
            public void doRenderLayer(EntityLivingBase entity, float limbSwing, float limbSwingAmount,
                                      float partialTicks, float ageInTicks, float netHeadYaw, float headPitch,
                                      float scale) {
                super.doRenderLayer(entity, limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw,
                    headPitch, scale);
                // What the layer's own ModelRenderer draws hand to the record.
                commands.stream(new Matrix4f(), new int[32], 4, 0, inputs);
                suppressedDuringCall = EntityShadowReplay.isOverlayOnlyRecording();
            }
        };

        final DeferredEntityOverlay.DeferredEntry recorded = DeferredEntityOverlay.captureShadowOverlay(
            renderer, probe, null, LIMB_SWING, LIMB_SWING_AMOUNT, PARTIAL_TICKS, AGE_IN_TICKS, NET_HEAD_YAW,
            HEAD_PITCH, SCALE);
        commands.overlay(recorded);

        InternalShadowRenderingState.begin(new Matrix4f(), new Matrix4f());
        final DeferredEntityOverlay.LayerAction action;
        try {
            action = runShadowLayer(probe);
        } finally {
            InternalShadowRenderingState.end();
        }

        assertEquals(DeferredEntityOverlay.LayerAction.RUN_SUPPRESSED, action);
        assertEquals(1, probe.calls.size());
        assertTrue(probe.suppressedDuringCall);
        assertFalse(EntityShadowReplay.isOverlayOnlyRecording());
        // One draw command in the record: the overlay. The model parts the inline layer drew were not
        // recorded, so replaying the record and flushing the overlay draws the aura once.
        assertEquals(1, commands.size());
    }
}
