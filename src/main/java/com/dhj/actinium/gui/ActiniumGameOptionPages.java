package com.dhj.actinium.gui;

import com.google.common.collect.ImmutableList;
import com.dhj.actinium.config.ActiniumRuntimeOptions;
import com.dhj.actinium.compat.scalingguis.ScalingGuiCompat;
import com.gtnewhorizon.gtnhlib.compat.Mods;
import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebugHooks;
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
import org.lwjgl.opengl.Display;
import dhj.embeddedt.embeddium.impl.render.chunk.MultiDrawMode;
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
import java.util.List;

public class ActiniumGameOptionPages {
    private static final SodiumGameOptions sodiumOpts = ActiniumRuntime.options();
    private static final MinecraftOptionsStorage vanillaOpts = new MinecraftOptionsStorage();

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
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setId(StandardOptions.Option.BRIGHTNESS.cast())
                        .setName(TextComponent.translatable("options.gamma"))
                        .setTooltip(TextComponent.translatable("sodium.options.brightness.tooltip"))
                        .setControl(opt -> new SliderControl(opt, 0, 100, 1, ControlValueFormatter.brightness()))
                        .setBinding((opts, value) -> opts.gammaSetting = (float) (value * 0.01D), (opts) -> (int) (opts.gammaSetting / 0.01D))
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
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setId(StandardOptions.Option.VSYNC.cast())
                        .setName(TextComponent.translatable("options.vsync"))
                        .setTooltip(TextComponent.translatable("sodium.options.v_sync.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> {
                            opts.enableVsync = value;
                            Display.setVSyncEnabled(opts.enableVsync);
                        }, opts -> opts.enableVsync)
                        .setImpact(OptionImpact.VARIES)
                        .build())
                .add(OptionImpl.createBuilder(int.class, vanillaOpts)
                        .setId(StandardOptions.Option.MAX_FRAMERATE.cast())
                        .setName(TextComponent.translatable("options.framerateLimit"))
                        .setTooltip(TextComponent.translatable("sodium.options.fps_limit.tooltip"))
                        .setControl(option -> new SliderControl(option, 10, 260, 10, ControlValueFormatter.fpsLimit()))
                        .setBinding((opts, value) -> opts.limitFramerate = value, opts -> opts.limitFramerate)
                        .build())
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "loading_screen_framerate_limit", int.class))
                        .setName(TextComponent.translatable("options.actinium.loadingScreenFramerateLimit"))
                        .setTooltip(TextComponent.translatable("options.actinium.loadingScreenFramerateLimit.tooltip"))
                        .setControl(option -> new SliderControl(option, 30, 240, 10, ControlValueFormatter.fpsLimit()))
                        .setBinding((opts, value) -> opts.performance.loadingScreenFramerateLimit = value,
                                opts -> opts.performance.loadingScreenFramerateLimit)
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
                        .build())
                .add(OptionImpl.createBuilder(SodiumGameOptions.GraphicsQuality.class, sodiumOpts)
                        .setId(StandardOptions.Option.WEATHER.cast())
                        .setName(TextComponent.translatable("soundCategory.weather"))
                        .setTooltip(TextComponent.translatable("sodium.options.weather_quality.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.GraphicsQuality.class))
                        .setBinding((opts, value) -> opts.quality.weatherQuality = value, opts -> opts.quality.weatherQuality)
                        .setImpact(OptionImpact.MEDIUM)
                        .build())
                .add(OptionImpl.createBuilder(SodiumGameOptions.GraphicsQuality.class, sodiumOpts)
                        .setId(StandardOptions.Option.LEAVES.cast())
                        .setName(TextComponent.translatable("sodium.options.leaves_quality.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.leaves_quality.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.GraphicsQuality.class))
                        .setBinding((opts, value) -> opts.quality.leavesQuality = value, opts -> opts.quality.leavesQuality)
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
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
                        .build())
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(StandardOptions.Option.BIOME_BLEND.cast())
                        .setName(TextComponent.translatable("sodium.options.biomeBlendRadius"))
                        .setTooltip(TextComponent.translatable("sodium.options.biome_blend.tooltip"))
                        .setControl(option -> new SliderControl(option, 0, 14, 1, ControlValueFormatter.biomeBlend()))
                        .setBinding((opts, value) -> opts.quality.legacyBiomeBlendRadius = value, opts -> opts.quality.legacyBiomeBlendRadius)
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(OptionIdentifier.create(ActiniumRuntime.MODID, "biome_color_noise", boolean.class))
                        .setName(TextComponent.translatable("sodium.options.actinium.biome_color_noise.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.biome_color_noise.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.LOW)
                        .setBinding((opts, value) -> opts.quality.useBiomeColorNoise = value, opts -> opts.quality.useBiomeColorNoise)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
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
                        .build())
                .add(OptionImpl.createBuilder(int.class, sodiumOpts)
                        .setId(StandardOptions.Option.CHUNK_FADE_IN_DURATION.cast())
                        .setName(TextComponent.translatable("celeritas.options.chunk_fade_in_duration.name"))
                        .setTooltip(TextComponent.translatable("celeritas.options.chunk_fade_in_duration.tooltip"))
                        .setControl(o -> new SliderControl(o, 0, 2000, 100, ControlValueFormatter.translateVariable("celeritas.options.chunk_fade_in_duration.value")))
                        .setImpact(OptionImpact.LOW)
                        .setBinding((opts, value) -> opts.quality.chunkFadeInDuration = value, opts -> opts.quality.chunkFadeInDuration)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, vanillaOpts)
                        .setId(StandardOptions.Option.ENTITY_SHADOWS.cast())
                        .setName(TextComponent.translatable("options.entityShadows"))
                        .setTooltip(TextComponent.translatable("sodium.options.entity_shadows.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.entityShadows = value, opts -> opts.entityShadows)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.VIGNETTE.cast())
                        .setName(TextComponent.translatable("sodium.options.vignette.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.vignette.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.quality.enableVignette = value, opts -> opts.quality.enableVignette)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.DYNAMIC_FOV.cast())
                        .setName(TextComponent.translatable("sodium.options.dynamic_fov.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.dynamic_fov.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setBinding((opts, value) -> opts.quality.dynamicFov = value, opts -> opts.quality.dynamicFov)
                        .setImpact(OptionImpact.LOW)
                        .build())
                .build());


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
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(OptionIdentifier.create("celeritas", "fast_block_renderer", boolean.class))
                        .setName(TextComponent.translatable("celeritas.options.fast_block_renderer.name"))
                        .setTooltip(TextComponent.translatable("celeritas.options.fast_block_renderer.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.performance.useFastBlockRenderer = value, opts -> opts.performance.useFastBlockRenderer)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
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
                        .build()
                )
                .add(OptionImpl.createBuilder(SodiumGameOptions.StreamingUploadStrategy.class, sodiumOpts)
                        .setId(StandardOptions.Option.STREAMING_UPLOAD_STRATEGY.cast())
                        .setName(TextComponent.translatable("sodium.options.streaming_upload_strategy.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.streaming_upload_strategy.tooltip"))
                        .setControl(option -> new CyclingControl<>(option, SodiumGameOptions.StreamingUploadStrategy.class))
                        .setBinding((opts, value) -> opts.advanced.streamingUploadStrategy = value, opts -> opts.advanced.streamingUploadStrategy)
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .build()
                )
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.DEFERRED_PARTICLE_BATCHING.cast())
                        .setName(TextComponent.translatable("sodium.options.enable_deferred_batching.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.enable_deferred_batching.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.advanced.enableDeferredBatching = value, opts -> opts.advanced.enableDeferredBatching)
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
                        .build()
                )
                .build());

        return new OptionPage(StandardOptions.Pages.ADVANCED, TextComponent.translatable("sodium.options.pages.advanced"), ImmutableList.copyOf(groups));
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
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.ACTINIUM_GL_DEBUG.cast())
                        .setName(TextComponent.translatable("sodium.options.actinium.gl_debug.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.gl_debug.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.debug.enableActiniumGlDebug = value, opts -> opts.debug.enableActiniumGlDebug)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.ACTINIUM_LWJGL_DEBUG.cast())
                        .setName(TextComponent.translatable("sodium.options.actinium.lwjgl_debug.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.lwjgl_debug.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.debug.enableLwjglDebug = value, opts -> opts.debug.enableLwjglDebug)
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.ACTINIUM_PBR_DEBUG.cast())
                        .setName(TextComponent.translatable("sodium.options.actinium.pbr_debug.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.pbr_debug.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.debug.enablePbrDebug = value, opts -> opts.debug.enablePbrDebug)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.ACTINIUM_CLOUD_CONTROL_DEBUG.cast())
                        .setName(TextComponent.translatable("sodium.options.actinium.cloud_control_debug.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.cloud_control_debug.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.debug.enableCloudControlDebug = value, opts -> opts.debug.enableCloudControlDebug)
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
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.ACTINIUM_GPU_PERF_DEBUG.cast())
                        .setName(TextComponent.translatable("sodium.options.actinium.gpu_perf_debug.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.gpu_perf_debug.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.debug.enableActiniumGpuPerfDebug = value, opts -> opts.debug.enableActiniumGpuPerfDebug)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.ACTINIUM_FRAME_GL_ERROR_CHECK.cast())
                        .setName(TextComponent.translatable("sodium.options.actinium.frame_gl_error_check.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.frame_gl_error_check.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.debug.enableFrameGlErrorCheck = value, opts -> opts.debug.enableFrameGlErrorCheck)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.ACTINIUM_POST_RENDER_GL_ERROR_CHECK.cast())
                        .setName(TextComponent.translatable("sodium.options.actinium.post_render_gl_error_check.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.post_render_gl_error_check.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.debug.enablePostRenderGlErrorCheck = value, opts -> opts.debug.enablePostRenderGlErrorCheck)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.ACTINIUM_REDIRECTOR_DEBUG.cast())
                        .setName(TextComponent.translatable("sodium.options.actinium.redirector_debug.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.redirector_debug.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.MEDIUM)
                        .setBinding((opts, value) -> opts.debug.enableRedirectorDebug = value, opts -> opts.debug.enableRedirectorDebug)
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.ACTINIUM_REDIRECTOR_LOG_SPAM.cast())
                        .setName(TextComponent.translatable("sodium.options.actinium.redirector_log_spam.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.redirector_log_spam.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.debug.enableRedirectorLogSpam = value, opts -> opts.debug.enableRedirectorLogSpam)
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .build())
                .add(OptionImpl.createBuilder(boolean.class, sodiumOpts)
                        .setId(StandardOptions.Option.ACTINIUM_REDIRECTOR_CLASS_DUMP.cast())
                        .setName(TextComponent.translatable("sodium.options.actinium.redirector_class_dump.name"))
                        .setTooltip(TextComponent.translatable("sodium.options.actinium.redirector_class_dump.tooltip"))
                        .setControl(TickBoxControl::new)
                        .setImpact(OptionImpact.HIGH)
                        .setBinding((opts, value) -> opts.debug.enableRedirectorClassDump = value, opts -> opts.debug.enableRedirectorClassDump)
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
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
