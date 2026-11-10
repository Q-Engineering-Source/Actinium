package com.gtnewhorizons.angelica.render;

import com.dhj.actinium.render.CloudRenderSettings;
import com.gtnewhorizons.angelica.api.clouds.CloudLayer;
import com.gtnewhorizons.angelica.api.clouds.CloudLayerProvider;
import com.gtnewhorizons.angelica.glsm.GLSMMatrices;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.stacks.ViewPortStateStack;
import net.coderbot.iris.Iris;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import net.coderbot.iris.shaderpack.CloudSetting;
import net.coderbot.iris.uniforms.IrisExclusiveUniforms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldProvider;
import net.minecraftforge.client.IRenderHandler;
import org.joml.Matrix4fStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static com.gtnewhorizons.angelica.render.CloudDisc.CELLS_PER_CHUNK;
import static com.gtnewhorizons.angelica.render.CloudDisc.MARGIN_CELLS;

/**
 * Draws the cloud layers of the current world and owns every GL object and native buffer they use.
 *
 * <p>Per frame the provider chain decides the layer list: the dimension's cloud handler when it publishes
 * layers through {@link CloudLayerProvider}, then the dimension provider, then the vanilla surface layer.
 * A handler that does not publish layers keeps its own pass, so Forge custom cloud renderers still draw
 * their clouds; {@link #render} returns {@code false} for them and leaves the vanilla entry point in place.
 *
 * <p>Every layer keeps one {@link CloudLayerRenderer} keyed by layer id. A layer whose texture changed is
 * released and rebuilt, because the decoded shape and the texture binding belong to the texture. Leaving a
 * world, reloading resources and disabling clouds all run the same release, which deletes every layer and
 * then the shared programs, so no GL name or native buffer outlives the world.
 *
 * <p>The renderer draws into the framebuffer, viewport and program state the caller established, at the
 * plane distances {@code EntityRenderer.renderCloudsCheck} set up. It owns no framebuffer and starts no
 * render pass; with a shader pack in use the pack's cloud program draws the layers instead.
 */
public final class CloudRenderer implements IResourceManagerReloadListener {

    /** Vanilla cloud texture, drawn on the layer every surface dimension gets. */
    private static final ResourceLocation VANILLA_TEXTURE = new ResourceLocation("textures/environment/clouds.png");
    /** Layer id of the vanilla cloud layer, distinct from every provider supplied layer. */
    private static final String VANILLA_LAYER_ID = "minecraft:clouds";
    /** Upper bound of the far plane the cloud pass asks for, in blocks. */
    static final float MAX_FAR_PLANE_DISTANCE = 65536.0F;
    /** Cloud scale multiplier; the NotFine cloud scale option is not ported, so the scale stays vanilla. */
    static final int SCALE_MULT = 1;
    /** Vanilla cloud cell width in blocks, along both horizontal axes. */
    private static final float CELL_WIDTH = 12.0F;
    /** Vanilla cloud cell thickness in blocks, used by the fancy top face. */
    private static final float CELL_HEIGHT = 4.0F;
    /** Vanilla cloud texture width in blocks, the divisor that turns block positions into cell coordinates. */
    private static final float COORDINATE_WIDTH = 12.0F;
    /** Scroll of the vanilla layer along X in cells per tick. */
    private static final double VANILLA_SCROLL_PER_TICK = 0.03 / (CELL_WIDTH * SCALE_MULT);
    /** Offset of the vanilla layer along Z in cells, the constant the vanilla fast path adds. */
    private static final double VANILLA_OFFSET_Z = 0.33000001311302185;
    /** Texture width reported before the vanilla texture has been bound once. */
    private static final int DEFAULT_TEXTURE_WIDTH = 256;
    /** Layers of a world this renderer owns but draws nothing for. */
    private static final Layers NO_LAYERS = new Layers(true, List.of());

    private static CloudRenderer instance;

