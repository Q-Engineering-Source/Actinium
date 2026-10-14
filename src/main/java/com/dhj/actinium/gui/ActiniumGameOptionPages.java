package com.dhj.actinium.gui;

import com.google.common.collect.ImmutableList;
import com.dhj.actinium.config.ActiniumRuntimeOptions;
import com.dhj.actinium.compat.scalingguis.ScalingGuiCompat;
import com.dhj.actinium.render.pacing.FpsReducer;
import com.dhj.actinium.render.terrain.TerrainTextureFiltering;
import com.gtnewhorizon.gtnhlib.compat.Mods;
import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebugHooks;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.VSyncMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import dhj.embeddedt.embeddium.api.options.OptionIdentifier;
import dhj.embeddedt.embeddium.api.options.control.ControlValueFormatter;
import dhj.embeddedt.embeddium.api.options.control.CyclingControl;
import dhj.embeddedt.embeddium.api.options.control.ExternalButtonControl;
import dhj.embeddedt.embeddium.api.options.control.SliderControl;
import dhj.embeddedt.embeddium.api.options.control.TickBoxControl;
import dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions;
import dhj.embeddedt.embeddium.impl.gui.framework.TextComponent;
import dhj.embeddedt.embeddium.impl.render.chunk.MultiDrawMode;
import dhj.embeddedt.embeddium.impl.texture.TexelSampling;
import dhj.embeddedt.embeddium.impl.texture.TextureFilterMode;
import net.minecraft.util.math.MathHelper;
import com.dhj.actinium.runtime.ActiniumRuntime;
import dhj.embeddedt.embeddium.api.options.structure.OptionFlag;
import dhj.embeddedt.embeddium.api.options.structure.OptionGroup;
import dhj.embeddedt.embeddium.api.options.structure.OptionImpact;
import dhj.embeddedt.embeddium.api.options.structure.OptionImpl;
import dhj.embeddedt.embeddium.api.options.structure.OptionPage;
import dhj.embeddedt.embeddium.api.options.structure.OptionStorage;
import dhj.embeddedt.embeddium.api.options.structure.StandardOptions;
import com.dhj.actinium.compat.modernui.MuiGuiScaleHook;
import com.dhj.actinium.render.FastLitItemDisplayListCache;
import com.mitchej123.lwjgl.GLExtension;

import static com.mitchej123.lwjgl.LWJGLServiceProvider.LWJGL;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.BiConsumer;

public class ActiniumGameOptionPages {
    private static final SodiumGameOptions sodiumOpts = ActiniumRuntime.options();
    private static final MinecraftOptionsStorage vanillaOpts = new MinecraftOptionsStorage();

    private static final OptionIdentifier<Void> FPS_REDUCER_PAGE = OptionIdentifier.create(ActiniumRuntime.MODID, "fps_reducer");
    private static final OptionIdentifier<Void> FPS_REDUCER_BACKGROUND = OptionIdentifier.create(ActiniumRuntime.MODID, "fps_reducer_background");
    private static final OptionIdentifier<Void> FPS_REDUCER_IDLE = OptionIdentifier.create(ActiniumRuntime.MODID, "fps_reducer_idle");
    private static final OptionIdentifier<Void> FPS_REDUCER_MENU = OptionIdentifier.create(ActiniumRuntime.MODID, "fps_reducer_menu");

    /**
     * The reducer's frame rate sliders span the same range as the player's own Max Framerate, so
     * their maximum reads as "Unlimited" and the reducer treats it as no limit at all.
     */
    private static final int REDUCER_MIN_FRAMERATE = 5;
    private static final int REDUCER_MAX_FRAMERATE = 260;

    private static int computeMaxRangeForRenderDistance(@SuppressWarnings("SameParameterValue") int injectedRenderDistance) {
        return injectedRenderDistance;
    }

    private static void setFastLitItemRendering(SodiumGameOptions opts, boolean value) {
        if (opts.advanced.useFastLitItemRendering != value) {
            opts.advanced.useFastLitItemRendering = value;
            FastLitItemDisplayListCache.clear();
        }
    }

    private static void setFastLitItemDisplayLists(SodiumGameOptions opts, boolean value) {
        if (opts.advanced.useFastLitItemDisplayLists != value) {
            opts.advanced.useFastLitItemDisplayLists = value;
            FastLitItemDisplayListCache.clear();
        }
    }

    /**
     * Applies a vsync mode picked in the three-state option.
     *
     * <p>Vanilla's {@code enableVsync} is kept as the two-valued shadow of the mode so the vanilla
     * video settings screen and the next launch agree with what is on screen here, and both files are
     * saved because the two settings live in different ones.</p>
     */
    private static void setVSyncMode(SodiumGameOptions opts, VSyncMode mode) {
        opts.advanced.vsyncMode = mode;
        final Minecraft minecraft = Minecraft.getMinecraft();
        minecraft.gameSettings.enableVsync = mode.tearFree();
        minecraft.gameSettings.saveOptions();
        GLStateManager.setVSyncMode(mode);
    }

