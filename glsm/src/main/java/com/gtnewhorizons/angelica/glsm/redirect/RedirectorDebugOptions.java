package com.gtnewhorizons.angelica.glsm.redirect;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class RedirectorDebugOptions {
    private static final Path CONFIG_PATH = Paths.get("config", "actinium-options.json");
    private static final Path LEGACY_CONFIG_PATH = Paths.get("config", "embeddium-options.json");
    private static final Snapshot SNAPSHOT = loadSnapshot();

    private RedirectorDebugOptions() {
    }

    public static boolean enableLogSpam() {
        return getBooleanOverride("angelica.redirectorLogspam", SNAPSHOT.redirectorLogSpam);
    }

    public static boolean enableDebug() {
        return getBooleanOverride("actinium.redirectorDebug", SNAPSHOT.redirectorDebug);
    }

    public static boolean enableClassDump() {
        return getBooleanOverride("angelica.dumpClass", SNAPSHOT.classDump);
    }

    private static boolean getBooleanOverride(String key, boolean fallback) {
        String override = System.getProperty(key);
        return override != null ? Boolean.parseBoolean(override) : fallback;
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
                getBoolean(debug, "enable_redirector_class_dump")
            );
        } catch (IOException | RuntimeException ignored) {
            return Snapshot.DEFAULT;
        }
    }

    private static boolean getBoolean(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean() && element.getAsBoolean();
    }

    private record Snapshot(boolean redirectorDebug, boolean redirectorLogSpam, boolean classDump) {
        private static final Snapshot DEFAULT = new Snapshot(false, false, false);
    }
}
