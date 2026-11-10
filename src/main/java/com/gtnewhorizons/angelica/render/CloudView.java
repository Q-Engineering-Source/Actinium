package com.gtnewhorizons.angelica.render;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * Camera state the cloud renderer culls and orients by: the projection scale, the offset from the view
 * entity to the matrix camera, the view direction, and how much of the disc the cull test can drop.
 *
 * <p>The instance is reused every frame through {@link #update}; matrices are read in the column major
 * layout JOML uses, which is also the layout the rest of the render stack hands over.
 */
final class CloudView {

    /** Scratch inverse of the model view matrix, kept to avoid allocating one per frame. */
    private final Matrix4f inverseModelView = new Matrix4f();
    /** Scratch camera position in world space. */
    private final Vector3f cameraOffset = new Vector3f();
    /** Forward direction of the previous frame, used to skip the trigonometric facing update. */
    private float lastForwardX = Float.NaN, lastForwardZ = Float.NaN;
    /** Largest on screen pixel scale of the projection, zero when the projection cannot supply one. */
    double pixelsPerRadian;
    /** Yaw of the camera forward direction in radians, the angle the cloud quads are oriented by. */
    double facing;
    /** Offset from the view entity's interpolated X position to the matrix camera X position. */
    float offsetX;
    /** Offset from the view entity's interpolated Y position to the matrix camera Y position. */
    float offsetY;
    /** Offset from the view entity's interpolated Z position to the matrix camera Z position. */
    float offsetZ;
    /** Squared sine of half the diagonal field of view, the angular extent the cull test works with. */
    private double sinHalfFovSquared;
    /** True when the matrices cannot be used for the cull test, which then keeps the whole disc. */
    private boolean uncullable;

    /**
     * Reads the frame matrices.
     *
     * @param projection projection matrix of the frame, expected to be a plain perspective frustum
     * @param modelView world to view matrix of the camera
     * @param width viewport width in pixels
     * @param height viewport height in pixels
     */
    void update(Matrix4fc projection, Matrix4fc modelView, int width, int height) {
        pixelsPerRadian = CloudDisc.pixelsPerRadian(width, height, projection);
        final boolean projectionCullable = updateProjection(projection);
        final boolean cameraCullable = updateCamera(modelView);
        uncullable = !projectionCullable || !cameraCullable;
    }

    /**
     * Derives the diagonal half field of view from the projection. Anything that is not a centred
     * perspective frustum (orthographic, off axis, degenerate) disables the cull test.
     */
    private boolean updateProjection(Matrix4fc projection) {
        if (!projection.isFinite() || projection.m23() >= 0.0f || projection.m33() != 0.0f
            || projection.m03() != 0.0f || projection.m13() != 0.0f
            || projection.m30() != 0.0f || projection.m31() != 0.0f
            || projection.m01() != 0.0f || projection.m10() != 0.0f
            || projection.m00() == 0.0f || projection.m11() == 0.0f) return false;

        final double tanX = (-projection.m23() + Math.abs(projection.m20())) / Math.abs(projection.m00());
        final double tanY = (-projection.m23() + Math.abs(projection.m21())) / Math.abs(projection.m11());
        final double tanSquared = tanX * tanX + tanY * tanY;
        sinHalfFovSquared = tanSquared / (1.0 + tanSquared);
        return Double.isFinite(sinHalfFovSquared);
    }

    /**
     * Inverts the model view to recover the camera position and reads the camera forward direction out of
     * it. A matrix that is not a rigid transform (for example one carrying a GUI scale) disables the cull
     * test, because the projection scale could not be combined with it.
     */
    private boolean updateCamera(Matrix4fc modelView) {
        offsetX = offsetY = offsetZ = 0.0f;
        if (!modelView.isFinite() || !modelView.isAffine()) return false;
        modelView.invertAffine(inverseModelView).getTranslation(cameraOffset);
        if (!cameraOffset.isFinite()) return false;
        offsetX = cameraOffset.x;
        offsetY = cameraOffset.y;
        offsetZ = cameraOffset.z;
        if (!rigid(modelView)) return false;
        final float forwardX = -modelView.m02(), forwardZ = -modelView.m22();
        if (forwardX != lastForwardX || forwardZ != lastForwardZ) {
            facing = Math.atan2(forwardZ, forwardX);
            lastForwardX = forwardX;
            lastForwardZ = forwardZ;
        }
        return true;
    }

    /**
     * Half angular span around {@link #facing} that can still show clouds, used as the angular half width of
     * the wedge fan.
     *
     * @param heightCells vertical distance from the camera to the cloud layer in cells, which widens the
     *                   span because a high layer is seen under a steeper angle
     * @param driftCells how far the camera sits from the cell grid, which the span has to cover on one side
     * @return the half span in radians, capped at a quarter turn when the cull test cannot be trusted
     */
    double halfSpan(double heightCells, double driftCells) {
        final double radius = CloudDisc.ALWAYS_DRAWN_CELLS - 2.0 - driftCells;
        if (uncullable || radius <= 0.0 || driftCells * 2.0 >= CloudDisc.ALWAYS_DRAWN_CELLS) return Math.PI;
        final double slope = heightCells / radius;
        final double spreadSquared = sinHalfFovSquared * (1.0 + slope * slope);
        if (spreadSquared >= 1.0) return Math.PI;
        return Math.asin(Math.sqrt(spreadSquared)) + CloudDisc.driftAngle(driftCells);
    }

    /** Whether the rotation part of the matrix is orthonormal to within matrix float precision. */
    private static boolean rigid(Matrix4fc matrix) {
        if ((matrix.properties() & Matrix4fc.PROPERTY_ORTHONORMAL) != 0) return true;
        final double xx = matrix.m00() * matrix.m00() + matrix.m01() * matrix.m01() + matrix.m02() * matrix.m02();
        final double yy = matrix.m10() * matrix.m10() + matrix.m11() * matrix.m11() + matrix.m12() * matrix.m12();
        final double zz = matrix.m20() * matrix.m20() + matrix.m21() * matrix.m21() + matrix.m22() * matrix.m22();
        final double xy = matrix.m00() * matrix.m10() + matrix.m01() * matrix.m11() + matrix.m02() * matrix.m12();
        final double xz = matrix.m00() * matrix.m20() + matrix.m01() * matrix.m21() + matrix.m02() * matrix.m22();
        final double yz = matrix.m10() * matrix.m20() + matrix.m11() * matrix.m21() + matrix.m12() * matrix.m22();
        return Math.abs(xx - 1.0) < 1.0e-4 && Math.abs(yy - 1.0) < 1.0e-4 && Math.abs(zz - 1.0) < 1.0e-4
            && Math.abs(xy) < 1.0e-4 && Math.abs(xz) < 1.0e-4 && Math.abs(yz) < 1.0e-4;
    }
}
