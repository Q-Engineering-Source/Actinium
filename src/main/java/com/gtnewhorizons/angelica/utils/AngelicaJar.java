package com.gtnewhorizons.angelica.utils;

import com.gtnewhorizons.angelica.Tags;
import com.gtnewhorizons.angelica.glsm.shader.ShaderDiskCache;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** Configures the shader cache from the Actinium source and Minecraft game directory during startup. */
public final class AngelicaJar {
    /** Logs cache bootstrap failures without turning an optional cache into a startup failure. */
    private static final Logger LOGGER = LogManager.getLogger("AngelicaJar");

    /** Prevents constructing this startup utility. */
    private AngelicaJar() {}

    /** Configures the cache for an installed jar and leaves it disabled for development classes. */
    public static void configureShaderDiskCache(@Nullable File artifact, Path gameDir) {
        if (artifact == null) {
            ShaderDiskCache.disable();
            LOGGER.warn("Could not locate the loaded Actinium artifact; shader disk cache remains disabled");
            return;
        }
        final Path artifactPath = artifact.toPath().toAbsolutePath().normalize();
        try {
            if (Files.isDirectory(artifactPath)) {
                ShaderDiskCache.disable();
                LOGGER.debug("Shader disk cache is disabled for classes directory {}", artifactPath);
                return;
            }
            if (!Files.isRegularFile(artifactPath)) {
                ShaderDiskCache.disable();
                LOGGER.warn("Shader disk cache is disabled because the loaded artifact is not a regular jar: {}", artifactPath);
                return;
            }
            final BasicFileAttributes attributes = Files.readAttributes(artifactPath, BasicFileAttributes.class);
            final Path cacheRoot = gameDir.resolve("angelica").resolve("shadercache");
            final String salt = Tags.VERSION + '|' + attributes.size() + '|' + attributes.lastModifiedTime().toMillis();
            ShaderDiskCache.configure(cacheRoot, salt);
        } catch (IOException | RuntimeException exception) {
            ShaderDiskCache.disable();
            LOGGER.warn("Shader disk cache could not be configured from {}", artifactPath, exception);
        }
    }
}
