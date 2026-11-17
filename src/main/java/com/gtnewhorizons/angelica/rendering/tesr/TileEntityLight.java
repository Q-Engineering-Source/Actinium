package com.gtnewhorizons.angelica.rendering.tesr;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;

/** Try to replicate modern behavior for when shaderpacks are in use.*/
public final class TileEntityLight {

    private TileEntityLight() {}

    public static int packedLight(World world, int x, int y, int z, int vanillaLight) {
        final BlockPos pos = new BlockPos(x, y, z);
        final IBlockState state = world.getBlockState(pos);
        final Block block = state.getBlock();
        if (!block.getUseNeighborBrightness(state) || block.getLightOpacity(state) != 0) return vanillaLight;
        final int sky = world.provider.hasSkyLight() ? world.getLightFor(EnumSkyBlock.SKY, pos) : 0;
        final int blockLight = Math.max(world.getLightFor(EnumSkyBlock.BLOCK, pos), state.getLightValue());
        return sky << 20 | blockLight << 4;
    }
}
