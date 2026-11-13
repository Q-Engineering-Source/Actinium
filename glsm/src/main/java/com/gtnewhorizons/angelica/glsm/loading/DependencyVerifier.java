package com.gtnewhorizons.angelica.glsm.loading;

import java.util.List;

/** Checks optional backend dependencies without initializing their native bindings. */
public final class DependencyVerifier {
    private DependencyVerifier() {}

    /** A resource that the backend needs and the message shown when it is absent. */
    public record Check(String resourcePath, String errorMessage) {}

    /** Returns the first missing requirement, or null when the backend can resolve every resource. */
    public static Check firstMissing(Class<?> anchor, List<Check> checks) {
        for (Check check : checks) {
            if (anchor.getResource(check.resourcePath()) == null) {
                return check;
            }
        }
        return null;
    }
}
