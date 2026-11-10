package com.dhj.actinium.render.terrain.deferred;

import com.dhj.actinium.render.terrain.deferred.DeferredSectionMesh.DeferredCapture;
import com.dhj.actinium.world.cloned.DeferredSlice;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildBuffers;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildOutput;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkTaskOutput;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.executor.ChunkBuilder;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.executor.ChunkJobCollector;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.executor.ChunkJobResult;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.executor.ChunkJobTyped;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns the deferred half of the chunk build pipeline: the pool of build buffers, section snapshots
 * and vertex captures that travel between the render thread and the build threads, and the two
 * queues of meshes waiting for their render-thread capture.
 *
 * <p>Lifecycle of one mesh: {@link #submit(DeferredSectionMesh)} counts it as pending, the render
 * thread captures it ({@link #runImportant()} for important sections, the shared {@link #runNext()}
 * task for the rest), and the finish job returns it here, where it is released and delivered as a
 * build result. {@link #inFlight} is the single set that decides who releases a mesh, so a mesh is
 * released exactly once even when a shutdown races a finishing job.</p>
 *
 * <p>Threading: the render thread calls every method except {@link #submit} and {@link #finished},
 * which build threads call.</p>
 */
public final class DeferredMeshScheduler {
    private static final Logger LOGGER = LogManager.getLogger("Actinium/DeferredMeshScheduler");

    /**
     * Priority of a finish job. Finishes hold pooled buffers and a section snapshot, so they always
     * run before the distance-prioritised rebuilds and sorts, and after the important ones.
     */
    private static final long FINISH_PRIORITY = -1L;

    private static final Comparator<DeferredSectionMesh> NEAREST_FIRST =
            Comparator.comparingLong(mesh -> mesh.priority);
    private static final int CAPTURES_PER_WORKER = 2;
    private static final int MIN_FREE_PER_WORKER = 2;

    private final ChunkBuilder builder;
    private final Consumer<Runnable> mainThreadQueue;
    private final Consumer<Runnable> mainThreadUnqueue;
    private final Queue<ChunkJobResult<? extends ChunkTaskOutput>> results;
    private final Consumer<ChunkJobResult<? extends ChunkTaskOutput>> resultSink;
    private final ObjectPool<ChunkBuildBuffers> buffers;
    private final ObjectPool<DeferredSlice> slices;
    private final ObjectPool<DeferredCapture> captures;
    private final ConcurrentLinkedQueue<DeferredSectionMesh> important = new ConcurrentLinkedQueue<>();
    private final PriorityBlockingQueue<DeferredSectionMesh> normal = new PriorityBlockingQueue<>(11, NEAREST_FIRST);
    private final Set<DeferredSectionMesh> inFlight = ConcurrentHashMap.newKeySet();
    private final Runnable runNext = this::runNext;
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicBoolean normalCaptureQueued = new AtomicBoolean();
    private final int minFree;

    private volatile boolean destroyed;

    /**
     * @param config render pass configuration of the pooled build buffers
     * @param builder executor the finish jobs are scheduled on
     * @param sliceFactory creates an empty snapshot when the pool has none
     * @param mainThreadQueue hands a task to the render thread
     * @param mainThreadUnqueue removes every queued occurrence of a task by reference from the render thread queue
     * @param results queue the finished build results are delivered to
     */
    public DeferredMeshScheduler(RenderPassConfiguration<?> config, ChunkBuilder builder,
                                 Supplier<? extends DeferredSlice> sliceFactory,
                                 Consumer<Runnable> mainThreadQueue, Consumer<Runnable> mainThreadUnqueue,
                                 Queue<ChunkJobResult<? extends ChunkTaskOutput>> results) {
        this.builder = builder;
        this.mainThreadQueue = mainThreadQueue;
        this.mainThreadUnqueue = mainThreadUnqueue;
        this.results = results;
        this.resultSink = results::add;

        int workers = Math.max(1, builder.getTotalThreadCount());
        this.minFree = workers * MIN_FREE_PER_WORKER;
        this.buffers = new ObjectPool<>(() -> new ChunkBuildBuffers(config), ChunkBuildBuffers::destroy, Integer.MAX_VALUE);
        this.slices = new ObjectPool<>(sliceFactory::get, DeferredSlice::releaseContext, Integer.MAX_VALUE);
        this.captures = new ObjectPool<>(DeferredCapture::new, DeferredCapture::dispose, workers * CAPTURES_PER_WORKER);
    }

    /** {@return pooled buffers for a section that is being captured on the render thread} */
    public ChunkBuildBuffers acquireBuffers() {
        return this.buffers.acquire();
    }

    /** {@return pooled snapshot for a section that is being captured on the render thread} */
    public DeferredSlice acquireSlice() {
        return this.slices.acquire();
    }

    /**
     * Returns buffers taken by {@link #acquireBuffers()} when the build failed before it could hand
     * a mesh over. Buffers that travel with a mesh are released by {@link #release(DeferredSectionMesh)}
     * instead, so this is only for the abort path of a build.
     */
    public void releaseBuffers(ChunkBuildBuffers buffers) {
        buffers.resetForTask();
        this.buffers.release(buffers);
    }

    /** Queues a mesh for its render-thread capture and counts it as pending. */
    public void submit(DeferredSectionMesh mesh) {
        this.pending.incrementAndGet();

        if (mesh.important) {
            this.important.add(mesh);
        } else {
            this.normal.add(mesh);
            scheduleNextNormalCapture();
        }
    }

    /**
     * Captures every important mesh and waits for their finishes. Called on the render thread once
     * per frame; an empty queue returns without waiting.
     */
    public void runImportant() {
        if (this.important.isEmpty()) {
            return;
        }

        ChunkJobCollector collector = new ChunkJobCollector(Integer.MAX_VALUE, this.resultSink);
        DeferredSectionMesh mesh;

        while ((mesh = this.important.poll()) != null) {
            run(mesh, collector);
        }

        collector.awaitCompletion(this.builder);
    }

    /** Stops the capture of new meshes; the meshes already queued are released by {@link #releaseOrphans()}. */
    public void markDestroyed() {
        this.destroyed = true;
    }

    /**
     * Releases every mesh the scheduler still holds and frees the pools.
     *
     * <p>Call this once the build executor is shut down: its queued finish jobs were dropped without
     * reaching their consumers, so nothing but this method releases the meshes they carried. The
     * shared render-thread task is removed first so a stale task cannot capture into a pool that is
     * being freed.</p>
     */
    public void releaseOrphans() {
        this.mainThreadUnqueue.accept(this.runNext);

        DeferredSectionMesh mesh;

        while ((mesh = this.important.poll()) != null) {
            releaseOrphan(mesh);
        }

        while ((mesh = this.normal.poll()) != null) {
            releaseOrphan(mesh);
        }

        for (DeferredSectionMesh orphan : this.inFlight) {
            if (this.inFlight.remove(orphan)) {
                releaseOrphan(orphan);
            }
        }

        this.captures.close();
        this.slices.close();
        this.buffers.close();
    }

    /** {@return meshes submitted but not yet captured or released} */
    public int pendingCount() {
        return this.pending.get();
    }

    /** {@return captured meshes whose finish job has not been consumed yet} */
    public int finishingCount() {
        return this.inFlight.size();
    }

    /** {@return snapshots currently available for reuse} */
    public int freeSliceCount() {
        return this.slices.freeCount();
    }

    /** Drops pooled resources the recent usage peak cannot need again. Called once per frame. */
    public void trimPools() {
        long now = System.nanoTime();
        this.slices.trim(now, this.minFree);
        this.buffers.trim(now, this.minFree);
    }

    /**
     * Consumes the result of a finish job: merges the timing, releases the mesh and delivers the
     * result. A release failure is reported as a failure result instead of escaping, because this
     * runs as a job consumer on a build thread.
     */
    void finished(DeferredSectionMesh mesh, @Nullable ChunkJobResult<ChunkBuildOutput> result,
                  @Nullable ChunkJobCollector collector) {
        ChunkJobResult<ChunkBuildOutput> out = result;

        if (this.inFlight.remove(mesh)) {
            ChunkJobResult<ChunkBuildOutput> completed = mergeTiming(result, mesh);
            out = completed;

            try {
                release(mesh);
            } catch (Throwable releaseFailure) {
                out = new ChunkJobResult.Failure<>(releaseFailure);
                discardMesh(completed);
            }
        } else {
            // releaseOrphans already returned the resources to pools that may hand them out again,
            // so a late result must not reach the upload path.
            LOGGER.debug("Dropping the result of a deferred mesh that was released as an orphan");
            out = null;
        }

        if (collector != null) {
            collector.onJobFinished(out);
        } else if (out != null) {
            this.results.add(out);
        }
    }

    /** Frees the mesh data of a build output that a release failure replaced, so the failure path keeps no native geometry. */
    private static void discardMesh(@Nullable ChunkJobResult<ChunkBuildOutput> result) {
        if (result instanceof ChunkJobResult.Success<ChunkBuildOutput> success) {
            success.output().delete();
        }
    }

    /**
     * Advances pool trimming and important work, bounds the normal queue, then allows one normal
     * capture in a later queue pass.
     */
    public void update() {
        trimPools();
        runImportant();
        trimNormalQueue();

        if (!this.destroyed && !this.normal.isEmpty()) {
            scheduleNextNormalCapture();
        }
    }

    /**
     * Drops the normal meshes that became obsolete and bounds the queue to {@link #minFree} entries,
     * keeping the nearest ones. A trimmed mesh releases its pooled buffers and snapshot and asks its
     * handler to rebuild the section on a later frame, so a burst of hand-offs cannot grow the queue
     * and its pooled snapshots without bound.
     */
    private void trimNormalQueue() {
        if (this.normal.isEmpty()) {
            return;
        }

        List<DeferredSectionMesh> queued = new ArrayList<>(this.normal.size());
        this.normal.drainTo(queued);

        List<DeferredSectionMesh> live = new ArrayList<>(queued.size());

        for (DeferredSectionMesh mesh : queued) {
            if (mesh.isObsolete()) {
                releaseOrphan(mesh);
            } else {
                live.add(mesh);
            }
        }

        live.sort(NEAREST_FIRST);

        for (int index = 0; index < live.size(); index++) {
            DeferredSectionMesh mesh = live.get(index);

            if (index < this.minFree) {
                this.normal.add(mesh);
            } else {
                retryTrimmed(mesh);
            }
        }
    }

    /** Releases a mesh the queue had no room for and asks its handler to rebuild the section later. */
    private void retryTrimmed(DeferredSectionMesh mesh) {
        releaseOrphan(mesh);

        try {
            mesh.retryLater();
        } catch (Throwable retryFailure) {
            LOGGER.error("Failed to reschedule a trimmed deferred section", retryFailure);
        }
    }

    /**
     * Captures at most one normal mesh. Obsolete meshes at the head of the queue are released in the
     * same pass, so they cannot use up the one capture the pass allows.
     */
    private void runNext() {
        try {
            DeferredSectionMesh mesh;

            while ((mesh = this.normal.poll()) != null) {
                if (mesh.isObsolete()) {
                    releaseOrphan(mesh);
                    continue;
                }

                run(mesh, null);
                return;
            }
        } finally {
            this.normalCaptureQueued.set(false);
        }
    }

    /** Keeps one normal capture in the render queue instead of draining a whole build burst in one pass. */
    private void scheduleNextNormalCapture() {
        if (this.normalCaptureQueued.compareAndSet(false, true)) {
            this.mainThreadQueue.accept(this.runNext);
        }
    }

    /**
     * Captures, submits and, when either step fails, releases one mesh. Everything this method runs
     * happens on the render thread, so it must not let an exception escape: a capture that throws or
     * a build executor that already shut down becomes a failure result instead of aborting the frame.
     */
    private void run(DeferredSectionMesh mesh, @Nullable ChunkJobCollector collector) {
        ChunkJobResult<ChunkBuildOutput> failure;

        try {
            failure = captureAndSubmit(mesh, collector);
        } catch (Throwable t) {
            failure = new ChunkJobResult.Failure<>(t);
        }

        if (failure != null) {
            deliver(failure, collector);
        }
    }

    /**
     * Captures the mesh and hands its finish to the executor.
     *
     * @return the failure to report when the mesh was not submitted, or {@code null} once the finish
     *         job owns the mesh
     */
    @Nullable
    private ChunkJobResult.Failure<ChunkBuildOutput> captureAndSubmit(DeferredSectionMesh mesh, @Nullable ChunkJobCollector collector) {
        ChunkJobResult.Failure<ChunkBuildOutput> failure = null;
        boolean scheduled = false;

        try {
            if (!this.destroyed && !mesh.isObsolete()) {
                mesh.capture = this.captures.acquire();
                failure = captureFailure(mesh);

                if (failure == null) {
                    DeferredFinishTask task = new DeferredFinishTask(mesh, this, collector);
                    this.inFlight.add(mesh);
                    ChunkJobTyped<DeferredFinishTask, ChunkBuildOutput> job =
                            this.builder.scheduleTask(task, mesh.important, FINISH_PRIORITY, task);
                    scheduled = true;

                    if (collector != null) {
                        collector.addSubmittedJob(job);
                    }
                }
            }
        } catch (Throwable captureFailure) {
            failure = new ChunkJobResult.Failure<>(captureFailure);
        } finally {
            if (!scheduled) {
                this.inFlight.remove(mesh);

                try {
                    release(mesh);
                } catch (Throwable releaseFailure) {
                    failure = mergeFailure(failure, releaseFailure);
                }
            }
        }

        return failure;
    }

    /**
     * Runs the render-thread capture of {@code mesh}. The handler may only report a failure; a
     * successful capture result would describe a mesh that was never finished, so it is rejected
     * before it can reach the upload path of already released buffers.
     */
    @Nullable
    private static ChunkJobResult.Failure<ChunkBuildOutput> captureFailure(DeferredSectionMesh mesh) {
        ChunkJobResult<ChunkBuildOutput> result = mesh.capture();

        if (result == null) {
            return null;
        }

        if (result instanceof ChunkJobResult.Failure<ChunkBuildOutput> failure) {
            return failure;
        }

        discardMesh(result);
        throw new IllegalStateException("captureDeferred returned a successful result; the mesh was never finished");
    }

    private void deliver(ChunkJobResult<ChunkBuildOutput> failure, @Nullable ChunkJobCollector collector) {
        if (collector != null) {
            collector.onJobSkipped(failure);
        } else {
            this.results.add(failure);
        }
    }

    private void releaseOrphan(DeferredSectionMesh mesh) {
        try {
            release(mesh);
        } catch (Throwable releaseFailure) {
            LOGGER.error("Failed to release a deferred mesh while shutting the scheduler down", releaseFailure);
        }
    }

    /**
     * Returns the buffers, snapshot and capture of a mesh and counts it as no longer pending. Every
     * resource is released even when an earlier one throws, so a failing buffer reset cannot strand
     * the snapshot or the capture of the same mesh, and the pending count always drops exactly once.
     */
    private void release(DeferredSectionMesh mesh) {
        Throwable failure = null;
        DeferredCapture capture = mesh.capture;
        mesh.capture = null;

        try {
            failure = releaseBuffers(mesh.buffers, failure);
            failure = releaseSlice(mesh.slice, failure);

            if (capture != null) {
                failure = releaseCapture(capture, failure);
            }
        } finally {
            this.pending.decrementAndGet();
        }

        if (failure != null) {
            throw asReleaseFailure(failure);
        }
    }

    /** Resets and returns the build buffers, keeping an earlier release failure instead of replacing it. */
    @Nullable
    private Throwable releaseBuffers(ChunkBuildBuffers buffers, @Nullable Throwable failure) {
        try {
            buffers.resetForTask();
        } catch (Throwable t) {
            failure = merge(failure, t);
        }

        try {
            this.buffers.release(buffers);
        } catch (Throwable t) {
            failure = merge(failure, t);
        }

        return failure;
    }

    /** Gives back the snapshot and returns the slice to the pool, merging both failures. */
    @Nullable
    private Throwable releaseSlice(DeferredSlice slice, @Nullable Throwable failure) {
        try {
            slice.releaseContext();
        } catch (Throwable t) {
            failure = merge(failure, t);
        }

        try {
            this.slices.release(slice);
        } catch (Throwable t) {
            failure = merge(failure, t);
        }

        return failure;
    }

    /** Clears the capture and returns it to the pool, merging both failures. */
    @Nullable
    private Throwable releaseCapture(DeferredCapture capture, @Nullable Throwable failure) {
        try {
            capture.clear();
        } catch (Throwable t) {
            failure = merge(failure, t);
        }

        try {
            this.captures.release(capture);
        } catch (Throwable t) {
            failure = merge(failure, t);
        }

        return failure;
    }

    @Nullable
    private static ChunkJobResult<ChunkBuildOutput> mergeTiming(@Nullable ChunkJobResult<ChunkBuildOutput> result,
                                                               DeferredSectionMesh mesh) {
        if (result instanceof ChunkJobResult.Success<ChunkBuildOutput> success) {
            return new ChunkJobResult.Success<>(success.output(), mesh.totalNanos() + success.executionTimeNanos());
        }

        return result;
    }

    /**
     * Merges the release failure of a mesh that was never submitted into the failure that made the
     * scheduler drop it, so neither exception is lost.
     */
    private static ChunkJobResult.Failure<ChunkBuildOutput> mergeFailure(
            @Nullable ChunkJobResult.Failure<ChunkBuildOutput> failure, Throwable releaseFailure) {
        if (failure == null) {
            return new ChunkJobResult.Failure<>(releaseFailure);
        }

        if (failure.throwable() != releaseFailure) {
            failure.throwable().addSuppressed(releaseFailure);
        }

        return failure;
    }

    private static Throwable merge(@Nullable Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }

        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    private static RuntimeException asReleaseFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }

        if (failure instanceof Error error) {
            throw error;
        }

        return new RuntimeException("Failed to release a deferred mesh", failure);
    }
}