    /**
     * Period inputs of the {@code cloudTime} uniform. The shader module cannot read the cloud renderer, so the
     * renderer hands its texture width and scale over through the uniform's provider.
     */
    private static final IrisExclusiveUniforms.CloudScrollPeriod CLOUD_SCROLL_PERIOD =
        new IrisExclusiveUniforms.CloudScrollPeriod() {
            @Override
            public int textureWidth() {
                return getCloudTextureWidth();
            }

            @Override
            public int scale() {
                return getScaleMult();
            }
        };

    static {
        IrisExclusiveUniforms.setCloudScrollPeriod(CLOUD_SCROLL_PERIOD);
    }

    private final Minecraft mc = Minecraft.getMinecraft();
    final CloudRenderResources resources = new CloudRenderResources();
    final Map<String, CloudLayerRenderer> layers = new HashMap<>();
    private final CloudView cloudView = new CloudView();
    private WorldClient world;
    private int cloudMode = CloudLayerRenderer.MODE_FAST;
    private int renderDistance;
    private int lastCloudTicks;
    private boolean enabled;

    CloudRenderer() {
    }

    /**
     * Returns the renderer of the client, creating it and registering its resource reload listener on the
     * first call.
     */
    public static CloudRenderer getCloudRenderer() {
        if (instance == null) {
            instance = new CloudRenderer();
            instance.registerReloadListener();
        }
        return instance;
    }

    /**
     * Releases the cloud resources of the current world. A no-op before the first cloud frame, which is the
     * state a world unload without clouds leaves behind.
     */
    public static void release() {
        if (instance != null) instance.invalidateWorld();
    }

    /** Re-evaluates cloud settings even when no cloud pass runs, so disabled clouds release their resources. */
    public static void tick() {
        if (instance != null) instance.checkSettings(instance.mc.world);
    }

    /**
     * Returns the width of the vanilla cloud texture in texels, or the vanilla texture size before the first
     * bind. Pack uniforms that scroll the cloud texture with the renderer need the same width the geometry
     * uses, so the value is reported even while nothing has been drawn yet.
     */
    public static int getCloudTextureWidth() {
        if (instance == null) return DEFAULT_TEXTURE_WIDTH;
        final int width = instance.resources.texture(VANILLA_TEXTURE).width;
        return width > 0 ? width : DEFAULT_TEXTURE_WIDTH;
    }

    /** Returns the cloud scale multiplier the renderer draws with. */
    public static int getScaleMult() {
        return SCALE_MULT;
    }

    /**
     * Draws the cloud pass of a world in place of {@code RenderGlobal.renderClouds}.
     *
     * @param cloudTicks the world's cloud tick counter, the scroll source of animated layers
     * @param partialTicks frame interpolation of the render pass
     * @param world client world the pass draws in
     * @return whether this renderer owns the pass; {@code false} leaves the vanilla entry point in place,
     *     which is what a Forge custom cloud renderer needs
     */
    public boolean render(int cloudTicks, float partialTicks, WorldClient world) {
        if (!supportedBackend()) return false;

        final Layers described = describeLayers(world, cloudTicks, partialTicks);
        if (!described.owned()) return false;

        checkSettings(world);
        if (!enabled || mc.getRenderViewEntity() == null) return true;
        if (cloudsDisabledInPack()) return true;

        lastCloudTicks = cloudTicks;
        final List<CloudLayer> descriptions = described.layers();
        final LayerPlan plan = planLayers(activeTextures(), descriptions);
        for (String id : plan.retired()) {
            layers.remove(id).delete();
        }
        for (CloudLayer description : plan.created()) {
            layers.put(description.id(), new CloudLayerRenderer(resources, resources.texture(description.texture())));
        }

        final List<CloudLayerRenderer> drawOrder = new ArrayList<>(descriptions.size());
        for (CloudLayer description : descriptions) {
            final CloudLayerRenderer layer = layers.get(description.id());
            layer.configure(description, cloudMode, renderDistance, SCALE_MULT);
            if (description.alpha() > 0.0F) drawOrder.add(layer);
        }

        final ViewPortStateStack viewport = GLSMRasterizer.getViewportState();
        final Matrix4fStack projection = GLSMMatrices.getProjectionMatrix();
        final Matrix4fStack modelView = GLSMMatrices.getModelViewMatrix();
        cloudView.update(projection, modelView, viewport.width, viewport.height);

        final Entity view = mc.getRenderViewEntity();
        final double cameraY = view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTicks + cloudView.offsetY;
        final Comparator<CloudLayerRenderer> farthestFirst = (a, b) -> Double.compare(
            distanceToLayer(b.layer, cameraY, cloudMode, SCALE_MULT),
            distanceToLayer(a.layer, cameraY, cloudMode, SCALE_MULT));
        drawOrder.sort(farthestFirst);
        for (CloudLayerRenderer layer : drawOrder) layer.render(partialTicks, cloudView);
        return true;
    }

