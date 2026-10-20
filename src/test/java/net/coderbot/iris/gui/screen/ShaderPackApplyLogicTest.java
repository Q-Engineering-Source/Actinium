package net.coderbot.iris.gui.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderPackApplyLogicTest {

    @Test
    void packChangeTriggersReload() {
        assertTrue(ShaderPackApplyLogic.shouldReloadOnApply(
            "BSL", "SEUS", true, true, true, false, true, false),
            "selecting a different pack must reload");
    }

    @Test
    void toggleTriggersReload() {
        assertTrue(ShaderPackApplyLogic.shouldReloadOnApply(
            "BSL", "BSL", false, true, true, false, true, false),
            "toggling the enable switch must reload");
    }

    @Test
    void queuedOptionsTriggerReload() {
        assertTrue(ShaderPackApplyLogic.shouldReloadOnApply(
            "BSL", "BSL", true, true, false, false, true, false),
            "pending option changes must reload");
    }

    @Test
    void resetPendingTriggersReload() {
        assertTrue(ShaderPackApplyLogic.shouldReloadOnApply(
            "BSL", "BSL", true, true, true, true, true, false),
            "a pending option reset must reload");
    }

    @Test
    void enabledButPackMissingTriggersReload() {
        assertTrue(ShaderPackApplyLogic.shouldReloadOnApply(
            "BSL", "BSL", true, true, true, false, false, false),
            "applying from the main menu must reload because the pack load is deferred");
    }

    @Test
    void fallbackTriggersReload() {
        assertTrue(ShaderPackApplyLogic.shouldReloadOnApply(
            "BSL", "BSL", true, true, true, false, true, true),
            "a previous failed load that fell back to vanilla rendering must be retried on apply");
    }

    @Test
    void unchangedStateDoesNotReload() {
        assertFalse(ShaderPackApplyLogic.shouldReloadOnApply(
            "BSL", "BSL", true, true, true, false, true, false),
            "closing the screen without changes must not reload a healthy pipeline");
    }

    @Test
    void disabledWithoutPackDoesNotReload() {
        assertFalse(ShaderPackApplyLogic.shouldReloadOnApply(
            "BSL", "BSL", false, false, true, false, false, false),
            "disabled shaders with no pack loaded and no fallback must not reload");
    }

    @Test
    void selectingAnotherPackDropsThePackReadAhead() {
        assertTrue(ShaderPackApplyLogic.shouldInvalidateReadAhead("BSL", "SEUS"),
            "the pack read ahead describes the selection it was read for");
        assertTrue(ShaderPackApplyLogic.shouldInvalidateReadAhead("BSL", null),
            "a selection made while no pack was configured was never read ahead");
    }

    @Test
    void applyingTheSamePackKeepsThePackReadAhead() {
        assertFalse(ShaderPackApplyLogic.shouldInvalidateReadAhead("BSL", "BSL"),
            "options, the enable switch and a reset reach the read-ahead through the reload they trigger");
    }
}
