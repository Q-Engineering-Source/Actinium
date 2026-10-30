package com.gtnewhorizons.angelica.glsm.backend;

/**
 * Selects the window toolkit used by Actinium. Each adapter is initialized only when selected,
 * allowing an SDL runtime to avoid resolving GLFW classes.
 */
public final class WindowBackends {
    private static WindowBackend backend;

    private WindowBackends() {}

    /** Returns the selected adapter, initializing GLFW by default. */
    public static synchronized WindowBackend get() {
        if (backend == null) {
            backend = GlfwHolder.INSTANCE;
        }
        return backend;
    }

    /** Selects SDL, retaining the same adapter on repeated calls. */
    public static synchronized void useSdl() {
        if (!(backend instanceof SdlWindowBackend)) {
            backend = SdlHolder.INSTANCE;
        }
    }

    /** Selects GLFW, retaining the same adapter on repeated calls. */
    public static synchronized void useGlfw() {
        if (!(backend instanceof GlfwWindowBackend)) {
            backend = GlfwHolder.INSTANCE;
        }
    }

    private static final class GlfwHolder {
        private static final WindowBackend INSTANCE = new GlfwWindowBackend();
    }

    private static final class SdlHolder {
        private static final WindowBackend INSTANCE = new SdlWindowBackend();
    }
}