    /**
     * Returns the far plane distance the cloud pass needs, in blocks.
     *
     * <p>The projection is the one {@code EntityRenderer.renderCloudsCheck} builds for the pass; the entry
     * point raises it to this distance so a cloud disc is not clipped by the terrain far plane.
     *
     * @return the distance, zero when no clouds are drawn
     */
    public float getRequiredFarPlaneDistance() {
        checkSettings(mc.world);
        if (!enabled || renderDistance <= 0) return 0.0F;

        final Layers described = describeLayers(mc.world, lastCloudTicks, 1.0F);
        if (!described.owned()) return 0.0F;
        final Entity view = mc.getRenderViewEntity();
        final double cameraY = view == null ? 0.0 : view.posY + cloudView.offsetY;
        return requiredFarPlaneDistance(renderDistance, cloudMode, described.layers(), cameraY, SCALE_MULT);
    }

    /**
     * Releases every layer of the current world and then the shared programs, and forgets the world.
     *
     * <p>Each layer stops and joins its pending builds, deletes its meshes and its face mesh and frees the
     * native buffers before the programs are deleted, so the release cannot leave a layer pointing at a
     * deleted program. Safe to repeat and safe before the first frame.
     */
    public void invalidateWorld() {
        releaseLayers();
        world = null;
        lastCloudTicks = 0;
        enabled = false;
    }

    @Override
    public void onResourceManagerReload(IResourceManager resourceManager) {
        releaseLayers();
    }

    private void registerReloadListener() {
        ((IReloadableResourceManager) mc.getResourceManager()).registerReloadListener(this);
    }

    /**
     * Re-reads the world and the cloud option and releases the layers when either changed.
     *
     * @param world world the pass draws in
     */
    private void checkSettings(WorldClient world) {
        if (this.world != world) {
            releaseLayers();
            this.world = world;
            lastCloudTicks = 0;
        }
        final int mode = CloudRenderSettings.effectiveCloudMode(mc.gameSettings.clouds);
        cloudMode = mode == 2 ? CloudLayerRenderer.MODE_FANCY : CloudLayerRenderer.MODE_FAST;
        renderDistance = mc.gameSettings.renderDistanceChunks;
        enabled = mode != 0 && world != null && supportedBackend() && !cloudsDisabledInPack();
        if (!enabled) releaseLayers();
    }

    private void releaseLayers() {
        for (CloudLayerRenderer layer : layers.values()) layer.delete();
        layers.clear();
        resources.clear();
    }

    /** Textures of the layers currently held, keyed by layer id. */
    private Map<String, ResourceLocation> activeTextures() {
        final Map<String, ResourceLocation> textures = new HashMap<>(layers.size());
        for (Map.Entry<String, CloudLayerRenderer> entry : layers.entrySet()) {
            textures.put(entry.getKey(), entry.getValue().texture.location);
        }
        return textures;
    }

