package com.gtnewhorizons.angelica.glsm.context;

/** Provides the process registry used by the native lifecycle adapter and GLSM. */
public final class GLContextRegistryProvider {
    private static final GLContextRegistry REGISTRY = new GLContextRegistryImpl();

    private GLContextRegistryProvider() {}

    /** Returns the registry shared by every context lifecycle callback. */
    public static GLContextRegistry get() {
        return REGISTRY;
    }

    /** Registers the GLSM state owner that must release CPU references after context destruction. */
    public static void setRetirementListener(ContextRetirementListener listener) {
        REGISTRY.setRetirementListener(listener);
    }

    /** Subscribes an independent GL resource owner without replacing GLSM's state owner. */
    public static AutoCloseable addRetirementListener(ContextRetirementListener listener) {
        return REGISTRY.addRetirementListener(listener);
    }
}
