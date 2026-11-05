package com.gtnewhorizons.angelica.proxy;

import com.dhj.actinium.config.AnimationMode;
import com.dhj.actinium.config.ManagedEnum;
import com.gtnewhorizons.angelica.Tags;
import com.gtnewhorizons.angelica.glsm.shader.ShaderDiskCache;
import dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions;
import com.dhj.actinium.runtime.ActiniumRuntime;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class ClientProxy {
    private static final Logger LOGGER = LogManager.getLogger("Actinium");
    public static final ManagedEnum<AnimationMode> animationsMode = new ManagedEnum<>(AnimationMode.VISIBLE_ONLY);

    private ClientProxy() {
    }

    public static SodiumGameOptions options() {
        return ActiniumRuntime.options();
    }

    /**
     * Points the shader disk cache at the instance directory. The salt carries the mod version and the
     * jar timestamp so an updated build starts from an empty cache instead of serving entries produced
     * by different shader code.
     */
    public static void configureShaderDiskCache() {
        final File jar = jarFile();
        if (jar == null || !jar.isFile()) return;
        ShaderDiskCache.configure(Minecraft.getMinecraft().gameDir.toPath().resolve("angelica").resolve("shadercache"),
            Tags.VERSION + "|" + jar.length() + "|" + jar.lastModified());
    }

    private static File jarFile() {
        try {
            String location = ClientProxy.class.getProtectionDomain().getCodeSource().getLocation().toString();
            if (location.startsWith("jar:")) {
                final int separator = location.indexOf("!/");
                location = location.substring(4, separator < 0 ? location.length() : separator);
            }
            final Path path = Paths.get(new URI(location));
            return path.toFile();
        } catch (Exception e) {
            LOGGER.warn("Could not resolve the mod jar location, shader disk cache stays disabled", e);
            return null;
        }
    }
}
