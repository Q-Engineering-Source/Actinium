package com.dhj.actinium.compat;

import net.minecraftforge.common.ForgeEarlyConfig;
import net.minecraftforge.common.ForgeModContainer;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;

/**
 * Bridges the OpenGL fields whose names differ between Outlands and legacy Cleanroom.
 *
 * <p>Outlands exposes lower camel case fields while older Cleanroom releases expose uppercase fields. Neither
 * loader provides a shared setter, and legacy LWJGLXX reads these fields before the display context is created. The
 * compatibility lookup is therefore isolated here so the rest of Actinium never links against one loader's field
 * spelling.</p>
 */
public final class ForgeEarlyConfigCompat {
    private static final Logger LOGGER = LogManager.getLogger("Actinium");
    private static final Field VERSION_MAJOR = field("openglVersionMajor", "OPENGL_VERSION_MAJOR");
    private static final Field VERSION_MINOR = field("openglVersionMinor", "OPENGL_VERSION_MINOR");
    private static final Field COMPAT_PROFILE = field("openglCompatProfile", "OPENGL_COMPAT_PROFILE");
    private static final Field DEBUG_CONTEXT = field("openglDebugContext", "OPENGL_DEBUG_CONTEXT");

    private ForgeEarlyConfigCompat() {
    }

    /** Captures the OpenGL settings that the loader will read during context creation. */
    public record Snapshot(int major, int minor, boolean compatibilityProfile, boolean debugContext) {
    }

    /** Reads the current OpenGL settings from the active loader. */
    public static Snapshot snapshot() {
        return new Snapshot(
            readInt(VERSION_MAJOR),
            readInt(VERSION_MINOR),
            readBoolean(COMPAT_PROFILE),
            readBoolean(DEBUG_CONTEXT)
        );
    }

    /** Applies an OpenGL core-profile request before the loader creates the display context. */
    public static void applyCoreProfile(int major, int minor, boolean debugContext) {
        restore(new Snapshot(major, minor, false, debugContext));
    }

    /** Restores the loader's compatibility-profile settings after the core context has been created. */
    public static void restoreCompatibilityProfile(int major, int minor, boolean debugContext) {
        restore(new Snapshot(major, minor, true, debugContext));
    }

    /** Restores an exact snapshot after a temporary change to the loader's context settings. */
    public static void restore(Snapshot snapshot) {
        writeInt(VERSION_MAJOR, snapshot.major());
        writeInt(VERSION_MINOR, snapshot.minor());
        writeBoolean(COMPAT_PROFILE, snapshot.compatibilityProfile());
        writeBoolean(DEBUG_CONTEXT, snapshot.debugContext());
    }

    /**
     * Saves the restored profile through Outlands' shared {@code forge.cfg} holder or legacy ConfigManager.
     *
     * <p>Outlands reads fields from its shared configuration during sync, so restored values must be written to
     * that configuration directly. Legacy ConfigManager writes the registered config class back to its file.</p>
     */
    public static void persistCompatibilityProfile() {
        try {
            if (VERSION_MAJOR.getName().equals("OPENGL_VERSION_MAJOR")) {
                ConfigManager.sync(ForgeEarlyConfig.class);
            } else {
                persistOutlandsProfile(ForgeModContainer.getConfig(), snapshot());
            }
        } catch (RuntimeException failure) {
            LOGGER.error("Failed to persist the restored ForgeEarlyConfig profile", failure);
            throw failure;
        }
    }

    static void persistOutlandsProfile(Configuration configuration, Snapshot restored) {
        configuration.get("early", "openglVersionMajor", restored.major()).setValue(restored.major());
        configuration.get("early", "openglVersionMinor", restored.minor()).setValue(restored.minor());
        configuration.get("early", "openglCompatProfile", restored.compatibilityProfile())
            .setValue(restored.compatibilityProfile());
        configuration.get("early", "openglDebugContext", restored.debugContext()).setValue(restored.debugContext());
        configuration.save();
    }

    private static int readInt(Field field) {
        try {
            return field.getInt(null);
        } catch (IllegalAccessException e) {
            throw accessFailure(field, e);
        }
    }

    private static boolean readBoolean(Field field) {
        try {
            return field.getBoolean(null);
        } catch (IllegalAccessException e) {
            throw accessFailure(field, e);
        }
    }

    private static void writeInt(Field field, int value) {
        try {
            field.setInt(null, value);
        } catch (IllegalAccessException e) {
            throw accessFailure(field, e);
        }
    }

    private static void writeBoolean(Field field, boolean value) {
        try {
            field.setBoolean(null, value);
        } catch (IllegalAccessException e) {
            throw accessFailure(field, e);
        }
    }

    private static Field field(String currentName, String legacyName) {
        try {
            return ForgeEarlyConfig.class.getField(currentName);
        } catch (NoSuchFieldException currentMissing) {
            try {
                return ForgeEarlyConfig.class.getField(legacyName);
            } catch (NoSuchFieldException legacyMissing) {
                IllegalStateException failure = new IllegalStateException(
                    "ForgeEarlyConfig exposes neither " + currentName + " nor " + legacyName,
                    legacyMissing
                );
                LOGGER.error(failure.getMessage(), failure);
                throw failure;
            }
        }
    }

    private static IllegalStateException accessFailure(Field field, IllegalAccessException cause) {
        IllegalStateException failure = new IllegalStateException(
            "Cannot access ForgeEarlyConfig field " + field.getName(),
            cause
        );
        LOGGER.error(failure.getMessage(), failure);
        return failure;
    }
}
