package net.coderbot.iris.postprocess;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import lombok.Getter;
import net.coderbot.iris.features.FeatureFlags;
import net.coderbot.iris.debug.IrisGlDebug;
import net.coderbot.iris.gl.blending.BlendModeOverride;
import net.coderbot.iris.gl.blending.BufferBlendOverride;
import net.coderbot.iris.gl.framebuffer.GlFramebuffer;
import net.coderbot.iris.gl.framebuffer.MinecraftFramebufferHelper;
import net.coderbot.iris.gl.framebuffer.ViewportData;
import net.coderbot.iris.gl.image.GlImage;
import net.coderbot.iris.gl.program.ComputeProgram;
import net.coderbot.iris.gl.program.Program;
import net.coderbot.iris.gl.program.ProgramBuilder;
import net.coderbot.iris.gl.program.ProgramSamplers;
import net.coderbot.iris.gl.program.ProgramUniforms;
import net.coderbot.iris.gl.sampler.SamplerLimits;
import net.coderbot.iris.gl.state.FogMode;
import net.coderbot.iris.gl.texture.TextureAccess;
import net.coderbot.iris.pipeline.PatchedShaderPrinter;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import net.coderbot.iris.pipeline.transform.PatchShaderType;
import net.coderbot.iris.pipeline.transform.TransformPatcher;
import net.coderbot.iris.rendertarget.RenderTarget;
import net.coderbot.iris.rendertarget.RenderTargets;
import net.coderbot.iris.samplers.IrisImages;
import net.coderbot.iris.samplers.IrisSamplers;
import net.coderbot.iris.shaderpack.ComputeSource;
import net.coderbot.iris.shaderpack.PackDirectives;
import net.coderbot.iris.shaderpack.ProgramDirectives;
import net.coderbot.iris.shaderpack.ProgramSource;
import net.coderbot.iris.shaderpack.texture.TextureStage;
import net.coderbot.iris.shadows.ShadowRenderTargets;
import net.coderbot.iris.uniforms.CommonUniforms;
import net.coderbot.iris.uniforms.FrameUpdateNotifier;
import net.coderbot.iris.uniforms.custom.CustomUniforms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

public class CompositeRenderer {
	private final RenderTargets renderTargets;

	private final ImmutableList<Pass> passes;
	private final TextureAccess noiseTexture;
	private final FrameUpdateNotifier updateNotifier;
	private final CenterDepthSampler centerDepthSampler;
    private final CustomUniforms customUniforms;
	private final Object2ObjectMap<String, TextureAccess> customTextureIds;
	@Nullable private final Set<GlImage> customImages;
	@Nullable private final Object2ObjectMap<String, TextureAccess> irisCustomTextures;
	@Nullable private final WorldRenderingPipeline pipeline;
	private final TextureStage textureStage;
	@Getter
    private final ImmutableSet<Integer> flippedAtLeastOnceFinal;

	public CompositeRenderer(PackDirectives packDirectives, ProgramSource[] sources, ComputeSource[][] computes, RenderTargets renderTargets,
							 TextureAccess noiseTexture, FrameUpdateNotifier updateNotifier,
							 CenterDepthSampler centerDepthSampler, BufferFlipper bufferFlipper,
							 Supplier<ShadowRenderTargets> shadowTargetsSupplier,
							 Object2ObjectMap<String, TextureAccess> customTextureIds, ImmutableMap<Integer, Boolean> explicitPreFlips,
							 CustomUniforms customUniforms,
							 Map<Integer, CompletableFuture<Map<PatchShaderType, String>>> precomputedTransformFutures) {
		this(sources, computes, bufferFlipper, new ProgramBuildContext(renderTargets, noiseTexture, updateNotifier, centerDepthSampler, shadowTargetsSupplier, customTextureIds, customUniforms, null, null, null), explicitPreFlips, precomputedTransformFutures, "unknown", TextureStage.COMPOSITE_AND_FINAL);
	}

