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
import com.gtnewhorizons.angelica.glsm.hooks.GLSMConfig;
import com.gtnewhorizons.angelica.rendering.tesr.EntityShadowReplay;
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
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
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
 * {@link RenderLivingBaseOverlayAccess} renderer and the real {@link LayerRenderer} instance, then
 * re-invokes {@code layer.doRenderLayer} on the same instances during the flush. Mod subclasses, mod
 * layers and the armor layer's four-slot walk keep running their own code; no model is
 * substituted.</p>
 *
 * <p>Two capture points feed the same flush. The main view captures the layer where it would have
 * run and skips it there. The shadow pass draws the layer inline, because the shadow map only stores
 * depth and a replay with depth writes off would drop the aura's shadow, and records the same layer
 * so the main pass can queue it: when the shadow pass recorded the entity, the main view does not run
 * the renderer at all, so nothing else would queue the overlay. A recorded overlay is therefore drawn
 * exactly once per view.</p>
 *
 * <p>Replay runs outside {@code renderLayers}, so it never re-enters the entity's main model or the
 * other layers. The state a layer reads back from the renderer, its brightness texture environment,
 * the current colour and the gbuffer pass are captured with the entry and restored around the replay
 * call.</p>
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
    private static final Matrix4f REPLAY_MATRIX = new Matrix4f();

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

    /** Whether the given layer type is registered, so its layers may be deferred. */
    public static boolean isRegistered(Class<?> layerType) {
        return ruleFor(layerType) != null;
    }

    /**
     * Whether the given layer has to be deferred to the flush.
     *
     * <p>The shadow pass and the outline pass keep the layer inline; layers that are not registered,
     * and entities the registered predicate rejects, take vanilla's inline path.</p>
     */
    public static boolean canDefer(LayerRenderer<?> layer, EntityLivingBase entity, boolean outlineActive) {
        if (replaying || outlineActive) return false;
        if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) return false;
        return matches(layer, entity);
    }

    /**
     * Handles one layer call made by {@code renderLayers}.
     *
     * @return whether the caller has to skip the layer now; {@code false} means it runs inline
     */
    public static boolean handleLayer(RenderLivingBaseOverlayAccess renderer, LayerRenderer<?> layer,
                                      EntityLivingBase entity, float limbSwing, float limbSwingAmount,
                                      float partialTicks, float ageInTicks, float netHeadYaw, float headPitch,
                                      float scale, boolean outlineActive) {
        if (replaying || outlineActive || !matches(layer, entity)) return false;

        if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
            // The shadow map needs the layer drawn inline, but the main view will not run the renderer
            // when the entity's draws replay, so the layer is recorded here to be queued there instead.
            final DeferredEntry recorded = captureShadowOverlay(renderer, layer, entity, limbSwing, limbSwingAmount,
                partialTicks, ageInTicks, netHeadYaw, headPitch, scale);
            EntityShadowReplay.recordDeferredOverlay(recorded);
            recycle(recorded);
            return false;
        }

        final DeferredEntry entry = capture(renderer, layer, entity, limbSwing, limbSwingAmount, partialTicks,
            ageInTicks, netHeadYaw, headPitch, scale);
        entry.pass = PassOverride.capture();
        deferred.add(entry);
        return true;
    }

    /**
     * Captures one eligible layer for the shadow-pass record.
     *
     * <p>The caller has already established that the layer is deferred and that the shadow pass is
     * drawing it inline; the record outlives this entry, which the caller recycles right after
     * {@code EntityShadowReplay} copied it.</p>
     */
    public static DeferredEntry captureShadowOverlay(RenderLivingBaseOverlayAccess renderer, LayerRenderer<?> layer,
                                                     EntityLivingBase entity, float limbSwing, float limbSwingAmount,
                                                     float partialTicks, float ageInTicks, float netHeadYaw,
                                                     float headPitch, float scale) {
        return capture(renderer, layer, entity, limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw,
            headPitch, scale);
    }

    /**
     * Queues an overlay recorded in the shadow pass for the main-view flush.
     *
     * <p>The recorded matrix is the shadow view's, so it is composed with the transform that maps
     * the shadow view onto the main view. The gbuffer pass is taken from the main view at queue
     * time, which is where the layer would have run had the entity not been replayed.</p>
     */
    public static void queueReplay(DeferredEntry recorded, Matrix4fc mainFromShadow) {
        final DeferredEntry entry = acquire();
        entry.copyFrom(recorded);
        REPLAY_MATRIX.set(recorded.matrix).mulLocal(mainFromShadow).get(entry.matrix);
        entry.pass = PassOverride.capture();
        deferred.add(entry);
    }

    /** Whether a replay is in progress. Layers must not defer their own replay. */
    public static boolean isReplaying() {
        return replaying;
    }

    /**
     * Drops every entry captured so far. Called at the start of Forge render pass 0, so an entry
     * from an aborted frame cannot reach the next one.
     */
    public static void clear() {
        recycle();
        replaying = false;
    }

    /** Replays every captured layer. Called once per render pass, before that pass's block entities. */
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
        final ModelBase mainModel = access.actinium$mainModel();

        // StateSet.BATCH does not carry the current colour, and the layers recolour themselves
        // without restoring it, so the colour is saved and put back explicitly.
        final float colorRed = GLSMCurrentAttribs.getColor().getRed();
        final float colorGreen = GLSMCurrentAttribs.getColor().getGreen();
        final float colorBlue = GLSMCurrentAttribs.getColor().getBlue();
        final float colorAlpha = GLSMCurrentAttribs.getColor().getAlpha();
        final float savedSwingProgress = mainModel.swingProgress;
        final boolean savedIsRiding = mainModel.isRiding;
        final boolean savedIsChild = mainModel.isChild;

        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMMatrices.glPushMatrix();

        boolean statePushed = false;
        int stateDepth = 0;
        boolean passApplied = false;
        boolean brightnessSet = false;
        boolean entityPushed = false;
        Throwable failure = null;
        replaying = true;
        GLSMConfig.forceDepthMaskOff = true;
        try {
            // Still inside the try whose finally pops the matrix: a failing pushState must not leave
            // the matrix pushed.
            stateDepth = GLSMAttribStack.pushState(StateSet.BATCH);
            statePushed = true;

            MATRIX_BUF.clear();
            MATRIX_BUF.put(entry.matrix);
            MATRIX_BUF.flip();
            GLSMMatrices.glLoadMatrix(MATRIX_BUF);
            GLSMCurrentAttribs.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, entry.lightmapX, entry.lightmapY);

            entry.pass.apply();
            passApplied = true;
            CapturedRenderingState.INSTANCE.pushCurrentEntityAndItem();
            entityPushed = true;
            CapturedRenderingState.INSTANCE.setCurrentEntity(EntityIdHelper.getEntityId(entity));

            final GbufferPrograms.EntityPhase phase = GbufferPrograms.enterEntityPhase();
            try {
                mainModel.swingProgress = entry.swingProgress;
                mainModel.isRiding = entry.isRiding;
                mainModel.isChild = entry.isChild;

                brightnessSet = access.actinium$setBrightness(entity, entry.partialTicks, entry.combineTextures);
                GLSMEnable.glDisable(GL11.GL_CULL_FACE);
                GLSMDepthStencilAlpha.glDepthMask(false);
                invokeLayer(entry.layer, entity, entry);
            } finally {
                if (brightnessSet) failure = run(failure, () -> access.actinium$unsetBrightness());
                failure = run(failure, phase::close);
                if (entityPushed) failure = run(failure, () -> CapturedRenderingState.INSTANCE.popCurrentEntityAndItem());
            }
        } catch (Throwable bodyFailure) {
            failure = RenderFailures.suppress(failure, bodyFailure);
        } finally {
            if (passApplied) failure = run(failure, entry.pass::clear);
            replaying = false;
            // The depth lock has to be off before the write state is restored, otherwise the restore
            // would be forced back to false as well.
            GLSMConfig.forceDepthMaskOff = false;
            failure = run(failure, DeferredEntityOverlay::restoreDrawState);
            failure = run(failure, () -> restoreColor(colorRed, colorGreen, colorBlue, colorAlpha));
            failure = run(failure, () -> restoreModel(mainModel, savedSwingProgress, savedIsRiding, savedIsChild));
            if (statePushed) {
                final int depth = stateDepth;
                failure = run(failure, () -> GLSMAttribStack.popStateTo(depth));
            }
            failure = run(failure, GLSMMatrices::glPopMatrix);
            RenderFailures.rethrowWrapped(failure);
        }
    }

    private static void restoreDrawState() {
        GLSMDepthStencilAlpha.glDepthMask(true);
        GLSMEnable.glEnable(GL11.GL_CULL_FACE);
    }

    private static void restoreColor(float red, float green, float blue, float alpha) {
        GLSMCurrentAttribs.glColor4f(red, green, blue, alpha);
    }

    private static void restoreModel(ModelBase model, float swingProgress, boolean isRiding, boolean isChild) {
        model.swingProgress = swingProgress;
        model.isRiding = isRiding;
        model.isChild = isChild;
    }

    /** Runs one cleanup step, keeping the first failure and attaching later ones to it. */
    private static Throwable run(Throwable failure, Runnable step) {
        try {
            step.run();
        } catch (Throwable cleanupFailure) {
            return RenderFailures.suppress(failure, cleanupFailure);
        }
        return failure;
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

    private static boolean matches(LayerRenderer<?> layer, EntityLivingBase entity) {
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

    private static DeferredEntry capture(RenderLivingBaseOverlayAccess renderer, LayerRenderer<?> layer,
                                         EntityLivingBase entity, float limbSwing, float limbSwingAmount,
                                         float partialTicks, float ageInTicks, float netHeadYaw, float headPitch,
                                         float scale) {
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
        GLSMMatrices.getModelViewMatrix().get(entry.matrix);
        return entry;
    }

    private static DeferredEntry acquire() {
        final int size = pool.size();
        if (size > 0) {
            return pool.remove(size - 1);
        }
        return new DeferredEntry();
    }

    private static void recycle(DeferredEntry entry) {
        entry.clear();
        pool.add(entry);
    }

    private static void recycle() {
        for (int i = 0, n = deferred.size(); i < n; i++) {
            recycle(deferred.get(i));
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
    public static final class DeferredEntry {
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

        /** Copies every field, including the matrix, so a record can outlive the capture it came from. */
        public void copyFrom(DeferredEntry source) {
            renderer = source.renderer;
            layer = source.layer;
            entity = source.entity;
            pass = source.pass;
            limbSwing = source.limbSwing;
            limbSwingAmount = source.limbSwingAmount;
            partialTicks = source.partialTicks;
            ageInTicks = source.ageInTicks;
            netHeadYaw = source.netHeadYaw;
            headPitch = source.headPitch;
            scale = source.scale;
            combineTextures = source.combineTextures;
            swingProgress = source.swingProgress;
            isRiding = source.isRiding;
            isChild = source.isChild;
            lightmapX = source.lightmapX;
            lightmapY = source.lightmapY;
            System.arraycopy(source.matrix, 0, matrix, 0, matrix.length);
        }

        /** Drops the fields so a pooled entry cannot keep a renderer, layer or entity alive. */
        public void clear() {
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
