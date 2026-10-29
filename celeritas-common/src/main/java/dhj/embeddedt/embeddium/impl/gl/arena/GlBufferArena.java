package dhj.embeddedt.embeddium.impl.gl.arena;

import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;
import com.gtnewhorizons.angelica.glsm.debug.TerrainBufferDiagnostics;
import dhj.embeddedt.embeddium.impl.gl.sync.GlFence;
import dhj.embeddedt.embeddium.impl.gl.arena.staging.StagingBuffer;
import dhj.embeddedt.embeddium.impl.gl.buffer.GlBuffer;
import dhj.embeddedt.embeddium.impl.gl.buffer.GlBufferUsage;
import dhj.embeddedt.embeddium.impl.gl.buffer.GlMutableBuffer;
import dhj.embeddedt.embeddium.impl.gl.device.CommandList;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class GlBufferArena {
    static final boolean CHECK_ASSERTIONS = false;

    private static final Logger LOGGER = LogManager.getLogger(GlBufferArena.class);
    private static final AtomicLong NEXT_ARENA_ID = new AtomicLong(1L);

    private static final GlBufferUsage BUFFER_USAGE = GlBufferUsage.STATIC_DRAW;

    private static final int LARGE_ARENA_THRESHOLD_BYTES = 4 * 1024 * 1024;

    private static final int LARGE_GROWTH_FACTOR = 2;

    private final StagingBuffer stagingBuffer;
    private GlMutableBuffer arenaBuffer;

    private GlBufferSegment head;

    private int capacity;
    private int used;

    private final int stride;
    private final int minimumCapacity;
    private final long arenaId = nextArenaId();
    private final ResizeObserver resizeObserver;
    private final ArenaDiagnosticsContext diagnosticsContext;
    private long registrationToken;
    private long registrationResizeSequence;

    public GlBufferArena(CommandList commands, int stride, int minimumCapacityBytes, StagingBuffer stagingBuffer) {
        this(commands, stride, minimumCapacityBytes, stagingBuffer, null, null);
    }

    public GlBufferArena(CommandList commands, int stride, int minimumCapacityBytes, StagingBuffer stagingBuffer,
                         ResizeObserver resizeObserver, ArenaDiagnosticsContext diagnosticsContext) {
        if (stride <= 0) {
            throw new IllegalArgumentException("Stride must be positive");
        }
        if ((resizeObserver == null) != (diagnosticsContext == null)) {
            throw new IllegalArgumentException("Arena diagnostics observer and context must be supplied together");
        }

        this.stride = stride;
        this.minimumCapacity = this.elementsFor(minimumCapacityBytes);
        this.resizeObserver = resizeObserver;
        this.diagnosticsContext = diagnosticsContext;

        this.capacity = 0;
        this.head = null;

        this.arenaBuffer = commands.createMutableBuffer();
        if (this.diagnosticsContext != null && GLSMPerfDebug.isEnabled()) {
            try {
                this.registrationToken = TerrainBufferDiagnostics.registerArenaBuffer(
                        this.arenaId, this.diagnosticsContext.arenaType().name().toLowerCase(Locale.ROOT),
                        this.stride, this.diagnosticsContext.regionId(), this.diagnosticsContext.regionX(),
                        this.diagnosticsContext.regionY(), this.diagnosticsContext.regionZ(),
                        this.arenaBuffer.handle(), 0L);
            } catch (RuntimeException | Error exception) {
                LOGGER.info("[ActiniumMemory] terrain.arenaObservationUnavailable phase=initial-register arenaId={} reason={}",
                        this.arenaId, exception.getClass().getName());
            }
        }

        try {
            this.runWithOperationScope(this.arenaBuffer.handle(), this.registrationToken, 0L,
                    TerrainBufferDiagnostics.BufferOperation.INITIAL_STORAGE,
                    () -> commands.allocateStorage(this.arenaBuffer, 0L, BUFFER_USAGE));
        } catch (RuntimeException | Error failure) {
            if (this.registrationToken != 0L) {
                try {
                    TerrainBufferDiagnostics.unregisterArenaBuffer(this.arenaId, this.arenaBuffer.handle());
                } catch (RuntimeException | Error diagnosticFailure) {
                    if (failure != diagnosticFailure) {
                        failure.addSuppressed(diagnosticFailure);
                    }
                    LOGGER.info("[ActiniumMemory] terrain.arenaObservationUnavailable phase=initial-register-cleanup arenaId={} reason={}",
                            this.arenaId, diagnosticFailure.getClass().getName());
                }
            }
            throw failure;
        }

        this.stagingBuffer = stagingBuffer;
    }

    private void resize(CommandList commandList, int newCapacity, long requestBytes) {
        if (this.used > newCapacity) {
            throw new UnsupportedOperationException("New capacity must be larger than used size");
        }

        this.checkAssertions();

        int tail = newCapacity - this.used;

        List<GlBufferSegment> usedSegments = this.getUsedSegments();
        List<PendingBufferCopyCommand> pendingCopies = this.buildTransferList(usedSegments, tail);

        this.transferSegments(commandList, pendingCopies, newCapacity, requestBytes);

        if (tail == 0) {
            this.head = usedSegments.isEmpty() ? null : usedSegments.get(0);

            if (this.head != null) {
                this.head.setPrev(null);
            }
        } else {
            this.head = new GlBufferSegment(this, 0, tail);
            this.head.setFree(true);

            if (usedSegments.isEmpty()) {
                this.head.setNext(null);
            } else {
                this.head.setNext(usedSegments.get(0));
                this.head.getNext()
                        .setPrev(this.head);
            }
        }

        this.checkAssertions();
    }

    private List<PendingBufferCopyCommand> buildTransferList(List<GlBufferSegment> usedSegments, int base) {
        List<PendingBufferCopyCommand> pendingCopies = new ArrayList<>();
        PendingBufferCopyCommand currentCopyCommand = null;

        int writeOffset = base;

        for (int i = 0; i < usedSegments.size(); i++) {
            GlBufferSegment s = usedSegments.get(i);

            if (currentCopyCommand == null || currentCopyCommand.readOffset + currentCopyCommand.length != s.getOffset()) {
                if (currentCopyCommand != null) {
                    pendingCopies.add(currentCopyCommand);
                }

                currentCopyCommand = new PendingBufferCopyCommand(s.getOffset(), writeOffset, s.getLength());
            } else {
                currentCopyCommand.length += s.getLength();
            }

            s.setOffset(writeOffset);

            if (i + 1 < usedSegments.size()) {
                s.setNext(usedSegments.get(i + 1));
            } else {
                s.setNext(null);
            }

            if (i - 1 < 0) {
                s.setPrev(null);
            } else {
                s.setPrev(usedSegments.get(i - 1));
            }

            writeOffset += s.getLength();
        }

        if (currentCopyCommand != null) {
            pendingCopies.add(currentCopyCommand);
        }

        return pendingCopies;
    }

    private void transferSegments(CommandList commandList, Collection<PendingBufferCopyCommand> list, int capacity, long requestBytes) {
        GlMutableBuffer srcBufferObj = this.arenaBuffer;
        GlMutableBuffer dstBufferObj = commandList.createMutableBuffer();

        long newCapacityBytes = (long) capacity * this.stride;
        long oldCapacityBytes = (long) this.capacity * this.stride;
        long usedBytes = (long) this.used * this.stride;
        ResizeEvent resizeEvent = null;
        if (this.diagnosticsContext != null && GLSMPerfDebug.isEnabled()) {
            resizeEvent = new ResizeEvent(this.diagnosticsContext, this.stride,
                    oldCapacityBytes, newCapacityBytes, usedBytes, requestBytes, list.size());
            try {
                this.resizeObserver.onResize(resizeEvent);
            } catch (RuntimeException | Error exception) {
                LOGGER.info("[ActiniumMemory] terrain.arenaObservationUnavailable phase=resize-record arenaId={} reason={}",
                        this.arenaId, exception.getClass().getName());
            }
        }

        int oldBufferId = srcBufferObj.handle();
        int newBufferId = dstBufferObj.handle();
        TerrainBufferDiagnostics.ResizeToken resizeToken = resizeEvent == null ? null
                : this.beginReplacement(resizeEvent, oldBufferId, newBufferId);
        long nextRegistrationToken = resizeToken == null ? 0L : resizeToken.newRegistrationToken();
        long nextResizeSequence = resizeToken == null ? 0L : resizeToken.resizeSequence();
        boolean deleteRequested = false;

        try {
            this.runWithOperationScope(newBufferId, nextRegistrationToken, nextResizeSequence,
                    TerrainBufferDiagnostics.BufferOperation.REPLACEMENT_STORAGE,
                    () -> commandList.allocateStorage(dstBufferObj, newCapacityBytes, BUFFER_USAGE));

            for (PendingBufferCopyCommand cmd : list) {
                commandList.copyBufferSubData(srcBufferObj, dstBufferObj,
                        (long) cmd.readOffset * this.stride,
                        (long) cmd.writeOffset * this.stride,
                        (long) cmd.length * this.stride);
            }

            if (resizeToken != null) {
                try {
                    this.recordRetirementFence(commandList, resizeEvent, resizeToken, oldBufferId, newBufferId);
                } catch (RuntimeException | Error diagnosticFailure) {
                    LOGGER.info("[ActiniumMemory] terrain.arenaObservationUnavailable phase=fence-observer arenaId={} resizeSequence={} reason={}",
                            this.arenaId, resizeToken.resizeSequence(), diagnosticFailure.getClass().getName());
                }
            }
            if (resizeToken != null) {
                deleteRequested = true;
                this.notifyDeleteRequested(resizeEvent, resizeToken, oldBufferId, newBufferId, System.nanoTime());
            }

            this.runWithOperationScope(oldBufferId,
                    resizeToken == null ? this.registrationToken : resizeToken.oldRegistrationToken(),
                    resizeToken == null ? this.registrationResizeSequence : resizeToken.resizeSequence(),
                    TerrainBufferDiagnostics.BufferOperation.RETIRED_DELETE,
                    () -> commandList.deleteBuffer(srcBufferObj));
        } catch (RuntimeException | Error failure) {
            if (!deleteRequested && resizeToken != null) {
                this.cancelReplacement(resizeToken, failure);
            }
            throw failure;
        }

        this.arenaBuffer = dstBufferObj;
        this.capacity = capacity;
        this.registrationToken = nextRegistrationToken;
        this.registrationResizeSequence = nextResizeSequence;
    }

    private TerrainBufferDiagnostics.ResizeToken beginReplacement(ResizeEvent resizeEvent,
                                                                   int oldBufferId, int newBufferId) {
        if (this.diagnosticsContext == null || !GLSMPerfDebug.isEnabled()) {
            return null;
        }
        try {
            return TerrainBufferDiagnostics.beginArenaReplacement(this.arenaId,
                    this.diagnosticsContext.arenaType().name().toLowerCase(Locale.ROOT), this.stride,
                    this.diagnosticsContext.regionId(), this.diagnosticsContext.regionX(),
                    this.diagnosticsContext.regionY(), this.diagnosticsContext.regionZ(),
                    oldBufferId, resizeEvent.oldCapacityBytes(), newBufferId, resizeEvent.newCapacityBytes(),
                    resizeEvent.usedBytes(), resizeEvent.requestBytes(), resizeEvent.copySpanCount());
        } catch (RuntimeException | Error exception) {
            LOGGER.info("[ActiniumMemory] terrain.arenaObservationUnavailable phase=replacement-register arenaId={} oldBufferId={} newBufferId={} reason={}",
                    this.arenaId, oldBufferId, newBufferId, exception.getClass().getName());
            return null;
        }
    }

    private void recordRetirementFence(CommandList commandList, ResizeEvent resizeEvent,
                                       TerrainBufferDiagnostics.ResizeToken resizeToken,
                                       int oldBufferId, int newBufferId) {
        if (resizeToken == null || this.resizeObserver == null) {
            return;
        }

        GlFence fence = null;
        try {
            if (!this.resizeObserver.shouldTrackRetirement()) {
                this.notifyRetirementSkipped(resizeEvent, resizeToken, oldBufferId, newBufferId);
                return;
            }

            long submittedAtNanos = System.nanoTime();
            String unavailableReason = "none";
            try {
                fence = commandList.createFence();
                if (!fence.isValid()) {
                    fence = null;
                    unavailableReason = "glFenceSync-returned-zero";
                }
            } catch (RuntimeException | Error fenceFailure) {
                unavailableReason = "glFenceSync-failed:" + fenceFailure.getClass().getName();
                LOGGER.info("[ActiniumMemory] terrain.arenaObservationUnavailable phase=fence-create arenaId={} resizeSequence={} reason={}",
                        this.arenaId, resizeToken.resizeSequence(), fenceFailure.getClass().getName());
            }
            ReplacementFenceEvent event = new ReplacementFenceEvent(this.diagnosticsContext.ownerId(), this.arenaId,
                    resizeEvent, resizeToken, oldBufferId, newBufferId, fence, submittedAtNanos, unavailableReason);
            this.resizeObserver.onRetirementFenceSubmitted(event);
            fence = null; // The tracker owns a successfully recorded fence.
        } catch (RuntimeException | Error exception) {
            LOGGER.info("[ActiniumMemory] terrain.arenaObservationUnavailable phase=fence-record arenaId={} resizeSequence={} reason={}",
                    this.arenaId, resizeToken.resizeSequence(), exception.getClass().getName());
            if (fence != null) {
                try {
                    fence.delete();
                } catch (RuntimeException | Error deleteFailure) {
                    LOGGER.warn("[ActiniumMemory] terrain.arenaObservationUnavailable phase=fence-cleanup arenaId={} resizeSequence={} reason={}",
                            this.arenaId, resizeToken.resizeSequence(), deleteFailure.getClass().getName());
                }
            }
        }
    }

    private void notifyRetirementSkipped(ResizeEvent resizeEvent,
                                        TerrainBufferDiagnostics.ResizeToken resizeToken,
                                        int oldBufferId, int newBufferId) {
        try {
            this.resizeObserver.onRetirementCoverageSkipped(new ReplacementFenceEvent(
                    this.diagnosticsContext.ownerId(), this.arenaId, resizeEvent, resizeToken,
                    oldBufferId, newBufferId, null, System.nanoTime(), "record-capacity"));
        } catch (RuntimeException | Error exception) {
            LOGGER.info("[ActiniumMemory] terrain.arenaObservationUnavailable phase=coverage-record arenaId={} resizeSequence={} reason={}",
                    this.arenaId, resizeToken.resizeSequence(), exception.getClass().getName());
        }
    }

    private void notifyDeleteRequested(ResizeEvent resizeEvent,
                                       TerrainBufferDiagnostics.ResizeToken resizeToken,
                                       int oldBufferId, int newBufferId, long requestedAtNanos) {
        try {
            this.resizeObserver.onOldBufferDeleteRequested(new ReplacementDeleteEvent(
                    this.diagnosticsContext.ownerId(), this.arenaId, resizeEvent, resizeToken,
                    oldBufferId, newBufferId, requestedAtNanos));
        } catch (RuntimeException | Error exception) {
            LOGGER.info("[ActiniumMemory] terrain.arenaObservationUnavailable phase=delete-request-record arenaId={} resizeSequence={} reason={}",
                    this.arenaId, resizeToken.resizeSequence(), exception.getClass().getName());
        }
    }

    private void cancelReplacement(TerrainBufferDiagnostics.ResizeToken resizeToken, Throwable originalFailure) {
        try {
            TerrainBufferDiagnostics.cancelArenaReplacement(resizeToken);
        } catch (RuntimeException | Error diagnosticFailure) {
            if (originalFailure != diagnosticFailure) {
                originalFailure.addSuppressed(diagnosticFailure);
            }
            LOGGER.warn("[ActiniumMemory] terrain.arenaObservationUnavailable phase=replacement-cancel arenaId={} resizeSequence={} reason={}",
                    this.arenaId, resizeToken.resizeSequence(), diagnosticFailure.getClass().getName());
        }
    }

    private TerrainBufferDiagnostics.ArenaBufferScope openOperationScope(int bufferId, long token,
                                                                         long resizeSequence,
                                                                         TerrainBufferDiagnostics.BufferOperation operation) {
        if (this.diagnosticsContext == null || token == 0L) {
            return null;
        }
        try {
            return TerrainBufferDiagnostics.openArenaBufferOperation(this.arenaId, bufferId, token,
                    resizeSequence, operation);
        } catch (RuntimeException | Error exception) {
            LOGGER.info("[ActiniumMemory] terrain.arenaObservationUnavailable phase=operation-scope arenaId={} bufferId={} operation={} reason={}",
                    this.arenaId, bufferId, operation, exception.getClass().getName());
            return null;
        }
    }

    private void runWithOperationScope(int bufferId, long token, long resizeSequence,
                                      TerrainBufferDiagnostics.BufferOperation operation,
                                      ScopedOperation scopedOperation) {
        TerrainBufferDiagnostics.ArenaBufferScope scope = this.openOperationScope(
                bufferId, token, resizeSequence, operation);
        Throwable operationFailure = null;
        try {
            scopedOperation.run();
        } catch (RuntimeException | Error failure) {
            operationFailure = failure;
            throw failure;
        } finally {
            if (scope != null) {
                try {
                    scope.close();
                } catch (RuntimeException | Error diagnosticFailure) {
                    if (operationFailure != null) {
                        if (operationFailure != diagnosticFailure) {
                            operationFailure.addSuppressed(diagnosticFailure);
                        }
                    }
                    LOGGER.info("[ActiniumMemory] terrain.arenaObservationUnavailable phase=operation-scope-close arenaId={} bufferId={} operation={} reason={}",
                            this.arenaId, bufferId, operation, diagnosticFailure.getClass().getName());
                }
            }
        }
    }

    @FunctionalInterface
    private interface ScopedOperation {
        void run();
    }

    private ArrayList<GlBufferSegment> getUsedSegments() {
        ArrayList<GlBufferSegment> used = new ArrayList<>();
        GlBufferSegment seg = this.head;

        while (seg != null) {
            GlBufferSegment next = seg.getNext();

            if (!seg.isFree()) {
                used.add(seg);
            }

            seg = next;
        }

        return used;
    }

    @Deprecated
    public int getDeviceUsedMemory() {
        return this.used * this.stride;
    }

    @Deprecated
    public int getDeviceAllocatedMemory() {
        return this.capacity * this.stride;
    }

    public long getDeviceUsedMemoryL() {
        return (long)this.used * this.stride;
    }

    public long getDeviceAllocatedMemoryL() {
        return (long)this.capacity * this.stride;
    }

    private GlBufferSegment alloc(int size) {
        GlBufferSegment a = this.findFree(size);

        if (a == null) {
            return null;
        }

        GlBufferSegment result;

        if (a.getLength() == size) {
            a.setFree(false);

            result = a;
        } else {
            GlBufferSegment b = new GlBufferSegment(this, a.getEnd() - size, size);
            b.setNext(a.getNext());
            b.setPrev(a);

            if (b.getNext() != null) {
                b.getNext()
                        .setPrev(b);
            }

            a.setLength(a.getLength() - size);
            a.setNext(b);

            result = b;
        }

        this.used += result.getLength();
        this.checkAssertions();

        return result;
    }

    private GlBufferSegment findFree(int size) {
        GlBufferSegment entry = this.head;
        GlBufferSegment best = null;

        while (entry != null) {
            if (entry.isFree()) {
                if (entry.getLength() == size) {
                    return entry;
                } else if (entry.getLength() >= size) {
                    if (best == null || best.getLength() > entry.getLength()) {
                        best = entry;
                    }
                }
            }

            entry = entry.getNext();
        }

        return best;
    }

    public void free(GlBufferSegment entry) {
        if (entry.isFree()) {
            throw new IllegalStateException("Already freed");
        }

        entry.setFree(true);

        this.used -= entry.getLength();

        GlBufferSegment next = entry.getNext();

        if (next != null && next.isFree()) {
            entry.mergeInto(next);
        }

        GlBufferSegment prev = entry.getPrev();

        if (prev != null && prev.isFree()) {
            prev.mergeInto(entry);
        }

        this.checkAssertions();
    }

    public void delete(CommandList commands) {
        int bufferId = this.arenaBuffer.handle();
        this.runWithOperationScope(bufferId, this.registrationToken, this.registrationResizeSequence,
                TerrainBufferDiagnostics.BufferOperation.RETIRED_DELETE,
                () -> commands.deleteBuffer(this.arenaBuffer));
        this.registrationToken = 0L;
        this.capacity = -1;
    }

    public boolean isDeleted() {
        return this.capacity < 0;
    }

    public boolean isEmpty() {
        return this.used <= 0;
    }

    public GlBuffer getBufferObject() {
        return this.arenaBuffer;
    }

    public boolean upload(CommandList commandList, Stream<PendingUpload> stream) {
        // If the arena needs to re-allocate a buffer, this will allow us to check and return an appropriate flag
        GlBuffer buffer = this.arenaBuffer;

        // A linked list is used as we'll be randomly removing elements and want O(1) performance
        List<PendingUpload> queue = stream.collect(Collectors.toCollection(LinkedList::new));

        this.tryUploads(commandList, queue);

        // If we weren't able to upload some buffers, they will have been left behind in the queue
        if (!queue.isEmpty()) {
            int remainingElements = (int)queue.stream()
                    .mapToLong(upload -> this.elementsFor(upload.getDataBuffer().getLength()))
                    .sum();
            long requestBytes = GLSMPerfDebug.isEnabled()
                    ? queue.stream().mapToLong(upload -> upload.getDataBuffer().getLength()).sum()
                    : 0L;

            // This will force a re-allocation and compaction, which will leave us a continuous free segment
            // for the remaining uploads
            this.ensureCapacity(commandList, remainingElements, requestBytes);

            this.tryUploads(commandList, queue);

            if (!queue.isEmpty()) {
                throw new RuntimeException("Failed to upload all buffers");
            }
        }

        return this.arenaBuffer != buffer;
    }

    private void tryUploads(CommandList commandList, List<PendingUpload> queue) {
        queue.removeIf(upload -> this.tryUpload(commandList, upload));
        this.stagingBuffer.flush(commandList);
    }

    private boolean tryUpload(CommandList commandList, PendingUpload upload) {
        ByteBuffer data = upload.getDataBuffer()
                .getDirectBuffer();

        int elementCount = this.elementsFor(data.remaining());

        GlBufferSegment dst = this.alloc(elementCount);

        if (dst == null) {
            return false;
        }

        this.stagingBuffer.enqueueCopy(commandList, data, this.arenaBuffer, (long)dst.getOffset() * this.stride);

        upload.setResult(dst);

        return true;
    }

    private int elementsFor(int byteLength) {
        return (int)(((long)byteLength + this.stride - 1) / this.stride);
    }

    private static long nextArenaId() {
        long arenaId = NEXT_ARENA_ID.getAndIncrement();
        if (arenaId <= 0L) {
            throw new IllegalStateException("Terrain arena diagnostic identity overflowed");
        }
        return arenaId;
    }

    public void ensureCapacity(CommandList commandList, int elementCount) {
        this.ensureCapacity(commandList, elementCount, (long) elementCount * this.stride);
    }

    private void ensureCapacity(CommandList commandList, int elementCount, long requestBytes) {
        long required = (long)this.used + elementCount;

        if (required * 2 <= this.capacity) {
            this.resize(commandList, this.capacity, requestBytes);
            return;
        }

        this.resize(commandList, this.growthTargetFor(required), requestBytes);
    }

    private int growthTargetFor(long required) {
        return growthTargetFor(required, this.stride, this.capacity, this.minimumCapacity);
    }

    static int growthTargetFor(long required, int stride, int capacity, int minimumCapacity) {
        long base = Math.max(required, capacity);
        long baseBytes = base * stride;

        long increment = baseBytes < LARGE_ARENA_THRESHOLD_BYTES ? base : base / LARGE_GROWTH_FACTOR;

        long target = Math.max(base + increment, minimumCapacity);

        if (required > Integer.MAX_VALUE) {
            throw new OutOfMemoryError("Arena cannot grow beyond " + Integer.MAX_VALUE + " elements");
        }

        return (int)Math.min(target, Integer.MAX_VALUE);
    }

    private void checkAssertions() {
        if (CHECK_ASSERTIONS) {
            this.checkAssertions0();
        }
    }

    private void checkAssertions0() {
        GlBufferSegment seg = this.head;
        int used = 0;

        while (seg != null) {
            if (seg.getOffset() < 0) {
                throw new IllegalStateException("segment.start < 0: out of bounds");
            } else if (seg.getEnd() > this.capacity) {
                throw new IllegalStateException("segment.end > arena.capacity: out of bounds");
            }

            if (!seg.isFree()) {
                used += seg.getLength();
            }

            GlBufferSegment next = seg.getNext();

            if (next != null) {
                if (next.getOffset() < seg.getEnd()) {
                    throw new IllegalStateException("segment.next.start < segment.end: overlapping segments (corrupted)");
                } else if (next.getOffset() > seg.getEnd()) {
                    throw new IllegalStateException("segment.next.start > segment.end: not truly connected (sparsity error)");
                }

                if (next.isFree() && next.getNext() != null) {
                    if (next.getNext().isFree()) {
                        throw new IllegalStateException("segment.free && segment.next.free: not merged consecutive segments");
                    }
                }
            }

            GlBufferSegment prev = seg.getPrev();

            if (prev != null) {
                if (prev.getEnd() > seg.getOffset()) {
                    throw new IllegalStateException("segment.prev.end > segment.start: overlapping segments (corrupted)");
                } else if (prev.getEnd() < seg.getOffset()) {
                    throw new IllegalStateException("segment.prev.end < segment.start: not truly connected (sparsity error)");
                }

                if (prev.isFree() && prev.getPrev() != null) {
                    if (prev.getPrev().isFree()) {
                        throw new IllegalStateException("segment.free && segment.prev.free: not merged consecutive segments");
                    }
                }
            }

            seg = next;
        }

        if (this.used < 0) {
            throw new IllegalStateException("arena.used < 0: failure to track");
        } else if (this.used > this.capacity) {
            throw new IllegalStateException("arena.used > arena.capacity: failure to track");
        }

        if (this.used != used) {
            throw new IllegalStateException("arena.used is invalid");
        }
    }

    /** Identifies the kind of region arena included in a resize diagnostic. */
    public enum ArenaType {
        /** Vertex data for terrain meshes. */
        GEOMETRY,
        /** Index data for terrain meshes. */
        INDEX,
        /** Any arena payload outside the standard geometry and index arenas. */
        OTHER
    }

    /**
     * Identifies the owning region for a resize event without expanding the normal arena
     * constructor into several unrelated diagnostic parameters.
     *
     * @param arenaType whether this arena stores geometry, indices, or another payload
     * @param regionId the manager-local id assigned to the owning render region
     * @param regionX the render-region x coordinate
     * @param regionY the render-region y coordinate
     * @param regionZ the render-region z coordinate
     */
    public record ArenaDiagnosticsContext(long ownerId, ArenaType arenaType, int regionId,
                                          int regionX, int regionY, int regionZ) {
        public ArenaDiagnosticsContext(ArenaType arenaType, int regionId, int regionX, int regionY, int regionZ) {
            this(0L, arenaType, regionId, regionX, regionY, regionZ);
        }
    }

    /** Observes a storage replacement immediately before the new OpenGL storage is allocated. */
    @FunctionalInterface
    public interface ResizeObserver {
        /**
         * Receives a compact event only when performance diagnostics are enabled.
         *
         * @param event immutable accounting for the old and requested arena capacities
         */
        void onResize(ResizeEvent event);

        /** Returns whether a bounded fence record can be kept for this replacement. */
        default boolean shouldTrackRetirement() {
            return false;
        }

        /** Receives a successfully inserted diagnostic fence, or a null fence with an unavailable reason. */
        default void onRetirementFenceSubmitted(ReplacementFenceEvent event) {
        }

        /** Records a coverage gap when the tracker has reached its documented retirement cap. */
        default void onRetirementCoverageSkipped(ReplacementFenceEvent event) {
        }

        /** Separates the command-list delete request from a later actual native delete callback. */
        default void onOldBufferDeleteRequested(ReplacementDeleteEvent event) {
        }
    }

    /** Identity and timing for one replacement fence inserted after copies and before the original old-store delete. */
    public record ReplacementFenceEvent(long ownerId, long arenaId, ResizeEvent resize,
                                        TerrainBufferDiagnostics.ResizeToken resizeToken,
                                        int oldBufferId, int newBufferId, GlFence fence,
                                        long submittedAtNanos, String unavailableReason) {
    }

    /** Identity and CPU timestamp for a normal command-list old-buffer delete request. */
    public record ReplacementDeleteEvent(long ownerId, long arenaId, ResizeEvent resize,
                                         TerrainBufferDiagnostics.ResizeToken resizeToken,
                                         int oldBufferId, int newBufferId, long requestedAtNanos) {
    }

    /**
     * Immutable byte accounting emitted before a terrain arena gets replacement storage.
     * The old and new capacities can coexist during the copy, but do not estimate all driver
     * allocations, retired backing stores, mappings, or implementation-specific copies.
     *
     * @param context region and arena identity
     * @param stride bytes per arena element
     * @param oldCapacityBytes currently reported capacity before replacement
     * @param newCapacityBytes requested capacity for replacement storage
     * @param usedBytes live segment bytes copied into replacement storage
     * @param requestBytes remaining upload payload bytes that triggered this resize
     * @param copySpanCount contiguous source spans copied from old to new storage
     */
    public record ResizeEvent(ArenaDiagnosticsContext context, int stride, long oldCapacityBytes,
                              long newCapacityBytes, long usedBytes, long requestBytes, int copySpanCount) {
    }

}
