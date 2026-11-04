package com.dhj.actinium.compat.farmersfuturedelight;

import com.gtnewhorizon.gtnhlib.compat.Mods;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockRenderLayer;

import java.util.Map;

/**
 * Compatibility for {@code Farmers-Future-Delight} (mod id {@code farmers_future_delight}).
 *
 * <p>The mod emits its water surface quads only into the translucent layer, and it patches OptiFine
 * because that layer carries neither the water identity nor the layer membership Actinium decides
 * here. Both are reproduced from the mod's vanilla-visible traits, without referencing its classes.
 */
public final class FarmersFutureDelightCompat {
    public static final boolean IS_LOADED = Mods.FARMERSFUTUREDELIGHT;

    private static final String WATERLOGGED_PROPERTY = "waterlogged";

    /** The model class that emits the water surface quads, matched by name to stay unlinked. */
    private static final String WATER_QUAD_MODEL_CLASS =
            "xy177.farmersfuturedelight.client.model.WaterloggedPlantBakedModel";

    private FarmersFutureDelightCompat() {
    }

    public static boolean isWaterQuadModel(IBakedModel model) {
        return IS_LOADED && model != null
                && WATER_QUAD_MODEL_CLASS.equals(model.getClass().getName());
    }

    /** Covers blocks carrying the injected property and the mod's own water-material blocks. */
    public static boolean isWaterloggedState(IBlockState state) {
        if (!IS_LOADED || state == null) {
            return false;
        }
        return carriesWaterloggedProperty(state) || reportsWaterMaterial(state);
    }

    /**
     * Only blocks carrying the injected property qualify: the mod's own water-carrying blocks
     * declare the translucent layer themselves, so forcing it for a foreign water block would mesh
     * its model twice.
     */
    public static boolean shouldForceTranslucentLayer(IBlockState state, BlockRenderLayer layer) {
        return IS_LOADED && layer == BlockRenderLayer.TRANSLUCENT && carriesWaterloggedProperty(state);
    }

    public static boolean usesWaterIdentity(IBlockState state, IBakedModel model,
                                            BlockRenderLayer layer) {
        return IS_LOADED && layer == BlockRenderLayer.TRANSLUCENT
                && (isWaterQuadModel(model) || isWaterloggedState(state));
    }

    public static boolean usesWaterIdentity(IBlockState state, BlockRenderLayer layer) {
        return usesWaterIdentity(state, null, layer);
    }

    private static boolean carriesWaterloggedProperty(IBlockState state) {
        if (state == null) {
            return false;
        }
        for (Map.Entry<IProperty<?>, Comparable<?>> entry : state.getProperties().entrySet()) {
            if (WATERLOGGED_PROPERTY.equals(entry.getKey().getName())) {
                return Boolean.TRUE.equals(entry.getValue());
            }
        }
        return false;
    }

    private static boolean reportsWaterMaterial(IBlockState state) {
        Block block = state.getBlock();
        return block != Blocks.WATER && block != Blocks.FLOWING_WATER
                && state.getMaterial() == Material.WATER;
    }
}
