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

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
        public AsyncOcclusionMode asyncOcclusionMode = AsyncOcclusionMode.ONLY_SHADOW;
        public boolean useRasterOcclusionCulling = true;
    }

    public static class AdvancedSettings {
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
        public boolean useBiomeColorNoise = true;
        public float biomeColorNoiseGrassIntensity = 0.08F;
        public float biomeColorNoiseFoliageIntensity = 0.08F;
        public float biomeColorNoiseWaterIntensity = 0.08F;
    }

    public static class NotificationSettings {
        public boolean forceDisableDonationPrompts = false;

        public boolean hasClearedDonationButton = false;
        public boolean hasSeenDonationPrompt = false;
    }

    public static class DebugSettings {
        public boolean enableProductionDiagnostics = true;
        public boolean ignoreFramebufferErrors = false;
        public boolean enableActiniumGlDebug = false;
        public boolean enableLwjglDebug = false;
        public boolean enablePbrDebug = false;
        public boolean enableCloudControlDebug = false;
        public boolean enableActiniumPerfDebug = false;
        public boolean enableActiniumGpuPerfDebug = false;
        public boolean enableFrameGlErrorCheck = false;
        public boolean enablePostRenderGlErrorCheck = false;
        public boolean enableRedirectorDebug = false;
        public boolean enableRedirectorLogSpam = false;
        public boolean enableRedirectorClassDump = false;
    }

    public static class WindowSettings {
        public String fullscreenMode;
        public String lastFullscreenMode;
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

        // Write the file to our temporary location
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
