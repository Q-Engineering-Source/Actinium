package com.dhj.actinium.render;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.states.BlendState;
import net.coderbot.iris.apiimpl.IrisApiV0Impl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.tileentity.TileEntityEndPortal;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;

import java.nio.FloatBuffer;
import java.util.List;
import com.gtnewhorizons.angelica.glsm.GLSMBlend;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.GLSMMatrices;
import com.gtnewhorizons.angelica.glsm.GLSMQueries;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;

/**
 * Draws the Core Profile replacement for the legacy projective end portal renderer.
 */
public final class EndPortalRenderer {
    private static final Logger LOGGER = LogManager.getLogger("EndPortalRenderer");
    private static final ResourceLocation END_SKY_TEXTURE = new ResourceLocation("textures/environment/end_sky.png");
    private static final ResourceLocation END_PORTAL_TEXTURE = new ResourceLocation("textures/entity/end_portal.png");
    private static final VertexFormatElement PROJECTIVE_UV_4F = new VertexFormatElement(
        0,
        VertexFormatElement.EnumType.FLOAT,
        VertexFormatElement.EnumUsage.UV,
        4
    );
    private static final VertexFormat PORTAL_VERTEX_FORMAT = createVertexFormat(DefaultVertexFormats.TEX_2F);
    private static final VertexFormat PROJECTIVE_PORTAL_VERTEX_FORMAT = createVertexFormat(PROJECTIVE_UV_4F);

    private EndPortalRenderer() {
    }

