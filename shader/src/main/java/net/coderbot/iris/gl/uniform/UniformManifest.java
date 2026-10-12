package net.coderbot.iris.gl.uniform;

import com.gtnewhorizons.angelica.glsm.shader.UniformType;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The uniforms a {@link ManifestCollectingHolder} was asked to provide, recorded instead of uploaded.
 *
 * <p>Iris normally pushes uniforms into a compiled program, so the set it declares is only observable
 * by looking at the program. The SDL GPU backend declares one shared uniform block per frame that
 * every shader has to spell out, so it needs that set as data: this is the container for it. Entries
 * keep the declaration order of the holder, and the two name sets record the uniforms that must stay
 * out of such a block because they are pushed per draw instead of per frame.</p>
 */
public final class UniformManifest {

    /** One declared uniform: its shader name, type and how often Iris would refresh it. */
    public record Entry(String name, UniformType type, UniformUpdateFrequency frequency) {}

    private final List<Entry> entries;
    private final Set<String> dynamicNames;
    private final Set<String> externallyManagedNames;

    UniformManifest(List<Entry> entries, Set<String> dynamicNames, Set<String> externallyManagedNames) {
        this.entries = Collections.unmodifiableList(entries);
        this.dynamicNames = Collections.unmodifiableSet(dynamicNames);
        this.externallyManagedNames = Collections.unmodifiableSet(externallyManagedNames);
    }

    /** {@return every declared uniform, in declaration order} */
    public List<Entry> entries() {
        return entries;
    }

    /** {@return names registered through the dynamic (push-notified) path} */
    public Set<String> dynamicNames() {
        return dynamicNames;
    }

    /** {@return names whose storage another owner maintains} */
    public Set<String> externallyManagedNames() {
        return externallyManagedNames;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
