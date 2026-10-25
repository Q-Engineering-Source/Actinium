package com.dhj.actinium.compat.kirino;

import net.minecraftforge.fml.common.Loader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Co-existence support for Kirino Engine (shipped with Cleanroom Loader,
 * mod id {@code kirino_engine}). Kirino and Actinium are mutually exclusive render owners:
 * in Graphics mode Kirino replaces {@code EntityRenderer#renderWorld}, starving every Actinium
 * render hook. The co-existence path is Kirino's Headless mode, where
 * {@code MixinKirinoConfigHub} answers {@code isEnableRenderDelegate()} with {@code false} so
 * vanilla {@code renderWorld} runs and Actinium owns the pipeline. This class only reports the
 * co-existence state for diagnostics; the {@link Loader} query is deferred to first use so class
 * loading never fails on a missing Forge environment detail.
 */
public final class KirinoCompat {
    /**
     * The mod ID of Kirino Engine (parent module; {@code kirino_ecs}/{@code kirino_gl}
     * declare it as their parent, so all three are present or absent together).
     */
    public static final String MODID = "kirino_engine";

    private static final Logger LOGGER = LogManager.getLogger("ActiniumKirinoCompat");

    private KirinoCompat() {
    }

    /**
     * Returns whether Kirino Engine is installed at runtime.
     */
    public static boolean isKirinoPresent() {
        return Loader.isModLoaded(MODID);
    }

    /**
     * Returns whether the render delegate is pinned off (Kirino Headless mode).
     * When Kirino is absent this is {@code false}: there is nothing to pin.
     */
    public static boolean isHeadlessPinned() {
        return isKirinoPresent();
    }

    /**
     * Logs the Headless pin once when Kirino is present. Call from {@code Actinium#onInit} after
     * the late mixin config has been queued; the pin itself is enforced by the mixin.
     */
    public static void install() {
        if (!isKirinoPresent()) {
            return;
        }
        LOGGER.info(
            "Kirino Engine detected: pinning render delegate OFF (Headless mode). "
                + "Kirino's ECS/analysis runtime stays active, while Actinium remains the "
                + "sole owner of the rendering pipeline."
        );
    }

    /**
     * Returns the one-line status shown on the F3 debug screen (or {@code null}
     * when Kirino is absent, so the caller can skip the line entirely).
     */
    public static String debugStatus() {
        if (!isKirinoPresent()) {
            return null;
        }
        return "Kirino: headless (render delegate pinned off)";
    }
}
