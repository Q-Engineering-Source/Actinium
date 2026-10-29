package com.gtnewhorizons.angelica.glsm;

import com.gtnewhorizons.angelica.glsm.recording.CompiledDisplayList;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Keeps display-list namespaces for the lifetime of their native OpenGL share groups. */
final class DisplayListNamespaces {
    private final Map<Long, DisplayListNamespace> namespaces = new HashMap<>();

    synchronized DisplayListNamespace forShareGroup(long shareGroupId) {
        if (shareGroupId <= 0L) {
            throw new IllegalArgumentException("Display-list namespace requires a positive share-group id");
        }
        return this.namespaces.computeIfAbsent(shareGroupId, DisplayListNamespace::new);
    }

    /** Removes the namespace after the last native context has retired. */
    synchronized List<CompiledDisplayList> retireShareGroup(long shareGroupId) {
        final DisplayListNamespace namespace = this.namespaces.remove(shareGroupId);
        return namespace == null ? List.of() : namespace.retire();
    }
}
