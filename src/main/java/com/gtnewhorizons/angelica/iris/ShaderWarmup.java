package com.gtnewhorizons.angelica.iris;

import com.gtnewhorizons.angelica.utils.AngelicaJar;
import net.coderbot.iris.Iris;
import net.coderbot.iris.parsing.IrisFunctions;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads the shader classes on a background thread while the game sits at the main menu, so the first
 * shader pack load does not pay for class loading and linking on the render thread.
 */
public final class ShaderWarmup {
    private static final Logger LOGGER = LogManager.getLogger("ActiniumShaderWarmup");
    private static final String[] PACKAGES = { "net/coderbot/iris/", "kroppeb/stareval/" };
    private static final String SKIPPED_PACKAGE = "net/coderbot/iris/compat/dh/";
    private static final String CLASS_SUFFIX = ".class";

    private static boolean started;

    private ShaderWarmup() {
    }

    /** Starts the warmup once. Must be called on the client thread. */
    public static void start() {
        if (started) {
            return;
        }
        started = true;
        final Runnable readSelectedPack = Iris.prepareSelectedShaderPack();
        final Thread thread = new Thread(() -> {
            preloadClasses();
            if (readSelectedPack != null) {
                readSelectedPack.run();
            }
        }, "Actinium-Shader-Warmup");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void preloadClasses() {
        final List<String> names;
        try {
            names = listClasses(AngelicaJar.location());
        } catch (Exception e) {
            LOGGER.warn("Could not list the shader classes to preload", e);
            return;
        }
        final ClassLoader loader = Iris.class.getClassLoader();
        for (String name : names) {
            try {
                Class.forName(name, false, loader);
            } catch (Throwable t) {
                // A class that cannot load here fails the same way when the pipeline reaches it.
                LOGGER.debug("Could not preload the shader class {}", name, t);
            }
        }
        try {
            // Initializing this one builds the expression function tables the shader parser resolves against.
            Class.forName(IrisFunctions.class.getName(), true, loader);
        } catch (Throwable t) {
            LOGGER.debug("Could not initialize the shader expression functions ahead of time", t);
        }
    }

    /**
     * The shader classes inside a jar or classes directory, sorted so the warmup order is stable.
     * Returns an empty list when the source is unavailable.
     */
    static List<String> listClasses(@Nullable Path source) throws Exception {
        final List<String> names = new ArrayList<>();
        if (source == null) {
            return names;
        }
        if (Files.isDirectory(source)) {
            try (Stream<Path> files = Files.walk(source)) {
                files.forEach(file -> addIfShaderClass(names, source.relativize(file).toString().replace('\\', '/')));
            }
        } else if (Files.isRegularFile(source)) {
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
        if (!entry.endsWith(CLASS_SUFFIX) || entry.startsWith(SKIPPED_PACKAGE)) {
            return;
        }
        for (String packagePath : PACKAGES) {
            if (entry.startsWith(packagePath)) {
                names.add(entry.substring(0, entry.length() - CLASS_SUFFIX.length()).replace('/', '.'));
                return;
            }
        }
    }
}
