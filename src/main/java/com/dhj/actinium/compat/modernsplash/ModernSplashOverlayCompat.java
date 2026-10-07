package com.dhj.actinium.compat.modernsplash;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import gkappa.modernsplash.ModernSplash;
import net.minecraft.launchwrapper.Launch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Crash guard for Modern Splash (modid {@code modernsplash}, issue #200).
 *
 * <p>Modern Splash's coremod {@code MSLoadingPlugin} is instantiated before any
 * Actinium transformer is registered (it carries no {@code SortingIndex}, while
 * Actinium deliberately sorts late). Its constructor reads
 * {@code config/time.history} via {@code TimeHistory.getEstimateTime()}, whose
 * {@code IOException} branch touches {@code ModernSplash.LOGGER} — so when that
 * file is missing or unreadable, {@code gkappa.modernsplash.ModernSplash} is
 * defined <i>before</i> {@code AngelicaRedirectorTransformer} is installed and
 * keeps its raw LWJGL fixed-function calls forever (transformers only rewrite
 * classes loaded after registration).
 *
 * <p>On Actinium's core-profile context those removed entry points
 * ({@code glPushAttrib}, {@code glBegin}, ...) have null function pointers and
 * LWJGL aborts the JVM outright when {@code ModernSplash.drawFadeOverlay} runs
 * during the 500 ms main-menu fade. No bytecode-level fix can reach a class that
 * is already defined, so this guard takes the runtime route: when the class
 * escaped the redirector, the splash logo texture handle is cleared at splash
 * finish, making the fade overlay skip its raw GL block (the background fade
 * still renders through already-transformed vanilla calls). The mod class is
 * referenced directly rather than reflectively: {@link #escapedRedirector} can
 * only be true when the class is already loaded, so the reference never triggers
 * a load, and nothing here executes when the mod is absent.
 */
public final class ModernSplashOverlayCompat {

    private static final Logger LOGGER = LogManager.getLogger("ActiniumModernSplashCompat");
    private static final String MAIN_CLASS = "gkappa.modernsplash.ModernSplash";

    private static boolean escapedRedirector;

    private ModernSplashOverlayCompat() {
    }

    /**
     * Records whether {@code ModernSplash} was already defined before the late
     * GL redirector is registered. Must be called from
     * {@code AngelicaLateTweaker#getLaunchArguments()} <i>before</i> the
     * registration, so the probe reflects the pre-registration state.
     */
    public static void markIfLoadedBeforeRedirector() {
        if (!escapedRedirector && Launch.classLoader.isClassLoaded(MAIN_CLASS)) {
            escapedRedirector = true;
            LOGGER.warn(
                "ModernSplash was class-loaded before Actinium's GL redirector was registered; "
                    + "its raw fixed-function GL calls cannot be rewritten. The main-menu logo fade "
                    + "will be skipped to avoid a core-profile crash (issue #200)."
            );
        }
    }

    /**
     * When the class escaped the redirector, deletes the splash logo texture
     * through GLSM and zeroes the handle so {@code drawFadeOverlay} never enters
     * its raw {@code glPushAttrib} block. Called from
     * {@code MixinSplashProgress#celeritas$finishSplash} (splash finish, before
     * the main menu opens); a no-op in the normal transformed case.
     */
    public static void neutralizeLogoOverlayIfEscaped() {
        if (!escapedRedirector) {
            return;
        }
        int logo = ModernSplash.logoGlTextureName;
        if (logo != 0) {
            GLStateManager.glDeleteTextures(logo);
            ModernSplash.logoGlTextureName = 0;
            LOGGER.info("Skipped ModernSplash logo fade overlay (logo texture cleared).");
        }
    }
}
