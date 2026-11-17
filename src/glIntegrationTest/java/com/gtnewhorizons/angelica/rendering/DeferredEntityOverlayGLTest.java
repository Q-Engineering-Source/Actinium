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
import com.gtnewhorizons.angelica.glsm.hooks.GLSMConfig;
import com.gtnewhorizons.angelica.glsm.shadow.InternalShadowRenderingState;
import net.coderbot.iris.layer.PassOverride;
import net.coderbot.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.entity.layers.LayerCreeperCharge;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;
import net.minecraft.client.renderer.entity.layers.LayerWitherAura;
import net.minecraft.entity.EntityLivingBase;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
 * <p>The shadow pass and the outline pass keep the layer inline; layers that are not registered do
 * too. The replay runs the recorded layer instance itself, puts the main model's animation state
 * back for the call and returns it afterwards, and leaves depth writes, culling, the current colour
 * and the gbuffer pass stacks as it found them.</p>
 *
 * <p>The two built-in rules key off entity state, which only exists once the game bootstraps, so
 * the registry is checked here and the entity half is covered by the client scenario in the port
 * report.</p>
 */
class DeferredEntityOverlayGLTest extends GLStateManagerIntegrationFixture {

    private static final float EPSILON = 0.000001F;

    /** Records what the replay did while the layer was running. */
    private static class RecordingLayer implements LayerRenderer<EntityLivingBase> {
        final List<float[]> calls = new ArrayList<>();
        final Matrix4f matrixDuringCall = new Matrix4f();
        float swingProgressDuringCall;
        boolean isRidingDuringCall;
        boolean isChildDuringCall;
        boolean replayingDuringCall;
        boolean depthMaskDuringCall;
        boolean cullEnabledDuringCall;
        boolean combining;

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

