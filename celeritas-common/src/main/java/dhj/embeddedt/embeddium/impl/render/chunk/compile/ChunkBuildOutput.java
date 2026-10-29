package dhj.embeddedt.embeddium.impl.render.chunk.compile;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderSection;
import dhj.embeddedt.embeddium.impl.render.chunk.data.BuiltRenderSectionData;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.chunk.data.BuiltSectionMeshParts;

import java.util.Map;

/**
 * The result of a chunk rebuild task which contains any and all data that needs to be processed or uploaded on
 * the main thread. If a task is cancelled after finishing its work and not before the result is processed, the result
 * will instead be discarded.
 */
public class ChunkBuildOutput extends ChunkTaskOutput {
    public final BuiltRenderSectionData info;
    public final Reference2ReferenceMap<TerrainRenderPass, BuiltSectionMeshParts> meshes;
    /** Scalar correlation token for sampled input/output diagnostics; zero means no diagnostic sample was reserved. */
    public final long meshInputTraceId;

    public ChunkBuildOutput(RenderSection render, BuiltRenderSectionData info, Reference2ReferenceMap<TerrainRenderPass, BuiltSectionMeshParts> meshes, int buildTime) {
        this(render, info, meshes, buildTime, 0L);
    }

    /** Adds a scalar-only correlation token without introducing a common-to-Actinium dependency. */
    public ChunkBuildOutput(RenderSection render, BuiltRenderSectionData info, Reference2ReferenceMap<TerrainRenderPass, BuiltSectionMeshParts> meshes, int buildTime, long meshInputTraceId) {
        super(render, buildTime);
        this.info = info;
        this.meshes = meshes;
        if (meshInputTraceId < 0L) {
            throw new IllegalArgumentException("Negative mesh input trace ID");
        }
        this.meshInputTraceId = meshInputTraceId;

        if (this.info != null) {
            this.info.bake();
        }
    }

    @Override
    public void delete() {
        for (BuiltSectionMeshParts data : this.meshes.values()) {
            data.free();
        }
    }

    @Override
    public long getNativeMemoryBytes() {
        return calculateNativeMemoryBytes(this.meshes);
    }

    /** Calculates the exact per-pass vertex plus optional index allocations owned by one build output. */
    public static long calculateNativeMemoryBytes(Map<TerrainRenderPass, BuiltSectionMeshParts> meshes) {
        long bytes = 0;
        for (BuiltSectionMeshParts data : meshes.values()) {
            bytes += data.vertexBuffer().getLength();
            if (data.indexBuffer() != null) {
                bytes += data.indexBuffer().getLength();
            }
        }
        return bytes;
    }

    /** Rechecks the GUI-controlled gate at the final accepted-for-upload boundary. */
    public boolean shouldLogAcceptedForUpload(boolean perfDebugEnabled) {
        return perfDebugEnabled && this.meshInputTraceId != 0L;
    }
}
