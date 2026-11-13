package com.dhj.actinium.mixin.vintage.core.terrain;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import dhj.embeddedt.embeddium.impl.render.chunk.map.ChunkStatus;
import dhj.embeddedt.embeddium.impl.render.chunk.map.ChunkTrackerHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkProviderClient.class)
public abstract class MixinClientChunkManager {

    private AccessorChunkProviderClient actinium$self() {
        return (AccessorChunkProviderClient) this;
    }

    @Inject(method = "loadChunk", at = @At("RETURN"))
    private void afterLoadChunkFromPacket(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        ChunkTrackerHolder.get(actinium$self().celeritas$getWorld()).onChunkStatusAdded(x, z, ChunkStatus.FLAG_ALL);
    }

    @Inject(method = "unloadChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/chunk/Chunk;onUnload()V", shift = At.Shift.AFTER))
    private void afterUnloadChunk(int x, int z, CallbackInfo ci) {
        ChunkTrackerHolder.get(actinium$self().celeritas$getWorld()).onChunkStatusRemoved(x, z, ChunkStatus.FLAG_ALL);
    }

    /**
     * Repairs any drift between the chunk tracker and the authoritative loaded set once per
     * client tick; in steady state the diff is empty and no events fire.
     */
    @Inject(method = "tick", at = @At("RETURN"))
    private void reconcileTrackerWithLoadedChunks(CallbackInfoReturnable<Boolean> cir) {
        ChunkTrackerHolder.get(actinium$self().celeritas$getWorld()).reconcile(actinium$self().celeritas$getLoadedChunks().keySet());
    }
}
