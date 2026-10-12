package com.dhj.actinium;

import com.dhj.actinium.compat.chunkanimator.ChunkAnimatorCompat;
import com.dhj.actinium.compat.glsm.VanillaTextureMirrorCompat;
import com.dhj.actinium.compat.MissingModelCompat;
import com.dhj.actinium.compat.kirino.KirinoCompat;
import com.dhj.actinium.compat.neofontrender.NeoFontRenderCompat;
import com.dhj.actinium.compat.neverenoughanimations.NeverEnoughAnimationsAlphaOverride;
import com.dhj.actinium.command.TogglePassCommand;
import com.dhj.actinium.config.ActiniumConfig;
import com.dhj.actinium.config.ActiniumRuntimeOptions;
import com.dhj.actinium.debug.ActiniumDiagnostics;
import com.dhj.actinium.gui.VSyncOptions;
import com.dhj.actinium.mixin.vintage.core.terrain.AccessorEntityRenderer;
import com.dhj.actinium.render.FastLitItemDisplayListCache;
import com.dhj.actinium.render.pacing.FpsReducer;
import com.dhj.actinium.render.pacing.FramePacer;
import com.dhj.actinium.render.terrain.ActiniumWorldRenderer;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.gtnewhorizons.angelica.proxy.ClientProxy;
import com.gtnewhorizon.gtnhlib.compat.Mods;
import net.coderbot.iris.debug.IrisDebugOptions;
import com.gtnewhorizon.gtnhlib.client.renderer.RuntimeOptionsBridge;
import com.gtnewhorizon.gtnhlib.client.renderer.postprocessing.PostProcessingBridge;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GlslTransformUtils;
import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebugHooks;
import com.gtnewhorizons.angelica.glsm.compat.FogDistanceModeBridge;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCache;
import com.gtnewhorizons.angelica.utils.AngelicaJar;
import com.gtnewhorizons.angelica.iris.IrisGLSMBridge;
import com.mojang.realmsclient.gui.ChatFormatting;
import net.coderbot.iris.Iris;
import net.coderbot.iris.pipeline.AdaptiveShadowBoundsStats;
import net.coderbot.iris.pipeline.transform.TransformPatcher;
import net.coderbot.iris.texture.pbr.PBRResourceIndex;
import net.coderbot.iris.texture.pbr.PBRTextureManager;
import net.coderbot.iris.uniforms.EntityIdHelper;
import net.coderbot.iris.uniforms.MatrixUniforms;
import net.coderbot.iris.compat.dh.DHCompat;
import net.coderbot.iris.rendertarget.IRenderTargetExt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLConstructionEvent;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import dhj.embeddedt.embeddium.impl.common.util.MathUtil;
import dhj.embeddedt.embeddium.impl.common.util.NativeBuffer;
import dhj.embeddedt.embeddium.impl.gl.device.GLRenderDevice;
import dhj.embeddedt.embeddium.impl.gl.shader.GlProgram;
import dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions;
import dhj.embeddedt.embeddium.impl.runtime.EmbeddiumRuntimeOptions;
import java.io.IOException;
import java.nio.file.Path;
import java.lang.management.ManagementFactory;

@Mod(modid =
        Actinium.MODID,
        useMetadata = true,
        clientSideOnly = true,
        acceptableRemoteVersions = "*",
        guiFactory = "com.dhj.actinium.gui.ActiniumGuiFactory"
)
public class Actinium {
    public static final String MODID = ActiniumRuntime.MODID;

    @EventHandler
    public void onConstruct(FMLConstructionEvent event) {
        GLRenderDevice.VANILLA_STATE_RESETTER = () -> OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);

