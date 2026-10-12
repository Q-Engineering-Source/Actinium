package com.gtnewhorizons.angelica.iris;

import com.gtnewhorizons.angelica.utils.AngelicaJar;
import net.coderbot.iris.Iris;
import net.coderbot.iris.parsing.IrisFunctions;
import net.coderbot.iris.shaderpack.ProgramSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Prepares the first shader pack load on a background thread while the game sits at the main menu:
 * the shader classes are resolved ahead of time, the selected pack is read through
 * {@link Iris#prepareSelectedShaderPack()}, and the transforms of the program set that read returned
 * are warmed on the transform pool, so loading the pack finds them cached.
 */
public final class ShaderWarmup {
    private static final Logger LOGGER = LogManager.getLogger("Actinium/ShaderWarmup");
    private static final String[] PACKAGES = { "net/coderbot/iris/", "kroppeb/stareval/" };
    private static final String SKIPPED_PACKAGE = "net/coderbot/iris/compat/dh/";

    private static boolean started;

    private ShaderWarmup() {}

    /** Main thread only. */
    public static void start() {
        if (started) return;
        started = true;
        final Supplier<ProgramSet> readSelectedPack = Iris.prepareSelectedShaderPack();
        final Thread thread = new Thread(() -> warmUp(readSelectedPack), "Actinium-Shader-Warmup");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void warmUp(@Nullable Supplier<ProgramSet> readSelectedPack) {
        preloadClasses();
        if (readSelectedPack == null) return;
        final ProgramSet programs = readSelectedPack.get();
        if (programs != null) {
            Iris.warmupPreparedTransforms(programs);
        }
    }

    private static void preloadClasses() {
        final List<String> names;
        try {
            names = listClasses();
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not list shader classes to preload", e);
            return;
        }
        final ClassLoader loader = Iris.class.getClassLoader();
        for (String name : names) {
            try {
                Class.forName(name, false, loader);
            } catch (Throwable e) {
                // A class that cannot load here fails the same way when the pipeline reaches it
                LOGGER.debug("Could not preload shader class {}", name, e);
            }
        }
        try {
            Class.forName(IrisFunctions.class.getName(), true, loader);
        } catch (Throwable e) {
            LOGGER.debug("Could not preload {}", IrisFunctions.class.getName(), e);
        }
    }

    private static List<String> listClasses() throws IOException {
        final Path source = AngelicaJar.location();
        final List<String> names = new ArrayList<>();
        if (source == null) return names;
        if (Files.isDirectory(source)) {
            try (Stream<Path> files = Files.walk(source)) {
                files.forEach(file -> addIfShaderClass(names, source.relativize(file).toString().replace('\\', '/')));
            }
        } else {
            try (ZipFile jar = new ZipFile(source.toFile())) {
                final Enumeration<? extends ZipEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    addIfShaderClass(names, entries.nextElement().getName());
                }
            }
        }
        Collections.sort(names);
        return names;
    }

    private static void addIfShaderClass(List<String> names, String entry) {
        if (!entry.endsWith(".class") || entry.startsWith(SKIPPED_PACKAGE)) return;
        for (String packagePath : PACKAGES) {
            if (entry.startsWith(packagePath)) {
                names.add(entry.substring(0, entry.length() - ".class".length()).replace('/', '.'));
                return;
            }
        }
    }
}
