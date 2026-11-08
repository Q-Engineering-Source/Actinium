package dhj.embeddedt.embeddium.impl.render.culling;

import dhj.embeddedt.embeddium.impl.render.viewport.CameraTransform;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Writes the frustum uniform block the compute cull pass tests sections against.
 *
 * <p>The block holds the six clip planes of {@code projection * modelView}, already offset by the padded section box and
 * the fractional part of the camera, so the shader only has to subtract the integer camera position from a section
 * origin and evaluate six dot products. Folding the padding into the plane constant is what keeps sections whose box
 * crosses the screen edge: their centre lies outside the exact frustum, but their padded box still reaches inside it.
 */
public final class FrustumExtractor {

    /** Bytes of the frustum uniform block; {@code chunk_cull.csh} declares the matching std140 block. */
    public static final int UBO_SIZE_BYTES = 160;
    public static final int PLANE_COUNT = 6;
    public static final int PLANE_ARRAY_OFFSET = 0;
    public static final int PLANE_STRIDE_BYTES = 16;

    public static final float SECTION_CENTER = 8.0f;
    /** Padding around a section box that keeps a box crossing a frustum plane from being culled. */
    public static final float SECTION_PADDING = 1.0f + 0.125f;
    /** Half extent of the padded section box, which is what the plane constant is offset by. */
    public static final float SECTION_HALF_EXTENT = SECTION_CENTER + SECTION_PADDING;

    public static final int CONTROL_OFFSET = 96;
    public static final int CONTROL_VISIBLE_COUNT_OFFSET = CONTROL_OFFSET;
    public static final int CONTROL_INDEX_MASK_OFFSET = CONTROL_OFFSET + 4;
    public static final int CONTROL_BYPASS_FRUSTUM_OFFSET = CONTROL_OFFSET + 8;

    public static final int CAMERA_BLOCK_OFFSET = 112;
    public static final int BATCH_OFFSET = 128;
    public static final int PYR_OFFSET = 144;
    public static final int PYR_VERTICES_PER_PRIMITIVE_OFFSET = PYR_OFFSET + 8;
    public static final int PYR_ELEMENTS_PER_PRIMITIVE_OFFSET = PYR_OFFSET + 12;

    private FrustumExtractor() {
    }

    public static ByteBuffer allocateUboByteBuffer() {
        return ByteBuffer.allocateDirect(UBO_SIZE_BYTES).order(ByteOrder.nativeOrder());
    }

    /**
     * Writes the six padded clip planes and the integer camera position.
     *
     * @param mvp scratch matrix that receives {@code projection * modelView}
     */
    public static void writeFrustum(Matrix4fc projection, Matrix4fc modelView, CameraTransform camera, Matrix4f mvp, ByteBuffer out) {
        projection.mul(modelView, mvp);
        final float r0x = mvp.m00(), r0y = mvp.m10(), r0z = mvp.m20(), r0w = mvp.m30();
        final float r1x = mvp.m01(), r1y = mvp.m11(), r1z = mvp.m21(), r1w = mvp.m31();
        final float r2x = mvp.m02(), r2y = mvp.m12(), r2z = mvp.m22(), r2w = mvp.m32();
        final float r3x = mvp.m03(), r3y = mvp.m13(), r3z = mvp.m23(), r3w = mvp.m33();
        final float cx = SECTION_CENTER - camera.fracX;
        final float cy = SECTION_CENTER - camera.fracY;
        final float cz = SECTION_CENTER - camera.fracZ;

        writePlane(out, PLANE_ARRAY_OFFSET, r3x + r0x, r3y + r0y, r3z + r0z, r3w + r0w, cx, cy, cz);
        writePlane(out, PLANE_ARRAY_OFFSET + PLANE_STRIDE_BYTES, r3x - r0x, r3y - r0y, r3z - r0z, r3w - r0w, cx, cy, cz);
        writePlane(out, PLANE_ARRAY_OFFSET + 2 * PLANE_STRIDE_BYTES, r3x + r1x, r3y + r1y, r3z + r1z, r3w + r1w, cx, cy, cz);
        writePlane(out, PLANE_ARRAY_OFFSET + 3 * PLANE_STRIDE_BYTES, r3x - r1x, r3y - r1y, r3z - r1z, r3w - r1w, cx, cy, cz);
        writePlane(out, PLANE_ARRAY_OFFSET + 4 * PLANE_STRIDE_BYTES, r3x + r2x, r3y + r2y, r3z + r2z, r3w + r2w, cx, cy, cz);
        writePlane(out, PLANE_ARRAY_OFFSET + 5 * PLANE_STRIDE_BYTES, r3x - r2x, r3y - r2y, r3z - r2z, r3w - r2w, cx, cy, cz);

        out.putInt(CAMERA_BLOCK_OFFSET, camera.intX);
        out.putInt(CAMERA_BLOCK_OFFSET + 4, camera.intY);
        out.putInt(CAMERA_BLOCK_OFFSET + 8, camera.intZ);
    }

    /**
     * Sets the base of this dispatch's visible-list entries, so several passes can share one dispatch batch.
     */
    public static void patchBatchEntryBase(int entryBase, ByteBuffer out) {
        out.putInt(BATCH_OFFSET, entryBase);
    }

    /**
     * Describes the primitive ratio the section fence posts are measured in. The values are small exact integers, so
     * the float round trip through the uniform block is lossless.
     */
    public static void patchPrimitiveRatio(int verticesPerPrimitive, int elementsPerPrimitive, ByteBuffer out) {
        out.putFloat(PYR_VERTICES_PER_PRIMITIVE_OFFSET, (float) verticesPerPrimitive);
        out.putFloat(PYR_ELEMENTS_PER_PRIMITIVE_OFFSET, (float) elementsPerPrimitive);
    }

    /**
     * Sets how many visible entries this dispatch covers, whether the pass keeps its index pointers
     * ({@code 0xFFFFFFFF}) or reads the shared index buffer from element zero, and whether the plane test is bypassed.
     */
    public static void patchControl(int visibleCount, int indexPointerMask, ByteBuffer out) {
        out.putInt(CONTROL_VISIBLE_COUNT_OFFSET, visibleCount);
        out.putInt(CONTROL_INDEX_MASK_OFFSET, indexPointerMask);
    }

    /**
     * Bypasses the plane test for passes whose render lists already hold only the sections to draw, such as the shadow
     * pass, whose frustum differs from the one the culler was built with.
     */
    public static void patchBypassFrustum(boolean bypass, ByteBuffer out) {
        out.putInt(CONTROL_BYPASS_FRUSTUM_OFFSET, bypass ? 1 : 0);
    }

    private static void writePlane(ByteBuffer out, int offset, float a, float b, float c, float d, float cx, float cy, float cz) {
        out.putFloat(offset, a);
        out.putFloat(offset + 4, b);
        out.putFloat(offset + 8, c);
        out.putFloat(offset + 12, d + SECTION_HALF_EXTENT * (Math.abs(a) + Math.abs(b) + Math.abs(c)) + a * cx + b * cy + c * cz);
    }
}
