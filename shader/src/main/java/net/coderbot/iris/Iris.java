package net.coderbot.iris;

import net.coderbot.iris.debug.IrisDebugOptions;
import com.google.common.base.Throwables;
import dhj.embeddedt.embeddium.api.shader.ShaderProviderHolder;
import com.gtnewhorizons.angelica.Tags;
import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCache;
import com.gtnewhorizons.angelica.glsm.threading.AngelicaWorkers;
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
import net.coderbot.iris.features.FeatureFlags;
import net.coderbot.iris.gbuffer_overrides.matching.InputAvailability;
import net.coderbot.iris.gl.program.RetainedPrograms;
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
import net.coderbot.iris.shaderpack.StringPair;
import net.coderbot.iris.shaderpack.discovery.ShaderpackDirectoryManager;
import net.coderbot.iris.shaderpack.option.OptionSet;
import net.coderbot.iris.shaderpack.option.Profile;
import net.coderbot.iris.shaderpack.option.values.MutableOptionValues;
import net.coderbot.iris.shaderpack.option.values.OptionValues;
import net.coderbot.iris.texture.pbr.PBRTextureManager;
import net.irisshaders.iris.api.v0.IrisApi;
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
import org.jetbrains.annotations.Nullable;
import org.lwjgl.input.Keyboard;
import dhj.embeddedt.embeddium.api.debug.RenderDebugHooksHolder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.ref.SoftReference;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;
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
    /**
     * The fingerprint the current pack was read from, or null when no pack is loaded. It is the
     * identity of a pack configuration: everything a parsed pack depends on - the pack file, its files,
     * the options applied to it and the environment defines it was built with - described in a fixed
     * order, so two packs with equal fingerprints are interchangeable once parsed.
     *
     * <p>Read by {@link #takePreparedPack(String, String)} to decide whether a pack read ahead still
     * describes this selection, and reused by {@link #destroyEverything()} to keep the parsed pack
     * across a reload that changed nothing.</p>
     */
    private static String currentPackState;
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
     * Whether {@link ShaderTransformer#init()} has run. The transform cache does not key on the version
     * hoisting that initialization decides, so a transform that runs before it caches products the
     * initialized transformer would not produce; this is what lets a read-ahead that runs while no pack
     * is loaded hold its transforms back instead of poisoning that cache.
     */
    private static boolean warmupPrepared = false;

    /**
     * The transform warmup of the pack that was just loaded, if one is still running. Completed by
     * {@link #warmupShaderTransforms()}, awaited and cleared by {@link #awaitProgramSetWarmup()}.
     * Package private so the handshake with pipeline creation is testable without a render context.
     */
    static CompletableFuture<Void> programSetWarmup;

    /**
     * A pack read ahead of time, holding the fingerprint it was read from and the invalidation generation
     * it was read under, so a read that no longer describes the selection is never taken.
     */
    record PreparedPack(String settings, ShaderPack pack, long generation) {}

    /** A pack a pre-read produced, with the program set that should be warmed before it is loaded. */
    record PreRead(PreparedPack pack, ProgramSet programs) {}

    /**
     * The pack the config has selected, as a reader that reads it: the name it will be loaded under and
     * the supplier that performs the read. Built on the calling thread, since the environment defines it
     * captures are read from the GL context; the read itself only touches files, so a background thread
     * can run it while the game sits at the main menu.
     *
     * <p>Public because the read-ahead is driven from outside this module: the caller reads the pack on
     * its own thread, then hands the program set to {@link #warmupPreparedTransforms(String, ProgramSet)}.</p>
     *
     * @param name the name the pack will be loaded under
     * @param read the read to perform, returning the program set to warm, or null when nothing was read
     */
    public record SelectedPackReader(String name, Supplier<ProgramSet> read) {}

    /**
     * The pack read ahead of time, if one was read and not taken yet. Softly referenced: a pack nobody got
     * around to loading before memory ran short is worth collecting, and taking one that was collected is a
     * reported miss, not an error.
     */
    private static volatile SoftReference<PreparedPack> preparedPack;
    /**
     * Incremented whenever the selection is invalidated, so a pack read ahead of the invalidation is
     * refused even when its fingerprint still matches: the fingerprint only sees file size and
     * timestamp, and an explicit reload or pack switch has to be authoritative over a stat that cannot
     * tell an edit from a touch.
     */
    private static final AtomicLong preparedPackGeneration = new AtomicLong();
    /** Whether the read-ahead has already run for the current pack selection. */
    private static boolean shaderWarmupClaimed;

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

        @SuppressWarnings("unchecked")
        final Map<String, String> changedConfigs = new HashMap<>(tryReadConfigProperties(shaderPackConfigTxt)
            .map(properties -> (Map<String, String>) (Map<?, ?>) properties).orElse(new HashMap<>()));

        changedConfigs.putAll(shaderPackOptionQueue);
        clearShaderPackOptionQueue();

        if (resetShaderPackOptions) {
            changedConfigs.clear();
        }
        resetShaderPackOptions = false;

        // The environment defines are read from the GL context, so they are captured here on the main
        // thread and reused both for the fingerprint and for the pack read ahead of time.
        final List<StringPair> environmentDefines = new ArrayList<>();
        StandardMacros.createStandardEnvironmentDefines().forEach(environmentDefines::add);

        // Fingerprint the pack as it is on disk right now, then take the pack that was read ahead when it
        // still describes exactly that. Only the fingerprint decides: a prepared pack is immutable once
        // parsed, so a matching fingerprint makes it interchangeable with reading it again.
        final String packState = describeSettings(name, shaderPackRoot, toProperties(changedConfigs), environmentDefines, false);
        final ShaderPack prepared = packState != null ? takePreparedPack(name, packState) : null;

        final ShaderPack pack;

        if (prepared != null) {
            pack = prepared;
        } else {
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

            try {
                pack = new ShaderPack(shaderPackPath, changedConfigs, environmentDefines);
            } catch (Exception e) {
                logger.error("Failed to load the shaderpack \"{}\"!", name);
                logger.error("", e);

                return false;
            }
        }

        // A pack that requires a feature this context cannot provide is not adopted. The decision belongs
        // here, on the main thread, and not in the parser: parsing also runs off the main thread, where
        // reloading the pipeline and touching the config are not allowed - and reloading from inside the
        // constructor closed the very zip file system the pack was still being read from.
        final List<FeatureFlags> missingFeatures = pack.getMissingRequiredFeatures();
        if (!missingFeatures.isEmpty()) {
            final String missing = missingFeatures.stream()
                .map(FeatureFlags::getHumanReadableName)
                .collect(Collectors.joining(", "));
            logger.warn("The shaderpack \"" + name + "\" requires " + missing
                + ", which this system does not support; disabling shaders");

            // The same effect the parser used to have, applied here instead: the switch goes off and the
            // reload it runs rebuilds the pipeline without shaders. Reported as loaded rather than failed,
            // because the pack did switch rendering back to vanilla and the failure branch would tell the
            // player the pack could not be loaded, which is not what happened.
            IrisApi.getInstance().getConfig().setShadersEnabledAndApply(false);

            return true;
        }

        currentPack = pack;
        currentPackState = packState;

        // Programs kept from a destroyed pipeline may only be reused inside one pack configuration, so the
        // pack that just loaded owns the pool from now on. The fingerprint describes the pack file, its
        // files, the options applied to it and the environment defines, so a reload of the same pack keeps
        // its programs and a pack switch drops them.
        RetainedPrograms.useConfiguration(packState);

        try {
            final MutableOptionValues changedConfigsValues = pack.getShaderPackOptions().getOptionValues().mutableCopy();

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
        return findShadersDirectory(zipSystem);
    }

    private static Optional<Path> findShadersDirectory(FileSystem zipSystem) throws IOException {
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
        currentPackState = null;
        // No pack wants saved programs any more; keeping the layer would key later programs under it.
        ProgramBinaryCache.usePack(null);
        fallback = false;
        currentPackName = "(off)";

        logger.info("Shaders are disabled");
    }

    /**
     * {@return whether this caller should read the selected pack ahead of time}
     *
     * <p>True once per selection: reading the pack only has to happen for the title screen shown for that
     * selection. {@link #invalidatePreparedPack()} hands the claim back, so a pack selected later is read
     * too.</p>
     *
     * <p>The claim lives here rather than in the warmup that takes it, because the code that hands it back
     * is part of this module and the warmup is not.</p>
     *
     * <p>Main thread only.</p>
     */
    public static boolean claimShaderWarmup() {
        if (shaderWarmupClaimed) {
            return false;
        }
        shaderWarmupClaimed = true;
        return true;
    }

    /**
     * Drops the pack read ahead of time and lets {@link #claimShaderWarmup()} succeed again: the selection
     * just changed, so what was read describes a pack that is not going to be loaded. Also advances the
     * generation, so a read that is still in flight publishes nothing and a read that already completed
     * cannot be taken.
     *
     * <p>Main thread only.</p>
     */
    public static void invalidatePreparedPack() {
        preparedPack = null;
        shaderWarmupClaimed = false;
        preparedPackGeneration.incrementAndGet();
    }

    /**
     * {@return a reader that reads the selected pack into the read-ahead slot, or null when there is
     * nothing useful to read}
     *
     * <p>Built on the calling thread: the environment defines it captures are read from the GL context.
     * The reader itself only reads files, so a background thread can run it while the game sits at the
     * main menu, and the program set it returns is the one to warm before the pack is really loaded.</p>
     *
     * <p>Public because the main menu hook lives outside this module; reading the pack is shader-side work
     * and stays here.</p>
     */
    @Nullable
    public static SelectedPackReader prepareSelectedShaderPack() {
        if (!renderSystemInitialized || irisConfig == null || currentPack != null) {
            // Nothing worth reading yet, or a pack is already in use: this has to stay silent, since the
            // caller asks on every menu it builds.
            return null;
        }

        final Optional<String> selected = irisConfig.getShaderPackName();
        if (selected.isEmpty()) {
            return null;
        }

        final String packName = selected.get();
        final List<StringPair> environmentDefines = new ArrayList<>();
        StandardMacros.createStandardEnvironmentDefines().forEach(environmentDefines::add);
        final long generation = preparedPackGeneration.get();

        return new SelectedPackReader(packName, () -> {
            final PreRead read = readPreparedPack(packName, environmentDefines, generation);
            if (read == null) {
                return null;
            }
            if (!publishPreparedPack(read.pack())) {
                return null;
            }
            return read.programs();
        });
    }

    /**
     * Publishes a pack that was read ahead of time, unless the selection was invalidated while it was being
     * read: a pack read for a selection that no longer applies must not replace the one read for the
     * selection that does.
     *
     * @return whether the pack was published
     */
    static boolean publishPreparedPack(PreparedPack prepared) {
        final long generation = preparedPackGeneration.get();
        if (prepared.generation() != generation) {
            logger.info("Dropping the shaderpack \"{}\" read ahead of time: it was read for an earlier selection",
                nameOfPreparedPack(prepared));
            return false;
        }

        preparedPack = new SoftReference<>(prepared);
        return true;
    }

    /**
     * Reads a pack into the read-ahead slot: fingerprint, parse, and resolve the program set a world will
     * most likely open with.
     *
     * <p>A zip pack is parsed inside a temporary file system that is closed as soon as this returns. That is
     * safe because {@link ShaderPack} keeps nothing but parsed data: every program source comes from the
     * include graph the constructor read into memory, and every texture is read into a byte array there.</p>
     *
     * @return what was read, or null when the pack cannot be read ahead and the caller should read it the
     *         normal way
     */
    @Nullable
    static PreRead readPreparedPack(String name, List<StringPair> environmentDefines, long generation) {
        try {
            final Path shaderPackRoot = getShaderpacksDirectory().resolve(name);
            final Map<String, String> changedConfigs = readChangedConfigs(getShaderpacksDirectory().resolve(name + ".txt"));
            final String settings = describeSettings(name, shaderPackRoot, toProperties(changedConfigs), environmentDefines, false);
            if (settings == null) {
                // Nothing can be gained here, and the load that follows reports the pack properly, with the
                // message the player expects: a pack that is not there is a normal state, not a failure.
                logger.debug("Not reading shaderpack \"{}\" ahead of time: it could not be described", name);
                return null;
            }

            if (Files.isDirectory(shaderPackRoot)) {
                final Path shadersDirectory = shaderPackRoot.resolve("shaders");
                if (!Files.exists(shadersDirectory)) {
                    // Rejected so the load path reports the missing directory itself instead of adopting a
                    // pack with no programs in it
                    logger.debug("Not reading shaderpack \"{}\" ahead of time: it has no \"shaders\" directory", name);
                    return null;
                }
                return parsePack(settings, generation, shadersDirectory, changedConfigs, environmentDefines);
            }

            try (FileSystem zipSystem = FileSystems.newFileSystem(shaderPackRoot, Iris.class.getClassLoader())) {
                final Optional<Path> shadersDirectory = findShadersDirectory(zipSystem);
                if (shadersDirectory.isEmpty()) {
                    logger.debug("Not reading shaderpack \"{}\" ahead of time: it has no \"shaders\" directory", name);
                    return null;
                }
                return parsePack(settings, generation, shadersDirectory.get(), changedConfigs, environmentDefines);
            }
        } catch (ZipError e) {
            // A zip that was truncated, or is not a zip at all, surfaces as an Error on some runtimes
            logger.warn("The shaderpack \"" + name + "\" appears to be corrupted, so it cannot be read ahead of time", e);
            return null;
        } catch (ZipException e) {
            logger.warn("The shaderpack \"" + name + "\" appears to be corrupted, so it cannot be read ahead of time", e);
            return null;
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not read the shaderpack \"" + name + "\" ahead of time", e);
            return null;
        }
    }

    private static PreRead parsePack(String settings, long generation, Path shadersDirectory,
                                     Map<String, String> changedConfigs, List<StringPair> environmentDefines) throws IOException {
        final ShaderPack pack = new ShaderPack(shadersDirectory, changedConfigs, environmentDefines);
        // The world a player opens after the main menu is the overworld far more often than not, and the
        // dimension is named explicitly rather than read from the last world this client was in: on the
        // menu there is no world, and the last one may well have been the nether.
        //
        // Resolving it writes the pack's access-ordered dimension cache, which is why this happens here,
        // where the pack is still owned by this thread alone: publishing it below is what lets another
        // thread read it, and only after every write to it has finished.
        final ProgramSet programs = pack.getProgramSet(DimensionId.OVERWORLD.getCanonicalId(), DimensionId.OVERWORLD.getNumericId());
        return new PreRead(new PreparedPack(settings, pack, generation), programs);
    }

    /**
     * {@return the pack read ahead of time, or null when it cannot be reused}
     *
     * <p>Only the fingerprint and the generation decide: a changed pack file, option or environment define
     * makes a prepared pack stale, and so does an invalidation, since the fingerprint cannot see an edit
     * that kept the file's size and timestamp. Every rejection is reported with its cause - a pack that is
     * read twice is exactly what the read-ahead exists to avoid, so a load whose log does not say it reused
     * the pack has to say why it could not.</p>
     */
    @Nullable
    static ShaderPack takePreparedPack(String name, @Nullable String packState) {
        final SoftReference<PreparedPack> reference = preparedPack;
        preparedPack = null;
        if (reference == null) {
            logger.debug("Shaderpack \"{}\": nothing was read ahead of this load", name);
            return null;
        }

        final PreparedPack prepared = reference.get();
        if (prepared == null) {
            logger.info("Shaderpack \"{}\": the pack read ahead was reclaimed before this load", name);
            return null;
        }

        if (prepared.generation() != preparedPackGeneration.get()) {
            logger.info("Shaderpack \"{}\": the pack read ahead was invalidated before this load", name);
            return null;
        }

        if (!Objects.equals(prepared.settings(), packState)) {
            logger.info("Shaderpack \"{}\": the pack read ahead no longer matches the selection, {}",
                name, firstDifferingLine(prepared.settings(), packState));
            return null;
        }

        logger.info("Reusing shaderpack \"{}\" read earlier, nothing it was read from has changed", name);
        return prepared.pack();
    }

    /**
     * {@return the line two pack fingerprints first differ in, or a description of which one is missing}
     *
     * <p>A fingerprint lists the pack file, its files, its options and its environment defines in a fixed
     * order, so naming the first line that differs names what made the pack read ahead stale.</p>
     */
    static String firstDifferingLine(@Nullable String read, @Nullable String selected) {
        if (read == null) {
            return "nothing was read ahead";
        }
        if (selected == null) {
            return "the selection could not be described";
        }

        final Iterator<String> readLines = read.lines().iterator();
        final Iterator<String> selectedLines = selected.lines().iterator();
        int line = 1;
        while (readLines.hasNext() && selectedLines.hasNext()) {
            final String left = readLines.next();
            final String right = selectedLines.next();
            if (!left.equals(right)) {
                return "line " + line + ": read=\"" + left + "\" selected=\"" + right + "\"";
            }
            line++;
        }

        return "the fingerprints have different lengths from line " + line;
    }

    /** {@return the fingerprint a pack read ahead of time was described with}, for reporting only. */
    private static String nameOfPreparedPack(PreparedPack prepared) {
        final String settings = prepared.settings();
        final int newline = settings.indexOf('\n');
        return newline < 0 ? settings : settings.substring(0, newline);
    }

    /** {@return the current invalidation generation of the read-ahead slot} */
    static long preparedPackGeneration() {
        return preparedPackGeneration.get();
    }

    /**
     * Starts transforming a pack that was read ahead of time on the worker pool, so the pipeline built for
     * it hits {@link TransformPatcher}'s cache instead of transforming on the frame that opens the world.
     *
     * <p>No pack is current yet, so this only fills the transform cache: publishing anything the pack
     * requires would describe a pack nothing renders with.</p>
     *
     * <p>Public because the caller that reads the pack ahead lives outside this module: it reads the pack on
     * its own thread and hands the resulting program set here. It has to have called {@link
     * #prepareShaderWarmup()} on the main thread first; without it the transforms are refused, because what
     * they would cache is not what the pipeline builds.</p>
     *
     * @param packName the pack the program set belongs to, for the log
     * @param programs the program set to warm, resolved on the calling thread
     */
    public static void warmupPreparedTransforms(String packName, ProgramSet programs) {
        if (!warmupPrepared) {
            logger.warn("[ShaderTransformCache] not warming the transforms of \"{}\": the shader transformer is not initialized yet,"
                + " so the products it would cache are not the ones the pipeline builds", packName);
            return;
        }

        logger.info("[ShaderTransformCache] warmup submitted source=readAheadPack pack={}", packName);
        reportWarmup("readAheadPack", packName,
            ShaderTransformExecutor.submitTracked(() -> DeferredWorldRenderingPipeline.warmupTransforms(programs)));
    }

    private static Map<String, String> readChangedConfigs(Path shaderPackConfigTxt) {
        @SuppressWarnings("unchecked")
        final Map<String, String> changedConfigs = tryReadConfigProperties(shaderPackConfigTxt)
            .map(properties -> (Map<String, String>) (Map<?, ?>) properties)
            .orElse(new HashMap<>());
        return new HashMap<>(changedConfigs);
    }

    private static Properties toProperties(Map<String, String> values) {
        final Properties properties = new Properties();
        properties.putAll(values);
        return properties;
    }

    /**
     * Initializes the state every shader transform depends on, on the calling thread, and does so at most
     * once.
     *
     * <p>{@link ShaderTransformer#init()} decides the GLSL version hoisting a transform applies, from the
     * capabilities glsm cached when the render system initialized. The transform cache does not key on
     * that state, so a transform that runs before it caches products the initialized transformer does not
     * produce - which is why this has to happen before a pack is read ahead of time, while {@link
     * #loadShaderpack()} has not run yet.</p>
     *
     * <p>Public because the main menu read-ahead lives outside this module: it is the step that has to
     * run on the main thread, before the read-ahead submits any transform to the pool. Idempotent, so the
     * pack load that follows calls it again without doing the work twice.</p>
     *
     * <p>Main thread only, and only after {@link #onRenderSystemInit()}.</p>
     */
    public static void prepareShaderWarmup() {
        if (!renderSystemInitialized) {
            throw new IllegalStateException("Shader warmup preparation was requested before RenderSystem initialization completed,"
                + " so the GLSL capabilities it hoists for are not known yet");
        }

        if (warmupPrepared) {
            return;
        }

        // Before anything transforms: the warmup below is only representative of the pipeline when the
        // hoisting state it caches against is already final.
        ShaderTransformer.init();
        warmupPrepared = true;
    }

    /** {@return whether {@link #prepareShaderWarmup()} has run, which gates the transform warmups} */
    static boolean isShaderWarmupPrepared() {
        return warmupPrepared;
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

        // Both of these transform shaders, so the transformer has to be initialized first.
        prepareShaderWarmup();
        ShaderTransformExecutor.warmup();
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
        // A reload is the user asking for the pack as it is on disk now. The fingerprint a read-ahead was
        // taken against only describes size and timestamp, so it cannot tell an edit from a touch: dropping
        // the read-ahead here, at the head of every reload entry point, makes the user's explicit request
        // decisive instead of the stat.
        invalidatePreparedPack();

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

        if (currentPack != null && currentPackState != null) {
            // The pack is still valid, only its programs are being rebuilt: keep the parse so the load that
            // follows does not repeat it. Stamped with the current generation, because this is the pack the
            // game is using, not an older read of it.
            publishPreparedPack(new PreparedPack(currentPackState, currentPack, preparedPackGeneration.get()));
        }

        currentPack = null;
        currentPackState = null;
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

    /** A file's content hash, keyed on the size and timestamp it was taken at so an unchanged file is hashed once. */
    private record HashedFile(long size, long modified, String hash) {}

    /**
     * Content hashes of the files that were described by content, so repeatedly fingerprinting a pack -
     * every option the screen applies, for instance - only reads the files that changed.
     */
    private static final Map<Path, HashedFile> contentHashes = new ConcurrentHashMap<>();

    private static String contentHash(Path file, BasicFileAttributes attributes) throws IOException {
        final long size = attributes.size();
        final long modified = attributes.lastModifiedTime().toMillis();
        final HashedFile known = contentHashes.get(file);
        if (known != null && known.size() == size && known.modified() == modified) {
            return known.hash();
        }

        final String hash;
        try {
            hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }

        contentHashes.put(file, new HashedFile(size, modified, hash));
        return hash;
    }

    /**
     * A folder being described, with the identity used to detect a symlink loop. The key is the platform's
     * file key, or null on platforms that do not expose one, in which case the paths are compared.
     */
    private record VisitedFolder(Path path, @Nullable Object key) {}

    /**
     * {@return every regular file below a folder, keyed by its path relative to the pack root}
     *
     * <p>Subfolders are described in parallel: a pack is thousands of files, and this runs on the render
     * thread when shaders turn on.</p>
     *
     * @param byContent whether to hash file contents instead of trusting size and timestamp
     */
    private static Map<String, String> describeFolder(Path root, VisitedFolder folder, List<VisitedFolder> ancestors, boolean byContent) {
        final Map<String, String> files = new HashMap<>();
        final List<Supplier<Map<String, String>>> subfolders = new ArrayList<>();
        final List<VisitedFolder> lineage = new ArrayList<>(ancestors);
        lineage.add(folder);

        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder.path())) {
            for (Path entry : entries) {
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(entry, BasicFileAttributes.class);
                } catch (IOException e) {
                    // A broken symlink still has to be described: it is part of the pack's state, and
                    // following it would fail the whole fingerprint
                    attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                }

                if (attributes.isDirectory()) {
                    final VisitedFolder subfolder = new VisitedFolder(entry, attributes.fileKey());
                    for (VisitedFolder ancestor : lineage) {
                        final boolean same = subfolder.key() != null && ancestor.key() != null
                            ? subfolder.key().equals(ancestor.key())
                            : Files.isSameFile(ancestor.path(), entry);
                        if (same) {
                            throw new FileSystemLoopException(entry.toString());
                        }
                    }
                    subfolders.add(() -> describeFolder(root, subfolder, lineage, byContent));
                } else if (attributes.isRegularFile()) {
                    files.put(relativePath(root, entry),
                        byContent ? contentHash(entry, attributes) : attributes.size() + "|" + attributes.lastModifiedTime().toMillis());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        for (Map<String, String> nested : AngelicaWorkers.invokeAll(subfolders)) {
            files.putAll(nested);
        }

        return files;
    }

    /**
     * {@return a path relative to the pack root, always separated by forward slashes}
     *
     * <p>The separator is normalized so the same pack fingerprints the same on every platform, which keeps
     * the description readable in a log and makes the comparison a property of the pack rather than of the
     * machine it was read on.</p>
     */
    private static String relativePath(Path root, Path entry) {
        final StringBuilder path = new StringBuilder();
        for (Path element : root.relativize(entry)) {
            if (!path.isEmpty()) {
                path.append('/');
            }
            path.append(element);
        }

        return path.toString();
    }

    /**
     * {@return a fingerprint of everything a loaded pack depends on, or null when the pack cannot be
     * described}
     *
     * <p>{@code byContent} hashes file contents instead of trusting size and timestamp. The fingerprint that
     * gates reusing a parsed pack is taken without it, because it runs on the render thread and a pack is
     * thousands of files; what keeps the stat from being the last word is the generation that
     * {@link #invalidatePreparedPack()} advances.</p>
     *
     * <p>Package private so the fingerprint rules are testable without a render context.</p>
     */
    @Nullable
    static String describeSettings(String packName, Path packRoot, Properties options, Iterable<StringPair> environmentDefines, boolean byContent) {
        final StringBuilder settings = new StringBuilder(packName).append('\n');

        if (Files.isRegularFile(packRoot)) {
            // A zip pack is one file: its size and timestamp describe everything inside it
            try {
                settings.append(Files.size(packRoot)).append('|').append(Files.getLastModifiedTime(packRoot).toMillis());
            } catch (IOException e) {
                logger.warn("Could not describe the shaderpack file \"" + packRoot + "\"", e);
                return null;
            }
        } else if (Files.isDirectory(packRoot)) {
            final TreeMap<String, String> files = new TreeMap<>();
            try {
                final Object rootKey = Files.readAttributes(packRoot, BasicFileAttributes.class).fileKey();
                files.putAll(describeFolder(packRoot, new VisitedFolder(packRoot, rootKey), List.of(), byContent));
            } catch (IOException | UncheckedIOException e) {
                logger.warn("Could not describe the shaderpack folder \"" + packRoot + "\"", e);
                return null;
            }
            files.forEach((file, state) -> settings.append(file).append('|').append(state).append('\n'));
        } else {
            return null;
        }

        // A blank line separates the pack's files from the settings that select what is read out of them
        settings.append('\n');
        new TreeMap<>(options).forEach((key, value) -> settings.append(key).append('=').append(value).append('\n'));
        for (StringPair define : environmentDefines) {
            settings.append('#').append(define.getKey()).append(' ').append(define.getValue()).append('\n');
        }

        return settings.toString();
    }


    /**
     * Creates a pipeline for a canonical dimension key.
     * Program selection supports exact dimension.properties mappings and wildcard fallback.
     */
    private static WorldRenderingPipeline createPipeline(String dimensionName) {
        // Read the transforms of the pack just warmed instead of transforming on the frame that opens
        // the world. A warmup that already ran only means the joins below are cheap.
        //
        // Only this pack's warmup is joined, not the one a pack read ahead of time started: both submit the
        // same transforms, so waiting for the older one would put an unbounded wait on the main thread at
        // the exact moment this exists to shorten, and a transform is a pure function of its inputs, so a
        // duplicate that still runs only costs the CPU it had left to run anyway.
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
        } finally {
            // The pipeline that was just built is the current batch of kept programs; the one before it can
            // no longer be taken, so the pool retires it.
            RetainedPrograms.afterPipelineBuilt();
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
