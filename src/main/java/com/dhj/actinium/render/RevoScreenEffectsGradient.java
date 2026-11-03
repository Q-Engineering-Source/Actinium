package com.dhj.actinium.render;

import com.dhj.actinium.mixin.mod.revoui.ScreenEffectsRendererInvoker;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.GLSMQueries;

/**
 * Transfers Revo's requested screen gradient to the next safe pre-HUD render boundary.
 */
public final class RevoScreenEffectsGradient {
    private static final Logger LOGGER = LogManager.getLogger("ActiniumRevoUi");
    private static PendingGradient pending;

    private RevoScreenEffectsGradient() {
    }

    /**
     * Records the latest gradient requested by Revo for the current render context.
     */
    public static void defer(
        int width,
        int height,
        float progress,
        Object world,
        Object screen
    ) {
        pending = new PendingGradient(width, height, progress, world, screen);
    }

    /**
     * Draws a current deferred gradient without allowing it to write depth ahead of the HUD.
     */
    public static void drawIfPending(Minecraft minecraft) {
        ScaledResolution resolution = new ScaledResolution(minecraft);
        Gradient gradient = poll(
            minecraft.world,
            minecraft.currentScreen,
            resolution.getScaledWidth(),
            resolution.getScaledHeight()
        );
        if (gradient == null) {
            return;
        }

        GuiGlStateBoundary.restoreHudBaseline();
        boolean depthTest = GLSMEnable.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean depthMask = GLSMQueries.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        try {
            GLStateManager.disableDepthTest();
            GLSMDepthStencilAlpha.glDepthMask(false);
            ScreenEffectsRendererInvoker.actinium$drawGradient(
                gradient.width,
                gradient.height,
                gradient.progress
            );
        } catch (Throwable throwable) {
            LOGGER.error("Revo UI gradient draw failed; dropping the deferred gradient", throwable);
        } finally {
            restoreDepthTest(depthTest);
            GLSMDepthStencilAlpha.glDepthMask(depthMask);
            GuiGlStateBoundary.restoreHudBaseline();
        }
    }

    static Gradient poll(Object world, Object screen, int width, int height) {
        PendingGradient deferred = pending;
        pending = null;
        if (deferred == null
            || deferred.world != world
            || deferred.screen != screen
            || deferred.width != width
            || deferred.height != height) {
            return null;
        }
        return new Gradient(width, height, deferred.progress);
    }

    private static void restoreDepthTest(boolean enabled) {
        if (enabled) {
            GLStateManager.enableDepthTest();
        } else {
            GLStateManager.disableDepthTest();
        }
    }

    static final class Gradient {
        private final int width;
        private final int height;
        private final float progress;

        private Gradient(int width, int height, float progress) {
            this.width = width;
            this.height = height;
            this.progress = progress;
        }

        int width() {
            return this.width;
        }

        int height() {
            return this.height;
        }

        float progress() {
            return this.progress;
        }

    }

    private static final class PendingGradient {
        private final int width;
        private final int height;
        private final float progress;
        private final Object world;
        private final Object screen;

        private PendingGradient(int width, int height, float progress, Object world, Object screen) {
            this.width = width;
            this.height = height;
            this.progress = progress;
            this.world = world;
            this.screen = screen;
        }
    }
}
