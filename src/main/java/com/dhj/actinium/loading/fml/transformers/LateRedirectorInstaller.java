package com.dhj.actinium.loading.fml.transformers;

import com.gtnewhorizons.angelica.loading.fml.transformers.AngelicaRedirectorTransformer;
import com.gtnewhorizons.angelica.loading.fml.transformers.EarlyRedirectorTransformer;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import top.outlands.foundation.TransformerDelegate;

import java.util.List;

/** Installs the full GL redirector through the active loader's transformer registry. */
public final class LateRedirectorInstaller {
    private static final Logger LOGGER = LogManager.getLogger("ActiniumRedirector");
    private static final String TRANSFORMER_DELEGATE_RESOURCE =
        "top/outlands/foundation/TransformerDelegate.class";
    private static final String EARLY_REDIRECTOR_CLASS = EarlyRedirectorTransformer.class.getName();
    private static final String FULL_REDIRECTOR_CLASS = AngelicaRedirectorTransformer.class.getName();

    private LateRedirectorInstaller() {
    }

    public static void install() {
        if (LateRedirectorInstaller.class.getClassLoader().getResource(TRANSFORMER_DELEGATE_RESOURCE) == null) {
            installLegacy(Launch.classLoader);
            return;
        }
        Outlands.install();
    }

    private static void installLegacy(LaunchClassLoader classLoader) {
        List<IClassTransformer> transformers = classLoader.getTransformers();
        transformers.stream()
            .filter(EarlyRedirectorTransformer.class::isInstance)
            .map(EarlyRedirectorTransformer.class::cast)
            .forEach(EarlyRedirectorTransformer::disable);
        if (transformers.stream().noneMatch(transformer -> FULL_REDIRECTOR_CLASS.equals(
            transformer.getClass().getName()))) {
            LOGGER.debug("Registering transformer {}", FULL_REDIRECTOR_CLASS);
            classLoader.registerTransformer(FULL_REDIRECTOR_CLASS);
        }
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
