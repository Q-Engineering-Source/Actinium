package com.gtnewhorizons.angelica.utils;

import com.gtnewhorizons.angelica.Tags;
import com.gtnewhorizons.angelica.glsm.shader.ShaderDiskCache;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Locates the artifact this mod was loaded from.
 *
 * <p>Shaders are compiled into caches keyed by the exact build: the jar's size and timestamp identify
 * the code that produced a cached blob, so a mod update invalidates the cache even when the version
 * string is unchanged (a dev build, for instance).</p>
 */
public final class AngelicaJar {
    private AngelicaJar() {}

    /** {@return the jar or classes directory this mod was loaded from, or null when that cannot be resolved} */
    @Nullable
    public static Path location() {
        try {
            String location = AngelicaJar.class.getProtectionDomain().getCodeSource().getLocation().toString();
            if (location.startsWith("jar:")) {
                final int separator = location.indexOf("!/");
                location = location.substring("jar:".length(), separator < 0 ? location.length() : separator);
            }
            return Paths.get(new URI(location));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Points the shader disk cache at the instance's cache directory, salted with this build.
     *
     * <p>Does nothing in a dev environment, where the code source is a classes directory rather than
     * the jar the caches are meant to be keyed to.</p>
     */
    public static void configureShaderDiskCache(@Nullable File jar) {
        if (jar == null || !jar.isFile()) return;
        ShaderDiskCache.configure(Minecraft.getMinecraft().gameDir.toPath().resolve("angelica").resolve("shadercache"),
            Tags.VERSION + "|" + jar.length() + "|" + jar.lastModified());
    }
}