    private Layers describeLayers(WorldClient world, int cloudTicks, float partialTicks) {
        final WorldProvider provider = world.provider;
        return describeLayers(world, cloudTicks, partialTicks, provider.getCloudRenderer(),
            provider instanceof CloudLayerProvider layers ? layers : null,
            provider.isSurfaceWorld() ? () -> vanillaLayer(world, cloudTicks, partialTicks) : null);
    }

    /**
     * Resolves the layers of one frame and whether this renderer owns the pass.
     *
     * <p>The order is fixed: a cloud handler that publishes layers, then the dimension provider, then the
     * vanilla layer. The handler only keeps its own pass when neither it nor the dimension publishes layers,
     * which is the case of the Forge custom cloud renderers and of AsmodeusCore's cloud provider.
     *
     * @param world client world handed to the providers
     * @param cloudTicks the world's cloud tick counter
     * @param partialTicks frame interpolation of the pass
     * @param handler the dimension's cloud handler, {@code null} when it has none
     * @param dimensionProvider the dimension itself when it publishes layers, {@code null} otherwise
     * @param vanillaLayer describes the vanilla layer, {@code null} when the dimension has no vanilla clouds
     * @return the layers of the frame and whether this renderer owns the pass
     */
    static Layers describeLayers(WorldClient world, int cloudTicks, float partialTicks, IRenderHandler handler,
                                 CloudLayerProvider dimensionProvider, Supplier<CloudLayer> vanillaLayer) {
        if (handler instanceof CloudLayerProvider provider) {
            return new Layers(true, provider.getCloudLayers(world, cloudTicks, partialTicks));
        }
        if (dimensionProvider != null) {
            return new Layers(true, dimensionProvider.getCloudLayers(world, cloudTicks, partialTicks));
        }
        if (handler != null) return new Layers(false, List.of());
        if (vanillaLayer == null) return NO_LAYERS;

        final CloudLayer layer = vanillaLayer.get();
        return layer == null ? NO_LAYERS : new Layers(true, List.of(layer));
    }

    /**
     * Plans the per-layer renderers of one frame.
     *
     * <p>A renderer survives while its id is described with the same texture. A changed texture replaces it,
     * and an id that is no longer described is released so its GL names and native buffers do not outlive
     * the provider that asked for it.
     *
     * @param activeTextures texture of every renderer currently held, by layer id
     * @param described layers of the frame
     * @return the ids to retire and the layers to create
     * @throws IllegalArgumentException when two layers share an id, which would make the per-id state
     *     ambiguous
     */
    static LayerPlan planLayers(Map<String, ResourceLocation> activeTextures, List<CloudLayer> described) {
        final Set<String> describedIds = new HashSet<>();
        for (CloudLayer layer : described) {
            if (!describedIds.add(layer.id())) {
                throw new IllegalArgumentException("Duplicate cloud layer id: " + layer.id());
            }
        }

        final List<String> retired = new ArrayList<>();
        for (String id : activeTextures.keySet()) {
            if (!describedIds.contains(id)) retired.add(id);
        }

        final List<CloudLayer> created = new ArrayList<>();
        for (CloudLayer layer : described) {
            final ResourceLocation active = activeTextures.get(layer.id());
            if (active == null) {
                created.add(layer);
            } else if (!active.equals(layer.texture())) {
                retired.add(layer.id());
                created.add(layer);
            }
        }
        return new LayerPlan(List.copyOf(retired), List.copyOf(created));
    }

