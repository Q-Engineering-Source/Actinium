package com.gtnewhorizon.gtnhlib.compat;

import com.cleanroommc.discovery.CleanroomModDiscoverer;

/**
 * Mod presence checks used by compatibility layers to keep a foreign mod's classes off the
 * classpath while that mod is absent.
 *
 * <p>{@link #isModPresent} answers from {@link CleanroomModDiscoverer}, whose scan of the library
 * and classpath archives runs during {@code CoreModManager#handleLaunch} - before any mod class is
 * loaded. A caller may therefore query once and cache the result in a static initializer; that is
 * safe from any mod code, including mixins applied to early-loaded vanilla classes.</p>
 *
 * <p>It is not interchangeable with {@code Loader#isModLoaded}, and the difference is what a
 * presence guard is made of:</p>
 * <ul>
 *   <li>the scan reads archives only, so a mod supplied as an exploded directory is loaded by FML
 *       yet reports absent here;</li>
 *   <li>a jar that FML dropped (mod-side mismatch, ignored coremod) is still recorded;</li>
 *   <li>Cleanroom's built-in and injected modules ({@code mixinbooter}, {@code configanytime} and
 *       the {@code kirino_engine} modules) never enter the scanned id map, so they always report
 *       absent and must keep using {@code Loader#isModLoaded}.</li>
 * </ul>
 *
 * <p>The constants live here rather than in the compat classes so that every call site - the
 * compat classes themselves, mixin code, per-frame paths and the shader module - reads a cached
 * flag without loading an Actinium compat class, which would pull the guarded mod's classes into
 * the class load.</p>
 */
public final class Mods {
    public static final boolean ARCHITECTURECRAFT = isModPresent("architecturecraft");
    public static final boolean CHUNKANIMATOR = isModPresent("chunkanimator");
    public static final boolean COMPONENT_MODEL_HIDER = isModPresent("component_model_hider");
    public static final boolean DEPTHSUPDATE = isModPresent("depthsupdate");
    public static final boolean DISTANTHORIZONS = isModPresent("distanthorizons");
    public static final boolean DRAGONCORE = isModPresent("dragoncore");
    public static final boolean FLUIDLOGGED_API = isModPresent("fluidlogged_api");
    public static final boolean FLUXLOADING = isModPresent("fluxloading");
    public static final boolean HBM = isModPresent("hbm");
    public static final boolean NEOFONTRENDER = isModPresent("neofontrender");
    public static final boolean NEVERENOUGHANIMATIONS = isModPresent("neverenoughanimations");
    public static final boolean RFP2 = isModPresent("rfp2");
    public static final boolean SCALINGGUIS = isModPresent("scalingguis");
    public static final boolean SNOWREALMAGIC = isModPresent("snowrealmagic");

    private Mods() {}

    /**
     * Returns whether the mod id was found by Cleanroom's launch-time scan. See the class javadoc
     * for the cases in which this differs from {@code Loader#isModLoaded}.
     */
    public static boolean isModPresent(String modId) {
        return CleanroomModDiscoverer.instance().isModPresent(modId);
    }
}