    /** A mod-style layer that turns depth writes back on without restoring them. */
    private static final class DepthWritingLayer extends RecordingLayer {
        @Override
        public void doRenderLayer(EntityLivingBase entity, float limbSwing, float limbSwingAmount, float partialTicks,
                                  float ageInTicks, float netHeadYaw, float headPitch, float scale) {
            super.doRenderLayer(entity, limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch, scale);
            // The production redirector routes GlStateManager.depthMask here, and the central entry has to
            // refuse the write while the replay holds the lock.
            GLSMDepthStencilAlpha.glDepthMask(true);
            depthMaskDuringCall = GLSMDepthStencilAlpha.isEffectiveDepthMaskEnabled();
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

    /** Reads the model state the way a layer's {@code setModelAttributes} call would, during the replay. */
    private static final class ModelReadingLayer extends RecordingLayer {
        private final ModelBase watched;
        float observedSwingProgress;
        boolean observedIsRiding;
        boolean observedIsChild;

        ModelReadingLayer(ModelBase watched) {
            this.watched = watched;
        }

        @Override
        public void doRenderLayer(EntityLivingBase entity, float limbSwing, float limbSwingAmount, float partialTicks,
                                  float ageInTicks, float netHeadYaw, float headPitch, float scale) {
            super.doRenderLayer(entity, limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch, scale);
            observedSwingProgress = watched.swingProgress;
            observedIsRiding = watched.isRiding;
            observedIsChild = watched.isChild;
        }
    }

    private TestAccess renderer;
    private RecordingLayer layer;

    @BeforeAll
    static void registerTestLayers() {
        DeferredEntityOverlay.register(RecordingLayer.class, entity -> true);
        DeferredEntityOverlay.register(DepthWritingLayer.class, entity -> true);
        DeferredEntityOverlay.register(ModelReadingLayer.class, entity -> true);
    }

    @BeforeEach
    void setUp() {
        WindowBackends.useSdl();
        installDefaultGlsmConfig();
        GLStateManager.markSplashComplete("DeferredEntityOverlayGLTest");
        GLStateManager.bindDisplayContext(new Object(), context);
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMMatrices.glLoadIdentity();
        GLSMCurrentAttribs.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GLSMDepthStencilAlpha.glDepthMask(true);
        GLSMEnable.glEnable(GL11.GL_CULL_FACE);
        renderer = new TestAccess();
        layer = new RecordingLayer();
    }

    @AfterEach
    void tearDown() {
        DeferredEntityOverlay.clear();
        InternalShadowRenderingState.clear();
        GLSMConfig.forceDepthMaskOff = false;
        GLSMDepthStencilAlpha.glDepthMask(true);
        GLSMEnable.glEnable(GL11.GL_CULL_FACE);
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMMatrices.glLoadIdentity();
    }

    @Test
    void registersTheTwoVanillaAuras() {
        assertTrue(DeferredEntityOverlay.isRegistered(LayerCreeperCharge.class));
        assertTrue(DeferredEntityOverlay.isRegistered(LayerWitherAura.class));
        assertFalse(DeferredEntityOverlay.isRegistered(NeverLayer.class));
    }

    @Test
    void keepsLayersThatAreNotRegisteredInline() {
        assertFalse(DeferredEntityOverlay.canDefer(new NeverLayer(), null, false));
        assertFalse(DeferredEntityOverlay.handleLayer(renderer, new NeverLayer(), null,
            11.0F, 12.0F, 13.0F, 14.0F, 15.0F, 16.0F, 0.0625F, false));

        DeferredEntityOverlay.renderAll();

        assertEquals(0, layer.calls.size());
    }

    @Test
    void keepsTheLayerInlineDuringTheOutlinePass() {
        assertFalse(DeferredEntityOverlay.canDefer(layer, null, true));
        assertFalse(DeferredEntityOverlay.handleLayer(renderer, layer, null,
            11.0F, 12.0F, 13.0F, 14.0F, 15.0F, 16.0F, 0.0625F, true));
        assertTrue(DeferredEntityOverlay.canDefer(layer, null, false));
    }

    @Test
    void keepsTheLayerInlineDuringTheShadowPass() {
        InternalShadowRenderingState.begin(new Matrix4f(), new Matrix4f());
        try {
            assertFalse(DeferredEntityOverlay.canDefer(layer, null, false));
            // The layer still runs inline for the shadow map; recording it must not defer it as well.
            assertFalse(DeferredEntityOverlay.handleLayer(renderer, layer, null,
                11.0F, 12.0F, 13.0F, 14.0F, 15.0F, 16.0F, 0.0625F, false));
        } finally {
            InternalShadowRenderingState.end();
        }

        DeferredEntityOverlay.renderAll();

        assertEquals(0, layer.calls.size());
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

        assertTrue(DeferredEntityOverlay.handleLayer(renderer, layer, null,
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
        assertEquals(1.0F, layer.matrixDuringCall.m30(), 0.0F);
        assertEquals(2.0F, layer.matrixDuringCall.m31(), 0.0F);
        assertEquals(3.0F, layer.matrixDuringCall.m32(), 0.0F);
        assertEquals(240.0F, GLStateManager.getLastBrightnessX(), 0.0F);
        assertEquals(192.0F, GLStateManager.getLastBrightnessY(), 0.0F);
    }

    @Test
    void putsTheCapturedModelStateBackForTheCallAndReturnsItAfterwards() {
        final ModelBase model = renderer.model;
        model.swingProgress = 0.75F;
        model.isRiding = true;
        model.isChild = true;

        final ModelReadingLayer modelReader = new ModelReadingLayer(model);
        assertTrue(DeferredEntityOverlay.handleLayer(renderer, modelReader, null,
            10.0F, 0.5F, 0.25F, 20.0F, 30.0F, 40.0F, 0.0625F, false));

        // Later entities reuse the same renderer and overwrite the shared model state.
        model.swingProgress = 0.125F;
        model.isRiding = false;
        model.isChild = false;

        DeferredEntityOverlay.renderAll();

        assertEquals(0.75F, modelReader.observedSwingProgress, 0.0F);
        assertTrue(modelReader.observedIsRiding);
        assertTrue(modelReader.observedIsChild);
        assertEquals(0.125F, model.swingProgress, 0.0F);
        assertFalse(model.isRiding);
        assertFalse(model.isChild);
    }

    @Test
    void keepsDepthWritesOffForALayerThatAsksForThem() {
        GLSMDepthStencilAlpha.glDepthMask(true);
        final DepthWritingLayer depthWriter = new DepthWritingLayer();

        assertTrue(DeferredEntityOverlay.handleLayer(renderer, depthWriter, null,
            10.0F, 0.5F, 0.25F, 20.0F, 30.0F, 40.0F, 0.0625F, false));
        DeferredEntityOverlay.renderAll();

        assertFalse(depthWriter.depthMaskDuringCall);
        assertTrue(GLSMDepthStencilAlpha.isEffectiveDepthMaskEnabled());
        assertFalse(GLSMConfig.forceDepthMaskOff);
    }

    @Test
    void keepsCullingBlendAndDepthClosedAroundTheReplay() {
        GLSMEnable.glEnable(GL11.GL_CULL_FACE);
        GLSMBlend.enableBlend();

        assertTrue(DeferredEntityOverlay.handleLayer(renderer, layer, null,
            10.0F, 0.5F, 0.25F, 20.0F, 30.0F, 40.0F, 0.0625F, false));
        DeferredEntityOverlay.renderAll();

        assertFalse(layer.cullEnabledDuringCall);
        assertTrue(GLSMEnable.glIsEnabled(GL11.GL_CULL_FACE));
        assertTrue(GLSMBlend.isEffectiveBlendEnabled());
    }

    @Test
    void restoresTheCurrentColourAfterTheReplay() {
        GLSMCurrentAttribs.glColor4f(0.25F, 0.5F, 0.75F, 1.0F);
        final RecordingLayer recolouring = new RecordingLayer() {
            @Override
            public void doRenderLayer(EntityLivingBase entity, float limbSwing, float limbSwingAmount,
                                      float partialTicks, float ageInTicks, float netHeadYaw, float headPitch,
                                      float scale) {
                super.doRenderLayer(entity, limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw,
                    headPitch, scale);
                GLSMCurrentAttribs.glColor4f(1.0F, 0.0F, 0.0F, 1.0F);
            }
        };

        assertTrue(DeferredEntityOverlay.handleLayer(renderer, recolouring, null,
            10.0F, 0.5F, 0.25F, 20.0F, 30.0F, 40.0F, 0.0625F, false));
        DeferredEntityOverlay.renderAll();

        assertEquals(0.25F, GLSMCurrentAttribs.getColor().getRed(), EPSILON);
        assertEquals(0.5F, GLSMCurrentAttribs.getColor().getGreen(), EPSILON);
        assertEquals(0.75F, GLSMCurrentAttribs.getColor().getBlue(), EPSILON);
    }

    @Test
    void balancesThePassAndEntityStateStacks() {
        final PassOverride passBefore = PassOverride.capture();
        CapturedRenderingState.INSTANCE.setCurrentEntity(7);
        CapturedRenderingState.INSTANCE.setCurrentEntityAndItem(7, 3);

        assertTrue(DeferredEntityOverlay.handleLayer(renderer, layer, null,
            10.0F, 0.5F, 0.25F, 20.0F, 30.0F, 40.0F, 0.0625F, false));
        DeferredEntityOverlay.renderAll();

        assertEquals(7, CapturedRenderingState.INSTANCE.getCurrentRenderedEntity());
        assertEquals(3, CapturedRenderingState.INSTANCE.getCurrentRenderedItem());
        assertSame(passBefore, PassOverride.capture());
        // A balanced stack accepts another round trip; an unbalanced one throws on the pop.
        PassOverride.capture().apply();
        PassOverride.capture().clear();
    }

    @Test
    void keepsAnEntryFromBeingCapturedAgainWhileItReplays() {
        final RecordingLayer reentrant = new RecordingLayer() {
            @Override
            public void doRenderLayer(EntityLivingBase entity, float limbSwing, float limbSwingAmount,
                                      float partialTicks, float ageInTicks, float netHeadYaw, float headPitch,
                                      float scale) {
                super.doRenderLayer(entity, limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw,
                    headPitch, scale);
                calls.add(new float[] {DeferredEntityOverlay.handleLayer(renderer, this, null,
                    limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch, scale, false)
                    ? 1.0F : 0.0F});
            }
        };

        assertTrue(DeferredEntityOverlay.handleLayer(renderer, reentrant, null,
            10.0F, 0.5F, 0.25F, 20.0F, 30.0F, 40.0F, 0.0625F, false));
        DeferredEntityOverlay.renderAll();

        assertEquals(2, reentrant.calls.size());
        assertEquals(0.0F, reentrant.calls.get(1)[0], 0.0F);
    }
}
