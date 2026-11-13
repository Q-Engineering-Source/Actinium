package com.dhj.actinium.debug;

import com.dhj.actinium.compat.ForgeEarlyConfigCompat;
import org.lwjgl.opengl.ContextAttribs;

import java.util.function.Function;

/**
 * Creates the core-profile context attributes required during early Minecraft startup.
 * Keeping this logic outside every Mixin configuration package allows transformed classes to load it normally.
 */
public final class CoreProfileContextAttributes {
    private CoreProfileContextAttributes() {
    }

    /**
     * Mutable implementations return {@code null} after changing the current attributes, while immutable
     * implementations return a new instance. Both forms produce the requested core-profile attributes.
     */
    public static ContextAttribs create(int major, int minor, boolean lwjglDebug) {
        ContextAttribs attributes = new ContextAttribs(major, minor);
        return configure(
            attributes,
            value -> value.withProfileCore(true),
            value -> value.withForwardCompatible(true),
            value -> value.withDebug(lwjglDebug)
        );
    }

    /**
     * Legacy LWJGLXX ignores {@code Display.create(PixelFormat, ContextAttribs)} and reads the desired version,
     * profile, and debug state from {@code ForgeEarlyConfig}. Without this override, macOS can
     * downgrade a requested modern core-profile context to the legacy 2.1 Metal context.
     */
    public static void applyForgeEarlyCoreProfile(int major, int minor, boolean lwjglDebug) {
        ForgeEarlyConfigCompat.applyCoreProfile(major, minor, lwjglDebug);
    }

    /**
     * Restores the Forge early OpenGL config after the core-profile context was created. Legacy LWJGLXX reads
     * these fields when creating the context; Outlands stores them in the {@code [early]} section of {@code forge.cfg}
     * and legacy Cleanroom stores them in {@code forge_early.cfg}.
     * Leaving the core-profile request in place breaks startup without Actinium because the default path expects the
     * compatibility profile. The compatibility flag is restored unconditionally because Actinium is the only writer
     * that sets it to false.
     */
    public static void restoreForgeEarlyCompatProfile(int originalMajor, int originalMinor, boolean originalDebug) {
        ForgeEarlyConfigCompat.restoreCompatibilityProfile(originalMajor, originalMinor, originalDebug);
    }

    /**
     * Persists the restored compatibility profile back to the loader's Forge configuration, using the Outlands
     * shared {@code forge.cfg} holder or the legacy Cleanroom config manager.
     *
     * <p>Legacy LWJGLXX calls its config writer while creating the core-profile context, which writes the
     * in-memory core-profile request (compatibility profile disabled) to the file before
     * {@link #restoreForgeEarlyCompatProfile} can undo it in memory.
     * Without this follow-up sync the file stays on the core profile and a later launch without
     * Actinium creates a core context whose fixed-pipeline startup code (SplashProgress texture
     * setup) fails with {@code GL_INVALID_ENUM}, kills the splash thread, and aborts the JVM on the
     * first {@code glAlphaFunc}. Write the restored fields back so the file matches the compatibility profile.</p>
     */
    public static void persistForgeEarlyCompatProfile() {
        ForgeEarlyConfigCompat.persistCompatibilityProfile();
    }

    /**
     * Applies context mutations in order. A {@code null} result keeps the current mutable attributes;
     * a non-null result supplies the attributes for the next mutation.
     */
    static <T> T configure(
        T attributes,
        Function<T, T> coreProfile,
        Function<T, T> forwardCompatible,
        Function<T, T> debug
    ) {
        attributes = applyMutation(attributes, coreProfile);
        attributes = applyMutation(attributes, forwardCompatible);
        attributes = applyMutation(attributes, debug);
        return attributes;
    }

    private static <T> T applyMutation(T attributes, Function<T, T> mutation) {
        T mutated = mutation.apply(attributes);
        return mutated == null ? attributes : mutated;
    }
}
