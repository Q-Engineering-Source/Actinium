package com.gtnewhorizons.angelica.rendering.items;

import com.dhj.actinium.render.FastLitItemDisplayListCache;
import com.dhj.actinium.render.ItemVertexAlphaOverrides;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.gtnewhorizons.angelica.api.tesr.TesrMaterial;
import com.gtnewhorizons.angelica.config.AngelicaConfig;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMMatrices;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMConfig;
import com.gtnewhorizons.angelica.rendering.SkippedGlintBlock;
import com.gtnewhorizons.angelica.rendering.tesr.BatchEligibility;
import com.gtnewhorizons.angelica.rendering.tesr.EntityMaterials;
import com.gtnewhorizons.angelica.rendering.tesr.EntityShadowReplay;
import com.gtnewhorizons.angelica.rendering.tesr.ModelPartBatcher;
import com.gtnewhorizons.angelica.rendering.tesr.TemplateBuffer;
import com.gtnewhorizons.angelica.rendering.tesr.TemplateCapture;
import com.gtnewhorizon.gtnhlib.client.renderer.TessellatorManager;
import net.coderbot.iris.gbuffer_overrides.matching.SpecialCondition;
import net.coderbot.iris.layer.GbufferPrograms;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms.TransformType;
import net.minecraft.client.renderer.color.ItemColors;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.ForgeModContainer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shares baked item capture between dropped items and items held by world entities. */
public final class DroppedItemInstancer {

    private static final Logger LOGGER = LogManager.getLogger("DroppedItemInstancer");
    private static final Set<String> loggedDiagnosticKeys = new HashSet<>();
    private static final Set<String> loggedHeldItemSites = new HashSet<>();

    /** The item was queued; the caller must not run the vanilla draw. */
    public static final long SKIP = -2L;
    /** The item was left to the vanilla draw, which has not run yet. */
    public static final long RUN = -1L;

    /**
     * The colour {@code RenderItem.renderEffect} multiplies into the glint geometry, packed the way
     * the instance layout stores colours. The method passes {@code -8372020}, which
     * {@code ForgeHooksClient.putQuadColor} reads as {@code 0xAARRGGBB}, so the same channels packed
     * as {@code 0xAABBGGRR} are {@code 0xFFCC4080}: red 0x80, green 0x40, blue 0xCC.
     */
    private static final int ITEM_GLINT_COLOR = 0xFFCC4080;
    private static final int CAPTURE_BYTES = 16 * 1024;
    private static final EnumFacing[] FACINGS = EnumFacing.values();

    private static final ItemPropCache<ItemMesh> MESHES = new ItemPropCache<>(
        AngelicaConfig::itemRendererCacheSize, ItemMesh::new, null);
    private static final BufferBuilder CAPTURE = new BufferBuilder(CAPTURE_BYTES);

    private static int scopeDepth;
    private static final ArrayList<EntityItem> scopeEntities = new ArrayList<>();
    private static long part = RUN;
    private static final ArrayList<DiagnosticFrame> diagnosticFrames = new ArrayList<>();
    private static int diagnosticDepth;

    private static long instanced;
    private static long glintInstanced;
    private static long cached;
    private static long captured;

    /** Why a dropped item was left to the vanilla draw. */
    public enum BailReason {
        /** Drawn outside a {@code RenderEntityItem.doRender} call, such as a GUI or held item. */
        SCOPE,
        /** No item to draw. */
        STACK,
        /** The model renders through its tile entity item stack renderer. */
        MODEL,
        /** Forge's lit item path owns the draw. */
        EMISSIVE,
        /** A mod is scaling item vertex alpha, so the captured colours would be stale. */
        ALPHA_OVERRIDE,
        /** The draw happens inside a display list recording. */
        RECORDING,
        /** The model class carries no guarantee that its quads stay the same between frames. */
        STABLE,
        /** The model has no quads in the {@code ITEM} format. */
        FORMAT,
        /** No entity batch is open. */
        NOT_ALLOWED,
        /** A tessellator capture is open and owns the vertices. */
        CAPTURE,
        /** The blend, depth or alpha state is not one the batch can reproduce. */
        MATERIAL,
        /** The captured template is empty. */
        GEOMETRY,
        /** The batch refused the geometry. */
        QUEUE,
        /** The batch refused the glint layers. */
        GLINT;

