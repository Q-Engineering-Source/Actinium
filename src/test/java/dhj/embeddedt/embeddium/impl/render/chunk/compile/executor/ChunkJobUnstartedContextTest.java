package dhj.embeddedt.embeddium.impl.render.chunk.compile.executor;

import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildContext;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.tasks.ChunkBuilderTask;
import dhj.embeddedt.embeddium.impl.util.task.CancellationToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the cancellation contract of a queued build job. A build task acquires its state on the
 * render thread, before it is submitted: for a chunk build that is the section snapshot the task
 * copies into its slice. A job that the executor cancels before it can start never reaches
 * {@code execute}, so the job has to give that state back exactly once; a job that started owns its
 * state through {@code execute} and must not be released by the executor as well.
 *
 * <p>The builder runs without worker threads, so {@link ChunkBuilder#tick()} executes the queued
 * job on the calling thread and the test can observe whether the release happened before the result
 * was delivered.</p>
 */
class ChunkJobUnstartedContextTest {
    private ChunkBuilder builder;
    private boolean builderStopped;

    @BeforeEach
    void createBuilder() {
        this.builder = new ChunkBuilder(ChunkBuilder.ManagedBlocker.NONE, () -> new ChunkBuildContext(null), -1);
    }

    @AfterEach
    void shutdownBuilder() {
        if (this.builderStopped) {
            return;
        }

        this.builderStopped = true;
        this.builder.shutdown();
    }

    @Test
    void aCancelledJobThatNeverStartedReleasesItsContextBeforeDelivering() {
        RecordingTask task = new RecordingTask();
        List<String> consumerObservations = new ArrayList<>();
        List<ChunkJobResult<String>> results = new ArrayList<>();

        ChunkJobTyped<RecordingTask, String> job = this.builder.scheduleTask(task, false, 0L, result -> {
            consumerObservations.add("releases=" + task.releases.get());
            results.add(result);
        });
        job.setCancelled();

        this.builder.tick();

        assertEquals(1, task.releases.get(),
                "the context of a job that never started must be released exactly once");
        assertEquals(0, task.executions.get());
        assertEquals(List.of("releases=1"), consumerObservations,
                "the release must happen before the cancelled result is delivered");
        assertEquals(1, results.size());
        assertNull(results.get(0), "a cancelled job reports no output");
    }

    @Test
    void aJobThatStartedDoesNotReleaseTheContextItExecuted() {
        RecordingTask task = new RecordingTask();
        List<ChunkJobResult<String>> results = new ArrayList<>();

        this.builder.scheduleTask(task, false, 0L, results::add);
        this.builder.tick();

        assertEquals(1, task.executions.get());
        assertEquals(0, task.releases.get(), "a started job owns its context until execute returns");

        ChunkJobResult.Success<String> success = assertInstanceOf(ChunkJobResult.Success.class, results.get(0));
        assertEquals("mesh", success.output());
    }

    @Test
    void aJobCancelledWhileExecutingKeepsItsContextWithTheTask() {
        RecordingTask task = new RecordingTask();
        task.cancelDuringExecute = true;
        List<ChunkJobResult<String>> results = new ArrayList<>();

        this.builder.scheduleTask(task, false, 0L, results::add);
        this.builder.tick();

        assertEquals(1, task.executions.get());
        assertEquals(0, task.releases.get(),
                "a task that observed the cancellation cleans up its own context");
        assertNull(results.get(0));
    }

    @Test
    void aQueuedJobDeliversItsCancelledResultWhenTheBuilderShutsDown() {
        RecordingTask task = new RecordingTask();
        List<ChunkJobResult<String>> results = new ArrayList<>();

        this.builder.scheduleTask(task, false, 0L, results::add);

        this.builderStopped = true;
        this.builder.shutdown();

        assertEquals(0, task.executions.get(), "a queued job must not start its task during shutdown");
        assertEquals(1, task.releases.get(), "the snapshot of a queued job must be released exactly once");
        assertEquals(1, results.size(), "the consumer must receive the cancelled result");
        assertNull(results.get(0), "a cancelled job reports no output");
    }

    private static final class RecordingTask extends ChunkBuilderTask<String> {
        private final AtomicInteger executions = new AtomicInteger();
        private final AtomicInteger releases = new AtomicInteger();

        private boolean cancelDuringExecute;

        @Override
        public String execute(ChunkBuildContext context, CancellationToken cancellationToken) {
            this.executions.incrementAndGet();

            if (this.cancelDuringExecute) {
                cancellationToken.setCancelled();
                return null;
            }

            return "mesh";
        }

        @Override
        public void releaseUnstartedContext() {
            this.releases.incrementAndGet();
        }
    }
}
