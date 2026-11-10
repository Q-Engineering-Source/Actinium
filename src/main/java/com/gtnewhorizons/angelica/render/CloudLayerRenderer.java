package com.gtnewhorizons.angelica.render;

import com.gtnewhorizons.angelica.api.clouds.CloudLayer;
import com.gtnewhorizons.angelica.glsm.GLSMBlend;
import com.gtnewhorizons.angelica.glsm.GLSMCurrentAttribs;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMFog;
import com.gtnewhorizons.angelica.glsm.GLSMMatrices;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.states.BlendState;
import com.gtnewhorizons.angelica.glsm.states.Color4;
import com.gtnewhorizons.angelica.glsm.states.ColorMask;
import com.gtnewhorizons.angelica.glsm.states.FogState;
import dhj.embeddedt.embeddium.impl.gl.shader.GlProgram;
import net.coderbot.iris.apiimpl.IrisApiV0Impl;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static com.gtnewhorizons.angelica.render.CloudDisc.CELLS_PER_CHUNK;
import static com.gtnewhorizons.angelica.render.CloudDisc.WEDGE_COUNT;
import static com.gtnewhorizons.angelica.render.CloudDisc.textureOffset;
import static com.gtnewhorizons.angelica.render.CloudDisc.withinDisc;
import static com.gtnewhorizons.angelica.render.CloudDisc.withinMargin;

/**
 * Draws one cloud layer.
 *
 * <p>Per frame {@link #render} reduces the texture to a {@link CloudShape}, resolves the camera cell through
 * {@link CloudLayerPlan}, builds the geometry the camera needs when the cache is stale, reorders it near to
 * far as the camera crosses cells, then draws the visible wedge arc.
 *
 * <p>The pass owns no framebuffer and no viewport: it draws into whatever the caller bound, at the caller's
 * viewport. It uses texture unit zero for the cloud texture and one of the four programs of
 * {@link CloudRenderResources}, or no program at all when a shader pack is in use, because the pack supplies
 * the cloud program and the layer colour then reaches it as the generic value of vertex attribute location 1,
 * which the mesh sets per face from the current tint. Culling, the blend enable state and function, the colour
 * mask, the depth mask, the current colour, the bound texture and the selected program are captured on entry
 * and restored on exit, so the surrounding world pass keeps its own anaglyph mask, depth state and Iris
 * program.
 *
 * <p>Only the CPU side of a build or of a reorder runs on the worker thread: emitting vertices, plate rectangles
 * and wall runs into native buffers, and computing the near to far index order from already uploaded geometry.
 * Uploading the vertices or that order, and creating or deleting GL names, always happen on the render thread.
 */
final class CloudLayerRenderer {

