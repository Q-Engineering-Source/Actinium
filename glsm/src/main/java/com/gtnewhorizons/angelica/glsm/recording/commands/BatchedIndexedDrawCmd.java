package com.gtnewhorizons.angelica.glsm.recording.commands;

import com.gtnewhorizons.angelica.glsm.GLStateManager;

/**
 * Placeholder command for one baked indexed draw. Recorded into the command stream as a
 * complex object at capture time and {@link #fill}ed once the {@link IndexedDrawBatch}
 * for its layout group has been built at the end of list compilation.
 */
public final class BatchedIndexedDrawCmd implements DisplayListCommand {
    private IndexedDrawBatch batch;
    private int drawMode;
    private int indexCount;
    private int indexType;
    private long indexByteOffset;

    public void fill(IndexedDrawBatch batch, int drawMode, int indexCount, int indexType, long indexByteOffset) {
        this.batch = batch;
        this.drawMode = drawMode;
        this.indexCount = indexCount;
        this.indexType = indexType;
        this.indexByteOffset = indexByteOffset;
    }

    public IndexedDrawBatch getBatch() { return batch; }
    public int getDrawMode() { return drawMode; }
    public int getIndexCount() { return indexCount; }
    public int getIndexType() { return indexType; }
    public long getIndexByteOffset() { return indexByteOffset; }

    @Override
    public void execute() {
        if (batch == null) {
            throw new IllegalStateException("Indexed display-list command was not assigned a batch");
        }
        final int array = batch.getVertexArray(GLStateManager.currentContextIdentity());
        GLStateManager.glBindVertexArray(array);
        GLStateManager.glDrawElements(drawMode, indexCount, indexType, indexByteOffset);
    }

    @Override
    public String toString() {
        return "BatchedIndexedDrawCmd(batch=" + batch + ", mode=" + drawMode + ", count=" + indexCount
            + ", type=" + indexType + ", offset=" + indexByteOffset + ")";
    }
}
