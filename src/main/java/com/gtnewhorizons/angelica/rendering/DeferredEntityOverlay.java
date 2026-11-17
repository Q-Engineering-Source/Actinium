package com.gtnewhorizons.angelica.rendering;

import com.dhj.actinium.mixinterface.features.iris.RenderLivingBaseOverlayAccess;
import com.dhj.actinium.shadows.ShadowRenderingState;
import com.gtnewhorizons.angelica.glsm.GLSMAttribStack;
import com.gtnewhorizons.angelica.glsm.GLSMCurrentAttribs;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.GLSMMatrices;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.StateSet;
import net.coderbot.iris.layer.GbufferPrograms;
import net.coderbot.iris.layer.PassOverride;
import net.coderbot.iris.uniforms.CapturedRenderingState;
import net.coderbot.iris.uniforms.EntityIdHelper;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.entity.layers.LayerCreeperCharge;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;
import net.minecraft.client.renderer.entity.layers.LayerWitherAura;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.monster.EntityCreeper;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Defers entity overlay layers (the charged creeper aura and the wither armor aura) to after every
 * entity has been drawn, and replays them with depth writes disabled so their coplanar shells stop
 * z-fighting with the model they wrap.
 *
 * <p>1.12.2 replaced the 1.7.10 {@code renderPassModel} / {@code shouldRenderPass} pair with
 * {@link LayerRenderer} instances that {@code RenderLivingBase.renderLayers} visits one by one.
 * Deferral therefore skips a single iteration of that loop and records the real
 * {@link RenderLivingBase} and the real {@link LayerRenderer} instance, then re-invokes
 * {@code layer.doRenderLayer} on the same instances during the flush. Mod subclasses, mod layers
 * and the armor layer's four-slot walk keep running their own code; no model is substituted.</p>
 *
 * <p>Replay runs outside {@code renderLayers}, so it never re-enters the entity's main model or the
 * other layers. The state a layer reads back from the renderer, its brightness texenv and the
 * gbuffer pass are captured with the entry and restored around the replay call.</p>
 */
public final class DeferredEntityOverlay {

    /** Decides whether one layer of one entity is worth deferring. */
    @FunctionalInterface
    public interface LayerPredicate {
        boolean shouldDefer(EntityLivingBase entity);
    }

    private static final int INITIAL_CAPACITY = 4;

    private static final List<LayerRule> LAYERS = new ArrayList<>(2);
    private static final List<DeferredEntry> deferred = new ArrayList<>(INITIAL_CAPACITY);
    private static final List<DeferredEntry> pool = new ArrayList<>(INITIAL_CAPACITY);

    private static final FloatBuffer MATRIX_BUF = BufferUtils.createFloatBuffer(16);

    private static boolean replaying;

    static {
        register(LayerCreeperCharge.class, entity -> entity instanceof EntityCreeper creeper && creeper.getPowered());
        register(LayerWitherAura.class, entity -> entity instanceof EntityWither wither && wither.isArmored());
    }

    private DeferredEntityOverlay() {
    }

    /** Registers one deferred layer type. Subclasses of the registered type are deferred as well. */
    public static void register(Class<? extends LayerRenderer<?>> layerType, LayerPredicate predicate) {
        LAYERS.add(new LayerRule(layerType, predicate));
    }

    /**
     * Whether the given layer type is registered.
     *
     * <p>Separate from {@link #canDefer} so the registry can be inspected without a live entity,
     * which 1.12.2 only produces once the game has bootstrapped.</p>
     */
    public static boolean isRegistered(Class<?> layerType) {
        return ruleFor(layerType) != null;
    }

