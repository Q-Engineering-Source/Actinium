package net.coderbot.iris.pipeline;

import dhj.embeddedt.embeddium.impl.render.terrain.SimpleWorldRenderer;
import net.coderbot.iris.debug.IrisDebugOptions;
import com.google.common.collect.ImmutableList;
import com.gtnewhorizons.angelica.compat.mojang.Camera;
import com.gtnewhorizons.angelica.compat.mojang.GameModeUtil;
import net.coderbot.iris.gl.MatrixStack;
import net.coderbot.iris.compat.rfp2.Rfp2Compat;
import net.coderbot.iris.celeritas.WorldRendererCompat;
import net.coderbot.iris.celeritas.WorldRendererCompatBridge;
import com.gtnewhorizons.angelica.glsm.shadow.InternalShadowRenderingState;
import com.gtnewhorizons.angelica.glsm.CompatUniformManager;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.rendering.RenderingState;
import net.coderbot.iris.Iris;
import net.coderbot.iris.compat.dh.DHCompat;
import net.coderbot.iris.debug.IrisGlDebug;
import net.coderbot.iris.gl.framebuffer.MinecraftFramebufferHelper;
import net.coderbot.iris.gl.program.ProgramUniforms;
import net.coderbot.iris.gui.option.IrisVideoSettings;
import net.coderbot.iris.layer.GbufferPrograms;
import net.coderbot.iris.shaderpack.PackDirectives;
import net.coderbot.iris.shaderpack.PackShadowDirectives;
import net.coderbot.iris.shaderpack.ProgramSource;
import net.coderbot.iris.shaderpack.ShadowCullState;
import net.coderbot.iris.shadow.ShadowMatrices;
import net.coderbot.iris.shadows.CullingDataCache;
import net.coderbot.iris.shadows.ShadowCompositeRenderer;
import net.coderbot.iris.shadows.ShadowRenderTargets;
import net.coderbot.iris.shadows.frustum.BoxCuller;
import net.coderbot.iris.shadows.frustum.CullEverythingFrustum;
import net.coderbot.iris.shadows.frustum.FrustumHolder;
import net.coderbot.iris.shadows.frustum.advanced.AdvancedShadowCullingFrustum;
import net.coderbot.iris.shadows.frustum.advanced.SafeZoneCullingFrustum;
import net.coderbot.iris.shadows.frustum.fallback.BoxCullingFrustum;
import net.coderbot.iris.shadows.frustum.fallback.NonCullingFrustum;
import net.coderbot.iris.uniforms.CapturedRenderingState;
import net.coderbot.iris.uniforms.CelestialUniforms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import dhj.embeddedt.embeddium.impl.gl.device.RenderDevice;
import dhj.embeddedt.embeddium.impl.render.viewport.ViewportProvider;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBTextureSwizzle;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class ShadowRenderer {
	public static final Matrix4f MODELVIEW = new Matrix4f();
    public static final FloatBuffer MODELVIEW_BUFFER = BufferUtils.createFloatBuffer(16);
	public static final Matrix4f PROJECTION = new Matrix4f();
	public static final List<TileEntity> visibleTileEntities = new ArrayList<>();
	public static final List<TileEntity> globalTileEntities = new ArrayList<>();
	public static boolean ACTIVE = false;

	public static Frustum FRUSTUM;

	private static final Comparator<Entity> ENTITY_CLASS_COMPARATOR = Comparator.comparingInt(a -> System.identityHashCode(a.getClass()));
	private static final NonCullingFrustum NON_CULLING_FRUSTUM = new NonCullingFrustum();
	private static final CullEverythingFrustum CULL_EVERYTHING_FRUSTUM = new CullEverythingFrustum();
	private static final List<BlockRenderLayer> OPAQUE_SHADOW_TERRAIN_LAYERS = ImmutableList.of(
		BlockRenderLayer.SOLID,
		BlockRenderLayer.CUTOUT_MIPPED,
		BlockRenderLayer.CUTOUT
	);

	public static ShadowRenderTargets CURRENT_TARGETS = null;
	private final float halfPlaneLength;
	private final float voxelDistance;
	private final float renderDistanceMultiplier;
	private final float entityShadowDistanceMultiplier;
	private final int resolution;
	private final float intervalSize;
	private final Float fov;
	private final ShadowRenderTargets targets;
	private final ShadowCullState packCullingState;
	private final ShadowCompositeRenderer compositeRenderer;
	private boolean packHasVoxelization;
	private final boolean shouldRenderTerrain;
	private final boolean shouldRenderTranslucent;
	private final boolean shouldRenderEntities;
	private final boolean shouldRenderPlayer;
	private final boolean shouldRenderBlockEntities;
	private final float sunPathRotation;
	private final List<MipmapPass> mipmapPasses = new ArrayList<>();
	private final String debugStringOverall;
	private FrustumHolder terrainFrustumHolder;
	private FrustumHolder entityFrustumHolder;
	private int renderedShadowEntities = 0;
	private int renderedShadowTileEntities = 0;
	private int celeritasShadowFrame = 0;
	private Profiler profiler;
	private final List<Entity> renderedEntitiesList = new ArrayList<>(64);
	private final MatrixStack shadowModelView = new MatrixStack();
	private final CelestialUniforms celestialUniforms;


	private final AdvancedShadowCullingFrustum cachedAdvancedFrustum = new AdvancedShadowCullingFrustum();
	private final Vector3f shadowLightVectorCache = new Vector3f();
	private BoxCuller cachedBoxCuller;
	private BoxCullingFrustum cachedBoxCullingFrustum;
	private BoxCuller cachedAdvancedBoxCuller;
	private BoxCuller cachedTileEntityCuller;
	private double lastBoxCullerDistance = -1;
	private double lastAdvancedBoxCullerDistance = -1;
	private double lastTileEntityCullerDistance = -1;
	private final boolean shouldRenderDH;
	private final float nearPlane, farPlane;

	public ShadowRenderer(ProgramSource shadow, PackDirectives directives, ShadowRenderTargets shadowRenderTargets, ShadowCompositeRenderer compositeRenderer) {

		this.profiler = Minecraft.getMinecraft().profiler;

		final PackShadowDirectives shadowDirectives = directives.getShadowDirectives();
		this.nearPlane = shadowDirectives.getNearPlane();
		this.farPlane = shadowDirectives.getFarPlane();

		this.halfPlaneLength = shadowDirectives.getDistance();
		this.voxelDistance = shadowDirectives.getVoxelDistance();
		this.renderDistanceMultiplier = shadowDirectives.getDistanceRenderMul();
		this.entityShadowDistanceMultiplier = shadowDirectives.getEntityShadowDistanceMul();
		this.resolution = shadowDirectives.getResolution();
		this.intervalSize = shadowDirectives.getIntervalSize();
		this.shouldRenderTerrain = shadowDirectives.shouldRenderTerrain();
		this.shouldRenderTranslucent = shadowDirectives.shouldRenderTranslucent();
		this.shouldRenderEntities = shadowDirectives.shouldRenderEntities();
		this.shouldRenderPlayer = shadowDirectives.shouldRenderPlayer();
		this.shouldRenderBlockEntities = shadowDirectives.shouldRenderBlockEntities();
		this.shouldRenderDH = shadowDirectives.isDhShadowEnabled().orElse(false);

		this.compositeRenderer = compositeRenderer;

		debugStringOverall = "half plane = " + halfPlaneLength + " meters @ " + resolution + "x" + resolution;

		this.terrainFrustumHolder = new FrustumHolder();
		this.entityFrustumHolder = new FrustumHolder();

		this.fov = shadowDirectives.getFov();
		this.targets = shadowRenderTargets;

		if (shadow != null) {
			// Assume that the shader pack is doing voxelization if a geometry shader is detected.
			// Also assume voxelization if image load / store is detected.
			this.packHasVoxelization = shadow.getGeometrySource().isPresent();
			this.packCullingState = shadowDirectives.getCullingState();
		} else {
			this.packHasVoxelization = false;
			this.packCullingState = ShadowCullState.DEFAULT;
		}

		this.sunPathRotation = directives.getSunPathRotation();
		this.celestialUniforms = new CelestialUniforms(this.sunPathRotation);

		configureSamplingSettings(shadowDirectives);
	}

	public void setUsesImages(boolean usesImages) {
		this.packHasVoxelization = packHasVoxelization || usesImages;
	}

	public static MatrixStack createShadowModelView(float sunPathRotation, float intervalSize) {
		final Vector3d entityPos = getShadowCameraAnchor(CapturedRenderingState.INSTANCE.getTickDelta());

		final MatrixStack modelView = new MatrixStack();
		ShadowMatrices.createModelViewMatrix(modelView, getShadowAngle(), intervalSize, sunPathRotation, entityPos.x, entityPos.y, entityPos.z);

		return modelView;
	}

	private MatrixStack getShadowModelView() {
		final Vector3d entityPos = getShadowCameraAnchor(CapturedRenderingState.INSTANCE.getTickDelta());

		shadowModelView.reset();
		ShadowMatrices.createModelViewMatrix(shadowModelView, getShadowAngle(), this.intervalSize, this.sunPathRotation, entityPos.x, entityPos.y, entityPos.z);
		return shadowModelView;
	}

	private static Vector3d getShadowCameraAnchor(float tickDelta) {
		return Camera.INSTANCE.getEntityPos();
	}

	private static WorldClient getLevel() {
		return Objects.requireNonNull(Minecraft.getMinecraft().world);
	}

	private static float getSkyAngle() {
        return Minecraft.getMinecraft().world.getCelestialAngle(CapturedRenderingState.INSTANCE.getTickDelta());
	}

	private static float getSunAngle() {
		final float skyAngle = getSkyAngle();

		if (skyAngle < 0.75F) {
			return skyAngle + 0.25F;
		} else {
			return skyAngle - 0.75F;
		}
	}

	private static float getShadowAngle() {
		float shadowAngle = getSunAngle();

		if (!CelestialUniforms.isDay()) {
			shadowAngle -= 0.5F;
		}

		return shadowAngle;
	}

	private void configureSamplingSettings(PackShadowDirectives shadowDirectives) {
		final ImmutableList<PackShadowDirectives.DepthSamplingSettings> depthSamplingSettings =
			shadowDirectives.getDepthSamplingSettings();

		final ImmutableList<PackShadowDirectives.SamplingSettings> colorSamplingSettings =
			shadowDirectives.getColorSamplingSettings();

		GLStateManager.glActiveTexture(GL13.GL_TEXTURE4);

		configureDepthSampler(targets.getDepthTexture().getTextureId(), depthSamplingSettings.get(0));

		configureDepthSampler(targets.getDepthTextureNoTranslucents().getTextureId(), depthSamplingSettings.get(1));

		for (int i = 0; i < Math.min(colorSamplingSettings.size(), targets.getNumColorTextures()); i++) {
			if (targets.get(i) == null) continue;
			int glTextureId = targets.get(i).getMainTexture();

			configureSampler(glTextureId, colorSamplingSettings.get(i));
		}

		GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);
	}

    private final IntBuffer swizzleBuf = BufferUtils.createIntBuffer(4);
	private void configureDepthSampler(int glTextureId, PackShadowDirectives.DepthSamplingSettings settings) {
		if (settings.getHardwareFiltering()) {
			// We have to do this or else shadow hardware filtering breaks entirely!
			RenderSystem.texParameteri(glTextureId, GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL30.GL_COMPARE_REF_TO_TEXTURE);
		}

		// Workaround for issues with old shader packs like Chocapic v4.
		// They expected the driver to put the depth value in z, but it's supposed to only
		// be available in r. So we set up the swizzle to fix that.
        swizzleBuf.rewind();
        swizzleBuf.put(new int[] { GL11.GL_RED, GL11.GL_RED, GL11.GL_RED, GL11.GL_ONE }).rewind();
		RenderSystem.texParameteriv(glTextureId, GL11.GL_TEXTURE_2D, ARBTextureSwizzle.GL_TEXTURE_SWIZZLE_RGBA, swizzleBuf);

		configureSampler(glTextureId, settings);
	}

	private void configureSampler(int glTextureId, PackShadowDirectives.SamplingSettings settings) {
		if (settings.getMipmap()) {
			final int filteringMode = settings.getNearest() ? GL11.GL_NEAREST_MIPMAP_NEAREST : GL11.GL_LINEAR_MIPMAP_LINEAR;
			mipmapPasses.add(new MipmapPass(glTextureId, filteringMode));
		}

		if (!settings.getNearest()) {
			RenderSystem.texParameteri(glTextureId, GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
			RenderSystem.texParameteri(glTextureId, GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
		} else {
			RenderSystem.texParameteri(glTextureId, GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
			RenderSystem.texParameteri(glTextureId, GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		}
	}

	private void generateMipmaps() {
		GLStateManager.glActiveTexture(GL13.GL_TEXTURE4);

		for (MipmapPass mipmapPass : mipmapPasses) {
			setupMipmappingForTexture(mipmapPass.getTexture(), mipmapPass.getTargetFilteringMode());
		}

		GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);
	}

	private void setupMipmappingForTexture(int texture, int filteringMode) {
		RenderSystem.generateMipmaps(texture, GL11.GL_TEXTURE_2D);
		RenderSystem.texParameteri(texture, GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filteringMode);
	}

	private FrustumHolder createShadowFrustum(float renderMultiplier, FrustumHolder holder) {
		// TODO: Cull entities / block entities with Advanced Frustum Culling even if voxelization is detected.
		String distanceInfo;
		String cullingInfo;
		if ((packCullingState == ShadowCullState.DISTANCE || packHasVoxelization) && packCullingState != ShadowCullState.ADVANCED && packCullingState != ShadowCullState.SAFE_ZONE) {
			double distance = halfPlaneLength * renderMultiplier;

			String reason;

			if (packCullingState == ShadowCullState.DISTANCE) {
				reason = "(set by shader pack)";
			} else /*if (packHasVoxelization)*/ {
				reason = "(voxelization detected)";
			}

			if (distance <= 0 || distance > Minecraft.getMinecraft().gameSettings.renderDistanceChunks * 16) {
				distanceInfo = Minecraft.getMinecraft().gameSettings.renderDistanceChunks * 16
					+ " blocks (capped by normal render distance)";
				cullingInfo = "disabled " + reason;
				return holder.setInfo(NON_CULLING_FRUSTUM, distanceInfo, cullingInfo);
			} else {
				distanceInfo = distance + " blocks (set by shader pack)";
				cullingInfo = "distance only " + reason;
				holder.setInfo(getOrCreateBoxCullingFrustum(distance), distanceInfo, cullingInfo);
			}
		} else {
			BoxCuller boxCuller;

			boolean hasSafeZone = packCullingState == ShadowCullState.SAFE_ZONE;

			if (hasSafeZone && renderMultiplier < 0) renderMultiplier = 1.0f;

			double distance = (hasSafeZone ? voxelDistance : halfPlaneLength) * renderMultiplier;
			String setter = "(set by shader pack)";

			if (renderMultiplier < 0) {
                // TODO: GUI
				distance = IrisVideoSettings.shadowDistance * 16; // can be zero :(
				setter = "(set by user)";
			}

			if (distance >= Minecraft.getMinecraft().gameSettings.renderDistanceChunks * 16 && !hasSafeZone) {
				distanceInfo = Minecraft.getMinecraft().gameSettings.renderDistanceChunks * 16
					+ " blocks (capped by normal render distance)";
				boxCuller = null;
			} else {
				distanceInfo = distance + " blocks " + setter;

				if (distance == 0.0 && !hasSafeZone) {
					cullingInfo = "no shadows rendered";
					return holder.setInfo(CULL_EVERYTHING_FRUSTUM, distanceInfo, cullingInfo);
				}

				boxCuller = getOrCreateAdvancedBoxCuller(distance);
			}

			cullingInfo = hasSafeZone ? "Safe Zone Frustum Culling enabled" : "Advanced Occlusion Culling enabled";

			final Vector4f shadowLightPosition = celestialUniforms.getShadowLightPositionInWorldSpace();
			shadowLightVectorCache.set(shadowLightPosition.x(), shadowLightPosition.y(), shadowLightPosition.z());
			shadowLightVectorCache.normalize();

			Matrix4fc projView = ((shouldRenderDH && DHCompat.hasRenderingEnabled()) ? DHCompat.getProjection() : RenderingState.INSTANCE.getProjectionMatrix());

			if (hasSafeZone) {
				BoxCuller distanceCuller = new BoxCuller(halfPlaneLength * renderMultiplier);
				SafeZoneCullingFrustum safeZoneFrustum = new SafeZoneCullingFrustum(
					RenderingState.INSTANCE.getModelViewMatrix(), projView,
					shadowLightVectorCache, boxCuller, distanceCuller);
				return holder.setInfo(safeZoneFrustum, distanceInfo, cullingInfo);
		} else {
			cachedAdvancedFrustum.init(RenderingState.INSTANCE.getModelViewMatrix(), projView, shadowLightVectorCache, boxCuller);
			return holder.setInfo(cachedAdvancedFrustum, distanceInfo, cullingInfo);
		}
		}

		return holder;
	}

	private FrustumHolder createEntityShadowFrustum(float renderMultiplier, FrustumHolder holder) {
		double distance = halfPlaneLength * renderMultiplier;
		String setter = "(set by shader pack)";

		if (renderMultiplier < 0) {
			distance = IrisVideoSettings.shadowDistance * 16;
			setter = "(set by user)";
		}

		int renderDistanceBlocks = Minecraft.getMinecraft().gameSettings.renderDistanceChunks * 16;
		if (distance <= 0 || distance > renderDistanceBlocks) {
			return holder.setInfo(
				NON_CULLING_FRUSTUM,
				renderDistanceBlocks + " blocks (capped by normal render distance)",
				"disabled (entity shadow stability)"
			);
		}

		return holder.setInfo(
			getOrCreateBoxCullingFrustum(distance),
			distance + " blocks " + setter,
			"distance only (entity shadow stability)"
		);
	}

	private BoxCullingFrustum getOrCreateBoxCullingFrustum(double distance) {
		if (cachedBoxCuller == null) {
			cachedBoxCuller = new BoxCuller(distance);
			cachedBoxCullingFrustum = new BoxCullingFrustum(cachedBoxCuller);
			lastBoxCullerDistance = distance;
		} else if (lastBoxCullerDistance != distance) {
			cachedBoxCuller.setMaxDistance(distance);
			lastBoxCullerDistance = distance;
		}
		return cachedBoxCullingFrustum;
	}

	private BoxCuller getOrCreateAdvancedBoxCuller(double distance) {
		if (cachedAdvancedBoxCuller == null) {
			cachedAdvancedBoxCuller = new BoxCuller(distance);
			lastAdvancedBoxCullerDistance = distance;
		} else if (lastAdvancedBoxCullerDistance != distance) {
			cachedAdvancedBoxCuller.setMaxDistance(distance);
			lastAdvancedBoxCullerDistance = distance;
		}
		return cachedAdvancedBoxCuller;
	}

	private BoxCuller getOrCreateTileEntityCuller(double distance) {
		if (cachedTileEntityCuller == null) {
			cachedTileEntityCuller = new BoxCuller(distance);
			lastTileEntityCullerDistance = distance;
		} else if (lastTileEntityCullerDistance != distance) {
			cachedTileEntityCuller.setMaxDistance(distance);
			lastTileEntityCullerDistance = distance;
		}
		return cachedTileEntityCuller;
	}

	private void setupGlState(Matrix4f projMatrix) {
		targets.getDepthSourceFb().bind();
		GLStateManager.glViewport(0, 0, resolution, resolution);

		RenderSystem.setupProjectionMatrix(projMatrix);

		// This partially works around an issue where if the front face of a mountain isn't visible, it casts no
		// shadow.
		//
		// However, it only partially resolves issues of light leaking into caves.
		//
		// TODO: Better way of preventing light from leaking into places where it shouldn't
		GLStateManager.disableCull();
	}

	private void restoreGlState() {
        GLStateManager.enableCull();

		RenderSystem.restoreProjectionMatrix();

		Minecraft mc = Minecraft.getMinecraft();
		MinecraftFramebufferHelper.restoreMainFramebuffer(false);
		GLStateManager.glViewport(0, 0, mc.displayWidth, mc.displayHeight);
	}

	private void copyPreTranslucentDepth() {
		profiler.endStartSection("translucent depth copy");

		targets.copyPreTranslucentDepth();
	}

	private void renderEntities(EntityRenderer levelRenderer, Frustum frustum, Object bufferSource, MatrixStack modelView, double cameraX, double cameraY, double cameraZ, float tickDelta) {
		profiler.startSection("cull");

		renderedEntitiesList.clear();

		final boolean playerIsSpectator = GameModeUtil.isSpectator();
		final EntityPlayer player = Minecraft.getMinecraft().player;
		final RenderManager renderManager = Minecraft.getMinecraft().getRenderManager();

		for (Entity entity : getLevel().loadedEntityList) {
			if (playerIsSpectator && entity == player) continue;
			if (Rfp2Compat.isPlayerDummy(entity)) continue;

			// Vanilla only reaches renderEntityStatic for entities that pass RenderManager#shouldRender, whose
			// first clause is Entity#isInRangeToRender3d. An entity that opts out of range rendering there
			// (placeholder/proxy entities do, while their target is missing) must not be drawn here either:
			// nothing in this pass stands in front of the lightmap lookup renderEntityStatic starts with.
			if (!renderManager.shouldRender(entity, frustum, cameraX, cameraY, cameraZ)) continue;

			renderedEntitiesList.add(entity);
		}

		profiler.endStartSection("sort");

		renderedEntitiesList.sort(ENTITY_CLASS_COMPARATOR);

		profiler.endStartSection("build geometry");

		setupEntityShadowState(modelView, cameraX, cameraY, cameraZ);
		try {
			for (Entity entity : renderedEntitiesList) {
				renderManager.renderEntityStatic(entity, tickDelta, false);
			}
		} finally {
			teardownEntityShadowState();
		}

		renderedShadowEntities = renderedEntitiesList.size();

		profiler.endSection();
	}

	// Saved RenderManager position for shadow pass
    private double savedRenderPosX, savedRenderPosY, savedRenderPosZ;
    private double savedViewerPosX, savedViewerPosY, savedViewerPosZ;
    private boolean savedRenderShadow;
    private int savedMatrixMode;
    private final Matrix4f savedRenderingStateModelView = new Matrix4f();
    private final Matrix4f savedRenderingStateProjection = new Matrix4f();

    private void setupEntityShadowState(MatrixStack modelView, double cameraX, double cameraY, double cameraZ) {
        RenderManager renderManager = Minecraft.getMinecraft().getRenderManager();
        savedRenderPosX = renderManager.renderPosX;
        savedRenderPosY = renderManager.renderPosY;
        savedRenderPosZ = renderManager.renderPosZ;
        savedViewerPosX = renderManager.viewerPosX;
        savedViewerPosY = renderManager.viewerPosY;
        savedViewerPosZ = renderManager.viewerPosZ;
        savedRenderShadow = renderManager.isRenderShadow();

        renderManager.setRenderPosition(cameraX, cameraY, cameraZ);
        renderManager.viewerPosX = cameraX;
        renderManager.viewerPosY = cameraY;
        renderManager.viewerPosZ = cameraZ;
        renderManager.setRenderShadow(false);

        IrisGlDebug.logShadowEntityState(
            "setup",
            cameraX,
            cameraY,
            cameraZ,
            renderManager.renderPosX,
            renderManager.renderPosY,
            renderManager.renderPosZ,
            renderManager.viewerPosX,
            renderManager.viewerPosY,
            renderManager.viewerPosZ,
            renderedEntitiesList.size()
        );

        GLStateManager.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GLStateManager.glPolygonOffset(1.0f, 1.0f);

        savedMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GLStateManager.glMatrixMode(GL11.GL_MODELVIEW);
        GLStateManager.glPushMatrix();
        MODELVIEW_BUFFER.clear().rewind();
        modelView.peek().getModel().get(MODELVIEW_BUFFER);
        GLStateManager.glLoadMatrix(MODELVIEW_BUFFER);
        pushShadowRenderingState(modelView);
    }

    private void teardownEntityShadowState() {
        GLStateManager.glMatrixMode(GL11.GL_MODELVIEW);
        GLStateManager.glPopMatrix();
        GLStateManager.glMatrixMode(savedMatrixMode);
        popShadowRenderingState();

        GLStateManager.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        GLStateManager.glPolygonOffset(0.0f, 0.0f);

        RenderManager renderManager = Minecraft.getMinecraft().getRenderManager();
        renderManager.setRenderPosition(savedRenderPosX, savedRenderPosY, savedRenderPosZ);
        renderManager.viewerPosX = savedViewerPosX;
        renderManager.viewerPosY = savedViewerPosY;
        renderManager.viewerPosZ = savedViewerPosZ;
        renderManager.setRenderShadow(savedRenderShadow);
    }

    private void pushShadowRenderingState(MatrixStack modelView) {
        savedRenderingStateModelView.set(RenderingState.INSTANCE.getModelViewMatrix());
        savedRenderingStateProjection.set(RenderingState.INSTANCE.getProjectionMatrix());
        RenderingState.INSTANCE.setModelViewMatrix(modelView.peek().getModel());
        RenderingState.INSTANCE.setProjectionMatrix(PROJECTION);
        ProgramUniforms.refreshActiveUniforms();
        CompatUniformManager.refreshCurrentProgramMatrices();
    }

    private void popShadowRenderingState() {
        RenderingState.INSTANCE.setModelViewMatrix(savedRenderingStateModelView);
        RenderingState.INSTANCE.setProjectionMatrix(savedRenderingStateProjection);
        CompatUniformManager.refreshCurrentProgramMatrices();
    }

	private void renderPlayerEntity(EntityRenderer levelRenderer, Frustum frustum, Object bufferSource, MatrixStack modelView, double cameraX, double cameraY, double cameraZ, float tickDelta) {
		profiler.startSection("cull");

		Entity player = Minecraft.getMinecraft().player;
		RenderManager renderManager = Minecraft.getMinecraft().getRenderManager();

		// Skip if spectating, or if the player would not be drawn in the main pass either
		if (GameModeUtil.isSpectator()) {
			profiler.endSection();
			renderedShadowEntities = 0;
			return;
		}

		if (!renderManager.shouldRender(player, frustum, cameraX, cameraY, cameraZ)) {
			profiler.endSection();
			renderedShadowEntities = 0;
			return;
		}

		profiler.endStartSection("build geometry");

		int shadowEntities = 0;

		setupEntityShadowState(modelView, cameraX, cameraY, cameraZ);
		try {
			for (Entity passenger : player.getPassengers()) {
				renderManager.renderEntityStatic(passenger, tickDelta, false);
				shadowEntities++;
			}

			if (player.getRidingEntity() != null) {
				renderManager.renderEntityStatic(player.getRidingEntity(), tickDelta, false);
				shadowEntities++;
			}

			renderManager.renderEntityStatic(player, tickDelta, false);
			shadowEntities++;
		} finally {
			teardownEntityShadowState();
		}

		renderedShadowEntities = shadowEntities;

		profiler.endSection();
	}

    private void renderTileEntity(TileEntity tile, double cameraX, double cameraY, double cameraZ, float partialTicks) {
        BlockPos pos = tile.getPos();
        if (tile.getDistanceSq(cameraX, cameraY, cameraZ) >= tile.getMaxRenderDistanceSquared()) {
            return;
        }
        int brightness = tile.getWorld().getCombinedLight(pos, 0);
        GLStateManager.setLightmapTextureCoords(GL13.GL_TEXTURE1, (float) brightness % 65536, (float) brightness / 65536);
        GLStateManager.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        TileEntityRendererDispatcher.instance.render(tile,
            (double)pos.getX() - cameraX,
            (double)pos.getY() - cameraY,
            (double)pos.getZ() - cameraZ,
            partialTicks
        );
    }

	private void renderTileEntities(Object bufferSource, MatrixStack modelView, double cameraX, double cameraY, double cameraZ, float partialTicks, boolean hasEntityFrustum) {
		profiler.startSection("build blockentities");

		int shadowTileEntities = 0;
		BoxCuller culler = null;
		if (hasEntityFrustum) {
			double distance = halfPlaneLength * (renderDistanceMultiplier * entityShadowDistanceMultiplier);
			culler = getOrCreateTileEntityCuller(distance);
			culler.setPosition(cameraX, cameraY, cameraZ);
		}

        int previousMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GLStateManager.glMatrixMode(GL11.GL_MODELVIEW);
        GLStateManager.glPushMatrix();
        MODELVIEW_BUFFER.clear().rewind();
        modelView.peek().getModel().get(MODELVIEW_BUFFER);
        GLStateManager.glLoadMatrix(MODELVIEW_BUFFER);
        pushShadowRenderingState(modelView);

        boolean beganBlockEntities = false;
        try {
            GbufferPrograms.beginBlockEntities();
            beganBlockEntities = true;
            GbufferPrograms.setBlockEntityDefaults();

            for (TileEntity tileEntity : visibleTileEntities) {
                BlockPos pos = tileEntity.getPos();
                if (hasEntityFrustum && (culler.isCulled(pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1, pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1))) {
                    continue;
                }
                renderTileEntity(tileEntity, cameraX, cameraY, cameraZ, partialTicks);

                shadowTileEntities++;
            }
            for (TileEntity tileEntity : globalTileEntities) {
                BlockPos pos = tileEntity.getPos();
                if (hasEntityFrustum && (culler.isCulled(pos.getX() - 1, pos.getY() - 1, pos.getZ() - 1, pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1))) {
                    continue;
                }
                renderTileEntity(tileEntity, cameraX, cameraY, cameraZ, partialTicks);

                shadowTileEntities++;
            }

            renderedShadowTileEntities = shadowTileEntities;
        } finally {
            if (beganBlockEntities) {
                GbufferPrograms.endBlockEntities();
            }
            GLStateManager.glMatrixMode(GL11.GL_MODELVIEW);
            GLStateManager.glPopMatrix();
            GLStateManager.glMatrixMode(previousMatrixMode);
            popShadowRenderingState();
        }

		profiler.endSection();
	}

	public void renderShadows(EntityRenderer levelRenderer, Camera playerCamera) {
        final Minecraft mc = Minecraft.getMinecraft();
        final RenderGlobal rg = mc.renderGlobal;
		final WorldRenderingPipeline renderingPipeline = Objects.requireNonNull(
			Iris.getPipelineManager().getPipelineNullable(),
			"Cannot render shadows without an active world rendering pipeline"
		);

        // We have to re-query this each frame since this changes based on whether the profiler is active
		// If the profiler is inactive, it will return InactiveProfiler.INSTANCE
		this.profiler = Minecraft.getMinecraft().profiler;

		profiler.endStartSection("shadows");
		ACTIVE = true;
		CURRENT_TARGETS = this.targets;

		// NB: We store the previous player buffers in order to be able to allow mods rendering entities in the shadow pass (Flywheel) to use the shadow buffers instead.
        // TODO: Render

		visibleTileEntities.clear();
		globalTileEntities.clear();

		final MatrixStack modelView = getShadowModelView();
		MODELVIEW.set(modelView.peek().getModel());

		final Matrix4f shadowProjection;
		if (this.fov != null) {
			// If FOV is not null, the pack wants a perspective based projection matrix. (This is to support legacy packs)
			shadowProjection = ShadowMatrices.createPerspectiveMatrix(this.fov);
		} else {
			shadowProjection = ShadowMatrices.createOrthoMatrix(halfPlaneLength, nearPlane < 0 ? -DHCompat.getRenderDistance() : nearPlane, farPlane < 0 ? DHCompat.getRenderDistance() : farPlane);
		}

		PROJECTION.set(shadowProjection);
		InternalShadowRenderingState.begin(MODELVIEW, PROJECTION, shouldRenderEntities, shouldRenderPlayer, shouldRenderBlockEntities);
		IrisGlDebug.logShadowPassState(
			"begin",
			shouldRenderTerrain,
			shouldRenderTranslucent,
			shouldRenderEntities,
			shouldRenderPlayer,
			shouldRenderBlockEntities,
			0,
			0,
			0
		);

		try {
		profiler.startSection("terrain_setup");

		if (levelRenderer instanceof CullingDataCache) {
			((CullingDataCache) levelRenderer).saveState();
		}

		profiler.startSection("initialize frustum");

		terrainFrustumHolder = createShadowFrustum(renderDistanceMultiplier, terrainFrustumHolder);
		FRUSTUM = terrainFrustumHolder.getFrustum();

		// Use the player/entity position for shadow rendering
		final float tickDelta = CapturedRenderingState.INSTANCE.getTickDelta();
		final Vector3d entityPos = playerCamera.getEntityPos();
		final double entityX = entityPos.x;
		final double entityY = entityPos.y;
		final double entityZ = entityPos.z;
		final double terrainX = entityX;
		final double terrainY = entityY;
		final double terrainZ = entityZ;
		final double renderOriginX = entityX;
		final double renderOriginY = entityY;
		final double renderOriginZ = entityZ;

		terrainFrustumHolder.getFrustum().setPosition(entityX, entityY, entityZ);

		profiler.endSection();

		// Always schedule a terrain update
		// TODO: Only schedule a terrain update if the sun / moon is moving, or the shadow map camera moved.
		// We have to ensure that we don't regenerate clouds every frame, since that's what needsUpdate ends up doing.
		// This took up to 10% of the frame time before we applied this fix! That's really bad!

		// Mark the shadow graph as needing update before terrain setup
		// Modern Celeritas does this to ensure the shadow render lists get populated
		boolean celeritasManaged = false;
		if (IrisDebugOptions.enableCeleritas()) {
			RenderDevice.enterManagedCode();
			celeritasManaged = true;
			WorldRendererCompat renderer = WorldRendererCompatBridge.instance();
			var terrainViewport = ((ViewportProvider)terrainFrustumHolder.getFrustum()).sodium$createViewport();
			renderer.markSectionGraphDirty();
			renderer.setupShadowTerrain(
				renderer.getLastViewport(),
				terrainViewport,
				new SimpleWorldRenderer.CameraState(
					entityX,
					entityY,
					entityZ,
					playerCamera.getPitch(),
					playerCamera.getYaw(),
					halfPlaneLength
				),
				this.celeritasShadowFrame++,
				false
			);
			renderer.setCurrentViewport(terrainViewport);
		}

		// Execute the vanilla terrain setup / culling routines using our shadow frustum.
        // Celeritas marks the section graph dirty above; vanilla 1.12 has no public frustum-only recull entrypoint here.

		// Don't forget to increment the frame counter! This variable is arbitrary and only used in terrain setup,
		// and if it's not incremented, the vanilla culling code will get confused and think that it's already seen
		// chunks during traversal, and break rendering in concerning ways.

		profiler.endStartSection("terrain");

		setupGlState(PROJECTION);

		if (shouldRenderTerrain) {
            mc.renderEngine.bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
			TerrainPhaseScope.runOpaque(renderingPipeline,
				() -> WorldRendererCompatBridge.instance().drawChunkLayersDeduplicated(
					OPAQUE_SHADOW_TERRAIN_LAYERS, terrainX, terrainY, terrainZ));
		}

		// Reset viewport in case terrain rendering changed it
		GLStateManager.glViewport(0, 0, resolution, resolution);

		profiler.endStartSection("entities");

		// Get the current tick delta. Normally this is the same as client.getTickDelta(), but when the game is paused,
		// it is set to a fixed value.
		// Create a constrained shadow frustum for entities to avoid rendering faraway entities in the shadow pass,
		// if the shader pack has requested it. Otherwise, use the same frustum as for terrain.
		boolean hasEntityFrustum = false;

		if (entityShadowDistanceMultiplier == 1.0F || entityShadowDistanceMultiplier < 0.0F) {
			entityFrustumHolder = createEntityShadowFrustum(renderDistanceMultiplier, entityFrustumHolder);
		} else {
			hasEntityFrustum = true;
			entityFrustumHolder = createEntityShadowFrustum(renderDistanceMultiplier * entityShadowDistanceMultiplier, entityFrustumHolder);
		}

		Frustum entityShadowFrustum = entityFrustumHolder.getFrustum();
		entityShadowFrustum.setPosition(entityX, entityY, entityZ);

		// Set viewport for entity visibility checks during shadow pass (matches modern Celeritas)
		if (IrisDebugOptions.enableCeleritas()) {
			WorldRendererCompatBridge.instance().setCurrentViewport(((ViewportProvider)entityShadowFrustum).sodium$createViewport());
		}

		if (shouldRenderEntities) {
			renderEntities(levelRenderer, entityShadowFrustum, null, modelView, renderOriginX, renderOriginY, renderOriginZ, tickDelta);
		} else if (shouldRenderPlayer) {
			renderPlayerEntity(levelRenderer, entityShadowFrustum, null, modelView, renderOriginX, renderOriginY, renderOriginZ, tickDelta);
		}

		if (shouldRenderBlockEntities) {
			renderTileEntities(null, modelView, renderOriginX, renderOriginY, renderOriginZ, tickDelta, hasEntityFrustum);
		}

		profiler.endStartSection("draw entities");

		// NB: Don't try to draw the translucent parts of entities afterwards. It'll cause problems since some
		// shader packs assume that everything drawn afterwards is actually translucent and should cast a colored
		// shadow...

		copyPreTranslucentDepth();

		profiler.endStartSection("translucent terrain");

		// TODO (Iris): Prevent these calls from scheduling translucent sorting...
		// It doesn't matter a ton, since this just means that they won't be sorted in the getNormal rendering pass.
		// Just something to watch out for, however...
		if (shouldRenderTranslucent) {
			TerrainPhaseScope.runTranslucent(renderingPipeline,
				() -> WorldRendererCompatBridge.instance().drawChunkLayer(
					BlockRenderLayer.TRANSLUCENT, terrainX, terrainY, terrainZ));
		}

		if (celeritasManaged) {
			RenderDevice.exitManagedCode();
			celeritasManaged = false;
		}

		// Note: Apparently tripwire isn't rendered in the shadow pass.

		profiler.endStartSection("generate mipmaps");

		generateMipmaps();

		profiler.endStartSection("restore gl state");

		restoreGlState();

		if (levelRenderer instanceof CullingDataCache) {
			((CullingDataCache) levelRenderer).restoreState();
		}

		IrisGlDebug.logShadowPassState(
			"after-render",
			shouldRenderTerrain,
			shouldRenderTranslucent,
			shouldRenderEntities,
			shouldRenderPlayer,
			shouldRenderBlockEntities,
			IrisDebugOptions.enableCeleritas() ? WorldRendererCompatBridge.instance().getVisibleChunkCount() : -1,
			renderedShadowEntities,
			renderedShadowTileEntities
		);

		profiler.endStartSection("shadowcomp");

		if (compositeRenderer != null) compositeRenderer.renderAll();

		if (celeritasManaged) {
			RenderDevice.exitManagedCode();
		}
		ACTIVE = false;
		CURRENT_TARGETS = null;
		profiler.endSection();
		profiler.endStartSection("updatechunks");
		} finally {
			InternalShadowRenderingState.end();
			ACTIVE = false;
			CURRENT_TARGETS = null;
		}
	}

	static final class TerrainPhaseScope {
		private TerrainPhaseScope() {
		}

		static void runOpaque(WorldRenderingPipeline pipeline, Runnable action) {
			run(pipeline, WorldRenderingPhase.TERRAIN_SOLID, action);
		}

		static void runTranslucent(WorldRenderingPipeline pipeline, Runnable action) {
			run(pipeline, WorldRenderingPhase.TERRAIN_TRANSLUCENT, action);
		}

		private static void run(WorldRenderingPipeline pipeline, WorldRenderingPhase phase, Runnable action) {
			WorldRenderingPhase previousPhase = pipeline.getPhase();
			pipeline.setPhase(phase);
			try {
				action.run();
			} finally {
				pipeline.setPhase(previousPhase);
			}
		}
	}

	public void addDebugText(List<String> messages) {
		if (IrisVideoSettings.getOverriddenShadowDistance(IrisVideoSettings.shadowDistance) == 0) {
			messages.add("[" + Iris.MODNAME + "] Shadow Maps: off, shadow distance 0");
			return;
		}

		String shadowTerrain = WorldRendererCompatBridge.instance().getChunksDebugString();
		if (Iris.getIrisConfig().areDebugOptionsEnabled()) {
			messages.add("[" + Iris.MODNAME + "] Shadow Maps: " + debugStringOverall);
			messages.add("[" + Iris.MODNAME + "] Shadow Distance Terrain: " + terrainFrustumHolder.getDistanceInfo() + " Entity: " + entityFrustumHolder.getDistanceInfo());
			messages.add("[" + Iris.MODNAME + "] Shadow Culling Terrain: " + terrainFrustumHolder.getCullingInfo() + " Entity: " + entityFrustumHolder.getCullingInfo());
			messages.add("[" + Iris.MODNAME + "] Shadow Projection: " + getProjectionInfo());
			messages.add("[" + Iris.MODNAME + "] Shadow Terrain: " + shadowTerrain
				+ (shouldRenderTerrain ? "" : " (no terrain) ") + (shouldRenderTranslucent ? "" : "(no translucent)"));
			messages.add("[" + Iris.MODNAME + "] Shadow Entities: " + getEntitiesDebugString());
			messages.add("[" + Iris.MODNAME + "] Shadow Block Entities: " + getTileEntitiesDebugString());
		} else {
			messages.add("[" + Iris.MODNAME + "] Shadow info: " + shadowTerrain);
			messages.add("[" + Iris.MODNAME + "] E: " + renderedShadowEntities);
			messages.add("[" + Iris.MODNAME + "] BE: " + renderedShadowTileEntities);
		}
	}

	private String getProjectionInfo() {
		return "Near: " + nearPlane + " Far: " + farPlane + " distance " + halfPlaneLength;
	}

	private String getEntitiesDebugString() {
		return (shouldRenderEntities || shouldRenderPlayer) ? (renderedShadowEntities + "/" + Minecraft.getMinecraft().world.loadedEntityList.size()) : "disabled by pack";
	}

	private String getTileEntitiesDebugString() {
		return shouldRenderBlockEntities ? (renderedShadowTileEntities + "/" + Minecraft.getMinecraft().world.loadedTileEntityList.size()) : "disabled by pack";
	}

	private static class MipmapPass {
		private final int texture;
		private final int targetFilteringMode;

		public MipmapPass(int texture, int targetFilteringMode) {
			this.texture = texture;
			this.targetFilteringMode = targetFilteringMode;
		}

		public int getTexture() {
			return texture;
		}

		public int getTargetFilteringMode() {
			return targetFilteringMode;
		}
	}
}
