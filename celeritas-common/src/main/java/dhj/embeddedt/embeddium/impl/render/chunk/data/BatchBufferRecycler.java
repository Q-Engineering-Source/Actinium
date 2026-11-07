package dhj.embeddedt.embeddium.impl.render.chunk.data;

import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Holds the GL buffers of discarded cached batches until a rebuilt batch takes them over. Deliberately free of GL
 * calls so the retirement policy can be exercised without a context.
 *
 * <p>Capacity is two because a storage caches at most two batches, so at most two buffers can be waiting at once.
 * Taking is last-in-first-out, which reuses the most recently retired buffer first. A buffer retired while the
 * recycler is full is deleted immediately.
 */
final class BatchBufferRecycler<T> {
    private static final int CAPACITY = 2;

    private final Object[] spares = new Object[CAPACITY];
    private int count;

    /**
     * Holds {@code buffer} for reuse, or deletes it right away when the recycler is already full. A {@code null}
     * buffer, which is what a batch without a GL buffer reports, is ignored.
     */
    void retire(@Nullable T buffer, Consumer<? super T> deleter) {
        if (buffer == null) {
            return;
        }

        if (this.count == CAPACITY) {
            deleter.accept(buffer);
            return;
        }

        this.spares[this.count++] = buffer;
    }

    /**
     * {@return the most recently retired buffer, or {@code null} when none is held}
     */
    @SuppressWarnings("unchecked")
    @Nullable
    T take() {
        if (this.count == 0) {
            return null;
        }

        int index = --this.count;
        T buffer = (T) this.spares[index];
        this.spares[index] = null;
        return buffer;
    }

    /**
     * Deletes every held buffer. Called when the owning storage is deleted, so no buffer outlives it.
     */
    @SuppressWarnings("unchecked")
    void drain(Consumer<? super T> deleter) {
        while (this.count > 0) {
            int index = --this.count;
            T buffer = (T) this.spares[index];
            this.spares[index] = null;
            deleter.accept(buffer);
        }
    }
}
