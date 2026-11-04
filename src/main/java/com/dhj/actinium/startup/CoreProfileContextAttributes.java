package com.dhj.actinium.startup;

import org.lwjgl.opengl.ContextAttribs;

import java.util.function.Function;

/**
 * Builds the core-profile context attributes requested during early Minecraft startup.
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
