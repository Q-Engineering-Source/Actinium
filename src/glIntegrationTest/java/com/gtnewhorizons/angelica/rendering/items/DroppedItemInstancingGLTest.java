package com.gtnewhorizons.angelica.rendering.items;

import com.dhj.actinium.runtime.ActiniumRuntime;
import com.gtnewhorizons.angelica.glsm.GLSMBlend;
import com.gtnewhorizons.angelica.glsm.GLSMCurrentAttribs;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMLighting;
import com.gtnewhorizons.angelica.glsm.GLSMMatrices;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import com.gtnewhorizons.angelica.glsm.ffp.ShaderManager;
import com.gtnewhorizons.angelica.rendering.tesr.BatchEligibility;
import com.gtnewhorizons.angelica.rendering.tesr.ModelPartBatcher;
import com.gtnewhorizons.angelica.rendering.tesr.TemplateBuffer;
import com.gtnewhorizons.angelica.rendering.tesr.TesrBatchRenderer;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.block.model.SimpleBakedModel;
import net.minecraft.client.renderer.color.ItemColors;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Enchantments;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.model.pipeline.LightUtil;
import org.joml.Matrix4f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.gtnewhorizon.gtnhlib.client.renderer.cel.util.ModelQuadUtil.COLOR_INDEX;
import static com.gtnewhorizon.gtnhlib.client.renderer.cel.util.ModelQuadUtil.LIGHT_INDEX;
import static com.gtnewhorizon.gtnhlib.client.renderer.cel.util.ModelQuadUtil.NORMAL_INDEX;
import static com.gtnewhorizon.gtnhlib.client.renderer.cel.util.ModelQuadUtil.TEX_X_INDEX;
import static com.gtnewhorizon.gtnhlib.client.renderer.cel.util.ModelQuadUtil.TEX_Y_INDEX;
import static com.gtnewhorizon.gtnhlib.client.renderer.cel.util.ModelQuadUtil.VERTEX_SIZE;
import static com.gtnewhorizon.gtnhlib.client.renderer.cel.util.ModelQuadUtil.X_INDEX;
import static com.gtnewhorizon.gtnhlib.client.renderer.cel.util.ModelQuadUtil.Y_INDEX;
import static com.gtnewhorizon.gtnhlib.client.renderer.cel.util.ModelQuadUtil.Z_INDEX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the dropped item path against a context: the template against the vertices vanilla writes,
 * the two glint layers and the state the skipped glint block would have left, and the fallback when
 * the batch refuses the item.
 */
class DroppedItemInstancingGLTest extends GLStateManagerIntegrationFixture {

    private static final ResourceLocation ATLAS = new ResourceLocation("minecraft", "textures/atlas/blocks.png");
    private static final int LIGHTMAP_U = 0x00F0;
    private static final int LIGHTMAP_V = 0x000A;
    private static final int LIGHTMAP = LIGHTMAP_U | LIGHTMAP_V << 16;
    private static final int TINT = 0xFF00FF00;
    private static final int OTHER_TINT = 0xFFFF0000;

    private static final ItemColors COLORS = new ItemColors() {
        @Override
        public int colorMultiplier(ItemStack stack, int tintIndex) {
            return TINT;
        }
    };
    private static final ItemColors OTHER_COLORS = new ItemColors() {
        @Override
        public int colorMultiplier(ItemStack stack, int tintIndex) {
            return OTHER_TINT;
        }
    };

    private boolean savedBatching;
    private boolean bracketOpen;

    @BeforeAll
    static void bootstrapRegistries() {
        Bootstrap.register();
    }

