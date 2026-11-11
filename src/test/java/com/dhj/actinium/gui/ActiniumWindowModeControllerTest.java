package com.dhj.actinium.gui;

import dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActiniumWindowModeControllerTest {
    @Test
    void resolvesStoredFullscreenModes() {
        SodiumGameOptions options = new SodiumGameOptions();

        options.window.fullscreenMode = FullscreenMode.BORDERLESS.name();
        assertEquals(FullscreenMode.BORDERLESS, ActiniumWindowModeController.resolveConfiguredMode(options));

        options.window.fullscreenMode = FullscreenMode.EXCLUSIVE.name();
        assertEquals(FullscreenMode.EXCLUSIVE, ActiniumWindowModeController.resolveConfiguredMode(options));

        options.window.fullscreenMode = FullscreenMode.OFF.name();
        assertEquals(FullscreenMode.OFF, ActiniumWindowModeController.resolveConfiguredMode(options));
    }

    @Test
    void fallsBackToWindowedForMissingOrInvalidMode() {
        SodiumGameOptions options = new SodiumGameOptions();

        assertEquals(FullscreenMode.OFF, ActiniumWindowModeController.resolveConfiguredMode(options));

        options.window.fullscreenMode = "invalid-mode";
        assertEquals(FullscreenMode.OFF, ActiniumWindowModeController.resolveConfiguredMode(options));
    }

    @Test
    void migratesLegacyFullscreenModeToExclusive() {
        SodiumGameOptions options = new SodiumGameOptions();
        options.window.fullscreenMode = "FULLSCREEN";

        assertEquals(FullscreenMode.EXCLUSIVE, ActiniumWindowModeController.resolveConfiguredMode(options));
        assertEquals(FullscreenMode.EXCLUSIVE.name(), options.window.fullscreenMode);
    }

    @Test
    void togglesBetweenWindowedAndExclusiveFullscreen() {
        assertEquals(
            FullscreenMode.EXCLUSIVE,
            ActiniumWindowModeController.nextMode(FullscreenMode.OFF, FullscreenMode.EXCLUSIVE)
        );
        assertEquals(
            FullscreenMode.OFF,
            ActiniumWindowModeController.nextMode(FullscreenMode.EXCLUSIVE, FullscreenMode.EXCLUSIVE)
        );
        assertEquals(
            FullscreenMode.OFF,
            ActiniumWindowModeController.nextMode(FullscreenMode.BORDERLESS, FullscreenMode.BORDERLESS)
        );
    }

    @Test
    void togglesBackToLastBorderlessFullscreen() {
        assertEquals(
            FullscreenMode.BORDERLESS,
            ActiniumWindowModeController.nextMode(FullscreenMode.OFF, FullscreenMode.BORDERLESS)
        );
    }

    @Test
    void resolvesLastFullscreenMode() {
        SodiumGameOptions options = new SodiumGameOptions();
        assertEquals(FullscreenMode.EXCLUSIVE, ActiniumWindowModeController.resolveLastFullscreenMode(options));

        options.window.lastFullscreenMode = FullscreenMode.BORDERLESS.name();
        assertEquals(FullscreenMode.BORDERLESS, ActiniumWindowModeController.resolveLastFullscreenMode(options));

        options.window.lastFullscreenMode = FullscreenMode.OFF.name();
        assertEquals(FullscreenMode.EXCLUSIVE, ActiniumWindowModeController.resolveLastFullscreenMode(options));
    }

    @Test
    void resolvesLastFullscreenModeFromCurrentSelection() {
        SodiumGameOptions options = new SodiumGameOptions();
        options.window.fullscreenMode = FullscreenMode.BORDERLESS.name();

        assertEquals(FullscreenMode.BORDERLESS, ActiniumWindowModeController.resolveLastFullscreenMode(options));
    }

    @Test
    void degradesBorderlessToExclusiveWhenUnsupported() {
        assertEquals(
            FullscreenMode.EXCLUSIVE,
            ActiniumWindowModeController.effectiveMode(FullscreenMode.BORDERLESS, false)
        );
        assertEquals(
            FullscreenMode.BORDERLESS,
            ActiniumWindowModeController.effectiveMode(FullscreenMode.BORDERLESS, true)
        );
        assertEquals(
            FullscreenMode.EXCLUSIVE,
            ActiniumWindowModeController.effectiveMode(FullscreenMode.EXCLUSIVE, false)
        );
        assertEquals(
            FullscreenMode.OFF,
            ActiniumWindowModeController.effectiveMode(FullscreenMode.OFF, false)
        );
    }

    @Test
    void mapsExclusiveFullscreenToBorderlessWhenTheWindowBackendLacksExclusiveMode() {
        assertEquals(
            FullscreenMode.BORDERLESS,
            ActiniumWindowModeController.effectiveMode(FullscreenMode.EXCLUSIVE, true, false)
        );
        assertEquals(
            FullscreenMode.OFF,
            ActiniumWindowModeController.effectiveMode(FullscreenMode.EXCLUSIVE, false, false)
        );
    }

    @Test
    void vanillaWindowedStateClearsAStaleFullscreenSelection() {
        SodiumGameOptions options = new SodiumGameOptions();
        options.window.fullscreenMode = FullscreenMode.EXCLUSIVE.name();

        assertEquals(FullscreenMode.OFF, ActiniumWindowModeController.resolveStartupMode(options, false));
        assertEquals(FullscreenMode.OFF, options.window.fullscreenMode == null
            ? FullscreenMode.OFF
            : FullscreenMode.valueOf(options.window.fullscreenMode));
    }
}
