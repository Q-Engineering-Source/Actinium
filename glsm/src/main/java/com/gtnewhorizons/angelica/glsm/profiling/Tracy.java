package com.gtnewhorizons.angelica.glsm.profiling;

/**
 * No-op stand-in for the upstream profiler facade.
 *
 * <p>Upstream loads a native Tracy client through a service-loaded backend and records zones, plots and capture
 * requests into it. Native collection is out of scope here: this facade keeps every call site compiling and lets
 * {@code GLSMTextureLifetime} and other instrumentation sites keep their zone markers, but each method terminates
 * without touching a backend. {@link #ENABLED} stays {@code false}, so guarded call sites skip the zone bookkeeping
 * they wrap.
 *
 * <p>Restoring real collection means reintroducing upstream's profiling package together with its native backend;
 * nothing in this class should be extended to collect data on its own.
 */
public final class Tracy {

    private Tracy() {}

    /** Whether a native profiling backend is attached. Always false without native collection. */
    public static final boolean ENABLED = false;

    /** Whether fine-grained sub-zone markers are recorded. Meaningless while {@link #ENABLED} is false. */
    public static final boolean FINE_ZONES = false;

    public static final int COLOR_CLIENT = 0x3A6EA5; // blue
    public static final int COLOR_SERVER = 0xB5651D; // brown
    public static final int COLOR_WORKER = 0x3F7F3F; // green
    public static final int COLOR_SWAP = 0xAA3333; // red
    public static final int COLOR_TERRAIN = 0x2E8B8B; // teal
    public static final int COLOR_IRIS = 0x9E862E; // gold
    public static final int COLOR_FFP = 0x7A4E9E; // purple

    /**
     * A resolved zone handle. Upstream interns the name into a native source location here; without a backend the
     * handle only carries the name and color so call sites keep one allocation-free constant instead of a string.
     */
    public static final class ZoneId {
        private final String name;
        private final int color;

        private ZoneId(String name, int color) {
            this.name = name;
            this.color = color;
        }
    }

    public static ZoneId zoneId(String name) {
        return zoneId(name, COLOR_WORKER);
    }

    public static ZoneId zoneId(String name, int color) {
        return new ZoneId(name, color);
    }

    public static void beginZone(String name) {
        beginZone(name, COLOR_WORKER);
    }

    public static void beginZone(String name, int color) {}

    public static void beginZone(ZoneId zone) {}

    public static void endZone() {}

    public static void zoneText(String text) {}

    public static void zoneValue(long value) {}

    public static void frameMark() {}

    /** No native plot exists while the profiler backend is disabled. */
    public static long plotHandle(String name) { return 0L; }

    /** Accepts upstream instrumentation while native profiling is disabled. */
    public static void plotInt(long plot, long value) {}
}