        public static final BailReason[] VALUES = values();
    }

    private static final class DiagnosticFrame {
        private EntityItem entity;
        private ItemStack stack;
        private IBakedModel model;
        private BailReason bailReason;
        private long drawsAtStart;
    }

    private static final long[] bails = new long[BailReason.VALUES.length];

    private DroppedItemInstancer() {
    }

    public static long statInstanced() {
        return instanced;
    }

    public static long statGlintInstanced() {
        return glintInstanced;
    }

    public static long statCached() {
        return cached;
    }

    public static long statCaptured() {
        return captured;
    }

    public static long statBail(BailReason reason) {
        return bails[reason.ordinal()];
    }

    /** The instance colour of the glint layers, in the byte order the instance layout stores. */
    public static int glintColor() {
        return ITEM_GLINT_COLOR;
    }

    /** Whether the vanilla glint pass may run for the current render scope. */
    public static boolean shouldRenderEffect() {
        return scopeDepth == 0 || AngelicaConfig.glintWorld();
    }

    /** Starts the Iris glint scope when the current render effect is enabled. */
    public static void beginGlintScope() {
        if (shouldRenderEffect()) {
            GbufferPrograms.setupSpecialRenderCondition(SpecialCondition.GLINT);
        }
    }

    /** Ends the Iris glint scope when the current render effect is enabled. */
    public static void endGlintScope() {
        if (shouldRenderEffect()) {
            GbufferPrograms.teardownSpecialRenderCondition();
        }
    }

    /**
     * Opens the scope a dropped item render runs in. Only draws inside this scope are instanced,
     * which keeps the GUI, the held item and item frames on the vanilla path.
     */
    public static void enterScope(EntityItem entity) {
        scopeDepth++;
        scopeEntities.add(entity);
    }

    public static void enterScope() {
        enterScope(null);
    }

    /** Closes the scope opened by {@link #enterScope}. */
    public static void exitScope() {
        if (scopeDepth > 0) {
            scopeDepth--;
            scopeEntities.remove(scopeEntities.size() - 1);
        }
        forgetItemDraws();
    }

    /**
     * Reports why this render cannot be instanced.
     *
     * @return the reason, or {@code null} when the model itself is one the instancer accepts
     */
    public static BailReason check(ItemStack stack, IBakedModel model) {
        if (scopeDepth == 0) {
            return BailReason.SCOPE;
        }
        return checkModel(stack, model);
    }

    private static BailReason checkModel(ItemStack stack, IBakedModel model) {
        if (stack == null || stack.isEmpty() || stack.getItem() == null || model == null) {
            return BailReason.STACK;
        }
        if (model.isBuiltInRenderer()) {
            return BailReason.MODEL;
        }
        if (ForgeModContainer.allowEmissiveItems) {
            return BailReason.EMISSIVE;
        }
        if (ItemVertexAlphaOverrides.isActive()) {
            return BailReason.ALPHA_OVERRIDE;
        }
        if (!FastLitItemDisplayListCache.isStableModel(model)) {
            return BailReason.STABLE;
        }
        return hasItemQuads(model) ? null : BailReason.FORMAT;
    }

    /** Returns {@link #SKIP} after queueing a dropped item or the draw count for its vanilla path. */
    public static long renderItemPart(ItemQuadWriter writer, ItemColors colors, ItemStack stack, IBakedModel model) {
        beginDiagnosticFrame(stack, model);
        if (scopeDepth == 0) {
            return part = bail(BailReason.SCOPE, RUN);
        }
        return part = queueItem(writer, colors, stack, model, EntityMaterials.GLINT);
    }

    /** Runs after Forge applies the camera transform, with vanilla still owning the outer render state. */
    public static void renderHeldItem(ItemQuadWriter writer, ItemColors colors, ItemStack stack, IBakedModel model,
                                      TransformType transform, Runnable drawVanilla) {
        final boolean entityBatchActive = ModelPartBatcher.INSTANCE.entitiesActive();
        final String bypassReason = transform != TransformType.THIRD_PERSON_LEFT_HAND
            && transform != TransformType.THIRD_PERSON_RIGHT_HAND ? "transform"
            : scopeDepth != 0 ? "item_scope" : !entityBatchActive ? "batch_inactive" : null;
        if (bypassReason != null) {
            drawHeldItemVanilla(drawVanilla, stack, model, transform, bypassReason, entityBatchActive);
            return;
        }
        final long drawnFrom = queueItem(writer, colors, stack, model, EntityMaterials.ITEM_GLINT);
        if (drawnFrom != SKIP) {
            try {
                drawHeldItemVanilla(drawVanilla, stack, model, transform, "instancing_fallback", entityBatchActive);
            } finally {
                reportFallback(drawnFrom);
            }
        }
    }

