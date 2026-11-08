package dhj.embeddedt.embeddium.impl.render.viewport.frustum;

import org.joml.FrustumIntersection;

public final class SimpleFrustum implements Frustum {
    private final FrustumIntersection frustum;

    public SimpleFrustum(FrustumIntersection frustumIntersection) {
        this.frustum = frustumIntersection;
    }

    public FrustumIntersection getFrustumIntersection() {
        return this.frustum;
    }

    @Override
    public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return this.frustum.testAab(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Uses JOML's detailed AABB result so callers can distinguish full containment. */
    @Override
    public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return switch (this.frustum.intersectAab(minX, minY, minZ, maxX, maxY, maxZ)) {
            case FrustumIntersection.INSIDE -> Frustum.FULLY_INSIDE;
            case FrustumIntersection.INTERSECT -> Frustum.PARTIALLY_INSIDE;
            default -> Frustum.OUTSIDE;
        };
    }
}
