package com.gtnewhorizons.angelica.glsm.debug;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.hooks.GpuCheckpointType;
import com.gtnewhorizons.angelica.glsm.hooks.GpuCommandRecorder;
import org.lwjgl.opengl.GL32;

/**
 * Maintains fixed per-thread, per-context rings of non-blocking GPU completion fences.
 */
public final class GpuCheckpointTracker {
    static final int CHECKPOINT_CAPACITY = 64;
    static final int CONTEXT_CAPACITY = 4;
    static final long OVERFLOW_CHECKPOINT_ID = -1L;
    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);

    private GpuCheckpointTracker() {
    }

    /**
     * Polls existing fences without waiting and inserts a new fence for the supplied command.
     *
     * @param recorder destination for checkpoint lifecycle breadcrumbs
     * @param commandCode stable command or pass-boundary code
     */
    public static void checkpoint(GpuCommandRecorder recorder, int commandCode) {
        STATE.get().checkpoint(recorder, commandCode, GLStateManager.currentContextCapabilities(), LwjglGpuSyncOperations.INSTANCE);
    }

    /**
     * Fixed-capacity checkpoint state owned by one issuing thread.
     */
    static final class State {
        private final Object[] contexts = new Object[CONTEXT_CAPACITY];
        private final int[] heads = new int[CONTEXT_CAPACITY];
        private final int[] counts = new int[CONTEXT_CAPACITY];
        private final boolean[] overflowRecorded = new boolean[CONTEXT_CAPACITY];
        private final long[] syncs = new long[CONTEXT_CAPACITY * CHECKPOINT_CAPACITY];
        private final long[] checkpointIds = new long[CONTEXT_CAPACITY * CHECKPOINT_CAPACITY];
        private final int[] commandCodes = new int[CONTEXT_CAPACITY * CHECKPOINT_CAPACITY];
        private long nextCheckpointId;
        private boolean contextOverflowRecorded;

        /**
         * Polls and issues a checkpoint using the ring assigned to the supplied context identity.
         *
         * @param recorder destination for lifecycle breadcrumbs
         * @param commandCode stable command or pass-boundary code
         * @param context current OpenGL context identity
         * @param syncOperations non-blocking native sync operations
         */
        void checkpoint(
            GpuCommandRecorder recorder,
            int commandCode,
            Object context,
            GpuSyncOperations syncOperations
        ) {
            int contextSlot = findContextSlot(context);
            if (contextSlot == -1) {
                if (!contextOverflowRecorded) {
                    contextOverflowRecorded = true;
                    recorder.checkpoint(GpuCheckpointType.OVERFLOW, OVERFLOW_CHECKPOINT_ID, commandCode);
                }
                return;
            }
            poll(recorder, contextSlot, syncOperations);
            issue(recorder, commandCode, contextSlot, syncOperations);
        }

        private int findContextSlot(Object context) {
            int reusableSlot = -1;
            for (int slot = 0; slot < CONTEXT_CAPACITY; slot++) {
                if (contexts[slot] == context) {
                    return slot;
                }
                if (reusableSlot == -1 && (contexts[slot] == null || counts[slot] == 0)) {
                    reusableSlot = slot;
                }
            }
            if (reusableSlot != -1) {
                contexts[reusableSlot] = context;
                heads[reusableSlot] = 0;
                overflowRecorded[reusableSlot] = false;
                contextOverflowRecorded = false;
            }
            return reusableSlot;
        }

        private void poll(GpuCommandRecorder recorder, int contextSlot, GpuSyncOperations syncOperations) {
            int offset = contextSlot * CHECKPOINT_CAPACITY;
            int count = counts[contextSlot];
            int head = heads[contextSlot];
            while (count > 0) {
                int index = offset + head;
                long sync = syncs[index];
                long checkpointId = checkpointIds[index];
                int commandCode = commandCodes[index];
                recorder.checkpoint(GpuCheckpointType.WAIT_CALL_BEGIN, checkpointId, commandCode);
                GpuSyncStatus status = syncOperations.poll(sync);
                recorder.checkpoint(waitResultType(status), checkpointId, commandCode);
                if (status == GpuSyncStatus.PENDING) {
                    break;
                }
                recorder.checkpoint(GpuCheckpointType.DELETE_CALL_BEGIN, checkpointId, commandCode);
                syncOperations.delete(sync);
                recorder.checkpoint(GpuCheckpointType.DELETE_CALL_RETURNED, checkpointId, commandCode);
                recorder.checkpoint(
                    status == GpuSyncStatus.COMPLETED ? GpuCheckpointType.COMPLETED : GpuCheckpointType.FAILED,
                    checkpointId,
                    commandCode
                );
                head = (head + 1) % CHECKPOINT_CAPACITY;
                count--;
            }
            heads[contextSlot] = head;
            counts[contextSlot] = count;
            if (count < CHECKPOINT_CAPACITY) {
                overflowRecorded[contextSlot] = false;
            }
        }

        private void issue(
            GpuCommandRecorder recorder,
            int commandCode,
            int contextSlot,
            GpuSyncOperations syncOperations
        ) {
            int count = counts[contextSlot];
            if (count == CHECKPOINT_CAPACITY) {
                if (!overflowRecorded[contextSlot]) {
                    overflowRecorded[contextSlot] = true;
                    recorder.checkpoint(GpuCheckpointType.OVERFLOW, OVERFLOW_CHECKPOINT_ID, commandCode);
                }
                return;
            }

            long checkpointId = nextCheckpointId++;
            recorder.checkpoint(GpuCheckpointType.FENCE_CALL_BEGIN, checkpointId, commandCode);
            long sync = syncOperations.fence();
            recorder.checkpoint(GpuCheckpointType.FENCE_CALL_RETURNED, checkpointId, commandCode);
            if (sync == 0L) {
                recorder.checkpoint(GpuCheckpointType.FAILED, checkpointId, commandCode);
                return;
            }
            int index = contextSlot * CHECKPOINT_CAPACITY
                + (heads[contextSlot] + count) % CHECKPOINT_CAPACITY;
            syncs[index] = sync;
            checkpointIds[index] = checkpointId;
            commandCodes[index] = commandCode;
            counts[contextSlot] = count + 1;
            recorder.checkpoint(GpuCheckpointType.ISSUED, checkpointId, commandCode);
        }

        private static GpuCheckpointType waitResultType(GpuSyncStatus status) {
            return switch (status) {
                case PENDING -> GpuCheckpointType.WAIT_CALL_PENDING;
                case COMPLETED -> GpuCheckpointType.WAIT_CALL_COMPLETED;
                case FAILED -> GpuCheckpointType.WAIT_CALL_FAILED;
            };
        }
    }

    /**
     * Supplies the three native sync operations needed by the fixed checkpoint state machine.
     */
    interface GpuSyncOperations {
        /**
         * Inserts a fence after all commands already submitted to the current context.
         *
         * @return native sync handle, or zero when fence creation failed
         */
        long fence();

        /**
         * Polls a fence without waiting.
         *
         * @param sync native sync handle
         * @return current completion status
         */
        GpuSyncStatus poll(long sync);

        /**
         * Deletes a fence that completed or failed.
         *
         * @param sync native sync handle
         */
        void delete(long sync);
    }

    /**
     * Normalizes native zero-timeout wait results for the fixed checkpoint state machine.
     */
    enum GpuSyncStatus {
        /** The fence is not signaled yet. */
        PENDING,
        /** The fence has completed. */
        COMPLETED,
        /** The driver reported a failed wait. */
        FAILED
    }

    private enum LwjglGpuSyncOperations implements GpuSyncOperations {
        INSTANCE;

        @Override
        public long fence() {
            return GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        }

        @Override
        public GpuSyncStatus poll(long sync) {
            int result = GL32.glClientWaitSync(sync, 0, 0L);
            if (result == GL32.GL_ALREADY_SIGNALED || result == GL32.GL_CONDITION_SATISFIED) {
                return GpuSyncStatus.COMPLETED;
            }
            if (result == GL32.GL_TIMEOUT_EXPIRED) {
                return GpuSyncStatus.PENDING;
            }
            if (result == GL32.GL_WAIT_FAILED) {
                return GpuSyncStatus.FAILED;
            }
            throw new IllegalStateException("Unexpected glClientWaitSync result: " + result);
        }

        @Override
        public void delete(long sync) {
            GL32.glDeleteSync(sync);
        }
    }
}
