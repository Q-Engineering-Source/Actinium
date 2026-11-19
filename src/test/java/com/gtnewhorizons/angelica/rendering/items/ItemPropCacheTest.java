package com.gtnewhorizons.angelica.rendering.items;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.block.model.ItemOverrideList;
import net.minecraft.client.renderer.block.model.SimpleBakedModel;
import net.minecraft.util.EnumFacing;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Covers the two properties the dropped item mesh cache relies on: the key distinguishes the renders
 * of one model that differ only in their per quad tint, and the entry count never exceeds the
 * configured cache size, so {@code itemRendererCacheSize} bounds the memory the cache keeps.
 */
class ItemPropCacheTest {

    private final List<Object> disposed = new ArrayList<>();

    private ItemPropCache<Object> cache(int capacity) {
        return new ItemPropCache<>(() -> capacity, Object::new, this.disposed::add);
    }

    @Test
    void quadTintSeparatesTheEntriesOfOneModel() {
        final ItemPropCache<Object> cache = cache(8);
        final IBakedModel model = model();
        final int[] red = {0xFF0000FF, -1};
        final int[] green = {0xFF00FF00, -1};

        final Object first = cache.insert(model, red);
        assertNull(cache.get(model, green), "a different tint must not reuse the red mesh");

        final Object second = cache.insert(model, green);
        assertNotSame(first, second);
        assertSame(first, cache.get(model, red));
        assertSame(second, cache.get(model, green));
    }

    @Test
    void theConfiguredCapacityBoundsTheEntries() {
        final ItemPropCache<Object> cache = cache(2);
        final IBakedModel model = model();

        final Object first = cache.insert(model, new int[]{1});
        cache.insert(model, new int[]{2});
        assertEquals(2, cache.size());
        cache.insert(model, new int[]{3});

        assertEquals(2, cache.size(), "a third mesh must evict the oldest entry");
        assertNull(cache.get(model, new int[]{1}), "the evicted mesh has to leave the cache");
        assertEquals(List.of(first), this.disposed, "the evicted mesh is released through the disposer");
    }

    @Test
    void aCapacityOfZeroKeepsNothing() {
        final ItemPropCache<Object> cache = cache(0);
        final IBakedModel model = model();

        cache.insert(model, new int[]{1});

        assertEquals(0, cache.size());
        assertNull(cache.get(model, new int[]{1}));
    }

    @Test
    void clearReleasesEveryEntry() {
        final ItemPropCache<Object> cache = cache(4);
        final IBakedModel model = model();
        cache.insert(model, new int[]{1});
        cache.insert(model, new int[]{2});

        cache.clear();

        assertEquals(0, cache.size());
        assertEquals(2, this.disposed.size(), "clearing the cache releases the meshes it held");
    }

    @Test
    void theSameModelAndTintShareOneEntry() {
        final ItemPropCache<Object> cache = cache(4);
        final IBakedModel model = model();
        final int[] colors = {0xFF102030, 0xFF405060};

        final Object inserted = cache.insert(model, colors);

        assertSame(inserted, cache.get(model, colors.clone()), "the key compares the tint values, not the array");
    }

    /** A real baked model, so the cache key is built from the class the item renderer actually sees. */
    private static IBakedModel model() {
        final Map<EnumFacing, List<BakedQuad>> faces = new Object2ObjectOpenHashMap<>();
        for (EnumFacing facing : EnumFacing.values()) {
            faces.put(facing, facing == EnumFacing.NORTH ? Collections.singletonList(quad(0)) : Collections.emptyList());
        }
        return new SimpleBakedModel(new ArrayList<>(), faces, false, true, null, ItemCameraTransforms.DEFAULT,
            ItemOverrideList.NONE);
    }

    private static BakedQuad quad(int tintIndex) {
        final int[] vertexData = new int[28];
        for (int vertex = 0; vertex < 4; vertex++) {
            vertexData[vertex * 7] = Float.floatToRawIntBits(vertex);
            vertexData[vertex * 7 + 3] = 0xFFFFFFFF;
            vertexData[vertex * 7 + 6] = 0x00010000;
        }
        return new BakedQuad(vertexData, tintIndex, EnumFacing.NORTH, null);
    }
}
