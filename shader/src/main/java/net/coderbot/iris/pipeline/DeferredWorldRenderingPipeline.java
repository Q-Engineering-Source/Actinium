package net.coderbot.iris.pipeline;

import net.coderbot.iris.debug.ShaderRegressionDebug;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import com.gtnewhorizons.angelica.compat.mojang.Camera;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import dhj.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformMatrix4f;
import dhj.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformMatrix3f;
import org.joml.Matrix3f;
import org.joml.Matrix4fc;
import java.util.Comparator;
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
import net.coderbot.iris.shaderpack.loading.ProgramGroup;
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
import org.taumc.glsl.ShaderParser;
import org.taumc.glsl.Transformer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import com.gtnewhorizons.angelica.glsm.GLSMCurrentAttribs;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.hooks.BatchStateGuard;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.hooks.PendingProgramSelection;
import com.gtnewhorizons.angelica.glsm.GLSMQueries;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.ffp.Instancing;

/**
 * Encapsulates the compiled shader program objects for the currently loaded shaderpack.
 */
public class DeferredWorldRenderingPipeline implements WorldRenderingPipeline, RenderTargetStateListener, TesrInstancingPipeline, PendingProgramSelection  {
	private final RenderTargets renderTargets;

	@Nullable
	private ShadowRenderTargets shadowRenderTargets;
	@Nullable
	private ComputeProgram[] shadowComputes;
	@Nullable private ComputeProgram shadowVoxelizationCompute;
	private final VertexWriteReplays vertexWriteReplays = new VertexWriteReplays();
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
	/** Transformed sources per instance layout, keyed the same way as {@link #attributeTransforms}. */
	private final EnumMap<Instancing, Map<Pair<String, InputAvailability>, Map<PatchShaderType, String>>> instancedAttributeTransforms = new EnumMap<>(Instancing.class);

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
	private final DeferredProgramMatch programMatch = DeferredProgramMatch.forDisplayLists(
		this,
		DeferredWorldRenderingPipeline::selectionOwnedElsewhere,
		this::matchPassNow);
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
		Objects.requireNonNull(programs);
		this.adaptiveShadowBoundsStats = AdaptiveShadowBoundsStats.create(programs.getPack().getBufferObjects());
		AdaptiveShadowBoundsStats.activate(this.adaptiveShadowBoundsStats);

		final Map<Integer, CompletableFuture<Map<PatchShaderType, String>>> prepareTransformFutures =
			submitCompositeTransforms(programs.getPrepare(), TextureStage.PREPARE, programs.getPackDirectives().getTextureMap());
		final Map<Integer, CompletableFuture<Map<PatchShaderType, String>>> deferredTransformFutures =
			submitCompositeTransforms(programs.getDeferred(), TextureStage.DEFERRED, programs.getPackDirectives().getTextureMap());
		final Map<Integer, CompletableFuture<Map<PatchShaderType, String>>> compositeTransformFutures =
			submitCompositeTransforms(programs.getComposite(), TextureStage.COMPOSITE_AND_FINAL, programs.getPackDirectives().getTextureMap());

		final CompletableFuture<Map<PatchShaderType, String>> finalTransformFuture =
			programs.getCompositeFinal()
				.filter(ProgramSource::isValid)
				.map(source -> submitCompositeTransform(source, TextureStage.COMPOSITE_AND_FINAL, programs.getPackDirectives().getTextureMap()))
				.orElse(null);

		resolver = new ProgramFallbackResolver(programs);
		final Map<String, Boolean> mvBuiltinsMemo = new ConcurrentHashMap<>();
		final Map<Pair<String, InputAvailability>, CompletableFuture<Map<PatchShaderType, String>>> attributeTransformFutures = submitAttributeTransforms(resolver, attributeProgramIds(Instancing.NONE), Instancing.NONE, mvBuiltinsMemo);
		final EnumMap<Instancing, Map<Pair<String, InputAvailability>, CompletableFuture<Map<PatchShaderType, String>>>> instancedTransformFutures = new EnumMap<>(Instancing.class);
		for (Instancing kind : Instancing.VALUES) {
			if (kind != Instancing.NONE && attributeProgramIds(kind).length > 0) {
				instancedTransformFutures.put(kind, submitAttributeTransforms(resolver, attributeProgramIds(kind), kind, mvBuiltinsMemo));
			}
		}

		final Optional<ProgramSource> terrainSource = first(programs.getGbuffersTerrain(), programs.getGbuffersTexturedLit(), programs.getGbuffersTextured(), programs.getGbuffersBasic());
		final Optional<ProgramSource> translucentSource = first(programs.getGbuffersWater(), terrainSource);
		final Optional<ProgramSource> shadowSource = programs.getShadow();
		final Optional<ProgramSource> shadowTranslucentSource = first(programs.getShadowWater(), shadowSource);

		// Celeritas terrain transform futures
		final CompletableFuture<Map<PatchShaderType, String>> celeritasTerrainFuture = terrainSource.map(DeferredWorldRenderingPipeline::submitCeleritasTerrainTransform).orElse(null);
		final CompletableFuture<Map<PatchShaderType, String>> celeritasTranslucentFuture = translucentSource.map(DeferredWorldRenderingPipeline::submitCeleritasTerrainTransform).orElse(null);
		final CompletableFuture<Map<PatchShaderType, String>> celeritasShadowFuture = shadowSource.map(DeferredWorldRenderingPipeline::submitCeleritasTerrainTransform).orElse(null);
		final CompletableFuture<Map<PatchShaderType, String>> celeritasShadowTranslucentFuture = shadowTranslucentSource.map(DeferredWorldRenderingPipeline::submitCeleritasTerrainTransform).orElse(null);

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
		GLSMTextures.glActiveTexture(GL13.GL_TEXTURE2);

		customTextureManager = new CustomTextureManager(programs.getPackDirectives(), programs.getPack().getCustomTextureDataMap(), programs.getPack().getIrisCustomTextureDataMap(), programs.getPack().getCustomNoiseTexture());

