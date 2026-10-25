package com.dhj.actinium.compat.modernsplash;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import gkappa.modernsplash.ModernSplash;
import net.minecraft.launchwrapper.Launch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Crash guard for Modern Splash (modid {@code modernsplash}, issue #200).
 *
 * <p>Modern Splash's coremod {@code MSLoadingPlugin} carries no {@code SortingIndex} and is
 * instantiated before any Actinium transformer is registered. Its constructor reads
 * {@code config/time.history}, and the {@code IOException} branch touches
 * {@code ModernSplash.LOGGER}, so a missing file defines {@code ModernSplash} before
 * {@code AngelicaRedirectorTransformer} is installed; the class keeps its raw fixed-function GL
 * calls forever, whose function pointers are null on the core profile and abort the JVM when
 * {@code drawFadeOverlay} runs during the main-menu fade. No bytecode fix can reach a loaded
 * class, so the runtime route is used: clear the splash logo texture at splash finish so the
 * fade overlay skips its raw GL block. The mod class is referenced directly because
 * {@code escapedRedirector} can only be true when it is already loaded.</p>
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
