package com.gtnewhorizons.angelica.render;

import com.gtnewhorizons.angelica.api.clouds.CloudLayer;
import net.minecraft.util.math.MathHelper;

import static com.gtnewhorizons.angelica.render.CloudDisc.CELLS_PER_CHUNK;
import static com.gtnewhorizons.angelica.render.CloudDisc.MARGIN_CELLS;

/**
 * What one cloud layer needs for a frame: the camera cell it is seen from, which faces and which mesher the
 * view uses, how far the disc reaches, the two distance LOD cuts, and the key the built geometry is cached
 * under.
 *
 * <p>Everything here is derived from the layer description, the frame camera and the shared build settings,
 * so the renderer only has to execute the plan and the geometry cache stays a comparison of two plans.
 */
final class CloudLayerPlan {

    /** Build settings a layer keeps until the host changes them; a change invalidates the built geometry. */
    record Settings(int mode, int distance, int scale) {}

    /**
     * Camera position of the frame. The horizontal axes interpolate from the previous tick position and the
     * vertical axis from the last tick position, which is the interpolation the cloud pass has always used.
     */
    record Camera(double prevX, double x, double lastTickY, double y, double prevZ, double z) {

        double interpolatedX(float partialTicks) {
            return prevX + (x - prevX) * partialTicks;
        }

        double interpolatedY(float partialTicks) {
            return lastTickY + (y - lastTickY) * partialTicks;
        }

        double interpolatedZ(float partialTicks) {
            return prevZ + (z - prevZ) * partialTicks;
        }
    }

    /**
     * Inputs a built mesh depends on. Two plans with matching keys can share a build; the plate detail
     * distance is ignored when the face mesh is used, because that mesh draws walls and never plates.
     */
    record Key(int shapeGeneration, boolean emitUnderside, boolean emitTopSurface, int renderRadiusChunks,
               float cellHeightBlocks, boolean shadersActive, int wallCutCells, int plateLodCells,
               boolean faceMeshActive) {

        /**
         * Whether a build cached under {@code cached} still describes what this plan asks for. Only the
         * dimensions that reach the emitted vertices are compared, so a camera walk that stays inside the
         * margin keeps its mesh.
         */
        boolean matches(Key cached) {
            return cached != null
                && shapeGeneration == cached.shapeGeneration
                && emitUnderside == cached.emitUnderside
                && emitTopSurface == cached.emitTopSurface
                && renderRadiusChunks == cached.renderRadiusChunks
                && cellHeightBlocks == cached.cellHeightBlocks
                && shadersActive == cached.shadersActive
                && wallCutCells == cached.wallCutCells
                && faceMeshActive == cached.faceMeshActive
                && (faceMeshActive || plateLodCells == cached.plateLodCells);
        }
    }

    private final float cellWidthBlocks;
    private final float cellHeightBlocks;
    private final int anchorX;
    private final int anchorZ;
    private final float cellFractionX;
    private final float cellFractionZ;
    private final float entityToCameraX;
    private final float entityToCameraZ;
    private final float cloudBaseRelativeY;
    private final float deckBaseY;
    private final boolean emitUnderside;
    private final boolean emitTopSurface;
    private final boolean insideDeck;
    private final int renderRadiusChunks;
    private final int radiusCells;
    private final int wallCutCells;
    private final int plateLodCells;
    private final boolean faceMeshActive;
    private final Key key;

    private CloudLayerPlan(float cellWidthBlocks, float cellHeightBlocks, int anchorX, int anchorZ,
                           float cellFractionX, float cellFractionZ, float entityToCameraX, float entityToCameraZ,
                           float cloudBaseRelativeY, float deckBaseY, boolean emitUnderside, boolean emitTopSurface,
                           boolean insideDeck, int renderRadiusChunks, int radiusCells, int wallCutCells,
                           int plateLodCells, boolean faceMeshActive, Key key) {
        this.cellWidthBlocks = cellWidthBlocks;
        this.cellHeightBlocks = cellHeightBlocks;
        this.anchorX = anchorX;
        this.anchorZ = anchorZ;
        this.cellFractionX = cellFractionX;
        this.cellFractionZ = cellFractionZ;
        this.entityToCameraX = entityToCameraX;
        this.entityToCameraZ = entityToCameraZ;
        this.cloudBaseRelativeY = cloudBaseRelativeY;
        this.deckBaseY = deckBaseY;
        this.emitUnderside = emitUnderside;
        this.emitTopSurface = emitTopSurface;
        this.insideDeck = insideDeck;
        this.renderRadiusChunks = renderRadiusChunks;
        this.radiusCells = radiusCells;
        this.wallCutCells = wallCutCells;
        this.plateLodCells = plateLodCells;
        this.faceMeshActive = faceMeshActive;
        this.key = key;
    }

