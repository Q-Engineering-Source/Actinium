package dhj.embeddedt.embeddium.impl.render.culling;

import com.mitchej123.lwjgl.GLExtension;
import dhj.embeddedt.embeddium.impl.render.chunk.MultiDrawMode;
import dhj.embeddedt.embeddium.impl.runtime.EmbeddiumRuntimeOptions;

import static com.mitchej123.lwjgl.LWJGLServiceProvider.LWJGL;

/**
 * Resolves the multidraw mode the terrain renderer can actually use on the current context.
 *
 * <p>This is the only place the mode is decided. The chunk renderer resolves the mode it draws with through here, and
 * the culling core asks for the same mode, so a culler cannot hand out batches in a layout the renderer does not
 * draw: the configured mode comes from {@link EmbeddiumRuntimeOptions#chunkMultiDrawMode()}, the
 * {@code actinium.chunkMultiDrawMode} system property overrides it after trimming, an override the mode lookup does
 * not know resolves to {@link MultiDrawMode#DIRECT}, and a requested {@link MultiDrawMode#INDIRECT} is downgraded to
 * {@code DIRECT} when the driver exposes neither GL 4.3 nor {@code GL_ARB_multi_draw_indirect}.
 */
public final class MultiDrawModeResolver {

    /** System property that overrides the configured mode. */
    private static final String OVERRIDE_PROPERTY = "actinium.chunkMultiDrawMode";

    private MultiDrawModeResolver() {
    }

    public static MultiDrawMode resolve() {
        final String override = System.getProperty(OVERRIDE_PROPERTY, "").trim();
        final MultiDrawMode configured = EmbeddiumRuntimeOptions.chunkMultiDrawMode();
        final MultiDrawMode requested = override.isEmpty() ? configured : MultiDrawMode.fromProperty(override);
        if (requested == MultiDrawMode.INDIRECT && !supportsMultiDrawIndirect()) {
            return MultiDrawMode.DIRECT;
        }
        return requested;
    }

    /**
     * {@return whether the current context can execute indirect multidraw}
     */
    public static boolean supportsMultiDrawIndirect() {
        return LWJGL.isOpenGLVersionSupported(4, 3) || LWJGL.isExtensionSupported(GLExtension.ARB_multi_draw_indirect);
    }
}
