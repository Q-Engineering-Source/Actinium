package com.gtnewhorizons.angelica.rendering.items;

import com.dhj.actinium.render.FastLitItemDisplayListCache;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.dhj.actinium.render.ItemVertexAlphaOverrides;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.block.model.SimpleBakedModel;
import net.minecraft.client.renderer.color.ItemColors;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.init.Bootstrap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.client.model.pipeline.LightUtil;
import net.minecraftforge.common.ForgeModContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers which dropped items the instancer refuses and why. Every refusal here has to end in the
 * vanilla draw, so the reported reason is the only thing that distinguishes "handled by the batch"
 * from "left to the renderer".
 *
 * <p>The alpha override source is registered for one case and removed again, so no other test in
 * the JVM sees a registration this class added.</p>
 */
class DroppedItemBailTest {

    private static final AtomicReference<Float> ALPHA = new AtomicReference<>(1.0F);
    private static final ItemColors COLORS = new ItemColors() {
        @Override
        public int colorMultiplier(ItemStack stack, int tintIndex) {
            return 0xFF00FF00;
        }
    };
    /** The vanilla quad loop, so a capture through this stub writes the same vertices. */
    private static final ItemQuadWriter QUADS = (buffer, quads, color, stack) -> {
        final int[] colors = FastLitItemDisplayListCache.getQuadColors(COLORS, quads, color, stack);
        for (int i = 0; i < quads.size(); i++) {
            LightUtil.renderQuadColor(buffer, quads.get(i), colors[i]);
        }
    };

    private boolean savedEmissive;
    private boolean savedGlintWorld;

    @BeforeAll
    static void bootstrapRegistries() {
        Bootstrap.register();
    }

    @BeforeEach
    void enterDroppedItemScope() {
        ALPHA.set(1.0F);
        this.savedEmissive = ForgeModContainer.allowEmissiveItems;
        this.savedGlintWorld = ActiniumRuntime.options().quality.glintWorld;
        ForgeModContainer.allowEmissiveItems = false;
        ActiniumRuntime.options().quality.glintWorld = true;
        DroppedItemInstancer.enterScope();
    }

    @AfterEach
    void leaveDroppedItemScope() {
        DroppedItemInstancer.exitScope();
        ItemVertexAlphaOverrides.clear();
        ALPHA.set(1.0F);
        ForgeModContainer.allowEmissiveItems = this.savedEmissive;
        ActiniumRuntime.options().quality.glintWorld = this.savedGlintWorld;
    }

    @Test
    void disablingWorldGlintSuppressesTheDroppedItemEffect() {
        ActiniumRuntime.options().quality.glintWorld = false;
        assertFalse(DroppedItemInstancer.shouldRenderEffect(),
            "the vanilla renderEffect path must be skipped for a dropped item while world glint is off");

        ActiniumRuntime.options().quality.glintWorld = true;
        assertTrue(DroppedItemInstancer.shouldRenderEffect(), "enabling world glint restores the dropped item effect");
    }

    @Test
    void disablingWorldGlintDoesNotSuppressHeldOrInventoryEffects() {
        ActiniumRuntime.options().quality.glintWorld = false;
        DroppedItemInstancer.exitScope();

        assertTrue(DroppedItemInstancer.shouldRenderEffect(),
            "held and inventory rendering is outside the dropped item scope and keeps its own behavior");
    }

    @Test
    void anItemOutsideTheDroppedItemScopeStaysVanilla() {
        DroppedItemInstancer.exitScope();

        assertEquals(DroppedItemInstancer.BailReason.SCOPE, DroppedItemInstancer.check(stack(), model()));
    }

    @Test
    void anEmptyStackStaysVanilla() {
        assertEquals(DroppedItemInstancer.BailReason.STACK, DroppedItemInstancer.check(ItemStack.EMPTY, model()));
        assertEquals(DroppedItemInstancer.BailReason.STACK, DroppedItemInstancer.check(null, model()));
    }

    @Test
    void aBuiltInRendererStaysVanilla() {
        final IBakedModel builtIn = new TestModel() {
            @Override
            public boolean isBuiltInRenderer() {
                return true;
            }
        };

        assertEquals(DroppedItemInstancer.BailReason.MODEL, DroppedItemInstancer.check(stack(), builtIn));
    }

    @Test
    void anEmissiveItemStaysVanilla() {
        ForgeModContainer.allowEmissiveItems = true;

        assertEquals(DroppedItemInstancer.BailReason.EMISSIVE, DroppedItemInstancer.check(stack(), model()));
    }