    /** Reports failed build and reorder tasks, so a broken layer leaves a trace instead of a silent hole. */
    private static final Logger LOGGER = LogManager.getLogger("ActiniumClouds");
    /** Flat cloud plates drawn as one quad covering the disc. */
    static final int MODE_FAST = 1;
    /** Layered plates and walls with an inside, the default Fancy cloud mode. */
    static final int MODE_FANCY = 2;
    /** Bytes of vertex data uploaded per frame once a build finished, so a large build never stalls one frame. */
    private static final int UPLOAD_SLICE_BYTES = 256 * 1024;
    /** Single worker thread the CPU side of the vertex builds and the index reorders run on. */
    private static final Executor BUILD_THREAD = Executors.newSingleThreadExecutor(task -> {
        final Thread thread = new Thread(task, "Actinium Cloud Builder");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private final Minecraft mc = Minecraft.getMinecraft();
    private final CloudRenderResources resources;
    final CloudRenderResources.Texture texture;
    CloudLayer layer;
    private CloudLayerPlan.Settings settings;
    private final CloudFaceMesh faceMesh = new CloudFaceMesh();
    private CloudVertexMesh vertexMesh = new CloudVertexMesh();
    private CloudVertexMesh spareMesh = new CloudVertexMesh();
    private final CloudVertexMesh interiorMesh = new CloudVertexMesh();
    private int interiorAnchorX = Integer.MIN_VALUE, interiorAnchorZ = Integer.MIN_VALUE;
    private int interiorShapeGeneration = Integer.MIN_VALUE;
    private float interiorCellHeight = Float.NaN;
    private boolean interiorShadersActive, interiorActive;
    private float interiorDriftX, interiorDriftZ;
    private CompletableFuture<Void> buildInFlight;
    private CompletableFuture<Void> reorderInFlight;
    private CloudVertexMesh reorderMesh;
    private BuildParams buildParams;
    private BuildParams uploadParams;
    private final Matrix4f mvpScratch = new Matrix4f();
    private final Matrix4f mvpInteriorScratch = new Matrix4f();
    private final Matrix4f modelViewScratch = new Matrix4f();
    private CloudShape shape;
    private int shapeGeneration;
    private boolean faceMeshActive;
    private int anchorCellX = Integer.MIN_VALUE, anchorCellZ = Integer.MIN_VALUE;
    private CloudLayerPlan.Key cachedKey;
    private boolean geometryBuilt;
    /** Inputs whose build failed, so the same geometry is not rebuilt without a log on every frame. */
    private CloudLayerPlan.Key failedBuildKey;
    /** Anchor whose draw order failed, so the same order is not recomputed on every frame. */
    private int failedReorderAnchorX = Integer.MIN_VALUE, failedReorderAnchorZ = Integer.MIN_VALUE;
    /** Anchor the in flight reorder was started for. */
    private int reorderAnchorX = Integer.MIN_VALUE, reorderAnchorZ = Integer.MIN_VALUE;
    private CloudView cloudView;
    private double driftCells;
    private double cullingHeightCells;
    private GlProgram<CloudUniforms> program;

    CloudLayerRenderer(CloudRenderResources resources, CloudRenderResources.Texture texture) {
        this.resources = resources;
        this.texture = texture;
    }

    /**
     * Stores the layer description and the settings it is drawn with. A changed mode, render distance or
     * scale drops the geometry, because all three decide which cells and which LOD cuts a build covers.
     */
    void configure(CloudLayer layer, int mode, int distance, int scale) {
        this.layer = layer;
        final CloudLayerPlan.Settings updated = new CloudLayerPlan.Settings(mode, distance, scale);
        if (!updated.equals(settings)) invalidateGeometry();
        settings = updated;
    }

    private void invalidateGeometry() {
        anchorCellX = Integer.MIN_VALUE;
        anchorCellZ = Integer.MIN_VALUE;
        cachedKey = null;
        failedBuildKey = null;
        failedReorderAnchorX = Integer.MIN_VALUE;
        failedReorderAnchorZ = Integer.MIN_VALUE;
        cancelAsyncBuild();
        vertexMesh.clear();
        spareMesh.clear();
        interiorMesh.clear();
        interiorAnchorX = Integer.MIN_VALUE;
        interiorAnchorZ = Integer.MIN_VALUE;
        interiorActive = false;
        faceMesh.clear();
        geometryBuilt = false;
    }

    private boolean untextured() {
        return settings.mode() == MODE_FANCY && shape != null && shape.opaqueTexelsAllWhite;
    }

    private boolean cullBackFaces() {
        return settings.mode() == MODE_FANCY;
    }

    private void uploadFogUniforms(CloudUniforms uniforms, float fogStart, float fogEnd) {
        final boolean fogEnabled = GLSMFog.getFogMode().isEnabled();
        uniforms.setFogEnabled(fogEnabled);
        if (!fogEnabled) return;
        final FogState fog = GLSMFog.getFogState();
        final Vector3d color = fog.getFogColor();
        uniforms.setFogColor((float) color.x, (float) color.y, (float) color.z);
        switch (fog.getFogMode()) {
            case GL11.GL_LINEAR -> {
                final float range = fogEnd - fogStart;
                uniforms.setFogParams(range != 0.0f ? -1.0f / range : 0.0f, range != 0.0f ? fogEnd / range : 1.0f,
                    0.0f, 0.0f);
            }
            case GL11.GL_EXP -> uniforms.setFogParams(0.0f, 0.0f, fog.getDensity(), 1.0f);
            default -> uniforms.setFogParams(0.0f, 0.0f, fog.getDensity(), 2.0f);
        }
    }

    /**
     * Draws the layer and returns whether anything of it was drawn. The caller's GL state is restored before
     * returning, including on the paths that draw nothing because the texture is still loading.
     *
     * @param partialTicks interpolation between the previous and the current tick
     * @param cloudView camera state of the frame, shared by every layer
     */
    boolean render(float partialTicks, CloudView cloudView) {
        final PassState entry = new PassState();
        try {
            this.cloudView = cloudView;
            texture.bind();
            shape = texture.shape;
            shapeGeneration = texture.generation;
            if (shape == null) return false;

            final Entity viewEntity = mc.getRenderViewEntity();
            final boolean shadersActive = IrisApiV0Impl.INSTANCE.isShaderPackInUse();
            final CloudLayerPlan plan = CloudLayerPlan.compute(
                new CloudLayerPlan.Camera(viewEntity.prevPosX, viewEntity.posX, viewEntity.lastTickPosY,
                    viewEntity.posY, viewEntity.prevPosZ, viewEntity.posZ),
                partialTicks, cloudView, layer, settings, shadersActive,
                shape.coarsePlateRects != null && shape.opaqueTexelsAllWhite, shapeGeneration);
            final boolean backFaceCulling = cullBackFaces();
            final Tint tint = Tint.of(layer, mc.gameSettings.anaglyph);

            faceMeshActive = plan.faceMeshActive();
            if (!shadersActive) program = resources.program(faceMeshActive, untextured());

            final boolean anchorInRange = geometryBuilt
                && withinMargin(plan.anchorX() - anchorCellX, plan.anchorZ() - anchorCellZ);
            final boolean settingsUnchanged = geometryBuilt && plan.key().matches(cachedKey);
            final boolean geomCacheValid = anchorInRange && settingsUnchanged;
            final boolean buildFailed = plan.key().equals(failedBuildKey);
            final boolean canBuildAsync = !buildFailed && settingsUnchanged && !faceMeshActive && vertexMesh.built()
                && withinDisc(plan.anchorX() - anchorCellX, plan.anchorZ() - anchorCellZ, plan.radiusCells());

            final boolean builderBusy = buildInFlight != null || uploadParams != null;
            if (!geomCacheValid && !buildFailed && !(canBuildAsync && builderBusy)) {
                final BuildParams params = captureBuildParams(plan, shadersActive, backFaceCulling, tint);
                if (canBuildAsync) {
                    startAsyncBuild(params);
                } else {
                    cancelAsyncBuild();
                    if (faceMeshActive) {
                        faceMesh.build(shape, plan.anchorX(), plan.anchorZ(), plan.radiusCells(),
                            plan.radiusCells() * plan.radiusCells(), plan.wallCutCells());
                    } else {
                        buildVertexMesh(vertexMesh, params);
                        vertexMesh.uploadBuilt();
                    }
                    markGeometryCached(params);
                }
            }

            collectFinishedBuild();

            final boolean wantInterior = plan.insideDeck() && settings.mode() == MODE_FANCY && !faceMeshActive;
            if (wantInterior && (interiorAnchorX != plan.anchorX() || interiorAnchorZ != plan.anchorZ()
                || interiorShapeGeneration != shapeGeneration || interiorCellHeight != plan.cellHeightBlocks()
                || interiorShadersActive != shadersActive)) {
                interiorMesh.setColor(tint.red(), tint.green(), tint.blue(), tint.alpha());
                interiorMesh.buildInterior(shape, plan.anchorX(), plan.anchorZ(), plan.cellHeightBlocks(),
                    textureOffset(plan.anchorX(), shape.width), textureOffset(plan.anchorZ(), shape.height),
                    untextured() && !shadersActive, shadersActive);
                interiorMesh.uploadBuilt();
                interiorAnchorX = plan.anchorX();
                interiorAnchorZ = plan.anchorZ();
                interiorShapeGeneration = shapeGeneration;
                interiorCellHeight = plan.cellHeightBlocks();
                interiorShadersActive = shadersActive;
            }
            interiorActive = wantInterior && interiorMesh.built();

            if (faceMeshActive) {
                if (faceMesh.orderStale(plan.anchorX(), plan.anchorZ())) {
                    faceMesh.reorder(plan.anchorX(), plan.anchorZ());
                }
                if (faceMesh.uploadedCount() == 0 && faceMesh.interiorCount() == 0) return true;
            } else if (!vertexMesh.built()) {
                return true;
            }

            if (!faceMeshActive && !shadersActive && settings.mode() == MODE_FANCY) {
                if (reorderInFlight != null && reorderInFlight.isDone()) {
                    final CompletableFuture<Void> finished = reorderInFlight;
                    final CloudVertexMesh target = reorderMesh;
                    reorderInFlight = null;
                    reorderMesh = null;
                    final Throwable failure = awaitFailure(finished);
                    if (failure != null) {
                        failReorder(reorderAnchorX, reorderAnchorZ, failure);
                        if (target != null) target.discardPendingReorder();
                    } else if (target == vertexMesh) {
                        vertexMesh.reorderUpload();
                    } else if (target != null) {
                        target.discardPendingReorder();
                    }
                }
                if (reorderInFlight == null
                    && (plan.anchorX() != failedReorderAnchorX || plan.anchorZ() != failedReorderAnchorZ)
                    && vertexMesh.orderStale(plan.anchorX(), plan.anchorZ())) {
                    final CloudVertexMesh target = vertexMesh;
                    reorderMesh = target;
                    reorderAnchorX = plan.anchorX();
                    reorderAnchorZ = plan.anchorZ();
                    reorderInFlight = CompletableFuture.runAsync(() -> target.reorder(plan.anchorX(), plan.anchorZ()),
                        BUILD_THREAD);
                }
            }

            final float origFogStart = GLSMFog.getFogState().getStart();
            final float origFogEnd = GLSMFog.getFogState().getEnd();
            final float cloudExtentBlocks = plan.renderRadiusChunks() * CELLS_PER_CHUNK * plan.cellWidthBlocks();
            final boolean stretchFog = GLSMFog.getFogMode().isEnabled()
                && GLSMFog.getFogState().getFogMode() == GL11.GL_LINEAR
                && origFogEnd > 0.0f
                && origFogEnd < cloudExtentBlocks;
            final float fogStart = stretchFog ? origFogStart * (cloudExtentBlocks / origFogEnd) : origFogStart;
            final float fogEnd = stretchFog ? cloudExtentBlocks : origFogEnd;

            final boolean pushFog = stretchFog && shadersActive;
            if (pushFog) {
                GLSMFog.glFogf(GL11.GL_FOG_START, fogStart);
                GLSMFog.glFogf(GL11.GL_FOG_END, fogEnd);
            }
            try {
                drawLayer(plan, tint, shadersActive, fogStart, fogEnd, entry);
            } finally {
                if (pushFog) {
                    GLSMFog.glFogf(GL11.GL_FOG_START, origFogStart);
                    GLSMFog.glFogf(GL11.GL_FOG_END, origFogEnd);
                }
            }
            return true;
        } finally {
            entry.restore();
        }
    }

    private void drawLayer(CloudLayerPlan plan, Tint tint, boolean shadersActive, float fogStart, float fogEnd,
                           PassState entry) {
        final float anchorDriftX = plan.anchorX() - anchorCellX;
        final float anchorDriftZ = plan.anchorZ() - anchorCellZ;
        // Face wedges are re-bucketed around the current cell; vertex wedges keep their build anchor.
        final double cameraDriftX = (faceMeshActive ? 0 : anchorDriftX) + plan.cellFractionX();
        final double cameraDriftZ = (faceMeshActive ? 0 : anchorDriftZ) + plan.cellFractionZ();
        driftCells = Math.hypot(cameraDriftX, cameraDriftZ);
        cullingHeightCells = Math.max(Math.abs(plan.cloudBaseRelativeY()),
            Math.abs(plan.cloudBaseRelativeY() + plan.cellHeightBlocks())) / plan.cellWidthBlocks();

        interiorDriftX = anchorDriftX;
        interiorDriftZ = anchorDriftZ;

        if (shadersActive) {
            GLSMMatrices.glPushMatrix();
            GLSMMatrices.glScalef(plan.cellWidthBlocks(), 1.0f, plan.cellWidthBlocks());
            GLSMMatrices.glTranslatef(plan.entityToCameraX() - (plan.cellFractionX() + anchorDriftX),
                plan.deckBaseY(), plan.entityToCameraZ() - (plan.cellFractionZ() + anchorDriftZ));
            try {
                drawClouds(tint, true, entry);
            } finally {
                GLSMMatrices.glPopMatrix();
            }
            return;
        }

        GLSMPrograms.glUseProgram(program.handle());
        final CloudUniforms uniforms = program.getInterface();
        final Matrix4fStack modelView = GLSMMatrices.getModelViewMatrix();
        modelView.pushMatrix();
        try {
            modelView.scale(plan.cellWidthBlocks(), 1.0f, plan.cellWidthBlocks());
            if (interiorActive) {
                modelView.pushMatrix();
                modelView.translate(plan.entityToCameraX() - plan.cellFractionX(), plan.deckBaseY(),
                    plan.entityToCameraZ() - plan.cellFractionZ());
                GLSMMatrices.getProjectionMatrix().mul(modelView, mvpInteriorScratch);
                modelView.popMatrix();
            }
            modelView.translate(plan.entityToCameraX() - (plan.cellFractionX() + anchorDriftX), plan.deckBaseY(),
                plan.entityToCameraZ() - (plan.cellFractionZ() + anchorDriftZ));
            modelViewScratch.set(modelView);
            GLSMMatrices.getProjectionMatrix().mul(modelView, mvpScratch);
        } finally {
            modelView.popMatrix();
        }
        uniforms.mvp.set(mvpScratch);
        uniforms.modelView.set(modelViewScratch);
        if (faceMeshActive) {
            uniforms.setCellHeight(plan.cellHeightBlocks());
            uniforms.setScroll(textureOffset(faceMesh.buildAnchorX(), shape.width),
                textureOffset(faceMesh.buildAnchorZ(), shape.height), 1.0f / shape.width, 1.0f / shape.height);
        }
        uploadFogUniforms(uniforms, fogStart, fogEnd);
        drawClouds(tint, false, entry);
    }

    private void drawClouds(Tint tint, boolean shadersActive, PassState entry) {
        if (faceMeshActive) drawFaces(tint);
        else drawVertices(tint, shadersActive, entry);
    }

    private void drawFaces(Tint tint) {
        GLSMRasterizer.enableCull();
        GLSMBlend.enableBlend();
        GLSMBlend.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GLSMDepthStencilAlpha.glDepthMask(true);

        program.getInterface().setColorMult(tint.red(), tint.green(), tint.blue(), tint.alpha());

        faceMesh.bind();
        try {
            if (faceMesh.interiorCount() > 0) faceMesh.drawInterior();
            drawVisibleWedges(faceMesh.plateStarts(), faceMesh.wallStarts(), faceMesh.uploadedCount(), true);
        } finally {
            faceMesh.unbind();
        }
    }

    /**
     * Draws the plate and wall mesh, either straight to the target or after a depth only pass, which is what
     * a shader pack needs so the pack program sees the nearest cloud surface. The pack reads the layer
     * colour from the vertex colour attribute, so the colour is set on the mesh before the ranges are drawn.
     */
    private void drawVertices(Tint tint, boolean depthPrepass, PassState entry) {
        vertexMesh.bind();
        final int totalQuads = vertexMesh.quadCount();
        try {
            if (cullBackFaces()) GLSMRasterizer.enableCull();
            else GLSMRasterizer.disableCull();

            if (depthPrepass) {
                GLSMBlend.disableBlend();
                GLSMDepthStencilAlpha.glDepthMask(true);
                GLSMBlend.glColorMask(false, false, false, false);
                vertexMesh.drawAll();
                if (interiorActive) {
                    vertexMesh.unbind();
                    pushInteriorTransform(true);
                    interiorMesh.bind();
                    try {
                        interiorMesh.drawAll();
                    } finally {
                        interiorMesh.unbind();
                        popInteriorTransform(true);
                    }
                    vertexMesh.bind();
                }
            }

            entry.applyColorMask();
            GLSMBlend.enableBlend();
            GLSMBlend.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE,
                GL11.GL_ZERO);
            GLSMDepthStencilAlpha.glDepthMask(!depthPrepass);

            vertexMesh.setColor(tint.red(), tint.green(), tint.blue(), tint.alpha());
            if (depthPrepass) {
                for (int face = 0; face < 4; face++) {
                    if (vertexMesh.faceEmpty(face)) continue;
                    vertexMesh.drawFace(face);
                }
            } else {
                program.getInterface().setColorMult(tint.red(), tint.green(), tint.blue(), tint.alpha());
                if (settings.mode() == MODE_FANCY && vertexMesh.orderPublished()) {
                    drawVisibleWedges(vertexMesh.plateStarts(), vertexMesh.wallStarts(), totalQuads, false);
                } else {
                    drawRange(0, totalQuads, false);
                }
            }

            if (interiorActive) {
                pushInteriorTransform(depthPrepass);
                interiorMesh.bind();
                try {
                    interiorMesh.setColor(tint.red(), tint.green(), tint.blue(), tint.alpha());
                    if (depthPrepass) {
                        for (int face = 0; face < 4; face++) {
                            if (interiorMesh.faceEmpty(face)) continue;
                            interiorMesh.drawFace(face);
                        }
                    } else {
                        interiorMesh.drawRange(0, interiorMesh.quadCount());
                    }
                } finally {
                    interiorMesh.unbind();
                    popInteriorTransform(depthPrepass);
                }
            }
        } finally {
            vertexMesh.unbind();
        }
    }

    private void pushInteriorTransform(boolean shadersActive) {
        if (shadersActive) {
            GLSMMatrices.glPushMatrix();
            GLSMMatrices.glTranslatef(interiorDriftX, 0.0f, interiorDriftZ);
        } else {
            program.getInterface().mvp.set(mvpInteriorScratch);
        }
    }

    private void popInteriorTransform(boolean shadersActive) {
        if (shadersActive) {
            GLSMMatrices.glPopMatrix();
        } else {
            program.getInterface().mvp.set(mvpScratch);
        }
    }

    private void drawVisibleWedges(int[] plateStarts, int[] wallStarts, int total, boolean fromFaceMesh) {
        final CloudWedgeArc arc = CloudWedgeArc.of(cloudView.facing,
            cloudView.halfSpan(cullingHeightCells, driftCells));
        if (arc.wholeFan()) {
            drawRange(0, total, fromFaceMesh);
            return;
        }

        drawRange(0, plateStarts[0], fromFaceMesh);

        if (arc.from() <= arc.to()) {
            drawRange(plateStarts[arc.from()], plateStarts[arc.to() + 1], fromFaceMesh);
            drawRange(plateStarts[WEDGE_COUNT], wallStarts[0], fromFaceMesh);
            drawRange(wallStarts[arc.from()], wallStarts[arc.to() + 1], fromFaceMesh);
        } else {
            drawRange(plateStarts[arc.from()], plateStarts[WEDGE_COUNT], fromFaceMesh);
            drawRange(plateStarts[0], plateStarts[arc.to() + 1], fromFaceMesh);
            drawRange(plateStarts[WEDGE_COUNT], wallStarts[0], fromFaceMesh);
            drawRange(wallStarts[arc.from()], wallStarts[WEDGE_COUNT], fromFaceMesh);
            drawRange(wallStarts[0], wallStarts[arc.to() + 1], fromFaceMesh);
        }
    }

    private void drawRange(int start, int end, boolean fromFaceMesh) {
        if (end - start <= 0) return;
        if (fromFaceMesh) faceMesh.drawRange(start, end);
        else vertexMesh.drawRange(start, end);
    }

    private record BuildParams(CloudShape shape, boolean fancy, int anchorX, int anchorZ, int radiusCells,
                               float cellHeightBlocks, boolean emitUnderside, boolean emitTopSurface,
                               boolean emitPlates, boolean shadersActive, int wallCutCells, int plateLodCells,
                               boolean untextured, Tint tint, CloudLayerPlan.Key key) {}

    private BuildParams captureBuildParams(CloudLayerPlan plan, boolean shadersActive, boolean backFaceCulling,
                                           Tint tint) {
        return new BuildParams(shape, settings.mode() == MODE_FANCY, plan.anchorX(), plan.anchorZ(),
            plan.radiusCells(), plan.cellHeightBlocks(), plan.emitUnderside(), plan.emitTopSurface(),
            !(backFaceCulling && plan.emitUnderside() && plan.emitTopSurface()), shadersActive,
            plan.wallCutCells(), plan.plateLodCells(), untextured() && !shadersActive, tint, plan.key());
    }

    private static void buildVertexMesh(CloudVertexMesh mesh, BuildParams p) {
        final float scrollX = textureOffset(p.anchorX(), p.shape().width);
        final float scrollZ = textureOffset(p.anchorZ(), p.shape().height);
        mesh.setColor(p.tint().red(), p.tint().green(), p.tint().blue(), p.tint().alpha());

        if (!p.fancy()) {
            mesh.buildFast(p.shape(), p.radiusCells(), scrollX, scrollZ, p.shadersActive());
            return;
        }

        mesh.build(p.shape(), p.anchorX(), p.anchorZ(), p.radiusCells(), p.radiusCells() * p.radiusCells(),
            p.cellHeightBlocks(), scrollX, scrollZ, p.emitUnderside(), p.emitTopSurface(), p.emitPlates(),
            p.wallCutCells(), p.plateLodCells(), p.untextured(), p.shadersActive());
    }

    private void markGeometryCached(BuildParams p) {
        anchorCellX = p.anchorX();
        anchorCellZ = p.anchorZ();
        cachedKey = p.key();
        failedBuildKey = null;
        failedReorderAnchorX = Integer.MIN_VALUE;
        failedReorderAnchorZ = Integer.MIN_VALUE;
        geometryBuilt = true;
    }

    private void startAsyncBuild(BuildParams p) {
        if (buildInFlight != null || uploadParams != null) return;
        final CloudVertexMesh target = spareMesh;
        buildParams = p;
        buildInFlight = CompletableFuture.runAsync(() -> buildVertexMesh(target, p), BUILD_THREAD);
    }

    private void collectFinishedBuild() {
        if (buildInFlight != null) {
            if (!buildInFlight.isDone()) return;

            final CompletableFuture<Void> finished = buildInFlight;
            final BuildParams params = buildParams;
            buildInFlight = null;
            buildParams = null;

            final Throwable failure = awaitFailure(finished);
            if (failure != null) {
                failBuild(params.key(), failure);
                spareMesh.discardPendingUpload();
                return;
            }
            if (params.key().shapeGeneration() != shapeGeneration) {
                spareMesh.discardPendingUpload();
                return;
            }
            uploadParams = params;
            if (!spareMesh.beginUpload()) {
                swapMeshes();
                return;
            }
        }

        if (uploadParams == null) return;
        if (!spareMesh.uploadSlice(UPLOAD_SLICE_BYTES)) return;
        swapMeshes();
    }

    private void swapMeshes() {
        discardReorder();
        final CloudVertexMesh finished = spareMesh;
        spareMesh = vertexMesh;
        vertexMesh = finished;
        markGeometryCached(uploadParams);
        uploadParams = null;
    }

    /**
     * Waits for a task the render thread can no longer use and returns the failure it ended with, or
     * {@code null} when it succeeded. Callers log and drop the result instead of letting the worker exception
     * escape into the world pass or the unload path.
     */
    static Throwable awaitFailure(CompletableFuture<Void> task) {
        try {
            task.join();
            return null;
        } catch (CompletionException e) {
            return e.getCause() == null ? e : e.getCause();
        }
    }

    /** Logs a failed build task and stops retrying the same geometry inputs on every frame. */
    void failBuild(CloudLayerPlan.Key key, Throwable cause) {
        LOGGER.error("Cloud layer {} failed to build its geometry", layerId(), cause);
        failedBuildKey = key;
    }

    /** Logs a failed reorder task and stops recomputing the same draw order on every frame. */
    void failReorder(int anchorX, int anchorZ, Throwable cause) {
        LOGGER.error("Cloud layer {} failed to reorder its draw order at cell {}, {}", layerId(), anchorX, anchorZ,
            cause);
        failedReorderAnchorX = anchorX;
        failedReorderAnchorZ = anchorZ;
    }

    private String layerId() {
        return layer == null ? "<unconfigured>" : layer.id();
    }

    private void discardReorder() {
        final CompletableFuture<Void> inFlight = reorderInFlight;
        final CloudVertexMesh target = reorderMesh;
        reorderInFlight = null;
        reorderMesh = null;
        if (inFlight != null) {
            final Throwable failure = awaitFailure(inFlight);
            if (failure != null) {
                LOGGER.error("Cloud layer {} failed to reorder its draw order while dropping it", layerId(), failure);
            }
        }
        if (target != null) target.discardPendingReorder();
    }

    private void cancelAsyncBuild() {
        discardReorder();
        final CompletableFuture<Void> inFlight = buildInFlight;
        buildInFlight = null;
        buildParams = null;
        uploadParams = null;
        if (inFlight != null) {
            final Throwable failure = awaitFailure(inFlight);
            if (failure != null) {
                LOGGER.error("Cloud layer {} failed to build its geometry while dropping it", layerId(), failure);
            }
        }
        spareMesh.discardPendingUpload();
    }

    /**
     * Stops the worker threads and releases every GL name and native buffer the layer owns. Safe to repeat
     * and safe before the first frame, which is what world unload needs. The release runs even when dropping
     * the pending work reports a failure, so a broken build cannot strand the GL objects of the layer.
     */
    void delete() {
        try {
            invalidateGeometry();
        } finally {
            vertexMesh.delete();
            spareMesh.delete();
            interiorMesh.delete();
            faceMesh.delete();
            program = null;
        }
    }

    /** Layer colour handed to the meshes and programs, already in the anaglyph space when it applies. */
    record Tint(float red, float green, float blue, float alpha) {

        /**
         * Resolves the layer tint. The anaglyph modes mix the channels of their single layer, and the alpha
         * of the layer is kept so blending stays per layer.
         */
        static Tint of(CloudLayer layer, boolean anaglyph) {
            float red = layer.red();
            float green = layer.green();
            float blue = layer.blue();
            if (anaglyph) {
                final float mixedRed = (red * 30.0F + green * 59.0F + blue * 11.0F) / 100.0F;
                final float mixedGreen = (red * 30.0F + green * 70.0F) / 100.0F;
                final float mixedBlue = (red * 30.0F + blue * 70.0F) / 100.0F;
                red = mixedRed;
                green = mixedGreen;
                blue = mixedBlue;
            }
            return new Tint(red, green, blue, layer.alpha());
        }
    }

    /** State the pass changes, captured on entry so the pass can hand back exactly what it found. */
    private static final class PassState {
        private final boolean cull;
        private final boolean blend;
        private final boolean depthMask;
        private final boolean colorMaskRed, colorMaskGreen, colorMaskBlue, colorMaskAlpha;
        private final int blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha;
        private final float colorRed, colorGreen, colorBlue, colorAlpha;
        private final int textureUnit, texture, program;

        private PassState() {
            cull = GLSMRasterizer.getCullState().isEnabled();
            blend = GLSMBlend.isEffectiveBlendEnabled();
            final BlendState blendState = GLSMBlend.getBlendState();
            blendSrcRgb = blendState.getSrcRgb();
            blendDstRgb = blendState.getDstRgb();
            blendSrcAlpha = blendState.getSrcAlpha();
            blendDstAlpha = blendState.getDstAlpha();
            final ColorMask colorMask = GLSMBlend.getColorMask();
            colorMaskRed = colorMask.red;
            colorMaskGreen = colorMask.green;
            colorMaskBlue = colorMask.blue;
            colorMaskAlpha = colorMask.alpha;
            depthMask = GLSMDepthStencilAlpha.isEffectiveDepthMaskEnabled();
            final Color4 color = GLSMCurrentAttribs.getColor();
            colorRed = color.getRed();
            colorGreen = color.getGreen();
            colorBlue = color.getBlue();
            colorAlpha = color.getAlpha();
            textureUnit = GLSMTextures.getActiveTextureUnitForServerState();
            texture = GLSMTextures.getBoundTextureForServerState();
            program = GLSMPrograms.getActiveProgram();
        }

        private void applyColorMask() {
            GLSMBlend.glColorMask(colorMaskRed, colorMaskGreen, colorMaskBlue, colorMaskAlpha);
        }

        private void restore() {
            GLSMPrograms.glUseProgram(program);
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0 + textureUnit);
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            if (cull) GLSMRasterizer.enableCull();
            else GLSMRasterizer.disableCull();
            applyColorMask();
            GLSMDepthStencilAlpha.glDepthMask(depthMask);
            GLSMBlend.tryBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
            if (blend) GLSMBlend.enableBlend();
            else GLSMBlend.disableBlend();
            GLSMCurrentAttribs.glColor4f(colorRed, colorGreen, colorBlue, colorAlpha);
        }
    }
}