    /**
     * Computes the far plane distance the cloud discs need, in blocks.
     *
     * <p>The radius covers the render distance in cells plus the margin kept beyond it. A fast plate draws
     * one square seen on its diagonal, so it needs the diagonal factor. The layer is then offset vertically
     * by its distance from the camera plus one cell of thickness.
     *
     * @param renderDistanceChunks render distance of the frame in chunks
     * @param mode {@link CloudLayerRenderer#MODE_FAST} or {@link CloudLayerRenderer#MODE_FANCY}
     * @param layers layers drawn this frame
     * @param cameraY camera height, the reference of the vertical offset
     * @param scale cloud scale multiplier
     * @return the distance, capped at {@link #MAX_FAR_PLANE_DISTANCE}, zero when nothing is drawn
     */
    static float requiredFarPlaneDistance(int renderDistanceChunks, int mode, List<CloudLayer> layers,
                                          double cameraY, int scale) {
        if (renderDistanceChunks <= 0) return 0.0F;

        final int marginChunks = (MARGIN_CELLS + CELLS_PER_CHUNK - 1) / CELLS_PER_CHUNK;
        float required = 0.0F;
        for (CloudLayer layer : layers) {
            if (layer.alpha() == 0.0F) continue;

            final float width = layer.cellWidth() * scale;
            final int radiusChunks = (int) Math.ceil(renderDistanceChunks * 64.0F / (CELLS_PER_CHUNK * width));
            float radius = (radiusChunks + marginChunks) * CELLS_PER_CHUNK * width;
            if (mode != CloudLayerRenderer.MODE_FANCY) radius *= (float) Math.sqrt(2.0);
            radius += MARGIN_CELLS * width;

            final float verticalSlack = (float) Math.abs(layer.height() + 0.33F - cameraY)
                + layer.cellHeight() * scale;
            required = Math.max(required, Math.min(radius + verticalSlack, MAX_FAR_PLANE_DISTANCE));
        }
        return required;
    }

    /**
     * Returns how far the camera is from a layer slab, in blocks, used to draw the farthest layer first.
     *
     * @param layer layer to measure
     * @param cameraY camera height
     * @param mode fast plates are flat, fancy layers have the cell thickness
     * @param scale cloud scale multiplier
     * @return the distance, zero when the camera is inside the slab
     */
    static double distanceToLayer(CloudLayer layer, double cameraY, int mode, int scale) {
        final double bottom = layer.height() + 0.33;
        final double top = bottom + (mode == CloudLayerRenderer.MODE_FANCY ? layer.cellHeight() * scale : 0.0);
        return Math.max(0.0, Math.max(bottom - cameraY, cameraY - top));
    }

    private static CloudLayer vanillaLayer(WorldClient world, int cloudTicks, float partialTicks) {
        final float height = world.provider.getCloudHeight();
        if (!Float.isFinite(height)) return null;

        final Vec3d colour = world.getCloudColour(partialTicks);
        return new CloudLayer(VANILLA_LAYER_ID, VANILLA_TEXTURE, height, CELL_WIDTH, CELL_HEIGHT, COORDINATE_WIDTH,
            (cloudTicks + (double) partialTicks) * VANILLA_SCROLL_PER_TICK, VANILLA_OFFSET_Z,
            (float) colour.x, (float) colour.y, (float) colour.z, CloudUniforms.ALPHA);
    }

    /**
     * Reports whether the renderer can draw on the current backend. The cloud programs are desktop GLSL 330,
     * which only MobileGlues translates among the ES stacks, so the other ES contexts keep vanilla clouds.
     */
    private static boolean supportedBackend() {
        return !RenderSystem.isGLES() || RenderSystem.isMobileGlues();
    }

    /** Reports whether the shader pack in use switches clouds off, which leaves the pass empty. */
    private static boolean cloudsDisabledInPack() {
        if (!Iris.enabled) return false;
        final WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        return pipeline != null && pipeline.getCloudSetting() == CloudSetting.OFF;
    }

    /**
     * Layers of one frame and whether this renderer owns the pass.
     *
     * @param owned whether the pass belongs to this renderer, {@code false} leaves it to the vanilla entry
     *     point and the Forge cloud handler
     * @param layers layers to draw, empty when the pass draws nothing
     */
    record Layers(boolean owned, List<CloudLayer> layers) {
    }

    /**
     * Per-layer renderer changes of one frame.
     *
     * @param retired ids whose renderer is deleted before the frame is drawn
     * @param created layers whose renderer is created for the frame
     */
    record LayerPlan(List<String> retired, List<CloudLayer> created) {
    }
}
