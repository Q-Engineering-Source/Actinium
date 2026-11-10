package com.dhj.actinium.render.terrain.deferred;

import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildBuffers;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildContext;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildOutput;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkTaskOutput;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.executor.ChunkBuilder;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.executor.ChunkJobResult;
import dhj.embeddedt.embeddium.impl.util.task.CancellationToken;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceMaps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the deferred mesh scheduler through its state machine with real {@link ChunkBuilder},
 * {@link ChunkBuildBuffers} and slice fixtures: the main thread captures and the build thread
 * finishes, and every mesh leaves the scheduler exactly once whether it finished, became obsolete,
 * failed to capture, failed to finish or was orphaned by a shutdown.
 *
 * <p>The builder runs without worker threads, so {@link ChunkBuilder#tick()} is the build thread;
 * one case moves that call onto a separate thread to show which thread finishes a mesh. The
 * scheduler's main-thread queue is a plain list here, standing in for the render queue of
 * {@code RenderSectionManager}.</p>
 */
class DeferredMeshSchedulerTest {
    private static final long WORKER_NANOS = 900L;
    private static final long MAIN_NANOS = 400L;

    private final List<Runnable> mainThreadQueue = new CopyOnWriteArrayList<>();
    private final Queue<ChunkJobResult<? extends ChunkTaskOutput>> results = new ConcurrentLinkedQueue<>();

    private ChunkBuilder builder;
    private DeferredMeshScheduler scheduler;
    private boolean builderStopped;

    @BeforeEach
    void createScheduler() {
        this.builder = new ChunkBuilder(ChunkBuilder.ManagedBlocker.NONE, () -> new ChunkBuildContext(null), -1);
        this.scheduler = new DeferredMeshScheduler(null, this.builder, RecordingDeferredSlice::new,
                this.mainThreadQueue::add, this.mainThreadQueue::remove, this.results);
    }

    @AfterEach
    void stopBuilder() {
        stopBuilderSafely();
        this.scheduler.releaseOrphans();
    }

    @Test
    void importantMeshIsCapturedAndFinishedBeforeRunImportantReturns() {
        RecordingHandler handler = handler();
        DeferredSectionMesh mesh = submit(handler, true, 7L);
        assertEquals(1, this.scheduler.pendingCount());

        this.scheduler.runImportant();

        assertEquals(1, handler.captures.get());
        assertEquals(1, handler.finishes.get());
        assertSame(Thread.currentThread(), handler.captureThread);
        assertSame(Thread.currentThread(), handler.finishThread);
        assertSame(mesh, handler.capturedMesh);
        assertNotNull(handler.usedCapture);
        assertEquals(0, this.scheduler.pendingCount());
        assertEquals(0, this.scheduler.finishingCount());

        ChunkJobResult.Success<?> result = assertInstanceOf(ChunkJobResult.Success.class, this.results.poll());
        assertSame(handler.output, result.output());
        assertTrue(result.executionTimeNanos() >= WORKER_NANOS + MAIN_NANOS,
                "finish time must include both the worker and the main-thread capture: " + result.executionTimeNanos());

        assertSame(mesh.buffers, this.scheduler.acquireBuffers());
        assertSame(mesh.slice, this.scheduler.acquireSlice());
    }

    @Test
    void meshThatIsAlreadyObsoleteIsReleasedWithoutCapturing() {
        RecordingHandler handler = handler();
        handler.obsolete = true;
        DeferredSectionMesh mesh = submit(handler, true, 3L);

        this.scheduler.runImportant();

        assertEquals(0, handler.captures.get());
        assertEquals(0, handler.finishes.get());
        assertEquals(1, ((RecordingDeferredSlice) mesh.slice).releaseCount());
        assertEquals(0, this.scheduler.pendingCount());
        assertEquals(1, this.scheduler.freeSliceCount());
        assertNull(this.results.poll(), "an obsolete mesh must not produce a build result");
    }

    @Test
    void meshThatBecomesObsoleteWhileQueuedForFinishIsReleasedWithoutFinishing() {
        RecordingHandler handler = handler();
        handler.obsoleteAfterCapture = true;
        DeferredSectionMesh mesh = submit(handler, true, 3L);

        this.scheduler.runImportant();

        assertEquals(1, handler.captures.get());
        assertEquals(0, handler.finishes.get());
        assertEquals(1, ((RecordingDeferredSlice) mesh.slice).releaseCount());
        assertEquals(0, this.scheduler.pendingCount());
        assertEquals(0, this.scheduler.finishingCount());
        assertNull(this.results.poll(), "an aborted finish must not deliver a result");

        DeferredSectionMesh.DeferredCapture capturedOnce = handler.usedCapture;
        assertNotNull(capturedOnce);

        RecordingHandler nextHandler = handler();
        submit(nextHandler, true, 3L);
        this.scheduler.runImportant();
        assertSame(capturedOnce, nextHandler.usedCapture, "the aborted capture must return to the capture pool");
    }

    @Test
    void captureFailureIsReportedAndReleasesTheMesh() {
        RecordingHandler handler = handler();
        handler.captureFailure = new ChunkJobResult.Failure<>(new IllegalStateException("capture exploded"));
        DeferredSectionMesh mesh = submit(handler, true, 3L);

        this.scheduler.runImportant();

        assertInstanceOf(ChunkJobResult.Failure.class, this.results.poll());
        assertEquals(0, handler.finishes.get());
        assertEquals(1, ((RecordingDeferredSlice) mesh.slice).releaseCount());
        assertEquals(0, this.scheduler.pendingCount());
        assertEquals(0, this.scheduler.finishingCount());
        assertSame(mesh.buffers, this.scheduler.acquireBuffers());
    }

    @Test
    void finishFailureBecomesAFailureResultAndStillReleasesTheMesh() {
        RecordingHandler handler = handler();
        handler.finishFailure = new IllegalStateException("finish exploded");
        DeferredSectionMesh mesh = submit(handler, false, 5L);

        drainMainThreadQueue();
        runBuildJobs();

        assertInstanceOf(ChunkJobResult.Failure.class, this.results.poll());
        assertEquals(1, handler.finishes.get());
        assertEquals(1, ((RecordingDeferredSlice) mesh.slice).releaseCount());
        assertEquals(0, this.scheduler.pendingCount());
        assertEquals(0, this.scheduler.finishingCount());
    }

    @Test
    void normalMeshesShareOneCapturePerRenderQueuePassAndFinishOnTheBuildThread() {
        RecordingHandler nearest = handler();
        RecordingHandler farthest = handler();
        submit(nearest, false, 10L);
        DeferredSectionMesh farthestMesh = submit(farthest, false, 20L);

        assertEquals(1, this.mainThreadQueue.size(), "a burst must queue only the next normal capture");

        drainOneMainThreadTask();

        assertEquals(1, nearest.captures.get());
        assertEquals(0, farthest.captures.get(), "the next section waits for the next render queue pass");
        assertTrue(this.mainThreadQueue.isEmpty(), "a capture must not requeue itself during runAsyncTasks");

        this.scheduler.update();
        assertEquals(1, this.mainThreadQueue.size());

        drainOneMainThreadTask();

        assertEquals(1, farthest.captures.get());
        assertTrue(this.mainThreadQueue.isEmpty(), "a drained normal queue must not reschedule empty captures");
        assertEquals(2, this.scheduler.finishingCount());
        Thread buildThread = runBuildJobsOnSeparateThread();

        assertSame(buildThread, nearest.finishThread);
        assertSame(buildThread, farthest.finishThread);
        assertSame(nearest.captureThread, farthest.captureThread);
        assertFalse(nearest.finishThread == nearest.captureThread, "finishing must not happen on the capture thread");
        assertEquals(0, this.scheduler.pendingCount());
        assertEquals(0, this.scheduler.finishingCount());
        assertEquals(2, this.results.size());
        assertSame(farthestMesh.buffers, this.scheduler.acquireBuffers());
    }

    @Test
    void normalMeshesAreCapturedNearestFirst() {
        List<Long> captureOrder = new ArrayList<>();
        submit(handler(captureOrder), false, 30L);
        submit(handler(captureOrder), false, 10L);
        submit(handler(captureOrder), false, 20L);

        for (int index = 0; index < 3; index++) {
            assertEquals(1, this.mainThreadQueue.size(), "capture " + index + " must have one queued task");
            drainOneMainThreadTask();
            assertEquals(index + 1, captureOrder.size(), "capture " + index + " must run exactly once");
            this.scheduler.update();
        }

        assertTrue(this.mainThreadQueue.isEmpty(), "all three normal captures must be drained");
        assertEquals(List.of(10L, 20L, 30L), captureOrder);
    }

    @Test
    void trimmingTheNormalQueueReleasesAndRetriesTheFarthestMesh() {
        RecordingHandler nearest = handler();
        RecordingHandler middle = handler();
        RecordingHandler farthest = handler();
        DeferredSectionMesh nearestMesh = submit(nearest, false, 10L);
        DeferredSectionMesh middleMesh = submit(middle, false, 20L);
        DeferredSectionMesh farthestMesh = submit(farthest, false, 30L);

        this.scheduler.update();

        assertEquals(1, farthest.retries.get(), "the farthest mesh must be asked to rebuild once");
        assertEquals(0, nearest.retries.get());
        assertEquals(0, middle.retries.get());
        assertTrue(farthestMesh.token.isCancelled(), "a retried mesh cancels the build it came from");
        assertEquals(1, ((RecordingDeferredSlice) farthestMesh.slice).releaseCount(),
                "the trimmed mesh returns its snapshot to the cache");
        assertEquals(2, this.scheduler.pendingCount(), "the trimmed mesh is no longer pending");
        assertEquals(0, farthest.captures.get());

        drainMainThreadQueue();

        assertNotNull(nearestMesh.capture, "the nearest mesh stays capturable");
        assertNotNull(middleMesh.capture, "the second nearest mesh stays capturable");
        assertEquals(List.of(10L), nearest.captureOrder);
        assertEquals(List.of(20L), middle.captureOrder);
        assertEquals(2, this.scheduler.finishingCount());

        runBuildJobs();

        assertEquals(0, this.scheduler.pendingCount());
        assertEquals(0, this.scheduler.finishingCount());
        assertEquals(2, this.results.size());
    }

    @Test
    void anObsoleteMeshDoesNotConsumeTheNextCapturePass() {
        RecordingHandler obsolete = handler();
        obsolete.obsolete = true;
        DeferredSectionMesh obsoleteMesh = submit(obsolete, false, 10L);
        RecordingHandler valid = handler();
        DeferredSectionMesh validMesh = submit(valid, false, 20L);

        drainOneMainThreadTask();

        assertEquals(0, obsolete.captures.get(), "an obsolete mesh must not be captured");
        assertEquals(1, ((RecordingDeferredSlice) obsoleteMesh.slice).releaseCount(),
                "the obsolete mesh is released by the same capture pass");
        assertEquals(1, valid.captures.get(), "the valid mesh takes the pass the obsolete one opened");
        assertNotNull(validMesh.capture);
        assertEquals(1, this.scheduler.pendingCount(), "only the captured mesh is still pending");
        assertEquals(1, this.scheduler.finishingCount());

        runBuildJobs();

        assertEquals(0, this.scheduler.pendingCount());
        assertEquals(0, this.scheduler.finishingCount());
        assertEquals(1, this.results.size());
    }

    @Test
    void aBurstBeyondTheQueueCapacityKeepsTheNearestMeshesAndReusableResources() {
        int burst = 6;
        List<RecordingHandler> handlers = new ArrayList<>();
        List<DeferredSectionMesh> meshes = new ArrayList<>();

        for (int index = 0; index < burst; index++) {
            RecordingHandler handler = handler();
            handlers.add(handler);
            meshes.add(submit(handler, false, index));
        }

        this.scheduler.update();

        int retried = 0;

        for (RecordingHandler handler : handlers) {
            retried += handler.retries.get();
        }

        assertEquals(burst - 2, retried, "every mesh beyond the queue capacity is asked to rebuild");
        assertEquals(2, this.scheduler.pendingCount(), "the queue keeps its capacity worth of meshes");

        drainMainThreadQueue();

        int captured = 0;

        for (RecordingHandler handler : handlers) {
            captured += handler.captures.get();
        }

        assertEquals(2, captured, "only the capacity worth of nearest meshes is captured");
        assertEquals(2, this.scheduler.finishingCount());

        runBuildJobs();

        assertEquals(0, this.scheduler.pendingCount());
        assertEquals(0, this.scheduler.finishingCount());
        assertEquals(2, this.results.size());
        assertSame(meshes.get(1).buffers, this.scheduler.acquireBuffers());
        assertSame(meshes.get(1).slice, this.scheduler.acquireSlice());

        RecordingHandler afterBurst = handler();
        submit(afterBurst, true, 0L);
        this.scheduler.runImportant();

        assertTrue(afterBurst.usedCapture != null && afterBurst.usedCapture.capacity() > 0,
                "a capture from the burst must be handed out again");
    }

    @Test
    void aDestroyedSchedulerReleasesSubmittedMeshesWithoutCapturing() {
        RecordingHandler handler = handler();
        DeferredSectionMesh mesh = newMesh(handler, false, 1L);

        this.scheduler.markDestroyed();
        this.scheduler.submit(mesh);
        drainMainThreadQueue();

        assertEquals(0, handler.captures.get());
        assertEquals(1, ((RecordingDeferredSlice) mesh.slice).releaseCount());
        assertEquals(0, this.scheduler.pendingCount());
        assertEquals(0, this.scheduler.finishingCount());
        assertTrue(this.results.isEmpty());
    }

    @Test
    void releaseOrphansDropsQueuedAndInFlightMeshesAndCanRunTwice() {
        List<Long> captureOrder = new ArrayList<>();
        RecordingHandler importantHandler = handler(captureOrder);
        RecordingHandler inFlightHandler = handler(captureOrder);
        RecordingHandler queuedHandler = handler(captureOrder);

        DeferredSectionMesh important = newMesh(importantHandler, true, 1L);
        DeferredSectionMesh inFlight = newMesh(inFlightHandler, false, 2L);
        DeferredSectionMesh queued = newMesh(queuedHandler, false, 3L);

        this.scheduler.submit(important);
        this.scheduler.submit(inFlight);
        this.scheduler.submit(queued);
        assertEquals(3, this.scheduler.pendingCount());

        drainOneMainThreadTask();

        assertEquals(1, inFlightHandler.captures.get());
        assertEquals(0, queuedHandler.captures.get());
        assertEquals(1, this.scheduler.finishingCount());
        DeferredSectionMesh.DeferredCapture inFlightCapture = inFlight.capture;
        assertNotNull(inFlightCapture);

        stopBuilderSafely();
        this.scheduler.releaseOrphans();

        assertEquals(0, this.scheduler.pendingCount());
        assertEquals(0, this.scheduler.finishingCount());
        assertTrue(this.mainThreadQueue.isEmpty(), "the shared task must be removed from the render queue");
        assertEquals(1, ((RecordingDeferredSlice) important.slice).releaseCount());
        assertEquals(1, ((RecordingDeferredSlice) queued.slice).releaseCount());
        assertEquals(1, ((RecordingDeferredSlice) inFlight.slice).releaseCount());
        assertEquals(0, inFlightCapture.capacity(), "pooled captures are freed when the scheduler is shut down");

        this.scheduler.releaseOrphans();

        assertEquals(0, this.scheduler.pendingCount());
        assertEquals(1, ((RecordingDeferredSlice) important.slice).releaseCount());
        assertEquals(1, ((RecordingDeferredSlice) inFlight.slice).releaseCount());
    }

    @Test
    void aReleaseFailureBecomesAFailureResultWithoutStrandingTheOtherResources() {
        RecordingHandler handler = handler();
        DeferredSectionMesh mesh = newMesh(handler, true, 1L);
        ((RecordingDeferredSlice) mesh.slice).failOnRelease(new IllegalStateException("snapshot release failed"));

        this.scheduler.submit(mesh);
        this.scheduler.runImportant();

        assertInstanceOf(ChunkJobResult.Failure.class, this.results.poll());
        assertEquals(0, this.scheduler.pendingCount());
        assertEquals(0, this.scheduler.finishingCount());
        assertSame(mesh.buffers, this.scheduler.acquireBuffers());
        assertSame(mesh.slice, this.scheduler.acquireSlice());

        DeferredSectionMesh.DeferredCapture releasedCapture = handler.usedCapture;
        assertNotNull(releasedCapture);

        RecordingHandler nextHandler = handler();
        submit(nextHandler, true, 1L);
        this.scheduler.runImportant();
        assertSame(releasedCapture, nextHandler.usedCapture);
    }

    @Test
    void trimPoolsKeepsReleasedResourcesWithinTheFirstWindow() {
        DeferredSectionMesh mesh = submit(handler(), true, 1L);
        this.scheduler.runImportant();
        assertEquals(1, this.scheduler.freeSliceCount());

        this.scheduler.trimPools();
        this.scheduler.trimPools();

        assertEquals(1, this.scheduler.freeSliceCount());
        assertSame(mesh.slice, this.scheduler.acquireSlice());
    }

    @Test
    void captureExceptionIsReportedWithoutEscapingTheRenderThread() {
        RecordingHandler handler = handler();
        handler.captureException = new IllegalStateException("capture threw");
        DeferredSectionMesh mesh = submit(handler, false, 4L);

        drainMainThreadQueue();

        ChunkJobResult.Failure<?> result = assertInstanceOf(ChunkJobResult.Failure.class, this.results.poll());
        assertSame(handler.captureException, result.throwable());
        assertEquals(0, this.scheduler.pendingCount());
        assertEquals(0, this.scheduler.finishingCount());
        assertSame(mesh.buffers, this.scheduler.acquireBuffers());
        assertSame(mesh.slice, this.scheduler.acquireSlice());
        assertEquals(1, ((RecordingDeferredSlice) mesh.slice).releaseCount());
    }

    @Test
    void importantCaptureExceptionIsDeliveredThroughTheCollector() {
        RecordingHandler handler = handler();
        handler.captureException = new IllegalStateException("important capture threw");
        DeferredSectionMesh mesh = submit(handler, true, 4L);

        this.scheduler.runImportant();

        ChunkJobResult.Failure<?> result = assertInstanceOf(ChunkJobResult.Failure.class, this.results.poll());
        assertSame(handler.captureException, result.throwable());
        assertEquals(0, this.scheduler.pendingCount());
        assertEquals(0, this.scheduler.finishingCount());
        assertSame(mesh.slice, this.scheduler.acquireSlice());
    }

    @Test
    void finishSubmissionAfterTheBuilderStoppedIsReportedAsAFailure() {
        RecordingHandler handler = handler();
        DeferredSectionMesh mesh = submit(handler, false, 4L);
        stopBuilderSafely();

        drainMainThreadQueue();

        ChunkJobResult.Failure<?> result = assertInstanceOf(ChunkJobResult.Failure.class, this.results.poll());
        assertInstanceOf(IllegalStateException.class, result.throwable());
        assertEquals(0, handler.finishes.get());
        assertEquals(0, this.scheduler.pendingCount());
        assertEquals(0, this.scheduler.finishingCount());
        assertSame(mesh.buffers, this.scheduler.acquireBuffers());
        assertSame(mesh.slice, this.scheduler.acquireSlice());

        DeferredSectionMesh.DeferredCapture releasedCapture = handler.usedCapture;
        assertNotNull(releasedCapture);

        RecordingHandler secondHandler = handler();
        submit(secondHandler, false, 5L);
        drainMainThreadQueue();
        assertSame(releasedCapture, secondHandler.usedCapture,
                "the capture of a mesh that could not be finished must return to the pool");
    }

    private RecordingHandler handler() {
        return handler(new ArrayList<>());
    }

    @Test
    void anImportantCaptureFailureDoesNotSatisfyAnotherMeshsFinishPermit() throws Exception {
        ChunkBuilder workerBuilder = new ChunkBuilder(ChunkBuilder.ManagedBlocker.NONE,
                () -> new ChunkBuildContext(null), 1);
        List<Runnable> queue = new CopyOnWriteArrayList<>();
        Queue<ChunkJobResult<? extends ChunkTaskOutput>> workerResults = new ConcurrentLinkedQueue<>();
        DeferredMeshScheduler workerScheduler = new DeferredMeshScheduler(null, workerBuilder,
                RecordingDeferredSlice::new, queue::add, queue::remove, workerResults);

        CountDownLatch finishEntered = new CountDownLatch(1);
        CountDownLatch allowFinish = new CountDownLatch(1);

        try {
            RecordingHandler failing = handler();
            failing.captureFailure = new ChunkJobResult.Failure<>(new IllegalStateException("capture failed"));

            RecordingHandler slow = handler();
            slow.beforeFinish = () -> {
                finishEntered.countDown();
                awaitUninterruptibly(allowFinish);
            };

            // Gates the drain until the slow finish is running, so the collector cannot steal it and
            // the only permit it could consume would be a spurious one from a mesh that never submitted.
            RecordingHandler gate = handler();
            gate.captureFailure = new ChunkJobResult.Failure<>(new IllegalStateException("gated capture failed"));
            gate.beforeCapture = () -> awaitUninterruptibly(finishEntered);

            workerScheduler.submit(newMesh(workerScheduler, failing, true, 1L));
            workerScheduler.submit(newMesh(workerScheduler, slow, true, 2L));
            workerScheduler.submit(newMesh(workerScheduler, gate, true, 3L));

            Thread importantRunner = new Thread(workerScheduler::runImportant, "deferred-important-runner");
            importantRunner.start();
            importantRunner.join(2_000L);

            boolean returnedEarly = !importantRunner.isAlive();

            allowFinish.countDown();
            importantRunner.join(30_000L);

            assertFalse(returnedEarly, "runImportant returned while a submitted finish was still running");
            assertEquals(1, slow.finishes.get());
            assertEquals(0, workerScheduler.pendingCount());
            assertEquals(0, workerScheduler.finishingCount());

            List<ChunkJobResult<? extends ChunkTaskOutput>> delivered = new ArrayList<>(workerResults);
            assertEquals(3, delivered.size());
            assertInstanceOf(ChunkJobResult.Failure.class, delivered.get(0));
            assertInstanceOf(ChunkJobResult.Failure.class, delivered.get(1));
            assertInstanceOf(ChunkJobResult.Success.class, delivered.get(2));
        } finally {
            allowFinish.countDown();
            workerBuilder.shutdown();
            workerScheduler.releaseOrphans();
        }
    }

    @Test
    void aCaptureExceptionCarriesTheReleaseFailureAsSuppressed() {
        RecordingHandler handler = handler();
        IllegalStateException captureFailure = new IllegalStateException("capture threw");
        handler.captureException = captureFailure;

        DeferredSectionMesh mesh = newMesh(handler, true, 1L);
        RuntimeException releaseFailure = new IllegalStateException("snapshot release failed");
        ((RecordingDeferredSlice) mesh.slice).failOnRelease(releaseFailure);

        this.scheduler.submit(mesh);
        this.scheduler.runImportant();

        ChunkJobResult.Failure<?> result = assertInstanceOf(ChunkJobResult.Failure.class, this.results.poll());
        assertSame(captureFailure, result.throwable());
        assertArrayEquals(new Throwable[] { releaseFailure }, captureFailure.getSuppressed());
        assertEquals(0, this.scheduler.pendingCount());
        assertEquals(0, this.scheduler.finishingCount());
    }

    @Test
    void aSuccessfulCaptureResultIsRejectedBeforeItCanBeUploaded() {
        RecordingHandler handler = handler();
        TrackingOutput output = new TrackingOutput();
        handler.output = output;
        handler.captureSuccess = new ChunkJobResult.Success<>(output, 1L);
        DeferredSectionMesh mesh = submit(handler, true, 1L);

        this.scheduler.runImportant();

        ChunkJobResult.Failure<?> result = assertInstanceOf(ChunkJobResult.Failure.class, this.results.poll());
        assertInstanceOf(IllegalStateException.class, result.throwable());
        assertEquals(0, handler.finishes.get());
        assertEquals(0, this.scheduler.pendingCount());
        assertEquals(0, this.scheduler.finishingCount());
        assertSame(mesh.buffers, this.scheduler.acquireBuffers());
        assertSame(mesh.slice, this.scheduler.acquireSlice());
        assertTrue(output.deleted, "a rejected capture result must free its mesh data");
    }

    @Test
    void aReleaseFailureDeletesTheSuccessfulOutputBeforeReportingIt() {
        RecordingHandler handler = handler();
        TrackingOutput output = new TrackingOutput();
        handler.output = output;

        DeferredSectionMesh mesh = newMesh(handler, true, 1L);
        ((RecordingDeferredSlice) mesh.slice).failOnRelease(new IllegalStateException("snapshot release failed"));

        this.scheduler.submit(mesh);
        this.scheduler.runImportant();

        assertInstanceOf(ChunkJobResult.Failure.class, this.results.poll());
        assertTrue(output.deleted, "the mesh data of the replaced build output must be freed");
        assertEquals(0, this.scheduler.pendingCount());
        assertEquals(0, this.scheduler.finishingCount());
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the test latch", e);
        }
    }

    private static final class TrackingOutput extends ChunkBuildOutput {
        private boolean deleted;

        private TrackingOutput() {
            super(null, null, Reference2ReferenceMaps.emptyMap(), 0);
        }

        @Override
        public void delete() {
            super.delete();
            this.deleted = true;
        }
    }

    private static RecordingHandler handler(List<Long> captureOrder) {
        return new RecordingHandler(captureOrder);
    }

    private DeferredSectionMesh newMesh(RecordingHandler handler, boolean important, long priority) {
        return newMesh(this.scheduler, handler, important, priority);
    }

    private static DeferredSectionMesh newMesh(DeferredMeshScheduler scheduler, RecordingHandler handler,
                                               boolean important, long priority) {
        return new DeferredSectionMesh(handler, token(), scheduler.acquireBuffers(),
                ((RecordingDeferredSlice) scheduler.acquireSlice()).attachSnapshot(), important, priority, WORKER_NANOS);
    }

    private static CancellationToken token() {
        return new CancellationToken() {
            private boolean cancelled;

            @Override
            public boolean isCancelled() {
                return this.cancelled;
            }

            @Override
            public void setCancelled() {
                this.cancelled = true;
            }
        };
    }

    private DeferredSectionMesh submit(RecordingHandler handler, boolean important, long priority) {
        DeferredSectionMesh mesh = newMesh(handler, important, priority);
        handler.priority = priority;
        this.scheduler.submit(mesh);
        return mesh;
    }

    /** Mirrors {@code RenderSectionManager#runAsyncTasks} followed by the scheduler's update hook. */
    private void drainMainThreadQueue() {
        int passes = 0;
        while (!this.mainThreadQueue.isEmpty()) {
            assertTrue(passes++ < 32, "render queue did not drain: queued=" + this.mainThreadQueue.size()
                    + ", pending=" + this.scheduler.pendingCount() + ", finishing=" + this.scheduler.finishingCount());
            this.mainThreadQueue.remove(0).run();
            this.scheduler.update();
        }
    }

    private void drainOneMainThreadTask() {
        assertFalse(this.mainThreadQueue.isEmpty(), "no queued render task to run");
        this.mainThreadQueue.remove(0).run();
    }

    private void runBuildJobs() {
        this.builder.tick();
    }

    private Thread runBuildJobsOnSeparateThread() {
        Thread buildThread = new Thread(this.builder::tick, "deferred-mesh-build-thread");
        buildThread.start();

        try {
            buildThread.join(30_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the build thread", e);
        }

        assertFalse(buildThread.isAlive(), "the build thread did not finish its jobs");
        return buildThread;
    }

    private void stopBuilderSafely() {
        if (this.builderStopped) {
            return;
        }

        this.builderStopped = true;
        this.builder.shutdown();
    }

    private static final class RecordingHandler implements DeferredSectionMesh.Handler {
        private final List<Long> captureOrder;
        private final AtomicInteger captures = new AtomicInteger();
        private final AtomicInteger finishes = new AtomicInteger();
        private final AtomicInteger retries = new AtomicInteger();
        private ChunkBuildOutput output = new ChunkBuildOutput(null, null, Reference2ReferenceMaps.emptyMap(), 0);

        private boolean obsolete;
        private boolean obsoleteAfterCapture;
        private ChunkJobResult<ChunkBuildOutput> captureFailure;
        private ChunkJobResult<ChunkBuildOutput> captureSuccess;
        private RuntimeException captureException;
        private RuntimeException finishFailure;
        private Runnable beforeCapture;
        private Runnable beforeFinish;
        private long priority;
        private volatile Thread captureThread;
        private volatile Thread finishThread;
        private volatile DeferredSectionMesh capturedMesh;
        private volatile DeferredSectionMesh.DeferredCapture usedCapture;

        private RecordingHandler(List<Long> captureOrder) {
            this.captureOrder = captureOrder;
        }

        @Override
        public boolean isObsolete() {
            return this.obsolete || (this.obsoleteAfterCapture && this.captures.get() > 0);
        }

        @Override
        public ChunkJobResult<ChunkBuildOutput> captureDeferred(DeferredSectionMesh mesh) {
            this.captureThread = Thread.currentThread();
            this.capturedMesh = mesh;
            this.usedCapture = mesh.capture;
            this.captureOrder.add(this.priority);
            this.captures.incrementAndGet();
            mesh.mainNanos = MAIN_NANOS;

            if (this.beforeCapture != null) {
                this.beforeCapture.run();
            }

            if (this.captureException != null) {
                throw this.captureException;
            }

            return this.captureSuccess != null ? this.captureSuccess : this.captureFailure;
        }

        @Override
        public void retryLater(DeferredSectionMesh mesh) {
            mesh.token.setCancelled();
            this.retries.incrementAndGet();
        }

        @Override
        public ChunkBuildOutput finishDeferred(DeferredSectionMesh mesh, ChunkBuildContext context) {
            this.finishThread = Thread.currentThread();
            this.finishes.incrementAndGet();

            if (this.beforeFinish != null) {
                this.beforeFinish.run();
            }

            if (this.finishFailure != null) {
                throw this.finishFailure;
            }

            return this.output;
        }
    }
}
