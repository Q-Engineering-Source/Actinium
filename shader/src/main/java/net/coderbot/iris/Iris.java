package net.coderbot.iris;

import net.coderbot.iris.debug.IrisDebugOptions;
import com.google.common.base.Throwables;
import dhj.embeddedt.embeddium.api.shader.ShaderProviderHolder;
import com.gtnewhorizons.angelica.Tags;
import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCache;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import lombok.Getter;
import net.coderbot.iris.block_rendering.BlockRenderingSettings;
import net.coderbot.iris.celeritas.buffer.ShaderMaterialOverrideState;
import net.coderbot.iris.celeritas.debug.IrisRenderDebugHooks;
import net.coderbot.iris.compat.dh.DHCompat;
import net.coderbot.iris.config.IrisConfig;
import net.coderbot.iris.celeritas.IrisCeleritasShaderProvider;
import net.coderbot.iris.client.IrisDebugScreenHandler;
import net.coderbot.iris.gbuffer_overrides.matching.InputAvailability;
import net.coderbot.iris.gl.shader.StandardMacros;
import net.coderbot.iris.gui.screen.ShaderPackScreen;
import net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline;
import net.coderbot.iris.pipeline.FixedFunctionWorldRenderingPipeline;
import net.coderbot.iris.pipeline.PipelineManager;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import net.coderbot.iris.pipeline.transform.ShaderTransformer;
import net.coderbot.iris.pipeline.transform.TransformPatcher;
import net.coderbot.iris.shaderpack.DimensionId;
import net.coderbot.iris.shaderpack.OptionalBoolean;
import net.coderbot.iris.shaderpack.ProgramSet;
import net.coderbot.iris.shaderpack.ShaderPack;
import net.coderbot.iris.shaderpack.discovery.ShaderpackDirectoryManager;
import net.coderbot.iris.shaderpack.option.OptionSet;
import net.coderbot.iris.shaderpack.option.Profile;
import net.coderbot.iris.shaderpack.option.values.MutableOptionValues;
import net.coderbot.iris.shaderpack.option.values.OptionValues;
import net.coderbot.iris.texture.pbr.PBRTextureManager;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.input.Keyboard;
import dhj.embeddedt.embeddium.api.debug.RenderDebugHooksHolder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.ZipError;
import java.util.zip.ZipException;

public class Iris {
    public final boolean isDevelopmentEnvironment;

    /**
     * The user-facing name of the mod. Moved into a constant to facilitate easy branding changes (for forks). You'll still need to change this separately in
     * mixin plugin classes & the language files.
     */
    public static final String MODNAME = "ActiniumShaders";

    public static final IrisLogging logger = new IrisLogging(MODNAME);

    // Cached at class load - config must be loaded before Iris. Do not change at runtime.
    public static final boolean enabled = IrisDebugOptions.enableIris();

    private static Path shaderpacksDirectory;
    private static ShaderpackDirectoryManager shaderpacksDirectoryManager;

    private static ShaderPack currentPack;
    @Getter
    private static String currentPackName;
    @Getter
    private static boolean initialized;
    @Getter
    private static int shaderPackLoadId = 0;

    private static PipelineManager pipelineManager;
    @Getter
    private static IrisConfig irisConfig;
    private static FileSystem zipFileSystem;

    @Getter
    private static final Map<String, String> shaderPackOptionQueue = new HashMap<>();
    // Flag variable used when reloading
    // Used in favor of queueDefaultShaderPackOptionValues() for resetting as the
    // behavior is more concrete and therefore is more likely to repair a user's issues
    private static boolean resetShaderPackOptions = false;
    private static boolean loadShaderPackWhenPossible = false;
    private static boolean renderSystemInitialized = false;
    private static boolean runtimeGlInitialized = false;

    /**
     * The transform warmup of the pack that was just loaded, if one is still running. Completed by
     * {@link #warmupShaderTransforms()}, awaited and cleared by {@link #awaitProgramSetWarmup()}.
     * Package private so the handshake with pipeline creation is testable without a render context.
     */
    static CompletableFuture<Void> programSetWarmup;

    private static String IRIS_VERSION;
    @Getter
    private static boolean fallback;

    public static boolean loadedIncompatiblePack() {
        return DHCompat.lastPackIncompatible();
    }

    public static void loadShaderpackWhenPossible() {
        loadShaderPackWhenPossible = true;
    }

    public static void tryLoadShaderpackWhenPossible() {
        if (loadShaderPackWhenPossible) {
            if (!isWorldReadyForShaderpackLoad()) {
                return;
            }
            loadShaderPackWhenPossible = false;
            try {
                reload();
            } catch (IOException e) {
                logger.error("Error during deferred shader reload", e);
            }
        }
    }

    /**
     * Lazy executor for parallelizing shader transformations during shader pack loading.
     * Creates threads on demand and shuts down after a period of inactivity.
     */
    public static final class ShaderTransformExecutor {
        private static final long IDLE_TIMEOUT_SECONDS = 120;
        private static final int THREAD_COUNT = Math.max(2, Math.min(12, Runtime.getRuntime().availableProcessors() / 2));