    /**
     * Resolves the plan of one layer for the frame.
     *
     * @param camera camera position of the frame
     * @param partialTicks interpolation between the previous and the current tick
     * @param view camera state shared by every layer, including the offset from the view entity to the
     *             camera the model view matrix is built from
     * @param layer description of the layer being drawn
     * @param settings build settings the host currently applies
     * @param shadersActive whether a shader pack is drawing, which takes the geometry and the program away
     *                      from this renderer
     * @param coarseCoverUsable whether the decoded shape has half resolution plate rectangles to fall back to
     * @param shapeGeneration identity of the decoded cloud shape, so a texture reload invalidates the mesh
     */
    static CloudLayerPlan compute(Camera camera, float partialTicks, CloudView view, CloudLayer layer,
                                  Settings settings, boolean shadersActive, boolean coarseCoverUsable,
                                  int shapeGeneration) {
        final float cellWidthBlocks = layer.cellWidth() * settings.scale();
        final float cellHeightBlocks = layer.cellHeight() * settings.scale();
        final float coordinateWidth = layer.coordinateWidth() * settings.scale();

        final float cameraY = (float) (camera.interpolatedY(partialTicks) + view.offsetY);
        final double cameraCellX = CloudDisc.cellCoordinate(camera.interpolatedX(partialTicks) + view.offsetX,
            coordinateWidth, layer.offsetX());
        final double cameraCellZ = CloudDisc.cellCoordinate(camera.interpolatedZ(partialTicks) + view.offsetZ,
            coordinateWidth, layer.offsetZ());

        final float cloudBaseRelativeY = layer.height() - cameraY + 0.33F;
        final int anchorX = MathHelper.floor(cameraCellX);
        final int anchorZ = MathHelper.floor(cameraCellZ);
        final float cellFractionX = (float) (cameraCellX - anchorX);
        final float cellFractionZ = (float) (cameraCellZ - anchorZ);

        final boolean emitUnderside = cloudBaseRelativeY + cellHeightBlocks >= 0.0F;
        final boolean emitTopSurface = cloudBaseRelativeY <= 0.0F;
        final boolean insideDeck = emitUnderside && emitTopSurface;

        final int renderRadiusChunks = (int) Math.ceil(settings.distance() * 64.0F / (CELLS_PER_CHUNK * cellWidthBlocks));
        final boolean backFaceCulling = settings.mode() == CloudLayerRenderer.MODE_FANCY;
        final int wallCutCells = CloudDisc.wallCutCells(view.pixelsPerRadian, !(backFaceCulling && insideDeck));
        final int plateLodCells = CloudDisc.plateLodCells(view.pixelsPerRadian, cloudBaseRelativeY, cellWidthBlocks,
            coarseCoverUsable);

        final int marginChunks = (MARGIN_CELLS + CELLS_PER_CHUNK - 1) / CELLS_PER_CHUNK;
        final int radiusCells = (renderRadiusChunks + marginChunks) * CELLS_PER_CHUNK;
        final boolean faceMeshActive = settings.mode() == CloudLayerRenderer.MODE_FANCY && !shadersActive
            && insideDeck && radiusCells <= CloudFaceMesh.CELL_LIMIT;

        final Key key = new Key(shapeGeneration, emitUnderside, emitTopSurface, renderRadiusChunks, cellHeightBlocks,
            shadersActive, wallCutCells, plateLodCells, faceMeshActive);

        return new CloudLayerPlan(cellWidthBlocks, cellHeightBlocks, anchorX, anchorZ, cellFractionX, cellFractionZ,
            view.offsetX / cellWidthBlocks, view.offsetZ / cellWidthBlocks, cloudBaseRelativeY,
            cloudBaseRelativeY + view.offsetY, emitUnderside, emitTopSurface, insideDeck, renderRadiusChunks,
            radiusCells, wallCutCells, plateLodCells, faceMeshActive, key);
    }

    /** Edge length of one cell in blocks, after the cloud scale is applied. */
    float cellWidthBlocks() {
        return cellWidthBlocks;
    }

    /** Vertical thickness of one cell in blocks, after the cloud scale is applied. */
    float cellHeightBlocks() {
        return cellHeightBlocks;
    }

    /** Cell column the camera sits in. */
    int anchorX() {
        return anchorX;
    }

    /** Cell row the camera sits in. */
    int anchorZ() {
        return anchorZ;
    }

    /** Fraction of the camera cell already crossed along X. */
    float cellFractionX() {
        return cellFractionX;
    }

    /** Fraction of the camera cell already crossed along Z. */
    float cellFractionZ() {
        return cellFractionZ;
    }

    /** Offset from the view entity to the camera the model view is built from, in cells along X. */
    float entityToCameraX() {
        return entityToCameraX;
    }

    /** Offset from the view entity to the camera the model view is built from, in cells along Z. */
    float entityToCameraZ() {
        return entityToCameraZ;
    }

    /** Cloud base relative to the camera, in blocks. */
    float cloudBaseRelativeY() {
        return cloudBaseRelativeY;
    }

    /** Cloud base relative to the view entity, in blocks, which is what the entity relative model view places. */
    float deckBaseY() {
        return deckBaseY;
    }

    /** Whether the underside of the deck is visible, which is the case when the camera is below its top. */
    boolean emitUnderside() {
        return emitUnderside;
    }

    /** Whether the top surface is visible, which is the case when the camera is above the deck base. */
    boolean emitTopSurface() {
        return emitTopSurface;
    }

    /** Whether the camera sits inside the deck and sees both faces. */
    boolean insideDeck() {
        return insideDeck;
    }

    /** Render distance of the layer in chunks, before the margin is added. */
    int renderRadiusChunks() {
        return renderRadiusChunks;
    }

    /** Radius of the disc in cells, margin included. */
    int radiusCells() {
        return radiusCells;
    }

    /** Distance in cells inside which walls are built, or {@link Integer#MAX_VALUE} when walls are not cut. */
    int wallCutCells() {
        return wallCutCells;
    }

    /** Distance in cells inside which plates use cell sized rectangles, or {@link Integer#MAX_VALUE} when kept. */
    int plateLodCells() {
        return plateLodCells;
    }

    /** Whether the instanced face mesh draws this layer instead of the plate and wall vertex mesh. */
    boolean faceMeshActive() {
        return faceMeshActive;
    }

    /** Inputs the built geometry is cached under. */
    Key key() {
        return key;
    }
}
