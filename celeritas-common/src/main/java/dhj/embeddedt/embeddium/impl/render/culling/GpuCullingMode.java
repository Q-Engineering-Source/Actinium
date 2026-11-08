package dhj.embeddedt.embeddium.impl.render.culling;

import dhj.embeddedt.embeddium.impl.gui.framework.TextComponent;
import dhj.embeddedt.embeddium.impl.gui.options.TextProvider;

/**
 * How chunk sections are culled before they are drawn.
 *
 * <p>{@link #CPU_ONLY} keeps the CPU emitter and its cached region batches. {@link #COMPUTE} culls sections on the GPU
 * and submits them with indirect multi-draw; the option only becomes selectable when the backend can provide compute
 * shaders, shader storage buffers and indirect multi-draw, see {@link GpuCulling#availability()}.
 */
public enum GpuCullingMode implements TextProvider {
    CPU_ONLY("options.actinium.gpuCullingMode.cpu_only"),
    COMPUTE("options.actinium.gpuCullingMode.compute");

    private final TextComponent name;

    GpuCullingMode(String translationKey) {
        this.name = TextComponent.translatable(translationKey);
    }

    /**
     * {@return whether this mode culls and draws through the compute backend}
     */
    public boolean computeEnabled() {
        return this == COMPUTE;
    }

    @Override
    public TextComponent getLocalizedName() {
        return this.name;
    }
}
