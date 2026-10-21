package net.coderbot.iris.pipeline;

import net.coderbot.iris.debug.ShaderRegressionDebug;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import com.gtnewhorizons.angelica.compat.mojang.Camera;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.texture.TextureInfoCache;
import com.gtnewhorizons.angelica.rendering.RenderingState;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.coderbot.iris.Iris;
import net.coderbot.iris.block_rendering.BlockMaterialMapping;
import net.coderbot.iris.block_rendering.BlockRenderingSettings;
import net.coderbot.iris.celeritas.CeleritasTerrainPipeline;
import net.coderbot.iris.compat.dh.DHCompat;
import net.coderbot.iris.debug.IrisGlDebug;
import net.coderbot.iris.features.FeatureFlags;
import net.coderbot.iris.gbuffer_overrides.matching.InputAvailability;
import net.coderbot.iris.gbuffer_overrides.matching.ProgramTable;
import net.coderbot.iris.gbuffer_overrides.matching.RenderCondition;
import net.coderbot.iris.gbuffer_overrides.matching.SpecialCondition;
import net.coderbot.iris.gbuffer_overrides.matching.TranslucentBlendMatcher;
import net.coderbot.iris.gbuffer_overrides.matching.TransformInputs;
import net.coderbot.iris.gbuffer_overrides.state.RenderTargetStateListener;
import net.coderbot.iris.gl.blending.AlphaTestOverride;
import net.coderbot.iris.gl.blending.BlendModeOverride;
import net.coderbot.iris.gl.blending.DepthColorStorage;
import net.coderbot.iris.gl.blending.BufferBlendOverride;
import net.coderbot.iris.gl.buffer.ShaderStorageBufferHolder;
import net.coderbot.iris.gl.buffer.ShaderStorageInfo;
import net.coderbot.iris.gl.framebuffer.GlFramebuffer;
import net.coderbot.iris.gl.framebuffer.MinecraftFramebufferHelper;
import net.coderbot.iris.gl.image.GlImage;
import net.coderbot.iris.gl.image.ImageHolder;
import net.coderbot.iris.gl.image.ImageInformation;
import net.coderbot.iris.gl.program.ComputeProgram;
import net.coderbot.iris.gl.program.Program;
import net.coderbot.iris.gl.program.ProgramBuilder;
import net.coderbot.iris.gl.program.ProgramImages;
import net.coderbot.iris.gl.program.ProgramSamplers;
import net.coderbot.iris.gl.sampler.SamplerHolder;
import net.coderbot.iris.gl.state.FogMode;
import net.coderbot.iris.gl.texture.DepthBufferFormat;
import net.coderbot.iris.gl.texture.TextureType;
import net.coderbot.iris.helpers.Tri;
import net.coderbot.iris.layer.GbufferPrograms;
import net.coderbot.iris.pipeline.transform.PatchShaderType;
import net.coderbot.iris.pipeline.transform.TransformPatcher;
import net.coderbot.iris.postprocess.BufferFlipper;
import net.coderbot.iris.postprocess.CenterDepthSampler;
import net.coderbot.iris.postprocess.CompositeRenderer;
import net.coderbot.iris.postprocess.FinalPassRenderer;
import net.coderbot.iris.postprocess.FullScreenQuadRenderer;
import net.coderbot.iris.postprocess.ProgramBuildContext;
import net.coderbot.iris.rendertarget.IRenderTargetExt;
import net.coderbot.iris.rendertarget.NativeImageBackedSingleColorTexture;
import net.coderbot.iris.rendertarget.RenderTargets;
import net.coderbot.iris.samplers.IrisImages;
import net.coderbot.iris.samplers.IrisSamplers;
import net.coderbot.iris.shaderpack.CloudSetting;
import net.coderbot.iris.shaderpack.ComputeSource;
import net.coderbot.iris.shaderpack.OptionalBoolean;
import net.coderbot.iris.shaderpack.PackDirectives;
import net.coderbot.iris.shaderpack.PackShadowDirectives;
import net.coderbot.iris.shaderpack.ProgramDirectives;
import net.coderbot.iris.shaderpack.ProgramFallbackResolver;
import net.coderbot.iris.shaderpack.ProgramSet;
import net.coderbot.iris.shaderpack.ProgramSource;
import net.coderbot.iris.shaderpack.loading.ProgramId;
import net.coderbot.iris.shaderpack.option.values.OptionValues;
import net.coderbot.iris.shaderpack.texture.TextureStage;
import net.coderbot.iris.shadows.ShadowCompositeRenderer;
import net.coderbot.iris.shadows.ShadowRenderTargets;
import net.coderbot.iris.texture.format.TextureFormat;
import net.coderbot.iris.texture.format.TextureFormatLoader;
import net.coderbot.iris.texture.pbr.PBRTextureHolder;
import net.coderbot.iris.texture.pbr.PBRTextureManager;
import net.coderbot.iris.texture.pbr.PBRType;
import net.coderbot.iris.uniforms.CommonUniforms;
import net.coderbot.iris.uniforms.FrameUpdateNotifier;
import net.coderbot.iris.uniforms.ItemMaterialHelper;
import net.coderbot.iris.uniforms.WorldTimeUniforms;
import net.coderbot.iris.uniforms.custom.CustomUniforms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.init.Blocks;
import org.apache.commons.lang3.tuple.Pair;
import dhj.embeddedt.embeddium.impl.gl.profiling.TimerQueryManager;
import dhj.embeddedt.embeddium.impl.model.light.debug.AODebug;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Encapsulates the compiled shader program objects for the currently loaded shaderpack.
 */
public class DeferredWorldRenderingPipeline implements WorldRenderingPipeline, RenderTargetStateListener  {
	private final RenderTargets renderTargets;

	@Nullable
	private ShadowRenderTargets shadowRenderTargets;
	@Nullable
	private ComputeProgram[] shadowComputes;
	private final Supplier<ShadowRenderTargets> shadowTargetsSupplier;

	private final ProgramTable<Pass> table;

	private ImmutableList<ClearPass> clearPassesFull;
	private ImmutableList<ClearPass> clearPasses;
	private ImmutableList<ClearPass> shadowClearPasses;
	private ImmutableList<ClearPass> shadowClearPassesFull;

	private final ComputeProgram[] setup;
	private final CompositeRenderer beginRenderer;
	private final CompositeRenderer prepareRenderer;

	@Nullable
	private final ShadowRenderer shadowRenderer;
	@Nullable
	private final ShadowCompositeRenderer shadowCompositeRenderer;

    private final CustomUniforms customUniforms;

	private final int shadowMapResolution;
	private final CompositeRenderer deferredRenderer;
	private final CompositeRenderer compositeRenderer;
	private final FinalPassRenderer finalPassRenderer;
	private final CustomTextureManager customTextureManager;
	private final AbstractTexture whitePixel;
	private final FrameUpdateNotifier updateNotifier;
	private final CenterDepthSampler centerDepthSampler;

	private final ImmutableSet<Integer> flippedBeforeShadow;
	private final ImmutableSet<Integer> flippedAfterPrepare;
	private final ImmutableSet<Integer> flippedAfterTranslucent;

	private final CeleritasTerrainPipeline celeritasTerrainPipeline;
	private final DHCompat dhCompat;

	// Custom images and SSBOs
	private final Set<GlImage> customImages;
	private final GlImage[] imagesToClear;
	@Nullable
	private final ShaderStorageBufferHolder ssboHolder;
	private final AdaptiveShadowBoundsStats adaptiveShadowBoundsStats;

	private final Map<Pair<String, InputAvailability>, Map<PatchShaderType, String>> attributeTransforms;

	private final HorizonRenderer horizonRenderer = new HorizonRenderer();
    @Nullable
    private TimerQueryManager finalizeOutputTimer;
    @Nullable
    private TimerQueryManager compositeOutputTimer;
    @Nullable
    private TimerQueryManager finalOutputTimer;

	private final float sunPathRotation;
	private final CloudSetting cloudSetting;
	private final boolean shouldRenderUnderwaterOverlay;
	private final boolean shouldRenderVignette;
	private final boolean shouldRenderSun;
	private final boolean shouldRenderMoon;
	private final boolean shouldRenderStars;
	private final boolean shouldRenderSkyDisc;
	private final boolean shouldRenderWeather;
	private final boolean shouldRenderWeatherParticles;
	private final boolean shouldWriteRainAndSnowToDepthBuffer;
	private final boolean shouldRenderParticlesBeforeDeferred;
	private final boolean shouldRenderPrepareBeforeShadow;
	private final boolean oldLighting;
	private final boolean allowConcurrentCompute;
	private final OptionalInt forcedShadowRenderDistanceChunks;
	private final CloudSetting dhCloudSetting;

	private Pass current = null;
	private WorldRenderingPhase overridePhase = null;
	private WorldRenderingPhase phase = WorldRenderingPhase.NONE;
	private boolean isBeforeTranslucent;
	private boolean isRenderingShadow = false;
	private InputAvailability inputs = new InputAvailability(false, false);
	private SpecialCondition special = null;
	private boolean refreshingPass;
	private boolean modProgramOverrode;
	private int programBeforeModOverride = -1;
	private boolean matchingBlend;
	private boolean drivingProgram;

	private boolean shouldBindPBR;
	private int currentNormalTexture;
	private int currentSpecularTexture;
	private PackDirectives packDirectives;
	private final Set<FeatureFlags> activeFeatures;
	private final ProgramFallbackResolver resolver;

