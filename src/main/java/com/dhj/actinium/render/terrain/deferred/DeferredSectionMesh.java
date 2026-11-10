package com.dhj.actinium.render.terrain.deferred;

import com.dhj.actinium.world.cloned.DeferredSlice;
import com.gtnewhorizon.gtnhlib.bytebuf.MemoryUtilities;
import dhj.embeddedt.embeddium.api.shader.buffer.VanillaQuadContext;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildBuffers;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildContext;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildOutput;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.executor.ChunkJobResult;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import dhj.embeddedt.embeddium.impl.util.task.CancellationToken;
import net.minecraft.util.BlockRenderLayer;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A section build that crossed from a build thread to the render thread and back.
 *
 * <p>The build thread scans a section snapshot and meshes every block it can; the blocks that have
 * to be rendered on the render thread are collected into {@link DeferredBlock}s. {@link #capture()}
 * runs on the render thread, renders those blocks and stores their raw vanilla-format vertices in a
 * {@link DeferredCapture}; {@link #finish(ChunkBuildContext)} then runs on a build thread and
 * encodes the capture into the final mesh.</p>
 *
 * <p>Threading: all fields are written by the render thread before the finish job is submitted and
 * are read-only afterwards, except {@link #capture} and {@link #mainNanos}, which the scheduler
 * assigns before capture. The scheduler owns the buffers, slice and capture, so the mesh itself
 * never releases them.</p>
 */
public final class DeferredSectionMesh {
    /**
     * The mesher that produced this mesh.
     *
     * <p>The scheduler only drives the mesh; what a section needs to capture and how the captured
     * geometry is encoded belongs to the mesher. The capturing side renders the vanilla buffers of
     * each deferred block, the finishing side re-encodes those bytes with the section's shader
     * metadata.</p>
     */
    public interface Handler {
        /**
         * {@return whether this mesh must be dropped}, which happens when the section was rebuilt,
         * disposed, or the build was cancelled while the mesh waited
         */
        boolean isObsolete();

        /**
         * Renders the deferred blocks of {@code mesh} into the mesh's {@link DeferredCapture}.
         * Called on the render thread, so vanilla block rendering is available.
         *
         * @return a failure to report instead of scheduling the finish, or {@code null} once the
         *         capture is complete
         */
        @Nullable
        ChunkJobResult<ChunkBuildOutput> captureDeferred(DeferredSectionMesh mesh);

        /**
         * Encodes the capture into the mesh buffers and returns the finished build output. Called
         * on a build thread with a pooled build context; the vintage mesher narrows
         * {@code context} to {@code VintageChunkBuildContext}.
         *
         * @return the build output, or {@code null} when the section turned out to be obsolete
         */
        @Nullable
        ChunkBuildOutput finishDeferred(DeferredSectionMesh mesh, ChunkBuildContext context);

        /**
         * Asks the handler to build {@code mesh} again later. The scheduler calls this on the render
         * thread after it drops a mesh to keep its normal queue bounded, so the handler may re-request
         * the section rebuild directly.
         */
        void retryLater(DeferredSectionMesh mesh);
    }

    private final Handler handler;

    /** Cancellation token of the build that handed this mesh over. */
    public final CancellationToken token;
    /** Buffers owned by the scheduler while this mesh exists. */
    public final ChunkBuildBuffers buffers;
    /** Snapshot owned by the scheduler while this mesh exists. */
    public final DeferredSlice slice;
    /** Whether the section update that produced this mesh was important. */
    public final boolean important;
    /** Squared distance to the camera, used to serve the nearest deferred section first. */
    public final long priority;
    /** Time the build thread spent before it handed the mesh over. */
    public final long workerNanos;

    /** Raw geometry captured on the render thread; set by the scheduler before {@link #capture()}. */
    @Nullable
    public DeferredCapture capture;
    /** Time the render thread spent capturing; set by the mesher during {@link #capture()}. */
    public long mainNanos;

    public DeferredSectionMesh(Handler handler, CancellationToken token, ChunkBuildBuffers buffers,
                               DeferredSlice slice, boolean important, long priority, long workerNanos) {
        this.handler = handler;
        this.token = token;
        this.buffers = buffers;
        this.slice = slice;
        this.important = important;
        this.priority = priority;
        this.workerNanos = workerNanos;
    }

    /** {@return whether this mesh must be dropped instead of being captured or finished} */
    public boolean isObsolete() {
        return this.handler.isObsolete();
    }

    /**
     * Renders the deferred blocks into {@link #capture} on the render thread, returning a failure to
     * report instead of scheduling the finish.
     */
    @Nullable
    public ChunkJobResult<ChunkBuildOutput> capture() {
        return this.handler.captureDeferred(this);
    }

    /** Encodes the capture into the final build output on a build thread. */
    @Nullable
    public ChunkBuildOutput finish(ChunkBuildContext context) {
        return this.handler.finishDeferred(this, context);
    }

    /** Asks the handler to build this section again later; called on the render thread. */
    public void retryLater() {
        this.handler.retryLater(this);
    }

    /** Total time this mesh occupied the executor, both before and after the handoff. */
    public long totalNanos() {
        return this.workerNanos + this.mainNanos;
    }

    /**
     * Raw vanilla-format vertex data of one deferred mesh section.
     *
     * <p>An entry is the byte range one captured block render produced in the render thread's buffer
     * builder, together with the material and layer it belongs to and the shader metadata of its
     * quads. The bytes live in one growable native block, so a capture keeps its capacity across
     * sections instead of allocating per block, and the block is freed explicitly by
     * {@link #dispose()} rather than by the garbage collector.</p>
     *
     * <p>Threading: written by the render thread during capture, read by one build thread during
     * finish. Ownership: the pool that hands a capture out owns it until it is released.</p>
     */
    public static final class DeferredCapture {
        private static final int INITIAL_CAPACITY = 64 * 1024;

        /**
         * Capacity kept when a capture that grew far beyond a typical section is cleared. Retaining a
         * huge block would keep that native memory for the lifetime of the pool.
         */
        private static final int MAX_RETAINED_CAPACITY = 2 * 1024 * 1024;

        private final List<Entry> entries = new ArrayList<>();

        @Nullable
        private ByteBuffer data;
        private int size;

        public DeferredCapture() {
            this.data = MemoryUtilities.memAlloc(INITIAL_CAPACITY);
        }

        /**
         * Appends the bytes one block render produced.
         *
         * @param material material the captured quads are encoded with
         * @param layer vanilla render layer the block was rendered into
         * @param source buffer builder content; the bytes from its position to its limit are copied
         *               out, and its position advances past them
         * @param quadContexts shader metadata of the captured quads, in quad order
         */
        public void add(Material material, BlockRenderLayer layer, ByteBuffer source, List<VanillaQuadContext> quadContexts) {
            requireUsable();

            if (!source.isDirect()) {
                throw new IllegalArgumentException(
                        "Captured block vertices must come from a direct buffer, got a heap buffer of " + source.capacity() + " bytes");
            }

            int length = source.remaining();
            int offset = this.size;
            int sourcePosition = source.position();

            ensureCapacity(offset + length);

            MemoryUtilities.memCopy(
                    MemoryUtilities.memAddress(source, sourcePosition),
                    MemoryUtilities.memAddress(data(), offset),
                    length);

            source.position(sourcePosition + length);
            this.size = offset + length;
            this.entries.add(new Entry(material, layer, offset, length, List.copyOf(quadContexts)));
        }

        /** {@return the number of captured block renders} */
        public int size() {
            return this.entries.size();
        }

        /** {@return the entry of the captured block render at {@code index}} */
        public Entry entry(int index) {
            return this.entries.get(index);
        }

        /**
         * {@return a read-only view of exactly the bytes of {@code entry}}, which is what the finisher
         * hands to the block data encoder
         */
        public ByteBuffer view(Entry entry) {
            return data().duplicate()
                    .position(entry.offset())
                    .limit(entry.offset() + entry.length())
                    .slice()
                    .asReadOnlyBuffer();
        }

        /** {@return the capacity of the native block currently owned by this capture} */
        public int capacity() {
            ByteBuffer target = this.data;
            return target != null ? target.capacity() : 0;
        }

        /** Drops the captured entries and keeps the native block for the next section. */
        public void clear() {
            ByteBuffer target = data();

            this.entries.clear();
            this.size = 0;

            if (target.capacity() > MAX_RETAINED_CAPACITY) {
                this.data = MemoryUtilities.memRealloc(target, MAX_RETAINED_CAPACITY);
            }
        }

        /** Frees the native block. Later calls do nothing; using the capture afterwards is an error. */
        public void dispose() {
            ByteBuffer target = this.data;

            if (target == null) {
                return;
            }

            this.data = null;
            this.size = 0;
            this.entries.clear();
            MemoryUtilities.memFree(target);
        }

        private void requireUsable() {
            if (this.data == null) {
                throw new IllegalStateException("Capture has been disposed");
            }
        }

        private ByteBuffer data() {
            requireUsable();
            return this.data;
        }

        private void ensureCapacity(int required) {
            ByteBuffer target = data();

            if (required <= target.capacity()) {
                return;
            }

            long doubled = Math.min((long) target.capacity() * 2, MAX_RETAINED_CAPACITY);
            int capacity = (int) Math.max(required, doubled);

            this.data = MemoryUtilities.memRealloc(target, capacity);
        }

        /**
         * One captured block render.
         *
         * @param material material the quads are encoded with
         * @param layer vanilla render layer the block was rendered into
         * @param offset byte offset of the vertices inside the capture's native block
         * @param length byte length of the vertices
         * @param quadContexts shader metadata of the captured quads, in quad order
         */
        public record Entry(Material material, BlockRenderLayer layer, int offset, int length,
                            List<VanillaQuadContext> quadContexts) {
        }
    }
}