	public CompositeRenderer(ProgramSource[] sources, ComputeSource[][] computes, BufferFlipper bufferFlipper, ProgramBuildContext context, ImmutableMap<Integer, Boolean> explicitPreFlips, Map<Integer, CompletableFuture<Map<PatchShaderType, String>>> precomputedTransformFutures, String stageName, TextureStage textureStage) {
		this.noiseTexture = context.noiseTexture();
		this.updateNotifier = context.updateNotifier();
		this.centerDepthSampler = context.centerDepthSampler();
		this.renderTargets = context.renderTargets();
		this.customTextureIds = context.customTextureIds();
        this.customUniforms = context.customUniforms();
		this.customImages = context.customImages();
		this.irisCustomTextures = context.irisCustomTextures();
		this.pipeline = context.pipeline();
		this.textureStage = textureStage;

		final ImmutableList.Builder<Pass> passes = ImmutableList.builder();
		final ImmutableSet.Builder<Integer> flippedAtLeastOnce = new ImmutableSet.Builder<>();

		Map<Integer, CompletableFuture<Map<PatchShaderType, String>>> transformFutures = precomputedTransformFutures;

		explicitPreFlips.forEach((buffer, shouldFlip) -> {
			if (shouldFlip) {
				bufferFlipper.flip(buffer);
				// NB: Flipping deferred_pre or composite_pre does NOT cause the "flippedAtLeastOnce" flag to trigger
			}
		});

		for (int i = 0; i < sources.length; i++) {
			final ProgramSource source = sources[i];

			ImmutableSet<Integer> flipped = bufferFlipper.snapshot();
			ImmutableSet<Integer> flippedAtLeastOnceSnapshot = flippedAtLeastOnce.build();

			if (source == null || !source.isValid()) {
				if (computes[i] != null) {
					final ComputeOnlyPass pass = new ComputeOnlyPass();
					pass.computes = createComputes(computes[i], flipped, flippedAtLeastOnceSnapshot, context.shadowTargetsSupplier());
					passes.add(pass);
				}
				continue;
			}

			final Pass pass = new Pass();
			final ProgramDirectives directives = source.getDirectives();

			Map<PatchShaderType, String> transformed = getTransformed(source, transformFutures, i, stageName);
			pass.sourceName = source.getName();
			pass.program = createProgramFromTransformed(source, transformed, flipped, flippedAtLeastOnceSnapshot, context.shadowTargetsSupplier());
			pass.computes = createComputes(computes[i], flipped, flippedAtLeastOnceSnapshot, context.shadowTargetsSupplier());
			pass.blendModeOverride = directives.getBlendModeOverride().orElse(null);
			final int[] drawBuffers = directives.getDrawBuffers();
			pass.bufferBlendOverrides = createBufferBlendOverrides(directives, drawBuffers);
            IrisGlDebug.logFullscreenProgram(this.textureStage.name(), pass.sourceName, pass.program.getProgramId(), drawBuffers);

			final GlFramebuffer framebuffer = renderTargets.createColorFramebuffer(flipped, drawBuffers);

			int passWidth = 0, passHeight = 0;
			// Flip the buffers that this shader wrote to, and set pass width and height
			final ImmutableMap<Integer, Boolean> explicitFlips = directives.getExplicitFlips();

			for (int buffer : drawBuffers) {
				final RenderTarget target = renderTargets.get(buffer);
				if ((passWidth > 0 && passWidth != target.getWidth()) || (passHeight > 0 && passHeight != target.getHeight())) {
					throw new IllegalStateException("Pass widths must match");
				}
				passWidth = target.getWidth();
				passHeight = target.getHeight();

				// compare with boxed Boolean objects to avoid NPEs
				if (explicitFlips.get(buffer) == Boolean.FALSE) {
					continue;
				}

				bufferFlipper.flip(buffer);
				flippedAtLeastOnce.add(buffer);
			}

			explicitFlips.forEach((buffer, shouldFlip) -> {
				if (shouldFlip) {
					bufferFlipper.flip(buffer);
					flippedAtLeastOnce.add(buffer);
				}
			});

			pass.drawBuffers = directives.getDrawBuffers();
			pass.viewWidth = passWidth;
			pass.viewHeight = passHeight;
			pass.stageReadsFromAlt = flipped;
			pass.framebuffer = framebuffer;
			pass.viewportScale = directives.getViewportScale();
			pass.mipmappedBuffers = directives.getMipmappedBuffers();

			passes.add(pass);
		}

		this.passes = passes.build();
		this.flippedAtLeastOnceFinal = flippedAtLeastOnce.build();

		GLStateManager.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
	}

