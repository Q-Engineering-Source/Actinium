package com.dhj.actinium.texture.atlas;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plan of one atlas load's registration loop, read off the registry before the loop starts.
 *
 * <p>{@link #decodeOrder} is the order the loop reaches each sprite's resource: dependencies before the sprite
 * that declares them. The decode lane must be seeded in this order or it desynchronises.
 *
 * <p>{@link #deferredChains} names every dependency any registered sprite declares - what a custom loader may
 * read through its {@code textureGetter}. Level building repaints the base level in place, so these chains are
 * built on the main thread after the loop, never on workers. Direct dependencies suffice: sprites the loop
 * registers on the fly are plain sprites and declare none, and lookup is by name.
 */
public record AtlasLoadPlan(List<TextureAtlasSprite> decodeOrder, Set<String> deferredChains) {

    /** Reads the plan off the registry copy the loop is about to walk, keyed by icon name. */
    public static AtlasLoadPlan of(Map<String, TextureAtlasSprite> registered) {
        final Set<String> visited = new HashSet<>();
        final List<TextureAtlasSprite> order = new ArrayList<>(registered.size());
        final Set<String> deferred = new HashSet<>();
        for (TextureAtlasSprite sprite : registered.values()) {
            appendWithDependencies(sprite, registered, visited, order, deferred);
        }
        return new AtlasLoadPlan(List.copyOf(order), deferred);
    }

    /** Appends a sprite's dependency closure to the decode order, dependencies first; each sprite once. */
    private static void appendWithDependencies(TextureAtlasSprite sprite, Map<String, TextureAtlasSprite> registered,
        Set<String> visited, List<TextureAtlasSprite> order, Set<String> deferred) {
        if (!visited.add(sprite.getIconName())) {
            return;
        }

        for (ResourceLocation dependency : sprite.getDependencies()) {
            deferred.add(dependency.toString());
            final TextureAtlasSprite dependencySprite = registered.get(dependency.toString());
            if (dependencySprite != null) {
                appendWithDependencies(dependencySprite, registered, visited, order, deferred);
            }
        }
        order.add(sprite);
    }
}