    private static void drawHeldItemVanilla(Runnable draw, ItemStack stack, IBakedModel model, TransformType transform,
                                            String route, boolean entityBatchActive) {
        final boolean logDiagnostics = entityBatchActive && ActiniumRuntime.options().debug.enableEntityRenderDiagnostics;
        final long drawsBefore = logDiagnostics ? GLSMDraws.drawCalls : 0L;
        final Throwable trace = logDiagnostics ? new Throwable() : null;
        try {
            draw.run();
        } finally {
            final long draws = logDiagnostics ? GLSMDraws.drawCalls - drawsBefore : 0L;
            if (logDiagnostics && draws > 0L) {
                final String item = stack == null || stack.isEmpty() ? "empty" : stack.getItem().getClass().getName();
                final String modelName = model == null ? "null" : model.getClass().getName();
                final String key = route + '|' + transform + '|' + item + '|' + modelName
                    + '|' + Arrays.toString(trace.getStackTrace());
                if (loggedHeldItemSites.add(key)) {
                    LOGGER.warn("[ACTINIUM-ENTITY-DIAGNOSTICS] heldItem route={} transform={} item={} model={} immediateDraws={} stack",
                        route, transform, item, modelName, draws, trace);
                }
            }
        }
    }

    private static long queueItem(ItemQuadWriter writer, ItemColors colors, ItemStack stack, IBakedModel model,
                                  TesrMaterial glintMaterial) {
        final long drawsBefore = GLSMDraws.drawCalls;
        final BailReason reason = checkModel(stack, model);
        if (reason != null) {
            return bail(reason, reason == BailReason.STACK ? RUN : drawsBefore);
        }
        final ModelPartBatcher batcher = ModelPartBatcher.INSTANCE;
        if (!BatchEligibility.batchingAllowed() || !batcher.isActive()) {
            return bail(BailReason.NOT_ALLOWED, drawsBefore);
        }
        if (GLStateManager.isRecordingDisplayList()) {
            return bail(BailReason.RECORDING, drawsBefore);
        }
        if (TessellatorManager.shouldInterceptBufferBuilderDraw()
            || TessellatorManager.shouldInterceptDraw(Tessellator.getInstance())) {
            return bail(BailReason.CAPTURE, drawsBefore);
        }
        final TesrMaterial material = EntityMaterials.itemFromCurrentState(true);
        if (material == null || GLSMMatrices.getMatrixMode().getMode() != GL11.GL_MODELVIEW
            || GLSMTextures.getActiveTextureUnit() != 0) {
            return bail(BailReason.MATERIAL, drawsBefore);
        }
        final boolean held = glintMaterial == EntityMaterials.ITEM_GLINT;
        final boolean glint = (held || AngelicaConfig.glintWorld()) && stack.hasEffect();
        if (glint && (!batcher.canQueueSkippedItemGlint(glintMaterial)
            || (batcher.isShadowPass() && GLSMRasterizer.getPolygonState().getOffsetClamp() != 0)
            || (held && (!ItemGlint.canResetStencil() || !ItemGlint.layersCoverSamePixels())))) {
            return bail(BailReason.GLINT, drawsBefore);
        }
        final ItemMesh mesh = mesh(writer, colors, stack, model);
        if (mesh == null) {
            return bail(BailReason.GEOMETRY, drawsBefore);
        }
        final TemplateBuffer glintTemplate = glint ? mesh.glintTemplate(writer, model) : null;
        if (glint && glintTemplate == null) {
            return bail(BailReason.GLINT, drawsBefore);
        }

        GLSMMatrices.glPushMatrix();
        try {
            GLSMMatrices.glTranslatef(-0.5F, -0.5F, -0.5F);
            if (!batcher.queueTemplate(mesh.base, material)) {
                return bail(BailReason.QUEUE, drawsBefore);
            }
            instanced++;
            BatchEligibility.onPartQueued();
            if (glint) {
                if (batcher.queueSkippedItemGlint(glintTemplate, glintMaterial, ITEM_GLINT_COLOR)) {
                    glintInstanced += 2;
                    BatchEligibility.onPartQueued();
                    BatchEligibility.onPartQueued();
                } else if (batcher.isShadowPass()) {
                    // Replay must redraw the entity in the main pass if it cannot attach the glint to the base.
                    EntityShadowReplay.rejectRenderFailed();
                } else {
                    throw new IllegalStateException("Item glint submission failed after preflight");
                }
                SkippedGlintBlock.applyItemGlintExitState();
            }
        } finally {
            GLSMMatrices.glPopMatrix();
        }
        return SKIP;
    }

