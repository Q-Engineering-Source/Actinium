package dhj.embeddedt.embeddium.impl.gl.device;

/**
 * Creates the multidraw batch a region assembly uploads into. {@code BatchAssembler} uses this hook to override the
 * batch kind the configured {@code MultiDrawMode} would otherwise pick, so a GL integration test can pin indirect
 * multidraw without changing the mode the renderer runs with.
 */
@FunctionalInterface
public interface MultiDrawBatchFactory {
    /**
     * Creates a batch with room for at least {@code capacity} draw commands.
     */
    MultiDrawBatch create(int capacity);
}
