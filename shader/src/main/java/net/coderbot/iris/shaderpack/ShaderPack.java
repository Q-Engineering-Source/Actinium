package net.coderbot.iris.shaderpack;

import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.threading.AngelicaWorkers;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import lombok.Getter;
import net.coderbot.iris.Iris;
import net.coderbot.iris.features.FeatureFlags;
import net.coderbot.iris.gl.buffer.ShaderStorageInfo;
import net.coderbot.iris.gl.image.ImageInformation;
import net.coderbot.iris.gl.texture.TextureDefinition;
import net.coderbot.iris.shaderpack.include.AbsolutePackPath;
import net.coderbot.iris.shaderpack.include.IncludeGraph;
import net.coderbot.iris.shaderpack.include.IncludeProcessor;
import net.coderbot.iris.shaderpack.include.ShaderPackSourceNames;
import net.coderbot.iris.shaderpack.option.ProfileSet;
import net.coderbot.iris.shaderpack.option.ShaderPackOptions;
import net.coderbot.iris.shaderpack.option.menu.OptionMenuContainer;
import net.coderbot.iris.shaderpack.option.values.MutableOptionValues;
import net.coderbot.iris.shaderpack.option.values.OptionValues;
import net.coderbot.iris.shaderpack.preprocessor.JcppProcessor;
import net.coderbot.iris.shaderpack.preprocessor.PropertiesPreprocessor;
import net.coderbot.iris.shaderpack.texture.CustomTextureData;
import net.coderbot.iris.shaderpack.texture.TextureFilteringData;
import net.coderbot.iris.shaderpack.texture.TextureStage;
import net.coderbot.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.api.v0.IrisApi;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class ShaderPack {
	private static final Gson GSON = new Gson();
	private static final Set<String> PINNED_DIMENSIONS = Set.of(
		DimensionId.OVERWORLD.getCanonicalId(),
		DimensionId.NETHER.getCanonicalId(),
		DimensionId.END.getCanonicalId()
	);
	// Configurable via -Diris.dimensionCacheSize=X (default: 12)
	// Set to 0 to disable caching entirely (only vanilla dimensions kept)
	// Minimum effective cache size is 3
	private static final int MAX_DIMENSION_CACHE;
	static {
		int configured = Math.max(0, Integer.getInteger("iris.dimensionCacheSize", 12));
		MAX_DIMENSION_CACHE = configured == 0 ? 0 : Math.max(PINNED_DIMENSIONS.size(), configured);
	}

    public final CustomUniforms.Builder customUniforms;

	private final ProgramSet base;
	private final Set<FeatureFlags> activeFeatures;

	private final Map<String, String> dimensionMap;
	private final Map<String, ProgramSet> dimensionProgramSets;
	private final Set<String> foldersWithShaderFiles;
	private final boolean mappedByDimensionProperties;
	private final String legacyDefaultFolder;
	private final Function<AbsolutePackPath, String> sourceProvider;
	private final ImmutableList<AbsolutePackPath> sourceFiles;
	private final ShaderProperties shaderProperties;
	private boolean hasLoggedCacheLimitReached = false;

	@Getter private final IdMap idMap;
	@Getter private final LanguageMap languageMap;
	@Getter private final EnumMap<TextureStage, Object2ObjectMap<String, CustomTextureData>> customTextureDataMap = new EnumMap<>(TextureStage.class);
	@Getter private final Object2ObjectMap<String, CustomTextureData> irisCustomTextureDataMap = new Object2ObjectOpenHashMap<>();
	private final CustomTextureData customNoiseTexture;
	@Getter private final Object2ObjectMap<String, ImageInformation> customImages;
	@Getter private final Int2ObjectMap<ShaderStorageInfo> bufferObjects;
	@Getter private final ShaderPackOptions shaderPackOptions;
	private final ProfileSet profiles;
	private OptionMenuContainer menuContainer;

	private final ProfileSet.ProfileResult profile;
	private final String profileInfo;

	public ShaderPack(Path root, Iterable<StringPair> environmentDefines) throws IOException, IllegalStateException {
		this(root, Collections.emptyMap(), environmentDefines);
	}

	/**
	 * Reads a shader pack from the disk.
	 *
	 * @param root The path to the "shaders" directory within the shader pack. The created ShaderPack will not retain
	 *             this path in any form; once the constructor exits, all disk I/O needed to load this shader pack will
	 *             have completed, and there is no need to hold on to the path for that reason.
	 * @throws IOException if there are any IO errors during shader pack loading.
	 */
	public ShaderPack(Path root, Map<String, String> changedConfigs, Iterable<StringPair> environmentDefines) throws IOException, IllegalStateException {
		Objects.requireNonNull(root);


		final ImmutableList.Builder<AbsolutePackPath> starts = ImmutableList.builder();
		final ImmutableList<String> potentialFileNames = ShaderPackSourceNames.POTENTIAL_STARTS;

		ShaderPackSourceNames.findPresentSources(starts, root, AbsolutePackPath.fromAbsolutePath("/"), potentialFileNames);

		// Parse dimension.properties to get dimension name -> folder mappings
		this.dimensionMap = new HashMap<>();
		this.foldersWithShaderFiles = new HashSet<>();

		Iris.logger.info("Dimension shader cache size: {} (3 vanilla pinned + {} modded), configurable via -Diris.dimensionCacheSize",
			MAX_DIMENSION_CACHE, Math.max(0, MAX_DIMENSION_CACHE - PINNED_DIMENSIONS.size()));

		// LRU cache to prevent memory leaks in modpacks with many dimensions (Mystcraft, Galacticraft, etc.)
		// Vanilla dimensions (Overworld, Nether, End) are pinned and never evicted
		this.dimensionProgramSets = new LinkedHashMap<>(Math.max(12, MAX_DIMENSION_CACHE), 0.75f, true);

		Optional<Properties> dimensionProperties = loadPropertiesFile(root, "dimension.properties");
		boolean hasDimensionProperties = dimensionProperties.isPresent();

		List<String> dimensionFolders = new ArrayList<>();
		String legacyDefault = null;

		if (hasDimensionProperties) {
			// Extract folder names from dimension.properties (e.g., "world0", "world-1", "custom_dim")
			dimensionFolders.addAll(parseDimensionMap(dimensionProperties.get(), "dimension."));
		}

		this.mappedByDimensionProperties = !dimensionFolders.isEmpty();

		if (mappedByDimensionProperties) {
			for (String folderName : dimensionFolders) {
				boolean folderExists = checkAndAddDimensionFolder(starts, root, potentialFileNames, folderName);
				if (folderExists) {
					foldersWithShaderFiles.add(folderName);
				} else {
					Iris.logger.warn("dimension.properties references folder '{}' but it doesn't exist or has no shader files", folderName);
				}
			}

			// Warn if dimension.properties has no wildcard and no explicit Overworld/Nether/End mappings
			if (!dimensionMap.containsKey("*") && PINNED_DIMENSIONS.stream().noneMatch(dimensionMap::containsKey)) {
				Iris.logger.warn("dimension.properties exists but doesn't define mappings for vanilla dimensions (Overworld/Nether/End) or a wildcard (*)");
			}
		} else {
            // No dimension.properties or it has no valid dimension mappings
			if (hasDimensionProperties) {
				Iris.logger.warn("dimension.properties exists but has no valid dimension mappings, falling back to legacy folder detection");
			}

			// Scan for all world{ID} folders to support old OptiFine packs with dimension IDs
			Set<String> foundFolders = new HashSet<>();
			try {
				Files.list(root)
					.filter(Files::isDirectory)
					.map(Path::getFileName)
					.map(Path::toString)
					.filter(name -> name.matches("world-?\\d+"))
					.forEach(folderName -> {
						try {
							boolean exists = checkAndAddDimensionFolder(starts, root, potentialFileNames, folderName);
							if (exists) {
								foundFolders.add(folderName);
								foldersWithShaderFiles.add(folderName);
							}
						} catch (IOException e) {
							Iris.logger.warn("Failed to scan dimension folder: {}", folderName, e);
						}
					});
			} catch (IOException e) {
				Iris.logger.warn("Failed to scan for dimension folders", e);
			}

			if (foundFolders.contains("world0")) {
				dimensionMap.put(DimensionId.OVERWORLD.getCanonicalId(), "world0");
				legacyDefault = "world0";
			}
			if (foundFolders.contains("world-1")) {
				dimensionMap.put(DimensionId.NETHER.getCanonicalId(), "world-1");
			}
			if (foundFolders.contains("world1")) {
				dimensionMap.put(DimensionId.END.getCanonicalId(), "world1");
			}
		}
		this.legacyDefaultFolder = legacyDefault;

		final CompletableFuture<LanguageMap> languageMapFuture = AngelicaWorkers.submit(() -> {
			try {
				return new LanguageMap(root.resolve("lang"));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});

		// Read all files and included files recursively
		this.sourceFiles = starts.build();
		IncludeGraph graph = new IncludeGraph(root, sourceFiles);
		if (!graph.getFailures().isEmpty()) {
			graph.getFailures().forEach((path, error) -> {
				Iris.logger.error("{}", error.toString());
			});

			throw new IOException("Failed to resolve some #include directives, see previous messages for details");
		}

		this.shaderPackOptions = new ShaderPackOptions(graph, changedConfigs);
		graph = this.shaderPackOptions.getIncludes();

		List<StringPair> finalEnvironmentDefines = new ArrayList<>();
		environmentDefines.forEach(finalEnvironmentDefines::add);
		for (FeatureFlags flag : FeatureFlags.values()) {
			boolean usable = flag.isUsable();
			Iris.logger.info("FeatureFlag {} usable: {}", flag.name(), usable);
			if (usable) {
				finalEnvironmentDefines.add(new StringPair("IRIS_FEATURE_" + flag.name(), ""));
				if (flag == FeatureFlags.TESSELLATION_SHADERS) {
					finalEnvironmentDefines.add(new StringPair("IRIS_FEATURE_TESSELATION_SHADERS", ""));
				}
			}
		}
		Iris.logger.info("Environment defines: {}", finalEnvironmentDefines.stream()
			.map(p -> p.getKey() + "=" + p.getValue())
			.collect(Collectors.joining(", ")));

		this.shaderProperties = Optional.ofNullable(readProperties(root, "shaders.properties"))
				.map(source -> new ShaderProperties(source, shaderPackOptions, finalEnvironmentDefines, RenderSystem::supportsBufferBlending))
				.orElseGet(ShaderProperties::empty);

		this.activeFeatures = new HashSet<>();
		for (String flag : shaderProperties.getRequiredFeatureFlags()) {
			activeFeatures.add(FeatureFlags.getValue(flag));
		}
		for (String flag : shaderProperties.getOptionalFeatureFlags()) {
			activeFeatures.add(FeatureFlags.getValue(flag));
		}

		List<FeatureFlags> invalidFlagList = shaderProperties.getRequiredFeatureFlags().stream().filter(FeatureFlags::isInvalid).map(FeatureFlags::getValue).collect(Collectors.toList());
		List<String> invalidFeatureFlags = invalidFlagList.stream().map(FeatureFlags::getHumanReadableName).collect(Collectors.toList());

		if (!invalidFeatureFlags.isEmpty()) {
            // TODO: GUI
			IrisApi.getInstance().getConfig().setShadersEnabledAndApply(false);
		}

		this.profiles = ProfileSet.fromTree(shaderProperties.getProfiles(), this.shaderPackOptions.getOptionSet());
		this.profile = profiles.scan(this.shaderPackOptions.getOptionSet(), this.shaderPackOptions.getOptionValues());

		List<String> disabledPrograms = new ArrayList<>();
		this.profile.current.ifPresent(profile -> disabledPrograms.addAll(profile.disabledPrograms));
		shaderProperties.getConditionallyEnabledPrograms().forEach((program, shaderOption) -> {
			if ("true".equals(shaderOption)) return;

			if ("false".equals(shaderOption) || !this.shaderPackOptions.getOptionValues().getBooleanValueOrDefault(shaderOption)) {
				disabledPrograms.add(program);
			}
		});

		{
			String profileName = getCurrentProfileName();
			OptionValues profileOptions = new MutableOptionValues(
					this.shaderPackOptions.getOptionSet(), this.profile.current.map(p -> p.optionValues).orElse(new HashMap<>()));

			int userOptionsChanged = this.shaderPackOptions.getOptionValues().getOptionsChanged() - profileOptions.getOptionsChanged();

			this.profileInfo = "Profile: " + profileName + " (+" + userOptionsChanged + " option" + (userOptionsChanged == 1 ? "" : "s") + " changed by user)";
		}

		Iris.logger.info(this.profileInfo);

		IncludeProcessor includeProcessor = new IncludeProcessor(graph);

		Iterable<StringPair> finalEnvironmentDefines1 = finalEnvironmentDefines;
		this.sourceProvider = (path) -> {
			String pathString = path.getPathString();
			// Removes the first "/" in the path if present, and the file
			// extension in order to represent the path as its program name
			String programString = pathString.substring(pathString.indexOf("/") == 0 ? 1 : 0, pathString.lastIndexOf("."));

			if (disabledPrograms.contains(programString)) {
				return null;
			}

			final byte[] contentHash = includeProcessor.getIncludedFileHash(path);

			if (contentHash == null) {
				return null;
			}

			// Apply GLSL preprocessor to source, while making environment defines available.
			//
			// This uses similar techniques to the *.properties preprocessor to avoid actually putting
			// #define statements in the actual source - instead, we tell the preprocessor about them
			// directly. This removes one obstacle to accurate reporting of line numbers for errors,
			// though there exist many more (such as relocating all #extension directives and similar things)
			//
			// The hash covers the file and every include it pulls in, so the include expansion and the
			// workarounds only run when the preprocessed source is not already on disk.
			return JcppProcessor.glslPreprocessSource(contentHash, () -> {
				final ImmutableList<String> lines = includeProcessor.getIncludedFile(path);

				int length = 0;
				for (String line : lines) {
					length += line.length() + 1;
				}

				final StringBuilder builder = new StringBuilder(length);

				for (String line : lines) {
					builder.append(line);
					builder.append('\n');
				}

				// Apply shader pack workarounds for version compatibility (before preprocessing)
				return ShaderPackWorkarounds.apply(builder.toString());
			}, finalEnvironmentDefines1);
		};

		this.base = new ProgramSet(AbsolutePackPath.fromAbsolutePath("/"), preprocessInParallel(AbsolutePackPath.fromAbsolutePath("/")), shaderProperties, this);

		// Reading block.properties for the legacy-section verdict happens on the client thread; the map itself
		// preprocesses three more files, so it is built on a worker and awaited once the pack is otherwise ready.
		final boolean hasLegacySection = IdMap.detectLegacySection(root);
		final CompletableFuture<IdMap> idMapFuture = AngelicaWorkers.submit(
			() -> new IdMap(root, hasLegacySection, shaderPackOptions, environmentDefines));

		customNoiseTexture = shaderProperties.getNoiseTexturePath().map(path -> {
			try {
				return readTexture(root, path);
			} catch (IOException e) {
				Iris.logger.error("Unable to read the custom noise texture at " + path, e);

				return null;
			}
		}).orElse(null);

		shaderProperties.getCustomTextures().forEach((textureStage, customTexturePropertiesMap) -> {
			Object2ObjectMap<String, CustomTextureData> innerCustomTextureDataMap = new Object2ObjectOpenHashMap<>();
			customTexturePropertiesMap.forEach((samplerName, definition) -> {
				try {
					innerCustomTextureDataMap.put(samplerName, readTexture(root, definition));
				} catch (IOException e) {
					Iris.logger.error("Unable to read the custom texture at " + definition.getName(), e);
				}
			});

			customTextureDataMap.put(textureStage, innerCustomTextureDataMap);
		});

		// Process irisCustomTextures (direct custom textures via customTexture.<name>=<path>)
		shaderProperties.getIrisCustomTextures().forEach((samplerName, definition) -> {
			try {
				irisCustomTextureDataMap.put(samplerName, readTexture(root, definition));
				Iris.logger.debug("[CustomTextures] Loaded customTexture.{} from {}", samplerName, definition.getName());
			} catch (IOException e) {
				Iris.logger.error("Unable to read custom texture for sampler " + samplerName + " at " + definition.getName(), e);
			}
		});

        this.customUniforms = shaderProperties.getCustomUniforms();
		this.customImages = shaderProperties.getCustomImages();
		this.bufferObjects = shaderProperties.getBufferObjects();

		this.idMap = await(idMapFuture);
		this.languageMap = await(languageMapFuture);
	}

	public OptionMenuContainer getMenuContainer() {
		if (menuContainer == null) {
			menuContainer = new OptionMenuContainer(shaderProperties, shaderPackOptions, profiles);
		}
		return menuContainer;
	}

	private static <T> T await(CompletableFuture<T> future) throws IOException {
		try {
			return future.join();
		} catch (CompletionException e) {
			if (e.getCause() instanceof UncheckedIOException io) throw io.getCause();
			if (e.getCause() instanceof IOException io) throw io;
			if (e.getCause() instanceof RuntimeException runtime) throw runtime;
			if (e.getCause() instanceof Error error) throw error;
			throw e;
		}
	}

	private String getCurrentProfileName() {
		return profile.current.map(p -> p.name).orElse("Custom");
	}

	public String getProfileInfo() {
		return profileInfo;
	}

    // TODO: Copy-paste from IdMap, find a way to deduplicate this
	private static Optional<Properties> loadPropertiesFile(Path shaderPath, String name) {
		String fileContents = readProperties(shaderPath, name);
		if (fileContents == null) {
			return Optional.empty();
		}

		StringReader propertiesReader = new StringReader(fileContents);

		Properties properties = new OrderBackedProperties();
		try {
			properties.load(propertiesReader);
		} catch (IOException e) {
			Iris.logger.error("Error loading " + name + " at " + shaderPath, e);
			return Optional.empty();
		}

		return Optional.of(properties);
	}

  /**
   * Parses dimension mappings from dimension.properties and populates the dimensionMap field.
   *
   * Format: dimension.<folder> = <dimension names...>
   * Example: dimension.world0 = Overworld "Twilight Forest" minecraft:overworld
   *
   * For each entry, creates mappings from each dimension name to its folder name in dimensionMap.
   *
   * @param keyPrefix The prefix to filter keys by (typically "dimension.")
   * @return List of folder names found in the properties (e.g., ["world0", "world-1", "custom_dim"])
   */
	private List<String> parseDimensionMap(Properties properties, String keyPrefix) {
		List<String> folderNames = new ArrayList<>();

		properties.forEach((keyObject, valueObject) -> {
			String key = (String) keyObject;
			String value = (String) valueObject;

			if (!key.startsWith(keyPrefix)) {
				return;
			}

			// Extract folder name (e.g., "world0" from "dimension.world0")
			String folderName = key.substring(keyPrefix.length());

			if (folderName.isEmpty()) {
				Iris.logger.warn("Ignoring dimension.properties entry with empty folder name: {}", key);
				return;
			}

			List<String> dimensionNames = IdMap.parseIdentifierList(value, "dimension.properties", key);

			if (dimensionNames.isEmpty()) {
				Iris.logger.warn("Ignoring dimension.properties entry '{}' with no dimension names", key);
				return;
			}

			folderNames.add(folderName);

			for (String dimensionName : dimensionNames) {
				dimensionMap.put(DimensionId.canonicalize(dimensionName), folderName);
			}
		});

		return folderNames;
	}

	/**
	 * Checks if a dimension folder exists and adds it to the starts list
	 */
	private boolean checkAndAddDimensionFolder(ImmutableList.Builder<AbsolutePackPath> starts, Path root,
											   ImmutableList<String> potentialFileNames, String folderName) throws IOException {
		return ShaderPackSourceNames.findPresentSources(starts, root,
				AbsolutePackPath.fromAbsolutePath("/" + folderName), potentialFileNames);
	}

	/**
	 * Reads a texture from a TextureDefinition. Handles both PNG and raw texture types.
	 */
	public CustomTextureData readTexture(Path root, TextureDefinition definition) throws IOException {
		if (definition instanceof TextureDefinition.RawDefinition rawDef) {
			return readRawTexture(root, rawDef);
		} else {
			// PNG definition - use the path-based method
			return readTexture(root, definition.getName());
		}
	}

	/**
	 * Reads a raw texture from a RawDefinition.
	 */
	private CustomTextureData readRawTexture(Path root, TextureDefinition.RawDefinition rawDef) throws IOException {
		String path = rawDef.getName();

		if (path.startsWith("/")) {
			path = path.substring(1);
		}

		// Read mcmeta for filtering data. Raw textures default to linear/clamped filtering,
		// matching Iris and preventing shaderpack LUTs from displaying banding artifacts.
		boolean blur = true;
		boolean clamp = true;

		String mcMetaPath = path + ".mcmeta";
		Path mcMetaResolvedPath = root.resolve(mcMetaPath);

		if (Files.exists(mcMetaResolvedPath)) {
			try {
				JsonObject meta = loadMcMeta(mcMetaResolvedPath);
				if (meta.get("texture") != null) {
					if (meta.get("texture").getAsJsonObject().get("blur") != null) {
						blur = meta.get("texture").getAsJsonObject().get("blur").getAsBoolean();
					}
					if (meta.get("texture").getAsJsonObject().get("clamp") != null) {
						clamp = meta.get("texture").getAsJsonObject().get("clamp").getAsBoolean();
					}
				}
			} catch (IOException e) {
				Iris.logger.error("Unable to read the custom texture mcmeta at " + mcMetaPath + ", ignoring: " + e);
			}
		}

		byte[] content = Files.readAllBytes(root.resolve(path));
		TextureFilteringData filteringData = new TextureFilteringData(blur, clamp);

		return switch (rawDef.getTarget()) {
			case TEXTURE_1D -> new CustomTextureData.RawData1D(content, filteringData,
					rawDef.getInternalFormat(), rawDef.getFormat(), rawDef.getPixelType(),
					rawDef.getSizeX());
			case TEXTURE_2D -> new CustomTextureData.RawData2D(content, filteringData,
					rawDef.getInternalFormat(), rawDef.getFormat(), rawDef.getPixelType(),
					rawDef.getSizeX(), rawDef.getSizeY());
			case TEXTURE_3D -> new CustomTextureData.RawData3D(content, filteringData,
					rawDef.getInternalFormat(), rawDef.getFormat(), rawDef.getPixelType(),
					rawDef.getSizeX(), rawDef.getSizeY(), rawDef.getSizeZ());
			case TEXTURE_RECTANGLE -> new CustomTextureData.RawDataRect(content, filteringData,
					rawDef.getInternalFormat(), rawDef.getFormat(), rawDef.getPixelType(),
					rawDef.getSizeX(), rawDef.getSizeY());
		};
	}

	/**
	 * Reads a texture from a path string (for PNG textures and resource locations).
	 */
	public CustomTextureData readTexture(Path root, String path) throws IOException {
		CustomTextureData customTextureData;
		if (path.contains(":")) {
			String[] parts = path.split(":");

			if (parts.length > 2) {
				Iris.logger.warn("Resource location " + path + " contained more than two parts?");
			}

			if (parts[0].equals("minecraft") && (parts[1].equals("dynamic/lightmap_1") || parts[1].equals("dynamic/light_map_1"))) {
				customTextureData = new CustomTextureData.LightmapMarker();
			} else {
				customTextureData = new CustomTextureData.ResourceData(parts[0], parts[1]);
			}
		} else {
			if (path.startsWith("/")) {
				// NB: This does not guarantee the resulting path is in the shaderpack as a double slash could be used,
				// this just fixes shaderpacks like Continuum 2.0.4 that use a leading slash in texture paths
				path = path.substring(1);
			}

			boolean blur = false;
			boolean clamp = false;

			String mcMetaPath = path + ".mcmeta";
			Path mcMetaResolvedPath = root.resolve(mcMetaPath);

			if (Files.exists(mcMetaResolvedPath)) {
				try {
					JsonObject meta = loadMcMeta(mcMetaResolvedPath);
					if (meta.get("texture") != null) {
						if (meta.get("texture").getAsJsonObject().get("blur") != null) {
							blur = meta.get("texture").getAsJsonObject().get("blur").getAsBoolean();
						}
						if (meta.get("texture").getAsJsonObject().get("clamp") != null) {
							clamp = meta.get("texture").getAsJsonObject().get("clamp").getAsBoolean();
						}
					}
				} catch (IOException e) {
					Iris.logger.error("Unable to read the custom texture mcmeta at " + mcMetaPath + ", ignoring: " + e);
				}
			}

			byte[] content = Files.readAllBytes(root.resolve(path));

			customTextureData = new CustomTextureData.PngData(new TextureFilteringData(blur, clamp), content);
		}
		return customTextureData;
	}

	private JsonObject loadMcMeta(Path mcMetaPath) throws IOException, JsonParseException {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(mcMetaPath), StandardCharsets.UTF_8))) {
			JsonReader jsonReader = new JsonReader(reader);
			return GSON.getAdapter(JsonObject.class).read(jsonReader);
		}
	}

	private static String readProperties(Path shaderPath, String name) {
		try {
			// Property files should be encoded in ISO_8859_1.
			return new String(Files.readAllBytes(shaderPath.resolve(name)), StandardCharsets.ISO_8859_1);
		} catch (NoSuchFileException e) {
			Iris.logger.debug("An " + name + " file was not found in the current shaderpack");

			return null;
		} catch (IOException e) {
			Iris.logger.error("An IOException occurred reading " + name + " from the current shaderpack", e);

			return null;
		}
	}

	/**
	 * Evicts the least recently used non-pinned dimension from the cache if it exceeds the size limit.
	 * Pinned dimensions (Overworld, Nether, The End) are never evicted.
	 * The LinkedHashMap maintains access order, so oldest entries are at the front.
	 */
	private void evictOldDimensions() {
		Set<String> pinnedFolders = resolvePinnedDimensionFolders(dimensionMap, foldersWithShaderFiles);

		// If cache is disabled, evict everything except pinned folders
		if (MAX_DIMENSION_CACHE == 0) {
			dimensionProgramSets.entrySet().removeIf(e -> !pinnedFolders.contains(e.getKey()));
			return;
		}

		long nonPinnedCount = dimensionProgramSets.entrySet().stream()
			.filter(e -> !pinnedFolders.contains(e.getKey()))
			.count();

		int maxNonPinned = Math.max(0, MAX_DIMENSION_CACHE - PINNED_DIMENSIONS.size());

		while (nonPinnedCount > maxNonPinned) {
			if (!hasLoggedCacheLimitReached) {
				Iris.logger.info("Dimension shader cache limit reached (max {} total: {} vanilla + {} modded). Now evicting least recently used dimensions.",
					MAX_DIMENSION_CACHE, PINNED_DIMENSIONS.size(), maxNonPinned);
				hasLoggedCacheLimitReached = true;
			}

			String toEvict = null;
			for (Map.Entry<String, ProgramSet> entry : dimensionProgramSets.entrySet()) {
				if (!pinnedFolders.contains(entry.getKey())) {
					toEvict = entry.getKey();
					break;
				}
			}

			if (toEvict != null) {
				dimensionProgramSets.remove(toEvict);
				nonPinnedCount--;
			} else {
				// Shouldn't happen, but break to avoid infinite loop
				break;
			}
		}
	}

    /**
     * Gets or creates the appropriate ProgramSet for the given dimension.
     *
     * Resolution order:
     *
     *   Exact match in dimension.properties (dimension.<folder> = dimensionName)
     *   Wildcard match (dimension.<folder> = *)
     *   Legacy world{ID} folder (e.g., world0, world-1)
     *   world0 for legacy packs without dimension.properties
     *   Fallback to base ProgramSet
     *
     *
     * ProgramSets are lazily created and cached. When cache exceeds limit,
     * least recently used non-vanilla dimensions are evicted.
     *
     * @param dimensionName The canonical dimension key returned by Iris
     * @return The ProgramSet for this dimension, or base ProgramSet if no override exists
     */
    public ProgramSet getProgramSet(String dimensionName) {
		int dimensionId = Iris.getCurrentDimensionId();
		String canonicalDimension = DimensionId.canonicalize(dimensionName, dimensionId);

		String folderName = resolveDimensionFolder(
			dimensionMap,
			foldersWithShaderFiles,
			legacyDefaultFolder,
			canonicalDimension,
			dimensionId
		);

		if (folderName != null && foldersWithShaderFiles.contains(folderName)) {
			final ProgramSet programSet = getFolderProgramSet(folderName);

			if (programSet != null) {
				return programSet;
			}
		}

		// Warn if dimension.properties exists but this dimension has no mapping and no wildcard
		if (mappedByDimensionProperties && !dimensionMap.containsKey(canonicalDimension) && !dimensionMap.containsKey("*")) {
			Iris.logger.warn("Dimension '{}' has no shader mapping in dimension.properties and no wildcard (*) fallback is defined. " +
					"Falling back to base shaders. Consider adding 'dimension.<folder>={}' or 'dimension.<folder>=*' to dimension.properties",
					canonicalDimension, canonicalDimension);
		}

		// NB: If a dimension overrides directory is present, none of the files from the parent directory are "merged"
		//     into the override. Rather, we act as if the overrides directory contains a completely different set of
		//     shader programs unrelated to that of the base shader pack.
		//
		//     This makes sense because if base defined a composite pass and the override didn't, it would make it
		//     impossible to "un-define" the composite pass. It also removes a lot of complexity related to "merging"
		//     program sets. At the same time, this might be desired behavior by shader pack authors. It could make
		//     sense to bring it back as a configurable option, and have a more maintainable set of code backing it.

		return base;
	}

	/**
	 * The shader folder a dimension resolves to, or null when it renders the base shaders.
	 */
	@Nullable
	public String folderForDimension(String dimensionName) {
		final int dimensionId = Iris.getCurrentDimensionId();
		final String canonicalDimension = DimensionId.canonicalize(dimensionName, dimensionId);
		final String folderName = resolveDimensionFolder(dimensionMap, foldersWithShaderFiles, legacyDefaultFolder, canonicalDimension, dimensionId);
		return folderName != null && foldersWithShaderFiles.contains(folderName) ? folderName : null;
	}

	/**
	 * Creates or returns the cached ProgramSet for a dimension folder.
	 */
	@Nullable
	private ProgramSet getFolderProgramSet(String folderName) {
		ProgramSet programSet = dimensionProgramSets.get(folderName);

		if (programSet == null) {
			// Create ProgramSet on-demand for dimension folder
			try {
				final AbsolutePackPath directory = AbsolutePackPath.fromAbsolutePath("/" + folderName);
				programSet = new ProgramSet(directory, preprocessInParallel(directory), shaderProperties, this);
				dimensionProgramSets.put(folderName, programSet);

				evictOldDimensions();
			} catch (Exception e) {
				Iris.logger.error("Failed to create ProgramSet for dimension folder '{}', falling back to base", folderName, e);
				programSet = null;
			}
		}

		return programSet;
	}

	/**
	 * Resolves one shader folder for the precompile screen; a null folder means the pack base, which only
	 * exists when some dimension can still fall back to it.
	 */
	@Nullable
	public ProgramSet getProgramSetForFolder(@Nullable String folder) {
		if (folder == null) {
			return canFallBackToBase(dimensionMap, foldersWithShaderFiles, legacyDefaultFolder) ? base : null;
		}
		return getFolderProgramSet(folder);
	}

	/**
	 * Every ProgramSet a full compile has to cover, keyed by folder name in sorted order; the base shaders are
	 * under the null key and are only included when some dimension can still fall back to them.
	 */
	public Map<String, ProgramSet> getEveryProgramSet() {
		final Map<String, ProgramSet> sets = new LinkedHashMap<>();
		for (String folderName : new TreeSet<>(foldersWithShaderFiles)) {
			final ProgramSet programSet = getFolderProgramSet(folderName);
			if (programSet != null) sets.put(folderName, programSet);
		}
		if (canFallBackToBase(dimensionMap, foldersWithShaderFiles, legacyDefaultFolder)) {
			sets.put(null, base);
		}
		return sets;
	}

	/**
	 * @return whether some dimension resolves to no folder of its own and therefore renders the base shaders
	 */
	static boolean canFallBackToBase(Map<String, String> dimensionMap, Set<String> foldersWithShaderFiles, String legacyDefaultFolder) {
		for (String folderName : dimensionMap.values()) {
			if (!foldersWithShaderFiles.contains(folderName)) return true;
		}
		// A dimension that is named nowhere and has no world<id> folder of its own
		return resolveDimensionFolder(dimensionMap, foldersWithShaderFiles, legacyDefaultFolder, "\0", Integer.MIN_VALUE) == null;
	}

	/**
	 * Resolves a dimension folder without loading shader sources or touching OpenGL state.
	 */
	@Nullable
	static String resolveDimensionFolder(Map<String, String> mappings, Set<String> availableFolders,
									 String dimensionName, int dimensionId) {
		return resolveDimensionFolder(mappings, availableFolders, null, dimensionName, dimensionId);
	}

	@Nullable
	static String resolveDimensionFolder(Map<String, String> mappings, Set<String> availableFolders,
									 String legacyDefaultFolder, String dimensionName, int dimensionId) {
		String canonicalDimension = DimensionId.canonicalize(dimensionName, dimensionId);
		String folderName = mappings.get(canonicalDimension);

		if (folderName == null) {
			folderName = mappings.get("*");
		}

		if (folderName == null) {
			String legacyFolder = "world" + dimensionId;
			folderName = availableFolders.contains(legacyFolder) ? legacyFolder : legacyDefaultFolder;
		}

		return folderName != null && availableFolders.contains(folderName) ? folderName : null;
	}

	/**
	 * Wraps the pack source provider so every program of one folder is preprocessed before the folder is read.
	 *
	 * <p>Reading a folder's programs is independent work: include expansion, the shader pack workarounds and jcpp
	 * all run per file. Doing it on the worker pool keeps a folder load from serializing them on the thread that
	 * creates the {@link ProgramSet}. Only successful results are remembered; a program that fails or is disabled
	 * falls through to the shared provider, so the error surfaces at the same point it would without this pass.
	 */
	private Function<AbsolutePackPath, String> preprocessInParallel(AbsolutePackPath directory) {
		final String directoryPath = directory.getPathString();
		final String prefix = directoryPath.endsWith("/") ? directoryPath : directoryPath + "/";
		final List<AbsolutePackPath> files = new ArrayList<>();
		final List<Supplier<String>> tasks = new ArrayList<>();

		for (AbsolutePackPath file : sourceFiles) {
			final String path = file.getPathString();

			// Only the programs of this folder; includes live in other folders and are reached through the cache.
			if (path.startsWith(prefix) && path.indexOf('/', prefix.length()) < 0) {
				files.add(file);
				tasks.add(() -> {
					try {
						return sourceProvider.apply(file);
					} catch (RuntimeException e) {
						// Leave the program to the shared provider so its failure is raised where it is read.
						Iris.logger.debug("Deferring preprocessing of {} to its reader", file.getPathString(), e);
						return null;
					}
				});
			}
		}

		final List<String> sources = AngelicaWorkers.invokeAll(tasks);
		final Map<AbsolutePackPath, String> preprocessed = new HashMap<>();

		for (int i = 0; i < files.size(); i++) {
			final String source = sources.get(i);
			if (source != null) {
				preprocessed.put(files.get(i), source);
			}
		}

		return path -> preprocessed.containsKey(path) ? preprocessed.get(path) : sourceProvider.apply(path);
	}

	/**
	 * Resolves every vanilla dimension through the production folder-selection rules for cache pinning.
	 */
	static Set<String> resolvePinnedDimensionFolders(Map<String, String> mappings, Set<String> availableFolders) {
		Set<String> pinnedFolders = new HashSet<>();

		for (DimensionId dimension : DimensionId.values()) {
			String folderName = resolveDimensionFolder(
				mappings,
				availableFolders,
				dimension.getCanonicalId(),
				dimension.getNumericId()
			);
			if (folderName != null) {
				pinnedFolders.add(folderName);
			}
		}

		return pinnedFolders;
	}

    public Optional<CustomTextureData> getCustomNoiseTexture() {
		return Optional.ofNullable(customNoiseTexture);
	}

	public Set<FeatureFlags> getActiveFeatures() {
		return activeFeatures;
	}

	public boolean hasFeature(FeatureFlags feature) {
		return activeFeatures.contains(feature);
	}
}
