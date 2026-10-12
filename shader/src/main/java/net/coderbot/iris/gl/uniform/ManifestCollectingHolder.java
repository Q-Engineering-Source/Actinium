package net.coderbot.iris.gl.uniform;

import com.gtnewhorizons.angelica.glsm.shader.UniformType;

import net.coderbot.iris.gl.state.ValueUpdateNotifier;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * A uniform holder that collects what Iris would declare instead of uploading it anywhere.
 *
 * <p>Handed to the regular uniform registration code ({@link net.coderbot.iris.uniforms.CommonUniforms}
 * and friends) to answer "which per-frame uniforms does this shader pack have?" without a compiled
 * program to ask. Locations are synthetic indices into the declaration order, which is what makes the
 * resulting {@link UniformManifest} a stable block layout.</p>
 *
 * <p>A name declares once: a second registration of the same name gets no location and is dropped, so
 * the manifest never lists a member twice.</p>
 */
public final class ManifestCollectingHolder implements DynamicLocationalUniformHolder {

    private final List<String> namesById = new ObjectArrayList<>();
    private final Map<String, UniformType> typesByName = new Object2ObjectOpenHashMap<>();
    private final List<UniformManifest.Entry> entries = new ArrayList<>();
    private final Set<String> dynamicNames = new LinkedHashSet<>();
    private final Set<String> externallyManagedNames = new LinkedHashSet<>();

    @Override
    public OptionalInt location(String name, UniformType type) {
        if (typesByName.putIfAbsent(name, type) != null) {
            return OptionalInt.empty();
        }
        final int id = namesById.size();
        namesById.add(name);
        return OptionalInt.of(id);
    }

    private String nameFor(int location) {
        return location >= 0 && location < namesById.size() ? namesById.get(location) : null;
    }

    /**
     * Records a uniform that Iris would refresh on the given schedule. {@code CUSTOM} is skipped: the
     * caller supplies those values itself rather than declaring how to produce them.
     */
    @Override
    public ManifestCollectingHolder addUniform(UniformUpdateFrequency updateFrequency, Uniform uniform) {
        Objects.requireNonNull(uniform);
        final String name = nameFor(uniform.getLocation());
        if (name == null) {
            return this;
        }
        switch (updateFrequency) {
            case ONCE, PER_TICK, PER_FRAME ->
                entries.add(new UniformManifest.Entry(name, typesByName.get(name), updateFrequency));
            case CUSTOM -> { }
        }
        return this;
    }

    /** Records a uniform that is pushed when the state it reads changes, rather than per frame. */
    @Override
    public ManifestCollectingHolder addDynamicUniform(Uniform uniform, ValueUpdateNotifier notifier) {
        Objects.requireNonNull(uniform);
        final String name = nameFor(uniform.getLocation());
        if (name != null) {
            dynamicNames.add(name);
        }
        return this;
    }

    @Override
    public ManifestCollectingHolder externallyManagedUniform(String name, UniformType type) {
        externallyManagedNames.add(name);
        return this;
    }

    /**
     * {@return the collected declarations}
     *
     * <p>Grouped by update frequency rather than purely in declaration order: uniform upload order is
     * what decides the layout a shared block has to reproduce, and Iris uploads once-only uniforms
     * before tick and frame ones.</p>
     */
    public UniformManifest build() {
        final List<UniformManifest.Entry> grouped = new ArrayList<>(entries.size());
        for (UniformUpdateFrequency frequency : GROUP_ORDER) {
            for (UniformManifest.Entry entry : entries) {
                if (entry.frequency() == frequency) {
                    grouped.add(entry);
                }
            }
        }
        return new UniformManifest(grouped, dynamicNames, externallyManagedNames);
    }

    /** The upload order Iris uses, most static first. */
    private static final UniformUpdateFrequency[] GROUP_ORDER = {
        UniformUpdateFrequency.ONCE, UniformUpdateFrequency.PER_TICK, UniformUpdateFrequency.PER_FRAME
    };
}
