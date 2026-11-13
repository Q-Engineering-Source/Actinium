package com.dhj.actinium.loading.fml.transformers;

import com.gtnewhorizons.angelica.loading.fml.transformers.AngelicaRedirectorTransformer;
import com.gtnewhorizons.angelica.loading.fml.transformers.EarlyRedirectorTransformer;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import top.outlands.foundation.TransformerDelegate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Installs the full GL redirector through the active loader's transformer registry. */
public final class LateRedirectorInstaller {
    private static final Logger LOGGER = LogManager.getLogger("ActiniumRedirector");
    private static final String TRANSFORMER_DELEGATE_RESOURCE =
        "top/outlands/foundation/TransformerDelegate.class";
    private static final String EARLY_REDIRECTOR_CLASS = EarlyRedirectorTransformer.class.getName();
    private static final String FULL_REDIRECTOR_CLASS = AngelicaRedirectorTransformer.class.getName();
    private static final String LATE_TWEAKER_CLASS =
        "com.gtnewhorizons.angelica.loading.fml.tweakers.AngelicaLateTweaker";

    private LateRedirectorInstaller() {
    }

    @SuppressWarnings("unchecked")
    public static void prepareTweaker() {
        try {
            Launch.classLoader.loadClass(LATE_TWEAKER_CLASS);
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("Could not preload " + LATE_TWEAKER_CLASS, failure);
        }

        Object value = Launch.blackboard.get("TweakClasses");
        List<String> tweaks = null;
        if (value instanceof List<?>) {
            tweaks = (List<String>) value;
        }
        if (tweaks == null) {
            tweaks = new ArrayList<>();
            Launch.blackboard.put("TweakClasses", tweaks);
        }
        if (!tweaks.contains(LATE_TWEAKER_CLASS)) {
            tweaks.add(LATE_TWEAKER_CLASS);
        }
    }

    public static void install() {
        if (LateRedirectorInstaller.class.getClassLoader().getResource(TRANSFORMER_DELEGATE_RESOURCE) == null) {
            installLegacy(Launch.classLoader);
            return;
        }
        Outlands.install();
    }

    private static void installLegacy(LaunchClassLoader classLoader) {
        try {
            List<IClassTransformer> transformers = mutableTransformers(classLoader);
            transformers.removeIf(transformer -> EARLY_REDIRECTOR_CLASS.equals(transformer.getClass().getName()));
            if (transformers.stream().noneMatch(transformer -> FULL_REDIRECTOR_CLASS.equals(
                transformer.getClass().getName()))) {
                LOGGER.debug("Registering transformer {}", FULL_REDIRECTOR_CLASS);
                classLoader.registerTransformer(FULL_REDIRECTOR_CLASS);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not replace the early GL redirector", failure);
        }
    }

    /**
     * Vanilla LaunchWrapper exposes only an unmodifiable transformer view and no removal API.
     * The upstream Angelica implementation uses the same private registry for early removal.
     */
    @SuppressWarnings("unchecked")
    private static List<IClassTransformer> mutableTransformers(LaunchClassLoader classLoader)
        throws ReflectiveOperationException {
        Field transformersField = classLoader.getClass().getDeclaredField("transformers");
        transformersField.setAccessible(true);
        return (List<IClassTransformer>) transformersField.get(classLoader);
    }

    private static final class Outlands {
        private static void install() {
            TransformerDelegate.unRegisterTransformer(EARLY_REDIRECTOR_CLASS);
            boolean alreadyRegistered = TransformerDelegate.getTransformers().stream()
                .anyMatch(transformer -> FULL_REDIRECTOR_CLASS.equals(transformer.getClass().getName()));
            if (!alreadyRegistered) {
                LOGGER.debug("Registering transformer {}", FULL_REDIRECTOR_CLASS);
                TransformerDelegate.registerTransformer(new AngelicaRedirectorTransformer());
            }
        }
    }
}
