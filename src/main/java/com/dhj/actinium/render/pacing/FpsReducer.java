package com.dhj.actinium.render.pacing;

import com.dhj.actinium.config.ActiniumConfig;
import com.dhj.actinium.render.pacing.ReducerStateMachine.State;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.gtnewhorizons.angelica.config.SystemProperties;
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
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

/**
 * Lowers the frame rate and the master volume while the player is not looking at the game.
 *
 * <p>A window that is in the background, minimized or simply untouched still renders every frame
 * and plays at full volume, which costs the machine the player has switched to. This class watches
 * the window's visibility and focus and the time since the last input, turns that into one of the
 * states {@link ReducerStateMachine} defines, and applies the frame rate cap and volume that state
 * implies. The cap itself is applied by the caller through {@link #effectiveCap}, so the pacer and
 * the reducer never have to know about each other.
 */
public final class FpsReducer {

    private static final ReducerStateMachine MACHINE = new ReducerStateMachine(System::nanoTime);
    private static final FpsReducer INSTANCE = new FpsReducer();

    private static KeyBinding forceKey;
    private static KeyBinding disableKey;

    private static State applied;
    private static int appliedVolumePercent = 100;
    private static boolean configDirty;
    private static boolean benchmarkActive;
    private static String hudText;
    private static long lastActiveMillis = Minecraft.getSystemTime();
    private static volatile int volumePercent = 100;

    private FpsReducer() {}

    /** Registers the key bindings and the input, tick and overlay hooks. */
    public static void init() {
        final String category = I18n.format("key.category.actinium.keybinds");
        forceKey = new KeyBinding("actinium.keybind.fps_reducer_force", 0, category);
        disableKey = new KeyBinding("actinium.keybind.fps_reducer_disable", 0, category);
        ClientRegistry.registerKeyBinding(forceKey);
        ClientRegistry.registerKeyBinding(disableKey);
        FMLCommonHandler.instance().bus().register(INSTANCE);
        MinecraftForge.EVENT_BUS.register(INSTANCE);
    }

    /** Re-derives the reducer state from the window and applies any change. Called once per frame. */
    public static void evaluateFrame() {
        final SodiumGameOptions.ReducerSettings s = ActiniumRuntime.options().reducer;
        final boolean created = s.enabled && Display.isCreated();
        final boolean visible = !created || Display.isVisible();
        final boolean active = !created || Display.isActive();
        final boolean changed = MACHINE.evaluate(s.enabled, visible, active, s.idleTimeoutMinutes * 60_000_000_000L);
        if (changed || configDirty) {
            applyTransition(s);
        }
    }

    private static void applyTransition(SodiumGameOptions.ReducerSettings s) {
        final State prev = applied;
        final State next = MACHINE.state();
        applied = next;
        configDirty = false;

        if (prev != null && prev != next) {
            ActiniumRuntime.logger().info("FPS reducer: {} -> {}", prev, next);
        }

        final int percent = ReducerStateMachine.stateVolume(next, s.unfocusedVolume, s.minimizedVolume, s.idleVolume);
        if (percent != appliedVolumePercent) {
            appliedVolumePercent = percent;
            applyVolume(percent);
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

    /** A benchmark run takes over the frame rate policy: see {@link ReducerStateMachine#benchmarkCap}. */
    public static void beginBenchmark() {
        benchmarkActive = true;
    }

    public static void endBenchmark() {
        benchmarkActive = false;
    }

    /**
     * The frame rate limit the pacer should apply: the lower of the player's own limit, the limit
     * the current reducer state implies, and while no world is loaded the loading screen's own
     * limit when the player asked for one.
     */
    public static int effectiveCap(int userLimit, boolean inMenu) {
        if (benchmarkActive) {
            return ReducerStateMachine.benchmarkCap(SystemProperties.FLYBY_PACING, userLimit);
        }
        final SodiumGameOptions.ReducerSettings s = ActiniumRuntime.options().reducer;
        final int stateCap = ReducerStateMachine.stateCap(MACHINE.state(), s.unfocusedFpsLimit, s.idleFpsLimit);
        final int menuCapHz = inMenu && s.limitMenuFrameRate
            ? ActiniumRuntime.options().performance.loadingScreenFramerateLimit
            : 0;
        return ReducerStateMachine.mergeCap(userLimit, stateCap, menuCapHz);
    }

    /** Whether the world render can be skipped entirely this frame. */
    public static boolean skipRender() {
        return MACHINE.state() == State.MINIMIZED;
    }

    /**
     * Runs the work the frame would have done had the world been rendered: the pause-on-lost-focus
     * check still has to fire, otherwise a minimized window would never pause a singleplayer world.
     */
    public static void onRenderSkipped() {
        final Minecraft mc = Minecraft.getMinecraft();
        if (Display.isActive()) {
            lastActiveMillis = Minecraft.getSystemTime();
            return;
        }
        if (mc.world == null || mc.currentScreen != null || !mc.gameSettings.pauseOnLostFocus) return;
        if (mc.gameSettings.touchscreen && Mouse.isButtonDown(1)) return;
        if (Minecraft.getSystemTime() - lastActiveMillis > 500L) {
            mc.displayInGameMenu();
        }
    }

    public static float scaleVolume(float user) {
        return ReducerStateMachine.masterVolume(user, volumePercent);
    }

    private static void applyVolume(int percent) {
        volumePercent = percent;
        final Minecraft mc = Minecraft.getMinecraft();
        final SoundHandler handler = mc.getSoundHandler();
        if (handler == null || mc.gameSettings == null) return;
        final float user = mc.gameSettings.getSoundLevel(SoundCategory.MASTER);
        handler.setSoundLevel(SoundCategory.MASTER, scaleVolume(user));
    }

    /** Marks the reducer configuration as changed so the next frame re-applies it. */
    public static void markConfigChanged() {
        configDirty = true;
    }

    /** The F3 tag naming the reducer state, or null while the reducer is not holding the game back. */
    public static String debugTag() {
        return applied == null ? null : applied.debugTag();
    }

    /** Records player input so the idle timer restarts. */
    public static void onInput() {
        MACHINE.onInput();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        if (forceKey != null) {
            while (forceKey.isPressed()) {
                MACHINE.toggleForced();
                configDirty = true;
            }
        }
        if (disableKey != null) {
            while (disableKey.isPressed()) {
                MACHINE.toggleKeybindDisabled();
                configDirty = true;
            }
        }
    }

    private static void drawHud(boolean requireNoWorld) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.gameSettings.showDebugInfo) return;
        if (requireNoWorld && mc.world != null) return;
        mc.fontRenderer.drawStringWithShadow(hudText, 2, 2, 0xFFFFFF);
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

    /**
     * Reports the pacing and the reducer state as an F3 line. It is appended next to the frame rate
     * because both only make sense together: the indicator names what is bounding the frame rate,
     * which is exactly what the frame rate counter cannot show.
     */
    @SubscribeEvent
    public void onDebugText(RenderGameOverlayEvent.Text event) {
        if (ActiniumConfig.disableF3Additions) return;
        final String indicator = FramePacer.debugIndicator();
        final String tag = debugTag();
        if (indicator == null && tag == null) return;
        final String suffix = indicator == null ? tag : (tag == null ? indicator : indicator + tag);
        event.getLeft().add(Math.min(event.getLeft().size(), 1), suffix.trim());
    }
}
