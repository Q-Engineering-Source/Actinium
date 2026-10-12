package net.coderbot.iris.uniforms;

import com.gtnewhorizons.angelica.rendering.RenderingState;
import net.coderbot.iris.compat.dh.DHCompat;
import net.coderbot.iris.gl.uniform.UniformHolder;
import net.coderbot.iris.pipeline.ShadowRenderer;
import net.coderbot.iris.shaderpack.PackDirectives;
import net.coderbot.iris.shadow.ShadowMatrices;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static net.coderbot.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME;

public final class MatrixUniforms {
	private MatrixUniforms() {
	}

	private static final Matrix4f GBUFFER_MODELVIEW_SCRATCH = new Matrix4f();

	private static Matrix4fc getGbufferModelView() {
		final Matrix4fc raw = RenderingState.INSTANCE.getModelViewMatrix();
		final Entity view = Minecraft.getMinecraft().getRenderViewEntity();
		if (view == null) {
			return raw;
		}

		return GBUFFER_MODELVIEW_SCRATCH.set(raw).translate(0f, view.getEyeHeight(), 0f);
	}

	public static void addMatrixUniforms(UniformHolder uniforms, PackDirectives directives) {
		addMatrix(uniforms, "ModelView", MatrixUniforms::getGbufferModelView);
		// TODO: In some cases, gbufferProjectionInverse takes on a value much different than OptiFine...
		// We need to audit Mojang's linear algebra.
		addMatrix(uniforms, "Projection", RenderingState.INSTANCE::getProjectionMatrix);
		addDHMatrix(uniforms, "Projection", DHCompat::getProjection);
		addShadowMatrix(uniforms, "ModelView", () -> ShadowRenderer.createShadowModelView(directives.getSunPathRotation(), directives.getShadowDirectives().getIntervalSize()).peek().getModel());
		addShadowMatrix(uniforms, "Projection", () -> ShadowMatrices.createOrthoMatrix(directives.getShadowDirectives().getDistance(), directives.getShadowDirectives().getNearPlane() < 0 ? -DHCompat.getRenderDistance() : directives.getShadowDirectives().getNearPlane(),
			directives.getShadowDirectives().getFarPlane() < 0 ? DHCompat.getRenderDistance() : directives.getShadowDirectives().getFarPlane()));
	}

	private static void addMatrix(UniformHolder uniforms, String name, Supplier<Matrix4fc> supplier) {
		uniforms
			.uniformMatrix(PER_FRAME, "gbuffer" + name, supplier)
			.uniformMatrix(PER_FRAME, "gbuffer" + name + "Inverse", new Inverted(supplier))
			.uniformMatrix(PER_FRAME, "gbufferPrevious" + name, Previous.shared("gbufferPrevious" + name, supplier));
	}

	private static void addDHMatrix(UniformHolder uniforms, String name, Supplier<Matrix4fc> supplier) {
		uniforms
			.uniformMatrix(PER_FRAME, "dh" + name, supplier)
			.uniformMatrix(PER_FRAME, "dh" + name + "Inverse", new Inverted(supplier))
			.uniformMatrix(PER_FRAME, "dhPrevious" + name, Previous.shared("dhPrevious" + name, supplier));
	}


	private static void addShadowMatrix(UniformHolder uniforms, String name, Supplier<Matrix4fc> supplier) {
		uniforms
				.uniformMatrix(PER_FRAME, "shadow" + name, supplier)
				.uniformMatrix(PER_FRAME, "shadow" + name + "Inverse", new Inverted(supplier));
	}

	private static class Inverted implements Supplier<Matrix4fc> {
		private final Supplier<Matrix4fc> parent;

		Inverted(Supplier<Matrix4fc> parent) {
			this.parent = parent;
		}

		@Override
		public Matrix4f get() {
			// PERF: Don't copy + allocate this matrix every time?
            final Matrix4f copy = new Matrix4f(parent.get());

			copy.invert();

			return copy;
		}
	}


	/**
	 * Supplies the value a "previous frame" matrix uniform held on the last rendered frame.
	 * <p>
	 * One tracker per uniform name is shared by every program declaring that uniform: the matrices
	 * come from world render state, so a per-program copy only multiplied the per-frame copies and
	 * uploads. The value advances once per rendered frame ({@link SystemTimeUniforms#COUNTER}), on
	 * the frame's first read — later reads in the same frame return that same snapshot, so a matrix
	 * mutated mid-frame is reported as the previous value one frame later, not immediately.
	 */
	static final class Previous implements Supplier<Matrix4fc> {
		/** Trackers by uniform name; programs register the same name from several threads. */
		private static final Map<String, Previous> INSTANCES = new ConcurrentHashMap<>();

		private final Supplier<Matrix4fc> parent;
		private final Matrix4f previous = new Matrix4f();
		private final Matrix4f current = new Matrix4f();
		private int lastFrame = -1;

		Previous(Supplier<Matrix4fc> parent) {
			this.parent = parent;
		}

		/**
		 * Returns the shared tracker for the given uniform name, creating it on the first
		 * registration. That first registration's supplier is the one captured: every caller of a
		 * name must supply the same matrix source, otherwise only the first is ever read.
		 */
		static Previous shared(String name, Supplier<Matrix4fc> parent) {
			return INSTANCES.computeIfAbsent(name, ignored -> new Previous(parent));
		}

		@Override
		public Matrix4fc get() {
			final int frame = SystemTimeUniforms.COUNTER.getAsInt();

			if (frame != lastFrame) {
				lastFrame = frame;
				previous.set(current);
				current.set(parent.get());
			}

			return previous;
		}
	}
}
