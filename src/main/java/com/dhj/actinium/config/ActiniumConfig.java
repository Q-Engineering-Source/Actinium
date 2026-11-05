package com.dhj.actinium.config;

public final class ActiniumConfig {
    public static boolean enableIris = true;
    public static boolean enableCeleritas = true;
    public static boolean enableThreadedChunkBuilding = true;
    public static int chunkBuilderThreadCount = 0;
    public static boolean useVanillaChunkTracking = false;
    public static boolean disableF3Additions = false;
    public static boolean speedupAnimations = false;
    public static boolean useTotalWorldTime = false;
    public static boolean enableHardcodedCustomUniforms = false;
    public static boolean defineIsIris = true;
    public static int itemRendererCacheSize = 512;
    /** Threads the GLSM worker pool may use. Zero sizes the pool from the core count. */
    public static int workerThreadCount = 0;

    /** Requests the SDL GPU render backend. Read once at construction through {@code BackendOptions}. */
    public static boolean renderBackendSdlGpu = false;
    /** Driver the SDL GPU backend should pick; empty leaves the choice to the backend. */
    public static String renderBackendDriver = "";
    /** Core profile version to request, encoded as major * 10 + minor (45 means 4.5). Zero probes the ceiling. */
    public static int glVersion = 0;

    private ActiniumConfig() {
    }
}
