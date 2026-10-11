package com.gtnewhorizons.angelica.glsm.loading;

import java.util.List;

public final class DependencyVerifier {

    public record Check(String resourcePath, String errorMessage) {}

    public static void verify(Class<?> anchor, List<Check> checks) {
        final Check missing = firstMissing(anchor, checks);
        if (missing != null) {
            throw new RuntimeException(missing.errorMessage());
        }
    }

    /** @return the first check whose resource is absent, or null when every check passes. */
    public static Check firstMissing(Class<?> anchor, List<Check> checks) {
        for (Check check : checks) {
            if (anchor.getResource(check.resourcePath()) == null) {
                return check;
            }
        }
        return null;
    }

    private DependencyVerifier() {}
}
