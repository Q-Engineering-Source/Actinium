package net.minecraft.client.renderer.culling;

/**
 * Test-runtime replacement for the OpenGL-backed vanilla frustum initializer.
 * AdvancedShadowCullingFrustum calculates its own clipping planes after construction.
 */
public final class ClippingHelperImpl extends ClippingHelper {
    private static final ClippingHelper INSTANCE = new ClippingHelper();

    public ClippingHelperImpl() {
    }

    public static ClippingHelper getInstance() {
        return INSTANCE;
    }
}