    /**
     * Closes one {@code RenderItem.renderItem} call. A draw that stayed vanilla is reported to the
     * batch as an expected draw, so the renderer that produced it is not mistaken for one that
     * mixes its own geometry with batched parts.
     */
    public static void endPart() {
        final long drawnFrom = part;
        part = RUN;
        final DiagnosticFrame diagnostic = endDiagnosticFrame();
        final long draws = diagnostic == null ? 0L : GLSMDraws.drawCalls - diagnostic.drawsAtStart;
        if (ActiniumRuntime.options().debug.enableEntityRenderDiagnostics && diagnostic != null
            && diagnostic.entity != null && diagnostic.stack != null && diagnostic.model != null
            && draws > 0) {
            final EntityItem entity = diagnostic.entity;
            final String item = diagnostic.stack.isEmpty() ? "empty" : diagnostic.stack.getItem().getClass().getName();
            final String key = entity.getClass().getName() + '|' + item + '|' + diagnostic.model.getClass().getName()
                + '|' + diagnostic.bailReason;
            if (loggedDiagnosticKeys.add(key)) {
                LOGGER.info("[ACTINIUM-ENTITY-DIAGNOSTICS] droppedItem entity={}#{}@{},{},{} item={} count={} model={} bail={} immediateDraws={}",
                    entity.getClass().getName(), entity.getEntityId(), entity.posX, entity.posY, entity.posZ,
                    item, diagnostic.stack.getCount(), diagnostic.model.getClass().getName(), diagnostic.bailReason, draws);
            }
        }
        reportFallback(drawnFrom);
        clearDiagnosticFrame(diagnostic);
    }

    private static void beginDiagnosticFrame(ItemStack stack, IBakedModel model) {
        if (diagnosticDepth == diagnosticFrames.size()) {
            diagnosticFrames.add(new DiagnosticFrame());
        }
        final DiagnosticFrame frame = diagnosticFrames.get(diagnosticDepth++);
        frame.entity = scopeEntities.isEmpty() ? null : scopeEntities.get(scopeEntities.size() - 1);
        frame.stack = stack;
        frame.model = model;
        frame.bailReason = null;
        frame.drawsAtStart = GLSMDraws.drawCalls;
    }

    private static DiagnosticFrame endDiagnosticFrame() {
        return diagnosticDepth == 0 ? null : diagnosticFrames.get(--diagnosticDepth);
    }

    private static void clearDiagnosticFrame(DiagnosticFrame frame) {
        if (frame == null) return;
        frame.entity = null;
        frame.stack = null;
        frame.model = null;
        frame.bailReason = null;
        frame.drawsAtStart = 0L;
    }

    private static void reportFallback(long drawnFrom) {
        if (drawnFrom >= 0L) {
            BatchEligibility.onPartFallback(drawnFrom, GLSMDraws.drawCalls);
        }
    }

    /** Drops the per-draw state a renderer that threw in the middle of an item left behind. */
    public static void forgetItemDraws() {
        part = RUN;
        diagnosticDepth = 0;
        for (DiagnosticFrame frame : diagnosticFrames) {
            clearDiagnosticFrame(frame);
        }
    }

    /** Drops every cached mesh, on a texture reload or a shader pack change. */
    public static void clear() {
        forgetItemDraws();
        MESHES.clear();
    }

    /**
     * The template this stack and model draw from, captured on first use.
     *
     * @return the template, or {@code null} when the model has no quads in the {@code ITEM} format
     */
    public static TemplateBuffer template(ItemQuadWriter writer, ItemColors colors, ItemStack stack, IBakedModel model) {
        final ItemMesh mesh = mesh(writer, colors, stack, model);
        return mesh == null ? null : mesh.base;
    }

