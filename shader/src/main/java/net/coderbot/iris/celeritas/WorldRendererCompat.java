package net.coderbot.iris.celeritas;

import net.minecraft.util.BlockRenderLayer;
import dhj.embeddedt.embeddium.impl.render.terrain.SimpleWorldRenderer;
import dhj.embeddedt.embeddium.impl.render.viewport.Viewport;

import java.util.Collection;
import java.util.function.Consumer;

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

    default void drawChunkLayersDeduplicated(Collection<BlockRenderLayer> renderLayers, double x, double y, double z) {
        drawChunkLayersDeduplicated(renderLayers, x, y, z, ignored -> {
        });
    }

    /** Draws each distinct terrain pass once and runs {@code beforePass} immediately before its draw. */
    void drawChunkLayersDeduplicated(Collection<BlockRenderLayer> renderLayers, double x, double y, double z,
                                     Consumer<BlockRenderLayer> beforePass);

    /**
     * Marks the shadow render lists dirty so the next shadow pass searches again. The terrain lists keep the dirty
     * state their own camera changes and build results maintain.
     */
    void markShadowGraphDirty();

    /** Joins every in-flight terrain search, the shadow one included, and runs the tasks they deferred. */
    void finishAllGraphUpdates();

    /** @return how many shadow searches this renderer has submitted so far. */
    int getShadowSearchSubmissions();

    void setupTerrain(Viewport viewport, SimpleWorldRenderer.CameraState cameraState, int frame, boolean spectator, boolean updateChunksImmediately);

    /**
     * Shadow-pass counterpart of {@link #setupTerrain}. The shadow pass precedes the terrain pass in a frame, so the
     * terrain search it runs first needs the player camera's viewport, which the main pass prepared before the shadow
     * pass began.
     *
     * @param shadowViewport the shadow frustum, centred on the player camera
     */
    void setupShadowTerrain(Viewport playerViewport, Viewport shadowViewport, SimpleWorldRenderer.CameraState cameraState, int frame, boolean spectator);

    /**
     * Shadow-pass entry that resolves the player camera viewport itself, through {@link #getLastViewport()}: the
     * shadow pass holds only the shadow frustum, and the terrain search it runs is rooted at the player viewport the
     * last terrain pass captured.
     *
     * @param shadowViewport the shadow frustum, centred on the player camera
     */
    void setupShadowTerrain(Viewport shadowViewport, SimpleWorldRenderer.CameraState cameraState, int frame, boolean spectator);
}
