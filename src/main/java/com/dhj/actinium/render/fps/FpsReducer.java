package com.dhj.actinium.render.fps;

import com.dhj.actinium.render.fps.ReducerStateMachine.State;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.SoundCategory;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.sound.SoundLoadEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

/**
 * Lowers the frame rate and the master volume while the window is unfocused, minimized, or idle.
 *
 * <p>The render loop asks the reducer for the frame cap of the current state, so the pacer and the
 * cached frame rate stay on one decision path. Volume changes go through the sound handler; the
 * player's own setting is never overwritten.</p>
 */
public final class FpsReducer {

    private static final ReducerStateMachine MACHINE = new ReducerStateMachine(System::nanoTime);
    private static final FpsReducer INSTANCE = new FpsReducer();

    private static KeyBinding forceKey;
    private static KeyBinding disableKey;

    private static final boolean[] MOUSE_BUTTONS = new boolean[3];
    private static final boolean[] KEY_STATES = new boolean[Keyboard.KEYBOARD_SIZE];

    private static State applied;
    private static int appliedVolumePercent = 100;
    private static boolean configDirty;
    private static String hudText;
    private static volatile int volumePercent = 100;

    private FpsReducer() {}

    public static void init() {
        forceKey = new KeyBinding("key.actinium.fps_reducer_force", 0, "key.categories.misc");
        disableKey = new KeyBinding("key.actinium.fps_reducer_disable", 0, "key.categories.misc");
        ClientRegistry.registerKeyBinding(forceKey);
        ClientRegistry.registerKeyBinding(disableKey);
        MinecraftForge.EVENT_BUS.register(INSTANCE);
    }

    /** Re-evaluates the state from the window and the config, and applies any transition. */
    public static void evaluateFrame() {
        final SodiumGameOptions.ReducerSettings settings = settings();
        // Nothing to do while the reducer is off and has never been applied; the per-frame polling below would
        // otherwise touch the keyboard and window bridge for every frame of every session.
        if (!settings.enabled && applied == State.DISABLED && !configDirty) return;
        pollMouseActivity();
        final boolean created = settings.enabled && Display.isCreated();
        final boolean visible = !created || Display.isVisible();
        final boolean active = !created || Display.isActive();
        final boolean changed = MACHINE.evaluate(settings.enabled, visible, active, settings.idleTimeoutMinutes * 60_000_000_000L);
        if (changed || configDirty) {
            applyTransition(settings);
        }
    }

    /**
     * @return the frame cap the current state allows, merged with the user's limit and the menu cap
     */
    public static int effectiveCap(int userLimit, boolean inMenu) {
        final SodiumGameOptions.ReducerSettings settings = settings();
        final int stateCap = ReducerStateMachine.stateCap(MACHINE.state(), settings.unfocusedFpsLimit, settings.idleFpsLimit);
        return ReducerStateMachine.mergeCap(userLimit, stateCap, inMenu, settings.limitMenuFrameRate);
    }

    /** Scales a master volume the user set, so transitions never touch the stored value. */
    public static float scaleVolume(float user) {
        return ReducerStateMachine.masterVolume(user, volumePercent);
    }

    /** Marks the cached state stale after the options screen changes a value. */
    public static void markConfigChanged() {
        configDirty = true;
    }

    public static void onInput() {
        MACHINE.onInput();
    }

    /** @return the debug-screen suffix for the active reduction, or {@code null} when uncapped */
    public static String debugTag() {
        return applied == null ? null : applied.debugTag();
    }

    private static SodiumGameOptions.ReducerSettings settings() {
        return ActiniumRuntime.options().reducer;
    }

    /**
     * Feeds mouse and keyboard activity to the idle timer. The game consumes the input queues inside
     * {@code runTick}/{@code runTickKeyboard} before the reducer runs, so both are polled as held state and a
     * rising edge counts as input.
     */
    private static void pollMouseActivity() {
        if (Mouse.isCreated()) {
            for (int button = 0; button < MOUSE_BUTTONS.length; button++) {
                final boolean down = Mouse.isButtonDown(button);
                if (down && !MOUSE_BUTTONS[button]) MACHINE.onInput();
                MOUSE_BUTTONS[button] = down;
            }
            if (Mouse.getDX() != 0 || Mouse.getDY() != 0) MACHINE.onInput();
        }
        pollKeyboardActivity();
    }

    private static void pollKeyboardActivity() {
        if (!Keyboard.isCreated()) return;
        for (int key = 0; key < KEY_STATES.length; key++) {
            final boolean down = Keyboard.isKeyDown(key);
            if (down && !KEY_STATES[key]) MACHINE.onInput();
            KEY_STATES[key] = down;
        }
    }

    private static void applyTransition(SodiumGameOptions.ReducerSettings settings) {
        final State previous = applied;
        final State next = MACHINE.state();
        applied = next;
        configDirty = false;

        if (previous != null && previous != next) {
            ActiniumRuntime.logger().info("FPS reducer: {} -> {}", previous, next);
        }

        final int percent = ReducerStateMachine.stateVolume(next, settings.unfocusedVolume, settings.minimizedVolume, settings.idleVolume);
        if (percent != appliedVolumePercent) {
            appliedVolumePercent = percent;
            volumePercent = percent;
            applyVolume();
        }

        if (next == State.FORCED) {
            hudText = I18n.format("actinium.fps_reducer.hud.forced");
        } else if (MACHINE.keybindDisabled()) {
            hudText = I18n.format("actinium.fps_reducer.hud.disabled");
        } else {
            hudText = null;
        }

        BackendManager.RENDER_BACKEND.setPresentSuppressed(next == State.MINIMIZED);
    }

    private static void applyVolume() {
        final Minecraft minecraft = Minecraft.getMinecraft();
        final SoundHandler handler = minecraft.getSoundHandler();
        if (handler == null || minecraft.gameSettings == null) return;
        handler.setSoundLevel(SoundCategory.MASTER, scaleVolume(minecraft.gameSettings.getSoundLevel(SoundCategory.MASTER)));
    }

    @SubscribeEvent
    public void onSoundLoad(SoundLoadEvent event) {
        applyVolume();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        while (forceKey != null && forceKey.isPressed()) {
            MACHINE.toggleForced();
            configDirty = true;
        }
        while (disableKey != null && disableKey.isPressed()) {
            MACHINE.toggleKeybindDisabled();
            configDirty = true;
        }
    }

    private static void drawHud(boolean requireNoWorld) {
        final Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.gameSettings.showDebugInfo) return;
        if (requireNoWorld && minecraft.world != null) return;
        minecraft.fontRenderer.drawStringWithShadow(hudText, 2, 2, 0xFFFFFF);
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL || hudText == null) return;
        drawHud(false);
    }

    @SubscribeEvent
    public void onDrawScreen(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (hudText == null || !(event.getGui() instanceof GuiMainMenu)) return;
        drawHud(true);
    }
}
