package dhj.embeddedt.embeddium.impl.gl.device;

import dhj.embeddedt.embeddium.impl.gl.buffer.GlBufferTarget;
import dhj.embeddedt.embeddium.impl.gl.buffer.GlBufferUsage;
import dhj.embeddedt.embeddium.impl.gl.buffer.GlMutableBuffer;
import dhj.embeddedt.embeddium.impl.gl.tessellation.GlIndexType;
import dhj.embeddedt.embeddium.impl.gl.tessellation.GlPrimitiveType;
import dhj.embeddedt.embeddium.impl.gl.tessellation.GlTessellation;

import static com.mitchej123.lwjgl.LWJGLServiceProvider.LWJGL;
import com.mitchej123.lwjgl.LWJGLServiceProvider;

/**
 * A {@link MultiDrawBatch} that builds commands directly into the layout expected by
 * {@code glMultiDrawElementsIndirect}, then uploads them to its own GPU buffer once assembly is done.
 */
public final class IndirectMultiDrawBatch extends MultiDrawBatch {
    // uint  count;
    // uint  instanceCount;
    // uint  firstIndex;
    // int  baseVertex;
    // uint  baseInstance;
    private static final int COMMAND_SIZE = 4 * 5;

    private long pCommands;
    // Created in upload() unless adoptBuffer() handed over the buffer of a discarded batch.
    private GlMutableBuffer bufferObject;

    public IndirectMultiDrawBatch(int capacity) {
        super(capacity);

        int allocCount = this.capacity();

        this.pCommands = LWJGL.nmemAlignedAlloc(32, (long) allocCount * COMMAND_SIZE);
        if (this.pCommands == LWJGLServiceProvider.NULL) {
            throw new OutOfMemoryError("Failed to allocate indirect command buffer");
        }

        // Prefill the constants that never change between commands.
        long ptr = this.pCommands;
        for (int i = 0; i < allocCount; i++) {
            LWJGL.memPutInt(ptr + 4L, 1); // instanceCount
            LWJGL.memPutInt(ptr + 16L, 0); // baseInstance
            ptr += COMMAND_SIZE;
        }
    }

    @Override
    public boolean usesGpuBuffer() {
        return true;
    }

    @Override
    public void adoptBuffer(GlMutableBuffer buffer) {
        if (this.bufferObject != null) {
            throw new IllegalStateException("Batch already has a buffer");
        }

        this.bufferObject = buffer;
    }

    @Override
    public GlMutableBuffer detachBuffer() {
        GlMutableBuffer buffer = this.bufferObject;
        this.bufferObject = null;
        return buffer;
    }

    @Override
    public void appendDrawCommand(int baseVertex, int elementCount, long elementPointer) {
        int index = this.size;

        long ptr = this.pCommands + ((long) index * COMMAND_SIZE);

        LWJGL.memPutInt(ptr + 0L, elementCount); // count
        LWJGL.memPutInt(ptr + 8L, (int) (elementPointer / 4L)); // firstIndex
        LWJGL.memPutInt(ptr + 12L, baseVertex); // baseVertex

        // Element counts are never negative, so the sign bit of the negation is set iff the command is non-empty.
        this.size = index + ((-elementCount) >>> 31);

        // Safe to apply unconditionally: an empty command cannot raise the maximum.
        this.maxElementCount = Math.max(this.maxElementCount, elementCount);
    }

    @Override
    public void mergeIntoLastCommand(int additionalElementCount) {
        long countPointer = this.pCommands + ((long) (this.size - 1) * COMMAND_SIZE);
        int mergedCount = LWJGL.memGetInt(countPointer) + additionalElementCount;

        LWJGL.memPutInt(countPointer, mergedCount);
        this.maxElementCount = Math.max(this.maxElementCount, mergedCount);
    }

    @Override
    public void upload(CommandList commandList) {
        if (this.bufferObject == null) {
            this.bufferObject = new GlMutableBuffer();
        }

        // Must stay a full glBufferData re-specification for an adopted buffer: it orphans the previous storage,
        // so draws still queued against it do not stall this upload.
        commandList.uploadData(this.bufferObject, this.pCommands, (long) this.size * COMMAND_SIZE,
                GlBufferUsage.STATIC_DRAW, GlBufferTarget.DRAW_INDIRECT_BUFFER);

        // Nothing after this point needs the CPU-side copy.
        LWJGL.nmemAlignedFree(this.pCommands);
        this.pCommands = LWJGLServiceProvider.NULL;
    }

    @Override
    public void delete() {
        if (this.pCommands != LWJGLServiceProvider.NULL) {
            LWJGL.nmemAlignedFree(this.pCommands);
            this.pCommands = LWJGLServiceProvider.NULL;
        }
        if (this.bufferObject != null) {
            this.bufferObject.delete();
            this.bufferObject = null;
        }
    }

    @Override
    public void execute(CommandList commandList, GlTessellation tessellation, GlPrimitiveType primitiveType) {
        commandList.bindBuffer(GlBufferTarget.DRAW_INDIRECT_BUFFER, this.bufferObject);
        try (DrawCommandList drawCommandList = commandList.beginTessellating(tessellation)) {
            drawCommandList.multiDrawElementsIndirect(this.bufferObject, this.size(), primitiveType, GlIndexType.UNSIGNED_INT);
        }
        commandList.bindBuffer(GlBufferTarget.DRAW_INDIRECT_BUFFER, null);
    }
}
