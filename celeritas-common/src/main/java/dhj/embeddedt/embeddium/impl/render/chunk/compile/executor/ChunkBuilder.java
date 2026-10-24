package dhj.embeddedt.embeddium.impl.render.chunk.compile.executor;

import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildContext;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildOutput;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkSortOutput;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.tasks.ChunkBuilderTask;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.GlobalChunkBuildContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ChunkBuilder {
    static final Logger LOGGER = LogManager.getLogger("ChunkBuilder");
    /**
     * Megabytes of heap required per chunk builder thread. This is used to cap the number of worker
     * threads when the game is given a small heap.
     */
    private static final int MBS_PER_CHUNK_BUILDER = 64;

    /**
     * The number of tasks to allow in the queue per available worker thread. This value should be kept conservative
     * to avoid the threads becoming backlogged and failing to keep up with changes in chunk visibility (e.g.
     * camera movement). However, it also needs to be large enough that the thread is not spending part of the
     * frame doing nothing. 2 seems to be a decent value, and is what Sodium 0.2 used.
     * <p></p>
     * With adaptive scheduling, this is used as the floor for the in-flight target.
     */
    private static final int TASK_QUEUE_LIMIT_PER_WORKER = 2;

    /** Keeps the legacy fixed floor available as a local fallback during tuning or regression diagnosis. */
    private static final boolean ENABLE_ADAPTIVE_SCHEDULING = false;

    /** Enables target-change logging for local tuning. */
    private static final boolean DEBUG_ADAPTIVE_SCHEDULING = false;

    /**
     * Upper bound on the in-flight target per worker thread. The feedforward estimate is bounded by actual
     * throughput, so this only comes into play at very low frame rates or with unusually cheap tasks. It caps how
     * many snapshots the render thread will take in a single frame and how much memory is held by pending tasks.
     */
    private static final int MAX_TARGET_PER_WORKER = 64;

    /**
     * Multiplier applied to the break-even queue depth so that variance in task cost and frame time does not
     * starve the workers just before the next top-up.
     */
    private static final double SCHEDULING_HEADROOM = 1.5;

    /**
     * Smoothing factor for the exponential moving average of the frame time (top-up interval).
     */
    private static final double FRAME_TIME_EMA_ALPHA = 0.15;

    /**
     * Frame time assumed before any frames have been observed. A freshly initialized builder always starts out
     * with a full backlog of initial builds (world join, dimension change, render-distance change, or resource
     * reload) at a low frame rate, so this is deliberately pessimistic.
     */
    private static final long INITIAL_FRAME_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    /**
     * Longest single frame interval fed into the frame time average. Longer stalls (e.g. the freeze while the world
     * first loads, or the window being minimized) are clamped so that they do not inflate the target.
     */
    private static final long MAX_FRAME_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    /**
     * Execution time assumed for a mesh task before any have completed.
     */
    private static final double DEFAULT_MESH_TASK_NANOS = TimeUnit.MILLISECONDS.toNanos(2);

    /**
     * Execution time assumed for a sort task before any have completed.
     */
    private static final double DEFAULT_SORT_TASK_NANOS = TimeUnit.MICROSECONDS.toNanos(500);

    /**
     * Bounds on how many sort tasks are considered equivalent to one mesh task when converting the leftover mesh
     * budget into a sort budget.
     */
    private static final double MIN_SORTS_PER_MESH = 1.0, MAX_SORTS_PER_MESH = 16.0;

    /**
     * The sort-to-mesh cost ratio used when adaptive scheduling is disabled.
     */
    private static final double LEGACY_SORTS_PER_MESH = 4.0;

    /**
     * Minimum number of sort tasks that may be dispatched in a frame regardless of the leftover mesh budget, so that
     * translucency sorting is never fully starved by a sustained rebuild backlog.
     */
    private static final int MIN_SORT_BUDGET = 4;

    private final ChunkJobQueue queue = new ChunkJobQueue();

    private final List<WorkerThread> threads = new ArrayList<>();

    private final AtomicInteger busyThreadCount = new AtomicInteger();

    /**
     * The current target number of in-flight mesh tasks. Recomputed each frame by {@link #tickSchedulingBudget}
     * from the observed frame time and task cost; see that method for the derivation.
     */
    private int targetInFlight;

    /**
     * How many sort tasks the workers can complete in the time it takes them to complete one mesh task, per the
     * most recent tick.
     */
    private double sortsPerMesh;

    /**
     * Exponential moving average of the interval between consecutive scheduling ticks, i.e. the period over which
     * the queue must hold enough work to keep the workers busy.
     */
    private double frameTimeEma = INITIAL_FRAME_NANOS;

    /**
     * Timestamp of the previous scheduling tick, or 0 if none has happened yet.
     */
    private long lastTickNanos;

    private final ChunkBuildContext localContext;

    private final ManagedBlocker managedBlocker;

    public ChunkBuilder(ManagedBlocker managedBlocker, Supplier<ChunkBuildContext> contextSupplier, int requestedThreads) {
        GlobalChunkBuildContext.setMainThread();

        if (requestedThreads >= 0) {
            int count = getThreadCount(requestedThreads);

            for (int i = 0; i < count; i++) {
                ChunkBuildContext context = contextSupplier.get();
                WorkerRunnable worker = new WorkerRunnable(context);

                WorkerThread thread = new WorkerThread(worker, "Chunk Render Task Executor #" + i, context);
                thread.setPriority(Math.max(0, Thread.NORM_PRIORITY - 2));
                thread.start();

                this.threads.add(thread);
            }
        }

        LOGGER.info("Started {} worker threads", this.threads.size());

        this.localContext = contextSupplier.get();

        this.managedBlocker = managedBlocker;

        // Seed the target from the priors so the first frame's dispatch is sensible even before any frame or task
        // has been observed.
        this.recomputeTargets(DEFAULT_MESH_TASK_NANOS, DEFAULT_SORT_TASK_NANOS);
    }

    /**
     * Returns the minimum in-flight queue size needed to keep the workers supplied.
     */
    private int getSchedulingFloor() {
        return Math.max(1, this.threads.size()) * TASK_QUEUE_LIMIT_PER_WORKER;
    }

    /**
     * Advances the scheduling controller by one frame. Must be called exactly once per frame, before the per-frame
     * dispatch reads {@link #getSchedulingBudget()}.
     *
     * <p>The queue is only topped up once per frame, so it has to hold enough work to keep every worker busy until
     * the next top-up. The break-even depth is therefore</p>
     * <pre>workers * frameTime / avgTaskTime</pre>
     * <p>and the target is that depth times a headroom factor, clamped to the floor and to
     * {@link #MAX_TARGET_PER_WORKER}. Both inputs are exponential moving averages of observed values: the frame time
     * is the interval between ticks, and the task time comes from the worker-side execution times in the metrics
     * tracker. The estimate is recomputed from scratch each frame and carries no other state, so it tracks the frame
     * rate down as well as up and cannot get stuck at a stale value.</p>
     *
     * <p>Because the target is derived from observed rates rather than from a frame-time goal, it needs no
     * machine-specific tuning: the only constants are a dimensionless headroom ratio and a safety cap.</p>
     */
    public void tickSchedulingBudget(ChunkJobMetricsTracker metrics) {
        long now = System.nanoTime();

        if (this.lastTickNanos != 0) {
            long frameNanos = Math.min(Math.max(0, now - this.lastTickNanos), MAX_FRAME_NANOS);
            this.frameTimeEma += FRAME_TIME_EMA_ALPHA * (frameNanos - this.frameTimeEma);
        }

        this.lastTickNanos = now;

        if (!ENABLE_ADAPTIVE_SCHEDULING) {
            // Legacy behavior: the target stays pinned at the floor, so getSchedulingBudget() yields the fixed
            // per-worker budget.
            return;
        }

        int previousTarget = this.targetInFlight;

        this.recomputeTargets(
                metrics.getAverageExecutionNanos(ChunkBuildOutput.class, DEFAULT_MESH_TASK_NANOS),
                metrics.getAverageExecutionNanos(ChunkSortOutput.class, DEFAULT_SORT_TASK_NANOS));

        if (DEBUG_ADAPTIVE_SCHEDULING && this.targetInFlight != previousTarget) {
            LOGGER.info("Scheduling target {} -> {} (frame={}us, queued={}, sortsPerMesh={})",
                    previousTarget, this.targetInFlight, (long) (this.frameTimeEma / 1000), this.queue.size(),
                    String.format("%.1f", this.sortsPerMesh));
        }
    }

    private void recomputeTargets(double meshTaskNanos, double sortTaskNanos) {
        int floor = this.getSchedulingFloor();

        if (!ENABLE_ADAPTIVE_SCHEDULING || this.threads.isEmpty()) {
            // When threading or adaptive scheduling are disabled, the target should always be the smallest queue size.
            this.targetInFlight = floor;
            this.sortsPerMesh = LEGACY_SORTS_PER_MESH;
            return;
        }

        // Guard against a degenerate average (a task that measured as instantaneous) blowing the target up to the cap.
        meshTaskNanos = Math.max(1.0, meshTaskNanos);
        sortTaskNanos = Math.max(1.0, sortTaskNanos);

        double perWorker = this.frameTimeEma / meshTaskNanos * SCHEDULING_HEADROOM;
        long target = (long) Math.ceil(perWorker * this.threads.size());
        long cap = (long) MAX_TARGET_PER_WORKER * this.threads.size();

        this.targetInFlight = (int) Math.max(floor, Math.min(target, cap));
        this.sortsPerMesh = Math.max(MIN_SORTS_PER_MESH, Math.min(meshTaskNanos / sortTaskNanos, MAX_SORTS_PER_MESH));
    }

    /**
     * Returns the current desired number of in-flight tasks.
     */
    public int getTargetQueueSize() {
        return this.targetInFlight;
    }

    /**
     * Returns the most recent sort-to-mesh cost ratio the scheduling controller computed, for perf reporting.
     */
    public double getSortsPerMesh() {
        return this.sortsPerMesh;
    }

    /**
     * Returns the exponential moving average of the frame time (scheduling tick interval) in nanoseconds, for
     * perf reporting.
     */
    public double getFrameTimeEmaNanos() {
        return this.frameTimeEma;
    }

    /**
     * Returns the remaining number of tasks allowed before reaching the current in-flight target.
     */
    public int getSchedulingBudget() {
        return Math.max(0, this.targetInFlight - this.queue.size());
    }

    /**
     * Returns the number of sort tasks which should be scheduled this frame. Sorts are dispatched after mesh tasks
     * and fill whatever worker time the mesh dispatch left over, so the remaining mesh budget is converted into an
     * equivalent number of sorts using the measured cost ratio of the two task types. A small minimum ensures sorts
     * are never fully starved by a sustained rebuild backlog.
     */
    public int getSortSchedulingBudget() {
        long budget = (long) Math.ceil(this.getSchedulingBudget() * this.sortsPerMesh);
        return (int) Math.max(MIN_SORT_BUDGET, Math.min(Integer.MAX_VALUE, budget));
    }

    /**
     * <p>Notifies all worker threads to stop and blocks until all workers terminate. After the workers have been shut
     * down, all tasks are cancelled and the pending queues are cleared. If the builder is already stopped, this
     * method does nothing and exits.</p>
     *
     * <p>After shutdown, all previously scheduled jobs will have been cancelled. Jobs that finished while
     * waiting for worker threads to shut down will still have their results processed for later cleanup.</p>
     */
    public void shutdown() {
        if (!this.queue.isRunning()) {
            throw new IllegalStateException("Worker threads are not running");
        }

        // Delete any queued tasks and resources attached to them
        var jobs = this.queue.shutdown();

        for (var job : jobs) {
            job.setCancelled();
        }

        this.shutdownThreads();
    }

    private void shutdownThreads() {
        LOGGER.info("Stopping worker threads");

        // Wait for every remaining thread to terminate, then free the off-heap buffers the
        // contexts kept across tasks. Threads are dead by this point, so no build can touch them.
        for (WorkerThread thread : this.threads) {
            this.managedBlocker.managedBlock(() -> !thread.isAlive());
            thread.embeddium$getGlobalContext().destroy();
        }

        this.threads.clear();

        // The main-thread context runs builds when no worker threads exist (and while stealing tasks).
        this.localContext.destroy();
    }

    public <TASK extends ChunkBuilderTask<OUTPUT>, OUTPUT> ChunkJobTyped<TASK, OUTPUT> scheduleTask(TASK task, boolean important, long priority,
                                                                                                    Consumer<@Nullable ChunkJobResult<OUTPUT>> consumer)
    {
        Objects.requireNonNull(task, "Task must be non-null");

        if (!this.queue.isRunning()) {
            throw new IllegalStateException("Executor is stopped");
        }

        var job = new ChunkJobTyped<>(task, consumer);

        this.queue.add(job, important ? ChunkJobQueue.IMPORTANT_PRIORITY : priority);

        return job;
    }

    /**
     * Returns the "optimal" number of threads to be used for chunk build tasks. This will always return at least one
     * thread.
     */
    private static int getOptimalThreadCount() {
        int desiredThreads = Math.max(getMaxThreadCount() / 3, getMaxThreadCount() - 6);
        if (desiredThreads < 1) {
            return 1;
        } else if (desiredThreads > 10) {
            return 10;
        } else {
            return desiredThreads;
        }
    }

    private static int getThreadCount(int requested) {
        return requested == 0 ? getOptimalThreadCount() : Math.min(requested, getMaxThreadCount());
    }

    public static int getMaxThreadCount() {
        int totalCores = Runtime.getRuntime().availableProcessors();
        long memoryMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        // always allow at least one builder regardless of heap size
        int maxBuilders = Math.max(1, (int)(memoryMb / MBS_PER_CHUNK_BUILDER));
        return Math.min(totalCores, maxBuilders);
    }

    public void tryStealTask(ChunkJob job) {
        if (!this.queue.stealJob(job)) {
            return;
        }

        executeJobWithLocalContext(job);
    }

    private void executeJobWithLocalContext(ChunkJob job) {
        var localContext = this.localContext;
        GlobalChunkBuildContext.bindMainThread(localContext);

        try {
            job.execute(localContext);
        } finally {
            GlobalChunkBuildContext.bindMainThread(null);
            localContext.cleanup();
        }
    }

    public void tick() {
        // Don't need to run jobs on the main thread if there are worker threads
        if (!this.threads.isEmpty()) {
            return;
        }

        while (!this.queue.isEmpty()) {
            var job = Objects.requireNonNull(this.queue.pollJob());
            executeJobWithLocalContext(job);
        }
    }

    public boolean isBuildQueueEmpty() {
        return this.queue.isEmpty();
    }

    public int getScheduledJobCount() {
        return this.queue.size();
    }

    public int getBusyThreadCount() {
        return this.busyThreadCount.get();
    }

    public int getTotalThreadCount() {
        return this.threads.size();
    }

    public void managedBlock(BooleanSupplier isDone) {
        this.managedBlocker.managedBlock(isDone);
    }

    public static final class WorkerThread extends Thread implements GlobalChunkBuildContext.Holder {
        private final ChunkBuildContext context;

        public WorkerThread(Runnable runnable, String name, ChunkBuildContext context) {
            super(runnable, name);
            this.context = context;
        }

        @Override
        public ChunkBuildContext embeddium$getGlobalContext() {
            return context;
        }
    }

    private class WorkerRunnable implements Runnable {
        // Making this thread-local provides a small boost to performance by avoiding the overhead in synchronizing
        // caches between different CPU cores
        private final ChunkBuildContext context;

        public WorkerRunnable(ChunkBuildContext context) {
            this.context = context;
        }

        @Override
        public void run() {
            while (ChunkBuilder.this.queue.isRunning()) {
                ChunkJob job;

                try {
                    job = ChunkBuilder.this.queue.waitForNextJob();
                } catch (InterruptedException ignored) {
                    continue;
                }

                if (job == null) {
                    // might mean we are not running anymore... go around and check isRunning
                    continue;
                }

                ChunkBuilder.this.busyThreadCount.getAndIncrement();

                try {
                    job.execute(this.context);
                } finally {
                    this.context.cleanup();

                    ChunkBuilder.this.busyThreadCount.decrementAndGet();
                }
            }
        }
    }

    public interface ManagedBlocker {
        ManagedBlocker NONE = isDone -> {
            while (!isDone.getAsBoolean()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
            }
        };

        void managedBlock(BooleanSupplier isDone);
    }
}
