package net.coderbot.iris.pipeline;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import com.gtnewhorizons.angelica.glsm.GLSMMatrices;

/**
 * Renders the sky horizon. Vanilla Minecraft simply uses the "clear color" for its horizon, and then draws a plane
 * above the player. This class extends the sky rendering so that an octagonal prism is drawn around the player instead,
 * allowing shaders to perform more advanced sky rendering.
 * <p>
 * However, the horizon rendering is designed so that when sky shaders are not being used, it looks almost exactly the
 * same as vanilla sky rendering, except a few almost entirely imperceptible differences where the walls
 * of the octagonal prism intersect the top plane.
 */
public class HorizonRenderer {
	/**
	 * The Y coordinate of the top skybox plane. Acts as the upper bound for the horizon prism, since the prism lies
	 * between the bottom and top skybox planes.
	 */
	private static final float TOP = 16.0F;

	/**
	 * The Y coordinate of the bottom skybox plane. Acts as the lower bound for the horizon prism, since the prism lies
	 * between the bottom and top skybox planes.
	 */
	private static final float BOTTOM = -16.0F;

	/**
	 * Cosine of 22.5 degrees.
	 */
	private static final double COS_22_5 = Math.cos(Math.toRadians(22.5));

	/**
	 * Sine of 22.5 degrees.
	 */
	private static final double SIN_22_5 = Math.sin(Math.toRadians(22.5));
	private int currentRenderDistance;

	public HorizonRenderer() {
		currentRenderDistance = SkyRenderDistance.effectiveChunks(
			Minecraft.getMinecraft().gameSettings.renderDistanceChunks
		);
	}

	private void rebuildBuffer() {
	}

	private void buildQuad(BufferBuilder consumer, double x1, double z1, double x2, double z2) {
		consumer.pos(x1, BOTTOM, z1).endVertex();
		consumer.pos(x1, TOP, z1).endVertex();
		consumer.pos(x2, TOP, z2).endVertex();
		consumer.pos(x2, BOTTOM, z2).endVertex();
	}

	private void buildHalf(BufferBuilder consumer, double adjacent, double opposite, boolean invert) {
		if (invert) {
			adjacent = -adjacent;
			opposite = -opposite;
		}

		// NB: Make sure that these vertices are being specified in counterclockwise order!
		// Otherwise back face culling will remove your quads, and you'll be wondering why there's a hole in your horizon.
		// Don't poke holes in the horizon. Specify vertices in counterclockwise order.

		// +X,-Z face
		buildQuad(consumer, adjacent, -opposite, opposite, -adjacent);
		// +X face
		buildQuad(consumer, adjacent, opposite, adjacent, -opposite);
		// +X,+Z face
		buildQuad(consumer, opposite, adjacent, adjacent, opposite);
		// +Z face
		buildQuad(consumer, -opposite, adjacent, opposite, adjacent);
	}

	/**
	 * @param adjacent the adjacent side length of a triangle with a hypotenuse extending from the center of the
	 *                 octagon to a given vertex on the perimeter.
	 * @param opposite the opposite side length of a triangle with a hypotenuse extending from the center of the
	 *                 octagon to a given vertex on the perimeter.
	 */
	private void buildOctagonalPrism(BufferBuilder consumer, double adjacent, double opposite) {
		buildHalf(consumer, adjacent, opposite, false);
		buildHalf(consumer, adjacent, opposite, true);
	}

	private void buildRegularOctagonalPrism(BufferBuilder consumer, double radius) {
		buildOctagonalPrism(consumer, radius * COS_22_5, radius * SIN_22_5);
	}

	private void buildBottomPlane(BufferBuilder consumer, int radius) {
		for (int x = -radius; x <= radius; x += 64) {
			for (int z = -radius; z <= radius; z += 64) {
				consumer.pos(x + 64, BOTTOM, z).endVertex();
				consumer.pos(x, BOTTOM, z).endVertex();
				consumer.pos(x, BOTTOM, z + 64).endVertex();
				consumer.pos(x + 64, BOTTOM, z + 64).endVertex();
			}
		}
	}

	private void buildTopPlane(BufferBuilder consumer, int radius) {
		// You might be tempted to try to combine this with buildBottomPlane to avoid code duplication,
		// but that won't work since the winding order has to be reversed or else one of the planes will be
		// discarded by back face culling.
		for (int x = -radius; x <= radius; x += 64) {
			for (int z = -radius; z <= radius; z += 64) {
				consumer.pos(x + 64, TOP, z).endVertex();
				consumer.pos(x + 64, TOP, z + 64).endVertex();
				consumer.pos(x, TOP, z + 64).endVertex();
				consumer.pos(x, TOP, z).endVertex();
			}
		}
	}

	private void buildHorizon(int radius, BufferBuilder consumer) {
		if (radius > 256) {
			// Prevent the prism from getting too large, this causes issues on some shader packs that modify the vanilla
			// sky if we don't do this.
			radius = 256;
		}

		buildRegularOctagonalPrism(consumer, radius);

		// Replicate the vanilla top plane since we can't assume that it'll be rendered.
		// TODO: Remove vanilla top plane
		buildTopPlane(consumer, 384);

		// Always make the bottom plane have a radius of 384, to match the top plane.
		buildBottomPlane(consumer, 384);
	}

	public void renderHorizon(FloatBuffer matrix) {
		int effectiveRenderDistance = SkyRenderDistance.effectiveChunks(
			Minecraft.getMinecraft().gameSettings.renderDistanceChunks
		);
		if (currentRenderDistance != effectiveRenderDistance) {
			currentRenderDistance = effectiveRenderDistance;
			rebuildBuffer();
		}

        GLSMMatrices.glPushMatrix();
        GLSMMatrices.glLoadIdentity();
        GLSMMatrices.glMultMatrix(matrix);

		Tessellator tessellator = Tessellator.getInstance();
		BufferBuilder buffer = tessellator.getBuffer();
		GLStateManager.disableCull();
		buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
		buildHorizon(SkyRenderDistance.effectiveBlocks(currentRenderDistance), buffer);
		tessellator.draw();

        GLSMMatrices.glPopMatrix();
	}

	public void destroy() {
	}
}