        var container = Loader.instance().getIndexedModList().get(MODID);
        String version = container != null ? container.getVersion() : "unknown";
        ActiniumRuntime.setVersion(version);
        RuntimeOptionsBridge.setAllowDirectMemoryAccess(ActiniumRuntimeOptions::allowDirectMemoryAccess);
        FogDistanceModeBridge.setCircularFogEnabledProvider(() -> ActiniumRuntime.options().quality.circularFog);
        EmbeddiumRuntimeOptions.setChunkMultiDrawMode(() -> ActiniumRuntime.options().advanced.multiDrawMode);
        PostProcessingBridge.setDepthTextureProvider(framebuffer -> ((IRenderTargetExt) framebuffer).iris$getDepthTextureId());
        PostProcessingBridge.setLightmapColorAccessor(renderer -> ((AccessorEntityRenderer) renderer).getLightmapColors());
        PostProcessingBridge.setLightmapTextureAccessor(renderer -> ((AccessorEntityRenderer) renderer).getLightmapTexture());
        PostProcessingBridge.setNightVisionBrightnessInvoker(
            (entity, partialTicks) -> ((AccessorEntityRenderer) Minecraft.getMinecraft().entityRenderer)
                .invokeGetNightVisionBrightness(entity, partialTicks));
        net.coderbot.iris.celeritas.WorldRendererCompatBridge.setProvider(
            com.dhj.actinium.render.terrain.ActiniumWorldRenderer::instanceNullable);
        IrisDebugOptions.setBridge(new IrisDebugOptions.Bridge() {
            @Override
            public boolean pbrDebugEnabled() {
                return ActiniumRuntimeOptions.pbrDebugEnabled();
            }

            @Override
            public boolean enableActiniumGlDebug() {
                return ActiniumRuntime.options().debug.enableActiniumGlDebug;
            }

            @Override
            public boolean enableCloudControlDebug() {
                return ActiniumRuntime.options().debug.enableCloudControlDebug;
            }

            @Override
            public boolean enableFrameGlErrorCheck() {
                return ActiniumRuntime.options().debug.enableFrameGlErrorCheck;
            }

            @Override
            public boolean enablePostRenderGlErrorCheck() {
                return ActiniumRuntime.options().debug.enablePostRenderGlErrorCheck;
            }

            @Override
            public boolean enableActiniumPerfDebug() {
                return ActiniumRuntime.options().debug.enableActiniumPerfDebug;
            }

            @Override
            public boolean enableActiniumGpuPerfDebug() {
                return ActiniumRuntime.options().debug.enableActiniumGpuPerfDebug;
            }

            @Override
            public boolean ignoreFramebufferErrors() {
                return ActiniumRuntime.options().debug.ignoreFramebufferErrors;
            }

            @Override
            public boolean enableIris() {
                return ActiniumConfig.enableIris;
            }

            @Override
            public boolean enableCeleritas() {
                return ActiniumConfig.enableCeleritas;
            }

            @Override
            public boolean defineIsIris() {
                return ActiniumConfig.defineIsIris;
            }

            @Override
            public boolean enableHardcodedCustomUniforms() {
                return ActiniumConfig.enableHardcodedCustomUniforms;
            }

            @Override
            public boolean disableF3Additions() {
                return ActiniumConfig.disableF3Additions;
            }

            @Override
            public boolean useTotalWorldTime() {
                return ActiniumConfig.useTotalWorldTime;
            }

            @Override
            public void cycleAnimationsMode() {
                ClientProxy.animationsMode.next();
            }
        });
        GLSMPerfDebugHooks.addStatsProvider(Actinium::dumpExtraPerfStats);
        GLSMPerfDebugHooks.addStatsProvider(FramePacer::statsSummary);
        GLSMPerfDebugHooks.addStatsProvider(ProgramBinaryCache::statsSummary);
        GLSMPerfDebugHooks.addStatsProvider(GlslTransformUtils::renameStatsSummary);
        GLSMPerfDebugHooks.setConfiguredEnabled(
            ActiniumRuntimeOptions.resolvePerfDebugEnabled(ActiniumRuntime.options().debug.enableActiniumPerfDebug)
        );
        GLSMPerfDebugHooks.setEnabledChangeListener(Actinium::reloadShaderPipelineForPerfDebug);

        GLSMHooks.textureBindSyncCallback = VanillaTextureMirrorCompat.createCallback();
        // Celeritas links its terrain programs itself, so the link-time hint that makes a program's
        // binary readable back (and with it the saved-program cache) never reaches that linker otherwise.
        GlProgram.setLinkHook(ProgramBinaryCache::markRetrievable);

