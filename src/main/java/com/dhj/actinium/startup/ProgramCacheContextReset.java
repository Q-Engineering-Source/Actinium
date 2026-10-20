package com.dhj.actinium.startup;

import com.dhj.actinium.runtime.ActiniumRuntime;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCache;
import net.coderbot.iris.gl.program.RetainedPrograms;

/**
 * Drops the program caches that only described the display context the game has just left.
 *
 * <p>Two caches survive inside a process but not across a context: {@link ProgramBinaryCache} memoizes the
 * driver fingerprint it keys every saved binary on, and {@link RetainedPrograms} holds GL program names.
 * After a splash replacement migrates the game to a context of its own (issue #150), the first would key
 * new programs on a driver that is no longer running and the second would hand out names that mean
 * something else - or nothing - on the new context.</p>
 *
 * <p>This lives in the root module because it is the only one that sees both sides: GLSM raises the change
 * and must not reach into the shader module, and the shader module must not reach into GLSM's hook table.
 * The callback is installed once, at construction, because that is the earliest point of the client
 * lifecycle and the first context is captured later.</p>
 */
public final class ProgramCacheContextReset {

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
     * Invalidates both caches. The retained programs are forgotten rather than deleted: their names belong
     * to the context that is gone, so deleting them on the new one could free an unrelated program that
     * happens to have the same name there.
     */
    private static void onDisplayContextChange() {
        final int discarded = RetainedPrograms.discardAll();
        ProgramBinaryCache.invalidateContext();
        ActiniumRuntime.logger().info(
            "Display context changed: discarded {} retained programs and reset the program binary cache",
            discarded);
    }
}
