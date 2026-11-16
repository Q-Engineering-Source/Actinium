package com.dhj.actinium.render;

import com.dhj.actinium.api.clouds.CloudLayer;
import com.dhj.actinium.api.clouds.CloudLayerProvider;
import com.dhj.actinium.compat.galaxyspace.GalaxySpaceClouds;
import com.gtnewhorizons.angelica.glsm.GLSMMatrices;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;
import com.gtnewhorizons.angelica.glsm.states.ViewportState;
import net.coderbot.iris.Iris;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import net.coderbot.iris.shaderpack.CloudSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.IRenderHandler;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.dhj.actinium.render.CloudDisc.CELLS_PER_CHUNK;
import static com.dhj.actinium.render.CloudDisc.MARGIN_CELLS;
import static com.dhj.actinium.render.CloudLayerRenderer.MODE_FANCY;
import static com.dhj.actinium.render.CloudLayerRenderer.MODE_FAST;

public class CloudRenderer extends IRenderHandler implements IResourceManagerReloadListener {

    private static final ResourceLocation VANILLA_TEXTURE = new ResourceLocation("textures/environment/clouds.png");
    private static final float MAX_FAR_PLANE_DISTANCE = 65536.0f;
    private static final float FAR_PLANE_MARGIN = 1.05f;
    private static CloudRenderer instance;

    private final Minecraft mc = Minecraft.getMinecraft();
    private final CloudRenderResources resources = new CloudRenderResources();
    private final CloudView cloudView = new CloudView();
    private final Matrix4f cloudProjection = new Matrix4f();
    private final FloatBuffer projectionBuffer = BufferUtils.createFloatBuffer(16);
    private final Map<String, CloudLayerRenderer> layers = new HashMap<>();
    private final List<CloudLayerRenderer> drawOrder = new ArrayList<>();
    private final Set<String> activeIds = new HashSet<>();
    private double sortCameraY;
    private final Comparator<CloudLayerRenderer> farthestFirst = (a, b) -> Double.compare(distanceToLayer(b.layer, sortCameraY), distanceToLayer(a.layer, sortCameraY));
    private WorldClient world;
    private IRenderHandler dimensionRenderer;
    private int cloudMode = MODE_FAST, renderDistance, scaleMult = 1, lastCloudTicks;
    private boolean enabled;

    public CloudRenderer() {
        ((IReloadableResourceManager) mc.getResourceManager()).registerReloadListener(this);
    }

    public static CloudRenderer getCloudRenderer() {
        if (instance == null) instance = new CloudRenderer();
        return instance;
    }

    public void checkSettings() {
        if (world != mc.world) {
            clearLayers();
            resources.clear();
            world = mc.world;
            lastCloudTicks = 0;
        }
        final int configuredClouds = mc.gameSettings.clouds;
        cloudMode = configuredClouds == 2 ? MODE_FANCY : MODE_FAST;
        renderDistance = CloudRenderSettings.cloudRenderDistance(mc.gameSettings.renderDistanceChunks);
        scaleMult = CloudRenderSettings.cloudScale();
        enabled = CloudRenderSettings.cloudsVisible(configuredClouds) && world != null;
        if (!enabled && !layers.isEmpty()) clearLayers();
    }

    /**
     * Records the renderer the dimension supplied before Actinium took over the hook.
     *
     * <p>A dimension that installs its own {@code IRenderHandler} keeps it here. Galaxy Space
     * dimensions are rebuilt from their known parameters, anything else is delegated to unchanged so
     * modded cloud rendering keeps working.</p>
     */
    public void setDimensionRenderer(IRenderHandler renderer) {
        this.dimensionRenderer = renderer == this ? null : renderer;
    }

    public boolean render(int cloudTicks, float partialTicks) {
        if (mc.world == null) return false;
        final IRenderHandler dimension = dimensionRenderer;
        if (dimension != null && !GalaxySpaceClouds.supports(mc.world, dimension)) {
            dimension.render(partialTicks, mc.world, mc);
            return true;
        }
        return renderLayers(cloudTicks, partialTicks);
    }

    /**
     * Forge entry point. The dimension hands cloud drawing to this renderer through
     * {@code WorldProvider.getCloudRenderer()}, so no dimension handler is threaded through.
     */
    @Override
    public void render(float partialTicks, WorldClient world, Minecraft minecraft) {
        render(minecraft.renderGlobal.cloudTickCounter, partialTicks);
    }

