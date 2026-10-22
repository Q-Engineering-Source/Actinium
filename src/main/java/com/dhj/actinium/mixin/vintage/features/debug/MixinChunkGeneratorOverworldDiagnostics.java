package com.dhj.actinium.mixin.vintage.features.debug;

import com.dhj.actinium.debug.WorldgenInputDiagnostics;
import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkGeneratorOverworld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Thin RETURN hooks for sampled Overworld chunk input diagnostics. */
@Mixin(ChunkGeneratorOverworld.class)
public abstract class MixinChunkGeneratorOverworldDiagnostics {
    @Shadow
    @Final
    private World world;

    @Inject(method = "generateChunk(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("RETURN"))
    private void actinium$afterGenerateChunk(int chunkX, int chunkZ,
                                             CallbackInfoReturnable<Chunk> cir) {
        if (!GLSMPerfDebug.isEnabled()) {
            return;
        }

        WorldgenInputDiagnostics.onGenerateReturned(this.world, cir.getReturnValue(), chunkX, chunkZ);
    }

    @Inject(method = "populate(II)V", at = @At("RETURN"))
    private void actinium$afterPopulate(int chunkX, int chunkZ, CallbackInfo ci) {
        if (!GLSMPerfDebug.isEnabled()) {
            return;
        }

        WorldgenInputDiagnostics.onPopulateReturned(this.world, chunkX, chunkZ);
    }
}
