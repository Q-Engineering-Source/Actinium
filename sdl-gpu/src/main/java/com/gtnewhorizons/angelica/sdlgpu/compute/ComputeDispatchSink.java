package com.gtnewhorizons.angelica.sdlgpu.compute;

import com.gtnewhorizons.angelica.sdlgpu.frame.ContextState;

/** Records compute work while allowing voxel batches to preserve storage-buffer contents between regions. */
public interface ComputeDispatchSink {
    /** Opens an ordinary batch that may cycle writable storage buffers. */
    default long beginBatchedComputeDispatch(ContextState st) {
        return beginBatchedComputeDispatch(st, true);
    }

    /** Opens a compute pass, choosing whether writes may discard the previous buffer allocation. */
    long beginBatchedComputeDispatch(ContextState st, boolean cycleRwBuffers);

    /** Appends workgroups to the supplied compute pass. */
    void dispatchInBatch(long pass, int gx, int gy, int gz);

    /** Updates read-only storage bindings when a batch moves to another terrain region. */
    void rebindRoStorageBuffers(ContextState st, long pass);

    /** Ends the pass so later graphics or transfer commands can use its resources. */
    void endBatchedComputeDispatch(long pass);

    /** Uploads uniform changes required by the next dispatch in the batch. */
    void pushPendingComputeUniforms(ContextState st);
}
