package com.gtnewhorizons.angelica.loading.fml.tweakers;

import com.dhj.actinium.compat.MixinReEntranceLockFix;
import com.dhj.actinium.compat.modernsplash.ModernSplashOverlayCompat;
import com.dhj.actinium.loading.fml.transformers.LateRedirectorInstaller;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.List;

public class AngelicaLateTweaker implements ITweaker {

    private static final Logger LOGGER = LogManager.getLogger("ActiniumRedirector");

    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
    }

    @Override
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
    }

    @Override
    public String getLaunchTarget() {
        return null;
    }

    @Override
    public String[] getLaunchArguments() {
        try {
            // Probe before registering: anything already defined at this point keeps
            // raw GL calls forever (issue #200).
            ModernSplashOverlayCompat.markIfLoadedBeforeRedirector();
            LateRedirectorInstaller.install();
        } catch (Exception e) {
            LOGGER.warn("Failed to install Angelica redirector late", e);
        } finally {
            // Instantiating the redirector above forces the first class loads through the
            // mixin transformer, so mixin select/prepare has completed by this point. If a
            // legacy coremod transformer re-entered mixin during prepare (e.g. Techguns
            // resolving supertypes), the processor's re-entrance lock depth is now leaked
            // and the nested-loaded class was blacklisted in the loader's invalid-class
            // cache; restore both here, then pre-load mixin targets that are known to be
            // resolved from inside other classes' transforms before their first real use.
            MixinReEntranceLockFix.clearLeakedLock();
            MixinReEntranceLockFix.clearInvalidVanillaClasses();
            try {
                MixinReEntranceLockFix.preloadClasses(Tessellator.class);
            } finally {
                MixinReEntranceLockFix.clearLeakedLock();
                MixinReEntranceLockFix.clearInvalidVanillaClasses();
            }
        }
        return new String[0];
    }
}
