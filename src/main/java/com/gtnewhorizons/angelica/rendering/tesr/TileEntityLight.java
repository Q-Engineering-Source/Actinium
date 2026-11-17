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
     * <p>Celeritas Dynamic Lights merges its level into the combined light of the tile entity
     * dispatcher. This method either returns the caller's value for a block that does not use
     * neighbour brightness or replaces it with the sky and block light sampled from the world, so
     * the level is merged into the value it returns on both paths. Blocks that do not use neighbour
     * brightness keep the caller's light apart from that merge.</p>
     */
    public static int packedLight(World world, int x, int y, int z, int vanillaLight) {
        final BlockPos pos = new BlockPos(x, y, z);
        final IBlockState state = world.getBlockState(pos);
        final Block block = state.getBlock();
        if (!block.getUseNeighborBrightness(state) || block.getLightOpacity(state) != 0) {
            return CeleritasDynamicLightsCompat.mergeDynamicLight(pos, vanillaLight);
        }
        final int sky = world.provider.hasSkyLight() ? world.getLightFor(EnumSkyBlock.SKY, pos) : 0;
        final int blockLight = Math.max(world.getLightFor(EnumSkyBlock.BLOCK, pos), state.getLightValue());
        return CeleritasDynamicLightsCompat.mergeDynamicLight(pos, sky << 20 | blockLight << 4);
    }
}
