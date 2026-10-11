package dhj.embeddedt.embeddium.api.shader.buffer;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Exposes the per-quad shader metadata that vanilla BufferBuilder does not retain. */
public interface BufferBuilderExtension {
    void actinium$setActiveQuadContext(@Nullable VanillaQuadContext context);

    List<VanillaQuadContext> actinium$consumeQuadContexts();

    /** Copies the pending contexts for storage beside a reusable vertex cache. */
    List<VanillaQuadContext> actinium$copyQuadContexts();

    /** Appends contexts in the same order as raw vertex quads appended to this builder. */
    void actinium$appendQuadContexts(List<VanillaQuadContext> contexts);

    boolean actinium$isDrawing();

    void actinium$discard();
}