	public DeferredWorldRenderingPipeline(ProgramSet programs) {
		try {
			Objects.requireNonNull(programs);
			this.adaptiveShadowBoundsStats = AdaptiveShadowBoundsStats.create(programs.getPack().getBufferObjects());
			AdaptiveShadowBoundsStats.activate(this.adaptiveShadowBoundsStats);

			resolver = new ProgramFallbackResolver(programs);
			// Every transform this constructor is about to need, submitted up front through the one place
			// the warmup submits them through too: whatever this set covers is read back out of the
			// transform cache below instead of being transformed on this thread.
			final ProgramTransforms transforms = ProgramTransforms.submit(programs);
			final Map<Pair<String, InputAvailability>, CompletableFuture<Map<PatchShaderType, String>>> attributeTransformFutures = transforms.attributes();

			final ProgramTransforms.TerrainPasses terrain = transforms.terrain();
			final Optional<ProgramSource> terrainSource = terrain.solid().source();
			final Optional<ProgramSource> translucentSource = terrain.translucent().source();
			final Optional<ProgramSource> shadowSource = terrain.shadow().source();
			final Optional<ProgramSource> shadowTranslucentSource = terrain.shadowTranslucent().source();

			// Celeritas terrain transform futures
			final CompletableFuture<Map<PatchShaderType, String>> celeritasTerrainFuture = terrain.solid().transform();
			final CompletableFuture<Map<PatchShaderType, String>> celeritasTranslucentFuture = terrain.translucent().transform();
			final CompletableFuture<Map<PatchShaderType, String>> celeritasShadowFuture = terrain.shadow().transform();
			final CompletableFuture<Map<PatchShaderType, String>> celeritasShadowTranslucentFuture = terrain.shadowTranslucent().transform();

			this.cloudSetting = programs.getPackDirectives().getCloudSetting();
			this.shouldRenderUnderwaterOverlay = programs.getPackDirectives().underwaterOverlay();
			this.shouldRenderVignette = programs.getPackDirectives().vignette();
			this.shouldRenderSun = programs.getPackDirectives().shouldRenderSun();
			this.shouldRenderMoon = programs.getPackDirectives().shouldRenderMoon();
			this.shouldRenderStars = programs.getPackDirectives().shouldRenderStars();
			this.shouldRenderSkyDisc = programs.getPackDirectives().shouldRenderSkyDisc();
			this.shouldRenderWeather = programs.getPackDirectives().shouldRenderWeather();
			this.shouldRenderWeatherParticles = programs.getPackDirectives().shouldRenderWeatherParticles();
			this.shouldWriteRainAndSnowToDepthBuffer = programs.getPackDirectives().rainDepth();
			this.dhCloudSetting = programs.getPackDirectives().getDHCloudSetting();
			this.shouldRenderParticlesBeforeDeferred = programs.getPackDirectives().getParticleRenderingSettings()
				.map(s -> s == net.coderbot.iris.shaderpack.ParticleRenderingSettings.BEFORE || s == net.coderbot.iris.shaderpack.ParticleRenderingSettings.MIXED)
				.orElse(false);
			this.allowConcurrentCompute = programs.getPackDirectives().getConcurrentCompute();
			this.shouldRenderPrepareBeforeShadow = programs.getPackDirectives().isPrepareBeforeShadow();
			this.oldLighting = programs.getPackDirectives().isOldLighting();
			this.updateNotifier = new FrameUpdateNotifier();

			this.packDirectives = programs.getPackDirectives();
			this.activeFeatures = programs.getPack().getActiveFeatures();

	        final Framebuffer main = Minecraft.getMinecraft().getFramebuffer();

	        final int depthTextureId = ((IRenderTargetExt)main).iris$getDepthTextureId();
			final int internalFormat = TextureInfoCache.INSTANCE.getInfo(depthTextureId).getInternalFormat();
			final DepthBufferFormat depthBufferFormat = DepthBufferFormat.fromGlEnumOrDefault(internalFormat);

			this.renderTargets = new RenderTargets(main.framebufferWidth, main.framebufferHeight, depthTextureId,
	            ((IRenderTargetExt)main).iris$getDepthBufferVersion(), depthBufferFormat,
				programs.getPackDirectives().getRenderTargetDirectives().getRenderTargetSettings(), programs.getPackDirectives());

			this.sunPathRotation = programs.getPackDirectives().getSunPathRotation();

			PackShadowDirectives shadowDirectives = programs.getPackDirectives().getShadowDirectives();

			if (shadowDirectives.isDistanceRenderMulExplicit()) {
				if (shadowDirectives.getDistanceRenderMul() >= 0.0) {
					// add 15 and then divide by 16 to ensure we're rounding up
					forcedShadowRenderDistanceChunks = OptionalInt.of(((int) (shadowDirectives.getDistance() * shadowDirectives.getDistanceRenderMul()) + 15) / 16);
				} else {
					forcedShadowRenderDistanceChunks = OptionalInt.of(-1);
				}
			} else {
				forcedShadowRenderDistanceChunks = OptionalInt.empty();
			}

	        this.customUniforms = programs.getPack().customUniforms.build(
	            holder -> CommonUniforms.addNonDynamicUniforms(holder, programs.getPack().getIdMap(), programs.getPackDirectives(), this.updateNotifier)
	        );

			final BlockMaterialMapping.BlockIdMaps blockIdMaps = BlockMaterialMapping.createBlockIdMaps(
				programs.getPack().getIdMap().getBlockProperties(),
				programs.getPack().getIdMap().getTagEntries(),
				programs.getPack().getIdMap().hasLegacySection());
			BlockRenderingSettings.INSTANCE.setBlockMetaMatches(blockIdMaps.blockMetaMap());
			BlockRenderingSettings.INSTANCE.setBlockNbtMap(blockIdMaps.blockNbtMap());
			BlockRenderingSettings.INSTANCE.setBlockTypeIds(BlockMaterialMapping.createBlockTypeMap(programs.getPack().getIdMap().getBlockRenderTypeMap()));
			logBlockMappingSummary();

			BlockRenderingSettings.INSTANCE.setEntityIds(programs.getPack().getIdMap().getEntityIdMap());
			BlockRenderingSettings.INSTANCE.setEntityNbtMap(BlockMaterialMapping.createNamespacedNbtMap(programs.getPack().getIdMap().getEntityNbtEntries()));

			ItemMaterialHelper.clearCache();
			BlockRenderingSettings.INSTANCE.setItemIds(programs.getPack().getIdMap().getItemIdMap());
			BlockRenderingSettings.INSTANCE.setItemNbtMap(BlockMaterialMapping.createNamespacedNbtMap(programs.getPack().getIdMap().getItemNbtEntries()));
			float ambientOcclusionLevel = resolveAmbientOcclusionLevel(programs);
			BlockRenderingSettings.INSTANCE.setAmbientOcclusionLevel(ambientOcclusionLevel);
			BlockRenderingSettings.INSTANCE.setDisableDirectionalShading(shouldDisableDirectionalShading());
			BlockRenderingSettings.INSTANCE.setUseSeparateAo(programs.getPackDirectives().shouldUseSeparateAo());
			BlockRenderingSettings.INSTANCE.setUseExtendedVertexFormat(true);
			AODebug.logSettings(
				"deferred",
				ambientOcclusionLevel,
				programs.getPackDirectives().shouldUseSeparateAo()
			);

			// Don't clobber anything in texture unit 0. It probably won't cause issues, but we're just being cautious here.
			GLStateManager.glActiveTexture(GL13.GL_TEXTURE2);

			customTextureManager = new CustomTextureManager(programs.getPackDirectives(), programs.getPack().getCustomTextureDataMap(), programs.getPack().getIrisCustomTextureDataMap(), programs.getPack().getCustomNoiseTexture());

			whitePixel = new NativeImageBackedSingleColorTexture(255, 255, 255, 255);

			// Initialize custom images
			this.customImages = new HashSet<>();
			final var customImageInfos = programs.getPack().getCustomImages();
			final List<GlImage> clearList = new ArrayList<>();
			for (var entry : customImageInfos.object2ObjectEntrySet()) {
				final ImageInformation info = entry.getValue();
				final GlImage image;
				if (info.isRelative()) {
					image = new GlImage.Relative(info.name(), info.samplerName(), info.format(), info.internalTextureFormat(),
						info.type(), info.clear(), info.relativeWidth(), info.relativeHeight(),
						main.framebufferWidth, main.framebufferHeight);
				} else {
					image = new GlImage(info.name(), info.samplerName(), info.target(), info.format(), info.internalTextureFormat(),
						info.type(), info.clear(), info.width(), info.height(), info.depth());
				}
				customImages.add(image);
				if (image.shouldClear()) {
					clearList.add(image);
				}
			}
			this.imagesToClear = clearList.toArray(new GlImage[0]);

			// Initialize SSBOs
			final var bufferObjectInfos = programs.getPack().getBufferObjects();
			if (!bufferObjectInfos.isEmpty() && RenderSystem.supportsSSBO()) {
				Int2ObjectArrayMap<ShaderStorageInfo> ssboOverrides = new Int2ObjectArrayMap<>();
				bufferObjectInfos.forEach(ssboOverrides::put);
				this.ssboHolder = new ShaderStorageBufferHolder(ssboOverrides, main.framebufferWidth, main.framebufferHeight);
			} else {
				this.ssboHolder = null;
			}

			GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);

			BufferFlipper flipper = new BufferFlipper();

			this.centerDepthSampler = new CenterDepthSampler(() -> getRenderTargets().getDepthTexture(), programs.getPackDirectives().getCenterDepthHalfLife());

			this.shadowMapResolution = programs.getPackDirectives().getShadowDirectives().getResolution();

			this.shadowTargetsSupplier = () -> {
				if (shadowRenderTargets == null) {
					this.shadowRenderTargets = new ShadowRenderTargets(this, shadowMapResolution, shadowDirectives);
				}

				return shadowRenderTargets;
			};

			PatchedShaderPrinter.resetPrintState();

			final ProgramBuildContext beginBuildContext = new ProgramBuildContext(renderTargets, customTextureManager.getNoiseTexture(), updateNotifier, centerDepthSampler, shadowTargetsSupplier, customTextureManager.getCustomTextureIdMap(TextureStage.BEGIN), customUniforms, customImages, customTextureManager.getIrisCustomTextures(), this);
			final ProgramBuildContext prepareBuildContext = new ProgramBuildContext(renderTargets, customTextureManager.getNoiseTexture(), updateNotifier, centerDepthSampler, shadowTargetsSupplier, customTextureManager.getCustomTextureIdMap(TextureStage.PREPARE), customUniforms, customImages, customTextureManager.getIrisCustomTextures(), this);
			final ProgramBuildContext deferredBuildContext = new ProgramBuildContext(renderTargets, customTextureManager.getNoiseTexture(), updateNotifier, centerDepthSampler, shadowTargetsSupplier, customTextureManager.getCustomTextureIdMap(TextureStage.DEFERRED), customUniforms, customImages, customTextureManager.getIrisCustomTextures(), this);
			final ProgramBuildContext compositeBuildContext = new ProgramBuildContext(renderTargets, customTextureManager.getNoiseTexture(), updateNotifier, centerDepthSampler, shadowTargetsSupplier, customTextureManager.getCustomTextureIdMap(TextureStage.COMPOSITE_AND_FINAL), customUniforms, customImages, customTextureManager.getIrisCustomTextures(), this);

			this.setup = createSetupComputes(programs.getSetup());

			this.beginRenderer = new CompositeRenderer(programs.getBegin(), programs.getBeginCompute(), flipper, beginBuildContext, programs.getPackDirectives().getExplicitFlips("begin_pre"), transforms.composites().begin(), "begin", TextureStage.BEGIN);

			this.flippedBeforeShadow = flipper.snapshot();

			this.prepareRenderer = new CompositeRenderer(programs.getPrepare(), programs.getPrepareCompute(), flipper, prepareBuildContext, programs.getPackDirectives().getExplicitFlips("prepare_pre"), transforms.composites().prepare(), "prepare", TextureStage.PREPARE);

			flippedAfterPrepare = flipper.snapshot();

			this.deferredRenderer = new CompositeRenderer(programs.getDeferred(), programs.getDeferredCompute(), flipper, deferredBuildContext, programs.getPackDirectives().getExplicitFlips("deferred_pre"), transforms.composites().deferred(), "deferred", TextureStage.DEFERRED);

			flippedAfterTranslucent = flipper.snapshot();

			this.compositeRenderer = new CompositeRenderer(programs.getComposite(), programs.getCompositeCompute(), flipper, compositeBuildContext, programs.getPackDirectives().getExplicitFlips("composite_pre"), transforms.composites().composite(), "composite", TextureStage.COMPOSITE_AND_FINAL);
			this.finalPassRenderer = new FinalPassRenderer(programs, compositeBuildContext, flipper.snapshot(), this.compositeRenderer.getFlippedAtLeastOnceFinal(), transforms.composites().compositeFinal(), "final");

			// [(textured=false,lightmap=false), (textured=true,lightmap=false), (textured=true,lightmap=true)]
			final ProgramId[] ids = new ProgramId[] {
					ProgramId.Basic, ProgramId.Textured, ProgramId.TexturedLit,
					ProgramId.SkyBasic, ProgramId.SkyTextured, ProgramId.SkyTextured,
					null, null, ProgramId.Terrain,
					null, null, ProgramId.Water,
					null, ProgramId.Clouds, ProgramId.Clouds,
					null, ProgramId.DamagedBlock, ProgramId.DamagedBlock,
					ProgramId.Block, ProgramId.Block, ProgramId.Block,
					ProgramId.BlockTrans, ProgramId.BlockTrans, ProgramId.BlockTrans,
					ProgramId.BeaconBeam, ProgramId.BeaconBeam, ProgramId.BeaconBeam,
					ProgramId.Entities, ProgramId.Entities, ProgramId.Entities,
					ProgramId.EntitiesTrans, ProgramId.EntitiesTrans, ProgramId.EntitiesTrans,
					null, ProgramId.ArmorGlint, ProgramId.ArmorGlint,
					null, ProgramId.SpiderEyes, ProgramId.SpiderEyes,
					ProgramId.Hand, ProgramId.Hand, ProgramId.Hand,
					ProgramId.HandWater, ProgramId.HandWater, ProgramId.HandWater,
					null, null, ProgramId.Weather,
					// world border uses textured_lit even though it has no lightmap :/
					null, ProgramId.TexturedLit, ProgramId.TexturedLit,
					ProgramId.ShadowWater, ProgramId.ShadowWater, ProgramId.ShadowWater,
					ProgramId.Shadow, ProgramId.Shadow, ProgramId.Shadow
			};

			if (ids.length != RenderCondition.values().length * 3) {
				throw new IllegalStateException("Program ID table length mismatch");
			}

			this.attributeTransforms = new HashMap<>();
			for (Map.Entry<Pair<String, InputAvailability>, CompletableFuture<Map<PatchShaderType, String>>> entry : attributeTransformFutures.entrySet()) {
				try {
					this.attributeTransforms.put(entry.getKey(), entry.getValue().join());
				} catch (Exception e) {
					Iris.logger.error("Failed to transform shader: {}", entry.getKey().getLeft(), e);
					throw new RuntimeException("Shader transformation failed for " + entry.getKey().getLeft(), e);
				}
			}

			final Map<Pair<ProgramId, InputAvailability>, Pass> cachedPasses = new HashMap<>();
			// Programs that several memoized passes resolve to are built once and shared, see createPass
			final SharedPrograms<BuiltProgram> builtPrograms = new SharedPrograms<>();

			this.shadowComputes = createShadowComputes(programs.getShadowCompute());

			this.table = new ProgramTable<>((condition, availability) -> {
				final ProgramId finalId = gbufferProgramId(ids, condition, availability);

				return cachedPasses.computeIfAbsent(Pair.of(finalId, availability), p -> {
					final ProgramSource source = resolver.resolveNullable(p.getLeft());

					if (condition == RenderCondition.SHADOW || condition == RenderCondition.SHADOW_TRANSLUCENT) {
						if (!shadowDirectives.isShadowEnabled().orElse(shadowRenderTargets != null)) {
							// shadow is not used
							return null;
						} else if (source == null) {
							// still need the custom framebuffer, viewport, and blend mode behavior
							GlFramebuffer shadowFb = shadowTargetsSupplier.get().createShadowFramebuffer(shadowRenderTargets.snapshot(), new int[] {0});
							return new Pass(null, shadowFb, shadowFb, null,
								BlendModeOverride.OFF, Collections.emptyList(), true);
						}
					}

					if (source == null) {
						return createDefaultPass();
					}

					try {
						return createPass(source, availability,
							condition == RenderCondition.SHADOW || condition == RenderCondition.SHADOW_TRANSLUCENT, finalId,
							builtPrograms);
					} catch (Exception e) {
						throw new RuntimeException("Failed to create pass for " + source.getName() + " for rendering condition "
							+ condition + " specialized to input availability " + availability, e);
					}
				});
			});
			if (shadowRenderTargets == null && shadowDirectives.isShadowEnabled() == OptionalBoolean.TRUE) {
				shadowRenderTargets = new ShadowRenderTargets(this, shadowMapResolution, shadowDirectives);
			}

			if (shadowRenderTargets != null) {
				this.shadowClearPasses = ClearPassCreator.createShadowClearPasses(shadowRenderTargets, false, shadowDirectives);
				this.shadowClearPassesFull = ClearPassCreator.createShadowClearPasses(shadowRenderTargets, true, shadowDirectives);

				this.shadowCompositeRenderer = new ShadowCompositeRenderer(this, programs.getPackDirectives(),
					programs.getShadowComposite(), programs.getShadowCompCompute(), this.shadowRenderTargets,
					ssboHolder,
					customTextureManager.getNoiseTexture(), updateNotifier,
					customTextureManager.getCustomTextureIdMap(TextureStage.SHADOWCOMP),
					customImages, programs.getPackDirectives().getExplicitFlips("shadowcomp_pre"),
					customTextureManager.getIrisCustomTextures(), customUniforms);

				if (programs.getPackDirectives().getShadowDirectives().isShadowEnabled().orElse(true)) {
					this.shadowRenderer = new ShadowRenderer(programs.getShadow().orElse(null),
						programs.getPackDirectives(), shadowRenderTargets, shadowCompositeRenderer);
					Program shadowProgram = table.match(RenderCondition.SHADOW, new InputAvailability(true, true)).getProgram();
					Program shadowWaterProgram = table.match(RenderCondition.SHADOW_TRANSLUCENT, new InputAvailability(true, true)).getProgram();
					shadowRenderer.setUsesImages((shadowProgram != null && shadowProgram.getActiveImages() > 0)
						|| (shadowWaterProgram != null && shadowWaterProgram.getActiveImages() > 0));
				} else {
					shadowRenderer = null;
				}
			} else {
				this.shadowClearPasses = ImmutableList.of();
				this.shadowClearPassesFull = ImmutableList.of();
				this.shadowCompositeRenderer = null;
				this.shadowRenderer = null;
			}

	        this.customUniforms.optimise();

			this.clearPassesFull = ClearPassCreator.createClearPasses(renderTargets, true, programs.getPackDirectives().getRenderTargetDirectives());
			this.clearPasses = ClearPassCreator.createClearPasses(renderTargets, false, programs.getPackDirectives().getRenderTargetDirectives());

			boolean hasSetup = false;
			for (ComputeProgram program : setup) {
				if (program != null) {
					if (!hasSetup) {
						hasSetup = true;
						renderTargets.onFullClear();
						final Vector4f fogColor = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
						clearPassesFull.forEach(clearPass -> clearPass.execute(fogColor));
					}
					program.use();
					customUniforms.push(program);
					program.dispatch(1, 1);
				}
			}
			if (hasSetup) {
				ComputeProgram.unbind();
				RenderSystem.memoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT);
			}

