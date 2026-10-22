package dhj.embeddedt.embeddium.impl.render.chunk.compile;

import dhj.embeddedt.embeddium.impl.render.chunk.RenderSection;

public abstract class ChunkTaskOutput {
    public final RenderSection render;
    public final int buildTime;

    protected ChunkTaskOutput(RenderSection render, int buildTime) {
        this.render = render;
        this.buildTime = buildTime;
    }

    public void delete() {

    }

    /** Returns the native allocation size owned by this result while it is awaiting upload or cleanup. */
    public long getNativeMemoryBytes() {
        return 0;
    }
}
