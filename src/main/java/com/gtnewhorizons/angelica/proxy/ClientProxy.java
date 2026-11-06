package com.gtnewhorizons.angelica.proxy;

import com.dhj.actinium.config.AnimationMode;
import com.dhj.actinium.config.ManagedEnum;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.gtnewhorizons.angelica.utils.AngelicaJar;
import dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;

public final class ClientProxy {
    private static final Logger LOGGER = LogManager.getLogger("Actinium");
    public static final ManagedEnum<AnimationMode> animationsMode = new ManagedEnum<>(AnimationMode.VISIBLE_ONLY);

    private ClientProxy() {
    }

    public static SodiumGameOptions options() {
        return ActiniumRuntime.options();
    }

    /** Points the shader disk cache at the instance directory, keyed by the mod jar the code was loaded from. */
    public static void configureShaderDiskCache() {
        final Path location = AngelicaJar.location();
        if (location == null) {
            LOGGER.warn("Could not resolve the mod jar location, shader disk cache stays disabled");
            return;
        }
        AngelicaJar.configureShaderDiskCache(location.toFile());
    }
}