    /** The cache entry of this render, capturing it when the render was not seen before. */
    private static ItemMesh mesh(ItemQuadWriter writer, ItemColors colors, ItemStack stack, IBakedModel model) {
        final List<BakedQuad>[] faces = gather(model);
        final int[] tints = FastLitItemDisplayListCache.getQuadColors(colors, flatten(faces), -1, stack);
        final ItemMesh hit = MESHES.get(model, tints);
        if (hit != null) {
            cached++;
            return hit;
        }
        final TemplateBuffer base = emit(writer, faces, -1, stack);
        if (base == null) {
            return null;
        }
        final ItemMesh mesh = MESHES.insert(model, tints);
        mesh.base = base;
        mesh.untinted = untinted(tints);
        captured++;
        return mesh;
    }

    private static boolean hasItemQuads(IBakedModel model) {
        for (EnumFacing facing : FACINGS) {
            if (!itemFormat(model.getQuads(null, facing, 0L))) {
                return false;
            }
        }
        return itemFormat(model.getQuads(null, null, 0L));
    }

    private static boolean itemFormat(List<BakedQuad> quads) {
        for (BakedQuad quad : quads) {
            if (!FastLitItemDisplayListCache.isItemFormat(quad)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Writes the model's quads through the renderer's own quad writer into the capture buffer and
     * converts the result to a template.
     *
     * @param color colour the writer multiplies into every vertex, or {@code -1} to resolve each
     *              quad's tint index through the stack
     * @param stack stack the tint indices resolve against, or {@code ItemStack.EMPTY} for the
     *              untinted geometry the glint pass needs
     */
    private static TemplateBuffer emit(ItemQuadWriter writer, List<BakedQuad>[] faces, int color, ItemStack stack) {
        final int brightness = GLSMConfig.packedLastBrightness();
        CAPTURE.begin(GL11.GL_QUADS, DefaultVertexFormats.ITEM);
        final TemplateBuffer template;
        try {
            for (List<BakedQuad> face : faces) {
                writer.renderQuads(CAPTURE, face, color, stack);
            }
            template = TemplateCapture.fromItemBuffer(CAPTURE, brightness);
        } finally {
            CAPTURE.finishDrawing();
            CAPTURE.reset();
        }
        return template;
    }

    private static boolean untinted(int[] tints) {
        for (int tint : tints) {
            if (tint != -1) {
                return false;
            }
        }
        return true;
    }

    private static List<BakedQuad> flatten(List<BakedQuad>[] faces) {
        int size = 0;
        for (List<BakedQuad> face : faces) {
            size += face.size();
        }
        final List<BakedQuad> all = new ArrayList<>(size);
        for (List<BakedQuad> face : faces) {
            all.addAll(face);
        }
        return all;
    }

    @SuppressWarnings("unchecked")
    private static List<BakedQuad>[] gather(IBakedModel model) {
        final List<BakedQuad>[] faces = new List[FACINGS.length + 1];
        for (int i = 0; i < FACINGS.length; i++) {
            faces[i] = model.getQuads(null, FACINGS[i], 0L);
        }
        faces[FACINGS.length] = model.getQuads(null, null, 0L);
        return faces;
    }

    private static long bail(BailReason reason, long drawsFrom) {
        if (diagnosticDepth > 0) {
            diagnosticFrames.get(diagnosticDepth - 1).bailReason = reason;
        }
        bails[reason.ordinal()]++;
        return drawsFrom;
    }

    /** The meshes one model renders from: the tinted base and, once needed, the untinted glint. */
    private static final class ItemMesh {
        private TemplateBuffer base;
        private TemplateBuffer glint;
        private boolean untinted;

        /**
         * The glint geometry of this model. A render whose quads carry no tint index produces the
         * untinted geometry already, so the base template serves both passes.
         */
        private TemplateBuffer glintTemplate(ItemQuadWriter writer, IBakedModel model) {
            if (this.untinted) {
                return this.base;
            }
            if (this.glint == null) {
                this.glint = emit(writer, gather(model), -1, ItemStack.EMPTY);
            }
            return this.glint;
        }
    }
}