    /**
     * Whether the given layer of the given entity has to be deferred now.
     *
     * <p>The shadow pass only carries depth, so a replay with depth writes off would drop the
     * aura's shadow entirely; the outline pass renders into its own framebuffer. Both keep the
     * layer inline. Layers that are not registered keep vanilla's inline path.</p>
     */
    public static boolean canDefer(LayerRenderer<?> layer, EntityLivingBase entity, boolean outlineActive) {
        if (replaying || outlineActive) return false;
        if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) return false;
        final LayerRule rule = ruleFor(layer.getClass());
        return rule != null && rule.predicate.shouldDefer(entity);
    }

    private static LayerRule ruleFor(Class<?> layerType) {
        for (int i = 0, n = LAYERS.size(); i < n; i++) {
            final LayerRule rule = LAYERS.get(i);
            if (rule.layerType.isAssignableFrom(layerType)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * Captures the layer for the flush. Returns {@code true} when the caller must skip the layer
     * now, in which case the entry holds everything the replay needs.
     */
    public static boolean maybeDefer(RenderLivingBaseOverlayAccess renderer, LayerRenderer<?> layer, EntityLivingBase entity,
                                     float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks,
                                     float netHeadYaw, float headPitch, float scale, boolean outlineActive) {
        if (!canDefer(layer, entity, outlineActive)) return false;

        final ModelBase mainModel = renderer.actinium$mainModel();
        final DeferredEntry entry = acquire();
        entry.renderer = renderer;
        entry.layer = layer;
        entry.entity = entity;
        entry.limbSwing = limbSwing;
        entry.limbSwingAmount = limbSwingAmount;
        entry.partialTicks = partialTicks;
        entry.ageInTicks = ageInTicks;
        entry.netHeadYaw = netHeadYaw;
        entry.headPitch = headPitch;
        entry.scale = scale;
        entry.combineTextures = layer.shouldCombineTextures();
        entry.swingProgress = mainModel.swingProgress;
        entry.isRiding = mainModel.isRiding;
        entry.isChild = mainModel.isChild;
        entry.lightmapX = GLStateManager.getLastBrightnessX();
        entry.lightmapY = GLStateManager.getLastBrightnessY();
        entry.pass = PassOverride.capture();
        GLSMMatrices.getModelViewMatrix().get(entry.matrix);
        deferred.add(entry);
        return true;
    }

    /** Whether a replay is in progress. Layers must not defer their own replay. */
    public static boolean isReplaying() {
        return replaying;
    }

    /** Drops every captured entry without drawing it. */
    public static void clear() {
        recycle();
        replaying = false;
    }

    /** Replays every captured layer. */
    public static void renderAll() {
        if (deferred.isEmpty()) return;

        final int count = deferred.size();
        try {
            for (int i = 0; i < count; i++) {
                replay(deferred.get(i));
            }
        } finally {
            recycle();
        }
    }

    static void replay(DeferredEntry entry) {
        final EntityLivingBase entity = entry.entity;
        final RenderLivingBaseOverlayAccess access = entry.renderer;

        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMMatrices.glPushMatrix();
        final int state = GLSMAttribStack.pushState(StateSet.BATCH);
        boolean brightnessSet = false;
        boolean phaseEntered = false;
        boolean entityPushed = false;
        replaying = true;
        try {
            MATRIX_BUF.clear();
            MATRIX_BUF.put(entry.matrix);
            MATRIX_BUF.flip();
            GLSMMatrices.glLoadMatrix(MATRIX_BUF);
            GLSMCurrentAttribs.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, entry.lightmapX, entry.lightmapY);

            entry.pass.apply();
            CapturedRenderingState.INSTANCE.pushCurrentEntityAndItem();
            entityPushed = true;
            if (entity != null) {
                CapturedRenderingState.INSTANCE.setCurrentEntity(EntityIdHelper.getEntityId(entity));
            }

            final GbufferPrograms.EntityPhase phase = GbufferPrograms.enterEntityPhase();
            phaseEntered = true;
            try {
                final ModelBase mainModel = access.actinium$mainModel();
                mainModel.swingProgress = entry.swingProgress;
                mainModel.isRiding = entry.isRiding;
                mainModel.isChild = entry.isChild;

                brightnessSet = access.actinium$setBrightness(entity, entry.partialTicks, entry.combineTextures);
                GLSMEnable.glDisable(GL11.GL_CULL_FACE);
                GLSMDepthStencilAlpha.glDepthMask(false);
                invokeLayer(entry.layer, entity, entry);
            } finally {
                if (brightnessSet) access.actinium$unsetBrightness();
                if (phaseEntered) phase.close();
                if (entityPushed) CapturedRenderingState.INSTANCE.popCurrentEntityAndItem();
            }
        } finally {
            replaying = false;
            entry.pass.clear();
            GLSMDepthStencilAlpha.glDepthMask(true);
            GLSMEnable.glEnable(GL11.GL_CULL_FACE);
            GLSMAttribStack.popStateTo(state);
            GLSMMatrices.glPopMatrix();
        }
    }

    /**
     * Re-invokes the recorded layer. {@link LayerRenderer} is typed per entity, and the entry holds
     * the layer and the entity it was captured with, so the cast is the one that pairing guarantees.
     */
    @SuppressWarnings("unchecked")
    private static void invokeLayer(LayerRenderer<?> layer, EntityLivingBase entity, DeferredEntry entry) {
        ((LayerRenderer<EntityLivingBase>) layer).doRenderLayer(entity, entry.limbSwing, entry.limbSwingAmount,
            entry.partialTicks, entry.ageInTicks, entry.netHeadYaw, entry.headPitch, entry.scale);
    }

    private static DeferredEntry acquire() {
        final int size = pool.size();
        if (size > 0) {
            return pool.remove(size - 1);
        }
        return new DeferredEntry();
    }

    private static void recycle() {
        for (int i = 0, n = deferred.size(); i < n; i++) {
            deferred.get(i).clear();
            pool.add(deferred.get(i));
        }
        deferred.clear();
    }

    private static final class LayerRule {
        final Class<? extends LayerRenderer<?>> layerType;
        final LayerPredicate predicate;

        LayerRule(Class<? extends LayerRenderer<?>> layerType, LayerPredicate predicate) {
            this.layerType = layerType;
            this.predicate = predicate;
        }
    }

    /** One captured layer call: the real renderer, the real layer, the entity and its anim state. */
    static final class DeferredEntry {
        final float[] matrix = new float[16];
        RenderLivingBaseOverlayAccess renderer;
        LayerRenderer<?> layer;
        EntityLivingBase entity;
        PassOverride pass;
        float limbSwing;
        float limbSwingAmount;
        float partialTicks;
        float ageInTicks;
        float netHeadYaw;
        float headPitch;
        float scale;
        boolean combineTextures;
        float swingProgress;
        boolean isRiding;
        boolean isChild;
        float lightmapX;
        float lightmapY;

        void clear() {
            renderer = null;
            layer = null;
            entity = null;
            pass = null;
            combineTextures = false;
            swingProgress = 0.0F;
            isRiding = false;
            isChild = false;
        }
    }
}