			// Terrain pipeline sampler/image factory setup follows.

			Supplier<ImmutableSet<Integer>> flipped = () -> isBeforeTranslucent ? flippedAfterPrepare : flippedAfterTranslucent;

			IntFunction<ProgramSamplers> createTerrainSamplers = (programId) -> {
				ProgramSamplers.Builder builder = ProgramSamplers.builder(programId, IrisSamplers.WORLD_RESERVED_TEXTURE_UNITS);
				ProgramSamplers.CustomTextureSamplerInterceptor customTextureSamplerInterceptor = ProgramSamplers.customTextureSamplerInterceptor(builder, customTextureManager.getCustomTextureIdMap(TextureStage.GBUFFERS_AND_SHADOW));

				IrisSamplers.addRenderTargetSamplers(customTextureSamplerInterceptor, flipped, renderTargets, false, this);
				IrisSamplers.addLevelSamplers(customTextureSamplerInterceptor, this, whitePixel, new InputAvailability(true, true));
				IrisSamplers.addWorldDepthSamplers(customTextureSamplerInterceptor, renderTargets);
				IrisSamplers.addNoiseSampler(customTextureSamplerInterceptor, customTextureManager.getNoiseTexture());

				// Bind custom images as samplers (for texture() access to voxel_sampler, floodfill_sampler, etc.)
				IrisSamplers.addCustomImages(customTextureSamplerInterceptor, customImages);
				// Bind custom textures (PNG files from shader pack)
				IrisSamplers.addCustomTextures(customTextureSamplerInterceptor, customTextureManager.getIrisCustomTextures());

				if (IrisSamplers.hasShadowSamplers(customTextureSamplerInterceptor)) {
					IrisSamplers.addShadowSamplers(customTextureSamplerInterceptor, Objects.requireNonNull(shadowRenderTargets), null, true);
				}

				return builder.build();
			};

			IntFunction<ProgramImages> createTerrainImages = (programId) -> {
				ProgramImages.Builder builder = ProgramImages.builder(programId);

				IrisImages.addRenderTargetImages(builder, flipped, renderTargets);
				// Bind custom images as image units (for imageLoad/imageStore)
				IrisImages.addCustomImages(builder, customImages);

				if (IrisImages.hasShadowImages(builder)) {
					IrisImages.addShadowColorImages(builder, Objects.requireNonNull(shadowRenderTargets), null);
				}

				return builder.build();
			};

			IntFunction<ProgramSamplers> createShadowTerrainSamplers = (programId) -> {
				ProgramSamplers.Builder builder = ProgramSamplers.builder(programId, IrisSamplers.WORLD_RESERVED_TEXTURE_UNITS);
				ProgramSamplers.CustomTextureSamplerInterceptor customTextureSamplerInterceptor = ProgramSamplers.customTextureSamplerInterceptor(builder, customTextureManager.getCustomTextureIdMap(TextureStage.GBUFFERS_AND_SHADOW));

				IrisSamplers.addRenderTargetSamplers(customTextureSamplerInterceptor, () -> flippedAfterPrepare, renderTargets, false, this);
				IrisSamplers.addLevelSamplers(customTextureSamplerInterceptor, this, whitePixel, new InputAvailability(true, true));
				IrisSamplers.addNoiseSampler(customTextureSamplerInterceptor, customTextureManager.getNoiseTexture());

				// Bind custom images as samplers (for texture() access to voxel_sampler, floodfill_sampler, etc.)
				IrisSamplers.addCustomImages(customTextureSamplerInterceptor, customImages);
				// Bind custom textures (PNG files from shader pack)
				IrisSamplers.addCustomTextures(customTextureSamplerInterceptor, customTextureManager.getIrisCustomTextures());

				// Only initialize these samplers if the shadow map renderer exists. Otherwise, this program shouldn't be used at all?
				if (IrisSamplers.hasShadowSamplers(customTextureSamplerInterceptor)) {
					IrisSamplers.addShadowSamplers(customTextureSamplerInterceptor, Objects.requireNonNull(shadowRenderTargets), null, true);
				}

				return builder.build();
			};
	        IntFunction<ProgramImages> createShadowTerrainImages = (programId) -> {
				ProgramImages.Builder builder = ProgramImages.builder(programId);

				IrisImages.addRenderTargetImages(builder, () -> flippedAfterPrepare, renderTargets);
				// Bind custom images as image units (for imageLoad/imageStore - voxelization uses this in shadow pass)
				IrisImages.addCustomImages(builder, customImages);

				if (IrisImages.hasShadowImages(builder)) {
					IrisImages.addShadowColorImages(builder, Objects.requireNonNull(shadowRenderTargets), null);
				}

				return builder.build();
			};
			final GlFramebuffer celeritasShadowFb = (shadowRenderTargets != null && shadowRenderer != null)
				? shadowRenderTargets.createShadowFramebuffer(shadowRenderTargets.snapshot(), new int[] {0, 1})
				: null;

			this.celeritasTerrainPipeline = new CeleritasTerrainPipeline(createTerrainSamplers,
				shadowRenderer == null ? null : createShadowTerrainSamplers, createTerrainImages,
				shadowRenderer == null ? null : createShadowTerrainImages, this.customUniforms,
				terrainSource,
				translucentSource,
				shadowSource,
				shadowTranslucentSource,
				celeritasTerrainFuture, celeritasTranslucentFuture, celeritasShadowFuture, celeritasShadowTranslucentFuture,
				renderTargets, flippedAfterPrepare, flippedAfterTranslucent,
				celeritasShadowFb);