    private static OptionImpl<GameSettings, Integer> createGuiScaleSliderOption() {
        return OptionImpl.createBuilder(int.class, vanillaOpts)
                .setId(StandardOptions.Option.GUI_SCALE.cast())
                .setName(TextComponent.translatable("options.guiScale"))
                .setTooltip(TextComponent.translatable("sodium.options.gui_scale.tooltip"))
                .setControl(option -> new SliderControl(option, 0, MuiGuiScaleHook.getMaxGuiScale(), 1, ControlValueFormatter.guiScale()))
                .setBinding((opts, value) -> {
                    opts.guiScale = value;

                    Minecraft mc = Minecraft.getMinecraft();
                    mc.resize(mc.displayWidth, mc.displayHeight);
                }, opts -> opts.guiScale)
                .setDefaultValue(0)
                .build();
    }

    private static OptionImpl<GameSettings, Void> createGuiScaleExternalButtonOption() {
        return OptionImpl.createBuilder(Void.class, vanillaOpts)
                .setId(StandardOptions.Option.GUI_SCALE)
                .setName(TextComponent.translatable("options.guiScale"))
                .setTooltip(TextComponent.translatable("scalingguis.videosettings.button.tooltip"))
                .setControl(option -> new ExternalButtonControl(option,
                        ScalingGuiCompat::openConfigScreen,
                        TextComponent.translatable("scalingguis.videosettings.button")))
                .setBinding((opts, value) -> { }, opts -> null)
                .build();
    }

    private static OptionImpl<SodiumGameOptions, Boolean> createModelRendererBatchingOption(TextComponent tooltip) {
        return OptionImpl.createBuilder(boolean.class, sodiumOpts)
                .setId(StandardOptions.Option.MODEL_RENDERER_BATCHING.cast())
                .setName(TextComponent.translatable("sodium.options.actinium.model_renderer_batching.name"))
                .setTooltip(tooltip)
                .setControl(TickBoxControl::new)
                .setImpact(OptionImpact.MEDIUM)
                .setBinding((opts, value) -> opts.advanced.useModelRendererBatching = value, opts -> opts.advanced.useModelRendererBatching)
                .setDefaultValue(true)
                .build();
    }

    private static OptionImpl<SodiumGameOptions, Boolean> createModelRendererDisplayListsOption(TextComponent tooltip) {
        return OptionImpl.createBuilder(boolean.class, sodiumOpts)
                .setId(StandardOptions.Option.MODEL_RENDERER_DISPLAY_LISTS.cast())
                .setName(TextComponent.translatable("sodium.options.model_renderer_display_lists.name"))
                .setTooltip(tooltip)
                .setControl(TickBoxControl::new)
                .setImpact(OptionImpact.MEDIUM)
                .setBinding((opts, value) -> opts.advanced.useModelRendererDisplayLists = value, opts -> opts.advanced.useModelRendererDisplayLists)
                .setDefaultValue(true)
                .build();
    }

    private static OptionImpl<SodiumGameOptions, Boolean> createFastLitItemRenderingOption(TextComponent tooltip) {
        return OptionImpl.createBuilder(boolean.class, sodiumOpts)
                .setId(StandardOptions.Option.FAST_LIT_ITEM_RENDERING.cast())
                .setName(TextComponent.translatable("sodium.options.fast_lit_item_rendering.name"))
                .setTooltip(tooltip)
                .setControl(TickBoxControl::new)
                .setImpact(OptionImpact.LOW)
                .setBinding(ActiniumGameOptionPages::setFastLitItemRendering, opts -> opts.advanced.useFastLitItemRendering)
                .setDefaultValue(true)
                .build();
    }

    private static OptionImpl<SodiumGameOptions, Boolean> createFastLitItemDisplayListsOption(TextComponent tooltip) {
        return OptionImpl.createBuilder(boolean.class, sodiumOpts)
                .setId(StandardOptions.Option.FAST_LIT_ITEM_DISPLAY_LISTS.cast())
                .setName(TextComponent.translatable("sodium.options.fast_lit_item_display_lists.name"))
                .setTooltip(tooltip)
                .setControl(TickBoxControl::new)
                .setImpact(OptionImpact.MEDIUM)
                .setBinding(ActiniumGameOptionPages::setFastLitItemDisplayLists, opts -> opts.advanced.useFastLitItemDisplayLists)
                .setDefaultValue(true)
                .build();
    }

