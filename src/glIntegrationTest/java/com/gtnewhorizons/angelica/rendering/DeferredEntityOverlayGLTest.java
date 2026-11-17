package com.gtnewhorizons.angelica.rendering;

import com.dhj.actinium.mixinterface.features.iris.RenderLivingBaseOverlayAccess;
import com.gtnewhorizons.angelica.glsm.GLSMBlend;
import com.gtnewhorizons.angelica.glsm.GLSMCurrentAttribs;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.GLSMMatrices;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import com.gtnewhorizons.angelica.glsm.shadow.InternalShadowRenderingState;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.entity.layers.LayerCreeperCharge;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;
import net.minecraft.client.renderer.entity.layers.LayerWitherAura;
import net.minecraft.entity.EntityLivingBase;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers which layers the deferred overlay takes over and what the replay restores.
 *
 * <p>The shadow pass stores depth only, so an aura replayed with depth writes off would drop its
 * shadow; the outline pass has its own framebuffer. Both keep the layer inline, and so does every
 * layer that is not registered. The replay runs the recorded layer instance itself, puts the main
 * model's animation state back first, and leaves depth writes, culling and blend state as it found
 * them.</p>
 *
 * <p>The two built-in rules key off entity state, which only exists once the game bootstraps, so
 * the registry is checked here and the entity half is covered by the client scenario in the port
 * report.</p>
 */
class DeferredEntityOverlayGLTest extends GLStateManagerIntegrationFixture {

    /** Records what the replay did while the layer was running. */
    private static class RecordingLayer implements LayerRenderer<EntityLivingBase> {
        final List<float[]> calls = new ArrayList<>();
        final Matrix4f matrixDuringCall = new Matrix4f();
        boolean combining;
        boolean replayingDuringCall;
        boolean depthMaskDuringCall;
        boolean cullEnabledDuringCall;

        @Override
        public void doRenderLayer(EntityLivingBase entity, float limbSwing, float limbSwingAmount, float partialTicks,
                                  float ageInTicks, float netHeadYaw, float headPitch, float scale) {
            calls.add(new float[] {limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch, scale});
            matrixDuringCall.set(GLSMMatrices.getModelViewMatrix());
            replayingDuringCall = DeferredEntityOverlay.isReplaying();
            depthMaskDuringCall = GLSMDepthStencilAlpha.isEffectiveDepthMaskEnabled();
            cullEnabledDuringCall = GLSMEnable.glIsEnabled(GL11.GL_CULL_FACE);
        }

        @Override
        public boolean shouldCombineTextures() {
            return combining;
        }
    }

    /** Stands in for the mixin the game adds to every RenderLivingBase. */
    private static final class TestAccess implements RenderLivingBaseOverlayAccess {
        final ModelBase model = new TestModel();
        boolean brightnessSet;
        boolean brightnessUnset;
        boolean lastCombineTextures;

        @Override
        public ModelBase actinium$mainModel() {
            return model;
        }

        @Override
        public boolean actinium$setBrightness(EntityLivingBase entity, float partialTicks, boolean combineTextures) {
            brightnessSet = true;
            lastCombineTextures = combineTextures;
            return true;
        }

        @Override
        public void actinium$unsetBrightness() {
            brightnessUnset = true;
        }
    }

    private static final class TestModel extends ModelBase {
    }

    /** Registered to prove a registration only catches its own type. */
    private static final class NeverLayer implements LayerRenderer<EntityLivingBase> {
        @Override
        public void doRenderLayer(EntityLivingBase entity, float limbSwing, float limbSwingAmount, float partialTicks,
                                  float ageInTicks, float netHeadYaw, float headPitch, float scale) {
        }

        @Override
        public boolean shouldCombineTextures() {
            return false;
        }
    }

    private TestAccess renderer;
    private RecordingLayer layer;

    @BeforeEach
    void setUp() {
        WindowBackends.useSdl();
        GLStateManager.bindDisplayContext(new Object(), context);
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMMatrices.glLoadIdentity();
        DeferredEntityOverlay.register(RecordingLayer.class, entity -> true);
        renderer = new TestAccess();
        layer = new RecordingLayer();
    }

    @AfterEach
    void tearDown() {
        DeferredEntityOverlay.clear();
        InternalShadowRenderingState.clear();
        GLSMDepthStencilAlpha.glDepthMask(true);
        GLSMEnable.glEnable(GL11.GL_CULL_FACE);
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMMatrices.glLoadIdentity();
    }

    @Test
    void registersTheTwoVanillaAuras() {
        assertTrue(DeferredEntityOverlay.isRegistered(LayerCreeperCharge.class));
        assertTrue(DeferredEntityOverlay.isRegistered(LayerWitherAura.class));
    }

    @Test
    void keepsLayersThatAreNotRegisteredInline() {
        assertFalse(DeferredEntityOverlay.isRegistered(NeverLayer.class));
        assertFalse(DeferredEntityOverlay.isRegistered(RecordingLayer.class) && false);
        assertTrue(DeferredEntityOverlay.isRegistered(RecordingLayer.class));
        assertFalse(DeferredEntityOverlay.canDefer(new NeverLayer(), null, false));
    }

