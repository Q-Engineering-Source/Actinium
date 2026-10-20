package net.coderbot.iris.gui.screen;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure decision logic for the saved-program cache controls of {@link ShaderPackScreen}.
 *
 * <p>The screen itself cannot be built outside a running client: loading the class translates its titles
 * through {@code I18n} and drawing a control needs a font renderer. What the two controls show, whether a
 * press can do anything, and what a press has to do are therefore kept here as functions of plain values,
 * so they can be unit tested without a client.</p>
 *
 * <p>The keys returned here are translation keys: the screen translates them, which keeps this class free
 * of the client translation table and of the loading order it imposes.</p>
 */
public final class ShaderPackCacheLogic {

    /** Label of the saved-program switch while programs are saved. */
    private static final String SAVE_ON = "options.iris.saveCompiledShaders.on";

    /** Label of the saved-program switch while programs are not saved. */
    private static final String SAVE_OFF = "options.iris.saveCompiledShaders.off";

    /** Explains what saving programs does, starting with the slower first load it costs. */
    private static final String SAVE_TOOLTIP = "options.iris.saveCompiledShaders.tooltip";

    /** Explains that the driver cannot restore a saved program, so the switch would change nothing. */
    private static final String SAVE_UNSUPPORTED = "options.iris.saveCompiledShaders.unsupported";

    /** Tells the player that the switch only reaches the pack on its next load. */
    private static final String SAVE_RELOAD = "options.iris.saveCompiledShaders.reload";

    /** Label of the delete button while it waits for its first press. */
    private static final String DELETE = "options.iris.cachedPrograms.delete";

    /** Label of the delete button once it waits for the confirmation press. */
    private static final String DELETE_CONFIRM = "options.iris.cachedPrograms.delete.confirm";

    /** Explains what deleting the saved programs costs. */
    private static final String DELETE_TOOLTIP = "options.iris.cachedPrograms.delete.tooltip";

    /** Announced once the saved programs are gone. */
    private static final String DELETE_DONE = "options.iris.cachedPrograms.deleted";

    private ShaderPackCacheLogic() {
    }

    /**
     * What the saved-program switch looks like right now.
     *
     * @param clickable whether pressing it changes anything
     * @param labelKey translation key of its label
     * @param tooltipKeys translation keys of its tooltip, in reading order
     */
    public record Toggle(boolean clickable, String labelKey, List<String> tooltipKeys) {
    }

    /** What a press on the delete button has to do. */
    public enum DeleteAction {
        /** The driver cannot restore a saved program, so nothing was ever saved: the button is disabled. */
        UNAVAILABLE,
        /** The press only asks for confirmation, because deleting throws away a full compile. */
        CONFIRM,
        /** The confirmation press deletes every saved program. */
        DELETE
    }

    /**
     * Decides the state of the saved-program switch.
     *
     * <p>Without driver support the switch is greyed out. Saving a binary and restoring it both need the
     * driver, and the prefetch of every shader folder that the switch also enables is skipped for the same
     * reason ({@code ProgramBinaryCache.isEnabled()} covers the driver), so a press could only store a
     * value nothing reads. The label still reports the stored value, so the player can see what the
     * configuration says; the tooltip then explains why the switch does nothing instead of promising a
     * speed-up.</p>
     *
     * <p>When the switch can work, the tooltip ends with {@link #takesEffectOnNextPackLoad(boolean)}'s
     * notice, because the pack fingerprint a full compile is marked with is taken during the load.</p>
     *
     * @param driverSupportsProgramBinary whether the driver can read a linked program back and restore it
     * @param saveCompiledShaders the stored value of the switch
     * @return the label, tooltip and clickability of the switch
     */
    public static Toggle savedProgramsToggle(boolean driverSupportsProgramBinary, boolean saveCompiledShaders) {
        final String labelKey = saveCompiledShaders ? SAVE_ON : SAVE_OFF;
        final List<String> tooltipKeys = new ArrayList<>(2);
        tooltipKeys.add(SAVE_TOOLTIP);
        tooltipKeys.add(takesEffectOnNextPackLoad(driverSupportsProgramBinary) ? SAVE_RELOAD : SAVE_UNSUPPORTED);
        return new Toggle(driverSupportsProgramBinary, labelKey, List.copyOf(tooltipKeys));
    }

    /**
     * The stored value the switch has to hold after a press.
     *
     * @param saveCompiledShaders the stored value before the press
     * @return the value to store
     */
    public static boolean nextSaveCompiledShaders(boolean saveCompiledShaders) {
        return !saveCompiledShaders;
    }

    /**
     * Whether a change of the switch only reaches the loaded pack on its next load.
     *
     * <p>Turning saving on is worth nothing before a pack load compiles every folder and marks the
     * fingerprint it did it for, so the screen announces a reload instead of pretending the change is
     * already in effect. Without driver support a reload would not apply it either, which is why the
     * unsupported notice replaces the reload notice there.</p>
     *
     * @param driverSupportsProgramBinary whether the driver can read a linked program back and restore it
     * @return whether the player has to reload the shaders for the change to apply
     */
    public static boolean takesEffectOnNextPackLoad(boolean driverSupportsProgramBinary) {
        return driverSupportsProgramBinary;
    }

    /**
     * Whether the delete button can be pressed.
     *
     * @param driverSupportsProgramBinary whether the driver can read a linked program back and restore it
     * @return whether anything can have been saved for this driver
     */
    public static boolean deleteAvailable(boolean driverSupportsProgramBinary) {
        return driverSupportsProgramBinary;
    }

    /**
     * Decides what a press on the delete button has to do.
     *
     * <p>Deleting discards the result of a full compile, which the next load has to pay again, so the
     * first press only arms the button and announces the second one in its label.</p>
     *
     * @param driverSupportsProgramBinary whether the driver can read a linked program back and restore it
     * @param confirmationPending whether the button is already waiting for its confirmation press
     * @return the action the press has to perform
     */
    public static DeleteAction deleteCachedPrograms(boolean driverSupportsProgramBinary, boolean confirmationPending) {
        if (!deleteAvailable(driverSupportsProgramBinary)) {
            return DeleteAction.UNAVAILABLE;
        }
        return confirmationPending ? DeleteAction.DELETE : DeleteAction.CONFIRM;
    }

    /**
     * The label of the delete button, which asks for the confirmation press once it is armed.
     *
     * @param confirmationPending whether the button is already waiting for its confirmation press
     * @return translation key of its label
     */
    public static String deleteButtonLabelKey(boolean confirmationPending) {
        return confirmationPending ? DELETE_CONFIRM : DELETE;
    }

    /** {@return the translation keys of the tooltip explaining the delete button} */
    public static List<String> deleteTooltipKeys() {
        return List.of(DELETE_TOOLTIP);
    }

    /** {@return the translation key announced once the saved programs are gone} */
    public static String deletedNotificationKey() {
        return DELETE_DONE;
    }
}
