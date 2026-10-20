package net.coderbot.iris.gui.screen;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers what the saved-program controls of the shader pack screen show and what a press on them does.
 *
 * <p>The screen itself needs a running client, so its decisions are made by
 * {@link ShaderPackCacheLogic}; this is the logic under test.</p>
 */
class ShaderPackCacheLogicTest {

    private static final String SAVE_ON = "options.iris.saveCompiledShaders.on";
    private static final String SAVE_OFF = "options.iris.saveCompiledShaders.off";
    private static final String SAVE_TOOLTIP = "options.iris.saveCompiledShaders.tooltip";
    private static final String SAVE_UNSUPPORTED = "options.iris.saveCompiledShaders.unsupported";
    private static final String SAVE_RELOAD = "options.iris.saveCompiledShaders.reload";
    private static final String DELETE = "options.iris.cachedPrograms.delete";
    private static final String DELETE_CONFIRM = "options.iris.cachedPrograms.delete.confirm";
    private static final String DELETE_TOOLTIP = "options.iris.cachedPrograms.delete.tooltip";
    private static final String DELETED = "options.iris.cachedPrograms.deleted";

    @Test
    void aSupportedDriverOffersTheSwitchAndReportsTheStoredValue() {
        final ShaderPackCacheLogic.Toggle on = ShaderPackCacheLogic.savedProgramsToggle(true, true);
        assertTrue(on.clickable(), "the driver can save and restore a program, so the switch has to work");
        assertEquals(SAVE_ON, on.labelKey(), "the label reports that programs are being saved");

        final ShaderPackCacheLogic.Toggle off = ShaderPackCacheLogic.savedProgramsToggle(true, false);
        assertTrue(off.clickable());
        assertEquals(SAVE_OFF, off.labelKey(), "the label reports that programs are not being saved");
    }

    @Test
    void theSupportedTooltipExplainsTheCostAndTheReload() {
        final List<String> tooltipKeys = ShaderPackCacheLogic.savedProgramsToggle(true, false).tooltipKeys();

        assertEquals(List.of(SAVE_TOOLTIP, SAVE_RELOAD), tooltipKeys,
            "the tooltip explains what saving costs, then that it only applies on the next pack load");
        assertFalse(tooltipKeys.contains(SAVE_UNSUPPORTED),
            "a supported driver must not be told that it cannot save programs");
    }

    @Test
    void anUnsupportedDriverGreysTheSwitchOutAndSaysWhy() {
        for (boolean stored : new boolean[] { true, false }) {
            final ShaderPackCacheLogic.Toggle toggle = ShaderPackCacheLogic.savedProgramsToggle(false, stored);

            assertFalse(toggle.clickable(), "nothing can be saved without driver support, so a press may not be offered");
            assertEquals(stored ? SAVE_ON : SAVE_OFF, toggle.labelKey(),
                "the label still reports the stored value, so the player can see what the configuration says");
            assertEquals(List.of(SAVE_TOOLTIP, SAVE_UNSUPPORTED), toggle.tooltipKeys(),
                "the tooltip says the driver cannot save programs instead of promising a reload that would change nothing");
        }
    }

    @Test
    void pressingTheSwitchStoresTheOppositeValue() {
        assertTrue(ShaderPackCacheLogic.nextSaveCompiledShaders(false), "pressing an off switch has to turn it on");
        assertFalse(ShaderPackCacheLogic.nextSaveCompiledShaders(true), "pressing an on switch has to turn it off");
    }

    @Test
    void theChangeOnlyAppliesOnTheNextPackLoadWhenSavingCanWork() {
        assertTrue(ShaderPackCacheLogic.takesEffectOnNextPackLoad(true),
            "the full compile runs during a pack load, so a reload is what applies the switch");
        assertFalse(ShaderPackCacheLogic.takesEffectOnNextPackLoad(false),
            "without driver support no reload would change anything, which is why the unsupported notice replaces it");
    }

    @Test
    void deletingTheSavedProgramsNeedsAConfirmationPress() {
        assertEquals(ShaderPackCacheLogic.DeleteAction.CONFIRM,
            ShaderPackCacheLogic.deleteCachedPrograms(true, false), "the first press only arms the button");
        assertEquals(ShaderPackCacheLogic.DeleteAction.DELETE,
            ShaderPackCacheLogic.deleteCachedPrograms(true, true), "the confirmation press deletes");
    }

    @Test
    void deletingIsUnavailableWithoutDriverSupport() {
        assertFalse(ShaderPackCacheLogic.deleteAvailable(false), "no program can have been saved without driver support");
        assertEquals(ShaderPackCacheLogic.DeleteAction.UNAVAILABLE,
            ShaderPackCacheLogic.deleteCachedPrograms(false, false));
        assertEquals(ShaderPackCacheLogic.DeleteAction.UNAVAILABLE,
            ShaderPackCacheLogic.deleteCachedPrograms(false, true),
            "an unavailable button stays unavailable even if a confirmation was pending");
        assertTrue(ShaderPackCacheLogic.deleteAvailable(true));
    }

    @Test
    void theDeleteLabelAnnouncesTheConfirmationPress() {
        assertEquals(DELETE, ShaderPackCacheLogic.deleteButtonLabelKey(false));
        assertEquals(DELETE_CONFIRM, ShaderPackCacheLogic.deleteButtonLabelKey(true),
            "an armed button has to say that the next press deletes");
    }

    @Test
    void theDeleteOperationDescribesItselfAndItsResult() {
        assertEquals(List.of(DELETE_TOOLTIP), ShaderPackCacheLogic.deleteTooltipKeys());
        assertEquals(DELETED, ShaderPackCacheLogic.deletedNotificationKey());
    }
}
