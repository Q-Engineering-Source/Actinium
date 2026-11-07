package dhj.embeddedt.embeddium.impl.gl.device;

import dhj.embeddedt.embeddium.impl.gl.buffer.GlMutableBuffer;
import dhj.embeddedt.embeddium.impl.gl.tessellation.GlPrimitiveType;
import dhj.embeddedt.embeddium.impl.gl.tessellation.GlTessellation;
import dhj.embeddedt.embeddium.impl.model.quad.properties.ModelQuadFacing;
import dhj.embeddedt.embeddium.impl.render.chunk.region.RenderRegion;
import org.jetbrains.annotations.Nullable;

/**
 * A fixed-size queue for building a draw-command list, usable with either direct or indirect multidraw.
 * Concrete subclasses own the storage format the underlying GL call expects, and how to execute it.
 */
public abstract class MultiDrawBatch {
    public static final int MAX_COMMAND_COUNT = (ModelQuadFacing.COUNT * RenderRegion.REGION_SIZE) + 1;

    private final int capacity;

    protected int size;

    protected int maxElementCount;

    protected MultiDrawBatch(int capacity) {
        // Always allow room for one extra command, to allow branchless tricks
        this.capacity = capacity + 1;
    }

    public final int size() {
        return this.size;
    }

    public final int capacity() {
        return this.capacity;
    }

    public void clear() {
        this.size = 0;
        this.maxElementCount = 0;
    }

    public abstract void delete();

    public final boolean isEmpty() {
        return this.size <= 0;
    }

    public final int getIndexBufferSize() {
        return this.maxElementCount;
    }

    /**
     * Appends one draw command to the batch. If {@code elementCount} is 0, the command is discarded
     * and {@link #size()} does not advance.
     */
    public abstract void appendDrawCommand(int baseVertex, int elementCount, long elementPointer);

    /**
     * Adds {@code additionalElementCount} elements onto the most recently appended command, for
     * coalescing adjacent runs. Only valid when {@code size() > 0}.
     */
    public abstract void mergeIntoLastCommand(int additionalElementCount);

    /**
     * Must be called once assembly of the batch is complete, before the batch is {@link #execute}d for the
     * first time (possibly repeatedly, across frames, if cached).
     */
    public abstract void upload(CommandList commandList);

    /**
     * Whether {@link #upload} puts the commands into a GL buffer owned by this batch, and can therefore take over
     * a buffer that a discarded batch no longer needs.
     */
    public boolean usesGpuBuffer() {
        return false;
    }

    /**
     * Hands this batch an existing GL buffer to upload into, instead of letting it create one. Only valid before
     * {@link #upload} and only for a batch that {@link #usesGpuBuffer()}.
     */
    public void adoptBuffer(GlMutableBuffer buffer) {
        throw new UnsupportedOperationException("This batch keeps no GL buffer");
    }

    /**
     * Takes the GL buffer away from this batch so that {@link #delete()} leaves it alive for another batch. The
     * batch cannot be uploaded or executed afterwards.
     *
     * @return the buffer this batch uploaded into, or {@code null} when it has none
     */
    public @Nullable GlMutableBuffer detachBuffer() {
        return null;
    }

    /**
     * Issues the multidraw call for this batch against the currently bound {@code tessellation}.
     */
    public abstract void execute(CommandList commandList, GlTessellation tessellation, GlPrimitiveType primitiveType);
}