	private Map<PatchShaderType, String> getTransformed(ProgramSource source, Map<Integer, CompletableFuture<Map<PatchShaderType, String>>> transformFutures, int index, String stageName) {
		if (transformFutures != null) {
			final CompletableFuture<Map<PatchShaderType, String>> future = transformFutures.get(index);
			if (future != null) {
				try {
					final Map<PatchShaderType, String> result = future.join();
					if (result != null) {
						return result;
					}
				} catch (CompletionException e) {
					throw new RuntimeException("Shader transformation failed for '" + source.getName() + "' in stage '" + stageName + "' (pass " + index + ")", e.getCause() != null ? e.getCause() : e);
				}
			}
		}

		// Fallback: transform synchronously
		return TransformPatcher.patchComposite(source.getVertexSource().orElseThrow(NullPointerException::new), source.getGeometrySource().orElse(null), source.getTessControlSource().orElse(null), source.getTessEvalSource().orElse(null), source.getFragmentSource().orElseThrow(NullPointerException::new), this.textureStage, pipeline != null ? pipeline.getTextureMap() : null);
	}

    public void recalculateSizes() {
		for (Pass pass : passes) {
			if (pass instanceof ComputeOnlyPass) {
				continue;
			}
			int passWidth = 0, passHeight = 0;
			for (int buffer : pass.drawBuffers) {
				final RenderTarget target = renderTargets.get(buffer);
				if ((passWidth > 0 && passWidth != target.getWidth()) || (passHeight > 0 && passHeight != target.getHeight())) {
					throw new IllegalStateException("Pass widths must match");
				}
				passWidth = target.getWidth();
				passHeight = target.getHeight();
			}
			renderTargets.destroyFramebuffer(pass.framebuffer);
            pass.framebuffer = renderTargets.createColorFramebuffer(pass.stageReadsFromAlt, pass.drawBuffers);
            pass.viewWidth = passWidth;
			pass.viewHeight = passHeight;
		}
	}

	private static class Pass {
		int[] drawBuffers;
		int viewWidth;
		int viewHeight;
		String sourceName;
		Program program;
		ComputeProgram[] computes;
		GlFramebuffer framebuffer;
		ImmutableSet<Integer> stageReadsFromAlt;
		ImmutableSet<Integer> mipmappedBuffers;
		ViewportData viewportScale;
		@Nullable
		BlendModeOverride blendModeOverride;
		List<BufferBlendOverride> bufferBlendOverrides = List.of();

		protected void destroy() {
			this.program.destroy();
			for (ComputeProgram compute : this.computes) {
				if (compute != null) {
					compute.destroy();
				}
			}
		}
	}

	private static List<BufferBlendOverride> createBufferBlendOverrides(ProgramDirectives directives, int[] drawBuffers) {
		List<BufferBlendOverride> overrides = new ArrayList<>();
		directives.getBufferBlendOverrides().forEach(information -> {
			int drawBuffer = Ints.indexOf(drawBuffers, information.getIndex());
			if (drawBuffer >= 0) {
				overrides.add(new BufferBlendOverride(drawBuffer, information.getBlendMode()));
			}
		});
		return overrides;
	}

	private static void applyBlendOverrides(Pass pass) {
		if (pass.blendModeOverride != null) {
			pass.blendModeOverride.apply();
		} else if (!pass.bufferBlendOverrides.isEmpty()) {
			BlendModeOverride.OFF.apply();
		} else {
			BlendModeOverride.restore();
		}

		pass.bufferBlendOverrides.forEach(BufferBlendOverride::apply);
	}

	private static void restoreBlendOverrides(Pass pass) {
		if (pass.blendModeOverride != null || !pass.bufferBlendOverrides.isEmpty()) {
			BlendModeOverride.restore();
		}
	}

	private class ComputeOnlyPass extends Pass {
		@Override
		protected void destroy() {
			for (ComputeProgram compute : this.computes) {
				if (compute != null) {
					compute.destroy();
				}
			}
		}
	}

