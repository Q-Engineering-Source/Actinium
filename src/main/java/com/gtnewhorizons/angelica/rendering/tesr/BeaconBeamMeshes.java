package com.gtnewhorizons.angelica.rendering.tesr;

import com.gtnewhorizon.gtnhlib.client.renderer.DirectTessellator;
import com.gtnewhorizon.gtnhlib.client.renderer.vertex.DefaultVertexFormat;
import com.gtnewhorizons.angelica.api.tesr.TesrMaterial;
import com.gtnewhorizons.angelica.api.tesr.TesrMeshSink;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.GLSMMatrices;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntityBeacon;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws the beacon beam from cached meshes instead of rebuilding both shells every frame.
 *
 * <p>1.12.2 stacks one segment per coloured-glass block above the beacon, each with its own height
 * and colour, up to the build limit. A segment is two constant shells: the inner beam is a square
 * tube of radius {@value #BEAM_RADIUS} whose cross-section spins with the world time, and the glow
 * is an axis-aligned box of half-size {@value #GLOW_RADIUS}. Only the texture V offset and the spin
 * change per frame, so both shells are cached per (height, colour) and drawn under a texture matrix
 * translation and a model rotation. Any positive height is cacheable, including the single white
 * segment a bare beacon produces and the shorter ones a beacon near the build limit gets.</p>
 *
 * <p>The fade-in the beacon plays while it powers up is left to the vanilla renderer, which owns
 * that animation's texture scale.</p>
 */
public final class BeaconBeamMeshes {

    private static final ResourceLocation BEACON_TEXTURE = new ResourceLocation("textures/entity/beacon_beam.png");

    /** Vanilla {@code beamRadius}: half the width of the inner tube. */
    static final double BEAM_RADIUS = 0.2;
    /** Vanilla {@code glowRadius}: half the width of the glow box. */
    static final double GLOW_RADIUS = 0.25;
    /** Vanilla glow alpha. */
    static final float GLOW_ALPHA = 0.125F;

    private static final int BEAM_COLOR = 0xFFF0F0;
    private static final float LIGHTMAP = 240.0F;

    private static final Map<Long, Object> INNER_KEYS = new HashMap<>();
    private static final Map<Long, Object> OUTER_KEYS = new HashMap<>();

    private BeaconBeamMeshes() {
    }

    /** Drops the interned mesh keys; their meshes are released with {@link AngelicaTesrMeshCache}. */
    public static void clear() {
        INNER_KEYS.clear();
        OUTER_KEYS.clear();
    }

    public static boolean render(TileEntityBeacon beacon, double x, double y, double z, float partialTicks) {
        final float textureScale = beacon.shouldBeamRender();
        GLSMDepthStencilAlpha.glAlphaFunc(GL11.GL_GREATER, 0.1f);
        if (textureScale <= 0.0f) {
            return true;
        }
        if (textureScale < 1.0f) {
            // The power-up animation scales the texture V extent; vanilla owns that curve.
            return false;
        }

        final List<TileEntityBeacon.BeamSegment> segments = beacon.getBeamSegments();
        if (segments.isEmpty()) {
            return true;
        }

        Minecraft.getMinecraft().getTextureManager().bindTexture(BEACON_TEXTURE);
        GLSMTextures.glTexParameterf(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GLSMTextures.glTexParameterf(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);

        final double time = (double) beacon.getWorld().getTotalWorldTime() + partialTicks;
        final double vOffset = textureOffset(time);
        final double spinDegrees = Math.toDegrees(time * 0.025 * -1.5);

        GLSMMatrices.glMatrixMode(GL11.GL_TEXTURE);
        GLSMMatrices.glPushMatrix();
        GLSMMatrices.glLoadIdentity();
        GLSMMatrices.glTranslatef(0.0F, (float) vOffset, 0.0F);
        GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
        GLSMEnable.glDisable(GL11.GL_FOG);
        try {
            int yOffset = 0;
            for (int i = 0, n = segments.size(); i < n; i++) {
                final TileEntityBeacon.BeamSegment segment = segments.get(i);
                final int height = segment.getHeight();
                if (height <= 0) {
                    continue;
                }
                final float[] colors = segment.getColors();

                GLSMMatrices.glPushMatrix();
                GLSMMatrices.glTranslated(x + 0.5, y + yOffset, z + 0.5);
                GLSMMatrices.glRotatef((float) -spinDegrees, 0.0F, 1.0F, 0.0F);
                AngelicaTesrMeshCache.INSTANCE.renderCached(keyFor(INNER_KEYS, height, colors),
                    sink -> buildInner(sink, height, colors));
                GLSMMatrices.glPopMatrix();

                GLSMMatrices.glPushMatrix();
                GLSMMatrices.glTranslated(x + 0.5, y + yOffset, z + 0.5);
                AngelicaTesrMeshCache.INSTANCE.renderCached(keyFor(OUTER_KEYS, height, colors),
                    sink -> buildOuter(sink, height, colors));
                GLSMMatrices.glPopMatrix();

                yOffset += height;
            }
        } finally {
            GLSMEnable.glEnable(GL11.GL_FOG);
            GLSMMatrices.glMatrixMode(GL11.GL_TEXTURE);
            GLSMMatrices.glPopMatrix();
            GLSMMatrices.glMatrixMode(GL11.GL_MODELVIEW);
            // The glow turns depth writes off; vanilla restores them before the next renderer runs.
            GLSMDepthStencilAlpha.glDepthMask(true);
        }
        return true;
    }

    /** Vanilla {@code d2}: the texture V offset shared by every segment of the beam. */
    static double textureOffset(double time) {
        final double d1 = -time;
        return MathHelper.frac(d1 * 0.2 - MathHelper.floor(d1 * 0.1)) - 1.0;
    }

    /** Vanilla {@code d15} without the {@code d14} offset, which the texture matrix applies. */
    static double innerVTop(int height) {
        return height * (0.5 / BEAM_RADIUS);
    }

    /** Vanilla {@code d14} without the {@code d13} offset, which the texture matrix applies. */
    static double outerVTop(int height) {
        return height;
    }

    static void buildInner(TesrMeshSink sink, int height, float[] colors) {
        final TesrMaterial material = TesrMaterial.builder()
            .color(colors[0], colors[1], colors[2], 1.0F).lightmap(LIGHTMAP, LIGHTMAP)
            .unlit().noCull().cutout(0.1f).stream()
            .special(TesrMaterial.SpecialRender.BEACON_BEAM).build();
        final DirectTessellator t = sink.angelica$bucket(DefaultVertexFormat.POSITION_TEXTURE, BEACON_TEXTURE, material);
        final double[] cx = new double[4];
        final double[] cz = new double[4];
        final double[] angles = {3 * Math.PI / 4, Math.PI / 4, 5 * Math.PI / 4, 7 * Math.PI / 4};
        for (int i = 0; i < 4; i++) {
            cx[i] = Math.cos(angles[i]) * BEAM_RADIUS;
            cz[i] = Math.sin(angles[i]) * BEAM_RADIUS;
        }
        final double vTop = innerVTop(height);
        sideQuad(t, cx[0], cz[0], cx[1], cz[1], height, vTop);
        sideQuad(t, cx[3], cz[3], cx[2], cz[2], height, vTop);
        sideQuad(t, cx[1], cz[1], cx[3], cz[3], height, vTop);
        sideQuad(t, cx[2], cz[2], cx[0], cz[0], height, vTop);
    }

    static void buildOuter(TesrMeshSink sink, int height, float[] colors) {
        final TesrMaterial material = TesrMaterial.builder()
            .color(colors[0], colors[1], colors[2], GLOW_ALPHA).lightmap(LIGHTMAP, LIGHTMAP)
            .unlit().noCull().cutout(0.1f).translucent().noDepthWrite().stream()
            .special(TesrMaterial.SpecialRender.BEACON_BEAM).build();
        final DirectTessellator t = sink.angelica$bucket(DefaultVertexFormat.POSITION_TEXTURE, BEACON_TEXTURE, material);
        final double r = GLOW_RADIUS;
        final double vTop = outerVTop(height);
        sideQuad(t, -r, -r, r, -r, height, vTop);
        sideQuad(t, r, r, -r, r, height, vTop);
        sideQuad(t, r, -r, r, r, height, vTop);
        sideQuad(t, -r, r, -r, -r, height, vTop);
    }

    private static void sideQuad(DirectTessellator t, double x0, double z0, double x1, double z1,
                                 double height, double vTop) {
        t.addVertexWithUV(x0, height, z0, 1.0, vTop);
        t.addVertexWithUV(x0, 0.0, z0, 1.0, 0.0);
        t.addVertexWithUV(x1, 0.0, z1, 0.0, 0.0);
        t.addVertexWithUV(x1, height, z1, 0.0, vTop);
    }

    private static Object keyFor(Map<Long, Object> keys, int height, float[] colors) {
        final long key = ((long) height << 32) | (colorKey(colors) & 0xFFFFFFFFL);
        return keys.computeIfAbsent(key, ignored -> new Object());
    }

    private static int colorKey(float[] colors) {
        final int red = Math.round(colors[0] * 255.0F) & 0xFF;
        final int green = Math.round(colors[1] * 255.0F) & 0xFF;
        final int blue = Math.round(colors[2] * 255.0F) & 0xFF;
        return red << 16 | green << 8 | blue;
    }
}