        ActiniumDiagnostics.logConstruction();
        initializeDistantHorizonsCompat();
        MinecraftForge.EVENT_BUS.register(this);
    }

    @EventHandler
    public void onInit(FMLInitializationEvent event) {
        if (Mods.NEOFONTRENDER) {
            NeoFontRenderCompat.initialize();
        }
        ChunkAnimatorCompat.install();
        KirinoCompat.install();
        NeverEnoughAnimationsAlphaOverride.install();
        FpsReducer.init();
        // Registered with the reducer rather than at construction: this is where the class is first loaded,
        // and its static state reads the game clock.
        GLSMPerfDebugHooks.addStatsProvider(FpsReducer::stateSummary);
        initVSync();

        ((IReloadableResourceManager) Minecraft.getMinecraft().getResourceManager())
                .registerReloadListener(resourceManager -> MissingModelCompat.onResourceManagerReload());
        // PBR lookups are answered from an index of the loaded packs, so a pack change has to invalidate
        // both the loaded PBR textures and that index.
        ((IReloadableResourceManager) Minecraft.getMinecraft().getResourceManager())
                .registerReloadListener(PBRTextureManager.INSTANCE::onResourceManagerReload);

        if ((Boolean) Launch.blackboard.get("fml.deobfuscatedEnvironment")) {
            ClientCommandHandler.instance.registerCommand(new TogglePassCommand());
        }

        if (Iris.enabled) {
            // Shader stages and preprocessed sources are cached on disk; point the cache at this instance
            // before anything loads a pack so the first load already benefits from it.
            final Path actiniumJar = AngelicaJar.location();
            AngelicaJar.configureShaderDiskCache(actiniumJar != null ? actiniumJar.toFile() : null);
            IrisGLSMBridge.register();
            // The shader side's stats only have anything to report while Iris is running, and registering
            // them here keeps their classes out of the way of a client that has shaders disabled.
            GLSMPerfDebugHooks.addStatsProvider(TransformPatcher::statsSummary);
            GLSMPerfDebugHooks.addStatsProvider(PBRResourceIndex::statsSummary);
            GLSMPerfDebugHooks.addStatsProvider(MatrixUniforms::previousStatsSummary);
            GLSMPerfDebugHooks.addStatsProvider(EntityIdHelper::statsSummary);
            Iris.INSTANCE.fmlInitEvent();
            MinecraftForge.EVENT_BUS.register(Iris.INSTANCE);
        }

        ActiniumDiagnostics.logInitialization(ActiniumRuntime.version());
    }

    /**
     * Adopts the stored vsync mode and hands the pacer the work it can do while it waits.
     *
     * <p>The mode is written here for a config that holds none, because the right default depends on
     * the display and on what the backend can deliver, neither of which is known while the config is
     * parsed. Only the preference is recorded — the driver is not touched, because no window exists
     * yet; the mode reaches the driver from vanilla's own startup switch, which is routed through the
     * backend.</p>
     *
     * <p>The idle work is the chunk pipeline's deferred work, which the pacer would otherwise sleep
     * through.</p>
     */
    private static void initVSync() {
        final SodiumGameOptions opts = ActiniumRuntime.options();
        if (opts.advanced.vsyncMode == null) {
            opts.advanced.vsyncMode = VSyncOptions.defaultMode();
        }
        Minecraft.getMinecraft().gameSettings.enableVsync = opts.advanced.vsyncMode.tearFree();
        GLStateManager.setVSyncPreference(opts.advanced.vsyncMode);
        FramePacer.setIdleWork(deadlineNanos -> {
            final ActiniumWorldRenderer renderer = ActiniumWorldRenderer.instanceNullable();
            if (renderer != null) {
                renderer.runFrameIdleWork(deadlineNanos);
            }
        });
    }

    /**
     * Distant Horizons owns its own integration (the {@code IIrisAccessor} binding and the deferred LOD
     * toggle); Actinium only installs the shader-side DH render programs that DH then triggers itself.
     */
    private static void initializeDistantHorizonsCompat() {
        if (Iris.enabled && Mods.DISTANTHORIZONS) {
            DHCompat.run();
        }
    }

    private static String dumpExtraPerfStats() {
        String fastLitStats = FastLitItemDisplayListCache.dumpStatsAndReset();
        String shadowStats = AdaptiveShadowBoundsStats.dumpStatsAndReset();
        if (shadowStats.isEmpty()) {
            return fastLitStats;
        }
        if (fastLitStats.isEmpty()) {
            return shadowStats;
        }
        return fastLitStats + " " + shadowStats;
    }

    private static void reloadShaderPipelineForPerfDebug() {
        if (!Iris.enabled || Minecraft.getMinecraft().world == null) {
            return;
        }
        try {
            Iris.reload();
        } catch (IOException | RuntimeException exception) {
            Iris.logger.error("Failed to reload shader pipeline after changing Actinium perf debug", exception);
        }
    }

    @SubscribeEvent
    public void onF3Text(RenderGameOverlayEvent.Text event) {
        if (!Minecraft.getMinecraft().gameSettings.showDebugInfo) {
            return;
        }

        var strings = event.getRight();
        strings.add("");
        strings.add(String.format("%s%s Renderer (%s)", ChatFormatting.AQUA, "Actinium", ActiniumRuntime.version()));

        if (Minecraft.getMinecraft().isReducedDebug()) {
            return;
        }

        var renderer = ActiniumWorldRenderer.instanceNullable();

        if (renderer != null) {
            strings.addAll(renderer.getDebugStrings());
        }

        String kirinoStatus = KirinoCompat.debugStatus();
        if (kirinoStatus != null) {
            strings.add(kirinoStatus);
        }

        for (int i = 0; i < strings.size(); i++) {
            String str = strings.get(i);

            if (str.startsWith("Allocated:")) {
                strings.add(i + 1, getNativeMemoryString());
                break;
            }
        }
    }

    private static String getNativeMemoryString() {
        return "Off-Heap: +" + MathUtil.toMib(getNativeMemoryUsage()) + "MB";
    }

    private static long getNativeMemoryUsage() {
        return ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed() + NativeBuffer.getTotalAllocated();
    }

    public static SodiumGameOptions options() {
        return ActiniumRuntime.options();
    }
}
