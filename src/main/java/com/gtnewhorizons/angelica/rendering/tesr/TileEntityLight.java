package com.gtnewhorizons.angelica.rendering.tesr;

import com.dhj.actinium.compat.celeritasdynamiclights.CeleritasDynamicLightsCompat;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;

/** Try to replicate modern behavior for when shaderpacks are in use.*/
public final class TileEntityLight {

    private TileEntityLight() {}

    /**
     * Computes the packed light of a tile entity whose block renders with neighbour brightness.
     *
     * <p>The sky and block light are sampled from the world again and the result replaces the value
     * the tile entity dispatcher passed in. Celeritas Dynamic Lights merges its level into the
     * dispatcher's combined light, and the recomputed value carries none of it, so the level is
     * merged into the recomputed packed light as well. Blocks that do not use neighbour brightness
     * keep the caller's value.</p>
     */
    public static int packedLight(World world, int x, int y, int z, int vanillaLight) {
        final BlockPos pos = new BlockPos(x, y, z);
        final IBlockState state = world.getBlockState(pos);
        final Block block = state.getBlock();
        if (!block.getUseNeighborBrightness(state) || block.getLightOpacity(state) != 0) return vanillaLight;
        final int sky = world.provider.hasSkyLight() ? world.getLightFor(EnumSkyBlock.SKY, pos) : 0;
        final int blockLight = Math.max(world.getLightFor(EnumSkyBlock.BLOCK, pos), state.getLightValue());
        return CeleritasDynamicLightsCompat.mergeDynamicLight(pos, sky << 20 | blockLight << 4);
    }
}
