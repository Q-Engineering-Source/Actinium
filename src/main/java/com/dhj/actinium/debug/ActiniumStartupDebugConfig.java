package com.dhj.actinium.debug;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Reads debug options needed before the normal runtime configuration is available.
 */
public final class ActiniumStartupDebugConfig {
    private static final Logger LOGGER = LogManager.getLogger("ActiniumStartupDebugConfig");
    private static final Path CONFIG_PATH = Paths.get("config", "actinium-options.json");
    private static final Path LEGACY_CONFIG_PATH = Paths.get("config", "embeddium-options.json");
    private static final Snapshot SNAPSHOT = loadSnapshot();
    private static final boolean LWJGL_DEBUG = resolveLwjglDebug(System.getProperty("actinium.lwjglDebug"), SNAPSHOT.enableLwjglDebug);

    private ActiniumStartupDebugConfig() {
    }

    /**
     * Returns whether startup must request an OpenGL debug context and install its driver callback.
     * This is intentionally separate from the runtime GL state checkpoint option.
     */
    public static boolean enableLwjglDebug() {
        return LWJGL_DEBUG;
    }

    public static boolean enableRedirectorDebug() {
        return getBooleanOverride("actinium.redirectorDebug", SNAPSHOT.redirectorDebug);
    }

    public static boolean enableRedirectorLogSpam() {
        return getBooleanOverride("angelica.redirectorLogspam", SNAPSHOT.redirectorLogSpam);
    }

    public static boolean enableClassDump() {
        return getBooleanOverride("angelica.dumpClass", SNAPSHOT.classDump);
    }

    private static boolean getBooleanOverride(String property, boolean fallback) {
        String override = System.getProperty(property);
        return override != null ? Boolean.parseBoolean(override) : fallback;
    }

    static boolean resolveLwjglDebug(String override, boolean configured) {
        return override != null ? Boolean.parseBoolean(override) : configured;
    }

    private static Snapshot loadSnapshot() {
        Path path = Files.isRegularFile(CONFIG_PATH) ? CONFIG_PATH : LEGACY_CONFIG_PATH;
        if (!Files.isRegularFile(path)) {
            return Snapshot.DEFAULT;
        }

        try (Reader reader = Files.newBufferedReader(path)) {
            JsonElement root = new JsonParser().parse(reader);
            if (!root.isJsonObject()) {
                return Snapshot.DEFAULT;
            }

            JsonObject debug = root.getAsJsonObject().getAsJsonObject("debug");
            if (debug == null) {
                return Snapshot.DEFAULT;
            }

            return new Snapshot(
                getBoolean(debug, "enable_redirector_debug"),
                getBoolean(debug, "enable_redirector_log_spam"),
                getBoolean(debug, "enable_redirector_class_dump"),
                getBoolean(debug, "enable_lwjgl_debug")
            );
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Failed to read startup debug options from {}", path, e);
            return Snapshot.DEFAULT;
        }
    }

    private static boolean getBoolean(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean() && element.getAsBoolean();
    }

    private record Snapshot(boolean redirectorDebug, boolean redirectorLogSpam, boolean classDump, boolean enableLwjglDebug) {
        private static final Snapshot DEFAULT = new Snapshot(false, false, false, false);
    }
}