			this.dhCompat = new DHCompat(this, shadowDirectives.isDhShadowEnabled().orElse(true));
		} catch (Throwable failure) {
			// The caller never receives a half-built pipeline, so whatever this constructor already
			// created has to be released here; destroy() skips the fields it never reached.
			try {
				destroy();
			} catch (Throwable cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
	}

	private RenderTargets getRenderTargets() {
		return renderTargets;
	}

	private static float resolveAmbientOcclusionLevel(ProgramSet programs) {
		float directiveLevel = programs.getPackDirectives().getAmbientOcclusionLevel();
		AODebug.logSettings("directive", directiveLevel, programs.getPackDirectives().shouldUseSeparateAo());
		OptionValues optionValues = programs.getPack().getShaderPackOptions().getOptionValues();
		Optional<String> optionLevel = optionValues.getStringValue("ambientOcclusionLevel");

		if (optionLevel.isEmpty()) {
			return directiveLevel;
		}

		try {
			float optionLevelValue = Math.max(0.0F, Math.min(1.0F, Float.parseFloat(optionLevel.get())));
			AODebug.logSettings("option", optionLevelValue, programs.getPackDirectives().shouldUseSeparateAo());
			return optionLevelValue;
		} catch (NumberFormatException e) {
			Iris.logger.error("Failed to parse ambientOcclusionLevel shader option value '{}', using directive value {}",
				optionLevel.get(), directiveLevel);
			return directiveLevel;
		}
	}

	private void checkWorld() {
		// If we're not in a world, then obviously we cannot possibly be rendering a world.
		if (Minecraft.getMinecraft().world == null) {
			isRenderingWorld = false;
			current = null;
		}
	}

	@Override
	public boolean shouldDisableVanillaEntityShadows() {
		// OptiFine seems to disable vanilla shadows when the shaderpack uses shadow mapping?
		return shadowRenderer != null;
	}

	@Override
	public boolean shouldDisableDirectionalShading() {
		return !oldLighting;
	}

	@Override
	public CloudSetting getCloudSetting() {
		return cloudSetting;
	}

	@Override
	public boolean shouldRenderUnderwaterOverlay() {
		return shouldRenderUnderwaterOverlay;
	}

	@Override
	public boolean shouldRenderVignette() {
		return shouldRenderVignette;
	}

	@Override
	public boolean shouldRenderSun() {
		return shouldRenderSun;
	}

	@Override
	public boolean shouldRenderMoon() {
		return shouldRenderMoon;
	}

	@Override
	public boolean shouldRenderStars() {
		return shouldRenderStars;
	}

	@Override
	public boolean shouldRenderSkyDisc() {
		return shouldRenderSkyDisc;
	}

	@Override
	public boolean shouldRenderWeather() {
		return shouldRenderWeather;
	}

	@Override
	public boolean shouldRenderWeatherParticles() {
		return shouldRenderWeatherParticles;
	}

	@Override
	public boolean shouldWriteRainAndSnowToDepthBuffer() {
		return shouldWriteRainAndSnowToDepthBuffer;
	}

	@Override
	public boolean shouldRenderParticlesBeforeDeferred() {
		return shouldRenderParticlesBeforeDeferred;
	}

	@Override
	public boolean allowConcurrentCompute() {
		return allowConcurrentCompute;
	}

	@Override
	public float getSunPathRotation() {
		return sunPathRotation;
	}

	@Override
	public boolean hasFeature(FeatureFlags flag) {
		return activeFeatures.contains(flag);
	}

	@Override
	public Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> getTextureMap() {
		return packDirectives.getTextureMap();
	}

	private RenderCondition getCondition(WorldRenderingPhase phase) {
		if (isRenderingShadow) {
			return switch (phase) {
				case TERRAIN_TRANSLUCENT, TRIPWIRE -> RenderCondition.SHADOW_TRANSLUCENT;
				default -> RenderCondition.SHADOW;
			};
		}

		if (special != null) {
			if (special == SpecialCondition.BEACON_BEAM) {
				return RenderCondition.BEACON_BEAM;
			} else if (special == SpecialCondition.ENTITY_EYES) {
				return RenderCondition.ENTITY_EYES;
			} else if (special == SpecialCondition.GLINT) {
				return RenderCondition.GLINT;
			}
		}

		switch (phase) {
			case NONE, OUTLINE, DEBUG, PARTICLES:
				return RenderCondition.DEFAULT;
			case SKY, SUNSET, CUSTOM_SKY, SUN, MOON, STARS, VOID:
				return RenderCondition.SKY;
			case TERRAIN_SOLID, TERRAIN_CUTOUT, TERRAIN_CUTOUT_MIPPED:
				return RenderCondition.TERRAIN_OPAQUE;
			case ENTITIES:
				return TranslucentBlendMatcher.matchesCurrentState()
					? RenderCondition.ENTITIES_TRANSLUCENT : RenderCondition.ENTITIES;
			case BLOCK_ENTITIES:
				return TranslucentBlendMatcher.matchesCurrentState()
					? RenderCondition.BLOCK_ENTITIES_TRANSLUCENT : RenderCondition.BLOCK_ENTITIES;
			case DESTROY:
				return RenderCondition.DESTROY;
			case HAND_SOLID:
				return RenderCondition.HAND_OPAQUE;
            case TERRAIN_TRANSLUCENT, TRIPWIRE:
				return RenderCondition.TERRAIN_TRANSLUCENT;
			case CLOUDS:
				return RenderCondition.CLOUDS;
			case RAIN_SNOW:
				return RenderCondition.RAIN_SNOW;
			case HAND_TRANSLUCENT:
				return RenderCondition.HAND_TRANSLUCENT;
			case WORLD_BORDER:
				return RenderCondition.WORLD_BORDER;
			default:
				throw new IllegalStateException("Unknown render phase " + phase);
		}
	}

	public boolean shouldOverrideShaders() {
		return isRenderingWorld && !isRenderingFullScreenPass && !isPostChain && isMainBound;
	}

	public int getActivePassProgramId() {
		if (current == null) return -1;
		final Program p = current.getProgram();
		return p != null ? p.getProgramId() : -1;
	}

	/**
	 * Called when a mod overrides the GL program away from the active Iris pass
	 */
	public void onModProgramOverride() {
		if (current != null) {
			modProgramOverrode = true;
			programBeforeModOverride = getActivePassProgramId();
		}
		IrisGlDebug.logModProgramOverride(
			"on-mod-program-override",
			getPhase().name(),
			inputs.toString(),
			isRenderingShadow,
			isMainBound,
			isRenderingWorld,
			isRenderingFullScreenPass,
			isPostChain,
			GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM),
			getActivePassProgramId()
		);
		current = null;
	}

	/** Restores the current Iris pass after a foreign renderer has returned control. */
	public void restorePassAfterModProgram(int newProgram) {
		if (refreshingPass || drivingProgram || !modProgramOverrode) {
			return;
		}
		if (newProgram != 0 && newProgram != programBeforeModOverride) {
			return;
		}
		restorePassAfterForeignDraw();
	}

	/** Reapplies the active Iris pass after a renderer that may have changed raw GL state. */
	public void restorePassAfterForeignDraw() {
		if (refreshingPass || drivingProgram
			|| !isRenderingWorld
			|| isRenderingFullScreenPass
			|| isPostChain
			|| (!isMainBound && !isRenderingShadow)) {
			return;
		}

		refreshingPass = true;
		try {
			if (current != null) {
				drivingProgram = true;
				try {
					current.use();
				} finally {
					drivingProgram = false;
				}
			} else {
				matchPass();
			}
		} finally {
			modProgramOverrode = false;
			programBeforeModOverride = -1;
			refreshingPass = false;
		}
	}

	/** Re-evaluates entity translucency after vanilla changes blend state. */
	public void onVanillaBlendChanged() {
		if (matchingBlend || drivingProgram || refreshingPass) {
			return;
		}

		final WorldRenderingPhase activePhase = getPhase();
		if (activePhase != WorldRenderingPhase.ENTITIES && activePhase != WorldRenderingPhase.BLOCK_ENTITIES) {
			return;
		}

		matchingBlend = true;
		try {
			matchPass();
		} finally {
			matchingBlend = false;
		}
	}

	private void matchPass() {
		if (!isRenderingWorld || isRenderingFullScreenPass || isPostChain || (!isMainBound && !isRenderingShadow)) {
            IrisGlDebug.logPipelineSkip(
                "match-pass-skip",
                getPhase().name(),
                isRenderingShadow,
                isMainBound,
                isRenderingWorld,
                isRenderingFullScreenPass,
                isPostChain);
			return;
		}
		
		final RenderCondition condition = getCondition(getPhase());
		final Pass matched = table.match(condition, inputs);
		beginPass(matched);
        IrisGlDebug.logPipelineMatch(
                "match-pass",
                getPhase().name(),
                condition.name(),
                isRenderingShadow,
                isMainBound,
                isRenderingWorld,
                isRenderingFullScreenPass,
                isPostChain,
                matched != null && matched.getProgram() != null ? matched.getProgram().getProgramId() : -1);
	}

	public void beginPass(Pass pass) {
        WorldRenderingPhase activePhase = getPhase();
        int previousProgram = getActivePassProgramId();
		int nextProgram = pass != null && pass.getProgram() != null ? pass.getProgram().getProgramId() : 0;

		if (current == pass) {
			int currentProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
			if (currentProgram != nextProgram) {
				drivingProgram = true;
				try {
					if (pass != null) {
						pass.use();
					} else {
						Program.unbind();
					}
				} finally {
					drivingProgram = false;
				}
				if (activePhase == WorldRenderingPhase.ENTITIES || activePhase == WorldRenderingPhase.BLOCK_ENTITIES || isRenderingShadow) {
					IrisGlDebug.logPassBind("begin-pass-rebind", activePhase.name(), currentProgram, nextProgram);
				}
				return;
			}
            if (activePhase == WorldRenderingPhase.ENTITIES || activePhase == WorldRenderingPhase.BLOCK_ENTITIES || isRenderingShadow) {
                IrisGlDebug.logPassBind("begin-pass-reuse", activePhase.name(), previousProgram, nextProgram);
            }
			return;
		}

		drivingProgram = true;
		try {
			if (current != null) {
				current.stopUsing();
			}

			current = pass;

			if (pass != null) {
				pass.use();
			} else {
				Program.unbind();
			}
		} finally {
			drivingProgram = false;
		}

        if (activePhase == WorldRenderingPhase.ENTITIES || activePhase == WorldRenderingPhase.BLOCK_ENTITIES || isRenderingShadow) {
            IrisGlDebug.logPassBind("begin-pass", activePhase.name(), previousProgram, nextProgram);
        }
	}

	@Override
	public void restoreActivePass() {
		drivingProgram = true;
		try {
			if (current != null) {
				current.use();
			} else {
				matchPass();
			}
		} finally {
			drivingProgram = false;
		}
	}

	private Pass createDefaultPass() {
		final GlFramebuffer framebufferBeforeTranslucents = renderTargets.createGbufferFramebuffer(flippedAfterPrepare, new int[] {0});
		final GlFramebuffer framebufferAfterTranslucents = renderTargets.createGbufferFramebuffer(flippedAfterTranslucent, new int[] {0});


		return new Pass(null, framebufferBeforeTranslucents, framebufferAfterTranslucents, null,
			null, Collections.emptyList(), false);
	}

	/**
	 * {@return the program id a condition specialised to {@code availability} renders with}
	 *
	 * <p>The table holds one id per condition and per input combination, and a null slot falls back to the
	 * unconditioned id of the same column.</p>
	 */
	static ProgramId gbufferProgramId(ProgramId[] ids, RenderCondition condition, InputAvailability availability) {
		final int idx;

		if (availability.texture && availability.lightmap) {
			idx = 2;
		} else if (availability.texture) {
			idx = 1;
		} else {
			idx = 0;
		}

		final ProgramId id = ids[condition.ordinal() * 3 + idx];
		return id != null ? id : ids[idx];
	}

	/**
	 * Identifies the program a pass is built from: the stage sources it is linked from, and the inputs the
	 * attributes and the bindings are wired for. Two passes with equal keys would link identical programs,
	 * so the second one reuses the first one's instead of compiling and linking its own copy.
	 *
	 * @param sourceName the name of the {@link ProgramSource} the stages come from
	 * @param transformAvailability the inputs the attribute transform produced the stage sources for
	 * @param availability the inputs the pass declares, which decide whether the lightmap level, the world
	 *                     depth and the shadow samplers are bound
	 * @param shadow whether the pass renders into the shadow targets, which replaces the world depth binding
	 *               with the shadow one and therefore yields a different program
	 */
	record SharedProgramKey(String sourceName, InputAvailability transformAvailability,
							InputAvailability availability, boolean shadow) {

		/**
		 * {@return the key this pass may share a program under, or null when it has to build its own}
		 *
		 * <p>Only a pass whose transform was computed ahead of the build shares. A pass on the synchronous
		 * fallback path is outside the batch the warmup submitted, so it builds its own program instead of
		 * joining a table that describes precomputed transforms.</p>
		 *
		 * @param transformed the transformed stages this pass is built from, or null when it has to
		 *                    transform them itself
		 */
		@Nullable
		static SharedProgramKey of(@Nullable Map<PatchShaderType, String> transformed, String sourceName,
								   InputAvailability transformAvailability, InputAvailability availability, boolean shadow) {
			return transformed == null
				? null
				: new SharedProgramKey(sourceName, transformAvailability, availability, shadow);
		}
	}

	/**
	 * A program this build has already linked, together with the pass its uniforms were wired with.
	 *
	 * <p>{@code firstPass} owns the uniform locations the program was assigned. Further passes reusing the
	 * program are pointed at those locations instead of registering the same uniforms a second time.</p>
	 */
	record BuiltProgram(Program program, Pass firstPass) {}

	/**
	 * The programs one pipeline build has already linked, keyed by what a pass is built from.
	 *
	 * <p>Several rendering conditions resolve to the same source and inputs, and without this table each of
	 * them would compile and link an identical program. The table is created in the constructor, so sharing
	 * is confined to one build and no program reference outlives the pipeline that linked it; reusing
	 * programs across pipelines is the job of {@link net.coderbot.iris.gl.program.RetainedPrograms}.</p>
	 *
	 * <p>The value type is left open so that the hit-or-miss policy can be exercised without a rendering
	 * context, which the linked programs themselves require.</p>
	 */
	static final class SharedPrograms<T> {
		private final Map<SharedProgramKey, T> built = new HashMap<>();

		/**
		 * {@return the program an earlier pass of this build linked for {@code key}, or null}
		 *
		 * <p>A miss is the caller's signal that it has to build; a hit means it must not create a program.</p>
		 */
		@Nullable
		T find(@Nullable SharedProgramKey key) {
			return key != null ? built.get(key) : null;
		}

		/** Records a program that was just linked. A pass without a key is never shared, so it is ignored. */
		void record(@Nullable SharedProgramKey key, T program) {
			if (key != null) {
				built.put(key, program);
			}
		}
	}

	private Pass createPass(ProgramSource source, InputAvailability availability, boolean shadow, ProgramId id,
							SharedPrograms<BuiltProgram> builtPrograms) {
		// Use pre-computed transform if available, otherwise transform synchronously
		InputAvailability transformAvailability = TransformInputs.of(id, availability);
		Pair<String, InputAvailability> key = Pair.of(source.getName(), transformAvailability);
		Map<PatchShaderType, String> transformed = attributeTransforms.get(key);

		// Deliberately not the program id: several conditions resolving to the same source and inputs would
		// otherwise each compile and link their own copy.
		final SharedProgramKey shareKey = SharedProgramKey.of(transformed, source.getName(), transformAvailability,
			availability, shadow);
		final BuiltProgram shared = builtPrograms.find(shareKey);

		if (shared != null) {
			// Same stages and inputs, so the same uniform locations and sampler assignments. Only the state
			// this pass re-derives from its directives and id can differ from the pass that built it.
			final Pass pass = createPassInner(shared.program(), source.getDirectives(), shadow, id);
			this.customUniforms.mapPassLike(shared.firstPass(), pass);
			return pass;
		}

		if (transformed == null) {
			// Fallback to synchronous transform if not pre-computed
			transformed = TransformPatcher.patchAttributes(
				source.getVertexSource().orElseThrow(NullPointerException::new),
				source.getGeometrySource().orElse(null),
				source.getTessControlSource().orElse(null),
				source.getTessEvalSource().orElse(null),
				source.getFragmentSource().orElseThrow(NullPointerException::new),
				transformAvailability);
		}

		String vertex = transformed.get(PatchShaderType.VERTEX);
		String geometry = transformed.get(PatchShaderType.GEOMETRY);
		String tessControl = transformed.get(PatchShaderType.TESS_CONTROL);
		String tessEval = transformed.get(PatchShaderType.TESS_EVAL);
		String fragment = transformed.get(PatchShaderType.FRAGMENT);

		PatchedShaderPrinter.debugPatchedShaders(source.getName(), vertex, geometry, tessControl, tessEval, fragment);

		try (ProgramBuilder builder = ProgramBuilder.begin(source.getName(), vertex, geometry, tessControl, tessEval, fragment,
			IrisSamplers.WORLD_RESERVED_TEXTURE_UNITS)) {
			final Program program = wireGbufferProgram(builder, availability, shadow);
			final Pass pass = createPassInner(program, source.getDirectives(), shadow, id);
			this.customUniforms.mapholderToPass(builder, pass);
			builtPrograms.record(shareKey, new BuiltProgram(program, pass));
			builder.commit();
			return pass;
		}
	}

	/**
	 * Wires the gbuffer uniforms, samplers and images into the program being built, then builds it.
	 *
	 * <p>Everything wired here belongs to the program rather than to a pass, which is what makes the program
	 * reusable: a second pass for the same program must not wire it again, or its uniforms would be
	 * registered twice on the same program object.</p>
	 */
	private Program wireGbufferProgram(ProgramBuilder builder, InputAvailability availability, boolean shadow) {
		CommonUniforms.addDynamicUniforms(builder, FogMode.PER_VERTEX);
        this.customUniforms.assignTo(builder);

		Supplier<ImmutableSet<Integer>> flipped;

		if (shadow) {
			flipped = () -> ((shouldRenderPrepareBeforeShadow || hasRenderedPreparePass) ? flippedAfterPrepare : flippedBeforeShadow);
		} else {
			flipped = () -> isBeforeTranslucent ? flippedAfterPrepare : flippedAfterTranslucent;
		}

		TextureStage textureStage = TextureStage.GBUFFERS_AND_SHADOW;

		ProgramSamplers.CustomTextureSamplerInterceptor customTextureSamplerInterceptor =
			ProgramSamplers.customTextureSamplerInterceptor(builder,
				customTextureManager.getCustomTextureIdMap(textureStage));

		IrisSamplers.addRenderTargetSamplers(customTextureSamplerInterceptor, flipped, renderTargets, false, this);
		IrisImages.addRenderTargetImages(builder, flipped, renderTargets);

		if (!shouldBindPBR) {
			shouldBindPBR = IrisSamplers.hasPBRSamplers(customTextureSamplerInterceptor);
		}

		IrisSamplers.addLevelSamplers(customTextureSamplerInterceptor, this, whitePixel, availability);

		if (!shadow) {
			IrisSamplers.addWorldDepthSamplers(customTextureSamplerInterceptor, renderTargets);
		}

		IrisSamplers.addNoiseSampler(customTextureSamplerInterceptor, customTextureManager.getNoiseTexture());

		IrisSamplers.addCustomImages(customTextureSamplerInterceptor, customImages);
		IrisSamplers.addCustomTextures(customTextureSamplerInterceptor, customTextureManager.getIrisCustomTextures());
		IrisImages.addCustomImages(builder, customImages);

		if (IrisSamplers.hasShadowSamplers(customTextureSamplerInterceptor)) {
			if (!shadow) {
				shadowTargetsSupplier.get();
			}

			if (shadowRenderTargets != null) {
				IrisSamplers.addShadowSamplers(customTextureSamplerInterceptor, shadowRenderTargets, null, true);
				IrisImages.addShadowColorImages(builder, shadowRenderTargets, null);
			}
		}

        final Program program = builder.build();
        IrisGlDebug.logProgramSamplerState("create-pass", program.getProgramId(), availability.toString(), getPhase().name());

		return program;
	}

	/**
	 * Creates the pass for an already built program: the pass owns the framebuffers it renders into, the alpha
	 * test it applies and the blend state it overrides.
	 *
	 * <p>All of that is derived from the directives and the program id, so two passes that share a program can
	 * still differ in every one of them.</p>
	 */
	private Pass createPassInner(Program program, ProgramDirectives programDirectives, boolean shadow, ProgramId id) {
		GlFramebuffer framebufferBeforeTranslucents;
		GlFramebuffer framebufferAfterTranslucents;

		if (shadow) {
			// Always add both draw buffers on the shadow pass.
			framebufferBeforeTranslucents = shadowTargetsSupplier.get().createShadowFramebuffer(shadowRenderTargets.snapshot(), new int[] { 0, 1 });
			framebufferAfterTranslucents = framebufferBeforeTranslucents;
		} else {
			framebufferBeforeTranslucents = renderTargets.createGbufferFramebuffer(flippedAfterPrepare, programDirectives.getDrawBuffers());
			framebufferAfterTranslucents = renderTargets.createGbufferFramebuffer(flippedAfterTranslucent, programDirectives.getDrawBuffers());
		}

		AlphaTestOverride alphaTestOverride = programDirectives.getAlphaTestOverride()
			.orElse(id.getDefaultAlphaTestOverride());

		List<BufferBlendOverride> bufferOverrides = new ArrayList<>();

		programDirectives.getBufferBlendOverrides().forEach(information -> {
			int index = Ints.indexOf(programDirectives.getDrawBuffers(), information.getIndex());
			if (index > -1) {
				bufferOverrides.add(new BufferBlendOverride(index, information.getBlendMode()));
			}
		});

        return new Pass(program, framebufferBeforeTranslucents, framebufferAfterTranslucents, alphaTestOverride,
            programDirectives.getBlendModeOverride().orElse(id.getBlendModeOverride()), bufferOverrides, shadow);
	}

	public void addGbufferOrShadowSamplers(SamplerHolder samplers, ImageHolder images, Supplier<ImmutableSet<Integer>> flipped,
										   boolean isShadowPass, boolean hasTexture, boolean hasLightmap, boolean hasOverlay) {
		TextureStage textureStage = TextureStage.GBUFFERS_AND_SHADOW;

		ProgramSamplers.CustomTextureSamplerInterceptor customTextureSamplerInterceptor =
			ProgramSamplers.customTextureSamplerInterceptor(samplers, customTextureManager.getCustomTextureIdMap(textureStage));

		IrisSamplers.addRenderTargetSamplers(customTextureSamplerInterceptor, flipped, renderTargets, false, this);
		IrisImages.addRenderTargetImages(images, flipped, renderTargets);

		if (!shouldBindPBR) {
			shouldBindPBR = IrisSamplers.hasPBRSamplers(customTextureSamplerInterceptor);
		}

		IrisSamplers.addLevelSamplers(customTextureSamplerInterceptor, this, whitePixel, new InputAvailability(hasTexture, hasLightmap));
		if (!isShadowPass) {
			IrisSamplers.addWorldDepthSamplers(customTextureSamplerInterceptor, renderTargets);
		}
		IrisSamplers.addNoiseSampler(customTextureSamplerInterceptor, customTextureManager.getNoiseTexture());
		IrisSamplers.addCustomImages(customTextureSamplerInterceptor, customImages);
		IrisSamplers.addCustomTextures(customTextureSamplerInterceptor, customTextureManager.getIrisCustomTextures());
		IrisImages.addCustomImages(images, customImages);

		if (IrisSamplers.hasShadowSamplers(customTextureSamplerInterceptor)) {
			IrisSamplers.addShadowSamplers(customTextureSamplerInterceptor, shadowTargetsSupplier.get(), null, true);
		}

		if (isShadowPass || IrisImages.hasShadowImages(images)) {
			IrisImages.addShadowColorImages(images, shadowTargetsSupplier.get(), null);
		}
	}

	private boolean isPostChain;
	private boolean isMainBound = true;

	@Override
	public void beginPostChain() {
		isPostChain = true;

		beginPass(null);
	}

	@Override
	public void endPostChain() {
		isPostChain = false;
	}

	@Override
	public void setIsMainBound(boolean bound) {
		isMainBound = bound;

		if (!isRenderingWorld || isRenderingFullScreenPass || isPostChain) {
			return;
		}

		if (bound) {
			// force refresh
			current = null;
		} else {
			beginPass(null);
		}
	}

	public CloudSetting getDHCloudSetting() {
		return dhCloudSetting;
	}

	public Optional<ProgramSource> getDHTerrainShader() {
		return resolver.resolve(ProgramId.DhTerrain);
	}

	public Optional<ProgramSource> getDHGenericShader() {
		return resolver.resolve(ProgramId.DhGeneric);
	}

	public Optional<ProgramSource> getDHWaterShader() {
		return resolver.resolve(ProgramId.DhWater);
	}

	public Optional<ProgramSource> getDHShadowShader() {
		return resolver.resolve(ProgramId.DhShadow);
	}

	public CustomUniforms getCustomUniforms() {
		return customUniforms;
	}

	public GlFramebuffer createDHFramebuffer(ProgramSource sources, boolean trans) {
		return renderTargets.createDHFramebuffer(trans ? flippedAfterTranslucent : flippedAfterPrepare,
			sources.getDirectives().getDrawBuffers());
	}

	public ImmutableSet<Integer> getFlippedBeforeShadow() {
		return flippedBeforeShadow;
	}

	public ImmutableSet<Integer> getFlippedAfterPrepare() {
		return flippedAfterPrepare;
	}

	public ImmutableSet<Integer> getFlippedAfterTranslucent() {
		return flippedAfterTranslucent;
	}

	public GlFramebuffer createDHFramebufferShadow(ProgramSource sources) {

		return shadowRenderTargets.createDHFramebuffer(ImmutableSet.of(), new int[]{0, 1});
	}

	public boolean hasShadowRenderTargets() {
		return shadowRenderTargets != null;
	}

	private final class Pass {
		@Nullable
		private final Program program;
		private final GlFramebuffer framebufferBeforeTranslucents;
		private final GlFramebuffer framebufferAfterTranslucents;
		@Nullable
		private final AlphaTestOverride alphaTestOverride;
		@Nullable
		private final BlendModeOverride blendModeOverride;
		@Nullable
		private final List<BufferBlendOverride> bufferBlendOverrides;
		private final boolean shadowViewport;

		private Pass(@Nullable Program program, GlFramebuffer framebufferBeforeTranslucents, GlFramebuffer framebufferAfterTranslucents,
					 @Nullable AlphaTestOverride alphaTestOverride, @Nullable BlendModeOverride blendModeOverride, @Nullable List<BufferBlendOverride> bufferBlendOverrides, boolean shadowViewport) {
			this.program = program;
			this.framebufferBeforeTranslucents = framebufferBeforeTranslucents;
			this.framebufferAfterTranslucents = framebufferAfterTranslucents;
			this.alphaTestOverride = alphaTestOverride;
			this.blendModeOverride = blendModeOverride;
			this.bufferBlendOverrides = bufferBlendOverrides;
			this.shadowViewport = shadowViewport;
		}

		public void use() {
			DepthColorStorage.unlockDepthColor();

			if (isBeforeTranslucent) {
				framebufferBeforeTranslucents.bind();
			} else {
				framebufferAfterTranslucents.bind();
			}

			if (shadowViewport) {
				GLStateManager.glViewport(0, 0, shadowMapResolution, shadowMapResolution);
			} else {
                final Framebuffer main = Minecraft.getMinecraft().getFramebuffer();
				GLStateManager.glViewport(0, 0, main.framebufferWidth, main.framebufferHeight);
			}

			// Apply state overrides before program.use() so that uniforms (e.g. iris_currentAlphaTest)
			// read the correct GLSM state during upload, not the stale vanilla state.
			if (alphaTestOverride != null) {
				alphaTestOverride.apply();
			} else {
				// Previous program on the stack might have applied an override
				AlphaTestOverride.restore();
			}

			if (blendModeOverride != null) {
				blendModeOverride.apply();
			} else {
				// Previous program on the stack might have applied an override
				BlendModeOverride.restore();
			}

			if (bufferBlendOverrides != null && !bufferBlendOverrides.isEmpty()) {
				bufferBlendOverrides.forEach(BufferBlendOverride::apply);
			}

			if (program != null) {
				program.use();
			} else {
				Program.unbind();
			}

			DeferredWorldRenderingPipeline.this.customUniforms.push(this);
		}

		public void stopUsing() {
			DepthColorStorage.unlockDepthColor();

			if (alphaTestOverride != null) {
				AlphaTestOverride.restore();
			}

			if (blendModeOverride != null || (bufferBlendOverrides != null && !bufferBlendOverrides.isEmpty())) {
				BlendModeOverride.restore();
			}
		}

		@Nullable
		public Program getProgram() {
			return program;
		}

		public void destroy() {
			if (this.program != null) {
				this.program.destroy();
			}
		}
	}

	@Override
	public void destroy() {
		DepthColorStorage.unlockDepthColor();
		BlendModeOverride.restore();
		AlphaTestOverride.restore();

		FullScreenQuadRenderer.clearLocCache();
		// A constructor that failed partway calls this method to release what it did create, so every field
		// it may not have reached is guarded here.
		if (table != null) {
			destroyPasses(table);
		}

		// Destroy the composite rendering pipeline
		//
		// This destroys all the loaded composite programs as well.
		if (beginRenderer != null) {
			beginRenderer.destroy();
		}
		if (prepareRenderer != null) {
			prepareRenderer.destroy();
		}
		if (compositeRenderer != null) {
			compositeRenderer.destroy();
		}
		if (deferredRenderer != null) {
			deferredRenderer.destroy();
		}
		if (finalPassRenderer != null) {
			finalPassRenderer.destroy();
		}
		if (centerDepthSampler != null) {
			centerDepthSampler.destroy();
		}
        closeFinalizeTimer(finalizeOutputTimer);
        closeFinalizeTimer(compositeOutputTimer);
        closeFinalizeTimer(finalOutputTimer);
        finalizeOutputTimer = null;
        compositeOutputTimer = null;
        finalOutputTimer = null;

		// Destroy setup compute programs
		if (setup != null) {
			for (ComputeProgram compute : setup) {
				if (compute != null) {
					compute.destroy();
				}
			}
		}

		// Destroy shadow compute programs
		if (shadowComputes != null) {
			for (ComputeProgram compute : shadowComputes) {
				if (compute != null) {
					compute.destroy();
				}
			}
		}

		// Destroy shadow composite renderer
		if (shadowCompositeRenderer != null) {
			shadowCompositeRenderer.destroy();
		}

		horizonRenderer.destroy();

		// Make sure that any custom framebuffers are not bound before destroying render targets
		GLStateManager.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
		GLStateManager.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
		GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

		// Destroy our render targets
		//
		// While it's possible to just clear them instead and reuse them, we'd need to investigate whether or not this
		// would help performance.
		if (renderTargets != null) {
			renderTargets.destroy();
		}
		if (dhCompat != null) {
			dhCompat.clearPipeline();
		}

		// destroy the shadow render targets
		if (shadowRenderTargets != null) {
			shadowRenderTargets.destroy();
		}

		// Destroy custom textures and the static samplers (normals, specular, and noise)
		if (customTextureManager != null) {
			customTextureManager.destroy();
		}
		if (whitePixel != null) {
			whitePixel.deleteGlTexture();
		}

		// Destroy custom images
		if (customImages != null) {
			for (GlImage image : customImages) {
				image.destroy();
			}
			customImages.clear();
		}

		// Destroy SSBOs
		if (adaptiveShadowBoundsStats != null) {
			AdaptiveShadowBoundsStats.deactivate(adaptiveShadowBoundsStats);
			adaptiveShadowBoundsStats.destroy();
		}
		if (ssboHolder != null) {
			ssboHolder.destroyBuffers();
		}

        MinecraftFramebufferHelper.restoreMainFramebuffer(false);
	}

	private static void destroyPasses(ProgramTable<Pass> table) {
		Set<Pass> destroyed = new HashSet<>();

		table.forEach(pass -> {
			if (pass == null) {
				return;
			}

			if (destroyed.contains(pass)) {
				return;
			}

			pass.destroy();
			destroyed.add(pass);
		});
	}

	private void prepareRenderTargets() {
		// Make sure we're using texture unit 0 for this.
		GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);
		final Vector4f emptyClearColor = new Vector4f(1.0F);

		if (shadowRenderTargets != null) {
			if (packDirectives.getShadowDirectives().isShadowEnabled() == OptionalBoolean.FALSE) {
				if (shadowRenderTargets.isFullClearRequired()) {
					shadowRenderTargets.onFullClear();
					for (ClearPass clearPass : shadowClearPassesFull) {
						clearPass.execute(emptyClearColor);
					}
				}
			} else {
				// Clear depth first, regardless of any color clearing.
				shadowRenderTargets.getDepthSourceFb().bind();
                GLStateManager.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                if (Minecraft.IS_RUNNING_ON_MAC) {
                    GLStateManager.glGetError();
                }


				ImmutableList<ClearPass> passes;

				for (ComputeProgram computeProgram : shadowComputes) {
					if (computeProgram != null) {
                        computeProgram.use();
                        this.customUniforms.push(computeProgram);
						computeProgram.dispatch(shadowMapResolution, shadowMapResolution);
					}
				}

				if (shadowRenderTargets.isFullClearRequired()) {
					passes = shadowClearPassesFull;
					shadowRenderTargets.onFullClear();
				} else {
					passes = shadowClearPasses;
				}

				for (ClearPass clearPass : passes) {
					clearPass.execute(emptyClearColor);
				}
			}
		}

        final Framebuffer main = Minecraft.getMinecraft().getFramebuffer();

        final int depthTextureId = ((IRenderTargetExt)main).iris$getDepthTextureId();
		final int internalFormat = TextureInfoCache.INSTANCE.getInfo(depthTextureId).getInternalFormat();
		final DepthBufferFormat depthBufferFormat = DepthBufferFormat.fromGlEnumOrDefault(internalFormat);

		final boolean changed = renderTargets.resizeIfNeeded(((IRenderTargetExt)main).iris$getDepthBufferVersion(), depthTextureId, main.framebufferWidth,
            main.framebufferHeight, depthBufferFormat, packDirectives);

		if (changed) {
			beginRenderer.recalculateSizes();
			prepareRenderer.recalculateSizes();
			deferredRenderer.recalculateSizes();
			compositeRenderer.recalculateSizes();
			finalPassRenderer.recalculateSwapPassSize();

			this.clearPassesFull.forEach(clearPass -> renderTargets.destroyFramebuffer(clearPass.getFramebuffer()));
			this.clearPasses.forEach(clearPass -> renderTargets.destroyFramebuffer(clearPass.getFramebuffer()));

			this.clearPassesFull = ClearPassCreator.createClearPasses(renderTargets, true, packDirectives.getRenderTargetDirectives());
			this.clearPasses = ClearPassCreator.createClearPasses(renderTargets, false, packDirectives.getRenderTargetDirectives());

			// Resize custom images if needed
			for (GlImage image : customImages) {
				image.updateNewSize(main.framebufferWidth, main.framebufferHeight);
			}

			// Resize SSBOs if needed
			if (ssboHolder != null) {
				ssboHolder.hasResizedScreen(main.framebufferWidth, main.framebufferHeight);
			}

			// Re-run setup computes after resize (SETUP dispatches with 1,1 workgroups)
			boolean ranSetup = false;
			for (ComputeProgram program : setup) {
				if (program != null) {
					ranSetup = true;
					program.use();
					customUniforms.push(program);
					program.dispatch(1, 1);
				}
			}
			if (ranSetup) {
				ComputeProgram.unbind();
				RenderSystem.memoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT);
			}
		}

		final ImmutableList<ClearPass> passes;

		if (renderTargets.isFullClearRequired()) {
			renderTargets.onFullClear();
			passes = clearPassesFull;
		} else {
			passes = clearPasses;
		}

		final Vector3d fogColor3 = GLStateManager.getFogColor();

		// NB: The alpha value must be 1.0 here, or else you will get a bunch of bugs. Sildur's Vibrant Shaders
		//     will give you pink reflections and other weirdness if this is zero.
        final Vector4f fogColor = new Vector4f((float) fogColor3.x, (float) fogColor3.y, (float) fogColor3.z, 1.0F);

		for (ClearPass clearPass : passes) {
			clearPass.execute(fogColor);
		}

		// Reset framebuffer and viewport
        MinecraftFramebufferHelper.restoreMainFramebuffer(true);
	}

	private ComputeProgram[] createShadowComputes(ComputeSource[] compute) {
		ComputeProgram[] programs = new ComputeProgram[compute.length];
		try {
			for (int i = 0; i < programs.length; i++) {
				ComputeSource source = compute[i];
				if (source == null || !source.getSource().isPresent()) {
					continue;
				}

				try {
					String transformed = TransformPatcher.patchCompute(source.getName(), source.getSource().orElse(null), TextureStage.GBUFFERS_AND_SHADOW, getTextureMap());
					PatchedShaderPrinter.debugPatchedShaders(source.getName() + "_compute", null, null, null, transformed);
					try (ProgramBuilder builder = ProgramBuilder.beginCompute(source.getName(), transformed, IrisSamplers.WORLD_RESERVED_TEXTURE_UNITS)) {
						CommonUniforms.addDynamicUniforms(builder, FogMode.PER_VERTEX);
						this.customUniforms.assignTo(builder);

						Supplier<ImmutableSet<Integer>> flipped = () -> flippedBeforeShadow;
						TextureStage textureStage = TextureStage.GBUFFERS_AND_SHADOW;
						ProgramSamplers.CustomTextureSamplerInterceptor customTextureSamplerInterceptor =
							ProgramSamplers.customTextureSamplerInterceptor(builder, customTextureManager.getCustomTextureIdMap(textureStage));

						IrisSamplers.addRenderTargetSamplers(customTextureSamplerInterceptor, flipped, renderTargets, false, this);
						IrisImages.addRenderTargetImages(builder, flipped, renderTargets);
						IrisSamplers.addLevelSamplers(customTextureSamplerInterceptor, this, whitePixel, new InputAvailability(true, true));
						IrisSamplers.addNoiseSampler(customTextureSamplerInterceptor, customTextureManager.getNoiseTexture());
						IrisSamplers.addCustomImages(customTextureSamplerInterceptor, customImages);
						IrisSamplers.addCustomTextures(customTextureSamplerInterceptor, customTextureManager.getIrisCustomTextures());

						if (IrisSamplers.hasShadowSamplers(customTextureSamplerInterceptor) && shadowRenderTargets != null) {
							IrisSamplers.addShadowSamplers(customTextureSamplerInterceptor, shadowRenderTargets, null, true);
							IrisImages.addShadowColorImages(builder, shadowRenderTargets, null);
						}

						IrisImages.addCustomImages(builder, customImages);
					final ComputeProgram program = builder.buildCompute();
					this.customUniforms.mapholderToPass(builder, program);
					program.setWorkGroupInfo(source.getWorkGroupRelative(), source.getWorkGroups(), null);
					programs[i] = program;
					builder.commit();
				}
				} catch (RuntimeException e) {
					throw new RuntimeException("Shader compilation failed!", e);
				}
			}
			return programs;
		} catch (RuntimeException | Error failure) {
			destroyComputePrograms(programs, failure);
			throw failure;
		}
	}

	private ComputeProgram[] createSetupComputes(ComputeSource[] compute) {
		ComputeProgram[] programs = new ComputeProgram[compute.length];
		try {
			for (int i = 0; i < programs.length; i++) {
				ComputeSource source = compute[i];
				if (source == null || !source.getSource().isPresent()) {
					continue;
				}

				try {
					String transformed = TransformPatcher.patchCompute(source.getName(), source.getSource().orElse(null), TextureStage.SETUP, getTextureMap());
					PatchedShaderPrinter.debugPatchedShaders(source.getName() + "_compute", null, null, null, transformed);
					try (ProgramBuilder builder = ProgramBuilder.beginCompute(source.getName(), transformed, IrisSamplers.COMPOSITE_RESERVED_TEXTURE_UNITS)) {
						CommonUniforms.addDynamicUniforms(builder, FogMode.OFF);
						this.customUniforms.assignTo(builder);

						ImmutableSet<Integer> empty = ImmutableSet.of();
						Supplier<ImmutableSet<Integer>> flipped = () -> empty;
						ProgramSamplers.CustomTextureSamplerInterceptor customTextureSamplerInterceptor =
							ProgramSamplers.customTextureSamplerInterceptor(builder, customTextureManager.getCustomTextureIdMap(TextureStage.SETUP));

						IrisSamplers.addRenderTargetSamplers(customTextureSamplerInterceptor, flipped, renderTargets, true, this);
						IrisSamplers.addCustomImages(customTextureSamplerInterceptor, customImages);
						IrisSamplers.addCustomTextures(customTextureSamplerInterceptor, customTextureManager.getIrisCustomTextures());

						IrisImages.addRenderTargetImages(builder, flipped, renderTargets);
						IrisImages.addCustomImages(builder, customImages);
						IrisSamplers.addNoiseSampler(customTextureSamplerInterceptor, customTextureManager.getNoiseTexture());
						IrisSamplers.addCompositeSamplers(customTextureSamplerInterceptor, renderTargets);

						if (IrisSamplers.hasShadowSamplers(customTextureSamplerInterceptor) && shadowRenderTargets != null) {
							IrisSamplers.addShadowSamplers(customTextureSamplerInterceptor, shadowRenderTargets, null, true);
							IrisImages.addShadowColorImages(builder, shadowRenderTargets, null);
						}

						final ComputeProgram program = builder.buildCompute();
					this.customUniforms.mapholderToPass(builder, program);
					program.setWorkGroupInfo(source.getWorkGroupRelative(), source.getWorkGroups(), null);
					programs[i] = program;
					builder.commit();
				}
				} catch (RuntimeException e) {
					throw new RuntimeException("Shader compilation failed for setup compute " + source.getName() + "!", e);
				}
			}
			return programs;
		} catch (RuntimeException | Error failure) {
			destroyComputePrograms(programs, failure);
			throw failure;
		}
	}

	private static void destroyComputePrograms(ComputeProgram[] programs, Throwable failure) {
		for (ComputeProgram program : programs) {
			if (program == null) {
				continue;
			}
			try {
				program.destroy();
			} catch (RuntimeException | Error cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
		}
	}

	@Override
	public void beginHand() {
        IrisGlDebug.check("level:begin-hand:start");
		// We need to copy the current depth texture so that depthtex2 can contain the depth values for
		// all non-translucent content without the hand, as required.
		renderTargets.copyPreHandDepth();
        IrisGlDebug.check("level:begin-hand:copy-depth");
	}

	@Override
	public void beginTranslucents() {
        IrisGlDebug.check("level:begin-translucents:start");
		isBeforeTranslucent = false;

		// We need to copy the current depth texture so that depthtex1 can contain the depth values for
		// all non-translucent content, as required.
		renderTargets.copyPreTranslucentDepth();
        IrisGlDebug.check("level:begin-translucents:copy-depth");


		// needed to remove blend mode overrides and similar
		beginPass(null);
        IrisGlDebug.check("level:begin-translucents:begin-pass-null");

		isRenderingFullScreenPass = true;

        IrisGlDebug.beginFramebufferSamplePhase("deferred-after-terrain");
		deferredRenderer.renderAll();
        IrisGlDebug.endFramebufferSamplePhase();
		IrisGlDebug.check("level:begin-translucents:deferred");

		GLStateManager.enableBlend();
		GLStateManager.enableAlphaTest();

		// note: we are careful not to touch the lightmap texture unit or overlay color texture unit here,
		// so we don't need to do anything to restore them if needed.
		//
		// Previous versions of the code tried to "restore" things by enabling the lightmap & overlay color
		// but that actually broke rendering of clouds and rain by making them appear red in the case of
		// a pack not overriding those shader programs.
		//
		// Not good!

		isRenderingFullScreenPass = false;
	}

	@Override
	public void renderShadows(EntityRenderer levelRenderer, Camera playerCamera) {
        IrisGlDebug.check("level:shadows:start");
		if (shouldRenderPrepareBeforeShadow && !hasRenderedPreparePass) {
			renderPreparePass("level:shadows:prepare-before");
		}

		if (shadowRenderer != null) {
			isRenderingShadow = true;
			matchPass();  // Ensure shadow shader is bound for entity rendering
            IrisGlDebug.check("level:shadows:match-pass");

			shadowRenderer.renderShadows(levelRenderer, playerCamera);
            IrisGlDebug.check("level:shadows:render");

			// needed to remove blend mode overrides and similar
			beginPass(null);
            IrisGlDebug.check("level:shadows:begin-pass-null");
			isRenderingShadow = false;
		}

		if (!shouldRenderPrepareBeforeShadow && !hasRenderedPreparePass) {
			renderPreparePass("level:shadows:prepare-after");
		}
        IrisGlDebug.check("level:shadows:end");
	}

	@Override
	public void renderPreSkyPrepare() {
		renderPreparePass("level:pre-sky-prepare");
	}

	private void renderPreparePass(String debugStage) {
		if (hasRenderedPreparePass) {
			return;
		}

		isRenderingFullScreenPass = true;
		prepareRenderer.renderAll();
		IrisGlDebug.check(debugStage);
		isRenderingFullScreenPass = false;
		hasRenderedPreparePass = true;
	}

	@Override
	public void addDebugText(List<String> messages) {
		if (shadowRenderer != null) {
			shadowRenderer.addDebugText(messages);
		} else {
			messages.add("[" + Iris.MODNAME + "] Shadow Maps: not used by shader pack");
		}
	}

	@Override
	public OptionalInt getForcedShadowRenderDistanceChunksForDisplay() {
		return forcedShadowRenderDistanceChunks;
	}

	// TODO: better way to avoid this global state?
	private boolean isRenderingWorld = false;
	private boolean isRenderingFullScreenPass = false;
	private boolean hasRenderedPreparePass = false;

	@Override
	public void beginLevelRendering() {
        IrisGlDebug.markStage("level:begin");
		isRenderingFullScreenPass = false;
		hasRenderedPreparePass = false;
		isRenderingWorld = true;
		isBeforeTranslucent = true;
		isMainBound = true;
		isPostChain = false;
		phase = WorldRenderingPhase.NONE;
		overridePhase = null;
//		HandRenderer.INSTANCE.getBufferSource().resetDrawCalls();

		checkWorld();

		WorldTimeUniforms.snapshot();

		if (!isRenderingWorld) {
			Iris.logger.warn("beginWorldRender was called but we are not currently rendering a world?");
			return;
		}

		if (current != null) {
			throw new IllegalStateException("Called beginLevelRendering but level rendering appears to still be in progress?");
		}

		updateNotifier.onNewFrame();

        this.customUniforms.update();

		// Get ready for world rendering
		prepareRenderTargets();

		// Clear custom images that need clearing each frame
		for (GlImage image : imagesToClear) {
			image.clear();
		}

		isRenderingFullScreenPass = true;
		beginRenderer.renderAll();
		isRenderingFullScreenPass = false;

		setPhase(WorldRenderingPhase.SKY);

		// Render our horizon box before actual sky rendering to avoid being broken by mods that do weird things
		// while rendering the sky.
		//
		// A lot of dimension mods touch sky rendering, FabricSkyboxes injects at HEAD and cancels, etc.
//		DimensionSpecialEffects.SkyType skyType = Minecraft.getMinecraft().theWorld.effects().skyType();

		if (true/*skyType == DimensionSpecialEffects.SkyType.NORMAL*/) {
            GLStateManager.glDisable(GL11.GL_TEXTURE_2D);
			GLStateManager.glDepthMask(false);

			final Vector3d fogColor = GLStateManager.getFogColor();
            GLStateManager.glColor4f((float) fogColor.x, (float) fogColor.y, (float) fogColor.z, 1.0F);

			horizonRenderer.renderHorizon(RenderingState.INSTANCE.getModelViewBuffer());

			GLStateManager.glDepthMask(true);
            GLStateManager.glEnable(GL11.GL_TEXTURE_2D);
		}
	}

	@Override
	public void finalizeLevelRendering() {
        IrisGlDebug.check("level:finalize:start");
		checkWorld();

		if (!isRenderingWorld) {
			Iris.logger.warn("finalizeWorldRendering was called but we are not currently rendering a world?");
			return;
		}

		beginPass(null);
        IrisGlDebug.check("level:finalize:begin-pass-null");

		isRenderingWorld = false;
		phase = WorldRenderingPhase.NONE;
		overridePhase = null;

		isRenderingFullScreenPass = true;

        boolean profileOutput = IrisGlDebug.shouldCaptureGpuPerfTiming();
        if (profileOutput) {
            updateFinalizeTimers();
            finalizeOutputTimer.startProfiling();
        }

        long cpuStart = System.nanoTime();
        IrisGlDebug.check("level:finalize:before-center-depth");
		centerDepthSampler.sampleCenterDepth();
        long cpuAfterCenterDepth = System.nanoTime();
        IrisGlDebug.check("level:finalize:center-depth");

        IrisGlDebug.check("level:finalize:before-composite");
        IrisGlDebug.beginFramebufferSamplePhase("finalize-after-terrain");
        if (profileOutput) {
            compositeOutputTimer.startProfiling();
        }
		compositeRenderer.renderAll();
        if (profileOutput) {
            compositeOutputTimer.finishProfiling();
        }
        long cpuAfterComposite = System.nanoTime();
        IrisGlDebug.check("level:finalize:before-final");
        if (profileOutput) {
            finalOutputTimer.startProfiling();
        }
		finalPassRenderer.renderFinalPass();
        if (profileOutput) {
            finalOutputTimer.finishProfiling();
            finalizeOutputTimer.finishProfiling();
        }
        long cpuAfterFinal = System.nanoTime();
        IrisGlDebug.endFramebufferSamplePhase();
        if (profileOutput) {
            IrisGlDebug.logCompositeOutputTiming(
                cpuAfterFinal - cpuStart,
                cpuAfterCenterDepth - cpuStart,
                cpuAfterComposite - cpuAfterCenterDepth,
                cpuAfterFinal - cpuAfterComposite,
                finalizeOutputTimer.getLastTime(),
                compositeOutputTimer.getLastTime(),
                finalOutputTimer.getLastTime()
            );
        }

		isRenderingFullScreenPass = false;
        IrisGlDebug.check("level:finalized");
	}

    private void updateFinalizeTimers() {
        if (finalizeOutputTimer == null) {
            finalizeOutputTimer = new TimerQueryManager();
            compositeOutputTimer = new TimerQueryManager();
            finalOutputTimer = new TimerQueryManager();
        }

        finalizeOutputTimer.updateTime();
        compositeOutputTimer.updateTime();
        finalOutputTimer.updateTime();
    }

    private static void closeFinalizeTimer(@Nullable TimerQueryManager timer) {
        if (timer != null) {
            timer.close();
        }
    }

	@Override
	public CeleritasTerrainPipeline getCeleritasTerrainPipeline() {
		return celeritasTerrainPipeline;
	}

	@Override
	public FrameUpdateNotifier getFrameUpdateNotifier() {
		return updateNotifier;
	}

	@Override
	public DHCompat getDHCompat() {
		return dhCompat;
	}

	@Override
	public WorldRenderingPhase getPhase() {
		if (overridePhase != null) {
			return overridePhase;
		}

		return phase;
	}

	@Override
	public void setOverridePhase(WorldRenderingPhase phase) {
		this.overridePhase = phase;
		matchPass();
		GbufferPrograms.runPhaseChangeNotifier();
	}

	@Override
	public void setPhase(WorldRenderingPhase phase) {
		WorldRenderingPhase previousPhase = this.phase;
		this.phase = phase;
		IrisGlDebug.logPhaseChange(
			"set-phase",
			previousPhase.name(),
			phase.name(),
			isRenderingShadow,
			isMainBound,
			isRenderingWorld,
			isRenderingFullScreenPass,
			isPostChain,
			inputs.toString()
		);
		matchPass();
		GbufferPrograms.runPhaseChangeNotifier();
	}

	@Override
	public void setInputs(InputAvailability availability) {
		this.inputs = availability;
		IrisGlDebug.logPipelineInputs(
			"set-inputs",
			getPhase().name(),
			availability.toString(),
			isRenderingShadow,
			isMainBound,
			isRenderingFullScreenPass,
			isPostChain
		);
		matchPass();
	}

	@Override
	public void setSpecialCondition(SpecialCondition special) {
		this.special = special;
		matchPass();
	}

	@Override
	public RenderTargetStateListener getRenderTargetStateListener() {
		return this;
	}

	@Override
	public int getCurrentNormalTexture() {
		return currentNormalTexture;
	}

	@Override
	public int getCurrentSpecularTexture() {
		return currentSpecularTexture;
	}

	@Override
	public void onBindTexture(int id) {
		if (shouldBindPBR && isRenderingWorld) {
			final PBRTextureHolder pbrHolder = PBRTextureManager.INSTANCE.getOrLoadHolder(id);
			currentNormalTexture = pbrHolder.getNormalTexture().getGlTextureId();
			currentSpecularTexture = pbrHolder.getSpecularTexture().getGlTextureId();

            final TextureFormat textureFormat = TextureFormatLoader.getFormat();
			if (textureFormat != null) {
				textureFormat.setupTextureParameters(PBRType.NORMAL, pbrHolder.getNormalTexture());
				textureFormat.setupTextureParameters(PBRType.SPECULAR, pbrHolder.getSpecularTexture());
			}

			PBRTextureManager.notifyPBRTexturesChanged();
		}
	}

	private static CompletableFuture<Map<PatchShaderType, String>> submitCompositeTransform(ProgramSource source, TextureStage stage,
		Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap) {
		return Iris.ShaderTransformExecutor.submitTracked(() -> TransformPatcher.patchComposite(
			source.getVertexSource().orElse(null),
			source.getGeometrySource().orElse(null),
			source.getTessControlSource().orElse(null),
			source.getTessEvalSource().orElse(null),
			source.getFragmentSource().orElse(null),
			stage,
			textureMap));
	}

	private static Map<Integer, CompletableFuture<Map<PatchShaderType, String>>> submitCompositeTransforms(ProgramSource[] sources,
		TextureStage stage, Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap) {
		// Count valid sources for initial capacity
		int count = 0;
		for (ProgramSource source : sources) {
			if (source != null && source.isValid()) count++;
		}
		final Map<Integer, CompletableFuture<Map<PatchShaderType, String>>> futures = new HashMap<>(count);
		for (int i = 0; i < sources.length; i++) {
			if (sources[i] != null && sources[i].isValid()) {
				futures.put(i, submitCompositeTransform(sources[i], stage, textureMap));
			}
		}
		return futures;
	}

	private static Map<Pair<String, InputAvailability>, CompletableFuture<Map<PatchShaderType, String>>>
			submitAttributeTransforms(ProgramFallbackResolver resolver) {
		final Map<Pair<String, InputAvailability>, CompletableFuture<Map<PatchShaderType, String>>> futures = new HashMap<>();
		final Set<String> processedSourceNames = new HashSet<>();
		for (ProgramId id : ProgramId.values()) {
			ProgramSource source = resolver.resolveNullable(id);
			if (source != null && !processedSourceNames.contains(source.getName())) {
				processedSourceNames.add(source.getName());
				for (InputAvailability avail : TransformInputs.availabilities()) {
					Pair<String, InputAvailability> key = Pair.of(source.getName(), avail);
					futures.put(key, Iris.ShaderTransformExecutor.submitTracked(() -> TransformPatcher.patchAttributes(source.getVertexSource().orElse(null), source.getGeometrySource().orElse(null), source.getTessControlSource().orElse(null), source.getTessEvalSource().orElse(null), source.getFragmentSource().orElse(null), avail)));
				}
			}
		}
		return futures;
	}

	private static CompletableFuture<Map<PatchShaderType, String>> submitCeleritasTerrainTransform(ProgramSource source) {
		return Iris.ShaderTransformExecutor.submitTracked(() -> TransformPatcher.patchCeleritasTerrain(source.getVertexSource().orElse(null), source.getGeometrySource().orElse(null), source.getFragmentSource().orElse(null)));
	}

	@SafeVarargs
	private static <T> Optional<T> first(Optional<T>... candidates) {
		for (Optional<T> candidate : candidates) {
			if (candidate.isPresent()) {
				return candidate;
			}
		}
		return Optional.empty();
	}

	/**
	 * Runs the transformations this pipeline would otherwise run while it is being constructed, so
	 * construction reads them back from {@link TransformPatcher}'s cache.
	 *
	 * <p>Called ahead of the pipeline, for the dimension a world is about to render with: the work
	 * then overlaps with the rest of pack loading instead of sitting on the frame that opens the
	 * world. Nothing is awaited here - the returned futures are the ones {@link ProgramTransforms}
	 * hands the constructor, so a warmup that is still running only means the constructor waits for
	 * it. They are returned rather than dropped so the caller can report what the warmup really ended
	 * up caching, once the transforms have run.</p>
	 */
	public static List<CompletableFuture<?>> warmupTransforms(ProgramSet programs) {
		return ProgramTransforms.submit(programs).submitted();
	}

	/**
	 * The transforms a program set needs, submitted to the transform pool as one unit.
	 *
	 * <p>Both the warmup that runs before a pack is loaded and the constructor that builds the pipeline
	 * collect their work here: the constructor reads the results back out of {@link TransformPatcher}'s
	 * cache, so a transform this does not submit is one that runs again on the thread opening the world.
	 * Nothing here decides which thread a transform runs on - they all go to the transform pool - and a
	 * source that several roles name is submitted once.</p>
	 */
	private record ProgramTransforms(
		CompositePasses composites,
		Map<Pair<String, InputAvailability>, CompletableFuture<Map<PatchShaderType, String>>> attributes,
		TerrainPasses terrain,
		List<CompletableFuture<?>> submitted) {

		/** The transforms of the fullscreen passes, keyed the way the renderer of each stage looks them up. */
		private record CompositePasses(
			Map<Integer, CompletableFuture<Map<PatchShaderType, String>>> begin,
			Map<Integer, CompletableFuture<Map<PatchShaderType, String>>> prepare,
			Map<Integer, CompletableFuture<Map<PatchShaderType, String>>> deferred,
			Map<Integer, CompletableFuture<Map<PatchShaderType, String>>> composite,
			@Nullable CompletableFuture<Map<PatchShaderType, String>> compositeFinal) {}

		/** The terrain roles the celeritas terrain pipeline builds from, each with the transform submitted for it. */
		private record TerrainPasses(TerrainRole solid, TerrainRole translucent, TerrainRole shadow, TerrainRole shadowTranslucent) {}

		/** What a compute transform is keyed on, so a source submitted for the same stage is only transformed once. */
		private record ComputeKey(TextureStage stage, String source) {}

		/** One terrain role: the source the pack names for it, and the transform submitted for that source. */
		private record TerrainRole(Optional<ProgramSource> source, @Nullable CompletableFuture<Map<PatchShaderType, String>> transform) {}

		static ProgramTransforms submit(ProgramSet programs) {
			final Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap = programs.getPackDirectives().getTextureMap();
			final List<CompletableFuture<?>> submitted = new ArrayList<>();

			final Map<Integer, CompletableFuture<Map<PatchShaderType, String>>> begin =
				submitCompositeTransforms(programs.getBegin(), TextureStage.BEGIN, textureMap);
			final Map<Integer, CompletableFuture<Map<PatchShaderType, String>>> prepare =
				submitCompositeTransforms(programs.getPrepare(), TextureStage.PREPARE, textureMap);
			final Map<Integer, CompletableFuture<Map<PatchShaderType, String>>> deferred =
				submitCompositeTransforms(programs.getDeferred(), TextureStage.DEFERRED, textureMap);
			final Map<Integer, CompletableFuture<Map<PatchShaderType, String>>> composite =
				submitCompositeTransforms(programs.getComposite(), TextureStage.COMPOSITE_AND_FINAL, textureMap);
			final CompletableFuture<Map<PatchShaderType, String>> compositeFinal = programs.getCompositeFinal()
				.filter(ProgramSource::isValid)
				.map(source -> submitCompositeTransform(source, TextureStage.COMPOSITE_AND_FINAL, textureMap))
				.orElse(null);
			// The shadowcomposite passes are transformed in their own stage by a renderer that builds them
			// synchronously, so only the submission is shared with the warmup.
			final Map<Integer, CompletableFuture<Map<PatchShaderType, String>>> shadowComposite =
				submitCompositeTransforms(programs.getShadowComposite(), TextureStage.SHADOWCOMP, textureMap);
			submitted.addAll(begin.values());
			submitted.addAll(prepare.values());
			submitted.addAll(deferred.values());
			submitted.addAll(composite.values());
			submitted.addAll(shadowComposite.values());
			if (compositeFinal != null) {
				submitted.add(compositeFinal);
			}

			final Map<Pair<String, InputAvailability>, CompletableFuture<Map<PatchShaderType, String>>> attributes =
				submitAttributeTransforms(new ProgramFallbackResolver(programs));
			submitted.addAll(attributes.values());

			final TerrainPasses terrain = submitTerrainTransforms(programs, submitted);

			// The stages here are the ones the renderers that compile these computes transform with, so a
			// compute is submitted under the same key the renderer will look up.
			final Set<ComputeKey> computeKeys = new HashSet<>();
			submitComputeTransforms(programs.getSetup(), TextureStage.SETUP, textureMap, submitted, computeKeys);
			submitComputeTransforms(programs.getShadowCompute(), TextureStage.GBUFFERS_AND_SHADOW, textureMap, submitted, computeKeys);
			submitComputeTransforms(programs.getBeginCompute(), TextureStage.BEGIN, textureMap, submitted, computeKeys);
			submitComputeTransforms(programs.getPrepareCompute(), TextureStage.PREPARE, textureMap, submitted, computeKeys);
			submitComputeTransforms(programs.getDeferredCompute(), TextureStage.DEFERRED, textureMap, submitted, computeKeys);
			submitComputeTransforms(programs.getCompositeCompute(), TextureStage.COMPOSITE_AND_FINAL, textureMap, submitted, computeKeys);
			submitComputeTransforms(programs.getFinalCompute(), TextureStage.COMPOSITE_AND_FINAL, textureMap, submitted, computeKeys);
			submitComputeTransforms(programs.getShadowCompCompute(), TextureStage.SHADOWCOMP, textureMap, submitted, computeKeys);

			return new ProgramTransforms(new CompositePasses(begin, prepare, deferred, composite, compositeFinal),
				attributes, terrain, List.copyOf(submitted));
		}

		/**
		 * Submits the four terrain roles. The translucent roles fall back to the terrain and shadow
		 * sources, and submitting the source a role fell back to again only occupies the pool with a
		 * duplicate of the same transform.
		 */
		private static TerrainPasses submitTerrainTransforms(ProgramSet programs, List<CompletableFuture<?>> submitted) {
			final Map<ProgramSource, TerrainRole> bySource = new IdentityHashMap<>();
			final TerrainRole solid = terrainRole(first(programs.getGbuffersTerrain(), programs.getGbuffersTexturedLit(), programs.getGbuffersTextured(), programs.getGbuffersBasic()), bySource);
			final TerrainRole translucent = terrainRole(first(programs.getGbuffersWater(), solid.source()), bySource);
			final TerrainRole shadow = terrainRole(programs.getShadow(), bySource);
			final TerrainRole shadowTranslucent = terrainRole(first(programs.getShadowWater(), shadow.source()), bySource);

			for (TerrainRole role : new TerrainRole[] { solid, translucent, shadow, shadowTranslucent }) {
				if (role.transform() != null) {
					submitted.add(role.transform());
				}
			}

			return new TerrainPasses(solid, translucent, shadow, shadowTranslucent);
		}

		private static TerrainRole terrainRole(Optional<ProgramSource> source, Map<ProgramSource, TerrainRole> bySource) {
			if (source.isEmpty()) {
				return new TerrainRole(source, null);
			}

			final ProgramSource program = source.get();
			final TerrainRole submitted = bySource.get(program);
			if (submitted != null) {
				return submitted;
			}

			final TerrainRole role = new TerrainRole(source, submitCeleritasTerrainTransform(program));
			bySource.put(program, role);
			return role;
		}
	}

	/** Submits the transform of every compute source in {@code sources} and records it in {@code submitted}. */
	private static void submitComputeTransforms(ComputeSource[] sources, TextureStage stage,
		Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap,
		List<CompletableFuture<?>> submitted, Set<ProgramTransforms.ComputeKey> keys) {
		for (ComputeSource source : sources) {
			if (source == null || !source.getSource().isPresent()) {
				continue;
			}
			final String compute = source.getSource().get();
			if (!keys.add(new ProgramTransforms.ComputeKey(stage, compute))) {
				continue;
			}
			final String name = source.getName();
			submitted.add(Iris.ShaderTransformExecutor.submitTracked(() -> TransformPatcher.patchCompute(name, compute, stage, textureMap)));
		}
	}

	/** Submits the computes of every fullscreen pass, which is where the shadowcomposite computes live. */
	private static void submitComputeTransforms(ComputeSource[][] sources, TextureStage stage,
		Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap,
		List<CompletableFuture<?>> submitted, Set<ProgramTransforms.ComputeKey> keys) {
		for (ComputeSource[] pass : sources) {
			if (pass != null) {
				submitComputeTransforms(pass, stage, textureMap, submitted, keys);
			}
		}
	}

	private static void logBlockMappingSummary() {
		if (!ShaderRegressionDebug.isEnabled()) {
			return;
		}

		ShaderRegressionDebug.logBlockMetaMap("minecraft:web", Blocks.WEB, 0);
		ShaderRegressionDebug.logBlockMetaMap("minecraft:reeds", Blocks.REEDS, 0, 1, 2, 4, 6);
		ShaderRegressionDebug.logBlockMetaMap("minecraft:leaves", Blocks.LEAVES, 0, 1, 2, 8, 9, 10);
		ShaderRegressionDebug.logBlockMetaMap("minecraft:leaves2", Blocks.LEAVES2, 0, 1, 8, 9);
		ShaderRegressionDebug.logBlockMetaMap("minecraft:rail", Blocks.RAIL, 0, 1, 9);
		ShaderRegressionDebug.logBlockMetaMap("minecraft:lit_redstone_ore", Blocks.LIT_REDSTONE_ORE, 0);
	}
}
