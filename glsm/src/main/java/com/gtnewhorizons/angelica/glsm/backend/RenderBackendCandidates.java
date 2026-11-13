package com.gtnewhorizons.angelica.glsm.backend;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Registers optional backends after the host has checked their runtime dependencies. */
public final class RenderBackendCandidates {
    private static final List<Supplier<? extends RenderBackend>> FACTORIES = new ArrayList<>();
    private static boolean selected;

    private RenderBackendCandidates() {}

    /** Registers a factory before the rendering backend is first selected. */
    public static synchronized void register(Supplier<? extends RenderBackend> factory) {
        if (selected) {
            throw new IllegalStateException("Render backend selection has already completed");
        }
        FACTORIES.add(Objects.requireNonNull(factory, "factory"));
    }

    static synchronized List<Supplier<? extends RenderBackend>> take() {
        selected = true;
        List<Supplier<? extends RenderBackend>> factories = List.copyOf(FACTORIES);
        FACTORIES.clear();
        return factories;
    }
}