    @BeforeEach
    void setUp() {
        WindowBackends.useSdl();
        installDefaultGlsmConfig();
        GLStateManager.markSplashComplete("DroppedItemInstancingGLTest");
        GLStateManager.bindDisplayContext(new Object(), context);
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMMatrices.glLoadIdentity();
        this.savedBatching = ActiniumRuntime.options().performance.enableEntityBatching;
        ActiniumRuntime.options().performance.enableEntityBatching = true;
        ShaderManager.enable();
        TesrBatchRenderer.INSTANCE.beginPass(TesrBatchRenderer.PASS_MAIN_0, new Matrix4f(), 0.0, 0.0, 0.0);
        ModelPartBatcher.INSTANCE.begin(ModelPartBatcher.Mode.ENTITIES);
        // The state RenderEntityItem.doRender leaves behind for the item draw.
        GLSMDepthStencilAlpha.enableDepthTest();
        GLSMDepthStencilAlpha.glDepthFunc(GL11.GL_LEQUAL);
        GLSMDepthStencilAlpha.glDepthMask(true);
        GLSMDepthStencilAlpha.enableAlphaTest();
        GLSMDepthStencilAlpha.glAlphaFunc(GL11.GL_GREATER, 0.1F);
        GLSMBlend.enableBlend();
        GLSMBlend.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GLSMCurrentAttribs.setLightmapTextureCoords(GL13.GL_TEXTURE1, LIGHTMAP_U, LIGHTMAP_V);
        GLSMCurrentAttribs.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        ModelPartBatcher.onTextureBind(ATLAS, GLSMTextures.getBoundTextureForServerState());
        DroppedItemInstancer.clear();
        DroppedItemInstancer.enterScope();
    }