	public void renderAll() {
        IrisGlDebug.check("composite:start");
        GLStateManager.disableBlend();
        GLStateManager.disableAlphaTest();

		FullScreenQuadRenderer.INSTANCE.begin();

		for (Pass renderPass : passes) {
			boolean ranCompute = false;
			for (ComputeProgram computeProgram : renderPass.computes) {
				if (computeProgram != null) {
					ranCompute = true;
                    final Framebuffer main = Minecraft.getMinecraft().getFramebuffer();
                    computeProgram.use();
                    this.customUniforms.push(computeProgram);
					computeProgram.dispatch(main.framebufferWidth, main.framebufferHeight);
				}
			}

			if (ranCompute) {
				RenderSystem.memoryBarrier(GL42.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL42.GL_TEXTURE_FETCH_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT);
                IrisGlDebug.check("composite:compute-barrier");
			}

			Program.unbind();

			if (renderPass instanceof ComputeOnlyPass) {
				continue;
			}

			if (!renderPass.mipmappedBuffers.isEmpty()) {
                IrisGlDebug.markStage("composite:mipmap");
				GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);

				for (int index : renderPass.mipmappedBuffers) {
					setupMipmapping(CompositeRenderer.this.renderTargets.get(index), renderPass.stageReadsFromAlt.contains(index));
				}
			}

			final float scaledWidth = renderPass.viewWidth * renderPass.viewportScale.scale();
			final float scaledHeight = renderPass.viewHeight * renderPass.viewportScale.scale();
			final float viewportX = renderPass.viewWidth * renderPass.viewportScale.viewportX();
			final float viewportY = renderPass.viewHeight * renderPass.viewportScale.viewportY();
			GLStateManager.glViewport((int) viewportX, (int) viewportY, (int) scaledWidth, (int) scaledHeight);

            IrisGlDebug.markStage("composite:draw");
			renderPass.framebuffer.bind();
            IrisGlDebug.check("composite:bind-fbo");
			applyBlendOverrides(renderPass);
			renderPass.program.use();
            IrisGlDebug.check("composite:use-program");

            this.customUniforms.push(renderPass.program);
            IrisGlDebug.check("composite:uniforms");
            IrisGlDebug.logFullscreenPassState(this.textureStage.name(), renderPass.sourceName, renderPass.program.getProgramId(), renderPass.drawBuffers, renderPass.stageReadsFromAlt, this.renderTargets);
            IrisGlDebug.logWorldPassState(this.textureStage.name(), "fullscreen", renderPass.sourceName);
            IrisGlDebug.logFullscreenSamplerSamples(this.textureStage.name(), renderPass.sourceName, renderPass.program.getProgramId());
            IrisGlDebug.logCloudControlPixels(this.textureStage.name(), renderPass.sourceName, this.renderTargets);
            IrisGlDebug.logCompositeChainPixels(this.textureStage.name(), renderPass.sourceName, this.renderTargets);
            if (this.pipeline != null) {
                IrisGlDebug.logCompositeDepthPixels(this.textureStage.name(), renderPass.sourceName, this.renderTargets, this.pipeline.getDHCompat().getDepthTex(), this.pipeline.getDHCompat().getDepthTexNoTranslucent());
                IrisGlDebug.logCloudTerrainChainPixels(this.textureStage.name(), renderPass.sourceName, this.renderTargets, this.pipeline.getDHCompat().getDepthTex(), this.pipeline.getDHCompat().getDepthTexNoTranslucent());
            }
			FullScreenQuadRenderer.uploadCompositeMatrices();
            IrisGlDebug.check("composite:matrices");

            String previousSamplePhase = IrisGlDebug.replaceFramebufferSamplePhase("composite-pass");
			FullScreenQuadRenderer.INSTANCE.renderQuad();
            IrisGlDebug.check("composite:render-quad");
            IrisGlDebug.logCurrentFramebufferSamples("composite:" + renderPass.sourceName, renderPass.drawBuffers.length);
            IrisGlDebug.restoreFramebufferSamplePhase(previousSamplePhase);
			restoreBlendOverrides(renderPass);
		}

		FullScreenQuadRenderer.end();

