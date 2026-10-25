package dhj.embeddedt.embeddium.impl.gl.arena.staging;

/** Keeps intermediate arena flushes synchronous while coalescing only their completion fences. */
public final class StagingFenceBatchImpl implements StagingFenceBatch {
    private int depth;
    private int transferredBytes;

    @Override
    public void begin() {
        depth++;
    }

    @Override
    public void end() {
        if (depth == 0) {
            throw new IllegalStateException("No staging upload batch to close");
        }
        depth--;
    }

    @Override
    public void addTransferredBytes(int bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("Negative staging transfer size");
        }
        transferredBytes = Math.addExact(transferredBytes, bytes);
    }

    @Override
    public int takeFenceBytes() {
        if (depth != 0) {
            return 0;
        }
        int bytes = transferredBytes;
        transferredBytes = 0;
        return bytes;
    }
}
