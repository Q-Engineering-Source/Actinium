package com.dhj.actinium.mixin.vintage.core.terrain;

import com.dhj.actinium.runtime.ActiniumRuntime;
import com.dhj.actinium.world.biome.SkyColorCache;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(World.class)
public abstract class MixinWorldSkyColor {
    @Unique
    private SkyColorCache actinium$skyColorCache;

    @WrapOperation(
            method = "getSkyColorBody",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraftforge/client/ForgeHooksClient;getSkyBlendColour(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;)I",
                    remap = false),
            remap = false)
    private int actinium$blendSkyColor(World world, BlockPos pos, Operation<Integer> original,
                                      @Local(argsOnly = true) Entity camera, @Local(argsOnly = true) float partialTicks) {
        int radius = ActiniumRuntime.options().quality.legacyBiomeBlendRadius;
        if (radius == 0) {
            return original.call(world, pos);
        }
        if (this.actinium$skyColorCache == null) {
            this.actinium$skyColorCache = new SkyColorCache(world);
        }
        return this.actinium$skyColorCache.getColor(world.getTotalWorldTime(), camera, partialTicks, radius);
    }
}
