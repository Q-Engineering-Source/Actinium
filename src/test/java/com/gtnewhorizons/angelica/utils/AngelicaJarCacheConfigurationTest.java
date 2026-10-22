package com.gtnewhorizons.angelica.utils;

import com.gtnewhorizons.angelica.glsm.shader.ShaderDiskCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises shader-cache bootstrap with the same artifact and game-directory inputs used by the client. */
class AngelicaJarCacheConfigurationTest {

    /** Supplies isolated artifact and game directories without touching a user's installation. */
    @TempDir
    Path temporaryDirectory;

    /** Clears another test's cache generation before exercising the bootstrap. */
    @BeforeEach
    void disablePreviousGeneration() {
        ShaderDiskCache.disable();
    }

    /** Invalidates queued cleanup before JUnit removes the temporary game directory. */
    @AfterEach
    void disableTestGeneration() {
        ShaderDiskCache.disable();
    }

    /** Configures below the game directory and round-trips a saved-program blob through the real disk cache. */
    @Test
    void jarArtifactConfiguresAndUsesTheGameDirectoryCache() throws IOException {
        final Path artifact = temporaryDirectory.resolve("Actinium dev.jar");
        Files.write(artifact, new byte[] {1, 2, 3});
        final Path gameDir = temporaryDirectory.resolve("Minecraft user directory");
        final Path cacheRoot = gameDir.resolve("angelica").resolve("shadercache");

        AngelicaJar.configureShaderDiskCache(artifact.toFile(), gameDir);

        assertTrue(ShaderDiskCache.isEnabled(), "an installed jar and a game directory configure the disk cache");
        assertTrue(Files.isRegularFile(cacheRoot.resolve("salt")), "the configured root stores the artifact salt");
        final ShaderDiskCache.Key key = ShaderDiskCache.key("gl-program/TestPack").str("program source");
        final byte[] binary = {9, 8, 7, 6};
        assertTrue(ShaderDiskCache.putBlob(key, "format-1", binary), "the configured root accepts a saved program");

        final ShaderDiskCache.Blob restored = ShaderDiskCache.getBlob(key);
        assertNotNull(restored, "the same configured root can restore a saved program");
        assertArrayEquals(binary, restored.data());
        assertTrue(Files.isRegularFile(cacheRoot.resolve("gl-program/TestPack").resolve(key.hex() + ".bin")),
            "the saved binary is written below the expected cache root");
    }

    /** Keeps development classes-directory sources from enabling a cache that cannot be salted as a jar. */
    @Test
    void classesDirectorySourceLeavesTheDiskCacheDisabled() throws IOException {
        final Path classesDirectory = Files.createDirectory(temporaryDirectory.resolve("classes"));
        final Path gameDir = temporaryDirectory.resolve("game");

        AngelicaJar.configureShaderDiskCache(classesDirectory.toFile(), gameDir);

        assertFalse(ShaderDiskCache.isEnabled());
        assertFalse(Files.exists(gameDir.resolve("angelica").resolve("shadercache")),
            "a classes directory does not create a persistent shader cache");
    }
}
