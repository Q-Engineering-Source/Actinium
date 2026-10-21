package com.dhj.actinium.startup;

import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCache;
import net.coderbot.iris.gl.program.RetainedPrograms;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Resets caches whose driver assumptions can change when the display context changes.
 *
 * <p>{@link ProgramBinaryCache} memoizes the driver fingerprint used by saved binaries. Retained GL program
 * names belong to their share groups, so that pool is reconciled against live contexts and only the current
 * group's pending deletes are drained.</p>
 *
 * <p>This lives in the root module because it is the only one that sees both sides: GLSM raises the change
 * and must not reach into the shader module, and the shader module must not reach into GLSM's hook table.
 * The callback is installed once, at construction, because that is the earliest point of the client
 * lifecycle and the first context is captured later.</p>
 */
public final class ProgramCacheContextReset {
    private static final Logger LOGGER = LogManager.getLogger("Actinium");

    /** Prevents instances; this class only installs and implements one callback. */
    private ProgramCacheContextReset() {}

    /**
     * Subscribes the reset to the GL state manager's display-context changes.
     *
     * <p>Called once from the mod's construction, the earliest point of the client lifecycle: the startup
     * context is captured later, so no change can go unobserved.</p>
     */
    public static void install() {
        GLSMHooks.displayContextChangeListener = ProgramCacheContextReset::onDisplayContextChange;
    }

    /**
     * Invalidates the driver fingerprint and reconciles program ownership with the current share group.
     * Programs in another live group remain cached for that group's next pipeline.
     */
    public static void onDisplayContextChange() {
        RetainedPrograms.onDisplayContextChanged();
        ProgramBinaryCache.invalidateContext();
        LOGGER.info(
            "Display context changed: pruned retired program share groups and reset the program binary cache driver fingerprint");
    }
}
