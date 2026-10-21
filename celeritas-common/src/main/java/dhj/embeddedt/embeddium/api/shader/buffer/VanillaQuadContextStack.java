package dhj.embeddedt.embeddium.api.shader.buffer;

import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;

/** Keeps per-builder quad metadata correct while vanilla model rendering recurses. */
public final class VanillaQuadContextStack {
    private VanillaQuadContext[] previous = new VanillaQuadContext[2];
    private int depth;

    @Nullable
    private VanillaQuadContext active;

    /** Activates a nested context while retaining the one that currently owns the builder. */
    public void push(VanillaQuadContext context) {
        Objects.requireNonNull(context, "context");
        if (this.depth == this.previous.length) {
            this.previous = Arrays.copyOf(this.previous, this.previous.length * 2);
        }

        this.previous[this.depth++] = this.active;
        this.active = context;
    }

    /** Restores the active context saved by the latest push. */
    public void pop() {
        if (this.depth == 0) {
            throw new IllegalStateException("No vanilla quad context is active");
        }

        this.active = this.previous[--this.depth];
        this.previous[this.depth] = null;
    }

    /** Replaces the context for legacy callers that do not establish a nested scope. */
    public void setActive(@Nullable VanillaQuadContext context) {
        this.active = context;
    }

    /** Returns the metadata captured for the next quad, or {@code null} outside a render scope. */
    @Nullable
    public VanillaQuadContext active() {
        return this.active;
    }

    /** Clears builder state after a new draw begins. */
    public void reset() {
        if (this.depth != 0) {
            throw new IllegalStateException("A vanilla quad context leaked across BufferBuilder.begin");
        }
        this.active = null;
    }

    /**
     * Prepares a new BufferBuilder draw while preserving an invocation scope established before lazy buffer start.
     */
    public void beginBuffer() {
        if (this.depth == 0) {
            this.active = null;
        }
    }
}
