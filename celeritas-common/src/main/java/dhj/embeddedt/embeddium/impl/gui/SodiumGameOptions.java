package dhj.embeddedt.embeddium.impl.gui;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.google.gson.JsonSyntaxException;
import com.gtnewhorizons.angelica.glsm.streaming.StreamingUploader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import dhj.embeddedt.embeddium.api.options.structure.OptionStorage;
import dhj.embeddedt.embeddium.impl.gui.framework.TextComponent;
import dhj.embeddedt.embeddium.impl.gui.options.TextProvider;
import dhj.embeddedt.embeddium.impl.render.chunk.MultiDrawMode;
import dhj.embeddedt.embeddium.impl.render.chunk.occlusion.AsyncOcclusionMode;
import dhj.embeddedt.embeddium.impl.render.culling.GpuCullingMode;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class SodiumGameOptions implements OptionStorage<SodiumGameOptions> {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final String DEFAULT_FILE_NAME = "actinium-options.json";
    private static final List<String> LEGACY_FILE_NAMES = List.of("embeddium-options.json");

    /**
     * Whether the video options screen exposes the DEBUG page. It defaults to
     * disabled and is intentionally reachable only through the config file, so
     * players cannot re-enable the page from the GUI it hides.
     */
    public boolean enableDebugTab = false;

    public final QualitySettings quality = new QualitySettings();
    public final AdvancedSettings advanced = new AdvancedSettings();
    public final PerformanceSettings performance = new PerformanceSettings();
    public final NotificationSettings notifications = new NotificationSettings();
    public final DebugSettings debug = new DebugSettings();
    public final WindowSettings window = new WindowSettings();
    public final ReducerSettings reducer = new ReducerSettings();

    private boolean readOnly;

    private Path configPath;

    public static SodiumGameOptions defaults() {
        var options = new SodiumGameOptions();
        options.configPath = getConfigPath(DEFAULT_FILE_NAME);

        return options;
    }

    @Override
    public SodiumGameOptions getData() {
        return this;
    }

    @Override
    public void save() {
        try {
            this.writeChanges();
        } catch (IOException e) {
            throw new RuntimeException("Couldn't save configuration changes", e);
        }

        LOGGER.info("Flushed changes to Actinium configuration");
    }

    public static class PerformanceSettings {
        public int chunkBuilderThreads = 0;
        public boolean trimChunkBuildScratch = true;
        @SerializedName("always_defer_chunk_updates_v2") // this will reset the option in older configs
        public boolean alwaysDeferChunkUpdates = true;

        public boolean animateOnlyVisibleTextures = true;
        public boolean useEntityCulling = true;
        public boolean useFogOcclusion = true;
        public boolean useBlockFaceCulling = true;
        public boolean useCompactVertexFormat = true;
        @SerializedName("use_translucent_face_sorting_v2")
        public boolean useTranslucentFaceSorting = true;
        public boolean useRenderPassOptimization = true;
        public boolean useRenderPassConsolidation = true;
        public boolean useFasterClouds = true;
        public boolean useNoErrorGLContext = true;
        public int loadingScreenFramerateLimit = 60;

        public boolean useFastBlockRenderer = true;
        /** Blocks whose geometry must be captured on the render thread, as {@code namespace:block} ids. */
        public List<String> deferredBlockIds = new ArrayList<>();

        /** Batches entity model parts, items and shadows on the fixed-function passes. */
        public boolean enableEntityBatching = true;
        /** Draws cuboid model parts from a shared unit cube instead of per-part geometry. */
        public boolean enableCubeInstancing = true;
        /** Stitches one texture atlas across entity and block entity models so different textures batch together. */
        public boolean enableEntityTextureAtlas = true;
        /** Replays entity draws recorded in the shader shadow pass instead of running the renderer again. */
        public boolean enableEntityShadowReplay = false;
        /** Entity classes that re-run their renderer in the main view instead of replaying shadow pass draws. */
        public List<String> entityShadowReplayExcludedClasses = new ArrayList<>(defaultEntityShadowReplayExcludedClasses());

        /**
         * {@return the exclusion list a fresh configuration starts with}. The load path restores the same list when the
         * file carries an explicit null, so a hand-edited null cannot drop the classes the shipped defaults exclude.
         */
        static List<String> defaultEntityShadowReplayExcludedClasses() {
            return List.of(
                    "twilightforest.entity.passive.EntityTFMobileFirefly",
                    "gregtech.common.entity.EntityDrone");
        }

        /** Upper bound for cached item meshes; larger values trade memory for fewer rebuilds. */
        public int itemRendererCacheSize = 512;
        public AsyncOcclusionMode asyncOcclusionMode = AsyncOcclusionMode.ONLY_SHADOW;
        public boolean useRasterOcclusionCulling = true;
        public GpuCullingMode gpuCullingMode = GpuCullingMode.CPU_ONLY;
    }

    public static class AdvancedSettings {
        /** Selects SDL GPU at the next startup, before the window and backend are created. */
        public boolean renderBackendSdlGpu = false;
        /** Preferred SDL GPU driver; an empty value selects the platform default. */
        public String renderBackendDriver = "";
        /** Limits queued GPU frames and their associated resource lifetime. */
        public int sdlGpuFramesInFlight = 2;
        /** Presents GPU frames on a separate executor. */
        public boolean sdlGpuPresenterThread = true;
        public boolean enableMemoryTracing = false;
        public boolean useAdvancedStagingBuffers = true;
        public boolean disableIncompatibleModWarnings = false;
        public boolean allowDirectMemoryAccess = true;
        public boolean enableDeferredBatching = true;
        public boolean useModelRendererBatching = true;
        public boolean useModelRendererDisplayLists = true;
        public boolean useFastLitItemRendering = true;
        public boolean useFastLitItemDisplayLists = true;

        public int cpuRenderAheadLimit = 0;
        public MultiDrawMode multiDrawMode = MultiDrawMode.DIRECT;
        public StreamingUploadStrategy streamingUploadStrategy = StreamingUploadStrategy.MAP_BUFFER_RANGE;
    }

    public static class QualitySettings {
        public GraphicsQuality weatherQuality = GraphicsQuality.DEFAULT;
        public GraphicsQuality leavesQuality = GraphicsQuality.DEFAULT;

        public boolean enableVignette = true;

        /** Uses camera-radial distance for vanilla fog instead of distance along the view plane. */
        public boolean circularFog = false;

        // Vanilla widens the field of view while sprinting/flying and narrows it while drawing a bow.
        // That factor only reaches the projection matrix when getFOVModifier is called with
        // useFOVSetting=true, so disabling this drops the dynamic factor while leaving the vanilla
        // underwater and death-camera scaling untouched.
        public boolean dynamicFov = true;

        @SerializedName("use_quad_normals_for_shading_v2")
        public boolean useQuadNormalsForShading = true;

        public int chunkFadeInDuration = 0;

        public int legacyBiomeBlendRadius = 0;

        // Biome color position noise (issue #56, inspired by Ambient Environment). Applied after
        // biome blending so the per-position variation survives blend averaging, with a
        // multiplicative factor of 1 +/- intensity so the result stays centered on the mean.
        public GuiSupersampling guiBlockSupersampling = GuiSupersampling.OFF;

        public boolean useBiomeColorNoise = true;
        public float biomeColorNoiseGrassIntensity = 0.08F;
        public float biomeColorNoiseFoliageIntensity = 0.08F;
        public float biomeColorNoiseWaterIntensity = 0.08F;

        // Zero keeps the vanilla render distance; a larger value widens the cloud disc beyond the terrain
        // render distance so the layer stays populated when the player looks at the horizon.
        public int cloudRenderDistanceChunks = 0;

        // Multiplies the cloud cell footprint. One matches the vanilla 12x12 fancy cell.
        public int cloudScale = 1;

        /** Renders the enchantment glint on items held in the world; disabling it hides world glint entirely. */
        public boolean glintWorld = true;
    }


    /** Atlas sample factors offered for GUI item rendering; the shader accepts powers of two up to sixteen. */
    public enum GuiSupersampling implements TextProvider {
        OFF(1, "options.actinium.guiSupersampling.off"),
        X2(2, "options.actinium.guiSupersampling.x2"),
        X4(4, "options.actinium.guiSupersampling.x4"),
        X8(8, "options.actinium.guiSupersampling.x8"),
        X16(16, "options.actinium.guiSupersampling.x16");

        private final int samples;
        private final String translationKey;

        GuiSupersampling(int samples, String translationKey) {
            this.samples = samples;
            this.translationKey = translationKey;
        }

        public int samples() {
            return samples;
        }

        @Override
        public TextComponent getLocalizedName() {
            return TextComponent.translatable(translationKey);
        }
    }

    public static class NotificationSettings {
        public boolean forceDisableDonationPrompts = false;

        public boolean hasClearedDonationButton = false;
        public boolean hasSeenDonationPrompt = false;
    }

    public static class DebugSettings {
        public boolean enableProductionDiagnostics = true;
        public boolean enableEntityRenderDiagnostics = false;
        public boolean ignoreFramebufferErrors = false;
        public boolean enableActiniumGlDebug = false;
        public boolean enableLwjglDebug = false;
        public boolean enablePbrDebug = false;
        public boolean enableCloudControlDebug = false;
        public boolean enableActiniumPerfDebug = false;
        public boolean enableActiniumGpuPerfDebug = false;
        public boolean enableFrameGlErrorCheck = false;
        public boolean enablePostRenderGlErrorCheck = false;
        public boolean disableErrorChecks = false;
        public boolean enableRedirectorDebug = false;
        public boolean enableRedirectorLogSpam = false;
        public boolean enableRedirectorClassDump = false;
    }

    public static class WindowSettings {
        public String fullscreenMode;
        public String lastFullscreenMode;
    }

    /**
     * Frame-rate and volume reductions applied while the window is unfocused, minimized or idle.
     * Frame limits are frames per second, volumes are percentages of the user's setting; the
     * reducer that consumes them lives in the root project.
     */
    public static class ReducerSettings {
        public boolean enabled = false;
        public int unfocusedFpsLimit = 10;
        public int idleFpsLimit = 30;
        public int idleTimeoutMinutes = 5;
        public boolean limitMenuFrameRate = true;
        public int unfocusedVolume = 100;
        public int idleVolume = 100;
        public int minimizedVolume = 100;
    }

    /** Driver identifiers understood by SDL, exposed without depending on a native backend. */
    public enum SdlGpuDriver implements TextProvider {
        AUTO("", "options.actinium.sdl_driver.auto"),
        VULKAN("vulkan", "options.actinium.sdl_driver.vulkan"),
        DIRECT3D12("direct3d12", "options.actinium.sdl_driver.direct3d12"),
        METAL("metal", "options.actinium.sdl_driver.metal");

        private final String id;
        private final String translationKey;

        SdlGpuDriver(String id, String translationKey) {
            this.id = id;
            this.translationKey = translationKey;
        }

        public String id() { return id; }

        public static SdlGpuDriver fromId(String id) {
            for (SdlGpuDriver driver : values()) {
                if (driver.id.equals(id)) return driver;
            }
            throw new IllegalArgumentException("Unknown SDL GPU driver: " + id);
        }

        @Override
        public TextComponent getLocalizedName() { return TextComponent.translatable(translationKey); }
    }

    public enum GraphicsQuality implements TextProvider {
        // "options.gamma.default" is a 1.13+ key absent from 1.12.2; use the
        // vanilla flat-world preset key which exists on 1.12.2 instead.
        DEFAULT("generator.default"),
        FANCY("options.clouds.fancy"),
        FAST("options.clouds.fast");

        private final TextComponent name;

        GraphicsQuality(String name) {
            this.name = TextComponent.translatable(name);
        }

        GraphicsQuality(List<String> names) {
            this.name = TextComponent.translatable(names);
        }

        @Override
        public TextComponent getLocalizedName() {
            return this.name;
        }

        public boolean isFancy(boolean fancyGraphics) {
            return (this == FANCY) || (this == DEFAULT && fancyGraphics);
        }
    }

    public enum StreamingUploadStrategy implements TextProvider {
        BUFFER_DATA(StreamingUploader.UploadStrategy.BUFFER_DATA, "sodium.options.streaming_upload_strategy.buffer_data"),
        BUFFER_SUB_DATA(StreamingUploader.UploadStrategy.BUFFER_SUB_DATA, "sodium.options.streaming_upload_strategy.buffer_sub_data"),
        MAP_BUFFER_RANGE(StreamingUploader.UploadStrategy.MAP_BUFFER_RANGE, "sodium.options.streaming_upload_strategy.map_buffer_range");

        private final StreamingUploader.UploadStrategy glsmStrategy;
        private final TextComponent name;

        StreamingUploadStrategy(StreamingUploader.UploadStrategy glsmStrategy, String translationKey) {
            this.glsmStrategy = glsmStrategy;
            this.name = TextComponent.translatable(translationKey);
        }

        public StreamingUploader.UploadStrategy glsmStrategy() {
            return this.glsmStrategy;
        }

        @Override
        public TextComponent getLocalizedName() {
            return this.name;
        }
    }

    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static SodiumGameOptions load() {
        return load(DEFAULT_FILE_NAME);
    }

    public static SodiumGameOptions load(String name) {
        Path path = getConfigPath(name);
        Path readPath = getReadPath(name, path);
        SodiumGameOptions config;
        boolean resaveConfig = true;

        if (Files.exists(readPath)) {
            try (FileReader reader = new FileReader(readPath.toFile())) {
                config = GSON.fromJson(reader, SodiumGameOptions.class);
            } catch (IOException e) {
                throw new RuntimeException("Could not parse config", e);
            } catch (JsonSyntaxException e) {
                LOGGER.error("Could not parse config, will fallback to default settings", e);
                config = new SodiumGameOptions();
                resaveConfig = false;
            }
        } else {
            config = new SodiumGameOptions();
        }

        config.configPath = path;

        // TODO Embeddium: Remove the field completely in 0.4
        config.notifications.forceDisableDonationPrompts = false;

        if (config.advanced.streamingUploadStrategy == null) {
            config.advanced.streamingUploadStrategy = StreamingUploadStrategy.MAP_BUFFER_RANGE;
        }

        if (config.performance.deferredBlockIds == null) {
            config.performance.deferredBlockIds = new ArrayList<>();
        }

        if (config.performance.entityShadowReplayExcludedClasses == null) {
            config.performance.entityShadowReplayExcludedClasses =
                    new ArrayList<>(PerformanceSettings.defaultEntityShadowReplayExcludedClasses());
        }

        try {
            if (resaveConfig || !readPath.equals(path)) {
                config.writeChanges();
                if (!readPath.equals(path)) {
                    LOGGER.info("Migrated Actinium configuration from {} to {}", readPath.getFileName(), path.getFileName());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Couldn't update config file", e);
        }

        return config;
    }

    private static Path getReadPath(String name, Path defaultPath) {
        if (Files.exists(defaultPath) || !DEFAULT_FILE_NAME.equals(name)) {
            return defaultPath;
        }

        for (String legacyFileName : LEGACY_FILE_NAMES) {
            Path legacyPath = getConfigPath(legacyFileName);
            if (Files.exists(legacyPath)) {
                return legacyPath;
            }
        }

        return defaultPath;
    }

    private static Path getConfigPath(String name) {
        return Paths.get("config", name);
    }

    @Deprecated
    public void writeChanges() throws IOException {
        writeToDisk(this);
    }

    public static void writeToDisk(SodiumGameOptions config) throws IOException {
        if (config.isReadOnly()) {
            throw new IllegalStateException("Config file is read-only");
        }

        Path dir = config.configPath.getParent();

        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        } else if (!Files.isDirectory(dir)) {
            throw new IOException("Not a directory: " + dir);
        }

        // Use a temporary location next to the config's final destination
        Path tempPath = config.configPath.resolveSibling(config.configPath.getFileName() + ".tmp");

        Files.writeString(tempPath, GSON.toJson(config));

        // Atomically replace the old config file (if it exists) with the temporary file
        Files.move(tempPath, config.configPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public boolean isReadOnly() {
        return this.readOnly;
    }

    public void setReadOnly() {
        this.readOnly = true;
    }

    public String getFileName() {
        return this.configPath.getFileName().toString();
    }
}
