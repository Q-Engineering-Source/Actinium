package dhj.embeddedt.embeddium.impl.render.chunk.compile.executor;

import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildContext;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.tasks.ChunkBuilderTask;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

public class ChunkJobTyped<TASK extends ChunkBuilderTask<OUTPUT>, OUTPUT>
        implements ChunkJob
{
    private final TASK task;
    private final Consumer<@Nullable ChunkJobResult<OUTPUT>> consumer;

    private volatile boolean cancelled;
    private volatile boolean started;

    // Ordering keys assigned by ChunkJobQueue when the job is enqueued. Only accessed under the queue's lock.
    long priority;
    long sequence;

    ChunkJobTyped(TASK task, Consumer<@Nullable ChunkJobResult<OUTPUT>> consumer) {
        this.task = task;
        this.consumer = consumer;
    }

    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public void setCancelled() {
        this.cancelled = true;
    }

    @Override
    public void execute(ChunkBuildContext context) {
        ChunkJobResult<OUTPUT> result = null;

        if (!this.cancelled) {
            this.started = true;

            long startTime = System.nanoTime();

            try {
                var output = this.task.execute(context, this);

                // A null output means the task was cancelled while executing
                if (output != null) {
                    result = new ChunkJobResult.Success<>(output, System.nanoTime() - startTime);
                }
            } catch (Throwable throwable) {
                result = new ChunkJobResult.Failure<>(throwable);
                ChunkBuilder.LOGGER.error("Chunk build failed", throwable);
            }
        }

        try {
            if (!this.started) {
                // Cancelled before the executor could start it: the task never reached execute, so it
                // still holds whatever it acquired when it was created and has to give that back.
                this.task.releaseUnstartedContext();
            }

            this.consumer.accept(result);
        } catch (Throwable throwable) {
            throw new RuntimeException("Exception while consuming result", throwable);
        }
    }

    @Override
    public boolean isStarted() {
        return this.started;
    }
}
