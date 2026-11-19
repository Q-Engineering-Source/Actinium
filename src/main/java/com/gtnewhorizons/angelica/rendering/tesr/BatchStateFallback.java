package com.gtnewhorizons.angelica.rendering.tesr;

import com.dhj.actinium.runtime.ActiniumRuntime;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMMatrices;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.hooks.BatchStateGuard;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Uncaptured state changes draw the queued batch before the mutation takes effect. */
final class BatchStateFallback {
    private static final Logger LOGGER = LogManager.getLogger("BatchStateFallback");
    private static final Set<String> LOGGED_STATE_SITES = new HashSet<>();
    private static final Runnable FLUSH = BatchStateFallback::flush;

    static void install() {
        BatchStateGuard.flush = FLUSH;
        // Shader-side code asks the guard instead of the host class whether draws are batched, which
        // is what defers a program selection out of an active batch.
        BatchStateGuard.batchingAllowed = BatchEligibility::batchingAllowed;
    }

    private static void flush() {
        final ModelPartBatcher models = ModelPartBatcher.INSTANCE;
        final TesrBatchRenderer tesrs = TesrBatchRenderer.INSTANCE;
        if (!models.isActive() && !tesrs.hasPendingGeometry()) return;
        // Pass setup may establish defaults after beginPass, before any renderer or queued draw.
        if (!models.hasQueuedGeometry() && !tesrs.hasQueuedGeometry() && !BatchEligibility.insideRenderer()) return;
        if (models.entityPassActive()) {
            final boolean inside = BatchEligibility.insideRenderer();
            if (inside && ActiniumRuntime.options().debug.enableEntityRenderDiagnostics) {
                logStateChangeSite(new Throwable());
            }
            GLStateManager.warnOnce("batch-fallback-entities:" + inside, "Uncaptured state change {} a renderer flushed the entity batch early",
                inside ? "inside" : "between", new Throwable());
        }
        final int mode = GLSMMatrices.getMatrixMode().getMode();
        final int unit = GLSMTextures.getActiveTextureUnit();
        BatchEligibility.onUncapturedState();
        final long before = GLSMDraws.drawCalls;
        try {
            GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
            models.flushForStateChange();
            tesrs.flushForStateChange();
        } finally {
            GLSMMatrices.glMatrixMode(mode);
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            BatchEligibility.onBatchFlushed(GLSMDraws.drawCalls - before);
        }
    }

    private static void logStateChangeSite(Throwable trace) {
        for (StackTraceElement frame : trace.getStackTrace()) {
            final String className = frame.getClassName();
            if (className.equals(BatchStateFallback.class.getName())
                || className.equals(BatchStateGuard.class.getName())
                || className.equals(GLStateManager.class.getName())
                || className.startsWith("com.gtnewhorizons.angelica.glsm.")) {
                continue;
            }
            final String site = frame.toString();
            final String key = Arrays.toString(trace.getStackTrace());
            if (LOGGED_STATE_SITES.add(key)) {
                LOGGER.warn("[ACTINIUM-ENTITY-DIAGNOSTICS] uncapturedStateChange site={} stack", site, trace);
            }
            return;
        }
    }
}
