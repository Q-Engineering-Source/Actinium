package com.dhj.actinium.gui;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;
import dhj.embeddedt.embeddium.api.options.control.CyclingControl;
import dhj.embeddedt.embeddium.api.options.structure.Option;
import dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions;
import dhj.embeddedt.embeddium.impl.gui.framework.TextComponent;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The three-state vsync option: which modes this backend can deliver, which one the player picked,
 * and how a pick is read back.
 *
 * <p>Vanilla's own switch is a boolean, which cannot express the difference between waiting for the
 * monitor and presenting from a mailbox; both are tear-free but only the first hands the frame rate
 * to the display. The option therefore stores a {@link VSyncMode} and keeps vanilla's
 * {@code enableVsync} as its two-valued shadow, so a player who never opens this screen still gets
 * the mode their vanilla setting implies.</p>
 */
public final class VSyncOptions {

    /** The order the modes are offered in, lowest latency first. */
    private static final VSyncMode[] DISPLAY_ORDER = { VSyncMode.OFF, VSyncMode.ON, VSyncMode.MAILBOX };

    private VSyncOptions() {}

    /**
     * The modes this backend can actually deliver, in display order.
     *
     * <p>A backend that reports nothing supported would leave the player with no way to turn vsync
     * back on, so the plain blocking mode is always offered.</p>
     */
    public static VSyncMode[] selectableModes() {
        return selectableModes(GLStateManager::supportsVSyncMode);
    }

    static VSyncMode[] selectableModes(Predicate<VSyncMode> supported) {
        final List<VSyncMode> available = new ArrayList<>(DISPLAY_ORDER.length);
        for (VSyncMode mode : DISPLAY_ORDER) {
            if (supported.test(mode)) {
                available.add(mode);
            }
        }
        return available.isEmpty() ? new VSyncMode[] { VSyncMode.ON } : available.toArray(new VSyncMode[0]);
    }

    /** The cycling control for the vsync option, labelled with one translation key per mode. */
    public static CyclingControl<VSyncMode> cyclingControl(Option<VSyncMode> option) {
        final VSyncMode[] modes = selectableModes();
        final TextComponent[] names = new TextComponent[modes.length];
        for (int i = 0; i < modes.length; i++) {
            names[i] = TextComponent.translatable(modeKey(modes[i]));
        }
        return new CyclingControl<>(option, modes, names);
    }

    static String modeKey(VSyncMode mode) {
        return "sodium.options.vsync_mode." + mode.name().toLowerCase(Locale.ROOT);
    }

    /**
     * The mode to use when the config holds none, which is the case on a fresh install: vanilla's own
     * switch is what the player has actually been using, and the backend decides which tear-free mode
     * it can honour.
     */
    public static VSyncMode defaultMode() {
        return Minecraft.getMinecraft().gameSettings.enableVsync ? GLStateManager.preferredTearFreeMode() : VSyncMode.OFF;
    }

    /**
     * The mode to show for the stored option.
     *
     * <p>A mode the backend cannot deliver — Mailbox on an OpenGL backend, or a mode written by a
     * build that no longer has it — reads as plain vsync rather than as no vsync, because the player
     * asked for tear-free output and only the waiting form is left.</p>
     */
    public static VSyncMode storedMode(SodiumGameOptions opts) {
        final VSyncMode stored = opts.advanced.vsyncMode;
        return stored == null ? defaultMode() : supportedMode(stored, GLStateManager::supportsVSyncMode);
    }

    static VSyncMode supportedMode(VSyncMode stored, Predicate<VSyncMode> supported) {
        return supported.test(stored) ? stored : VSyncMode.ON;
    }
}
