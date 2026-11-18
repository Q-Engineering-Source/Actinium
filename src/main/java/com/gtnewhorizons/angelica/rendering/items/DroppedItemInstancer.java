package com.gtnewhorizons.angelica.rendering.items;

import com.dhj.actinium.render.FastLitItemDisplayListCache;
import com.dhj.actinium.render.ItemVertexAlphaOverrides;
import com.gtnewhorizons.angelica.api.tesr.TesrMaterial;
import com.gtnewhorizons.angelica.config.AngelicaConfig;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMMatrices;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMConfig;
import com.gtnewhorizons.angelica.rendering.SkippedGlintBlock;
import com.gtnewhorizons.angelica.rendering.tesr.BatchEligibility;
import com.gtnewhorizons.angelica.rendering.tesr.EntityMaterials;
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
import net.minecraft.client.renderer.color.ItemColors;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.ForgeModContainer;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the render of a dropped item into an instanced draw.
 *
 * <p>1.12.2 has no equivalent of the 1.6/1.7 {@code RenderItem.renderDroppedItem} chain: the item
 * entity hands a baked model to {@code RenderItem.renderItem}, which writes the model's quads into
 * one {@code ITEM} buffer and draws it, then repeats the same geometry twice more for the
 * enchantment glint. This class replaces the three draws with one instance per item of a shared
 * template, plus the two glint layers the fixed function path renders from the same template.</p>
 *
 * <p>The template is captured by replaying the model's quads through the renderer's own
 * {@code renderQuads}, the same call the vanilla draw makes, and converting the resulting 28 byte
 * {@code ITEM} vertices to the eight int layout the batch reads. The capture stores the lightmap the
 * frame had, which is the value a template drawn as plain vertices reads; an instanced draw takes
 * its lightmap from the per-instance attribute instead. The stored value is what an item lit by
 * {@code RenderHelper.enableStandardItemLighting} keeps as its fixed brightness.</p>
 *
 * <p>Every path the batch cannot reproduce falls back to the vanilla draw: models outside the
 * stable set, non {@code ITEM} quads, built-in renderers, Forge's lit item path, a mod scaling item
 * vertex alpha, a display list recording and anything drawn outside a dropped item scope.</p>
 */
public final class DroppedItemInstancer {

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
    private static long part = RUN;

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
    public static void enterScope() {
        scopeDepth++;
    }

    /** Closes the scope opened by {@link #enterScope}. */
    public static void exitScope() {
        if (scopeDepth > 0) {
            scopeDepth--;
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

    /**
     * Queues the geometry of one dropped item: the base pass and, for an enchanted stack, both glint
     * layers.
     *
     * <p>The whole method is cancelled when this returns {@link #SKIP}, which is what removes the
     * vanilla draw. That also removes the only call site of {@code RenderItem.renderEffect}, so the
     * glint has to be queued by the same call instead of from a hook of its own. An item whose
     * glint cannot be queued is left entirely to vanilla, so the two passes never disagree about
     * which of them runs.</p>
     *
     * <p>A draw left to vanilla reports the draw count it starts from, which {@link #endPart} hands
     * to the batch as an expected draw once the vanilla call is done. Without it the batch sees a
     * renderer that queued a part and drew something else, which is the shape it answers with
     * {@code DENIED}.</p>
     *
     * @param writer the quad writer of the renderer, normally {@code RenderItem::renderQuads}
     * @param colors the item colour table the renderer resolves tint indices through
     * @return {@link #SKIP} when the caller must not draw, {@link #RUN} when nothing was drawn at
     *         all, otherwise the draw count the vanilla draw starts from, to be passed to
     *         {@link #endPart}
     */
    public static long renderItemPart(ItemQuadWriter writer, ItemColors colors, ItemStack stack, IBakedModel model) {
        final long drawsBefore = GLSMDraws.drawCalls;
        final BailReason reason = check(stack, model);
        if (reason == BailReason.SCOPE || reason == BailReason.STACK) {
            return bail(reason, RUN);
        }
        if (reason != null) {
            return bail(reason, drawsBefore);
        }
        if (!BatchEligibility.batchingAllowed() || !ModelPartBatcher.INSTANCE.isActive()) {
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
        if (material == null) {
            return bail(BailReason.MATERIAL, drawsBefore);
        }
        final ItemMesh mesh = mesh(writer, colors, stack, model);
        if (mesh == null) {
            return bail(BailReason.GEOMETRY, drawsBefore);
        }
        final boolean glint = AngelicaConfig.glintWorld() && stack.hasEffect();
        if (glint && !ModelPartBatcher.INSTANCE.canQueueSkippedItemGlint(EntityMaterials.GLINT)) {
            return bail(BailReason.GLINT, drawsBefore);
        }

        // The draw this replaces runs under RenderItem's own centring of the model about the origin.
        GLSMMatrices.glPushMatrix();
        try {
            GLSMMatrices.glTranslatef(-0.5F, -0.5F, -0.5F);
            if (!ModelPartBatcher.INSTANCE.queueTemplate(mesh.base, material)) {
                return bail(BailReason.QUEUE, drawsBefore);
            }
            instanced++;
            BatchEligibility.onPartQueued();
            if (glint) {
                queueGlint(mesh, writer, model);
            }
        } finally {
            GLSMMatrices.glPopMatrix();
        }
        return part = SKIP;
    }

    /**
     * Queues both glint layers of a dropped item.
     *
     * <p>The layers keep the two texture matrices {@code RenderItem.renderEffect} sets around its
     * two {@code renderModel} calls, and draw the untinted geometry with
     * {@value #ITEM_GLINT_COLOR} as the instance colour, which is the colour the vanilla pass
     * multiplies into every glint vertex.</p>
     */
    private static void queueGlint(ItemMesh mesh, ItemQuadWriter writer, IBakedModel model) {
        final TemplateBuffer template = mesh.glintTemplate(writer, model);
        if (template == null || !ModelPartBatcher.INSTANCE.queueSkippedItemGlint(template, EntityMaterials.GLINT, ITEM_GLINT_COLOR)) {
            // The base is already queued, so the vanilla glint would draw before it and the depth
            // equal test would drop it. Leaving it out keeps the item intact for this frame.
            bails[BailReason.GLINT.ordinal()]++;
            return;
        }
        glintInstanced += 2;
        BatchEligibility.onPartQueued();
        BatchEligibility.onPartQueued();
        // The skipped method restores the depth mask, the depth function, lighting and the blend
        // function before it returns; the block atlas is still bound because it never rebinds one.
        SkippedGlintBlock.applyItemGlintExitState();
    }

    /**
     * Closes one {@code RenderItem.renderItem} call. A draw that stayed vanilla is reported to the
     * batch as an expected draw, so the renderer that produced it is not mistaken for one that
     * mixes its own geometry with batched parts.
     */
    public static void endPart() {
        final long drawnFrom = part;
        part = RUN;
        if (drawnFrom >= 0L) {
            BatchEligibility.onPartFallback(drawnFrom, GLSMDraws.drawCalls);
        }
    }

    /** Drops the per-draw state a renderer that threw in the middle of an item left behind. */
    public static void forgetItemDraws() {
        part = RUN;
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
        bails[reason.ordinal()]++;
        return part = drawsFrom;
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