		whitePixel = new NativeImageBackedSingleColorTexture(255, 255, 255, 255);

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

		final var bufferObjectInfos = programs.getPack().getBufferObjects();
		if (!bufferObjectInfos.isEmpty() && RenderSystem.supportsSSBO()) {
			Int2ObjectArrayMap<ShaderStorageInfo> ssboOverrides = new Int2ObjectArrayMap<>();
			bufferObjectInfos.forEach(ssboOverrides::put);
			this.ssboHolder = new ShaderStorageBufferHolder(ssboOverrides, main.framebufferWidth, main.framebufferHeight);
		} else {
			this.ssboHolder = null;
		}

		GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);

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

		this.beginRenderer = new CompositeRenderer(programs.getBegin(), programs.getBeginCompute(), flipper, beginBuildContext, programs.getPackDirectives().getExplicitFlips("begin_pre"), null, "begin", TextureStage.BEGIN);

		this.flippedBeforeShadow = flipper.snapshot();

		this.prepareRenderer = new CompositeRenderer(programs.getPrepare(), programs.getPrepareCompute(), flipper, prepareBuildContext, programs.getPackDirectives().getExplicitFlips("prepare_pre"), prepareTransformFutures, "prepare", TextureStage.PREPARE);

		flippedAfterPrepare = flipper.snapshot();

		this.deferredRenderer = new CompositeRenderer(programs.getDeferred(), programs.getDeferredCompute(), flipper, deferredBuildContext, programs.getPackDirectives().getExplicitFlips("deferred_pre"), deferredTransformFutures, "deferred", TextureStage.DEFERRED);

		flippedAfterTranslucent = flipper.snapshot();

		this.compositeRenderer = new CompositeRenderer(programs.getComposite(), programs.getCompositeCompute(), flipper, compositeBuildContext, programs.getPackDirectives().getExplicitFlips("composite_pre"), compositeTransformFutures, "composite", TextureStage.COMPOSITE_AND_FINAL);
		this.finalPassRenderer = new FinalPassRenderer(programs, compositeBuildContext, flipper.snapshot(), this.compositeRenderer.getFlippedAtLeastOnceFinal(), finalTransformFuture, "final");

		// [(textured=false,lightmap=false), (textured=true,lightmap=false), (textured=true,lightmap=true)]
		final ProgramId[] ids = PROGRAM_IDS;

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

		// A kind whose transform fails only loses its own variants; the remaining kinds and the
		// per-vertex path keep working.
		for (Map.Entry<Instancing, Map<Pair<String, InputAvailability>, CompletableFuture<Map<PatchShaderType, String>>>> kindEntry : instancedTransformFutures.entrySet()) {
			final Instancing kind = kindEntry.getKey();
			final Map<Pair<String, InputAvailability>, Map<PatchShaderType, String>> joined = new HashMap<>();
			this.instancedAttributeTransforms.put(kind, joined);
			for (Map.Entry<Pair<String, InputAvailability>, CompletableFuture<Map<PatchShaderType, String>>> entry : kindEntry.getValue().entrySet()) {
				try {
					final Map<PatchShaderType, String> transformed = entry.getValue().join();
					if (transformed == null) continue;
					joined.put(entry.getKey(), transformed);
				} catch (Exception e) {
					Iris.logger.warn("{} instanced transform failed for {}; disabling it", kind, entry.getKey().getLeft(), e);
					supportsInstancing[kind.ordinal()] = false;
					joined.clear();
					break;
				}
			}
		}

		final Map<Pair<ProgramId, InputAvailability>, Pass> cachedPasses = new HashMap<>();

		this.shadowComputes = createShadowComputes(programs.getShadowCompute());

		final Map<SharedProgramKey, BuiltProgram> builtPrograms = new HashMap<>();

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
						condition == RenderCondition.SHADOW || condition == RenderCondition.SHADOW_TRANSLUCENT, finalId, builtPrograms);
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
				Program shadowProgram = table.match(RenderCondition.SHADOW, SHADOW_INPUTS).getProgram();
				Program shadowWaterProgram = table.match(RenderCondition.SHADOW_TRANSLUCENT, SHADOW_INPUTS).getProgram();
				shadowRenderer.setUsesImages((shadowProgram != null && (shadowProgram.getActiveImages() > 0 || vertexWriteReplays.writesImages(shadowProgram)))
					|| (shadowWaterProgram != null && (shadowWaterProgram.getActiveImages() > 0 || vertexWriteReplays.writesImages(shadowWaterProgram))));
			} else {
				shadowRenderer = null;
			}
		} else {
			this.shadowClearPasses = ImmutableList.of();
			this.shadowClearPassesFull = ImmutableList.of();
			this.shadowCompositeRenderer = null;
			this.shadowRenderer = null;
		}


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
        if (BackendManager.RENDER_BACKEND.isSDLGPU()) {
            shadowVoxelizationCompute = createShadowVoxelizationCompute();
            bindVoxelizationMatrixUniforms();
            if (shadowRenderer != null && shadowVoxelizationCompute != null) shadowRenderer.setUsesImages(true);
        }


		this.dhCompat = new DHCompat(this, shadowDirectives.isDhShadowEnabled().orElse(true));
        customUniforms.optimise();
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
			case NONE, OUTLINE, DEBUG:
				return RenderCondition.DEFAULT;
			case PARTICLES:
				// The run declares its translucency, so gbuffers_particles_translucent is selected by
				// the sprite the run is drawing rather than by the live blend state.
				return isTranslucentDraw() ? RenderCondition.PARTICLES_TRANSLUCENT : RenderCondition.PARTICLES;
			case SKY, SUNSET, CUSTOM_SKY, SUN, MOON, STARS, VOID:
				return RenderCondition.SKY;
			case TERRAIN_SOLID, TERRAIN_CUTOUT, TERRAIN_CUTOUT_MIPPED:
				return RenderCondition.TERRAIN_OPAQUE;
			case ENTITIES:
				return isTranslucentDraw()
					? RenderCondition.ENTITIES_TRANSLUCENT : RenderCondition.ENTITIES;
			case BLOCK_ENTITIES:
				return isTranslucentDraw()
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
		programMatch.clear();
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
			if (programMatch.isPending()) {
				programMatch.resolve();
			} else if (current != null) {
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

	/**
	 * Re-evaluates entity translucency after vanilla changes blend state. A declared translucency
	 * wins over the live blend state, so the selection cannot change while one is active.
	 */
	public void onVanillaBlendChanged() {
		if (matchingBlend || drivingProgram || refreshingPass) {
			return;
		}

		if (declaredTranslucent != null) {
			return;
		}

		final WorldRenderingPhase activePhase = getPhase();
		if (activePhase != WorldRenderingPhase.ENTITIES && activePhase != WorldRenderingPhase.BLOCK_ENTITIES) {
			return;
		}

		matchingBlend = true;
		try {
			programMatch.request();
		} finally {
			matchingBlend = false;
		}
	}

	private void matchPass() {
		programMatch.matchNow();
	}

	private void matchPassNow() {
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
		final Pass matched = table.match(condition, inputsFor(condition, inputs));
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

	static InputAvailability inputsFor(RenderCondition condition, InputAvailability liveInputs) {
		if (condition == RenderCondition.SHADOW || condition == RenderCondition.SHADOW_TRANSLUCENT) {
			return SHADOW_INPUTS;
		}

		return liveInputs;
	}

	private static final InputAvailability SHADOW_INPUTS = new InputAvailability(true, true);

	public void beginPass(Pass pass) {
		programMatch.runUnrecorded(() -> beginPassNow(pass));
	}

	private void beginPassNow(Pass pass) {
        WorldRenderingPhase activePhase = getPhase();
        int previousProgram = getActivePassProgramId();
		int nextProgram = expectedProgramId(pass);

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
			if (programMatch.isPending()) {
				programMatch.resolve();
			}
			if (current != null) {
				current.use();
			} else {
				matchPass();
			}
		} finally {
			drivingProgram = false;
		}
	}

	private Instancing activeInstancing = Instancing.NONE;

	/**
	 * Which instance layouts can produce a variant for this pack. It starts from the host options and
	 * a kind is cleared when its transform or a variant link fails.
	 */
	private final boolean[] supportsInstancing = initialInstancingSupport();

	/**
	 * A pass change has to wait while a foreign renderer owns the GL program, and while a batch is
	 * being built: both replace the program the rest of the batch expects to stay bound.
	 */
	private static boolean selectionOwnedElsewhere() {
		return GLStateManager.isForeignDraw() || BatchStateGuard.isBatching();
	}

	@Override
	public void resolvePendingProgram() {
		programMatch.resolve();
	}

	@Override
	public void rebindCurrentPass() {
		activeInstancing = Instancing.NONE;
		GLSMHooks.resolvePendingProgram();
		final Pass pass = this.current;
		if (pass == null) {
			if (GLSMPrograms.getActiveProgram() == 0) {
				matchPass();
			}
			return;
		}
		final Program program = pass.getProgram();
		final GlFramebuffer expected = isBeforeTranslucent ? pass.framebufferBeforeTranslucents : pass.framebufferAfterTranslucents;
		if (program != null && GLSMPrograms.getActiveProgram() == program.getProgramId() && GLSMFramebuffer.getDrawFramebuffer() == expected.getId()) {
			return;
		}
		pass.use();
	}

	@Override
	public void onEntityRenderBoundary() {
		GLSMHooks.resolvePendingProgram();

		if (!shouldOverrideShaders()) {
			return;
		}

		// The pass is re-matched rather than compared against a cached condition so a pack whose
		// inputs change per entity gets the program its availability now selects.
		matchPass();
	}

	/** Whether the pack requires one pass match per entity instead of a single match for the whole loop. */
	public boolean shouldSeparateEntityDraws() {
		return packDirectives.shouldUseSeparateEntityDraws();
	}

	/** {@return whether the pipeline is drawing world geometry rather than a full-screen or post pass} */
	public boolean isRenderingLevelGeometry() {
		return isRenderingWorld && !isRenderingFullScreenPass && !isPostChain;
	}

	public boolean supportsInstancing(Instancing kind) {
		return supportsInstancing[kind.ordinal()];
	}

	@Override
	public boolean hasInstancedVariant(Instancing kind) {
		return current != null && current.instancedVariant(kind) != null;
	}

	public boolean hasInstancedVariant(RenderCondition condition, InputAvailability availability, Instancing kind) {
		final Pass pass = table.match(condition, availability);
		return pass != null && pass.instancedVariant(kind) != null;
	}

	@Override
	public void bindInstancedVariant(Instancing kind) {
		final Program variant = current != null ? current.instancedVariant(kind) : null;
		if (variant == null) {
			throw new IllegalStateException("No " + kind + " variant is available for the active pass");
		}
		activeInstancing = kind;
		variant.use();
		this.customUniforms.push(variant);
	}

	/**
	 * @return the program {@code pass} would bind right now, which is its instanced variant while a
	 * batch draws from one and its own program otherwise
	 */
	private int expectedProgramId(@Nullable Pass pass) {
		if (pass == null) {
			return 0;
		}
		if (activeInstancing != Instancing.NONE) {
			final Program variant = pass.instancedVariant(activeInstancing);
			if (variant != null) {
				return variant.getProgramId();
			}
		}
		return pass.getProgram() != null ? pass.getProgram().getProgramId() : 0;
	}

	/**
	 * Binds the active layout's variant of {@code pass} when that pass has one.
	 *
	 * @return whether the variant replaced the pass's own program; a pass without one keeps its
	 * program so a variant that failed to build cannot break drawing for the rest of the batch
	 */
	private boolean applyInstancing(Pass pass) {
		if (activeInstancing == Instancing.NONE || pass.program == null) {
			return false;
		}
		final Program variant = pass.instancedVariant(activeInstancing);
		if (variant == null) {
			return false;
		}
		variant.use();
		this.customUniforms.push(variant);
		return true;
	}

	private Pass createDefaultPass() {
		final GlFramebuffer framebufferBeforeTranslucents = renderTargets.createGbufferFramebuffer(flippedAfterPrepare, new int[] {0});
		final GlFramebuffer framebufferAfterTranslucents = renderTargets.createGbufferFramebuffer(flippedAfterTranslucent, new int[] {0});


		return new Pass(null, framebufferBeforeTranslucents, framebufferAfterTranslucents, null,
			null, Collections.emptyList(), false);
	}

	/**
	 * The program each render condition selects, three columns per condition:
	 * {@code (texture=false, lightmap=false)}, {@code (texture=true, lightmap=false)} and
	 * {@code (texture=true, lightmap=true)}. A null entry falls back to the first column.
	 */
	static final ProgramId[] PROGRAM_IDS = {
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
		ProgramId.Particles, ProgramId.Particles, ProgramId.Particles,
		ProgramId.ParticlesTrans, ProgramId.ParticlesTrans, ProgramId.ParticlesTrans,
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

	private record SharedProgramKey(String sourceName, InputAvailability availability, boolean shadow) {}

	private record BuiltProgram(Program program, Pass firstPass) {}

	private Pass createPass(ProgramSource source, InputAvailability availability, boolean shadow, ProgramId id,
							Map<SharedProgramKey, BuiltProgram> builtPrograms) {
		// Use pre-computed transform if available, otherwise transform synchronously
		InputAvailability transformAvailability = getTransformInputAvailability(id, availability);
		Pair<String, InputAvailability> key = Pair.of(source.getName(), transformAvailability);
		Map<PatchShaderType, String> transformed = attributeTransforms.get(key);

		// The synchronous fallback below depends on the program id, so only precomputed programs are shared
		final SharedProgramKey shareKey = transformed != null ? new SharedProgramKey(source.getName(), transformAvailability, shadow) : null;
		final BuiltProgram shared = shareKey != null ? builtPrograms.get(shareKey) : null;
		if (shared != null) {
			final Pass pass = createPassInner(shared.program(), source.getDirectives(), shadow, id);
			this.customUniforms.mapPassLike(shared.firstPass(), pass);
			pass.setInstancingKey(source.getName(), availability, shadow);
			return pass;
		}

		if (transformed == null) {
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

		ProgramBuilder builder = ProgramBuilder.begin(source.getName(), vertex, geometry, tessControl, tessEval, fragment,
			IrisSamplers.WORLD_RESERVED_TEXTURE_UNITS);
		wireGbufferProgram(builder, availability, shadow);
		final Program program = builder.build();
        vertexWriteReplays.attach(source.getName(), program, transformed,
            (samplers, images) -> wireGbufferResources(samplers, images, availability, shadow));
		IrisGlDebug.logProgramSamplerState("create-pass", program.getProgramId(), availability.toString(), getPhase().name());

		final Pass pass = createPassInner(program, source.getDirectives(), shadow, id);
		this.customUniforms.mapholderToPass(builder, pass);
		pass.setInstancingKey(source.getName(), availability, shadow);
		if (shareKey != null) {
			builtPrograms.put(shareKey, new BuiltProgram(program, pass));
		}
		return pass;
	}

	private void wireGbufferProgram(ProgramBuilder builder, InputAvailability availability, boolean shadow) {
		CommonUniforms.addDynamicUniforms(builder, FogMode.PER_VERTEX);
		this.customUniforms.assignTo(builder);

		builder.bindAttributeLocation(11, "mc_Entity");
		builder.bindAttributeLocation(12, "mc_midTexCoord");
		builder.bindAttributeLocation(13, "at_tangent");
		builder.bindAttributeLocation(14, "at_midBlock");

        wireGbufferResources(builder, builder, availability, shadow);
    }

    private void wireGbufferResources(SamplerHolder samplers, ImageHolder images, InputAvailability availability, boolean shadow) {
		final Supplier<ImmutableSet<Integer>> flipped;

		if (shadow) {
			flipped = () -> ((shouldRenderPrepareBeforeShadow || hasRenderedPreparePass) ? flippedAfterPrepare : flippedBeforeShadow);
		} else {
			flipped = () -> isBeforeTranslucent ? flippedAfterPrepare : flippedAfterTranslucent;
		}

		TextureStage textureStage = TextureStage.GBUFFERS_AND_SHADOW;

		ProgramSamplers.CustomTextureSamplerInterceptor customTextureSamplerInterceptor =
			ProgramSamplers.customTextureSamplerInterceptor(samplers,
				customTextureManager.getCustomTextureIdMap(textureStage));

		IrisSamplers.addRenderTargetSamplers(customTextureSamplerInterceptor, flipped, renderTargets, false, this);
		IrisImages.addRenderTargetImages(images, flipped, renderTargets);

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
		IrisImages.addCustomImages(images, customImages);

		if (IrisSamplers.hasShadowSamplers(customTextureSamplerInterceptor)) {
			if (!shadow) {
				shadowTargetsSupplier.get();
			}

			if (shadowRenderTargets != null) {
				IrisSamplers.addShadowSamplers(customTextureSamplerInterceptor, shadowRenderTargets, null, true);
				IrisImages.addShadowColorImages(images, shadowRenderTargets, null);
			}
		}
	}

	private static InputAvailability getTransformInputAvailability(ProgramId id, InputAvailability availability) {
		if (id == ProgramId.SkyTextured && availability.texture && !availability.lightmap) {
			return INPUT_TEXTURE_NO_COLOR;
		}
		return availability;
	}

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

		/**
		 * Source and availability this pass was created from. A pass that did not come from the
		 * attribute transform map (a default or shadow-only pass) leaves the name null and has no
		 * variants.
		 */
		private String instancingSourceName;
		private InputAvailability instancingAvailability;
		private boolean instancingShadow;
		private final Program[] instancedVariants = new Program[Instancing.VALUES.length];
		private final boolean[] instancedVariantAttempted = new boolean[Instancing.VALUES.length];

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
				GLSMRasterizer.glViewport(0, 0, shadowMapResolution, shadowMapResolution);
			} else {
                final Framebuffer main = Minecraft.getMinecraft().getFramebuffer();
				GLSMRasterizer.glViewport(0, 0, main.framebufferWidth, main.framebufferHeight);
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

			if (!DeferredWorldRenderingPipeline.this.applyInstancing(this)) {
				if (program != null) {
					program.use();
				} else {
					Program.unbind();
				}
				DeferredWorldRenderingPipeline.this.customUniforms.push(this);
			}
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

		void setInstancingKey(String sourceName, InputAvailability availability, boolean shadow) {
			this.instancingSourceName = sourceName;
			this.instancingAvailability = availability;
			this.instancingShadow = shadow;
		}

		/**
		 * @return this pass's variant for the given layout, building it on first use; null when the
		 * layout is unsupported, the pack has no transformed source for it, or the link failed
		 */
		@Nullable
		Program instancedVariant(Instancing kind) {
			final int index = kind.ordinal();
			if (!instancedVariantAttempted[index]) {
				instancedVariantAttempted[index] = true;
				if (instancingSourceName != null && supportsInstancing[index]) {
					instancedVariants[index] = sharedInstancedVariant(instancingSourceName, instancingAvailability, instancingShadow, kind);
				}
			}
			return instancedVariants[index];
		}

		public void destroy() {
			if (this.program != null) {
				this.program.destroy();
			}
		}
	}

	private record InstancedVariantKey(String sourceName, InputAvailability availability, boolean shadow, Instancing kind) {}

	/**
	 * Variants keyed by source and layout. Passes that share a program also share the variant built
	 * from it, so one link serves every condition that resolved to the same source. A key that
	 * produced no variant is kept as a null entry so a failing source is not re-linked on every query.
	 */
	private final InstancedVariantCache<Program> instancedVariantPrograms =
		new InstancedVariantCache<>(Program::destroy);

	@Nullable
	private Program sharedInstancedVariant(String sourceName, InputAvailability availability, boolean shadow, Instancing kind) {
		return instancedVariantPrograms.get(new InstancedVariantKey(sourceName, availability, shadow, kind), this::buildInstancedVariant);
	}

	/**
	 * Links one variant from the pre-computed instanced transform. A failed link only disables this
	 * kind, so the batcher falls back to per-instance draws for it and keeps the other kinds working.
	 */
	@Nullable
	private Program buildInstancedVariant(InstancedVariantKey key) {
		final Map<Pair<String, InputAvailability>, Map<PatchShaderType, String>> transforms = instancedAttributeTransforms.get(key.kind());
		final Map<PatchShaderType, String> transformed = transforms != null ? transforms.get(Pair.of(key.sourceName(), key.availability())) : null;
		if (transformed == null) {
			return null;
		}

		final Instancing kind = key.kind();
		final InputAvailability availability = key.availability();
		final boolean shadow = key.shadow();
		final String name = key.sourceName() + kind.variantSuffix;
		try {
			final String vertex = transformed.get(PatchShaderType.VERTEX);
			final String geometry = transformed.get(PatchShaderType.GEOMETRY);
			final String tessControl = transformed.get(PatchShaderType.TESS_CONTROL);
			final String tessEval = transformed.get(PatchShaderType.TESS_EVAL);
			final String fragment = transformed.get(PatchShaderType.FRAGMENT);

			PatchedShaderPrinter.debugPatchedShaders(name, vertex, geometry, tessControl, tessEval, fragment);

			final ProgramBuilder builder = ProgramBuilder.begin(name, vertex, geometry, tessControl, tessEval, fragment,
				IrisSamplers.WORLD_RESERVED_TEXTURE_UNITS);
			wireGbufferProgram(builder, availability, shadow);
			final Program variant = builder.build();
			vertexWriteReplays.attach(name, variant, transformed,
				(samplers, images) -> wireGbufferResources(samplers, images, availability, shadow));
			this.customUniforms.mapholderToPass(builder, variant);
			return variant;
		} catch (Exception e) {
			Iris.logger.warn("{} instanced variant link failed for {}; disabling it", kind, key.sourceName(), e);
			supportsInstancing[kind.ordinal()] = false;
			return null;
		}
	}

	/**
	 * Resolves every variant the pack can produce, once. The full-compile path calls this so the
	 * binaries are written to the pack cache; the batch host calls it before it opens a batch, when
	 * creating a variant would otherwise happen while queued vertices wait for a bound program.
	 */
	public void compileInstancedVariants() {
		if (instancedVariantsCompiled) {
			return;
		}
		instancedVariantsCompiled = true;
		forEachInstancingTarget(table, (pass, kind) -> pass.instancedVariant(kind));
	}

	/**
	 * Visits every pass in {@code table} once per layout that can produce a variant. The table holds
	 * one entry per render condition and availability, so this covers every condition the batches can
	 * ask for rather than a hand-picked list, and passes that several rows share are visited once.
	 */
	static <P> void forEachInstancingTarget(ProgramTable<P> table, BiConsumer<P, Instancing> visitor) {
		final Set<P> visited = new HashSet<>();
		table.forEach(pass -> {
			if (pass == null || !visited.add(pass)) return;
			for (Instancing kind : Instancing.VALUES) {
				if (kind != Instancing.NONE) {
					visitor.accept(pass, kind);
				}
			}
		});
	}

	private boolean instancedVariantsCompiled;

	static boolean[] initialInstancingSupport() {
		final boolean[] out = new boolean[Instancing.VALUES.length];
		out[Instancing.TEMPLATE.ordinal()] = true;
		out[Instancing.CUBE.ordinal()] = InstancingSupportProvider.cubeInstancingEnabled();
		// Particle quad instancing only needs the fixed particle layout, so it is always attempted;
		// a pack whose particle program cannot be transformed or linked clears this again.
		out[Instancing.PARTICLE.ordinal()] = true;
		return out;
	}

	@Override
	public void destroy() {
        vertexWriteReplays.destroy();
        if (shadowVoxelizationCompute != null) {
            shadowVoxelizationCompute.destroy();
            shadowVoxelizationCompute = null;
        }
		DepthColorStorage.unlockDepthColor();
		BlendModeOverride.restore();
		AlphaTestOverride.restore();

		FullScreenQuadRenderer.clearLocCache();
		destroyPasses(table);
		destroyInstancedVariants();

		// Destroy the composite rendering pipeline
		// This destroys all the loaded composite programs as well.
		beginRenderer.destroy();
		prepareRenderer.destroy();
		compositeRenderer.destroy();
		deferredRenderer.destroy();
		finalPassRenderer.destroy();
		centerDepthSampler.destroy();
        closeFinalizeTimer(finalizeOutputTimer);
        closeFinalizeTimer(compositeOutputTimer);
        closeFinalizeTimer(finalOutputTimer);
        finalizeOutputTimer = null;
        compositeOutputTimer = null;
        finalOutputTimer = null;

		if (setup != null) {
			for (ComputeProgram compute : setup) {
				if (compute != null) {
					compute.destroy();
				}
			}
		}

		if (shadowComputes != null) {
			for (ComputeProgram compute : shadowComputes) {
				if (compute != null) {
					compute.destroy();
				}
			}
		}

		if (shadowCompositeRenderer != null) {
			shadowCompositeRenderer.destroy();
		}

		horizonRenderer.destroy();

		// Make sure that any custom framebuffers are not bound before destroying render targets
		GLSMFramebuffer.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
		GLSMFramebuffer.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
		GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

		// Destroy our render targets
		// While it's possible to just clear them instead and reuse them, we'd need to investigate whether or not this
		// would help performance.
		renderTargets.destroy();
		dhCompat.clearPipeline();

		if (shadowRenderTargets != null) {
			shadowRenderTargets.destroy();
		}

		// Destroy custom textures and the static samplers (normals, specular, and noise)
		customTextureManager.destroy();
		whitePixel.deleteGlTexture();

		for (GlImage image : customImages) {
			image.destroy();
		}
		customImages.clear();

		AdaptiveShadowBoundsStats.deactivate(adaptiveShadowBoundsStats);
		adaptiveShadowBoundsStats.destroy();
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

	/**
	 * Destroys the instanced variants once each. They are keyed by source and layout rather than
	 * owned by a pass, so several passes can reference the same program.
	 */
	private void destroyInstancedVariants() {
		instancedVariantPrograms.destroyAll();
	}

	private void prepareRenderTargets() {
		// Make sure we're using texture unit 0 for this.
		GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
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
                GLSMFramebuffer.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                if (Minecraft.IS_RUNNING_ON_MAC) {
                    GLSMQueries.glGetError();
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

			for (GlImage image : customImages) {
				image.updateNewSize(main.framebufferWidth, main.framebufferHeight);
			}

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

        MinecraftFramebufferHelper.restoreMainFramebuffer(true);
	}

	@Nullable
	private ComputeProgram createShadowVoxelizationCompute() {
		if (celeritasTerrainPipeline == null) return null;
		final Optional<String> sourceOpt = celeritasTerrainPipeline.getShadowVoxelizationComputeSource();
		if (sourceOpt.isEmpty()) return null;
		final String src = sourceOpt.get();
		final ProgramBuilder builder;
		try {
			PatchedShaderPrinter.debugPatchedShaders("shadow_voxelization_compute", null, null, null, src);
			builder = ProgramBuilder.beginCompute("shadow_voxelization", src, IrisSamplers.WORLD_RESERVED_TEXTURE_UNITS);
		} catch (RuntimeException e) {
			Iris.logger.error("Voxelization compute shader compilation failed; colored lighting will not render", e);
			return null;
		}
		CommonUniforms.addDynamicUniforms(builder, FogMode.PER_VERTEX);
		this.customUniforms.assignTo(builder);
		final Supplier<ImmutableSet<Integer>> flipped = () -> flippedBeforeShadow;
		final TextureStage textureStage = TextureStage.GBUFFERS_AND_SHADOW;
		final ProgramSamplers.CustomTextureSamplerInterceptor customTextureSamplerInterceptor =
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
		customImages.stream()
			.sorted(Comparator.comparing(GlImage::getName))
			.forEach(image -> builder.addTextureImage(image::getId, image.getInternalFormat(), image.getName()));
		final ComputeProgram program = builder.buildCompute();
		this.customUniforms.mapholderToPass(builder, program);
		return program;
	}

	@Nullable
	public ComputeProgram getShadowVoxelizationCompute() { return shadowVoxelizationCompute; }

	@Nullable
	public ShadowRenderer getShadowRenderer() { return shadowRenderer; }

	private GlUniformMatrix4f voxelizationModelView;
	private GlUniformMatrix3f voxelizationNormalMatrix;
	private final Matrix3f voxelizationNormalScratch = new Matrix3f();

	private void bindVoxelizationMatrixUniforms() {
		if (shadowVoxelizationCompute == null) return;
		final int id = shadowVoxelizationCompute.getProgramId();
		final int modelView = GLSMPrograms.glGetUniformLocation(id, "iris_ModelViewMatrix");
		final int normal = GLSMPrograms.glGetUniformLocation(id, "iris_NormalMatrix");
		this.voxelizationModelView = modelView >= 0 ? new GlUniformMatrix4f(modelView) : null;
		this.voxelizationNormalMatrix = normal >= 0 ? new GlUniformMatrix3f(normal) : null;
	}

	public void prepareShadowVoxelizationCompute(Matrix4fc modelView) {
		if (shadowVoxelizationCompute == null) return;
		if (voxelizationModelView != null) {
			voxelizationModelView.set(modelView);
		}
		if (voxelizationNormalMatrix != null) {
			voxelizationNormalScratch.set(modelView).invert().transpose();
			voxelizationNormalMatrix.set(voxelizationNormalScratch);
		}
		customUniforms.push(shadowVoxelizationCompute);
	}

	private ComputeProgram[] createShadowComputes(ComputeSource[] compute) {
		ComputeProgram[] programs = new ComputeProgram[compute.length];
		for (int i = 0; i < programs.length; i++) {
			ComputeSource source = compute[i];
			if (source == null || !source.getSource().isPresent()) {
				continue;
			} else {
				ProgramBuilder builder;

				try {
					String transformed = TransformPatcher.patchCompute(source.getName(), source.getSource().orElse(null), TextureStage.GBUFFERS_AND_SHADOW, getTextureMap());
					PatchedShaderPrinter.debugPatchedShaders(source.getName() + "_compute", null, null, null, transformed);
					builder = ProgramBuilder.beginCompute(source.getName(), transformed, IrisSamplers.WORLD_RESERVED_TEXTURE_UNITS);
				} catch (RuntimeException e) {
					// TODO: Better error handling
					throw new RuntimeException("Shader compilation failed!", e);
				}

				CommonUniforms.addDynamicUniforms(builder, FogMode.PER_VERTEX);
                this.customUniforms.assignTo(builder);

				Supplier<ImmutableSet<Integer>> flipped;

				flipped = () -> flippedBeforeShadow;

				TextureStage textureStage = TextureStage.GBUFFERS_AND_SHADOW;

				ProgramSamplers.CustomTextureSamplerInterceptor customTextureSamplerInterceptor =
					ProgramSamplers.customTextureSamplerInterceptor(builder, customTextureManager.getCustomTextureIdMap(textureStage));

				IrisSamplers.addRenderTargetSamplers(customTextureSamplerInterceptor, flipped, renderTargets, false, this);
				IrisImages.addRenderTargetImages(builder, flipped, renderTargets);

				IrisSamplers.addLevelSamplers(customTextureSamplerInterceptor, this, whitePixel, new InputAvailability(true, true));

				IrisSamplers.addNoiseSampler(customTextureSamplerInterceptor, customTextureManager.getNoiseTexture());

				IrisSamplers.addCustomImages(customTextureSamplerInterceptor, customImages);
				IrisSamplers.addCustomTextures(customTextureSamplerInterceptor, customTextureManager.getIrisCustomTextures());

				if (IrisSamplers.hasShadowSamplers(customTextureSamplerInterceptor)) {
					if (shadowRenderTargets != null) {
						IrisSamplers.addShadowSamplers(customTextureSamplerInterceptor, shadowRenderTargets, null, true);
						IrisImages.addShadowColorImages(builder, shadowRenderTargets, null);
					}
				}

				IrisImages.addCustomImages(builder, customImages);

				programs[i] = builder.buildCompute();

                this.customUniforms.mapholderToPass(builder, programs[i]);

				programs[i].setWorkGroupInfo(source.getWorkGroupRelative(), source.getWorkGroups(), null);
			}
		}


		return programs;
	}

	private ComputeProgram[] createSetupComputes(ComputeSource[] compute) {
		ComputeProgram[] programs = new ComputeProgram[compute.length];
		for (int i = 0; i < programs.length; i++) {
			ComputeSource source = compute[i];
			if (source == null || !source.getSource().isPresent()) {
				continue;
			} else {
				ProgramBuilder builder;

				try {
					String transformed = TransformPatcher.patchCompute(source.getName(), source.getSource().orElse(null), TextureStage.SETUP, getTextureMap());
					PatchedShaderPrinter.debugPatchedShaders(source.getName() + "_compute", null, null, null, transformed);
					builder = ProgramBuilder.beginCompute(source.getName(), transformed, IrisSamplers.COMPOSITE_RESERVED_TEXTURE_UNITS);
				} catch (RuntimeException e) {
					throw new RuntimeException("Shader compilation failed for setup compute " + source.getName() + "!", e);
				}

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

				if (IrisSamplers.hasShadowSamplers(customTextureSamplerInterceptor)) {
					if (shadowRenderTargets != null) {
						IrisSamplers.addShadowSamplers(customTextureSamplerInterceptor, shadowRenderTargets, null, true);
						IrisImages.addShadowColorImages(builder, shadowRenderTargets, null);
					}
				}

				programs[i] = builder.buildCompute();

				this.customUniforms.mapholderToPass(builder, programs[i]);

				programs[i].setWorkGroupInfo(source.getWorkGroupRelative(), source.getWorkGroups(), null);
			}
		}

		return programs;
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
		programMatch.clear();
		isRenderingWorld = true;
		isBeforeTranslucent = true;
		isMainBound = true;
		isPostChain = false;
		phase = WorldRenderingPhase.NONE;
		overridePhase = null;

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

		renderTargets.onLevelRenderStart();
		prepareRenderTargets();

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

		if (true/*skyType == DimensionSpecialEffects.SkyType.NORMAL*/) {
            GLSMEnable.glDisable(GL11.GL_TEXTURE_2D);
			GLSMDepthStencilAlpha.glDepthMask(false);

			final Vector3d fogColor = GLStateManager.getFogColor();
            GLSMCurrentAttribs.glColor4f((float) fogColor.x, (float) fogColor.y, (float) fogColor.z, 1.0F);

			horizonRenderer.renderHorizon(RenderingState.INSTANCE.getModelViewBuffer());

			GLSMDepthStencilAlpha.glDepthMask(true);
            GLSMEnable.glEnable(GL11.GL_TEXTURE_2D);
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
		programMatch.clear();

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
		programMatch.request();
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
		programMatch.request();
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
		programMatch.request();
	}

	@Override
	public void setSpecialCondition(SpecialCondition special) {
		this.special = special;
		programMatch.request();
	}

	@Override
	public void setDeclaredTranslucency(@Nullable Boolean translucent) {
		this.declaredTranslucent = translucent;
		programMatch.request();
	}

	@Nullable
	private Boolean declaredTranslucent;

	private boolean isTranslucentDraw() {
		return TranslucentBlendMatcher.resolveTranslucency(declaredTranslucent);
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

	private static final InputAvailability INPUT_NONE = new InputAvailability(false, false);
	private static final InputAvailability INPUT_TEXTURE = new InputAvailability(true, false);
	private static final InputAvailability INPUT_TEXTURE_LIGHTMAP = new InputAvailability(true, true);
	private static final InputAvailability INPUT_LIGHTMAP = new InputAvailability(false, true);
	private static final InputAvailability INPUT_TEXTURE_NO_COLOR = new InputAvailability(true, false, false);
	/**
	 * Availability keys the attribute transforms are pre-computed for. A build asked for a key that is
	 * missing here resolves no transform and silently keeps the CPU path, so this must cover every
	 * combination the batch paths can query.
	 */
	static final InputAvailability[] INPUT_AVAILABILITIES = { INPUT_NONE, INPUT_TEXTURE, INPUT_TEXTURE_LIGHTMAP, INPUT_LIGHTMAP, INPUT_TEXTURE_NO_COLOR };

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
			submitAttributeTransforms(ProgramFallbackResolver resolver, ProgramId[] ids, Instancing instancing, Map<String, Boolean> mvBuiltinsMemo) {
		final boolean instanced = instancing != Instancing.NONE;
		final boolean matrixInstanced = instancing.hasInstanceHead();
		final Map<Pair<String, InputAvailability>, CompletableFuture<Map<PatchShaderType, String>>> futures = new HashMap<>();
		final Set<String> processedSourceNames = new HashSet<>();
		for (ProgramId id : ids) {
			if (id.getGroup() == ProgramGroup.Dh) continue;
			ProgramSource source = resolver.resolveNullable(id);
			if (source == null || !processedSourceNames.add(source.getName())) continue;

			final String vertexSource = source.getVertexSource().orElse(null);
			for (InputAvailability avail : INPUT_AVAILABILITIES) {
				Pair<String, InputAvailability> key = Pair.of(source.getName(), avail);
				futures.put(key, Iris.ShaderTransformExecutor.submitTracked(() -> {
					// A stage outside the vertex one that reads the model-view builtins would see the
					// per-draw matrix instead of the instance one, so that program keeps the CPU path.
					if (matrixInstanced && mvBuiltinsMemo.computeIfAbsent(source.getName(), name -> {
						final boolean references = referencesMvBuiltinsOutsideVertex(source);
						if (references) {
							Iris.logger.info("TESR instancing: {} references MV builtins outside the vertex stage, keeping CPU path", name);
						}
						return references;
					})) {
						return null;
					}
					final String geometry = source.getGeometrySource().orElse(null);
					final String tessControl = source.getTessControlSource().orElse(null);
					final String tessEval = source.getTessEvalSource().orElse(null);
					final String fragment = source.getFragmentSource().orElse(null);
					return instanced
						? TransformPatcher.patchAttributesInstanced(vertexSource, geometry, tessControl, tessEval, fragment, avail, instancing)
						: TransformPatcher.patchAttributes(vertexSource, geometry, tessControl, tessEval, fragment, avail);
				}));
			}
		}
		return futures;
	}

	private static final ProgramId[] PARTICLE_PROGRAM_IDS = {
		ProgramId.Particles, ProgramId.ParticlesTrans
	};

	private static final ProgramId[] INSTANCED_PROGRAM_IDS = {
		ProgramId.Block, ProgramId.BlockTrans, ProgramId.Entities, ProgramId.EntitiesTrans,
		ProgramId.Shadow, ProgramId.ShadowWater, ProgramId.ArmorGlint, ProgramId.SpiderEyes
	};

	/**
	 * The programs a layout can produce a variant for. The entity batch draws entities, block
	 * entities, glint and shadows, the particle batch draws the two particle programs, and the weather
	 * layout has no transform of its own yet, so it returns no ids and reports itself unsupported.
	 */
	static ProgramId[] attributeProgramIds(Instancing kind) {
		return switch (kind) {
			case NONE -> ProgramId.values();
			case TEMPLATE, CUBE -> INSTANCED_PROGRAM_IDS;
			case PARTICLE -> PARTICLE_PROGRAM_IDS;
			case WEATHER -> new ProgramId[0];
		};
	}

	private static final String[] MV_BUILTINS = {
		"gl_ModelViewMatrix", "gl_ModelViewMatrixInverse",
		"gl_ModelViewProjectionMatrix", "gl_ModelViewProjectionMatrixInverse",
		"gl_NormalMatrix", "ftransform",
		"modelViewMatrix", "modelViewMatrixInverse", "normalMatrix"
	};

	static boolean referencesMvBuiltins(String source) {
		if (source == null) {
			return false;
		}
		boolean mentioned = false;
		for (String builtin : MV_BUILTINS) {
			if (source.contains(builtin)) {
				mentioned = true;
				break;
			}
		}
		if (!mentioned) {
			return false;
		}
		try {
			final Transformer transformer = new Transformer(ShaderParser.parseShader(source).full());
			for (String builtin : MV_BUILTINS) {
				if (transformer.containsCall(builtin)) {
					return true;
				}
			}
			return false;
		} catch (Exception e) {
			Iris.logger.warn("Failed to parse a shader stage while checking for model-view builtins; keeping the CPU path", e);
			return true;
		}
	}

	private static boolean referencesMvBuiltinsOutsideVertex(ProgramSource source) {
		return referencesMvBuiltins(source.getGeometrySource().orElse(null))
			|| referencesMvBuiltins(source.getTessControlSource().orElse(null))
			|| referencesMvBuiltins(source.getTessEvalSource().orElse(null))
			|| referencesMvBuiltins(source.getFragmentSource().orElse(null));
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
