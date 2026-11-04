package com.dhj.actinium.mixin.vintage.core.terrain;

import com.dhj.actinium.render.terrain.TerrainDrawStats;
import dhj.embeddedt.embeddium.impl.gl.device.CommandList;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildOutput;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkTaskOutput;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.executor.ChunkJobResult;
import dhj.embeddedt.embeddium.impl.render.chunk.data.BuiltSectionMeshParts;
import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

@Mixin(value = RenderRegionManager.class, remap = false)
public abstract class MixinRenderRegionManager {

    @Inject(method = "uploadMeshes", at = @At("HEAD"))
    private void actinium$onUploadMeshes(CommandList commandList, Collection<ChunkJobResult.Success<? extends ChunkTaskOutput>> results, Runnable graphUpdateTrigger, CallbackInfo ci) {
        for (var holder : results) {
            if (!(holder.output() instanceof ChunkBuildOutput buildOutput)) continue;
            for (BuiltSectionMeshParts mesh : buildOutput.meshes.values()) {
                long bytes = mesh.vertexBuffer().getLength();
                if (mesh.indexBuffer() != null) bytes += mesh.indexBuffer().getLength();
                TerrainDrawStats.recordSectionUpload(bytes);
            }
        }
    }
}
