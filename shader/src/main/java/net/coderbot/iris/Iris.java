package net.coderbot.iris;

import net.coderbot.iris.debug.IrisDebugOptions;
import com.google.common.base.Throwables;
import dhj.embeddedt.embeddium.api.shader.ShaderProvider;
import dhj.embeddedt.embeddium.api.shader.ShaderProviderHolder;
import com.gtnewhorizons.angelica.Tags;
import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCache;
import com.gtnewhorizons.angelica.glsm.shader.ShaderDiskCache;
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
import net.coderbot.iris.gl.shader.StandardMacros;
import net.coderbot.iris.gl.program.RetainedPrograms;
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
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.input.Keyboard;
import dhj.embeddedt.embeddium.api.debug.RenderDebugHooksHolder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.ref.SoftReference;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
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
    /** Fingerprint used to reuse the parsed pack even when the program cache is disabled. */
    private static String currentPackState;
    /** Fingerprint of the loaded pack, its options and the environment defines; null while the cache is off. */
    private static String currentPackSettings;

    private static PipelineManager pipelineManager;
    @Getter
    private static IrisConfig irisConfig;
    private static FileSystem zipFileSystem;

    @Getter
    private static final Map<String, String> shaderPackOptionQueue = new HashMap<>();
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
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            PrecompileQueue.tick();
        }
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

        try {
            final Iterable<StringPair> environmentDefines = StandardMacros.createStandardEnvironmentDefines();
            final String packState = describeSettings(name, shaderPackRoot, toProperties(changedConfigs), environmentDefines, false);
            final PreparedPack prepared = takePreparedPack(name, packState);
            currentPack = prepared != null ? prepared.pack() : new ShaderPack(shaderPackPath, changedConfigs, environmentDefines);
            currentPackState = packState;
            RetainedPrograms.useConfiguration(packState);

            final MutableOptionValues changedConfigsValues = currentPack.getShaderPackOptions().getOptionValues().mutableCopy();

            // Store changed values from those currently in use by the shader pack
            final Properties configsToSave = new Properties();
            changedConfigsValues.getBooleanValues().forEach((k, v) -> configsToSave.setProperty(k, Boolean.toString(v)));
            changedConfigsValues.getStringValues().forEach(configsToSave::setProperty);

            tryUpdateConfigPropertiesFile(shaderPackConfigTxt, configsToSave);

            currentPackSettings = irisConfig.shouldSaveCompiledShaders()
                ? describeSettings(name, shaderPackRoot, configsToSave, environmentDefines, false)
                : null;
        } catch (Exception e) {
            logger.error("Failed to load the shaderpack \"{}\"!", name);
            logger.error("", e);

            return false;
        }

        fallback = false;
        currentPackName = name;
        ProgramBinaryCache.usePack(name);

        logger.info("Using shaderpack: " + name);

        return true;
    }

    private record PreparedPack(String settings, ShaderPack pack, @Nullable FileSystem fileSystem) {}

    private static volatile SoftReference<PreparedPack> preparedPack;

    @Nullable
    public static Runnable prepareSelectedShaderPack() {
        if (irisConfig == null || !irisConfig.areShadersEnabled() || currentPack != null) return null;
        final Optional<String> name = irisConfig.getShaderPackName();
        if (name.isEmpty()) return null;
        final List<StringPair> environmentDefines = new ArrayList<>();
        StandardMacros.createStandardEnvironmentDefines().forEach(environmentDefines::add);
        return () -> {
            try {
                final PreparedPack prepared = readPreparedPack(name.get(), environmentDefines);
                if (prepared != null) preparedPack = new SoftReference<>(prepared);
            } catch (Exception e) {
                logger.debug("Could not read shaderpack \"{}\" ahead of time", name.get(), e);
            }
        };
    }

    @Nullable
    private static PreparedPack readPreparedPack(String name, List<StringPair> environmentDefines) throws IOException {
        final Path shaderPackRoot = getShaderpacksDirectory().resolve(name);
        final Map<String, String> changedConfigs = readChangedConfigs(getShaderpacksDirectory().resolve(name + ".txt"));
        final String settings = describeSettings(name, shaderPackRoot, toProperties(changedConfigs), environmentDefines, false);
        if (settings == null) return null;

        if (Files.isDirectory(shaderPackRoot)) {
            return new PreparedPack(settings, parsePack(shaderPackRoot.resolve("shaders"), changedConfigs, environmentDefines), null);
        }

        FileSystem fileSystem = null;
        try {
            fileSystem = FileSystems.newFileSystem(shaderPackRoot, Iris.class.getClassLoader());
            final Optional<Path> shadersDirectory = findShadersDirectory(fileSystem);
            if (shadersDirectory.isEmpty()) {
                closeQuietly(fileSystem);
                return null;
            }
            final ShaderPack pack = parsePack(shadersDirectory.get(), changedConfigs, environmentDefines);
            return new PreparedPack(settings, pack, fileSystem);
        } catch (IOException | RuntimeException | Error e) {
            closeQuietly(fileSystem);
            throw e;
        }
    }

    private static ShaderPack parsePack(Path shadersDirectory, Map<String, String> changedConfigs,
                                        List<StringPair> environmentDefines) throws IOException {
        final ShaderPack pack = new ShaderPack(shadersDirectory, changedConfigs, environmentDefines);
        pack.getProgramSet("Overworld");
        return pack;
    }

    @Nullable
    private static PreparedPack takePreparedPack(String name, @Nullable String packState) {
        final SoftReference<PreparedPack> reference = preparedPack;
        preparedPack = null;
        final PreparedPack prepared = reference != null ? reference.get() : null;
        if (prepared == null) return null;
        if (!prepared.settings().equals(packState)) {
            if (prepared.fileSystem() != null && prepared.fileSystem() != zipFileSystem) {
                closeQuietly(prepared.fileSystem());
            }
            return null;
        }
        logger.info("Reusing shaderpack \"{}\" read earlier, nothing it was read from has changed", name);
        return prepared;
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

    private static Optional<Path> loadExternalZipShaderpack(Path shaderpackPath) throws IOException {
        FileSystem zipSystem;
        try {
            zipSystem = FileSystems.newFileSystem(shaderpackPath, Iris.class.getClassLoader());
        } catch (FileSystemAlreadyExistsException e) {
            zipSystem = FileSystems.getFileSystem(URI.create("jar:" + shaderpackPath.toUri()));
        }
        zipFileSystem = zipSystem;
        return findShadersDirectory(zipSystem);
    }

    private static Optional<Path> findShadersDirectory(FileSystem zipSystem) throws IOException {
        final Path root = zipSystem.getRootDirectories().iterator().next();
        final Path potentialShaderDir = zipSystem.getPath("shaders");
        if (Files.exists(potentialShaderDir)) {
            return Optional.of(potentialShaderDir);
        }

        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isDirectory).filter(path -> path.endsWith("shaders")).findFirst();
        }
    }

    private static void setShadersDisabled() {
        currentPack = null;
        currentPackState = null;
        currentPackSettings = null;
        fallback = false;
        ProgramBinaryCache.usePack(null);
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

    /** Restores the active world pipeline after throwaway precompile pipelines changed global render settings. */
    public static void restoreAfterPrecompile() {
        final Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.world == null) return;
        try {
            reload();
        } catch (IOException e) {
            logger.error("Failed to restore the active shader pipeline after precompiling", e);
        }
    }

    public static void reload() throws IOException {
        // allows shaderpacks to be changed at runtime
        irisConfig.initialize();

        destroyEverything();

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
        // The running precompile queue belongs to the pack being replaced: abandon it before the pack layer
        // and the cached pack previews it compiles against are cleared.
        PrecompileQueue.abandon();
        final boolean preservePack = currentPack != null && currentPackState != null;
        if (preservePack) {
            preparedPack = new SoftReference<>(new PreparedPack(currentPackState, currentPack, zipFileSystem));
        }
        currentPack = null;
        currentPackState = null;
        currentPackSettings = null;
        closePreviews();
        ProgramBinaryCache.usePack(null);

        getPipelineManager().destroyPipeline();

        if (zipFileSystem != null) {
            if (!preservePack) {
                try {
                    zipFileSystem.close();
                } catch (NoSuchFileException e) {
                    logger.warn("Failed to close the shaderpack zip when reloading because it was deleted, proceeding anyways.");
                } catch (IOException e) {
                    logger.error("Failed to close zip file system?", e);
                }
            }
            zipFileSystem = null;
        }
    }

    public static String lastDimensionName = null;
    public static int lastDimensionId = 0;

    private record HashedFile(long size, long modified, String hash) {}

    private record VisitedFolder(Path path, @Nullable Object key) {}

    private static final Map<Path, HashedFile> contentHashes = new ConcurrentHashMap<>();

    /**
     * Builds the fingerprint the compiled shader cache is keyed by: pack contents, pack options and the
     * environment defines. {@code byContent} hashes file bytes; the load path passes false so turning
     * shaders on only stats files instead of reading every one of them.
     */
    @Nullable
    private static String describeSettings(String packName, Path packRoot, Properties options,
                                           Iterable<StringPair> environmentDefines, boolean byContent) {
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

    private static String contentHash(Path file, BasicFileAttributes attributes) {
        final long size = attributes.size();
        final long modified = attributes.lastModifiedTime().toMillis();
        final HashedFile known = contentHashes.get(file);
        if (known != null && known.size() == size && known.modified() == modified) return known.hash();
        final String hash;
        try {
            hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        contentHashes.put(file, new HashedFile(size, modified, hash));
        return hash;
    }

    /** Fingerprint of the loaded pack, its options and the environment defines; null while the cache is off. */
    @Nullable
    public static String currentPackSettings() {
        return currentPackSettings;
    }

    /** The folder name to display for a compiled shader folder, or null for the pack base folder. */
    public static String describeFolderName(@Nullable String folder) {
        return folder == null ? I18n.format("options.actinium.precompile.baseFolder") : folder;
    }

    public static boolean isFolderCompiled(@Nullable String folder) {
        final String settings = currentPackSettings;
        return settings != null && ProgramBinaryCache.isFolderCompiled(settings, folderKey(folder));
    }

    public static void forgetFolderCompilation(@Nullable String folder) {
        final String settings = currentPackSettings;
        if (settings != null) ProgramBinaryCache.forgetFolder(settings, folderKey(folder));
    }

    /** One selectable row of the precompile screen. */
    public record PrecompileEntry(@Nullable String folder, String displayName, boolean compiled) {}

    /** All shader packs under the shaderpacks folder, in display order. */
    public static List<String> listShaderPacks() {
        try {
            return new ArrayList<>(getShaderpacksDirectoryManager().enumerate());
        } catch (IOException | RuntimeException e) {
            logger.error("Failed to list shader packs", e);
            return List.of();
        }
    }

    /**
     * The folders of one pack, or null when the pack could not be read. Reading a pack parses its shaders, so this
     * can take a moment the first time a pack is opened.
     */
    public static @Nullable List<PrecompileEntry> packEntries(String packName) {
        final PackPreview preview = previewOf(packName);
        if (preview == null) return null;
        final List<PrecompileEntry> entries = new ArrayList<>(preview.folderNames.size());
        for (String folder : preview.folderNames) {
            entries.add(new PrecompileEntry(folder, describeFolderName(folder),
                ProgramBinaryCache.isFolderCompiledFor(packName, preview.settings, folderKey(folder))));
        }
        return entries;
    }

    /** Fingerprint used by a selected whole-pack cache session, or null when the pack cannot be read. */
    public static @Nullable String packSettings(String packName) {
        final PackPreview preview = previewOf(packName);
        return preview != null ? preview.settings : null;
    }

    public static boolean isPackFolderCompiled(String packName, @Nullable String folder) {
        final PackPreview preview = previewOf(packName);
        return preview != null && ProgramBinaryCache.isFolderCompiledFor(packName, preview.settings, folderKey(folder));
    }

    public static void forgetPackFolderCompilation(String packName, @Nullable String folder) {
        final PackPreview preview = previewOf(packName);
        if (preview != null) ProgramBinaryCache.forgetFolderFor(packName, preview.settings, folderKey(folder));
    }

    /**
     * Builds a throwaway pipeline for one shader folder of any pack so every program it would otherwise compile on
     * first draw is compiled now and written to that pack's own cache layer. Must run on the client thread.
     *
     * @return true when the folder compiled and its marker was written
     */
    public static boolean compilePackFolder(String packName, @Nullable String folder) {
        final PackPreview preview = previewOf(packName);
        if (preview == null) {
            logger.warn("Cannot precompile '{}': the pack could not be opened", packName);
            return false;
        }
        final Map<String, ProgramSet> programs = ensurePrograms(preview);
        if (programs == null) {
            logger.warn("Cannot precompile '{}': the pack programs could not be loaded", packName);
            return false;
        }
        final ProgramSet programSet = programs.get(folder);
        if (programSet == null) {
            logger.warn("Cannot precompile folder '{}' of pack '{}': no such program set (known: {})",
                describeFolderName(folder), packName, programs.keySet());
            return false;
        }

        final String previousPack = currentPackName;
        ProgramBinaryCache.usePackForPrecompile(packName);
        RetainedPrograms.useConfiguration(preview.settings);
        ProgramBinaryCache.beginFolderCompile(preview.settings, folderKey(folder));
        try {
            shaderPackLoadId++;
            final DeferredWorldRenderingPipeline pipeline = new DeferredWorldRenderingPipeline(programSet);
            try {
                final ShaderProvider provider = ShaderProviderHolder.getProvider();
                if (provider instanceof IrisCeleritasShaderProvider terrain) {
                    terrain.compileTerrainPrograms(pipeline.getCeleritasTerrainPipeline());
                }
            } finally {
                getPipelineManager().discardPipeline(pipeline);
                RetainedPrograms.afterPipelineBuilt();
            }
            ProgramBinaryCache.endFolderCompile();
            logger.info("Compiled folder '{}' of pack '{}'", describeFolderName(folder), packName);
            return true;
        } catch (Exception e) {
            logger.error("Failed to precompile shader folder '{}' of pack '{}'", describeFolderName(folder), packName, e);
            ProgramBinaryCache.abortFolderCompile();
            return false;
        } finally {
            ProgramBinaryCache.usePack(previousPack);
        }
    }

    private static final String BASE_FOLDER_MARKER = "<base>";

    /**
     * A pack opened for precompiling. The folder list comes from a cache file keyed by the pack fingerprint, so
     * reopening a pack does not reparse its shaders; the programs load only when a folder is actually compiled.
     */
    private static final class PackPreview {
        private final String packName;
        private final Path root;
        private final String settings;
        private List<String> folderNames;
        private final Properties configs;
        private final List<StringPair> defines;
        private @Nullable Map<String, ProgramSet> programs;
        private @Nullable FileSystem fileSystem;

        private PackPreview(String packName, Path root, String settings, List<String> folderNames,
                            Properties configs, List<StringPair> defines) {
            this.packName = packName;
            this.root = root;
            this.settings = settings;
            this.folderNames = folderNames;
            this.configs = configs;
            this.defines = defines;
        }
    }

    private static final Map<String, PackPreview> packPreviews = new HashMap<>();

    @Nullable
    private static PackPreview previewOf(String packName) {
        final PackPreview cached = packPreviews.get(packName);
        if (cached != null) return cached;
        final PackPreview opened = openPack(packName);
        if (opened != null) packPreviews.put(packName, opened);
        return opened;
    }

    @Nullable
    private static PackPreview openPack(String packName) {
        final Path root;
        try {
            root = getShaderpacksDirectory().resolve(packName);
        } catch (InvalidPathException e) {
            logger.error("Shader pack name '{}' is not a valid path", packName);
            return null;
        }

        final Properties configs = tryReadConfigProperties(getShaderpacksDirectory().resolve(packName + ".txt"))
            .orElseGet(Properties::new);
        final List<StringPair> defines = new ArrayList<>();
        StandardMacros.createStandardEnvironmentDefines().forEach(defines::add);

        final String settings = describeSettings(packName, root, configs, defines, false);
        if (settings == null) {
            logger.warn("Could not fingerprint shader pack '{}'; it cannot be precompiled", packName);
            return null;
        }

        final List<String> cached = readFolderList(packName, settings);
        if (cached != null) {
            logger.info("Using the cached folder list of shader pack '{}' ({} folders)", packName, cached.size());
            return new PackPreview(packName, root, settings, cached, configs, defines);
        }
        logger.info("Parsing shader pack '{}' for its folder list", packName);

        final PackPreview preview = new PackPreview(packName, root, settings, List.of(), configs, defines);
        final Map<String, ProgramSet> programs = ensurePrograms(preview);
        if (programs == null) return null;
        final List<String> folderNames = new ArrayList<>(programs.keySet());
        writeFolderList(packName, settings, folderNames);
        preview.folderNames = folderNames;
        return preview;
    }

    private record LoadedPrograms(Map<String, ProgramSet> programs, FileSystem fileSystem) {}

    @Nullable
    private static Map<String, ProgramSet> ensurePrograms(PackPreview preview) {
        if (preview.programs != null) return preview.programs;
        final LoadedPrograms loaded = loadPrograms(preview.packName, preview.root, preview.configs, preview.defines);
        if (loaded == null) return null;
        preview.programs = loaded.programs();
        preview.fileSystem = loaded.fileSystem();
        return preview.programs;
    }

    @Nullable
    private static LoadedPrograms loadPrograms(String packName, Path root, Properties configs, List<StringPair> defines) {
        FileSystem fileSystem = null;
        try {
            final Path shaders;
            if (root.toString().endsWith(".zip")) {
                fileSystem = FileSystems.newFileSystem(root, Iris.class.getClassLoader());
                shaders = findShadersDirectory(fileSystem.getRootDirectories().iterator().next());
            } else {
                shaders = root.resolve("shaders");
            }
            if (shaders == null || !Files.exists(shaders)) {
                logger.warn("Shader pack '{}' has no shaders directory; it cannot be precompiled", packName);
                closeQuietly(fileSystem);
                return null;
            }

            final Map<String, String> changedConfigs = new HashMap<>();
            for (String key : configs.stringPropertyNames()) {
                changedConfigs.put(key, configs.getProperty(key));
            }

            final ShaderPack pack = new ShaderPack(shaders, changedConfigs, defines);
            return new LoadedPrograms(new LinkedHashMap<>(pack.getEveryProgramSet()), fileSystem);
        } catch (Exception e) {
            logger.error("Failed to read shader pack '{}' for precompiling", packName, e);
            closeQuietly(fileSystem);
            return null;
        }
    }

    @Nullable
    private static Path folderListFile(String packName) {
        final Path root = ShaderDiskCache.root();
        if (root == null) return null;
        return root.resolve("pack-folders").resolve(sanitizePackName(packName) + ".txt");
    }

    @Nullable
    private static List<String> readFolderList(String packName, String settings) {
        final Path file = folderListFile(packName);
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.size() < 2 || !lines.get(0).equals(settingsHash(settings))) return null;
            final List<String> folders = new ArrayList<>(lines.size() - 1);
            for (int i = 1; i < lines.size(); i++) {
                final String line = lines.get(i);
                folders.add(BASE_FOLDER_MARKER.equals(line) ? null : line);
            }
            return folders;
        } catch (IOException e) {
            logger.warn("Failed to read the cached folder list for shader pack '{}'", packName, e);
            return null;
        }
    }

    private static void writeFolderList(String packName, String settings, List<String> folders) {
        final Path file = folderListFile(packName);
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            final StringBuilder body = new StringBuilder(settingsHash(settings)).append('\n');
            for (String folder : folders) {
                body.append(folder == null ? BASE_FOLDER_MARKER : folder).append('\n');
            }
            Files.write(file, body.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.warn("Failed to cache the folder list for shader pack '{}'", packName, e);
        }
    }

    private static String settingsHash(String settings) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(settings.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sanitizePackName(String packName) {
        final StringBuilder out = new StringBuilder(Math.min(packName.length(), 64));
        for (int i = 0; i < packName.length() && out.length() < 64; i++) {
            final char c = packName.charAt(i);
            out.append(c < 128 && (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_') ? c : '_');
        }
        return out.toString();
    }

    @Nullable
    private static Path findShadersDirectory(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isDirectory).filter(path -> path.endsWith("shaders")).findFirst().orElse(null);
        }
    }

    private static void closeQuietly(@Nullable FileSystem fileSystem) {
        if (fileSystem == null) return;
        try {
            fileSystem.close();
        } catch (IOException e) {
            logger.error("Failed to close a shader pack file system", e);
        }
    }

    private static void closePreviews() {
        for (PackPreview preview : packPreviews.values()) {
            closeQuietly(preview.fileSystem);
        }
        packPreviews.clear();
    }

    private static String folderKey(@Nullable String folder) {
        return folder == null ? "" : folder;
    }

    private static int folderTrackingDepth;
    private static boolean folderTrackingActive;

    /**
     * Credits every program compiled inside this scope to the shader folder the dimension resolves to, so a normal
     * visit to a dimension counts towards its precompile state. Nested scopes share one cache session.
     */
    public static void beginFolderTracking(String dimensionName) {
        if (folderTrackingDepth++ > 0) return;
        if (ProgramBinaryCache.isFolderCompileRunning()) return;
        final ShaderPack pack = currentPack;
        final String settings = currentPackSettings;
        if (pack == null || settings == null) return;
        ProgramBinaryCache.beginFolderCompile(settings, folderKey(pack.folderForDimension(dimensionName)));
        folderTrackingActive = true;
    }

    public static void endFolderTracking() {
        if (folderTrackingDepth == 0 || --folderTrackingDepth > 0) return;
        if (!folderTrackingActive) return;
        folderTrackingActive = false;
        ProgramBinaryCache.endFolderCompile();
    }

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
        if (currentPack == null) {
            // Completely disables shader-based rendering
            return new FixedFunctionWorldRenderingPipeline();
        }

        final ProgramSet programs = currentPack.getProgramSet(dimensionName);

        try {
            shaderPackLoadId++;
            long startTime = System.nanoTime();
            final WorldRenderingPipeline pipeline;
            try {
                pipeline = new DeferredWorldRenderingPipeline(programs);
            } finally {
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
