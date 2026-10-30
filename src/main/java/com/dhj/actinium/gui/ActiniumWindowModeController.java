package com.dhj.actinium.gui;

import com.dhj.actinium.mixin.vintage.core.MinecraftAccessor;
import com.dhj.actinium.runtime.ActiniumRuntime;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.Logger;
import dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions;
import com.gtnewhorizons.angelica.glsm.backend.WindowBackends;
import org.lwjgl.opengl.Display;

public final class ActiniumWindowModeController {
    private static final Logger LOGGER = ActiniumRuntime.logger();
    private static boolean synchronizing;
    private static String loggedInvalidFullscreenMode;
    private static final boolean androidRuntime = System.getenv("POJAV_RENDERER") != null;
    private static final boolean borderlessSupported;
    private static boolean borderlessActive;
    private static boolean loggedBorderlessUnsupported;
    // Android can start fullscreen before LWJGLXX has saved a window geometry to restore.
    private static boolean windowedGeometryCaptured;

    static {
        // Some Android runtimes omit glfwGetWindowPos(IntBuffer), making Display.isBorderless()
        // throw NoSuchMethodError. Probe once and use exclusive fullscreen when borderless is unavailable.
        boolean supported = false;
        try {
            borderlessActive = Display.isBorderless();
            supported = true;
        } catch (Throwable t) {
            LOGGER.warn(
                "Borderless fullscreen is not supported on this runtime, treating it as exclusive fullscreen: {}",
                t
            );
        }
        borderlessSupported = supported;
    }

    private ActiniumWindowModeController() {
    }

    public static FullscreenMode resolveConfiguredMode(SodiumGameOptions options) {
        String configuredModeName = options.window.fullscreenMode;
        if (configuredModeName != null) {
            if ("FULLSCREEN".equals(configuredModeName)) {
                options.window.fullscreenMode = FullscreenMode.EXCLUSIVE.name();
                return FullscreenMode.EXCLUSIVE;
            }
            try {
                return FullscreenMode.valueOf(configuredModeName);
            } catch (IllegalArgumentException e) {
                if (!configuredModeName.equals(loggedInvalidFullscreenMode)) {
                    loggedInvalidFullscreenMode = configuredModeName;
                    LOGGER.warn(
                        "Unknown fullscreen mode '{}' in the options file, falling back to {}",
                        configuredModeName,
                        FullscreenMode.OFF
                    );
                }
            }
        }

        return FullscreenMode.OFF;
    }

    public static void applyMode(Minecraft client, SodiumGameOptions options, FullscreenMode mode) {
        FullscreenMode modeToApply = effectiveMode(mode, borderlessSupported);
        if (modeToApply != mode) {
            warnBorderlessUnsupported();
        }
        options.window.fullscreenMode = modeToApply.name();
        if (modeToApply != FullscreenMode.OFF) {
            options.window.lastFullscreenMode = modeToApply.name();
        }
        client.gameSettings.fullScreen = modeToApply != FullscreenMode.OFF;
        synchronize(client);
    }

    public static void toggleFullscreen(Minecraft client) {
        SodiumGameOptions options = ActiniumRuntime.options();
        FullscreenMode current = resolveConfiguredMode(options);
        if (current != FullscreenMode.OFF && options.window.lastFullscreenMode == null) {
            options.window.lastFullscreenMode = current.name();
        }
        applyMode(client, options, nextMode(current, resolveLastFullscreenMode(options)));
    }

    static FullscreenMode nextMode(FullscreenMode current, FullscreenMode lastFullscreenMode) {
        return current == FullscreenMode.OFF ? lastFullscreenMode : FullscreenMode.OFF;
    }

    static FullscreenMode resolveLastFullscreenMode(SodiumGameOptions options) {
        String configuredModeName = options.window.lastFullscreenMode;
        if (configuredModeName == null) {
            FullscreenMode current = resolveConfiguredMode(options);
            if (current != FullscreenMode.OFF) {
                return current;
            }
        }
        if (configuredModeName != null) {
            try {
                FullscreenMode mode = FullscreenMode.valueOf(configuredModeName);
                if (mode != FullscreenMode.OFF) {
                    return mode;
                }
            } catch (IllegalArgumentException e) {
                if (!configuredModeName.equals(loggedInvalidFullscreenMode)) {
                    loggedInvalidFullscreenMode = configuredModeName;
                    LOGGER.warn(
                        "Unknown last fullscreen mode '{}', falling back to {}",
                        configuredModeName,
                        FullscreenMode.EXCLUSIVE
                    );
                }
            }
        }

        return FullscreenMode.EXCLUSIVE;
    }