        private static final Object lock = new Object();
        private static ExecutorService executor;
        private static ScheduledExecutorService scheduler;
        private static volatile long lastActivityTime;
        private static final AtomicInteger inFlight = new AtomicInteger(0);
        private static boolean idleCheckScheduled;

        private ShaderTransformExecutor() {}

        private static void noteActivity() {
            lastActivityTime = System.nanoTime();
        }

        public static ExecutorService get() {
            synchronized (lock) {
                noteActivity();

                if (executor != null && !executor.isShutdown()) {
                    return executor;
                }

                executor = new ForkJoinPool(
                    THREAD_COUNT,
                    pool -> {
                        ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
                        t.setName("Shader-Transform-" + t.getPoolIndex());
                        t.setDaemon(true);
                        return t;
                    },
                    null,  // UncaughtExceptionHandler
                    true   // asyncMode - FIFO, better for independent tasks
                );
                logger.debug("Created shader transform executor with " + THREAD_COUNT + " threads");

                if (scheduler == null || scheduler.isShutdown()) {
                    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "Shader-Transform-Scheduler");
                        t.setDaemon(true);
                        return t;
                    });
                }
                if (!idleCheckScheduled) {
                    idleCheckScheduled = true;
                    scheduleIdleCheck(IDLE_TIMEOUT_SECONDS);
                }