    private boolean renderLayers(int cloudTicks, float partialTicks) {
        checkSettings();
        if (!enabled || mc.getRenderViewEntity() == null) return false;
        final WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        if (pipeline != null && pipeline.getCloudSetting() == CloudSetting.OFF) return false;
        lastCloudTicks = cloudTicks;

        final List<CloudLayer> descriptions = describeLayers(cloudTicks, partialTicks);
        activeIds.clear();
        drawOrder.clear();
        for (CloudLayer description : descriptions) {
            if (!activeIds.add(description.id())) {
                throw new IllegalArgumentException("Duplicate cloud layer id: " + description.id());
            }
            CloudLayerRenderer renderer = layers.get(description.id());
            if (renderer != null && !renderer.texture.location.equals(description.texture())) {
                renderer.delete();
                layers.remove(description.id());
                renderer = null;
            }
            if (renderer == null) {
                renderer = new CloudLayerRenderer(resources, resources.texture(description.texture()));
                layers.put(description.id(), renderer);
            }
            renderer.configure(description, cloudMode, renderDistance, scaleMult);
            if (description.alpha() > 0.0f) drawOrder.add(renderer);
        }
        if (layers.size() != activeIds.size()) layers.entrySet().removeIf(entry -> {
            if (activeIds.contains(entry.getKey())) return false;
            entry.getValue().delete();
            return true;
        });

        final ViewportState viewport = GLSMRasterizer.getViewportState();
        final Matrix4f projection = GLSMMatrices.getProjectionMatrix();
        cloudView.update(projection, GLSMMatrices.getModelViewMatrix(), viewport.width, viewport.height);
        cloudProjection.set(projection);
        final boolean extended = extendFarPlane(cloudProjection, requiredFarPlane(descriptions) * FAR_PLANE_MARGIN);
        final Entity view = mc.getRenderViewEntity();
        final double cameraY = view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTicks + cloudView.offsetY;
        sortCameraY = cameraY;
        drawOrder.sort(farthestFirst);
        boolean rendered = descriptions.isEmpty();
        if (extended) pushCloudProjection();
        try {
            for (CloudLayerRenderer renderer : drawOrder) rendered |= renderer.render(partialTicks, cloudView, cloudProjection);
        } finally {
            if (extended) popCloudProjection();
        }
        return rendered;
    }

    private double distanceToLayer(CloudLayer layer, double cameraY) {
        final double bottom = layer.height() + 0.33;
        final double top = bottom + (cloudMode == MODE_FANCY ? layer.cellHeight() * scaleMult : 0.0);
        return Math.max(0.0, Math.max(bottom - cameraY, cameraY - top));
    }

    private List<CloudLayer> describeLayers(int cloudTicks, float partialTicks) {
        if (world.provider instanceof CloudLayerProvider provider) {
            return provider.getCloudLayers(world, cloudTicks, partialTicks);
        }
        if (GalaxySpaceClouds.supports(world, dimensionRenderer)) {
            return GalaxySpaceClouds.getLayers(world, cloudTicks, partialTicks);
        }
        if (!world.provider.isSurfaceWorld()) return List.of();
        final float height = world.provider.getCloudHeight();
        if (!Float.isFinite(height)) return List.of();
        final Vec3d color = world.getCloudColour(partialTicks);
        return List.of(new CloudLayer("minecraft:clouds", VANILLA_TEXTURE, height,
            12.0f, 4.0f, 12.0f, (cloudTicks + (double) partialTicks) * 0.03 / (12.0 * scaleMult),
            0.33000001311302185, (float) color.x, (float) color.y, (float) color.z, CloudUniforms.ALPHA));
    }

    private float requiredFarPlane(List<CloudLayer> descriptions) {
        if (renderDistance <= 0) return 0.0f;
        float required = 0.0f;
        final Entity view = mc.getRenderViewEntity();
        final float cameraY = view == null ? 0.0f : (float) (view.posY + cloudView.offsetY);
        for (CloudLayer layer : descriptions) {
            if (layer.alpha() == 0.0f) continue;
            final float width = layer.cellWidth() * scaleMult;
            final int radiusChunks = (int) Math.ceil(renderDistance * 64.0f / (CELLS_PER_CHUNK * width));
            final int marginChunks = (MARGIN_CELLS + CELLS_PER_CHUNK - 1) / CELLS_PER_CHUNK;
            float radius = (radiusChunks + marginChunks) * CELLS_PER_CHUNK * width;
            if (cloudMode != MODE_FANCY) radius *= (float) Math.sqrt(2);
            radius += MARGIN_CELLS * width;
            final float verticalSlack = Math.abs(layer.height() + 0.33f - cameraY) + layer.cellHeight() * scaleMult;
            required = Math.max(required, Math.min(radius + verticalSlack, MAX_FAR_PLANE_DISTANCE));
        }
        return required;
    }

    private void pushCloudProjection() {
        projectionBuffer.clear();
        cloudProjection.get(projectionBuffer);
        GLSMMatrices.glMatrixMode(GL11.GL_PROJECTION);
        GLSMMatrices.glPushMatrix();
        GLSMMatrices.glLoadMatrix(projectionBuffer);
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
    }

    private void popCloudProjection() {
        GLSMMatrices.glMatrixMode(GL11.GL_PROJECTION);
        GLSMMatrices.glPopMatrix();
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
    }

    /**
     * Pushes the projection far plane out to hold the cloud disc without touching the projection the
     * world pass uses. The near plane and field of view come from the source matrix, so only the depth
     * range changes.
     *
     * @return whether the far plane moved
     */
    private static boolean extendFarPlane(Matrix4f projection, float required) {
        if (!(required > 0.0f)) return false;
        final float m22 = projection.m22();
        final float m23 = projection.m23();
        final float near = m23 / (m22 - 1.0f);
        final float far = m23 / (m22 + 1.0f);
        if (!(near > 0.0f) || !(far > near) || far >= required) return false;
        projection.m22(-(required + near) / (required - near));
        projection.m23(-2.0f * required * near / (required - near));
        return true;
    }

    private void clearLayers() {
        for (CloudLayerRenderer layer : layers.values()) layer.delete();
        layers.clear();
        drawOrder.clear();
        activeIds.clear();
    }

    @Override
    public void onResourceManagerReload(IResourceManager resourceManager) {
        clearLayers();
        resources.clear();
    }
}