    /**
     * Renders one portal immediately so block entity shader state remains active for the draw call.
     *
     * @param portal portal or gateway supplying visible faces
     * @param x render-relative minimum X coordinate
     * @param y render-relative minimum Y coordinate
     * @param z render-relative minimum Z coordinate
     * @param topOffset renderer-specific height of the top face
     * @param vanillaLayerCount distance-dependent layer count returned by the vanilla renderer
     */
    public static void render(
        TileEntityEndPortal portal,
        double x,
        double y,
        double z,
        float topOffset,
        int vanillaLayerCount
    ) {
        float vanillaAnimationTime = (float) (Minecraft.getSystemTime() % 800000L) / 800000.0F;
        List<EndPortalLayers.Layer> layers = EndPortalLayers.create(vanillaLayerCount, vanillaAnimationTime);
        boolean shaderPackInUse = IrisApiV0Impl.INSTANCE.isShaderPackInUse();
        // Synthetic overlay calls (world-less dummy block entity, e.g. BetterPortals' starfield
        // overlay) fire their fade hook from shouldRenderFace during face evaluation; snapshot
        // the pre-hook blend state so the hook cannot leak past this render call.
        boolean syntheticOverlay = shaderPackInUse && portal.getWorld() == null;
        BlendState preHookBlend = syntheticOverlay ? GLStateManager.getBlendState().copy() : null;
        EndPortalProjection projection;
        List<EndPortalMesh.FaceQuad> faces;
        List<EndPortalMesh.Triangle> shaderTriangles;
        try {
            Matrix4f activeProjection = new Matrix4f(GLStateManager.getProjectionMatrix());
            Matrix4f activeModelView = new Matrix4f(GLStateManager.getModelViewMatrix());
            projection = new EndPortalProjection(activeProjection, activeModelView);
            faces = EndPortalMesh.visibleFaces(portal::shouldRenderFace, topOffset);
            shaderTriangles = shaderPackInUse
                ? EndPortalMesh.shaderTriangles(faces, projection, x, y, z, layers)
                : List.of();
        } catch (IllegalArgumentException exception) {
            LOGGER.error("Invalid active matrix while building end portal geometry at {}", portal.getPos(), exception);
            return;
        }

        // The overlay fade hook (CONSTANT_ALPHA blend factors + constant alpha = opacity) is
        // reproduced by folding its opacity into the first (sky) layer's vertex alpha, which is
        // the exact SRC_ALPHA equivalent, and by keeping the per-layer path so the additive
        // layers stay at full strength. The blend color is set through a raw GL14.glBlendColor
        // call that bypasses glsm tracking, so it must be read back from the real GL state.
        float layerZeroAlpha = 1.0F;
        boolean fadeHook = false;
        if (syntheticOverlay) {
            BlendState hookBlend = GLStateManager.getBlendState();
            if (EndPortalRenderPolicy.isLegacyFadeHookBlend(hookBlend.getSrcRgb(), hookBlend.getDstRgb())) {
                fadeHook = true;
                layerZeroAlpha = readBlendColorAlpha();
            }
        }

        int activeTexture = GLStateManager.getActiveTextureUnit();
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
        int previousMatrixMode = GLSMQueries.glGetInteger(GL11.GL_MATRIX_MODE);
        GLSMMatrices.glMatrixMode(GL11.GL_TEXTURE);
        GLSMMatrices.glPushMatrix();
        GLSMMatrices.glLoadIdentity();
        GLSMMatrices.glMatrixMode(previousMatrixMode);
        int previousTexture = GLStateManager.getBoundTextureForServerState();
        boolean textureEnabled = GLSMEnable.glIsEnabled(GL11.GL_TEXTURE_2D);
        BlendState previousBlend = GLStateManager.getBlendState().copy();
        boolean blendEnabled = GLStateManager.getBlendMode().isEnabled();
        boolean lightingEnabled = GLSMEnable.glIsEnabled(GL11.GL_LIGHTING);

        try {
            GLStateManager.enableTexture();
            GLStateManager.disableLighting();

            boolean precomposed = false;
            if (!fadeHook && EndPortalCompositeLogic.shouldPrecompose(shaderPackInUse, layers.size())) {
                int compositeTexture = EndPortalCompositeRenderer.texture(layers);
                if (compositeTexture != 0) {
                    GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, compositeTexture);
                    GLStateManager.disableBlend();
                    EndPortalBlockEntityIdScope.run(() -> drawComposite(x, y, z, shaderTriangles));
                    precomposed = true;
                }
            }

            if (!precomposed) {
                EndPortalLayers.Layer skyLayer = layers.getFirst();
                bindTexture(skyLayer.texture());
                applyBlend(skyLayer.blend());
                drawLayers(x, y, z, layers, 0, 1, layerZeroAlpha, shaderPackInUse, faces, shaderTriangles, projection);

                if (layers.size() > 1) {
                    EndPortalLayers.Layer portalLayer = layers.get(1);
                    bindTexture(portalLayer.texture());
                    applyBlend(portalLayer.blend());
                    drawLayers(x, y, z, layers, 1, layers.size(), 1.0F, shaderPackInUse, faces, shaderTriangles, projection);
                }
            }
        } finally {
            BlendState blendToRestore = preHookBlend != null ? preHookBlend : previousBlend;
            GLSMBlend.glBlendFuncSeparate(
                blendToRestore.getSrcRgb(),
                blendToRestore.getDstRgb(),
                blendToRestore.getSrcAlpha(),
                blendToRestore.getDstAlpha()
            );
            if (blendEnabled) {
                GLStateManager.enableBlend();
            } else {
                GLStateManager.disableBlend();
            }
            if (lightingEnabled) {
                GLStateManager.enableLighting();
            } else {
                GLStateManager.disableLighting();
            }
            GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
            if (!textureEnabled) {
                GLStateManager.disableTexture();
            }
            GLSMMatrices.glMatrixMode(GL11.GL_TEXTURE);
            GLSMMatrices.glPopMatrix();
            GLSMMatrices.glMatrixMode(previousMatrixMode);
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0 + activeTexture);
        }
    }

    private static void bindTexture(EndPortalLayers.Texture texture) {
        ResourceLocation location = switch (texture) {
            case END_SKY -> END_SKY_TEXTURE;
            case END_PORTAL -> END_PORTAL_TEXTURE;
        };
        Minecraft.getMinecraft().getTextureManager().bindTexture(location);
    }

    /**
     * Reads the constant blend color alpha from the real GL state. The overlay fade hook sets it
     * through a raw GL14.glBlendColor call, so the glsm-tracked blend color never observes it.
     */
    private static float readBlendColorAlpha() {
        FloatBuffer blendColor = BufferUtils.createFloatBuffer(4);
        BackendManager.RENDER_BACKEND.getFloat(GL14.GL_BLEND_COLOR, blendColor);
        return blendColor.get(3);
    }

    private static void applyBlend(EndPortalLayers.Blend blend) {
        switch (blend) {
            case ALPHA -> {
                GLStateManager.enableBlend();
                GLSMBlend.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            }
            case ADDITIVE -> {
                GLStateManager.enableBlend();
                GLSMBlend.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
            }
        }
    }

    private static void drawLayers(
        double x,
        double y,
        double z,
        List<EndPortalLayers.Layer> layers,
        int fromIndex,
        int toIndex,
        float layerAlpha,
        boolean shaderPackInUse,
        List<EndPortalMesh.FaceQuad> faces,
        List<EndPortalMesh.Triangle> shaderTriangles,
        EndPortalProjection projection
    ) {
        BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        buffer.begin(
            shaderPackInUse ? GL11.GL_TRIANGLES : GL11.GL_QUADS,
            shaderPackInUse ? PORTAL_VERTEX_FORMAT : PROJECTIVE_PORTAL_VERTEX_FORMAT
        );
        for (int index = fromIndex; index < toIndex; index++) {
            EndPortalLayers.Layer layer = layers.get(index);
            if (shaderPackInUse) {
                EndPortalGeometry.emitDivided(
                    shaderTriangles,
                    layer,
                    x,
                    y,
                    z,
                    (face, vertexX, vertexY, vertexZ, u, v, red, green, blue, alpha, lightU, lightV, normalX, normalY, normalZ) ->
                        buffer.pos(vertexX, vertexY, vertexZ)
                            .color(red, green, blue, alpha * layerAlpha)
                            .tex(u, v)
                            .lightmap(lightU, lightV)
                            .normal(normalX, normalY, normalZ)
                            .endVertex()
                );
            } else {
                EndPortalGeometry.emitHomogeneous(
                    faces,
                    projection,
                    x,
                    y,
                    z,
                    layer,
                    (face, vertexX, vertexY, vertexZ, s, t, r, q, red, green, blue, alpha, lightU, lightV, normalX, normalY, normalZ) -> {
                        buffer.pos(vertexX, vertexY, vertexZ).color(red, green, blue, alpha);
                        ((ProjectiveTexCoordBuffer) buffer).actinium$projectiveTexCoord(s, t, r, q);
                        buffer.lightmap(lightU, lightV).normal(normalX, normalY, normalZ).endVertex();
                    }
                );
            }
        }
        buffer.finishDrawing();
        if (buffer.getVertexCount() > 0) {
            VanillaBufferBuilderRenderer.draw(buffer, "EndPortal");
        } else {
            buffer.reset();
        }
    }

    private static void drawComposite(
        double x,
        double y,
        double z,
        List<EndPortalMesh.Triangle> shaderTriangles
    ) {
        BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        buffer.begin(GL11.GL_TRIANGLES, PORTAL_VERTEX_FORMAT);
        EndPortalGeometry.emitComposite(
            shaderTriangles,
            x,
            y,
            z,
            (face, vertexX, vertexY, vertexZ, u, v, red, green, blue, alpha, lightU, lightV, normalX, normalY, normalZ) ->
                buffer.pos(vertexX, vertexY, vertexZ)
                    .color(red, green, blue, alpha)
                    .tex(u, v)
                    .lightmap(lightU, lightV)
                    .normal(normalX, normalY, normalZ)
                    .endVertex()
        );
        buffer.finishDrawing();
        if (buffer.getVertexCount() > 0) {
            VanillaBufferBuilderRenderer.draw(buffer, "EndPortalComposite");
        } else {
            buffer.reset();
        }
    }

    private static VertexFormat createVertexFormat(VertexFormatElement primaryUv) {
        return new VertexFormat()
            .addElement(DefaultVertexFormats.POSITION_3F)
            .addElement(DefaultVertexFormats.COLOR_4UB)
            .addElement(primaryUv)
            .addElement(DefaultVertexFormats.TEX_2S)
            .addElement(DefaultVertexFormats.NORMAL_3B)
            .addElement(DefaultVertexFormats.PADDING_1B);
    }
}