    @AfterEach
    void tearDown() {
        DroppedItemInstancer.endPart();
        DroppedItemInstancer.exitScope();
        if (this.bracketOpen) {
            BatchEligibility.end(BatchEligibility.SAFE, GLSMDraws.drawCalls);
            this.bracketOpen = false;
        }
        // The harness has no Minecraft instance, so a textured layer cannot be drawn; discarding the
        // batch keeps the queued layers from reaching a later test's flush.
        ModelPartBatcher.INSTANCE.clear();
        TesrBatchRenderer.INSTANCE.clearRetained();
        ActiniumRuntime.options().performance.enableEntityBatching = this.savedBatching;
        ShaderManager.disable();
        DroppedItemInstancer.clear();
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError(), "the dropped item path must leave no GL error");
    }

    @Test
    void theTemplateHoldsTheVerticesTheVanillaDrawWrites() {
        final IBakedModel model = model(quad(EnumFacing.NORTH, -1, 0x40C0FF20), genericQuad(-1, 0x10203040));
        final BufferBuilder vanilla = new BufferBuilder(8 * 1024);
        vanilla.begin(GL11.GL_QUADS, DefaultVertexFormats.ITEM);
        for (EnumFacing facing : EnumFacing.values()) {
            for (BakedQuad quad : model.getQuads(null, facing, 0L)) {
                LightUtil.renderQuadColor(vanilla, quad, -1);
            }
        }
        for (BakedQuad quad : model.getQuads(null, null, 0L)) {
            LightUtil.renderQuadColor(vanilla, quad, -1);
        }
        final TemplateBuffer template = DroppedItemInstancer.template(COLORS, stack(false), model);

        assertEquals(vanilla.getVertexCount(), template.vertexCount, "one template vertex per vanilla vertex");
        assertEquals(GL11.GL_QUADS, template.drawMode);
        final int stride = vanilla.getVertexFormat().getSize();
        for (int vertex = 0; vertex < template.vertexCount; vertex++) {
            final int base = vertex * VERTEX_SIZE;
            final int at = vertex * stride;
            assertEquals(vanilla.getByteBuffer().getFloat(at), Float.intBitsToFloat(template.data[base + X_INDEX]), 1.0e-6F);
            assertEquals(vanilla.getByteBuffer().getFloat(at + 4), Float.intBitsToFloat(template.data[base + Y_INDEX]), 1.0e-6F);
            assertEquals(vanilla.getByteBuffer().getFloat(at + 8), Float.intBitsToFloat(template.data[base + Z_INDEX]), 1.0e-6F);
            assertEquals(vanilla.getByteBuffer().getFloat(at + 16), Float.intBitsToFloat(template.data[base + TEX_X_INDEX]), 1.0e-6F);
            assertEquals(vanilla.getByteBuffer().getFloat(at + 20), Float.intBitsToFloat(template.data[base + TEX_Y_INDEX]), 1.0e-6F);
            assertEquals(vanilla.getByteBuffer().getInt(at + 12), template.data[base + COLOR_INDEX]);
            assertEquals((vanilla.getByteBuffer().get(at + 24) & 0xFF)
                | (vanilla.getByteBuffer().get(at + 25) & 0xFF) << 8
                | (vanilla.getByteBuffer().get(at + 26) & 0xFF) << 16, template.data[base + NORMAL_INDEX]);
            assertEquals(LIGHTMAP, template.data[base + LIGHT_INDEX], "the capture keeps the frame lightmap");
        }
    }

    @Test
    void aDifferentTintCapturesADifferentTemplate() {
        final IBakedModel model = model(quad(EnumFacing.NORTH, 0, 0xFFFFFFFF), genericQuad(-1, 0xFFFFFFFF));

        final long capturedBefore = DroppedItemInstancer.statCaptured();
        final TemplateBuffer tinted = DroppedItemInstancer.template(COLORS, stack(false), model);
        final TemplateBuffer other = DroppedItemInstancer.template(OTHER_COLORS, stack(false), model);

        assertNotEquals(tinted, other, "one model with two tints needs two templates");
        assertNotEquals(tinted.data[COLOR_INDEX], other.data[COLOR_INDEX],
            "each template bakes the colour the vanilla draw would have written into its vertices");
        assertEquals(capturedBefore + 2, DroppedItemInstancer.statCaptured(), "one capture per tint");

        final long cachedBefore = DroppedItemInstancer.statCached();
        assertEquals(tinted, DroppedItemInstancer.template(COLORS, stack(false), model),
            "the second render of the same tint reuses its template");
        assertEquals(cachedBefore + 1, DroppedItemInstancer.statCached());
        assertEquals(capturedBefore + 2, DroppedItemInstancer.statCaptured(), "a hit must not capture again");
    }

    @Test
    void theGlintQueuesBothLayersAndLeavesTheStateTheSkippedBlockWould() {
        openBracket();
        final long glintPasses = ModelPartBatcher.INSTANCE.statGlintPassReuses();
        final IBakedModel model = model(quad(EnumFacing.NORTH, -1, 0xFFFFFFFF), genericQuad(-1, 0xFFFFFFFF));

        assertEquals(DroppedItemInstancer.SKIP, DroppedItemInstancer.renderItemPart(COLORS, stack(true), model),
            "an enchanted item is queued together with its glint");
        assertEquals(glintPasses + 1, ModelPartBatcher.INSTANCE.statGlintPassReuses());
        assertEquals(1L, DroppedItemInstancer.statInstanced(), "one instance for the base geometry");
        assertEquals(2L, DroppedItemInstancer.statGlintInstanced(), "one layer per glint pass");

        assertTrue(GLSMDepthStencilAlpha.isEffectiveDepthMaskEnabled(), "the skipped block restores the depth mask");
        assertEquals(GL11.GL_LEQUAL, GLSMDepthStencilAlpha.getDepthState().getFunc());
        assertTrue(GLSMLighting.getLightingState().isEnabled(), "the skipped block re-enables lighting");
        assertEquals(GL11.GL_SRC_ALPHA, GLSMBlend.getBlendState().getSrcRgb());
        assertEquals(GL11.GL_ONE_MINUS_SRC_ALPHA, GLSMBlend.getBlendState().getDstRgb());
    }

    @Test
    void aRefusedDrawReportsItsFallbackDraws() {
        final IBakedModel model = model(quad(EnumFacing.NORTH, -1, 0xFFFFFFFF), genericQuad(-1, 0xFFFFFFFF));
        final long refused = DroppedItemInstancer.statBail(DroppedItemInstancer.BailReason.NOT_ALLOWED);

        assertEquals(DroppedItemInstancer.RUN, DroppedItemInstancer.renderItemPart(COLORS, stack(false), model),
            "a closed batch leaves the item to the vanilla draw");
        assertEquals(refused + 1, DroppedItemInstancer.statBail(DroppedItemInstancer.BailReason.NOT_ALLOWED));
        DroppedItemInstancer.endPart();
        assertEquals(DroppedItemInstancer.RUN, DroppedItemInstancer.renderItemPart(COLORS, stack(false), model),
            "the fallback bookkeeping must not leak into the next item");
    }

    @Test
    void aStateTheBatchCannotReproduceStaysVanilla() {
        openBracket();
        final IBakedModel model = model(quad(EnumFacing.NORTH, -1, 0xFFFFFFFF), genericQuad(-1, 0xFFFFFFFF));
        final long refused = DroppedItemInstancer.statBail(DroppedItemInstancer.BailReason.MATERIAL);
        GLSMBlend.tryBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE);

        assertEquals(DroppedItemInstancer.RUN, DroppedItemInstancer.renderItemPart(COLORS, stack(false), model),
            "a blend the batch has no material for leaves the item to the vanilla draw");
        assertEquals(refused + 1, DroppedItemInstancer.statBail(DroppedItemInstancer.BailReason.MATERIAL));
    }

    private void openBracket() {
        BatchEligibility.begin(BatchEligibility.SAFE, GLSMDraws.drawCalls);
        this.bracketOpen = true;
    }

    private static IBakedModel model(BakedQuad faceQuad, BakedQuad generalQuad) {
        final Map<EnumFacing, List<BakedQuad>> faces = new Object2ObjectOpenHashMap<>();
        for (EnumFacing facing : EnumFacing.values()) {
            faces.put(facing, facing == EnumFacing.NORTH ? Collections.singletonList(faceQuad) : Collections.emptyList());
        }
        final List<BakedQuad> general = new ArrayList<>();
        general.add(generalQuad);
        return new SimpleBakedModel(general, faces, false, true, null, ItemCameraTransforms.DEFAULT,
            ItemOverrideList.NONE);
    }

    private static ItemStack stack(boolean enchanted) {
        final ItemStack stack = new ItemStack(new Item(), 1);
        if (enchanted) {
            stack.addEnchantment(Enchantments.SHARPNESS, 1);
        }
        return stack;
    }

    private static BakedQuad quad(EnumFacing facing, int tintIndex, int color) {
        return new BakedQuad(vertexData(color), tintIndex, facing, null, true, DefaultVertexFormats.ITEM);
    }

    private static BakedQuad genericQuad(int tintIndex, int color) {
        return new BakedQuad(vertexData(color), tintIndex, null, null, true, DefaultVertexFormats.ITEM);
    }

    /** One {@code ITEM} quad: 28 bytes, position, colour, texture coordinate and normal. */
    private static int[] vertexData(int color) {
        final int stride = DefaultVertexFormats.ITEM.getIntegerSize();
        final int[] data = new int[stride * 4];
        for (int vertex = 0; vertex < 4; vertex++) {
            final int at = vertex * stride;
            data[at] = Float.floatToRawIntBits(vertex);
            data[at + 1] = Float.floatToRawIntBits(vertex * 0.5F);
            data[at + 2] = Float.floatToRawIntBits(-vertex);
            data[at + 3] = color;
            data[at + 4] = Float.floatToRawIntBits(vertex * 0.25F);
            data[at + 5] = Float.floatToRawIntBits(0.75F);
            data[at + 6] = 0x00010000;
        }
        return data;
    }
}
