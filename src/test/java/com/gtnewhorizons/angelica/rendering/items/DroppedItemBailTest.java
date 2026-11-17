package com.gtnewhorizons.angelica.rendering.items;

import com.dhj.actinium.render.ItemVertexAlphaOverrides;
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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers which dropped items the instancer refuses and why. Every refusal here has to end in the
 * vanilla draw, so the reported reason is the only thing that distinguishes "handled by the batch"
 * from "left to the renderer".
 *
 * <p>The alpha override source is registered once for the whole JVM and reports the neutral
 * multiplier between tests, which is the value every other reader already sees.</p>
 */
class DroppedItemBailTest {

    private static final AtomicReference<Float> ALPHA = new AtomicReference<>(1.0F);
    private static final ItemColors COLORS = new ItemColors() {
        @Override
        public int colorMultiplier(ItemStack stack, int tintIndex) {
            return 0xFF00FF00;
        }
    };

    static {
        ItemVertexAlphaOverrides.register(ALPHA::get);
    }

    private boolean savedEmissive;

    @BeforeAll
    static void bootstrapRegistries() {
        Bootstrap.register();
    }

    @BeforeEach
    void enterDroppedItemScope() {
        ALPHA.set(1.0F);
        this.savedEmissive = ForgeModContainer.allowEmissiveItems;
        ForgeModContainer.allowEmissiveItems = false;
        DroppedItemInstancer.enterScope();
    }

    @AfterEach
    void leaveDroppedItemScope() {
        DroppedItemInstancer.exitScope();
        ALPHA.set(1.0F);
        ForgeModContainer.allowEmissiveItems = this.savedEmissive;
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
    void anAcceptedModelReachesTheBatchScope() {
        assertNull(DroppedItemInstancer.check(stack(), model()), "the model itself has to be accepted");
        final long refused = DroppedItemInstancer.statBail(DroppedItemInstancer.BailReason.NOT_ALLOWED);

        assertEquals(DroppedItemInstancer.RUN, DroppedItemInstancer.renderItemPart(COLORS, stack(), model()));
        assertEquals(refused + 1, DroppedItemInstancer.statBail(DroppedItemInstancer.BailReason.NOT_ALLOWED),
            "a closed batch is the only thing left to refuse the item");
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