    public static OptionPage general() {
        List<OptionGroup> groups = new ArrayList<>();
        groups.add(OptionGroup.createBuilder()
                .setId(StandardOptions.Group.RENDERING)
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setId(StandardOptions.Option.RENDER_DISTANCE.cast())
                        .setName(TextComponent.literal(I18n.format("options.renderDistance")))
                        .setTooltip(TextComponent.translatable("sodium.options.view_distance.tooltip"))
                        .setControl(option -> new SliderControl(option, 2, computeMaxRangeForRenderDistance(32), 1, ControlValueFormatter.translateVariable("options.chunks")))
                        .setBinding((options, value) -> options.renderDistanceChunks = value, options -> options.renderDistanceChunks)
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setDefaultValue(12)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setId(StandardOptions.Option.BRIGHTNESS.cast())
                        .setName(TextComponent.translatable("options.gamma"))
                        .setTooltip(TextComponent.translatable("sodium.options.brightness.tooltip"))
                        .setControl(opt -> new SliderControl(opt, 0, 100, 1, ControlValueFormatter.brightness()))
                        .setBinding((opts, value) -> opts.gammaSetting = (float) (value * 0.01D), (opts) -> (int) (opts.gammaSetting / 0.01D))
                        .setDefaultValue(0)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .setId(StandardOptions.Group.WINDOW)
                .add(Mods.SCALINGGUIS
                        ? createGuiScaleExternalButtonOption()
                        : createGuiScaleSliderOption())
                .add(OptionImpl.createBuilder(FullscreenMode.class, sodiumOpts)
                        .setId(StandardOptions.Option.FULLSCREEN_MODE.cast())
                        .setName(TextComponent.translatable("celeritas.options.fullscreen_mode.name"))
                        .setTooltip(TextComponent.translatable("celeritas.options.fullscreen_mode.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, FullscreenMode.class))
                        .setBinding((opts, value) -> ActiniumWindowModeController.applyMode(Minecraft.getMinecraft(), opts, value),
                                ActiniumWindowModeController::resolveConfiguredMode)
                        .build())
                .add(OptionImpl.createBuilder(VSyncMode.class, sodiumOpts)
                        .setId(StandardOptions.Option.VSYNC.cast())
                        .setName(TextComponent.translatable("options.vsync"))
                        .setTooltip(TextComponent.translatable("sodium.options.v_sync.tooltip"))
                        .setControl(VSyncOptions::cyclingControl)
                        .setBinding(ActiniumGameOptionPages::setVSyncMode, VSyncOptions::storedMode)
                        .setImpact(OptionImpact.VARIES)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setId(StandardOptions.Option.MAX_FRAMERATE.cast())
                        .setName(TextComponent.translatable("options.framerateLimit"))
                        .setTooltip(TextComponent.translatable("sodium.options.fps_limit.tooltip"))
                        .setControl(option -> new SliderControl(option, 10, 260, 1, ControlValueFormatter.fpsLimit()))
                        .setBinding((opts, value) -> opts.limitFramerate = value, opts -> opts.limitFramerate)
                        .setDefaultValue(120)
                        .build())
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "loading_screen_framerate_limit", int.class))
                        .setName(TextComponent.translatable("options.actinium.loadingScreenFramerateLimit"))
                        .setTooltip(TextComponent.translatable("options.actinium.loadingScreenFramerateLimit.tooltip"))
                        .setControl(option -> new SliderControl(option, 30, 240, 10, ControlValueFormatter.fpsLimit()))
                        .setBinding((opts, value) -> opts.performance.loadingScreenFramerateLimit = value,
                                opts -> opts.performance.loadingScreenFramerateLimit)
                        .setDefaultValue(60)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .setId(StandardOptions.Group.INDICATORS)
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setId(StandardOptions.Option.VIEW_BOBBING.cast())
                        .setName(TextComponent.translatable("options.viewBobbing"))
                        .setTooltip(TextComponent.translatable("sodium.options.view_bobbing.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.viewBobbing = value, opts -> opts.viewBobbing)
                        .setDefaultValue(true)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setId(StandardOptions.Option.ATTACK_INDICATOR.cast())
                        .setName(TextComponent.translatable("options.attackIndicator"))
                        .setTooltip(TextComponent.translatable("sodium.options.attack_indicator.tooltip"))
                        .setControl(opts -> new CyclingControl<>(opts, new Integer[] { 0, 1, 2 }, new TextComponent[] {
                                TextComponent.translatable("options.off"),
                                TextComponent.translatable("options.attack.crosshair"),
                                TextComponent.translatable("options.attack.hotbar") }))
                        .setBinding((opts, value) -> opts.attackIndicator = value, (opts) -> opts.attackIndicator)
                        .setDefaultValue(1)
                        .build())
                .build());

        return new OptionPage(StandardOptions.Pages.GENERAL, TextComponent.translatable("stat.generalButton"), ImmutableList.copyOf(groups));
    }

    public static OptionPage quality() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .setId(StandardOptions.Group.GRAPHICS)
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setId(StandardOptions.Option.GRAPHICS_MODE.cast())
                        .setName(TextComponent.translatable("options.graphics"))
                        .setTooltip(TextComponent.translatable("sodium.options.graphics_quality.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.fancyGraphics = value, opts -> opts.fancyGraphics)
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setDefaultValue(true)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .setId(StandardOptions.Group.DETAILS)
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setId(StandardOptions.Option.CLOUDS.cast())
                        .setName(TextComponent.translatable("options.renderClouds"))
                        .setTooltip(TextComponent.translatable("sodium.options.clouds_quality.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, new Integer[] { 0, 1, 2}, new TextComponent[] {
                                TextComponent.translatable("options.off"),
                                TextComponent.translatable("options.clouds.fast"),
                                TextComponent.translatable("options.clouds.fancy") }))
                        .setBinding((opts, value) -> {
                            opts.clouds = value;
                        }, opts -> {
                            return opts.clouds;
                        })
                        .setImpact(OptionImpact.LOW)
                        .setDefaultValue(2)
                        .build())
                .add(OptionImpl.createBuilder(SodiumGameOptions.GraphicsQuality.class, sodiumOpts)
                        .setId(StandardOptions.Option.WEATHER.cast())
                        .setName(TextComponent.translatable("soundCategory.weather"))
                        .setTooltip(TextComponent.translatable("sodium.options.weather_quality.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.GraphicsQuality.class))
                        .setBinding((opts, value) -> opts.quality.weatherQuality = value, opts -> opts.quality.weatherQuality)
                        .setImpact(OptionImpact.MEDIUM)
                        .setDefaultValue(SodiumGameOptions.GraphicsQuality.DEFAULT)
                        .build())
                .add(OptionImpl.createBuilder(SodiumGameOptions.GraphicsQuality.class, sodiumOpts)
                        .setId(StandardOptions.Option.LEAVES.cast())
                        .setName(TextComponent.translatable("sodium.options.leaves_quality.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.leaves_quality.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.GraphicsQuality.class))
                        .setBinding((opts, value) -> opts.quality.leavesQuality = value, opts -> opts.quality.leavesQuality)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setDefaultValue(SodiumGameOptions.GraphicsQuality.DEFAULT)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setId(StandardOptions.Option.PARTICLES.cast())
                        .setName(TextComponent.translatable("options.particles"))
                        .setTooltip(TextComponent.translatable("sodium.options.particle_quality.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, new Integer[] { 0, 1, 2}, new TextComponent[] {
                                TextComponent.translatable("options.particles.all"),
                                TextComponent.translatable( "options.particles.decreased"),
                                TextComponent.translatable("options.particles.minimal") }))
                        .setBinding((opts, value) -> opts.particleSetting = value, (opts) -> opts.particleSetting)
                        .setImpact(OptionImpact.MEDIUM)
                        .setDefaultValue(0)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setId(StandardOptions.Option.SMOOTH_LIGHT.cast())
                        .setName(TextComponent.translatable("options.ao"))
                        .setTooltip(TextComponent.translatable("sodium.options.smooth_lighting.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, new Integer[] { 0, 1, 2}, new TextComponent[] {
                                TextComponent.translatable("options.ao.off"),
                                TextComponent.translatable("options.ao.min"),
                                TextComponent.translatable("options.ao.max") }))
                        .setBinding((opts, value) -> opts.ambientOcclusion = value, opts -> opts.ambientOcclusion)
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setDefaultValue(2)
                        .build())
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(StandardOptions.Option.BIOME_BLEND.cast())
                        .setName(TextComponent.translatable("sodium.options.biomeBlendRadius"))
                        .setTooltip(TextComponent.translatable("sodium.options.biome_blend.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 14, 1, ControlValueFormatter.biomeBlend()))
                        .setBinding((opts, value) -> opts.quality.legacyBiomeBlendRadius = value, opts -> opts.quality.legacyBiomeBlendRadius)
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setDefaultValue(0)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "biome_color_noise", boolean.class))
                        .setName(TextComponent.translatable("sodium.options.actinium.biome_color_noise.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.biome_color_noise.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.LOW)
                        .setBinding((opts, value) -> opts.quality.useBiomeColorNoise = value, opts -> opts.quality.useBiomeColorNoise)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setDefaultValue(true)
                        .build())
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "biome_color_noise_grass", int.class))
                        .setName(TextComponent.translatable("sodium.options.actinium.biome_color_noise.grass.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.biome_color_noise.grass.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 50, 1, ControlValueFormatter.percentage()))
                        .setBinding((opts, value) -> opts.quality.biomeColorNoiseGrassIntensity = value / 100.0F,
                                opts -> Math.round(opts.quality.biomeColorNoiseGrassIntensity * 100))
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setDefaultValue(8)
                        .build())
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "biome_color_noise_foliage", int.class))
                        .setName(TextComponent.translatable("sodium.options.actinium.biome_color_noise.foliage.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.biome_color_noise.foliage.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 50, 1, ControlValueFormatter.percentage()))
                        .setBinding((opts, value) -> opts.quality.biomeColorNoiseFoliageIntensity = value / 100.0F,
                                opts -> Math.round(opts.quality.biomeColorNoiseFoliageIntensity * 100))
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setDefaultValue(8)
                        .build())
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "biome_color_noise_water", int.class))
                        .setName(TextComponent.translatable("sodium.options.actinium.biome_color_noise.water.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.biome_color_noise.water.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 50, 1, ControlValueFormatter.percentage()))
                        .setBinding((opts, value) -> opts.quality.biomeColorNoiseWaterIntensity = value / 100.0F,
                                opts -> Math.round(opts.quality.biomeColorNoiseWaterIntensity * 100))
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setDefaultValue(8)
                        .build())
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(StandardOptions.Option.CHUNK_FADE_IN_DURATION.cast())
                        .setName(TextComponent.translatable("celeritas.options.chunk_fade_in_duration.name"))
                        .setTooltip(TextComponent.translatable("celeritas.options.chunk_fade_in_duration.tooltip"))
                        .setControl(o -> new SliderControl(o, 0, 2000, 100, ControlValueFormatter.translateVariable("celeritas.options.chunk_fade_in_duration.value")))
                        .setImpact(OptionImpact.LOW)
                        .setBinding((opts, value) -> opts.quality.chunkFadeInDuration = value, opts -> opts.quality.chunkFadeInDuration)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setDefaultValue(0)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setId(StandardOptions.Option.ENTITY_SHADOWS.cast())
                        .setName(TextComponent.translatable("options.entityShadows"))
                        .setTooltip(TextComponent.translatable("sodium.options.entity_shadows.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.entityShadows = value, opts -> opts.entityShadows)
                        .setImpact(OptionImpact.LOW)
                        .setDefaultValue(true)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.VIGNETTE.cast())
                        .setName(TextComponent.translatable("sodium.options.vignette.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.vignette.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.quality.enableVignette = value, opts -> opts.quality.enableVignette)
                        .setImpact(OptionImpact.LOW)
                        .setDefaultValue(true)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "circular_fog", boolean.class))
                        .setName(TextComponent.translatable("sodium.options.actinium.circular_fog.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.circular_fog.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.quality.circularFog = value, opts -> opts.quality.circularFog)
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setDefaultValue(false)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.DYNAMIC_FOV.cast())
                        .setName(TextComponent.translatable("sodium.options.dynamic_fov.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.dynamic_fov.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.quality.dynamicFov = value, opts -> opts.quality.dynamicFov)
                        .setImpact(OptionImpact.LOW)
                        .setDefaultValue(true)
                        .build())
                .build());


        final int anisotropyMin = TerrainTextureFiltering.minAnisotropyLevel();
        final int anisotropyMax = Math.max(TerrainTextureFiltering.maxAnisotropyLevel(), anisotropyMin + 1);

        final OptionImpl<SodiumGameOptions, TextureFilterMode> textureFilterMode =
                OptionImpl.createBuilder(TextureFilterMode.class, sodiumOpts)
                        .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "texture_filtering", TextureFilterMode.class))
                        .setName(TextComponent.translatable("sodium.options.texture_filtering.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.texture_filtering.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, TextureFilterMode.class,
                                TextureFilterMode.selectableValues(TerrainTextureFiltering.anisotropySupported())))
                        .setBinding((opts, value) -> opts.quality.textureFilterMode = value,
                                opts -> TerrainTextureFiltering.resolveFilterMode(opts.quality.textureFilterMode))
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD, OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setDefaultValue(TerrainTextureFiltering.resolveFilterMode(TextureFilterMode.RGSS_ANISOTROPIC))
                        .build();

        groups.add(OptionGroup.createBuilder()
                .setId(StandardOptions.Group.MIPMAPS)
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setId(StandardOptions.Option.MIPMAP_LEVEL.cast())
                        .setName(TextComponent.translatable("options.mipmapLevels"))
                        .setTooltip(TextComponent.translatable("sodium.options.mipmap_levels.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 4, 1, ControlValueFormatter.multiplier()))
                        .setBinding((opts, value) -> opts.mipmapLevels = value, opts -> opts.mipmapLevels)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                        .setDefaultValue(4)
                        .build())
                .add(textureFilterMode)
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "anisotropic_filtering", int.class))
                        .setName(TextComponent.translatable("sodium.options.anisotropic_filtering.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.anisotropic_filtering.tooltip"))
                        .setControl(option -> new SliderControl(option, anisotropyMin, anisotropyMax, 1,
                                value -> TextComponent.literal((1 << value) + "x")))
                        .setBinding((opts, value) -> opts.quality.anisotropicLevel = value,
                                opts -> MathHelper.clamp(opts.quality.anisotropicLevel, anisotropyMin, anisotropyMax))
                        .setImpact(OptionImpact.MEDIUM)
                        .setDefaultValue(1)
                        .setEnabledPredicate(() -> textureFilterMode.getValue().usesAnisotropy()
                                && TerrainTextureFiltering.hasAnisotropyRange())
                        .build())
                .add(OptionImpl.createBuilder(TexelSampling.class, sodiumOpts)
                        .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "texel_sampling", TexelSampling.class))
                        .setName(TextComponent.translatable("sodium.options.texel_sampling.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.texel_sampling.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, TexelSampling.class))
                        .setBinding((opts, value) -> opts.quality.texelSampling = value, opts -> opts.quality.texelSampling)
                        .setImpact(OptionImpact.LOW)
                        .setDefaultValue(TexelSampling.LINEAR)
                        // The shader only lets the texture's own filter state pick the mip level while no filter
                        // mode asks for the snapped path and nearest texels are wanted, so this choice changes a
                        // shader define - and needs a rerender - exactly when filtering is off.
                        .setFlags(() -> textureFilterMode.getValue() == TextureFilterMode.NONE
                                ? EnumSet.of(OptionFlag.REQUIRES_RENDERER_RELOAD)
                                : EnumSet.noneOf(OptionFlag.class))
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .setId(StandardOptions.Group.SORTING)
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.TRANSLUCENT_FACE_SORTING.cast())
                        .setName(TextComponent.translatable("sodium.options.translucent_face_sorting.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.translucent_face_sorting.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.VARIES)
                        .setBinding((opts, value) -> opts.performance.useTranslucentFaceSorting = value, opts -> opts.performance.useTranslucentFaceSorting)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setDefaultValue(true)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(OptionIdentifier.create("celeritas", "fast_block_renderer", boolean.class))
                        .setName(TextComponent.translatable("celeritas.options.fast_block_renderer.name"))
                        .setTooltip(TextComponent.translatable("celeritas.options.fast_block_renderer.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.performance.useFastBlockRenderer = value, opts -> opts.performance.useFastBlockRenderer)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setDefaultValue(true)
                        .build())
                .build());

        return new OptionPage(StandardOptions.Pages.QUALITY, TextComponent.translatable("sodium.options.pages.quality"), ImmutableList.copyOf(groups));
    }

    public static OptionPage advanced() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .setId(StandardOptions.Group.CPU_SAVING)
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(StandardOptions.Option.CPU_FRAMES_AHEAD.cast())
                        .setName(TextComponent.translatable("sodium.options.cpu_render_ahead_limit.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.cpu_render_ahead_limit.tooltip"))
                        .setControl(opt -> new SliderControl(opt, 0, 9, 1, ControlValueFormatter.translateVariable("sodium.options.cpu_render_ahead_limit.value")))
                        .setBinding((opts, value) -> opts.advanced.cpuRenderAheadLimit = value, opts -> opts.advanced.cpuRenderAheadLimit)
                        .setEnabled(LWJGL.isOpenGLVersionSupported(3, 2))
                        .setDefaultValue(0)
                        .build()
                )
                .add(OptionImpl.createBuilder(MultiDrawMode.class, sodiumOpts)
                        .setId(StandardOptions.Option.MULTIDRAW_MODE.cast())
                        .setName(TextComponent.translatable("sodium.options.multidraw_mode.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.multidraw_mode.tooltip"))
                        .setControl(option -> {
                            MultiDrawMode[] allowed = isIndirectMultiDrawSupported()
                                    ? MultiDrawMode.values()
                                    : new MultiDrawMode[] { MultiDrawMode.DIRECT, MultiDrawMode.INDIVIDUAL };
                            return new CyclingControl<>(option, MultiDrawMode.class, allowed);
                        })
                        .setBinding((opts, value) -> opts.advanced.multiDrawMode = value, opts -> opts.advanced.multiDrawMode)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setDefaultValue(MultiDrawMode.DIRECT)
                        .build()
                )
                .add(OptionImpl.createBuilder(SodiumGameOptions.StreamingUploadStrategy.class, sodiumOpts)
                        .setId(StandardOptions.Option.STREAMING_UPLOAD_STRATEGY.cast())
                        .setName(TextComponent.translatable("sodium.options.streaming_upload_strategy.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.streaming_upload_strategy.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.StreamingUploadStrategy.class))
                        .setBinding((opts, value) -> opts.advanced.streamingUploadStrategy = value, opts -> opts.advanced.streamingUploadStrategy)
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .setDefaultValue(SodiumGameOptions.StreamingUploadStrategy.MAP_BUFFER_RANGE)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.DEFERRED_PARTICLE_BATCHING.cast())
                        .setName(TextComponent.translatable("sodium.options.enable_deferred_batching.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.enable_deferred_batching.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.advanced.enableDeferredBatching = value, opts -> opts.advanced.enableDeferredBatching)
                        .setDefaultValue(true)
                        .build()
                )
                .add(createModelRendererBatchingOption(TextComponent.translatable("sodium.options.actinium.model_renderer_batching.tooltip")))
                .add(createModelRendererDisplayListsOption(TextComponent.translatable("sodium.options.model_renderer_display_lists.tooltip")))
                .add(createFastLitItemRenderingOption(TextComponent.translatable("sodium.options.fast_lit_item_rendering.tooltip")))
                .add(createFastLitItemDisplayListsOption(TextComponent.translatable("sodium.options.fast_lit_item_display_lists.tooltip")))
                .build());

        groups.add(OptionGroup.createBuilder()
                .setId(StandardOptions.Option.ALLOW_DIRECT_MEMORY_ACCESS.cast())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.ALLOW_DIRECT_MEMORY_ACCESS.cast())
                        .setName(TextComponent.translatable("sodium.options.allow_direct_memory_access.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.allow_direct_memory_access.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.advanced.allowDirectMemoryAccess = value, opts -> opts.advanced.allowDirectMemoryAccess)
                        .setDefaultValue(true)
                        .build()
                )
                .build());

        groups.add(OptionGroup.createBuilder()
                .setId(StandardOptions.Option.ACTINIUM_IGNORE_FRAMEBUFFER_ERRORS.cast())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.ACTINIUM_IGNORE_FRAMEBUFFER_ERRORS.cast())
                        .setName(TextComponent.translatable("sodium.options.actinium.ignore_framebuffer_errors.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.ignore_framebuffer_errors.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.debug.ignoreFramebufferErrors = value, opts -> opts.debug.ignoreFramebufferErrors)
                        .setDefaultValue(false)
                        .build()
                )
                .build());

        groups.add(OptionGroup.createBuilder()
                .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "texture_loading"))
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "parallel_atlas_decode", boolean.class))
                        .setName(TextComponent.translatable("sodium.options.parallel_atlas_decode.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.parallel_atlas_decode.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                        .setBinding((opts, value) -> opts.performance.enableParallelAtlasDecode = value, opts -> opts.performance.enableParallelAtlasDecode)
                        .setDefaultValue(true)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "parallel_atlas_mipmaps", boolean.class))
                        .setName(TextComponent.translatable("sodium.options.parallel_atlas_mipmaps.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.parallel_atlas_mipmaps.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                        .setBinding((opts, value) -> opts.performance.enableParallelAtlasMipmaps = value, opts -> opts.performance.enableParallelAtlasMipmaps)
                        .setDefaultValue(true)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "batched_atlas_upload", boolean.class))
                        .setName(TextComponent.translatable("sodium.options.batched_atlas_upload.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.batched_atlas_upload.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_ASSET_RELOAD)
                        .setBinding((opts, value) -> opts.performance.enableBatchedAtlasUpload = value, opts -> opts.performance.enableBatchedAtlasUpload)
                        .setDefaultValue(true)
                        .build()
                )
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "worker_threads", int.class))
                        .setName(TextComponent.translatable("sodium.options.worker_threads.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.worker_threads.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 32, 1,
                                ControlValueFormatter.translateDisabledOrVariable(
                                        "sodium.options.worker_threads.default",
                                        "sodium.options.worker_threads.value")))
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .setBinding((opts, value) -> opts.performance.workerThreadCount = value, opts -> opts.performance.workerThreadCount)
                        .setDefaultValue(0)
                        .build()
                )
                .add(OptionImpl.createBuilder(Void.class, sodiumOpts)
                        .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "atlas_packs"))
                        .setName(TextComponent.translatable("sodium.options.atlas_packs.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.atlas_packs.tooltip"))
                        .setControl(option -> new ExternalButtonControl(option,
                                parent -> Minecraft.getMinecraft().displayGuiScreen(new AtlasPackScreen(parent)),
                                TextComponent.translatable("sodium.options.atlas_packs.button")))
                        .setBinding((opts, value) -> { }, opts -> null)
                        .build()
                )
                .build());

        return new OptionPage(StandardOptions.Pages.ADVANCED, TextComponent.translatable("sodium.options.pages.advanced"), ImmutableList.copyOf(groups));
    }

    /**
     * The reducer's settings. The frame rate limits here are ceilings the reducer applies on top of
     * the player's own Max Framerate; the volume percentages scale the player's own master volume
     * rather than replacing it.
     */
    public static OptionPage fpsReducer() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .setId(FPS_REDUCER_BACKGROUND)
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "fps_reducer_enabled", boolean.class))
                        .setName(TextComponent.translatable("sodium.options.actinium.fps_reducer_enabled.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.fps_reducer_enabled.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.LOW)
                        .setBinding(reducerBinding((opts, value) -> opts.reducer.enabled = value),
                                opts -> opts.reducer.enabled)
                        .setDefaultValue(false)
                        .build())
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "fps_reducer_unfocused_fps_limit", int.class))
                        .setName(TextComponent.translatable("sodium.options.actinium.unfocused_fps_limit.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.unfocused_fps_limit.tooltip"))
                        .setControl(option -> new SliderControl(option, REDUCER_MIN_FRAMERATE, REDUCER_MAX_FRAMERATE, 1, ControlValueFormatter.fpsLimit()))
                        .setImpact(OptionImpact.LOW)
                        .setBinding(reducerBinding((opts, value) -> opts.reducer.unfocusedFpsLimit = value),
                                opts -> opts.reducer.unfocusedFpsLimit)
                        .setDefaultValue(10)
                        .build())
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "fps_reducer_unfocused_volume", int.class))
                        .setName(TextComponent.translatable("sodium.options.actinium.unfocused_volume.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.unfocused_volume.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 100, 1, ControlValueFormatter.percentage()))
                        .setImpact(OptionImpact.LOW)
                        .setBinding(reducerBinding((opts, value) -> opts.reducer.unfocusedVolume = value),
                                opts -> opts.reducer.unfocusedVolume)
                        .setDefaultValue(100)
                        .build())
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "fps_reducer_minimized_volume", int.class))
                        .setName(TextComponent.translatable("sodium.options.actinium.minimized_volume.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.minimized_volume.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 100, 1, ControlValueFormatter.percentage()))
                        .setImpact(OptionImpact.LOW)
                        .setBinding(reducerBinding((opts, value) -> opts.reducer.minimizedVolume = value),
                                opts -> opts.reducer.minimizedVolume)
                        .setDefaultValue(100)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .setId(FPS_REDUCER_IDLE)
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "fps_reducer_idle_timeout", int.class))
                        .setName(TextComponent.translatable("sodium.options.actinium.idle_timeout.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.idle_timeout.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 30, 1,
                                ControlValueFormatter.translateDisabledOrVariable(
                                        "sodium.options.actinium.idle_timeout.off",
                                        "sodium.options.actinium.idle_timeout.value")))
                        .setImpact(OptionImpact.LOW)
                        .setBinding(reducerBinding((opts, value) -> opts.reducer.idleTimeoutMinutes = value),
                                opts -> opts.reducer.idleTimeoutMinutes)
                        .setDefaultValue(5)
                        .build())
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "fps_reducer_idle_fps_limit", int.class))
                        .setName(TextComponent.translatable("sodium.options.actinium.idle_fps_limit.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.idle_fps_limit.tooltip"))
                        .setControl(option -> new SliderControl(option, REDUCER_MIN_FRAMERATE, REDUCER_MAX_FRAMERATE, 1, ControlValueFormatter.fpsLimit()))
                        .setImpact(OptionImpact.LOW)
                        .setBinding(reducerBinding((opts, value) -> opts.reducer.idleFpsLimit = value),
                                opts -> opts.reducer.idleFpsLimit)
                        .setDefaultValue(30)
                        .build())
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "fps_reducer_idle_volume", int.class))
                        .setName(TextComponent.translatable("sodium.options.actinium.idle_volume.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.idle_volume.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 100, 1, ControlValueFormatter.percentage()))
                        .setImpact(OptionImpact.LOW)
                        .setBinding(reducerBinding((opts, value) -> opts.reducer.idleVolume = value),
                                opts -> opts.reducer.idleVolume)
                        .setDefaultValue(100)
                        .build())
                .build());

        groups.add(OptionGroup.createBuilder()
                .setId(FPS_REDUCER_MENU)
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "fps_reducer_limit_menu_fps", boolean.class))
                        .setName(TextComponent.translatable("sodium.options.actinium.limit_menu_fps.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.limit_menu_fps.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.LOW)
                        .setBinding(reducerBinding((opts, value) -> opts.reducer.limitMenuFrameRate = value),
                                opts -> opts.reducer.limitMenuFrameRate)
                        .setDefaultValue(true)
                        .build())
                .build());

        return new OptionPage(FPS_REDUCER_PAGE, TextComponent.translatable("sodium.options.actinium.pages.fps_reducer"), ImmutableList.copyOf(groups));
    }

    private static <T> BiConsumer<SodiumGameOptions, T> reducerBinding(BiConsumer<SodiumGameOptions, T> setter) {
        return (opts, value) -> {
            setter.accept(opts, value);
            FpsReducer.markConfigChanged();
        };
    }

    public static OptionPage debug() {
        List<OptionGroup> groups = new ArrayList<>();

        groups.add(OptionGroup.createBuilder()
                .setId(StandardOptions.Group.ACTINIUM_DEBUG)
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.ACTINIUM_PRODUCTION_DIAGNOSTICS.cast())
                        .setName(TextComponent.translatable("sodium.options.actinium.production_diagnostics.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.production_diagnostics.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.LOW)
                        .setBinding((opts, value) -> opts.debug.enableProductionDiagnostics = value, opts -> opts.debug.enableProductionDiagnostics)
                        .setDefaultValue(true)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.ACTINIUM_GL_DEBUG.cast())
                        .setName(TextComponent.translatable("sodium.options.actinium.gl_debug.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.gl_debug.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.debug.enableActiniumGlDebug = value, opts -> opts.debug.enableActiniumGlDebug)
                        .setDefaultValue(false)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.ACTINIUM_LWJGL_DEBUG.cast())
                        .setName(TextComponent.translatable("sodium.options.actinium.lwjgl_debug.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.lwjgl_debug.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.debug.enableLwjglDebug = value, opts -> opts.debug.enableLwjglDebug)
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .setDefaultValue(false)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.ACTINIUM_PBR_DEBUG.cast())
                        .setName(TextComponent.translatable("sodium.options.actinium.pbr_debug.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.pbr_debug.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.debug.enablePbrDebug = value, opts -> opts.debug.enablePbrDebug)
                        .setDefaultValue(false)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.ACTINIUM_CLOUD_CONTROL_DEBUG.cast())
                        .setName(TextComponent.translatable("sodium.options.actinium.cloud_control_debug.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.cloud_control_debug.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.debug.enableCloudControlDebug = value, opts -> opts.debug.enableCloudControlDebug)
                        .setDefaultValue(false)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.ACTINIUM_PERF_DEBUG.cast())
                        .setName(TextComponent.translatable("sodium.options.actinium.perf_debug.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.perf_debug.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> {
                            opts.debug.enableActiniumPerfDebug = value;
                            GLSMPerfDebugHooks.setConfiguredEnabled(
                                ActiniumRuntimeOptions.resolvePerfDebugEnabled(value)
                            );
                        }, opts -> opts.debug.enableActiniumPerfDebug)
                        .setDefaultValue(false)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.ACTINIUM_GPU_PERF_DEBUG.cast())
                        .setName(TextComponent.translatable("sodium.options.actinium.gpu_perf_debug.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.gpu_perf_debug.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.debug.enableActiniumGpuPerfDebug = value, opts -> opts.debug.enableActiniumGpuPerfDebug)
                        .setDefaultValue(false)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.ACTINIUM_FRAME_GL_ERROR_CHECK.cast())
                        .setName(TextComponent.translatable("sodium.options.actinium.frame_gl_error_check.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.frame_gl_error_check.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.debug.enableFrameGlErrorCheck = value, opts -> opts.debug.enableFrameGlErrorCheck)
                        .setDefaultValue(false)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.ACTINIUM_POST_RENDER_GL_ERROR_CHECK.cast())
                        .setName(TextComponent.translatable("sodium.options.actinium.post_render_gl_error_check.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.post_render_gl_error_check.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.debug.enablePostRenderGlErrorCheck = value, opts -> opts.debug.enablePostRenderGlErrorCheck)
                        .setDefaultValue(false)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.ACTINIUM_REDIRECTOR_DEBUG.cast())
                        .setName(TextComponent.translatable("sodium.options.actinium.redirector_debug.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.redirector_debug.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.debug.enableRedirectorDebug = value, opts -> opts.debug.enableRedirectorDebug)
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .setDefaultValue(false)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.ACTINIUM_REDIRECTOR_LOG_SPAM.cast())
                        .setName(TextComponent.translatable("sodium.options.actinium.redirector_log_spam.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.redirector_log_spam.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.debug.enableRedirectorLogSpam = value, opts -> opts.debug.enableRedirectorLogSpam)
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .setDefaultValue(false)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.ACTINIUM_REDIRECTOR_CLASS_DUMP.cast())
                        .setName(TextComponent.translatable("sodium.options.actinium.redirector_class_dump.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.redirector_class_dump.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.debug.enableRedirectorClassDump = value, opts -> opts.debug.enableRedirectorClassDump)
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .setDefaultValue(false)
                        .build())
                .build());

        return new OptionPage(StandardOptions.Pages.DEBUG, TextComponent.translatable("sodium.options.pages.debug"), ImmutableList.copyOf(groups));
    }

    private static boolean isIndirectMultiDrawSupported() {
        return LWJGL.isOpenGLVersionSupported(4, 3) || LWJGL.isExtensionSupported(GLExtension.ARB_multi_draw_indirect);
    }

    public static OptionStorage<GameSettings> getVanillaOpts() {
        return vanillaOpts;
    }

    public static OptionStorage<SodiumGameOptions> getSodiumOpts() {
        return sodiumOpts;
    }
}
