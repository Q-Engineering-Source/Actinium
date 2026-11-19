package com.gtnewhorizons.angelica.rendering.tesr;

import com.gtnewhorizons.angelica.config.AngelicaConfig;
import com.gtnewhorizons.angelica.glsm.GLSMAttribStack;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.StateSet;
import com.gtnewhorizons.angelica.glsm.hooks.BatchStateGuard;
import com.gtnewhorizons.angelica.mixins.interfaces.IBatchEligibility;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import org.joml.Matrix4f;

/**
 * Opens and closes the brackets that surround the renderers the TESR batch records.
 *
 * <p>Each entry point takes the renderer call as a callback so the brackets stay balanced when the
 * call throws, and so the injections only have to name the work instead of repeating the state it
 * needs. The state owned here is the batch scope, the blend attribute bracket, the attribution of
 * the current renderable, the draw-eligibility bracket of one renderer, and the shadow-pass record
 * brackets.</p>
 */
public final class TesrBrackets {

    private TesrBrackets() {
    }

    /**
     * Runs one tile entity renderer inside the TESR batch scope and its eligibility bracket.
     *
     * <p>The scope makes the meshes the renderer queues part of one batch that flushes with the
     * block entity pass. The bracket records whether the renderer drew its own geometry alongside
     * them, so a mixed renderer stops being batched after its first frame. A registered
     * {@link com.gtnewhorizons.angelica.api.tesr.TesrMeshProvider} replaces the renderer call
     * entirely and runs outside the bracket.</p>
     */
    public static void renderTileEntity(TileEntitySpecialRenderer<?> renderer, TileEntity te, double x, double y,
                                        double z, float partialTicks, int destroyStage, float alpha, Runnable render) {
        TesrBatchRenderer.INSTANCE.beginScope();
        final int blendDepth = GLSMAttribStack.pushState(StateSet.BLEND);
        final Class<?> previousRenderable = TesrAttribution.currentRenderable;
        TesrAttribution.currentRenderable = te == null ? null : te.getClass();
        try {
            PassRebindGate.rebindIfDirty();
            if (TesrProviderDispatch.tryRender(renderer, te, x, y, z)) {
                return;
            }
            if (!AngelicaConfig.enableEntityBatching()) {
                render.run();
                return;
            }

            final IBatchEligibility holder = (IBatchEligibility) renderer;
            final byte state = holder.angelica$batchState();
            BatchEligibility.begin(state, GLSMDraws.drawCalls);
            try {
                render.run();
            } finally {
                final byte next = BatchEligibility.end(state, GLSMDraws.drawCalls);
                if (next != state) {
                    holder.angelica$setBatchState(next);
                    BatchEligibility.onStateChange(renderer, te, next);
                }
            }
        } finally {
            TesrAttribution.currentRenderable = previousRenderable;
            GLSMAttribStack.popStateTo(blendDepth);
            TesrBatchRenderer.INSTANCE.endScope();
        }
    }

    /**
     * Runs one entity renderer call inside its eligibility bracket and the shadow-pass record.
     *
     * <p>The bracket proves the renderer batches cleanly, and the record marks the entity whose
     * draws the shadow pass is capturing, which is what lets the main pass replay them.</p>
     */
    public static void renderEntity(Render<?> renderer, Entity entity, float partialTicks, Runnable render) {
        final IBatchEligibility holder = (IBatchEligibility) renderer;
        final byte state = holder.angelica$batchState();
        final boolean recording = AngelicaConfig.entityShadowReplayEnabled()
            && EntityShadowReplay.beginRenderer(renderer, entity, partialTicks, state);
        BatchEligibility.begin(state, GLSMDraws.drawCalls);
        boolean completed = false;
        try {
            render.run();
            completed = true;
        } finally {
            final byte next = BatchEligibility.end(state, GLSMDraws.drawCalls);
            if (recording) {
                EntityShadowReplay.endRenderer(completed, next);
            }
            if (next != state) {
                holder.angelica$setBatchState(next);
                BatchEligibility.onStateChange(renderer, entity, next);
            }
        }
    }

    /** Accounts for immediate draws made by a known entity model or layer. */
    public static void expectedDraws(Runnable render) {
        BatchEligibility.beginExpectedDraws(GLSMDraws.drawCalls);
        try {
            render.run();
        } finally {
            BatchEligibility.endExpectedDraws(GLSMDraws.drawCalls);
        }
    }

    /** Keeps nameplate draws eligible for model batching and rejects their shadow replay. */
    public static void renderNameplate(Runnable render) {
        EntityShadowReplay.rejectForeignDraw();
        expectedDraws(render);
    }

    /** Flushes entity geometry before the vanilla enderman-held block draws immediately. */
    public static void renderImmediateEntityLayer(Runnable render) {
        BatchStateGuard.beforeChange();
        EntityShadowReplay.rejectForeignDraw();
        expectedDraws(render);
    }

    /** Runs the shadow pass block entities through the shadow batch bracket. */
    public static void shadowBlockEntities(Matrix4f baseModelView, double cameraX, double cameraY, double cameraZ,
                                           Runnable render) {
        TesrBatchRenderer.INSTANCE.beginPass(TesrBatchRenderer.PASS_SHADOW, baseModelView, cameraX, cameraY, cameraZ);
        ModelPartBatcher.INSTANCE.begin(ModelPartBatcher.Mode.BLOCK_ENTITIES, true);
        PendingTesrDraws.beginPass();
        try {
            render.run();
        } finally {
            PendingTesrDraws.endPass();
            ModelPartBatcher.INSTANCE.flush();
            TesrBatchRenderer.INSTANCE.flush();
        }
    }

    /** Runs the shadow pass entity list inside the shadow entity bracket. */
    public static void shadowEntities(Runnable render) {
        ModelPartBatcher.INSTANCE.begin(ModelPartBatcher.Mode.ENTITIES, true);
        try {
            render.run();
        } finally {
            ModelPartBatcher.INSTANCE.flush();
        }
    }

    /** Runs one shadow-pass entity draw with its replay record, if the record accepts it. */
    public static void shadowEntity(Entity entity, float partialTicks, Runnable render) {
        final boolean recording = AngelicaConfig.entityShadowReplayEnabled()
            && EntityShadowReplay.beginEntity(entity, partialTicks);
        boolean completed = false;
        try {
            render.run();
            completed = true;
        } finally {
            if (recording) {
                EntityShadowReplay.endEntity(completed);
            }
        }
    }
}
