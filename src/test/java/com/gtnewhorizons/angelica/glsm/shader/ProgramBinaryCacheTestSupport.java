package com.gtnewhorizons.angelica.glsm.shader;

import org.jetbrains.annotations.Nullable;

/**
 * Lets tests that live outside {@code com.gtnewhorizons.angelica.glsm.shader} install the
 * {@link ProgramBinaryOps} double {@link ProgramBinaryCache} uses.
 *
 * <p>The cache exposes its driver port through a package-private setter, which the cache's own tests use
 * directly. Code in another module that builds cache keys or drives the restore path needs the same
 * indirection, and reflection is not an option here, so this class forwards the call. Nothing in the
 * production source sets can reference it.</p>
 */
public final class ProgramBinaryCacheTestSupport {

    private ProgramBinaryCacheTestSupport() {}

    /**
     * Installs {@code ops} as the driver the cache talks to.
     *
     * @param ops the double to install, or null to restore the production implementation
     */
    public static void installOps(@Nullable ProgramBinaryOps ops) {
        ProgramBinaryCache.setOpsForTesting(ops);
    }
}
