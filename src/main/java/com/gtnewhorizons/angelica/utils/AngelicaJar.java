package com.gtnewhorizons.angelica.utils;

import com.gtnewhorizons.angelica.Tags;
import com.gtnewhorizons.angelica.glsm.shader.ShaderDiskCache;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.CodeSource;

/** Locates the loaded Actinium artifact and configures its shader cache during client startup. */
public final class AngelicaJar {
    /** Logs cache bootstrap failures without turning an optional cache into a startup failure. */
    private static final Logger LOGGER = LogManager.getLogger("AngelicaJar");

    /** Prevents constructing this startup utility. */
    private AngelicaJar() {}

    /** Configures the cache for an installed jar and leaves it disabled for development classes. */
    public static void configureShaderDiskCache() {
        final Path artifact = location();
        if (artifact == null) {
            ShaderDiskCache.disable();
            return;
        }
        try {
            if (Files.isDirectory(artifact)) {
                ShaderDiskCache.disable();
                LOGGER.debug("Shader disk cache is disabled for classes directory {}", artifact);
                return;
            }
            if (!Files.isRegularFile(artifact)) {
                ShaderDiskCache.disable();
                LOGGER.warn("Shader disk cache is disabled because the loaded artifact is not a regular jar: {}", artifact);
                return;
            }
            final BasicFileAttributes attributes = Files.readAttributes(artifact, BasicFileAttributes.class);
            final Path cacheRoot = Minecraft.getMinecraft().gameDir.toPath()
                .resolve("angelica")
                .resolve("shadercache");
            final String salt = Tags.VERSION + '|' + attributes.size() + '|' + attributes.lastModifiedTime().toMillis();
            ShaderDiskCache.configure(cacheRoot, salt);
        } catch (IOException | RuntimeException exception) {
            ShaderDiskCache.disable();
            LOGGER.warn("Shader disk cache could not be configured from {}", artifact, exception);
        }
    }

    /** Resolves the code source path and records failures that would otherwise look like cache misses. */
    private static Path location() {
        try {
            final CodeSource codeSource = AngelicaJar.class.getProtectionDomain().getCodeSource();
            if (codeSource == null) throw new IllegalStateException("Actinium code source is unavailable");
            return Paths.get(codeSource.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (URISyntaxException | RuntimeException exception) {
            LOGGER.warn("Could not locate the loaded Actinium artifact; shader disk cache remains disabled", exception);
            return null;
        }
    }
}
