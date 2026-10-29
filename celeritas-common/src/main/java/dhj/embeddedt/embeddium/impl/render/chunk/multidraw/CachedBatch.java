package dhj.embeddedt.embeddium.impl.render.chunk.multidraw;

import dhj.embeddedt.embeddium.impl.gl.device.MultiDrawBatch;
import dhj.embeddedt.embeddium.impl.gl.tessellation.GlTessellation;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

public class CachedBatch {
    private final @Nullable MultiDrawBatch batch;

    private final @Nullable GlTessellation tessellation;

    private final byte[] sections;

    private final int cameraMinX, cameraMaxX;
    private final int cameraMinY, cameraMaxY;
    private final int cameraMinZ, cameraMaxZ;

    public CachedBatch(@Nullable MultiDrawBatch batch,
                       @Nullable GlTessellation tessellation,
                       byte[] sections, int sectionCount,
                       int cameraMinX, int cameraMaxX,
                       int cameraMinY, int cameraMaxY,
                       int cameraMinZ, int cameraMaxZ) {
        this.batch = batch;
        this.tessellation = tessellation;
        this.sections = Arrays.copyOf(sections, sectionCount);
        this.cameraMinX = cameraMinX;
        this.cameraMaxX = cameraMaxX;
        this.cameraMinY = cameraMinY;
        this.cameraMaxY = cameraMaxY;
        this.cameraMinZ = cameraMinZ;
        this.cameraMaxZ = cameraMaxZ;
    }

    public boolean isValidFor(byte[] sections, int sectionCount, int cameraX, int cameraY, int cameraZ) {
        return cameraX >= this.cameraMinX && cameraX < this.cameraMaxX
                && cameraY >= this.cameraMinY && cameraY < this.cameraMaxY
                && cameraZ >= this.cameraMinZ && cameraZ < this.cameraMaxZ
                && sectionCount == this.sections.length
                && Arrays.equals(this.sections, 0, sectionCount, sections, 0, sectionCount);
    }

    public @Nullable MultiDrawBatch getBatch() {
        return this.batch;
    }

    /**
     * {@return the tessellation this batch was assembled against, or {@code null} when the batch is empty}
     *
     * <p>Assembling the batch is when the region's arena and vertex format are first known to be needed, so the
     * tessellation is built there and reused for as long as the batch stays cached.</p>
     */
    public @Nullable GlTessellation getTessellation() {
        return this.tessellation;
    }

    public void delete() {
        if (this.batch != null) {
            this.batch.delete();
        }
    }
}
