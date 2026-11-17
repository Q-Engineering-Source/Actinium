package net.coderbot.iris.pipeline;

/**
 * Registration point for the host options that gate instance-batched program variants.
 *
 * <p>The deferred pipeline builds one variant per {@code Instancing} kind it supports, and the cube
 * layout is only usable when the host enables cube instancing. The host owns that option, so it
 * registers a provider at startup; the shader module keeps the interface and reads no host class.</p>
 */
public final class InstancingSupportProvider {

    /** Supplies the host option values the instancing variants read. */
    public interface Provider {
        /** Whether cube instancing is enabled, which also requires entity batching in the host. */
        boolean cubeInstancingEnabled();
    }

    private static volatile Provider provider;

    private InstancingSupportProvider() {
    }

    public static void setProvider(Provider newProvider) {
        provider = newProvider;
    }

    /**
     * @return whether cube instancing is enabled; a pipeline built before the host registers its
     * provider reports cube variants as unsupported instead of enabling them by default
     */
    public static boolean cubeInstancingEnabled() {
        final Provider current = provider;
        return current != null && current.cubeInstancingEnabled();
    }
}
