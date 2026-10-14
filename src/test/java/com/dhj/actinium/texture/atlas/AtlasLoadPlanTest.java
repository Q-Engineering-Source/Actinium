package com.dhj.actinium.texture.atlas;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AtlasLoadPlan} replays the loop's dependency-first walk; exercised through name and dependency
 * declarations alone, no resources.
 */
class AtlasLoadPlanTest {

    @Test
    void ordersADependencyBeforeTheSpriteThatDeclaresIt() {
        final DependentSprite base = new DependentSprite("minecraft:blocks/base");
        final DependentSprite plain = new DependentSprite("minecraft:blocks/plain");
        final DependentSprite derived = new DependentSprite("minecraft:blocks/derived", base);

        // The registry order puts the dependent first, and the loop still loads the base texture first.
        final AtlasLoadPlan plan = AtlasLoadPlan.of(registryOf(derived, base, plain));

        assertEquals(List.of(base, derived, plain), plan.decodeOrder(),
            "the loop loads a dependency before the sprite that declares it");
    }

    @Test
    void ordersADependencyChainOutermostLast() {
        final DependentSprite bottom = new DependentSprite("minecraft:blocks/bottom");
        final DependentSprite middle = new DependentSprite("minecraft:blocks/middle", bottom);
        final DependentSprite top = new DependentSprite("minecraft:blocks/top", middle);

        final AtlasLoadPlan plan = AtlasLoadPlan.of(registryOf(top, middle, bottom));

        assertEquals(List.of(bottom, middle, top), plan.decodeOrder(), "a chain is walked from its end");
    }

    @Test
    void visitsASpriteOnceHoweverManySpritesDependOnIt() {
        final DependentSprite base = new DependentSprite("minecraft:blocks/base");
        final DependentSprite plain = new DependentSprite("minecraft:blocks/plain");
        final DependentSprite first = new DependentSprite("minecraft:blocks/first", base);
        final DependentSprite second = new DependentSprite("minecraft:blocks/second", base, first);

        final AtlasLoadPlan plan = AtlasLoadPlan.of(registryOf(first, second, plain, base));

        assertEquals(List.of(base, first, second, plain), plan.decodeOrder(),
            "a sprite the loop has already loaded is not loaded again");
    }

    @Test
    void terminatesOnACircularDependency() {
        final DependentSprite first = new DependentSprite("minecraft:blocks/first");
        final DependentSprite second = new DependentSprite("minecraft:blocks/second", first);
        first.dependsOn(second);

        final AtlasLoadPlan plan = AtlasLoadPlan.of(registryOf(first, second));

        assertEquals(List.of(second, first), plan.decodeOrder(),
            "both sprites are seeded once, and the walk stops at the cycle");
    }

    @Test
    void defersTheChainOfEveryDeclaredDependency() {
        final DependentSprite base = new DependentSprite("minecraft:blocks/base");
        final DependentSprite plain = new DependentSprite("minecraft:blocks/plain");
        final DependentSprite derived = new DependentSprite("minecraft:blocks/derived", base);

        final AtlasLoadPlan plan = AtlasLoadPlan.of(registryOf(derived, base, plain));

        assertEquals(Set.of(base.getIconName()), plan.deferredChains(),
            "the loader of a derived sprite reads its dependency's frames, so that chain is deferred");
    }

    @Test
    void defersADependencyTheRegistryDoesNotHoldYet() {
        final ResourceLocation late = new ResourceLocation("minecraft:blocks/late");
        final DependentSprite derived = new DependentSprite("minecraft:blocks/derived");
        derived.dependsOn(late);

        final AtlasLoadPlan plan = AtlasLoadPlan.of(registryOf(derived));

        assertEquals(Set.of(late.toString()), plan.deferredChains(),
            "the loop registers a missing dependency on the fly, and the loader still reads its frames");
        assertEquals(List.of(derived), plan.decodeOrder(),
            "a sprite the registry does not hold cannot be seeded into the decode lane");
    }

    /** The registry the registration loop walks, keyed the way {@code TextureMap} keys it. */
    private static Map<String, TextureAtlasSprite> registryOf(TextureAtlasSprite... sprites) {
        final Map<String, TextureAtlasSprite> registered = new LinkedHashMap<>();
        for (TextureAtlasSprite sprite : sprites) {
            registered.put(sprite.getIconName(), sprite);
        }
        return registered;
    }

    /** A sprite that names the locations the loop must load first - all the plan reads. */
    private static final class DependentSprite extends TextureAtlasSprite {

        private List<ResourceLocation> dependencies = List.of();

        private DependentSprite(String name, TextureAtlasSprite... dependencies) {
            super(name);
            for (TextureAtlasSprite dependency : dependencies) {
                dependsOn(new ResourceLocation(dependency.getIconName()));
            }
        }

        private void dependsOn(TextureAtlasSprite dependency) {
            dependsOn(new ResourceLocation(dependency.getIconName()));
        }

        private void dependsOn(ResourceLocation dependency) {
            final List<ResourceLocation> next = new ArrayList<>(this.dependencies);
            next.add(dependency);
            this.dependencies = List.copyOf(next);
        }

        @Override
        public Collection<ResourceLocation> getDependencies() {
            return this.dependencies;
        }
    }
}
