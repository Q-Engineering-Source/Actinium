package com.dhj.actinium.debug;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and compares the desktop OpenGL version reported by {@code GL_VERSION}.
 */
public record OpenGlVersion(int major, int minor) {
    private static final Pattern VERSION_PATTERN = Pattern.compile(
        "^(?<major>\\d+)\\.(?<minor>\\d+)(?:\\.\\d+)?(?:\\s.*)?$"
    );

    public OpenGlVersion {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("OpenGL version components must be non-negative");
        }
    }

    /**
     * Parses the leading major and minor components of a specification-compliant version string, e.g.
     * the value returned by {@code glGetString(GL_VERSION)}.
     *
     * @throws NullPointerException if the driver returned {@code null}
     * @throws IllegalArgumentException if the driver returned an invalid version string
     */
    public static OpenGlVersion parse(String versionString) {
        Matcher matcher = VERSION_PATTERN.matcher(Objects.requireNonNull(versionString, "versionString"));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid OpenGL version string: \"" + versionString + "\"");
        }

        return new OpenGlVersion(
            Integer.parseInt(matcher.group("major")),
            Integer.parseInt(matcher.group("minor"))
        );
    }

    public boolean isAtLeast(int requiredMajor, int requiredMinor) {
        if (requiredMajor < 0 || requiredMinor < 0) {
            throw new IllegalArgumentException("Required OpenGL version components must be non-negative");
        }
        return major > requiredMajor || major == requiredMajor && minor >= requiredMinor;
    }
}