        IrisGlDebug.check("composite:end");
		// Make sure to reset the viewport to how it was before... Otherwise weird issues could occur.
		// Also bind the "main" framebuffer if it isn't already bound.
        MinecraftFramebufferHelper.bindMainFramebuffer(true);
        IrisGlDebug.check("composite:restore-main");
		ProgramUniforms.clearActiveUniforms();
		ProgramSamplers.clearActiveSamplers();
		BlendModeOverride.restore();
		GLStateManager.glUseProgram(0);

		// NB: Unbinding all of these textures is necessary for proper shaderpack reloading.
		for (int i = 0; i < SamplerLimits.get().getMaxTextureUnits(); i++) {
			// Unbind all textures that we may have used.
			// NB: This is necessary for shader pack reloading to work propely
			GLStateManager.glActiveTexture(GL13.GL_TEXTURE0 + i);
			GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, 0);
		}

		GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);
	}

	private static void setupMipmapping(RenderTarget target, boolean readFromAlt) {
		int texture = readFromAlt ? target.getAltTexture() : target.getMainTexture();

		// TODO: Only generate the mipmap if a valid mipmap hasn't been generated or if we've written to the buffer
		// (since the last mipmap was generated)
		//
		// NB: We leave mipmapping enabled even if the buffer is written to again, this appears to match the
		// behavior of ShadersMod/OptiFine, however I'm not sure if it's desired behavior. It's possible that a
		// program could use mipmapped sampling with a stale mipmap, which probably isn't great. However, the
		// sampling mode is always reset between frames, so this only persists after the first program to use
		// mipmapping on this buffer.
		//
		// Also note that this only applies to one of the two buffers in a render target buffer pair - making it
		// unlikely that this issue occurs in practice with most shader packs.
		RenderSystem.generateMipmaps(texture, GL11.GL_TEXTURE_2D);

		int filter = GL11.GL_LINEAR_MIPMAP_LINEAR;
		if (target.getInternalFormat().getPixelFormat().isInteger()) {
			filter = GL11.GL_NEAREST_MIPMAP_NEAREST;
		}

		RenderSystem.texParameteri(texture, GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
	}

	// TODO: Don't just copy this from DeferredWorldRenderingPipeline
	private Program createProgramFromTransformed(ProgramSource source, Map<PatchShaderType, String> transformed,
												 ImmutableSet<Integer> flipped, ImmutableSet<Integer> flippedAtLeastOnceSnapshot,
												 Supplier<ShadowRenderTargets> shadowTargetsSupplier) {
		String vertex = transformed.get(PatchShaderType.VERTEX);
		String geometry = transformed.get(PatchShaderType.GEOMETRY);
		String tessControl = transformed.get(PatchShaderType.TESS_CONTROL);
		String tessEval = transformed.get(PatchShaderType.TESS_EVAL);
		String fragment = transformed.get(PatchShaderType.FRAGMENT);
		PatchedShaderPrinter.debugPatchedShaders(source.getName(), vertex, geometry, tessControl, tessEval, fragment);

		Objects.requireNonNull(flipped);
		ProgramBuilder builder;

		try {
			builder = ProgramBuilder.begin(source.getName(), vertex, geometry, tessControl, tessEval, fragment,
				IrisSamplers.COMPOSITE_RESERVED_TEXTURE_UNITS);
		} catch (RuntimeException e) {
			// TODO: Better error handling
			throw new RuntimeException("Shader compilation failed!", e);
		}

        CommonUniforms.addDynamicUniforms(builder, FogMode.OFF);
        this.customUniforms.assignTo(builder);

		ProgramSamplers.CustomTextureSamplerInterceptor customTextureSamplerInterceptor = ProgramSamplers.customTextureSamplerInterceptor(builder, customTextureIds, flippedAtLeastOnceSnapshot);

		IrisSamplers.addRenderTargetSamplers(customTextureSamplerInterceptor, () -> flipped, renderTargets, true, pipeline);
		IrisSamplers.addCustomImages(customTextureSamplerInterceptor, customImages);
		IrisSamplers.addCustomTextures(customTextureSamplerInterceptor, irisCustomTextures);

		IrisImages.addRenderTargetImages(builder, () -> flipped, renderTargets);
		IrisImages.addCustomImages(builder, customImages);

		IrisSamplers.addNoiseSampler(customTextureSamplerInterceptor, noiseTexture);
		IrisSamplers.addCompositeSamplers(customTextureSamplerInterceptor, renderTargets);

		if (IrisSamplers.hasShadowSamplers(customTextureSamplerInterceptor)) {
			IrisSamplers.addShadowSamplers(customTextureSamplerInterceptor, shadowTargetsSupplier.get(), null, pipeline != null && pipeline.hasFeature(FeatureFlags.SEPARATE_HARDWARE_SAMPLERS));
			IrisImages.addShadowColorImages(builder, shadowTargetsSupplier.get(), null);
		}

		// TODO: Don't duplicate this with FinalPassRenderer
		centerDepthSampler.setUsage(builder.addDynamicSampler(centerDepthSampler::getCenterDepthTexture, "iris_centerDepthSmooth"));

		Program build = builder.build();

	    this.customUniforms.mapholderToPass(builder, build);

        return build;
    }

	private ComputeProgram[] createComputes(ComputeSource[] compute, ImmutableSet<Integer> flipped, ImmutableSet<Integer> flippedAtLeastOnceSnapshot, Supplier<ShadowRenderTargets> shadowTargetsSupplier) {
		ComputeProgram[] programs = new ComputeProgram[compute.length];
		for (int i = 0; i < programs.length; i++) {
			ComputeSource source = compute[i];
			if (source == null || !source.getSource().isPresent()) {
				continue;
			} else {
				// TODO: Properly handle empty shaders
				Objects.requireNonNull(flipped);
				ProgramBuilder builder;

				try {
					String transformed = TransformPatcher.patchCompute(source.getName(), source.getSource().orElse(null), this.textureStage, pipeline != null ? pipeline.getTextureMap() : null);
					PatchedShaderPrinter.debugPatchedShaders(source.getName() + "_compute", null, null, null, transformed);
					builder = ProgramBuilder.beginCompute(source.getName(), transformed, IrisSamplers.COMPOSITE_RESERVED_TEXTURE_UNITS);
				} catch (RuntimeException e) {
					// TODO: Better error handling
					throw new RuntimeException("Shader compilation failed!", e);
				}

				ProgramSamplers.CustomTextureSamplerInterceptor customTextureSamplerInterceptor = ProgramSamplers.customTextureSamplerInterceptor(builder, customTextureIds, flippedAtLeastOnceSnapshot);

				CommonUniforms.addDynamicUniforms(builder, FogMode.OFF);

                this.customUniforms.assignTo(builder);

				IrisSamplers.addRenderTargetSamplers(customTextureSamplerInterceptor, () -> flipped, renderTargets, true, pipeline);
				IrisSamplers.addCustomImages(customTextureSamplerInterceptor, customImages);
				IrisSamplers.addCustomTextures(customTextureSamplerInterceptor, irisCustomTextures);

				IrisImages.addRenderTargetImages(builder, () -> flipped, renderTargets);
				IrisImages.addCustomImages(builder, customImages);

				IrisSamplers.addNoiseSampler(customTextureSamplerInterceptor, noiseTexture);
				IrisSamplers.addCompositeSamplers(customTextureSamplerInterceptor, renderTargets);

				if (IrisSamplers.hasShadowSamplers(customTextureSamplerInterceptor)) {
					IrisSamplers.addShadowSamplers(customTextureSamplerInterceptor, shadowTargetsSupplier.get(), null, pipeline != null && pipeline.hasFeature(FeatureFlags.SEPARATE_HARDWARE_SAMPLERS));
					IrisImages.addShadowColorImages(builder, shadowTargetsSupplier.get(), null);
				}

				// TODO: Don't duplicate this with FinalPassRenderer
				centerDepthSampler.setUsage(builder.addDynamicSampler(centerDepthSampler::getCenterDepthTexture, "iris_centerDepthSmooth"));

				programs[i] = builder.buildCompute();

                customUniforms.mapholderToPass(builder, programs[i]);

				programs[i].setWorkGroupInfo(source.getWorkGroupRelative(), source.getWorkGroups(), null);
			}
		}


		return programs;
	}

	public void destroy() {
		for (Pass renderPass : passes) {
			renderPass.destroy();
		}
	}
}
