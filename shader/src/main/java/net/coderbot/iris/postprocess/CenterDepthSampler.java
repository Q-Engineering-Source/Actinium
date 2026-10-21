package net.coderbot.iris.postprocess;

import com.google.common.collect.ImmutableSet;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import net.coderbot.iris.debug.IrisGlDebug;
import net.coderbot.iris.gl.ResourceCleanup;
import net.coderbot.iris.gl.framebuffer.GlFramebuffer;
import net.coderbot.iris.gl.framebuffer.MinecraftFramebufferHelper;
import net.coderbot.iris.gl.program.Program;
import net.coderbot.iris.gl.program.ProgramBuilder;
import net.coderbot.iris.gl.program.ProgramSamplers;
import net.coderbot.iris.gl.program.ProgramUniforms;
import net.coderbot.iris.gl.texture.DepthCopyStrategy;
import net.coderbot.iris.gl.texture.InternalTextureFormat;
import net.coderbot.iris.gl.texture.PixelType;
import net.coderbot.iris.gl.uniform.UniformUpdateFrequency;
import net.coderbot.iris.uniforms.SystemTimeUniforms;
import dhj.embeddedt.embeddium.impl.render.shader.ShaderLoader;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.util.function.IntSupplier;
import java.util.ArrayList;
import java.util.List;

public class CenterDepthSampler {
	private static final double LN2 = Math.log(2);
	private boolean hasFirstSample;
	private boolean everRetrieved;
	private final Program program;
	private final GlFramebuffer framebuffer;
	private final int texture;
	private final int altTexture;
	private boolean destroyed;

	public CenterDepthSampler(IntSupplier depthSupplier, float halfLife) {
		int texture = 0;
		int altTexture = 0;
		GlFramebuffer framebuffer = null;
		try {
			texture = GLStateManager.glGenTextures();
			altTexture = GLStateManager.glGenTextures();
			framebuffer = new GlFramebuffer();

			// Fall back to a less precise format if the system doesn't support OpenGL 3
			InternalTextureFormat format = GLStateManager.capabilities.OpenGL32 ? InternalTextureFormat.R32F : InternalTextureFormat.RGB16;
			setupColorTexture(texture, format);
			setupColorTexture(altTexture, format);
			GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, 0);

			framebuffer.addColorAttachment(0, texture);
			String vsh = ShaderLoader.getShaderSource("angelica:centerDepth.vsh");
			String fsh = ShaderLoader.getShaderSource("angelica:centerDepth.fsh");
			final int sampledAltTexture = altTexture;
			final Program program;
			try (ProgramBuilder builder = ProgramBuilder.begin("centerDepthSmooth", vsh, null, fsh, ImmutableSet.of(0, 1, 2))) {
				builder.addDynamicSampler(depthSupplier, "depth");
				builder.addDynamicSampler(() -> sampledAltTexture, "altDepth");
				builder.uniform1f(UniformUpdateFrequency.PER_FRAME, "lastFrameTime", SystemTimeUniforms.TIMER::getLastFrameTime);
				builder.uniform1f(UniformUpdateFrequency.ONCE, "decay", () -> (1.0f / ((halfLife * 0.1) / LN2)));
				program = builder.build();
				builder.commit();
			}

			this.texture = texture;
			this.altTexture = altTexture;
			this.framebuffer = framebuffer;
			this.program = program;
		} catch (RuntimeException | Error failure) {
			final List<Runnable> cleanupActions = new ArrayList<>();
			if (framebuffer != null) {
				cleanupActions.add(framebuffer::destroy);
			}
			if (altTexture != 0) {
				final int allocatedAltTexture = altTexture;
				cleanupActions.add(() -> GLStateManager.glDeleteTextures(allocatedAltTexture));
			}
			if (texture != 0) {
				final int allocatedTexture = texture;
				cleanupActions.add(() -> GLStateManager.glDeleteTextures(allocatedTexture));
			}
			ResourceCleanup.runAll(failure, cleanupActions);
			throw failure;
		}
	}

	public void sampleCenterDepth() {
        IrisGlDebug.check("center-depth:start");
		if ((hasFirstSample && (!everRetrieved)) || destroyed) {
			// If the shaderpack isn't reading center depth values, don't bother sampling it
			// This improves performance with most shaderpacks
			return;
		}

		hasFirstSample = true;

		this.framebuffer.bind();
        IrisGlDebug.check("center-depth:bind-fbo");
		this.program.use();
        IrisGlDebug.check("center-depth:use-program");

		GLStateManager.glViewport(0, 0, 1, 1);

		FullScreenQuadRenderer.INSTANCE.render();
        IrisGlDebug.check("center-depth:render");

		ProgramUniforms.clearActiveUniforms();
		ProgramSamplers.clearActiveSamplers();

		// The API contract of DepthCopyStrategy claims it can only copy depth, however the 2 non-stencil methods used are entirely capable of copying color as of now.
		DepthCopyStrategy.fastest(false).copy(this.framebuffer, texture, null, altTexture, 1, 1);
        IrisGlDebug.check("center-depth:copy");

		//Reset viewport
        MinecraftFramebufferHelper.bindMainFramebuffer(true);
        IrisGlDebug.check("center-depth:restore-main");
	}

	public void setupColorTexture(int texture, InternalTextureFormat format) {
		RenderSystem.texImage2D(texture, GL11.GL_TEXTURE_2D, 0, format.getGlFormat(), 1, 1, 0, format.getPixelFormat().getGlFormat(), PixelType.FLOAT.getGlFormat(), null);

		RenderSystem.texParameteri(texture, GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
		RenderSystem.texParameteri(texture, GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
		RenderSystem.texParameteri(texture, GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		RenderSystem.texParameteri(texture, GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
	}

	public int getCenterDepthTexture() {
		return altTexture;
	}

	public void setUsage(boolean usage) {
		everRetrieved |= usage;
	}

	public void destroy() {
		if (destroyed) {
			return;
		}
		destroyed = true;
		ResourceCleanup.runAll(null,
			() -> GLStateManager.glDeleteTextures(texture),
			() -> GLStateManager.glDeleteTextures(altTexture),
			framebuffer::destroy,
			program::destroy);
	}
}
