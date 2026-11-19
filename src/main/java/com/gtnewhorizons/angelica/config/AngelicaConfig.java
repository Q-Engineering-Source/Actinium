package com.gtnewhorizons.angelica.config;

import com.dhj.actinium.runtime.ActiniumRuntime;
import dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions;

import java.util.List;

/**
 * Reads the options the entity batching and instancing code consumes.
 *
 * <p>The values live in {@link SodiumGameOptions} so the settings screen, the config file and the
 * render path share one source of truth; this class only names the subset the batching code reads
 * and combines the two flags that gate each feature.</p>
 */
public final class AngelicaConfig {

    private AngelicaConfig() {
    }

    private static SodiumGameOptions.PerformanceSettings performance() {
        return ActiniumRuntime.options().performance;
    }

    /** Whether entity model parts, items and shadows are batched on the fixed-function passes. */
    public static boolean enableEntityBatching() {
        return performance().enableEntityBatching;
    }

    /** Whether cuboid model parts draw from a shared unit cube instead of per-part geometry. */
    public static boolean enableCubeInstancing() {
        return performance().enableCubeInstancing;
    }

    /** Whether entity and block entity models share one stitched texture atlas. */
    public static boolean enableEntityTextureAtlas() {
        return performance().enableEntityTextureAtlas;
    }

    /** Whether entity draws recorded in the shader shadow pass are replayed in the main view. */
    public static boolean enableEntityShadowReplay() {
        return performance().enableEntityShadowReplay;
    }

    /** Entity class names that re-run their renderer instead of replaying shadow pass draws. */
    public static List<String> entityShadowReplayExcludedClasses() {
        return performance().entityShadowReplayExcludedClasses;
    }

    /** Upper bound for cached item meshes. */
    public static int itemRendererCacheSize() {
        return performance().itemRendererCacheSize;
    }

    /** Whether the enchantment glint renders on items held in the world. */
    public static boolean glintWorld() {
        return ActiniumRuntime.options().quality.glintWorld;
    }

    /** Cube instancing draws into the shared unit cube, which requires entity batching to record draws. */
    public static boolean cubeInstancingEnabled() {
        return enableEntityBatching() && enableCubeInstancing();
    }

    /** Shadow replay replaces the main view draw, which requires entity batching to record draws. */
    public static boolean entityShadowReplayEnabled() {
        return enableEntityBatching() && enableEntityShadowReplay();
    }

    /** The texture atlas only helps when cube instancing merges the parts that sample it. */
    public static boolean entityTextureAtlasEnabled() {
        return cubeInstancingEnabled() && enableEntityTextureAtlas();
    }
}