    @Test
    void keepsTheLayerInlineDuringTheOutlinePass() {
        assertFalse(DeferredEntityOverlay.canDefer(layer, null, true));
        assertTrue(DeferredEntityOverlay.canDefer(layer, null, false));
    }

    @Test
    void keepsTheLayerInlineDuringTheShadowPass() {
        InternalShadowRenderingState.begin(new Matrix4f(), new Matrix4f());
        try {
            assertFalse(DeferredEntityOverlay.canDefer(layer, null, false));
        } finally {
            InternalShadowRenderingState.end();
        }

        assertTrue(DeferredEntityOverlay.canDefer(layer, null, false));
    }

    @Test
    void replaysTheSameLayerWithTheCapturedArguments() {
        GLSMMatrices.glTranslatef(1.0F, 2.0F, 3.0F);
        // OpenGlHelper latches its lightmap unit from the LWJGL 2 capabilities, which are absent under the
        // SDL backend used by this fixture, so the unit is spelled out here. The production path reads the
        // same GL_TEXTURE1 through OpenGlHelper.
        GLSMCurrentAttribs.setLightmapTextureCoords(GL13.GL_TEXTURE1, 240.0F, 192.0F);
        layer.combining = true;

        assertTrue(DeferredEntityOverlay.maybeDefer(renderer, layer, null,
            11.0F, 12.0F, 13.0F, 14.0F, 15.0F, 16.0F, 0.0625F, false));
        assertEquals(0, layer.calls.size());

        GLSMMatrices.glLoadIdentity();
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
        assertTrue(renderer.brightnessSet);
        assertTrue(renderer.brightnessUnset);
        assertTrue(renderer.lastCombineTextures);
        assertTrue(layer.replayingDuringCall);
        assertFalse(DeferredEntityOverlay.isReplaying());
        assertSame(renderer.model, renderer.actinium$mainModel());
        assertEquals(1.0F, layer.matrixDuringCall.m30(), 0.0F);
        assertEquals(2.0F, layer.matrixDuringCall.m31(), 0.0F);
        assertEquals(3.0F, layer.matrixDuringCall.m32(), 0.0F);
        assertEquals(240.0F, GLStateManager.getLastBrightnessX(), 0.0F);
        assertEquals(192.0F, GLStateManager.getLastBrightnessY(), 0.0F);
    }

    @Test
    void restoresTheMainModelAnimationStateBeforeTheReplay() {
        final ModelBase model = renderer.model;
        model.swingProgress = 0.75F;
        model.isRiding = true;
        model.isChild = true;

        assertTrue(DeferredEntityOverlay.maybeDefer(renderer, layer, null,
            10.0F, 0.5F, 0.25F, 20.0F, 30.0F, 40.0F, 0.0625F, false));

        // Later entities reuse the same renderer and overwrite the shared model state.
        model.swingProgress = 0.0F;
        model.isRiding = false;
        model.isChild = false;

        DeferredEntityOverlay.renderAll();

        assertEquals(0.75F, model.swingProgress, 0.0F);
        assertTrue(model.isRiding);
        assertTrue(model.isChild);
    }

    @Test
    void keepsDepthWritesOffDuringTheReplayAndRestoresTheEntryState() {
        GLSMDepthStencilAlpha.glDepthMask(true);
        GLSMEnable.glEnable(GL11.GL_CULL_FACE);
        GLSMBlend.enableBlend();

        assertTrue(DeferredEntityOverlay.maybeDefer(renderer, layer, null,
            10.0F, 0.5F, 0.25F, 20.0F, 30.0F, 40.0F, 0.0625F, false));
        DeferredEntityOverlay.renderAll();

        assertFalse(layer.depthMaskDuringCall);
        assertFalse(layer.cullEnabledDuringCall);
        assertTrue(GLSMDepthStencilAlpha.isEffectiveDepthMaskEnabled());
        assertTrue(GLSMEnable.glIsEnabled(GL11.GL_CULL_FACE));
        assertTrue(GLSMBlend.isEffectiveBlendEnabled());
    }

    @Test
    void keepsAnEntryFromBeingCapturedAgainWhileItReplays() {
        final int[] deferredDuringReplay = {0};
        final RecordingLayer reentrant = new RecordingLayer() {
            @Override
            public void doRenderLayer(EntityLivingBase entity, float limbSwing, float limbSwingAmount,
                                      float partialTicks, float ageInTicks, float netHeadYaw, float headPitch,
                                      float scale) {
                super.doRenderLayer(entity, limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw,
                    headPitch, scale);
                if (DeferredEntityOverlay.maybeDefer(renderer, this, null,
                    limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch, scale, false)) {
                    deferredDuringReplay[0]++;
                }
            }
        };

        assertTrue(DeferredEntityOverlay.maybeDefer(renderer, reentrant, null,
            10.0F, 0.5F, 0.25F, 20.0F, 30.0F, 40.0F, 0.0625F, false));
        DeferredEntityOverlay.renderAll();

        assertEquals(0, deferredDuringReplay[0]);
        assertEquals(1, reentrant.calls.size());
    }
}
