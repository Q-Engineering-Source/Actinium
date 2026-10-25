package com.dhj.actinium.render.terrain.compile.light;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import dhj.embeddedt.embeddium.impl.model.light.data.LightDataAccess;
import com.dhj.actinium.world.EmptyBlockAccess;
import net.coderbot.iris.block_rendering.BlockRenderingSettings;
import net.minecraft.block.Block;
import dhj.embeddedt.embeddium.impl.model.light.debug.AODebug;

public class LightDataCache extends LightDataAccess {
    private final IBlockAccess world;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    public LightDataCache(IBlockAccess world) {
        this.world = world;
    }

    protected int compute(int x, int y, int z) {
        BlockPos pos = this.pos.setPos(x, y, z);
        IBlockAccess world = this.world;

        var state = world.getBlockState(pos);

        boolean em;

        try {
            em = state.getPackedLightmapCoords(EmptyBlockAccess.INSTANCE, BlockPos.ORIGIN) == 15728880;
        } catch (Exception e) {
            em = false;
        }

        boolean op = state.getMaterial().isOpaque() && state.getLightOpacity(world, pos) > 0;
        boolean fo = state.isOpaqueCube();
        boolean fc = state.isBlockNormalCube();

        int lu = state.getLightValue(world, pos);

        int bl;
        int sl;
        if (fo && lu == 0) {
            bl = 0;
            sl = 0;
        } else {
            // call the vanilla method so mods using custom lightmap logic work correctly
            int packedCoords = state.getPackedLightmapCoords(world, pos);
            bl = LightDataAccess.unpackBlock(packedCoords);
            sl = LightDataAccess.unpackSky(packedCoords);
        }

        float vanillaAo = lu == 0 ? state.getAmbientOcclusionLightValue() : 1.0f;
        float aoLevel = BlockRenderingSettings.INSTANCE.getAmbientOcclusionLevel();
        float ao = lu == 0 ? applyAmbientOcclusionLevel(vanillaAo, aoLevel) : 1.0f;

        AODebug.logLightData(
            "cache",
            Block.getIdFromBlock(state.getBlock()),
            x,
            y,
            z,
            vanillaAo,
            aoLevel,
            ao,
            packFC(fc) | packFO(fo) | packOP(op) | packEM(em) | packAO(ao) | packLU(lu) | packSL(sl) | packBL(bl),
            lu,
            op,
            fc,
            fo
        );

        return packFC(fc) | packFO(fo) | packOP(op) | packEM(em) | packAO(ao) | packLU(lu) | packSL(sl) | packBL(bl);
    }

    static float applyAmbientOcclusionLevel(float vanillaAo, float aoLevel) {
        return 1.0f - aoLevel * (1.0f - vanillaAo);
    }
}

