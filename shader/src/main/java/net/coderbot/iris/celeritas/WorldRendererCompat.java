package net.coderbot.iris.celeritas;

import net.minecraft.util.BlockRenderLayer;
import dhj.embeddedt.embeddium.impl.render.terrain.SimpleWorldRenderer;
import dhj.embeddedt.embeddium.impl.render.viewport.Viewport;

import java.util.Collection;

/**
 * World-terrain rendering surface needed by the shadow pass.
 *
 * <p>Implemented by the host mod's world renderer and registered via
 * {@link WorldRendererCompatBridge}, so the shader module does not depend on
 * the host's rendering implementation.
 */
public interface WorldRendererCompat {

    int getVisibleChunkCount();

    String getChunksDebugString();

    void setCurrentViewport(Viewport viewport);

    /**
     * @return the viewport last used by the terrain pass, i.e. the player camera's viewport of the current frame.
     * The main pass runs before the shadow pass within a frame, so this is the player viewport the shadow pass's
     * terrain search needs as its receiver root set.
     */
    Viewport getLastViewport();

    void drawChunkLayer(BlockRenderLayer renderLayer, double x, double y, double z);

    void drawChunkLayersDeduplicated(Collection<BlockRenderLayer> renderLayers, double x, double y, double z);

    /** Marks the terrain section graph dirty for re-culling (used by the shadow pass). */
    void markSectionGraphDirty();

    void setupTerrain(Viewport viewport, SimpleWorldRenderer.CameraState cameraState, int frame, boolean spectator, boolean updateChunksImmediately);

    /**
     * Shadow-pass counterpart of {@link #setupTerrain}. The shadow pass precedes the terrain pass in a frame, so the
     * terrain search it runs first needs the player camera's viewport, which the main pass prepared before the shadow
     * pass began.
     *
     * @param shadowViewport the shadow frustum, centred on the player camera
     */
    void setupShadowTerrain(Viewport playerViewport, Viewport shadowViewport, SimpleWorldRenderer.CameraState cameraState, int frame, boolean spectator);
}
