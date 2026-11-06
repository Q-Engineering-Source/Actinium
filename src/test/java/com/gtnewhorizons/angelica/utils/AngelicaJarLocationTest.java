package com.gtnewhorizons.angelica.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AngelicaJarLocationTest {

    @Test
    void resolvesTheClassesDirectoryTheModWasLoadedFrom() {
        final Path location = AngelicaJar.location();

        assertNotNull(location, "the test runtime always loads Actinium from a directory or a jar");
        assertTrue(Files.exists(location), "resolved location must exist: " + location);
    }

    @Test
    void stripsTheJarUrlPrefixAndEntrySeparator() {
        final Path jar = Path.of("mods", "Actinium-1.0.0.jar").toAbsolutePath();
        final String nested = "jar:" + jar.toUri() + "!/com/dhj/actinium/Actinium.class";

        assertEquals(jar, AngelicaJar.parseLocation(nested));
    }

    @Test
    void keepsThePathOfAJarWithoutAnEntrySeparator() {
        final Path jar = Path.of("mods", "Actinium-1.0.0.jar").toAbsolutePath();

        assertEquals(jar, AngelicaJar.parseLocation("jar:" + jar.toUri()));
    }

    @Test
    void resolvesAPlainFileUrlToItsPath(@TempDir Path dir) throws Exception {
        final File file = Files.createFile(dir.resolve("Actinium.jar")).toFile();

        assertEquals(file.toPath(), AngelicaJar.parseLocation(file.toURI().toString()));
    }

    @Test
    void resolvesADirectoryUrlWithAnEscapedSpace(@TempDir Path dir) throws Exception {
        final File spaced = Files.createDirectory(dir.resolve("with space")).toFile();

        assertEquals(spaced.toPath(), AngelicaJar.parseLocation(spaced.toURI().toString()));
    }
}
