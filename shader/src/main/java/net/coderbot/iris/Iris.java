package net.coderbot.iris;

import net.coderbot.iris.debug.IrisDebugOptions;
import com.google.common.base.Throwables;
import dhj.embeddedt.embeddium.api.shader.ShaderProviderHolder;
import com.gtnewhorizons.angelica.Tags;
import com.gtnewhorizons.angelica.glsm.GlslTransformUtils;
import com.gtnewhorizons.angelica.glsm.debug.GLSMPerfDebug;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
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
import net.coderbot.iris.texture.pbr.PBRResourceIndex;
import net.coderbot.iris.texture.pbr.PBRTextureManager;
import net.coderbot.iris.uniforms.PerFrameUniformBlockHarvester;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
    /**
     * The transform warmup that is running for the pack just loaded, or null once every waiter has
     * joined it. Pipeline creation joins it before reading any transform result.
     */
    static CompletableFuture<Void> programSetWarmup;
    @Getter
    private static String currentPackName;
    /**
     * The pack fingerprint that was compiled in full for the saved-program cache, or null when saving
     * is off. Described by content, since only content changes invalidate a compiled program.
     */
    private static String currentPackSettings;
    /** The pack fingerprint a prepared pack was read with, used to validate reusing it. */
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

        /**
         * Marks the pool's own threads, so a task can tell whether it is already running a
         * transformation without being sent back to the pool.
         */
        private static final class Worker extends ForkJoinWorkerThread {
            Worker(ForkJoinPool pool) {
                super(pool);
                setDaemon(true);
            }
        }

        /** {@return whether the calling thread runs this executor's transformations} */
        public static boolean isOnWorker() {
            return Thread.currentThread() instanceof Worker;
        }

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
                        Worker t = new Worker(pool);
                        t.setName("Shader-Transform-" + t.getPoolIndex());
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
         *
         * <p>Deliberately does not wait: the caller is loading a pack, and a transformation that
         * arrives before this one finishes only queues behind it.</p>
         */
        public static void warmup() {
            final String vertexShader = "#version 120\nvoid main() { gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex; }";
            final String fragmentShader = "#version 120\nvoid main() { gl_FragColor = vec4(1.0); }";
            submitTracked(() -> {
                TransformPatcher.patchComposite(vertexShader, null, fragmentShader);
                TransformPatcher.patchAttributes(vertexShader, null, fragmentShader, new InputAvailability(true, true));
            }).exceptionally(e -> {
                logger.warn("Warmup failed", e);
                return null;
            });
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

        final Map<String, String> changedConfigs = readChangedConfigs(shaderPackConfigTxt);

        changedConfigs.putAll(shaderPackOptionQueue);
        clearShaderPackOptionQueue();

        if (resetShaderPackOptions) {
            changedConfigs.clear();
        }
        resetShaderPackOptions = false;

        final long loadStartNanos = System.nanoTime();
        final long parseNanos;
        final long activateNanos;
        final long fingerprintNanos;
        final boolean reused;
        try {
            final Iterable<StringPair> environmentDefines = StandardMacros.createStandardEnvironmentDefines();
            final long parseStartNanos = System.nanoTime();
            final String packState = describeSettings(name, shaderPackRoot, toProperties(changedConfigs), environmentDefines, false);
            final ShaderPack prepared = takePreparedPack(name, packState);
            reused = prepared != null;
            currentPack = reused ? prepared : new ShaderPack(shaderPackPath, changedConfigs, environmentDefines);
            parseNanos = System.nanoTime() - parseStartNanos;
            currentPackState = packState;
            RetainedPrograms.useConfiguration(packState);
            final long activateStartNanos = System.nanoTime();
            currentPack.activate();
            activateNanos = System.nanoTime() - activateStartNanos;

            final long fingerprintStartNanos = System.nanoTime();
            final MutableOptionValues changedConfigsValues = currentPack.getShaderPackOptions().getOptionValues().mutableCopy();

            // Store changed values from those currently in use by the shader pack
            final Properties configsToSave = new Properties();
            changedConfigsValues.getBooleanValues().forEach((k, v) -> configsToSave.setProperty(k, Boolean.toString(v)));
            changedConfigsValues.getStringValues().forEach(configsToSave::setProperty);

            tryUpdateConfigPropertiesFile(shaderPackConfigTxt, configsToSave);
            currentPackSettings = irisConfig.shouldSaveCompiledShaders()
                ? describeSettings(name, shaderPackRoot, configsToSave, environmentDefines, true) : null;
            fingerprintNanos = System.nanoTime() - fingerprintStartNanos;
        } catch (Exception e) {
            logger.error("Failed to load the shaderpack \"{}\"!", name);
            logger.error("", e);

            return false;
        }

        fallback = false;
        currentPackName = name;
        ProgramBinaryCache.usePack(name);

        logger.info("Using shaderpack: " + name);

        final long compileStartNanos = System.nanoTime();
        compileEveryShaderFolder();
        final long compileNanos = System.nanoTime() - compileStartNanos;

        final long warmupStartNanos = System.nanoTime();
        warmupShaderTransforms();
        final long warmupNanos = System.nanoTime() - warmupStartNanos;

        logLoadSummary(name, loadStartNanos, parseNanos, reused, activateNanos, fingerprintNanos, compileNanos, warmupNanos);

        return true;
    }

    /**
     * Reports where a shaderpack load spent its time, and the state of the two lookups the load's
     * optimisations removed work from: the reserved-word rename pass the transforms run, and the PBR
     * resource index the atlas stitch consults.
     */
    private static void logLoadSummary(String name, long loadStartNanos, long parseNanos, boolean reused,
                                       long activateNanos, long fingerprintNanos, long compileNanos, long warmupNanos) {
        final StringBuilder sb = new StringBuilder(256);
        sb.append("Shaderpack load: pack=").append(name)
            .append(" totalMs=").append(millis(System.nanoTime() - loadStartNanos))
            .append(" parseMs=").append(millis(parseNanos))
            .append(" reused=").append(reused)
            .append(" activateMs=").append(millis(activateNanos))
            .append(" fingerprintMs=").append(millis(fingerprintNanos))
            .append(" compileMs=").append(millis(compileNanos))
            .append(" warmupSubmitMs=").append(millis(warmupNanos));
        appendSegment(sb, GLSMPerfDebug.isEnabled() ? GlslTransformUtils.renameStatsSummary() : "");
        appendSegment(sb, PBRResourceIndex.statsSummary());
        logger.info(sb.toString());
    }

    private static void appendSegment(StringBuilder sb, String segment) {
        if (segment != null && !segment.isEmpty()) {
            sb.append(' ').append(segment);
        }
    }

    private static String millis(long nanos) {
        return String.format("%.3f", nanos / 1_000_000.0);
    }

    /**
     * Starts transforming the loaded pack's shaders on the worker pool, so the pipeline built next
     * hits {@link TransformPatcher}'s cache instead of transforming on the frame that opens the
     * world. The uniform blocks the SDL backend declares are harvested from the same program set.
     *
     * <p>Also arms {@link GLSMHooks#uniformBlockBarrier}: the SDL shader backend reads the harvested
     * blocks off the main thread, and it has to wait for this warmup (which owns them) to finish
     * before it does.</p>
     */
    public static void warmupShaderTransforms() {
        final ShaderPack pack = currentPack;
        if (pack == null) return;
        try {
            final String dimensionName = lastDimensionName != null ? lastDimensionName : DimensionId.OVERWORLD.getCanonicalId();
            GLSMHooks.uniformBlockBarrier = Iris::awaitProgramSetWarmup;
            logger.info("[ShaderTransformCache] warmup submitted source=loadedPack dimension={}", dimensionName);
            programSetWarmup = ShaderTransformExecutor.submitTracked(() -> {
                final ProgramSet programs = pack.getProgramSet(dimensionName);
                PerFrameUniformBlockHarvester.harvest(programs);
                DeferredWorldRenderingPipeline.warmupTransforms(programs);
            }).whenComplete((ignored, failure) -> logWarmupCompletion("loadedPack", failure));
        } catch (Throwable t) {
            logger.warn("Early shader transform warmup failed; transforms will run at pipeline creation", t);
        }
    }

    /**
     * Reports how a transform warmup ended, so the periodic {@code transform[...]} segment showing no
     * misses at pipeline creation can be traced back to a warmup that really did run to completion.
     */
    private static void logWarmupCompletion(String source, @Nullable Throwable failure) {
        if (failure != null) {
            logger.warn("[ShaderTransformCache] warmup failed source=" + source
                + "; transforms will run at pipeline creation", failure);
            return;
        }
        logger.info("[ShaderTransformCache] warmup done source={} cacheSize={}", source, TransformPatcher.cacheSize());
    }

    /**
     * Warms the transforms of a pack that was read ahead of time, so the pipeline built for it hits
     * {@link TransformPatcher}'s cache instead of transforming on the frame that opens the world.
     *
     * <p>No pack is current yet, so unlike {@link #warmupShaderTransforms()} this only fills the
     * transform cache: the harvested uniform blocks describe the pack the pipeline compiles for, and
     * publishing them before that pipeline exists would describe a pack nothing renders with.</p>
     */
    public static void warmupPreparedTransforms(ProgramSet programs) {
        logger.info("Warming the transforms of the shaderpack read ahead of time");
        ShaderTransformExecutor.submitTracked(() -> DeferredWorldRenderingPipeline.warmupTransforms(programs))
            .whenComplete((ignored, failure) -> logWarmupCompletion("readAheadPack", failure));
    }

    /**
     * Waits for the running transform warmup, if any. A warmup failure is logged and swallowed: the
     * transforms it was going to cache run again at pipeline creation, which is correct, just slower.
     */
    static void awaitProgramSetWarmup() {
        final CompletableFuture<Void> warmup = programSetWarmup;
        if (warmup == null) return;
        programSetWarmup = null;
        try {
            warmup.join();
        } catch (RuntimeException e) {
            logger.warn("Early shader transform warmup failed; transforms will run at pipeline creation", e);
        }
    }

    /** A pack read ahead of time, kept alongside the fingerprint it was read with. */
    private record PreparedPack(String settings, ShaderPack pack) {}

    /** A pack that a pre-read produced, together with the program set to warm before it is loaded. */
    private record PreRead(PreparedPack pack, ProgramSet programs) {}

    private static volatile SoftReference<PreparedPack> preparedPack;

    /**
     * {@return a reader that reads the selected pack into {@link #preparedPack}, or null when there is
     * nothing useful to read}
     *
     * <p>Built on the calling thread: the environment defines it captures are read from the GL context.
     * The reader itself only reads files, so a background thread can run it while the game sits at the
     * main menu, and the program set it returns is the one to warm before the pack is really loaded.</p>
     */
    @Nullable
    public static Supplier<ProgramSet> prepareSelectedShaderPack() {
        if (irisConfig == null || currentPack != null) return null;
        final Optional<String> name = irisConfig.getShaderPackName();
        if (name.isEmpty()) return null;
        final List<StringPair> environmentDefines = new ArrayList<>();
        StandardMacros.createStandardEnvironmentDefines().forEach(environmentDefines::add);
        return () -> {
            try {
                final PreRead read = readPreparedPack(name.get(), environmentDefines);
                if (read == null) return null;
                preparedPack = new SoftReference<>(read.pack());
                return read.programs();
            } catch (Exception e) {
                logger.debug("Could not read shaderpack \"{}\" ahead of time", name.get(), e);
                return null;
            }
        };
    }

    @Nullable
    private static PreRead readPreparedPack(String name, List<StringPair> environmentDefines) throws IOException {
        final Path shaderPackRoot = getShaderpacksDirectory().resolve(name);
        final Map<String, String> changedConfigs = readChangedConfigs(getShaderpacksDirectory().resolve(name + ".txt"));
        final String settings = describeSettings(name, shaderPackRoot, toProperties(changedConfigs), environmentDefines, false);
        if (settings == null) return null;

        if (Files.isDirectory(shaderPackRoot)) {
            return parsePack(settings, shaderPackRoot.resolve("shaders"), changedConfigs, environmentDefines);
        }
        try (FileSystem zipSystem = FileSystems.newFileSystem(shaderPackRoot, Iris.class.getClassLoader())) {
            final Optional<Path> shadersDirectory = findShadersDirectory(zipSystem);
            if (shadersDirectory.isEmpty()) return null;
            return parsePack(settings, shadersDirectory.get(), changedConfigs, environmentDefines);
        }
    }

    private static PreRead parsePack(String settings, Path shadersDirectory, Map<String, String> changedConfigs, List<StringPair> environmentDefines) throws IOException {
        final ShaderPack pack = new ShaderPack(shadersDirectory, changedConfigs, environmentDefines);
        // Most worlds open in the overworld; other dimensions build their program sets on demand as usual.
        return new PreRead(new PreparedPack(settings, pack), pack.getProgramSet("Overworld"));
    }

    /**
     * {@return the pack read ahead of time, or null when it cannot be reused}
     *
     * <p>Only the fingerprint decides: a changed pack file, option, or environment define makes the
     * prepared pack stale, and the caller reads the pack normally.</p>
     */
    @Nullable
    private static ShaderPack takePreparedPack(String name, @Nullable String packState) {
        final SoftReference<PreparedPack> reference = preparedPack;
        preparedPack = null;
        final PreparedPack prepared = reference != null ? reference.get() : null;
        if (prepared == null || !prepared.settings().equals(packState)) return null;
        logger.info("Reusing shaderpack \"{}\" read earlier, nothing it was read from has changed", name);
        return prepared.pack();
    }

    private static Map<String, String> readChangedConfigs(Path shaderPackConfigTxt) {
        @SuppressWarnings("unchecked")
        final Map<String, String> changedConfigs = tryReadConfigProperties(shaderPackConfigTxt).map(properties -> (Map<String, String>) (Map<?, ?>) properties)
            .orElse(new HashMap<>());
        return new HashMap<>(changedConfigs);
    }

    private static Properties toProperties(Map<String, String> values) {
        final Properties properties = new Properties();
        properties.putAll(values);
        return properties;
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
        currentPackSettings = null;
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
        awaitProgramSetWarmup();
        if (currentPack != null && currentPackState != null) {
            // The pack itself is still valid: only its programs are being rebuilt
            preparedPack = new SoftReference<>(new PreparedPack(currentPackState, currentPack));
        }
        currentPack = null;
        currentPackState = null;
        currentPackSettings = null;
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
     * Compiles the shaders of every dimension folder of the current pack, so the saved-program cache
     * covers all of them. Skipped when the pack fingerprint was already compiled in full.
     *
     * <p>Programs are built through a throwaway pipeline per folder: nothing here is rendered with, and
     * a folder that fails to compile only costs that folder's programs. Progress is logged rather than
     * shown: the pack loads on the first world frame, where drawing the vanilla loading screen would
     * swap buffers in the middle of the frame.</p>
     */
    private static void compileEveryShaderFolder() {
        if (currentPack == null) return;
        if (!irisConfig.shouldSaveCompiledShaders()) {
            logger.info("Program binary cache: not compiling every shader folder, saving compiled shaders is off");
            return;
        }
        if (currentPackSettings != null && ProgramBinaryCache.wasCompiledFor(currentPackSettings)) {
            logger.info("Program binary cache: every folder of this pack was compiled in a previous run, nothing to compile");
            return;
        }
        awaitProgramSetWarmup();

        final Map<String, ProgramSet> programSets = currentPack.getEveryProgramSet();
        boolean everyFolderCompiled = true;
        boolean loopFinished = false;
        ProgramBinaryCache.beginFullCompile();
        try {
            int i = 0;
            for (Map.Entry<String, ProgramSet> entry : programSets.entrySet()) {
                final String folder = entry.getKey() != null ? entry.getKey() : "(base shaders)";
                i++;
                final ProgramSet programs = entry.getValue();
                try {
                    shaderPackLoadId++;
                    logger.info("[Load #{}] Compiling shader folder {}/{}: {}", shaderPackLoadId, i, programSets.size(), folder);
                    ShaderTransformExecutor.prepare();
                    PerFrameUniformBlockHarvester.harvest(programs);
                    final DeferredWorldRenderingPipeline pipeline = new DeferredWorldRenderingPipeline(programs);
                    try {
                        // Built lazily on the first draw otherwise, after this pipeline is gone
                        if (ShaderProviderHolder.getProvider() instanceof IrisCeleritasShaderProvider terrain) {
                            terrain.compileTerrainPrograms(pipeline.getCeleritasTerrainPipeline());
                        }
                    } finally {
                        getPipelineManager().discardPipeline(pipeline);
                    }
                } catch (Exception e) {
                    everyFolderCompiled = false;
                    logger.error("Failed to compile shader folder '{}' for saving", folder, e);
                } finally {
                    RetainedPrograms.afterPipelineBuilt();
                }
            }
            loopFinished = true;
        } finally {
            ProgramBinaryCache.finishFullCompile(loopFinished && everyFolderCompiled ? currentPackSettings : null);
            PerFrameUniformBlockHarvester.clear();
            // The batch that fills the cache for this pack is over: report what it restored, compiled and
            // saved, and what the restores saved in compile time.
            logger.info("Program binary cache: folders={} complete={} {}",
                programSets.size(), loopFinished && everyFolderCompiled, ProgramBinaryCache.dumpEventSummary());
        }
    }

    private record HashedFile(long size, long modified, String hash) {}

    private static final Map<Path, HashedFile> contentHashes = new ConcurrentHashMap<>();

    private static String contentHash(Path file, BasicFileAttributes attributes) throws IOException {
        final long size = attributes.size();
        final long modified = attributes.lastModifiedTime().toMillis();
        final HashedFile known = contentHashes.get(file);
        if (known != null && known.size() == size && known.modified() == modified) return known.hash();
        final String hash;
        try {
            hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        contentHashes.put(file, new HashedFile(size, modified, hash));
        return hash;
    }

    private record VisitedFolder(Path path, @Nullable Object key) {}

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
                    attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                }
                if (attributes.isDirectory()) {
                    final VisitedFolder subfolder = new VisitedFolder(entry, attributes.fileKey());
                    for (VisitedFolder ancestor : lineage) {
                        final boolean same = subfolder.key() != null && ancestor.key() != null
                            ? subfolder.key().equals(ancestor.key())
                            : Files.isSameFile(ancestor.path(), entry);
                        if (same) throw new FileSystemLoopException(entry.toString());
                    }
                    subfolders.add(() -> describeFolder(root, subfolder, lineage, byContent));
                } else if (attributes.isRegularFile()) {
                    files.put(root.relativize(entry).toString(),
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
     * {@return a fingerprint of everything a loaded pack depends on, or null when the pack cannot be
     * read}
     *
     * <p>{@code byContent} hashes file contents instead of trusting size and timestamp: the fingerprint
     * that gates the saved programs must not change when a file was merely touched.</p>
     */
    @Nullable
    static String describeSettings(String packName, Path packRoot, Properties options, Iterable<StringPair> environmentDefines, boolean byContent) {
        final StringBuilder settings = new StringBuilder(packName).append('\n');
        if (Files.isRegularFile(packRoot)) {
            try {
                settings.append(Files.size(packRoot)).append('|').append(Files.getLastModifiedTime(packRoot).toMillis());
            } catch (IOException e) {
                return null;
            }
        } else if (Files.isDirectory(packRoot)) {
            final TreeMap<String, String> files = new TreeMap<>();
            try {
                // One stat per file; this runs on the render thread when shaders turn on, so folders are read in parallel
                final Object rootKey = Files.readAttributes(packRoot, BasicFileAttributes.class).fileKey();
                files.putAll(describeFolder(packRoot, new VisitedFolder(packRoot, rootKey), List.of(), byContent));
            } catch (IOException | UncheckedIOException e) {
                return null;
            }
            files.forEach((file, state) -> settings.append(file).append('|').append(state).append('\n'));
        } else {
            return null;
        }
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
        awaitProgramSetWarmup();
        if (currentPack == null) {
            // Completely disables shader-based rendering
            PerFrameUniformBlockHarvester.clear();
            return new FixedFunctionWorldRenderingPipeline();
        }

        final ProgramSet programs = currentPack.getProgramSet(dimensionName);

        try {
            shaderPackLoadId++;
            ShaderTransformExecutor.prepare();
            PerFrameUniformBlockHarvester.harvest(programs);
            long startTime = System.nanoTime();
            final WorldRenderingPipeline pipeline;
            try {
                pipeline = new DeferredWorldRenderingPipeline(programs);
            } finally {
                // Programs the pipeline did not take back are never taken again
                RetainedPrograms.afterPipelineBuilt();
            }
            long endTime = System.nanoTime();
            logger.info("[Load #{}] Total shaderpack load time for '{}' in dimension '{}': {} ms", shaderPackLoadId, currentPackName, dimensionName, String.format("%.1f", (endTime - startTime) / 1_000_000.0));
            return pipeline;
        } catch (Exception e) {
            logger.error("Failed to create shader rendering pipeline, disabling shaders!", e);
            // TODO: This should be reverted if a dimension change causes shaders to compile again
            fallback = true;
            notifyPlayer(I18n.format("iris.shaders.pipelineFailed", Throwables.getRootCause(e).getMessage()));
            PerFrameUniformBlockHarvester.clear();

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
