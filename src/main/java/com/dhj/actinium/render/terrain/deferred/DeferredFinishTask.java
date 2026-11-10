package com.dhj.actinium.render.terrain.deferred;

import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildContext;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildOutput;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.executor.ChunkJobCollector;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.executor.ChunkJobResult;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.tasks.ChunkBuilderTask;
import dhj.embeddedt.embeddium.impl.util.task.CancellationToken;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * The build-thread half of a deferred mesh: encodes a capture produced on the render thread and
 * reports the mesh back to the scheduler.
 *
 * <p>The task is also the job's result consumer, so the mesh leaves the scheduler on the thread
 * that ran the finish, which is where the pooled buffers, slice and capture are released.</p>
 */
public final class DeferredFinishTask extends ChunkBuilderTask<ChunkBuildOutput>
        implements Consumer<ChunkJobResult<ChunkBuildOutput>> {
    private final DeferredSectionMesh mesh;
    private final DeferredMeshScheduler scheduler;
    @Nullable
    private final ChunkJobCollector collector;

    /**
     * @param mesh the mesh captured on the render thread
     * @param scheduler the scheduler that owns the mesh resources
     * @param collector collector the finish job belongs to, or {@code null} for a task submitted by
     *                  the distance queue
     */
    public DeferredFinishTask(DeferredSectionMesh mesh, DeferredMeshScheduler scheduler,
                              @Nullable ChunkJobCollector collector) {
        this.mesh = mesh;
        this.scheduler = scheduler;
        this.collector = collector;
    }

    @Override
    public ChunkBuildOutput execute(ChunkBuildContext context, CancellationToken cancellationToken) {
        if (this.mesh.isObsolete()) {
            return null;
        }

        return this.mesh.finish(context);
    }

    @Override
    public void accept(@Nullable ChunkJobResult<ChunkBuildOutput> result) {
        this.scheduler.finished(this.mesh, result, this.collector);
    }
}
