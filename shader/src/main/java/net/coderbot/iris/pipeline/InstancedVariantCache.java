package net.coderbot.iris.pipeline;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One-entry-per-key cache for the instanced program variants.
 *
 * <p>A pass asks for its variant on the first query and every later query reuses the result, so the
 * same source and layout is linked once no matter how many render conditions resolved to that
 * source. A key whose build produced nothing is kept as a null entry: retrying would re-link a
 * program that already failed, and the caller already treats a null variant as "use the per-instance
 * path".</p>
 *
 * @param <T> the program type the pipeline uses, so the cache stays testable without a GL context
 */
final class InstancedVariantCache<T> {

    private final Map<Object, T> variants = new HashMap<>();
    private final Consumer<T> destroyer;

    InstancedVariantCache(Consumer<T> destroyer) {
        this.destroyer = destroyer;
    }

    /** @return the cached value for {@code key}, or the value the factory built and cached */
    @Nullable
    <K> T get(K key, Function<K, T> factory) {
        if (variants.containsKey(key)) {
            return variants.get(key);
        }
        final T built = factory.apply(key);
        variants.put(key, built);
        return built;
    }

    /** @return whether this key was already asked for, including when it built nothing */
    boolean contains(Object key) {
        return variants.containsKey(key);
    }

    int size() {
        return variants.size();
    }

    /** Destroys every built value once and forgets all keys. */
    void destroyAll() {
        final List<T> built = new ArrayList<>(variants.values());
        variants.clear();
        for (T value : built) {
            if (value != null) {
                destroyer.accept(value);
            }
        }
    }
}
