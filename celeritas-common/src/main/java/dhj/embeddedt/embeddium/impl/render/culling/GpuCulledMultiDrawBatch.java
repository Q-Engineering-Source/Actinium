package dhj.embeddedt.embeddium.impl.render.culling;

import dhj.embeddedt.embeddium.impl.gl.device.CommandList;
import dhj.embeddedt.embeddium.impl.gl.device.MultiDrawBatch;
import dhj.embeddedt.embeddium.impl.gl.tessellation.GlPrimitiveType;
import dhj.embeddedt.embeddium.impl.gl.tessellation.GlTessellation;

/**
 * A batch whose commands the compute cull pass wrote on the GPU, so the CPU side only carries the command range.
 *
 * <p>The range is the slice of the shared indirect buffer the region's pass occupies. Assembling a command stream here
 * would duplicate what the dispatch already produced, so every command-building entry point rejects.
 */
public final class GpuCulledMultiDrawBatch extends MultiDrawBatch {

    private final GpuTerrainCuller culler;
    private int drawStart;

    GpuCulledMultiDrawBatch(GpuTerrainCuller culler) {
        super(MAX_COMMAND_COUNT);
        this.culler = culler;
    }

    /**
     * Points this batch at one contiguous range of the shared indirect buffer.
     */
    void prepare(int drawStart, int drawCount, int maxElementCount) {
        this.drawStart = drawStart;
        this.size = drawCount;
        this.maxElementCount = maxElementCount;
    }

    @Override
    public void appendDrawCommand(int baseVertex, int elementCount, long elementPointer) {
        throw new UnsupportedOperationException("GPU-culled batches build their commands on the GPU");
    }

    @Override
    public void mergeIntoLastCommand(int additionalElementCount) {
        throw new UnsupportedOperationException("GPU-culled batches build their commands on the GPU");
    }

    @Override
    public void upload(CommandList commandList) {
    }

    @Override
    public void delete() {
    }

    @Override
    public void execute(CommandList commandList, GlTessellation tessellation, GlPrimitiveType primitiveType) {
        this.culler.drawRegionRange(commandList, tessellation, primitiveType, this.drawStart, this.size);
    }
}
