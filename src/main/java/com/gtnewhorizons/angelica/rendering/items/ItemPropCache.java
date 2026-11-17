package com.gtnewhorizons.angelica.rendering.items;

import net.minecraft.client.renderer.block.model.IBakedModel;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Bounded map from one render of a dropped item to the mesh captured for it.
 *
 * <p>1.12.2 bakes the tint of an item into its vertex colours, so one baked model renders
 * differently for every set of per quad colours the item colour table returns. The key therefore
 * holds the model identity together with those colours; keying on the model alone would hand the
 * mesh of one dye colour to every other colour of the same item.</p>
 *
 * <p>The capacity comes from the caller and is read on every insert, so lowering
 * {@code itemRendererCacheSize} takes effect on the next captured mesh. Entries leave the cache in
 * least recently used order and are handed to the disposer.</p>
 *
 * @param <V> mesh type the cache holds
 */
public final class ItemPropCache<V> {

    private final IntSupplier capacity;
    private final Supplier<V> factory;
    private final Consumer<V> disposer;
    private final Map<Key, V> entries = new LinkedHashMap<Key, V>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, V> eldest) {
            if (this.size() <= ItemPropCache.this.capacity.getAsInt()) {
                return false;
            }
            ItemPropCache.this.dispose(eldest.getValue());
            return true;
        }
    };

    /**
     * @param capacity entry limit, read on every insert
     * @param factory creates the value an {@link #insert} returns
     * @param disposer releases an evicted value, or {@code null} when the value owns no resource
     */
    public ItemPropCache(IntSupplier capacity, Supplier<V> factory, Consumer<V> disposer) {
        this.capacity = capacity;
        this.factory = factory;
        this.disposer = disposer;
    }

    /**
     * Returns the value captured for this model and these per quad colours.
     *
     * @param colors per quad tint of the render, compared by value
     * @return the cached value, or {@code null} when this render was never captured
     */
    public V get(IBakedModel model, int[] colors) {
        if (this.entries.isEmpty()) {
            return null;
        }
        return this.entries.get(new Key(model, colors));
    }

    /**
     * Stores a new entry and returns the value the factory created for it.
     *
     * @param colors per quad tint of the render; the cache copies the array, so the caller may
     *               reuse it
     */
    public V insert(IBakedModel model, int[] colors) {
        final V value = this.factory.get();
        this.entries.put(new Key(model, Arrays.copyOf(colors, colors.length)), value);
        return value;
    }

    /** Drops every entry, releasing the values through the disposer. */
    public void clear() {
        for (V value : this.entries.values()) {
            this.dispose(value);
        }
        this.entries.clear();
    }

    public int size() {
        return this.entries.size();
    }

    private void dispose(V value) {
        if (this.disposer != null) {
            this.disposer.accept(value);
        }
    }

    /**
     * Model identity plus per quad colours. The model is compared by reference because the renderer
     * hands out one model instance per item, and two models that compare equal by value can still
     * carry different quads.
     */
    private static final class Key {
        private final IBakedModel model;
        private final int[] colors;
        private final int hash;

        private Key(IBakedModel model, int[] colors) {
            this.model = model;
            this.colors = colors;
            this.hash = System.identityHashCode(model) * 31 + Arrays.hashCode(colors);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Key)) {
                return false;
            }
            final Key other = (Key) obj;
            return this.model == other.model && Arrays.equals(this.colors, other.colors);
        }
    }
}