    @Test
    void aScaledVertexAlphaStaysVanilla() {
        ItemVertexAlphaOverrides.register(ALPHA::get);
        ALPHA.set(0.5F);

        assertEquals(DroppedItemInstancer.BailReason.ALPHA_OVERRIDE, DroppedItemInstancer.check(stack(), model()));
    }

    @Test
    void aModelOutsideTheStableSetStaysVanilla() {
        assertEquals(DroppedItemInstancer.BailReason.STABLE, DroppedItemInstancer.check(stack(), new TestModel()));
    }

    @Test
    void aQuadThatIsNotInTheItemFormatStaysVanilla() {
        final IBakedModel model = new SimpleBakedModel(new ArrayList<>(), faces(quad(DefaultVertexFormats.POSITION_TEX)),
            false, true, null, ItemCameraTransforms.DEFAULT, ItemOverrideList.NONE);

        assertEquals(DroppedItemInstancer.BailReason.FORMAT, DroppedItemInstancer.check(stack(), model));
    }

    @Test
    void anAcceptedModelReportsTheDrawItLeavesToVanilla() {
        assertNull(DroppedItemInstancer.check(stack(), model()), "the model itself has to be accepted");
        final long refused = DroppedItemInstancer.statBail(DroppedItemInstancer.BailReason.NOT_ALLOWED);
        final long drawsBefore = GLSMDraws.drawCalls;

        assertEquals(drawsBefore, DroppedItemInstancer.renderItemPart(QUADS, COLORS, stack(), model()),
            "a draw left to vanilla reports the count it starts from, so the batch can expect it");
        assertEquals(refused + 1, DroppedItemInstancer.statBail(DroppedItemInstancer.BailReason.NOT_ALLOWED),
            "a closed batch is the only thing left to refuse the item");
    }

    @Test
    void aDrawThatNeverHappensReportsNoDrawCount() {
        DroppedItemInstancer.exitScope();

        assertEquals(DroppedItemInstancer.RUN, DroppedItemInstancer.renderItemPart(QUADS, COLORS, stack(), model()),
            "an item outside the dropped item scope is not this class's draw to account for");
        DroppedItemInstancer.enterScope();
        assertEquals(DroppedItemInstancer.RUN, DroppedItemInstancer.renderItemPart(QUADS, COLORS, ItemStack.EMPTY, model()),
            "an empty stack draws nothing");
    }

    private static ItemStack stack() {
        return new ItemStack(new Item(), 1);
    }

    private static IBakedModel model() {
        return new SimpleBakedModel(new ArrayList<>(), faces(quad(DefaultVertexFormats.ITEM)), false, true, null,
            ItemCameraTransforms.DEFAULT, ItemOverrideList.NONE);
    }

    /** Every facing key, the way {@code SimpleBakedModel.Builder} leaves a baked model. */
    private static Map<EnumFacing, List<BakedQuad>> faces(BakedQuad quad) {
        final Map<EnumFacing, List<BakedQuad>> faces = new Object2ObjectOpenHashMap<>();
        for (EnumFacing facing : EnumFacing.values()) {
            faces.put(facing, facing == EnumFacing.NORTH ? Collections.singletonList(quad) : Collections.emptyList());
        }
        return faces;
    }

    private static BakedQuad quad(VertexFormat format) {
        final int stride = format.getIntegerSize();
        final int[] vertexData = new int[stride * 4];
        for (int vertex = 0; vertex < 4; vertex++) {
            final int at = vertex * stride;
            vertexData[at] = Float.floatToRawIntBits(vertex);
            for (int element = 1; element < stride; element++) {
                vertexData[at + element] = 0xFFFFFFFF;
            }
        }
        return new BakedQuad(vertexData, 0, EnumFacing.NORTH, null, true, format);
    }

    /** A model whose class the instancer has no stability guarantee for. */
    private static class TestModel implements IBakedModel {
        @Override
        public List<BakedQuad> getQuads(IBlockState state, EnumFacing side, long rand) {
            return Collections.emptyList();
        }

        @Override
        public boolean isAmbientOcclusion() {
            return false;
        }

        @Override
        public boolean isGui3d() {
            return true;
        }

        @Override
        public boolean isBuiltInRenderer() {
            return false;
        }

        @Override
        public TextureAtlasSprite getParticleTexture() {
            return null;
        }

        @Override
        public ItemOverrideList getOverrides() {
            return ItemOverrideList.NONE;
        }
    }
}
