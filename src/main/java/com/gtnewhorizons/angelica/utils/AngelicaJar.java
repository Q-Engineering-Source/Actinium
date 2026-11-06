package com.gtnewhorizons.angelica.utils;

import com.gtnewhorizons.angelica.Tags;
import com.gtnewhorizons.angelica.glsm.shader.ShaderDiskCache;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;

/**
 * Locates the jar or classes directory the mod was loaded from, and derives the shader disk cache
 * settings from it. The location is both the cache salt input and the source the shader warmup
 * reads class names from, so both call sites share one resolution path.
 */
public final class AngelicaJar {
    private AngelicaJar() {
    }

    /**
     * The jar or classes directory the mod was loaded from, or null when the code source is
     * unavailable or not a filesystem path.
     */
    @Nullable
    public static Path location() {
        final ProtectionDomain domain = AngelicaJar.class.getProtectionDomain();
        final CodeSource codeSource = domain != null ? domain.getCodeSource() : null;
        if (codeSource == null || codeSource.getLocation() == null) {
            return null;
        }
        return parseLocation(codeSource.getLocation().toString());
    }

    /**
     * Resolves a code source URL to a filesystem path. Accepts plain {@code file:} URLs and nested
     * {@code jar:file:...!/entry} URLs.
     */
    @Nullable
    public static Path parseLocation(String location) {
        String resolved = location;
        if (resolved.startsWith("jar:")) {
            resolved = resolved.substring("jar:".length());
            final int separator = resolved.indexOf("!/");
            if (separator >= 0) {
                resolved = resolved.substring(0, separator);
            }
        }
        try {
            return Paths.get(new URI(resolved));
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Points the shader disk cache at the instance directory. The salt carries the mod version and
     * the jar timestamp so an updated build starts from an empty cache instead of serving entries
     * produced by different shader code.
     */
    public static void configureShaderDiskCache(@Nullable File jar) {
        if (jar == null || !jar.isFile()) {
            return;
        }
        ShaderDiskCache.configure(Minecraft.getMinecraft().gameDir.toPath().resolve("angelica").resolve("shadercache"),
            Tags.VERSION + "|" + jar.length() + "|" + jar.lastModified());
    }
}