                return executor;
            }
        }

        private static void scheduleIdleCheck(long delaySeconds) {
            try {
                scheduler.schedule(ShaderTransformExecutor::checkIdleShutdown, delaySeconds, TimeUnit.SECONDS);
            } catch (Exception e) {
                // If scheduling fails, reset the flag so a future get() can try again
                idleCheckScheduled = false;
                logger.warn("Failed to schedule idle check", e);
            }
        }

        /**
         * Ensures the executor exists and begins spinning up worker threads.
         * Intended for UI entrypoints (eg. shader settings screen open).
         */
        public static void prepare() {
            // Submit empty tasks to warm up all worker threads without blocking the UI thread.
            for (int i = 0; i < THREAD_COUNT; i++) {
                submitTracked(() -> { });
            }
        }

        /**
         * Warm up the threadpool by running a representative shader transformation.
         */
        public static void warmup() {
            final String vertexShader = "#version 120\nvoid main() { gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex; }";
            final String fragmentShader = "#version 120\nvoid main() { gl_FragColor = vec4(1.0); }";
            try {
                submitTracked(() -> {
                    TransformPatcher.patchComposite(vertexShader, null, fragmentShader);
                    TransformPatcher.patchAttributes(vertexShader, null, fragmentShader, new InputAvailability(true, true));
                }).get();
            } catch (Exception e) {
                logger.warn("Warmup failed", e);
            }
        }

        public static <T> CompletableFuture<T> submitTracked(Supplier<T> supplier) {
            Objects.requireNonNull(supplier);
            noteActivity();
            inFlight.incrementAndGet();
            try {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        return supplier.get();
                    } finally {
                        noteActivity();
                        inFlight.decrementAndGet();
                    }
                }, get());
            } catch (Exception e) {
                inFlight.decrementAndGet();
                throw e;
            }
        }

        public static CompletableFuture<Void> submitTracked(Runnable runnable) {
            Objects.requireNonNull(runnable);
            noteActivity();
            inFlight.incrementAndGet();
            try {
                return CompletableFuture.runAsync(() -> {
                    try {
                        runnable.run();
                    } finally {
                        noteActivity();
                        inFlight.decrementAndGet();
                    }
                }, get());
            } catch (Exception e) {
                inFlight.decrementAndGet();
                throw e;
            }
        }

        private static void checkIdleShutdown() {
            synchronized (lock) {
                final ExecutorService current = executor;
                if (current == null || current.isShutdown()) {
                    // Executor already gone, shut down scheduler
                    if (scheduler != null && !scheduler.isShutdown()) {
                        scheduler.shutdown();
                        scheduler = null;
                    }
                    idleCheckScheduled = false;
                    return;
                }

                final long idleNanos = System.nanoTime() - lastActivityTime;
                final long idleSeconds = TimeUnit.NANOSECONDS.toSeconds(idleNanos);

                if (idleSeconds >= IDLE_TIMEOUT_SECONDS && inFlight.get() == 0) {
                    logger.debug("Shutting down idle shader transform executor after " + idleSeconds + " seconds");
                    current.shutdown();
                    executor = null;
                    scheduler.shutdown();
                    scheduler = null;
                    idleCheckScheduled = false;

                    // Clear transformation caches - no longer needed after loading
                    //
                    // This also discards what warmupShaderTransforms() cached, so a warmup whose
                    // results are still wanted has to be consumed well within IDLE_TIMEOUT_SECONDS.
                    // An installed jar recovers those results from the transform disk layer, which
                    // keys them on content; a classes-directory deployment has no disk layer, so it
                    // transforms again on the frame that opens the world. Not worked around here: a
                    // pool kept alive for a warmup that may never be consumed costs more than the
                    // transforms it would save.
                    TransformPatcher.clearCache();
                } else {
                    // Still active (or in-flight), schedule another check.
                    final long remainingSeconds = Math.max(1, IDLE_TIMEOUT_SECONDS - idleSeconds + 1);
                    scheduleIdleCheck(remainingSeconds);
                }
            }
        }
    }

    private static KeyBinding reloadKeybind;
    private static KeyBinding toggleShadersKeybind;
    private static KeyBinding shaderpackScreenKeybind;
    private static KeyBinding wireframeKeybind;

    public static Iris INSTANCE = new Iris();

    private Iris() {
        // Guard against null blackboard in test environments
        final Object deobfEnv = Launch.blackboard != null ? Launch.blackboard.get("fml.deobfuscatedEnvironment") : null;
        isDevelopmentEnvironment = deobfEnv != null && (boolean) deobfEnv;
    }

    @SubscribeEvent
    public void onKeypress(InputEvent.KeyInputEvent event) {
        if (reloadKeybind.isPressed()) {
            final Minecraft mc = Minecraft.getMinecraft();
            try {
                reload();
                if (mc.player != null) mc.player.sendMessage(new TextComponentString("Shaders Reloaded!"));

            } catch (Exception e) {
                logger.error("Error while reloading Shaders for Iris!", e);
                if (mc.player != null) mc.player.sendMessage(new TextComponentString( "Failed tgo reload shaders! Reason: " + Throwables.getRootCause(e).getMessage()));
            }
        } else if (toggleShadersKeybind.isPressed()) {
            final Minecraft mc = Minecraft.getMinecraft();
            try {
                toggleShaders(mc, !irisConfig.areShadersEnabled());
            } catch (Exception e) {
                logger.error("Error while toggling shaders!", e);

                if (mc.player != null) mc.player.sendMessage(new TextComponentString( "Failed tgo toggle shaders! Reason: " + Throwables.getRootCause(e).getMessage()));
                setShadersDisabled();
                fallback = true;
            }
        } else if (shaderpackScreenKeybind.isPressed()) {
            final Minecraft mc = Minecraft.getMinecraft();
            mc.displayGuiScreen(new ShaderPackScreen(null));
        } else if (wireframeKeybind.isPressed()) {
            final Minecraft mc = Minecraft.getMinecraft();
            if (irisConfig.areDebugOptionsEnabled() && mc.player != null && !mc.isSingleplayer()) {
                mc.player.sendMessage(new TextComponentString(I18n.format("iris.wireframe.singleplayer")));
            }
        }

    }

    public static boolean shouldActivateWireframe() {
        return irisConfig != null
                && irisConfig.areDebugOptionsEnabled()
                && wireframeKeybind != null
                && wireframeKeybind.isKeyDown();
    }

    @SubscribeEvent
    public void keyUp(InputEvent.KeyInputEvent event) {
        final int key = Keyboard.getEventKey();
        final boolean released = !Keyboard.getEventKeyState();
        if (Minecraft.getMinecraft().gameSettings.showDebugInfo && GuiScreen.isShiftKeyDown() && GuiScreen.isCtrlKeyDown() && released) {
            if (key == Keyboard.KEY_N) {
                IrisDebugOptions.cycleAnimationsMode();
            }
        }
    }

    /**
     * Called very early on in Minecraft initialization. At this point we *cannot* safely access OpenGL, but we can do some very basic setup, config loading,
     * and environment checks.
     *
     * <p>This is roughly equivalent to Fabric Loader's ClientModInitializer#onInitializeClient entrypoint, except
     * it's entirely cross platform & we get to decide its exact semantics.</p>
     *
     * <p>This is called right before options are loaded, so we can add key bindings here.</p>
     */
    public void onEarlyInitialize() {
        RenderDebugHooksHolder.setHooks(IrisRenderDebugHooks.INSTANCE);
        DHCompat.run();
        try {
            if (!Files.exists(getShaderpacksDirectory())) {
                Files.createDirectories(getShaderpacksDirectory());
            }
        } catch (IOException e) {
            logger.warn("Failed to create the shaderpacks directory!");
            logger.warn("", e);
        }

        irisConfig = new IrisConfig(Minecraft.getMinecraft().gameDir.toPath().resolve("config").resolve("shaders.properties"));

        try {
            irisConfig.initialize();
        } catch (IOException e) {
            logger.error("Failed to initialize Actinium configuration, default values will be used instead");
            logger.error("", e);
        }


        initialized = true;
    }

    /**
     * Called once RenderSystem#initRenderer has completed. This means that we can safely access OpenGL.
     */
    public static void onRenderSystemInit() {
        if (!initialized) {
            Iris.logger.warn("Iris::onRenderSystemInit was called, but Iris::onEarlyInitialize was not called."
                + " Trying to avoid a crash but this is an odd state.");
            return;
        }
        renderSystemInitialized = true;

        boolean isDHLoaded = DHCompat.isDistantHorizonsLoaded();

        // Defer shaderpack loading until the first loading-complete stage so the window, splash,
        // and default framebuffer are fully settled before heavy shader initialization starts.
        if (isDHLoaded) {
            loadShaderPackWhenPossible = true;
        }
    }

    /**
     * Called when the title screen is initialized for the first time.
     */
    public static void onLoadingComplete() {
        if (!initialized) {
            Iris.logger.warn("Iris::onLoadingComplete was called, but Iris::onEarlyInitialize was not called."
                + " Trying to avoid a crash but this is an odd state.");
            return;
        }
        loadShaderPackWhenPossible = true;
        logger.info("Deferring shaderpack load until world rendering begins");
    }

    public static void toggleShaders(Minecraft minecraft, boolean enabled) throws IOException {
        irisConfig.setShadersEnabled(enabled);
        irisConfig.save();

        reload();
        if (minecraft.player != null) {
            minecraft.player.sendMessage(new TextComponentString(enabled ? I18n.format("iris.shaders.toggled", currentPackName) : I18n.format("iris.shaders.disabled")));
        }
    }

    public static void loadShaderpack() {
        // NB: Loading the pack itself only parses files and reads GL capability strings, so it is
        // safe outside of a world (e.g. the shader pack screen on the main menu). Only pipeline
        // creation requires an active world, and that is gated separately in reload().
        // We still defer until the render system has been initialized, since loading the pack
        // reads GL capabilities for the standard environment defines.
        if (!renderSystemInitialized) {
            loadShaderPackWhenPossible = true;
            logger.info("Deferring shaderpack load because the render system is not initialized yet");
            return;
        }

        ensureRuntimeGlInitialized();

        if (irisConfig == null) {
            if (!initialized) {
                throw new IllegalStateException("Iris::loadShaderpack was called, but Iris::onInitializeClient wasn't" + " called yet. How did this happen?");
            } else {
                throw new NullPointerException("Iris.irisConfig was null unexpectedly");
            }
        }

        if (!irisConfig.areShadersEnabled()) {
            logger.info("Shaders are disabled because enableShaders is set to false in shaders.properties");

            setShadersDisabled();

            return;
        }

        // Attempt to load an external shaderpack if it is available
        final Optional<String> externalName = irisConfig.getShaderPackName();

        if (externalName.isEmpty()) {
            logger.info("Shaders are disabled because no valid shaderpack is selected");
            notifyPlayer(I18n.format("iris.shaders.noPackSelected"));
            setShadersDisabled();
            return;
        }

        if (!loadExternalShaderpack(externalName.get())) {
            logger.warn("Falling back to normal rendering without shaders because the shaderpack could not be loaded");
            notifyPlayer(I18n.format("iris.shaders.loadFailed", externalName.get()));
            setShadersDisabled();
            fallback = true;
        }
    }

    private static boolean loadExternalShaderpack(String name) {
        final Path shaderPackRoot;
        final Path shaderPackConfigTxt;

        try {
            shaderPackRoot = getShaderpacksDirectory().resolve(name);
            shaderPackConfigTxt = getShaderpacksDirectory().resolve(name + ".txt");
        } catch (InvalidPathException e) {
            logger.error("Failed to load the shaderpack \"{}\" because it contains invalid characters in its path", name);

            return false;
        }

        final Path shaderPackPath;

        if (shaderPackRoot.toString().endsWith(".zip")) {
            final Optional<Path> optionalPath;

            try {
                optionalPath = loadExternalZipShaderpack(shaderPackRoot);
            } catch (FileSystemNotFoundException | NoSuchFileException e) {
                logger.error("Failed to load the shaderpack \"{}\" because it does not exist in your shaderpacks folder!", name);

                return false;
            } catch (ZipException e) {
                logger.error("The shaderpack \"{}\" appears to be corrupted, please try downloading it again!", name);

                return false;
            } catch (IOException e) {
                logger.error("Failed to load the shaderpack \"{}\"!", name);
                logger.error("", e);

                return false;
            }

            if (optionalPath.isPresent()) {
                shaderPackPath = optionalPath.get();
            } else {
                logger.error("Could not load the shaderpack \"{}\" because it appears to lack a \"shaders\" directory", name);

                if (zipFileSystem != null) {
                    try {
                        zipFileSystem.close();
                    } catch (IOException e) {
                        logger.error("Failed to close zip file system?", e);
                    }
                    zipFileSystem = null;
                }

                return false;
            }
        } else {
            if (!Files.exists(shaderPackRoot)) {
                logger.error("Failed to load the shaderpack \"{}\" because it does not exist!", name);
                return false;
            }

            // If it's a folder-based shaderpack, just use the shaders subdirectory
            shaderPackPath = shaderPackRoot.resolve("shaders");
        }

        if (!Files.exists(shaderPackPath)) {
            logger.error("Could not load the shaderpack \"{}\" because it appears to lack a \"shaders\" directory", name);
            return false;
        }

        @SuppressWarnings("unchecked")
        final Map<String, String> changedConfigs = tryReadConfigProperties(shaderPackConfigTxt).map(properties -> (Map<String, String>) (Map<?, ?>) properties)
            .orElse(new HashMap<>());

        changedConfigs.putAll(shaderPackOptionQueue);
        clearShaderPackOptionQueue();

        if (resetShaderPackOptions) {
            changedConfigs.clear();
        }
        resetShaderPackOptions = false;

        try {
            currentPack = new ShaderPack(shaderPackPath, changedConfigs, StandardMacros.createStandardEnvironmentDefines());

            final MutableOptionValues changedConfigsValues = currentPack.getShaderPackOptions().getOptionValues().mutableCopy();

            // Store changed values from those currently in use by the shader pack
            final Properties configsToSave = new Properties();
            changedConfigsValues.getBooleanValues().forEach((k, v) -> configsToSave.setProperty(k, Boolean.toString(v)));
            changedConfigsValues.getStringValues().forEach(configsToSave::setProperty);

            tryUpdateConfigPropertiesFile(shaderPackConfigTxt, configsToSave);
        } catch (Exception e) {
            logger.error("Failed to load the shaderpack \"{}\"!", name);
            logger.error("", e);

            return false;
        }

        fallback = false;
        currentPackName = name;
        ProgramBinaryCache.usePack(name);

        logger.info("Using shaderpack: " + name);

        // Start the transforms the pipeline for the current dimension will need while the world is
        // still loading, instead of on the frame that opens it.
        warmupShaderTransforms();

        return true;
    }

    private static Optional<Path> loadExternalZipShaderpack(Path shaderpackPath) throws IOException {
        final FileSystem zipSystem = FileSystems.newFileSystem(shaderpackPath, Iris.class.getClassLoader());
        zipFileSystem = zipSystem;

        // Should only be one root directory for a zip shaderpack
        final Path root = zipSystem.getRootDirectories().iterator().next();

        final Path potentialShaderDir = zipSystem.getPath("shaders");

        // If the shaders dir was immediately found return it
        // Otherwise, manually search through each directory path until it ends with "shaders"
        if (Files.exists(potentialShaderDir)) {
            return Optional.of(potentialShaderDir);
        }

        // Sometimes shaderpacks have their shaders directory within another folder in the shaderpack
        // For example Sildurs-Vibrant-Shaders.zip/shaders
        // While other packs have Trippy-Shaderpack-master.zip/Trippy-Shaderpack-master/shaders
        // This makes it hard to determine what is the actual shaders dir
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isDirectory).filter(path -> path.endsWith("shaders")).findFirst();
        }
    }

    private static void setShadersDisabled() {
        currentPack = null;
        // No pack wants saved programs any more; keeping the layer would key later programs under it.
        ProgramBinaryCache.usePack(null);
        fallback = false;
        currentPackName = "(off)";

        logger.info("Shaders are disabled");
    }

    private static void ensureRuntimeGlInitialized() {
        if (runtimeGlInitialized) {
            return;
        }

        if (!renderSystemInitialized) {
            throw new IllegalStateException("Iris runtime GL initialization was requested before RenderSystem initialization completed");
        }

        if (IrisDebugOptions.enableCeleritas()) {
            ShaderProviderHolder.setProvider(new IrisCeleritasShaderProvider());
        }

        ShaderTransformExecutor.warmup();
        ShaderTransformer.init();
        PBRTextureManager.INSTANCE.init();
        runtimeGlInitialized = true;
    }

    /**
     * Starts transforming the loaded pack's shaders on the worker pool, so the pipeline built next
     * hits {@link TransformPatcher}'s cache instead of transforming on the frame that opens the world.
     *
     * <p>The program set is resolved here, on the calling thread, and only the transforms are handed
     * to the pool: resolving it writes to the pack's access-ordered dimension map, which must not be
     * written from the pool while the pipeline of the current dimension is being built. The transforms
     * themselves read immutable program sources and the shared transform cache, so they are safe to
     * run off-thread and across a reload.</p>
     *
     * <p>Requires {@link ShaderTransformer#init()} to have run, since it decides the GLSL hoisting the
     * transforms cache their results against and the transform cache does not key on that state:
     * transforming first would cache products the initialized transformer does not produce. {@link
     * #loadShaderpack()} initializes it before loading the pack, so this only has to gate, not
     * initialize.</p>
     *
     * <p>The warmup is an optional optimization, so a failure is logged and dropped instead of being
     * propagated: the transforms it was going to cache run again at pipeline creation, which is
     * correct, just slower.</p>
     */
    static void warmupShaderTransforms() {
        final ShaderPack pack = currentPack;
        if (pack == null) {
            return;
        }

        if (!runtimeGlInitialized) {
            logger.debug("Skipping the shader transform warmup because the runtime GL state is not initialized yet");
            return;
        }

        final String packName = currentPackName;
        final String dimensionName = lastDimensionName != null ? lastDimensionName : DimensionId.OVERWORLD.getCanonicalId();
        try {
            final ProgramSet programs = pack.getProgramSet(dimensionName);
            logger.info("[ShaderTransformCache] warmup submitted source=loadedPack pack={} dimension={}", packName, dimensionName);
            programSetWarmup = reportWarmup("loadedPack", packName,
                ShaderTransformExecutor.submitTracked(() -> DeferredWorldRenderingPipeline.warmupTransforms(programs)));
        } catch (RuntimeException e) {
            logger.warn("Early shader transform warmup failed; transforms will run at pipeline creation", e);
        }
    }

    /**
     * Reports how a transform warmup ended, once the transforms it submitted have finished: the cache
     * size at submission time says nothing, because none of the submitted work has run yet. A warmup
     * that submits but never fills the cache is worth a report, since the pipeline built next then
     * transforms on the frame that opens the world.
     *
     * @param submitted the future of the warmup's submission step, carrying the transforms it submitted
     * @return a future that completes once every submitted transform has finished, and exceptionally if
     *         the submission or any of the transforms failed
     */
    private static CompletableFuture<Void> reportWarmup(String source, String packName,
                                                        CompletableFuture<List<CompletableFuture<?>>> submitted) {
        return submitted
            .thenCompose(transforms -> CompletableFuture
                .allOf(transforms.toArray(new CompletableFuture<?>[0]))
                .thenRun(() -> logger.info("[ShaderTransformCache] warmup done source={} pack={} submitted={} cacheSizeAfter={}",
                    source, packName, transforms.size(), TransformPatcher.cacheSize())))
            .whenComplete((ignored, failure) -> {
                if (failure != null) {
                    logger.warn("Early shader transform warmup failed; transforms will run at pipeline creation", failure);
                }
            });
    }

    /**
     * Waits for the transform warmup that is still running, if any, and clears it so it is only
     * awaited once. A failed warmup is logged and swallowed: the transforms it was going to cache run
     * again at pipeline creation, which is correct, just slower, and a warmup must never take the game
     * down with it.
     */
    static void awaitProgramSetWarmup() {
        final CompletableFuture<Void> warmup = programSetWarmup;
        if (warmup == null) {
            return;
        }
        // Cleared before joining, so a warmup that already ran is never joined twice and a late
        // failure of it cannot be read as the next pack's.
        programSetWarmup = null;
        try {
            warmup.join();
        } catch (CompletionException | CancellationException e) {
            logger.warn("Early shader transform warmup failed; transforms will run at pipeline creation", e);
        }
    }

    private static Optional<Properties> tryReadConfigProperties(Path path) {
        final Properties properties = new Properties();

        if (Files.exists(path)) {
            try (InputStream is = Files.newInputStream(path)) {
                // NB: config properties are specified to be encoded with ISO-8859-1 by OptiFine,
                //     so we don't need to do the UTF-8 workaround here.
                properties.load(is);
            } catch (IOException e) {
                // TODO: Better error handling
                return Optional.empty();
            }
        }

        return Optional.of(properties);
    }

    private static void tryUpdateConfigPropertiesFile(Path path, Properties properties) {
        try {
            if (properties.isEmpty()) {
                // Delete the file or don't create it if there are no changed configs
                if (Files.exists(path)) {
                    Files.delete(path);
                }

                return;
            }

            try (OutputStream out = Files.newOutputStream(path)) {
                properties.store(out, null);
            }
        } catch (IOException e) {
            // TODO: Better error handling
        }
    }

    public static boolean isValidShaderpack(Path pack) {
        if (Files.isDirectory(pack)) {
            // Sometimes the shaderpack directory itself can be
            // identified as a shader pack due to it containing
            // folders which contain "shaders" folders, this is
            // necessary to check against that
            if (pack.equals(getShaderpacksDirectory())) {
                return false;
            }
            try (Stream<Path> stream = Files.walk(pack)) {
                return stream.filter(Files::isDirectory)
                    // Prevent a pack simply named "shaders" from being
                    // identified as a valid pack
                    .filter(path -> !path.equals(pack)).anyMatch(path -> path.endsWith("shaders"));
            } catch (IOException e) {
                // Not a valid pack, but never fail silently: this is what makes a
                // configured pack vanish from the selection list.
                logger.warn("Failed to inspect potential shaderpack folder \"{}\", it will be skipped", pack, e);
            }
        }

        if (pack.toString().endsWith(".zip")) {
            try (FileSystem zipSystem = FileSystems.newFileSystem(pack, Iris.class.getClassLoader())) {
                final Path root = zipSystem.getRootDirectories().iterator().next();
                try (Stream<Path> stream = Files.walk(root)) {
                    return stream.filter(Files::isDirectory).anyMatch(path -> path.endsWith("shaders"));
                }
            } catch (ZipError zipError) {
                // Java 8 seems to throw a ZipError instead of a subclass of IOException
                Iris.logger.warn("The ZIP at " + pack + " is corrupt");
            } catch (IOException e) {
                // Same here: a transient read failure (file lock, sync client) hides
                // the pack from the list, which then silently drops apply requests.
                Iris.logger.warn("Failed to inspect potential shaderpack zip \"{}\", it will be skipped", pack, e);
            }
        }

        return false;
    }

    public static void queueShaderPackOptionsFromProfile(Profile profile) {
        getShaderPackOptionQueue().putAll(profile.optionValues);
    }

    public static void queueShaderPackOptionsFromProperties(Properties properties) {
        queueDefaultShaderPackOptionValues();

        properties.stringPropertyNames().forEach(key -> getShaderPackOptionQueue().put(key, properties.getProperty(key)));
    }

    // Used in favor of resetShaderPackOptions as the aforementioned requires the pack to be reloaded
    public static void queueDefaultShaderPackOptionValues() {
        clearShaderPackOptionQueue();

        getCurrentPack().ifPresent(pack -> {
            final OptionSet options = pack.getShaderPackOptions().getOptionSet();
            final OptionValues values = pack.getShaderPackOptions().getOptionValues();

            options.getStringOptions().forEach((key, mOpt) -> {
                if (values.getStringValue(key).isPresent()) {
                    getShaderPackOptionQueue().put(key, mOpt.getOption().getDefaultValue());
                }
            });
            options.getBooleanOptions().forEach((key, mOpt) -> {
                if (values.getBooleanValue(key) != OptionalBoolean.DEFAULT) {
                    getShaderPackOptionQueue().put(key, Boolean.toString(mOpt.getOption().getDefaultValue()));
                }
            });
        });
    }

    public static void clearShaderPackOptionQueue() {
        getShaderPackOptionQueue().clear();
    }

    public static void resetShaderPackOptionsOnNextReload() {
        resetShaderPackOptions = true;
    }

    public static boolean shouldResetShaderPackOptionsOnNextReload() {
        return resetShaderPackOptions;
    }

    public static void reload() throws IOException {
        // allows shaderpacks to be changed at runtime
        irisConfig.initialize();

        // Destroy all allocated resources
        destroyEverything();

        // Load the new shaderpack
        loadShaderpack();

        // Very important - we need to re-create the pipeline straight away.
        // https://github.com/IrisShaders/Iris/issues/1330
        if (Minecraft.getMinecraft().world != null) {
            Iris.getPipelineManager().preparePipeline(Iris.getCurrentDimensionName());

            BlockRenderingSettings.INSTANCE.reloadRendererIfRequired();
        }

        if (loadedIncompatiblePack() && Minecraft.getMinecraft().player != null) {
            Iris.logger.warn("Incompatible pack for DH!");
            Minecraft.getMinecraft().player.sendMessage(new TextComponentString(TextFormatting.BOLD.toString() + TextFormatting.RED + "This pack doesn't have DH support."));
            Minecraft.getMinecraft().player.sendMessage(new TextComponentString(TextFormatting.RED + "Distant Horizons (DH) chunks won't show up. This isn't a bug, get another shader."));
        }
    }

    /**
     * Destroys and deallocates all created OpenGL resources. Useful as part of a reload.
     */
    private static void destroyEverything() {
        // The warmup of the pack being dropped may still be filling the transform cache. Let it end
        // here, before the pipeline and the zip file system go, so nothing of this pack's is still
        // running when the next one is loaded.
        awaitProgramSetWarmup();

        currentPack = null;
        // Everything this pack saved belongs to a pack that is no longer loaded.
        ProgramBinaryCache.usePack(null);

        getPipelineManager().destroyPipeline();

        // Close the zip filesystem that the shaderpack was loaded from
        //
        // This prevents a FileSystemAlreadyExistsException when reloading shaderpacks.
        if (zipFileSystem != null) {
            try {
                zipFileSystem.close();
            } catch (NoSuchFileException e) {
                logger.warn("Failed to close the shaderpack zip when reloading because it was deleted, proceeding anyways.");
            } catch (IOException e) {
                logger.error("Failed to close zip file system?", e);
            }
        }
    }

    public static String lastDimensionName = null;
    public static int lastDimensionId = 0;

    /**
     * Gets the stable shader-pack dimension key for the current world.
     * Vanilla provider aliases are normalized to namespaced identifiers; missing names fall back to numeric identity.
     * Falls back to lastDimensionName when no world is loaded.
     */
    public static String getCurrentDimensionName() {
        final WorldClient level = Minecraft.getMinecraft().world;

        if (level != null && level.provider != null) {
            String dimensionName = level.provider.getDimensionType().getName();
            if (dimensionName == null || dimensionName.isBlank()) {
                logger.warn("WorldProvider dimension name was null or blank for dimension ID {}; using its numeric identity", level.provider.getDimension());
            }
            lastDimensionId = level.provider.getDimension();
            lastDimensionName = DimensionId.canonicalize(dimensionName, lastDimensionId);
            return lastDimensionName;
        } else {
            // This prevents us from reloading the shaderpack unless we need to. Otherwise, if the player is in
            // another dimension and quits the game, we might end up reloading the shaders on exit and on entry to the level
            // because the code thinks that the dimension changed.
            return lastDimensionName != null ? lastDimensionName : DimensionId.OVERWORLD.getCanonicalId();
        }
    }

    /**
     * Gets the current dimension ID.
     */
    public static int getCurrentDimensionId() {
        final WorldClient level = Minecraft.getMinecraft().world;
        if (level != null && level.provider != null) {
            return level.provider.getDimension();
        }
        return lastDimensionId;
    }

    public static boolean isWorldReadyForShaderpackLoad() {
        final Minecraft minecraft = Minecraft.getMinecraft();
        return minecraft != null && minecraft.world != null && minecraft.world.provider != null;
    }


    /**
     * Creates a pipeline for a canonical dimension key.
     * Program selection supports exact dimension.properties mappings and wildcard fallback.
     */
    private static WorldRenderingPipeline createPipeline(String dimensionName) {
        // Read the transforms of the pack just warmed instead of transforming on the frame that opens
        // the world. A warmup that already ran only means the joins below are cheap.
        awaitProgramSetWarmup();

        if (currentPack == null) {
            // Completely disables shader-based rendering
            return new FixedFunctionWorldRenderingPipeline();
        }

        final ProgramSet programs = currentPack.getProgramSet(dimensionName);

        try {
            shaderPackLoadId++;
            long startTime = System.nanoTime();
            WorldRenderingPipeline pipeline = new DeferredWorldRenderingPipeline(programs);
            long endTime = System.nanoTime();
            logger.info("[Load #{}] Total shaderpack load time for '{}' in dimension '{}': {} ms", shaderPackLoadId, currentPackName, dimensionName, String.format("%.1f", (endTime - startTime) / 1_000_000.0));
            return pipeline;
        } catch (Exception e) {
            logger.error("Failed to create shader rendering pipeline, disabling shaders!", e);
            // TODO: This should be reverted if a dimension change causes shaders to compile again
            fallback = true;
            notifyPlayer(I18n.format("iris.shaders.pipelineFailed", Throwables.getRootCause(e).getMessage()));

            return new FixedFunctionWorldRenderingPipeline();
        }
    }

    /**
     * Sends a user-facing message to the in-game chat when a player is present.
     * Shader loading failures must never be silent: the enable switch stays on in the
     * config even when rendering fell back to vanilla, so without this the user has no
     * way to tell why the pack did not load.
     */
    private static void notifyPlayer(String message) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.player != null) {
            mc.player.sendMessage(new TextComponentString(message));
        }
    }

    @NotNull
    public static PipelineManager getPipelineManager() {
        if (pipelineManager == null) {
            pipelineManager = new PipelineManager(Iris::createPipeline);
        }

        return pipelineManager;
    }

    @NotNull
    public static Optional<ShaderPack> getCurrentPack() {
        return Optional.ofNullable(currentPack);
    }

    public static String getVersion() {
        if (IRIS_VERSION == null) {
            return "Version info unknown!";
        }

        return IRIS_VERSION;
    }

    public static String getFormattedVersion() {
        final TextFormatting color;
        String version = getVersion();

        if (version.endsWith("-development-environment")) {
            color = TextFormatting.GOLD;
            version = version.replace("-development-environment", " (Development Environment)");
        } else if (version.endsWith("-dirty") || version.contains("unknown") || version.endsWith("-nogit")) {
            color = TextFormatting.RED;
        } else if (version.contains("+rev.")) {
            color = TextFormatting.LIGHT_PURPLE;
        } else {
            color = TextFormatting.GREEN;
        }

        return color + version;
    }

    public static Path getShaderpacksDirectory() {
        if (shaderpacksDirectory == null) {
            shaderpacksDirectory = Minecraft.getMinecraft().gameDir.toPath().resolve("shaderpacks");
        }

        return shaderpacksDirectory;
    }

    public static ShaderpackDirectoryManager getShaderpacksDirectoryManager() {
        if (shaderpacksDirectoryManager == null) {
            shaderpacksDirectoryManager = new ShaderpackDirectoryManager(getShaderpacksDirectory());
        }

        return shaderpacksDirectoryManager;
    }

    public void fmlInitEvent() {
        IRIS_VERSION = Tags.VERSION;
        String keybindCategory = I18n.format("key.category.iris.keybinds");
        reloadKeybind = new KeyBinding(I18n.format("iris.keybind.reload"), Keyboard.KEY_R, keybindCategory);
        toggleShadersKeybind = new KeyBinding(I18n.format("iris.keybind.toggleShaders"), Keyboard.KEY_K, keybindCategory);
        shaderpackScreenKeybind = new KeyBinding(I18n.format("iris.keybind.shaderPackSelection"), Keyboard.KEY_O, keybindCategory);
        wireframeKeybind = new KeyBinding(I18n.format("iris.keybind.wireframe"), 0, keybindCategory);

        ClientRegistry.registerKeyBinding(reloadKeybind);
        ClientRegistry.registerKeyBinding(toggleShadersKeybind);
        ClientRegistry.registerKeyBinding(shaderpackScreenKeybind);
        ClientRegistry.registerKeyBinding(wireframeKeybind);
        MinecraftForge.EVENT_BUS.register(IrisDebugScreenHandler.INSTANCE);
    }

    public static void setShaderMaterialOverride(Block block, int meta) {
        if (!enabled)
            return;

        final Reference2ObjectMap<Block, Int2IntMap> blockMetaMatches = BlockRenderingSettings.INSTANCE.getBlockMetaMatches();
        if (blockMetaMatches == null)
            return;

        final int blockId = BlockRenderingSettings.INSTANCE.getBlockStateId(block, meta);

        ShaderMaterialOverrideState.setBlockId(blockId);

    }

    public static void resetShaderMaterialOverride() {
        if (!enabled)
            return;
        ShaderMaterialOverrideState.clear();
    }
}
