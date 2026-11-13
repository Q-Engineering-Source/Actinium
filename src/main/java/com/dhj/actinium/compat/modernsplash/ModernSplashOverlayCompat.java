package com.dhj.actinium.compat.modernsplash;

import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import gkappa.modernsplash.ModernSplash;
import net.minecraft.launchwrapper.Launch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.util.Map;

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
    private static final String ACTUAL_CLASS_LOADER_RESOURCE =
        "top/outlands/foundation/boot/ActualClassLoader.class";

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
        if (!escapedRedirector && isClassLoaded(MAIN_CLASS)) {
            escapedRedirector = true;
            LOGGER.warn(
                "ModernSplash was class-loaded before Actinium's GL redirector was registered; "
                    + "its raw fixed-function GL calls cannot be rewritten. The main-menu logo fade "
                    + "will be skipped to avoid a core-profile crash (issue #200)."
            );
        }
    }

    private static boolean isClassLoaded(String className) {
        if (ModernSplashOverlayCompat.class.getClassLoader().getResource(ACTUAL_CLASS_LOADER_RESOURCE) != null) {
            return Outlands.isClassLoaded(className);
        }
        return Legacy.isClassLoaded(className);
    }

    private static final class Outlands {
        private static boolean isClassLoaded(String className) {
            return Launch.classLoader.isClassLoaded(className);
        }
    }

    private static final class Legacy {
        /**
         * Vanilla LaunchWrapper has no public loaded-class query. Read its cache instead of
         * defining the class through Class.forName, which would change the startup sequence.
         */
        private static boolean isClassLoaded(String className) {
            try {
                Field cachedClassesField = Launch.classLoader.getClass().getDeclaredField("cachedClasses");
                cachedClassesField.setAccessible(true);
                Map<?, ?> cachedClasses = (Map<?, ?>) cachedClassesField.get(Launch.classLoader);
                return cachedClasses.containsKey(className);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Could not inspect the LaunchWrapper class cache", failure);
            }
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
            GLSMTextures.glDeleteTextures(logo);
            ModernSplash.logoGlTextureName = 0;
            LOGGER.info("Skipped ModernSplash logo fade overlay (logo texture cleared).");
        }
    }
}