    static FullscreenMode effectiveMode(FullscreenMode mode, boolean borderlessSupported) {
        return mode == FullscreenMode.BORDERLESS && !borderlessSupported ? FullscreenMode.EXCLUSIVE : mode;
    }

    private static void warnBorderlessUnsupported() {
        if (!loggedBorderlessUnsupported) {
            loggedBorderlessUnsupported = true;
            LOGGER.warn("Borderless fullscreen is not supported on this runtime, using exclusive fullscreen instead");
        }
    }

    public static void synchronize(Minecraft client) {
        if (synchronizing) {
            return;
        }

        SodiumGameOptions options = ActiniumRuntime.options();
        FullscreenMode desiredMode = resolveConfiguredMode(options);
        if (options.window.fullscreenMode == null && client.gameSettings.fullScreen) {
            desiredMode = FullscreenMode.EXCLUSIVE;
            options.window.fullscreenMode = desiredMode.name();
        }
        FullscreenMode modeToApply = effectiveMode(desiredMode, borderlessSupported);
        if (modeToApply != desiredMode) {
            options.window.fullscreenMode = modeToApply.name();
            warnBorderlessUnsupported();
        }
        if (isWindowStateCompatible(client, modeToApply)) {
            return;
        }

        synchronizing = true;

        try {
            switch (modeToApply) {
                case OFF -> applyWindowed(client);
                case EXCLUSIVE -> applyExclusiveFullscreen(client);
                case BORDERLESS -> applyBorderlessFullscreen(client);
            }
        } finally {
            synchronizing = false;
        }
    }

    private static boolean isWindowStateCompatible(Minecraft client, FullscreenMode desiredMode) {
        return switch (desiredMode) {
            case OFF -> !client.isFullScreen() && !Display.isFullscreen() && !borderlessActive;
            case EXCLUSIVE -> client.isFullScreen() && Display.isFullscreen() && !borderlessActive;
            case BORDERLESS -> client.isFullScreen() && borderlessActive;
        };
    }

    private static void applyWindowed(Minecraft client) {
        if (borderlessActive) {
            if (!androidRuntime || windowedGeometryCaptured) {
                Display.setBorderless(false);
            }
            borderlessActive = false;
        }
        if (Display.isFullscreen() && (!androidRuntime || windowedGeometryCaptured)) {
            WindowBackends.get().setExclusiveFullscreen(false);
        }
        setFullscreenState(client, false);
        updateClientDisplaySize(client);
    }

    private static void applyExclusiveFullscreen(Minecraft client) {
        if (borderlessActive) {
            if (!androidRuntime || windowedGeometryCaptured) {
                Display.setBorderless(false);
            }
            borderlessActive = false;
        }
        WindowBackends.get().setExclusiveFullscreen(true);
        windowedGeometryCaptured = true;
        setFullscreenState(client, true);
        updateClientDisplaySize(client);
    }

    private static void applyBorderlessFullscreen(Minecraft client) {
        if (Display.isFullscreen() && (!androidRuntime || windowedGeometryCaptured)) {
            Display.setFullscreen(false);
        }
        Display.setBorderless(true);
        windowedGeometryCaptured = true;
        borderlessActive = true;
        setFullscreenState(client, true);
        updateClientDisplaySize(client);
    }

    private static void setFullscreenState(Minecraft client, boolean fullscreen) {
        ((MinecraftAccessor) client).celeritas$setFullscreen(fullscreen);
        client.gameSettings.fullScreen = fullscreen;
    }

    private static void updateClientDisplaySize(Minecraft client) {
        int width = Display.getFramebufferWidth();
        int height = Display.getFramebufferHeight();

        if (width > 0 && height > 0) {
            client.displayWidth = width;
            client.displayHeight = height;
            client.resize(width, height);
        }
    }
}
