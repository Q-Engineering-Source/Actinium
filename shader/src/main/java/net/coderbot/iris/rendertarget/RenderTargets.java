package net.coderbot.iris.rendertarget;

import net.coderbot.iris.debug.IrisDebugOptions;
import net.coderbot.iris.debug.ShaderMemoryDiagnostics;
import com.google.common.collect.ImmutableSet;
import com.gtnewhorizons.angelica.glsm.GLDebug;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import lombok.Getter;
import net.coderbot.iris.Iris;
import net.coderbot.iris.gl.framebuffer.GlFramebuffer;
import net.coderbot.iris.gl.texture.DepthBufferFormat;
import net.coderbot.iris.gl.texture.DepthCopyStrategy;
import net.coderbot.iris.shaderpack.PackDirectives;
import net.coderbot.iris.shaderpack.PackRenderTargetDirectives;
import org.joml.Vector2i;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RenderTargets {
	// When enabled, a failed framebuffer creation is retried once with fresh bindings (actinium.fbRetry)
	private static final boolean FB_RETRY = Boolean.parseBoolean(System.getProperty("actinium.fbRetry", "true"));

	// Advisory mode: a framebuffer that fails the driver's completeness check is used anyway.
	// Forced by -Dactinium.advisoryFboStatus=true|false; otherwise follows the GUI option
	// "Ignore Framebuffer Errors" (debug.ignoreFramebufferErrors). Intended for Android GL
	// translation layers (e.g. MobileGlues) where the verdict can be a false negative.
	private static boolean loggedAdvisoryBypass;

	private final RenderTarget[] targets;
	private int currentDepthTexture;
	private DepthBufferFormat currentDepthFormat;

    @Getter
	private final DepthTexture noTranslucents;
	private final DepthTexture noHand;
	private final GlFramebuffer depthSourceFb;
	private final GlFramebuffer noTranslucentsDestFb;
	private final GlFramebuffer noHandDestFb;
	private DepthCopyStrategy copyStrategy;

	private final List<GlFramebuffer> ownedFramebuffers;

	private int cachedWidth;
	private int cachedHeight;
	@Getter
    private boolean fullClearRequired;
	private boolean translucentDepthDirty;
	private boolean handDepthDirty;

	private int cachedDepthBufferVersion;
	private long memoryEstimateBytes;
	private boolean memoryDiagnosticsRegistered;

	public RenderTargets(int width, int height,  int depthTexture, int depthBufferVersion, DepthBufferFormat depthFormat, Map<Integer, PackRenderTargetDirectives.RenderTargetSettings> renderTargets, PackDirectives packDirectives) {
        targets = new RenderTarget[renderTargets.size()];

		renderTargets.forEach((index, settings) -> {
			// TODO: Handle mipmapping?
			Vector2i dimensions = packDirectives.getTextureScaleOverride(index, width, height);
			// Apply format fallback for opengl versions with limited color-renderable format support (e.g., macOS GL 2.1)
			var requestedFormat = settings.getInternalFormat();
			var actualFormat = requestedFormat.getColorRenderableFallback();
			if (actualFormat != requestedFormat) {
				Iris.logger.info("Render target {} using fallback format {} (requested {})", index, actualFormat, requestedFormat);
			}
			targets[index] = RenderTarget.builder().setDimensions(dimensions.x, dimensions.y)
					.setInternalFormat(actualFormat)
					.setPixelFormat(actualFormat.getPixelFormat()).build();
			Iris.logger.info("Render target colortex{}: format={} (gl=0x{}) size={}x{}", index, actualFormat, Integer.toHexString(actualFormat.getGlFormat()), dimensions.x, dimensions.y);
		});
		this.currentDepthTexture = depthTexture;
		this.currentDepthFormat = depthFormat;
		this.copyStrategy = DepthCopyStrategy.fastest(currentDepthFormat.isCombinedStencil());

		this.cachedWidth = width;
		this.cachedHeight = height;
		this.cachedDepthBufferVersion = depthBufferVersion;

		this.ownedFramebuffers = new ArrayList<>();

		// NB: Make sure all buffers are cleared so that they don't contain undefined
		// data. Otherwise very weird things can happen.
		fullClearRequired = true;

		this.depthSourceFb = createFramebufferWritingToMain(new int[] {0});

		this.noTranslucents = new DepthTexture(width, height, currentDepthFormat);
		this.noHand = new DepthTexture(width, height, currentDepthFormat);

		this.noTranslucentsDestFb = createFramebufferWritingToMain(new int[] {0});
		this.noTranslucentsDestFb.addDepthAttachment(this.noTranslucents.getTextureId());

		this.noHandDestFb = createFramebufferWritingToMain(new int[] {0});
		this.noHandDestFb.addDepthAttachment(this.noHand.getTextureId());

		this.translucentDepthDirty = true;
		this.handDepthDirty = true;
		this.memoryEstimateBytes = estimateMemoryBytes();
		ShaderMemoryDiagnostics.renderTargetsCreated(targets.length, memoryEstimateBytes);
		memoryDiagnosticsRegistered = true;
	}

	public void destroy() {
		for (GlFramebuffer owned : ownedFramebuffers) {
			owned.destroy();
		}

		for (RenderTarget target : targets) {
			target.destroy();
		}

		noTranslucents.destroy();
		noHand.destroy();
		if (memoryDiagnosticsRegistered) {
			ShaderMemoryDiagnostics.renderTargetsDestroyed(targets.length, memoryEstimateBytes);
			memoryDiagnosticsRegistered = false;
		}
	}

	public int getRenderTargetCount() {
		return targets.length;
	}

	public RenderTarget get(int index) {
		return targets[index];
	}

	public int getDepthTexture() {
		return currentDepthTexture;
	}

	public DepthTexture getDepthTextureNoTranslucents() {
		return noTranslucents;
	}

	public DepthTexture getDepthTextureNoHand() {
		return noHand;
	}

    public boolean resizeIfNeeded(int newDepthBufferVersion, int newDepthTextureId, int newWidth, int newHeight, DepthBufferFormat newDepthFormat, PackDirectives packDirectives) {
        final long previousMemoryEstimateBytes = memoryEstimateBytes;
        boolean recreateDepth = false;
        if (cachedDepthBufferVersion != newDepthBufferVersion) {
            recreateDepth = true;
            currentDepthTexture = newDepthTextureId;
            cachedDepthBufferVersion = newDepthBufferVersion;
        }

        boolean sizeChanged = newWidth != cachedWidth || newHeight != cachedHeight;
        boolean depthFormatChanged = newDepthFormat != currentDepthFormat;

        if (depthFormatChanged) {
            currentDepthFormat = newDepthFormat;
            // Might need a new copy strategy
            copyStrategy = DepthCopyStrategy.fastest(currentDepthFormat.isCombinedStencil());
        }

        if (recreateDepth) {
            // Re-attach the depth textures with the new depth texture ID, since Minecraft re-creates
            // the depth texture when resizing its render targets.
            //
            // I'm not sure if our framebuffers holding on to the old depth texture between frames
            // could be a concern, in the case of resizing and similar. I think it should work
            // based on what I've seen of the spec, though - it seems like deleting a texture
            // automatically detaches it from its framebuffers.
            for (GlFramebuffer framebuffer : ownedFramebuffers) {
                if (framebuffer == noHandDestFb || framebuffer == noTranslucentsDestFb) {
                    // NB: Do not change the depth attachment of these framebuffers
                    // as it is intentionally different
                    continue;
                }

                if (framebuffer.isDepthAttachmentManagedExternally()) {
                    // NB: Distant Horizons framebuffers re-attach DH's own depth texture via
                    // DHCompatInternal.reconnectDHTextures on the next LOD pass; attaching the
                    // window depth here leaves them broken until a full shader reload.
                    continue;
                }

                if (framebuffer.hasDepthAttachment()) {
                    framebuffer.addDepthAttachment(newDepthTextureId);
                }
            }
        }

        if (depthFormatChanged || sizeChanged)  {
            noTranslucents.resize(newWidth, newHeight, newDepthFormat);
            noHand.resize(newWidth, newHeight, newDepthFormat);
            this.translucentDepthDirty = true;
            this.handDepthDirty = true;
        }

        if (sizeChanged) {
            cachedWidth = newWidth;
            cachedHeight = newHeight;

            for (int i = 0; i < targets.length; i++) {
                targets[i].resize(packDirectives.getTextureScaleOverride(i, newWidth, newHeight));
            }

            fullClearRequired = true;
        }

        if (memoryDiagnosticsRegistered && (sizeChanged || depthFormatChanged)) {
            memoryEstimateBytes = estimateMemoryBytes();
            ShaderMemoryDiagnostics.renderTargetsResized(targets.length, previousMemoryEstimateBytes,
                memoryEstimateBytes);
        }

        return sizeChanged;
    }

	private long estimateMemoryBytes() {
		long estimatedBytes = 2L * ShaderMemoryDiagnostics.estimateDepthTextureBytes(
			currentDepthFormat, cachedWidth, cachedHeight);
		for (RenderTarget target : targets) {
			if (target != null) {
				estimatedBytes += 2L * ShaderMemoryDiagnostics.estimateColorTextureBytes(
					target.getInternalFormat(), target.getWidth(), target.getHeight());
			}
		}
		return estimatedBytes;
	}

	public void copyPreTranslucentDepth() {
		if (translucentDepthDirty) {
			translucentDepthDirty = false;
			GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, noTranslucents.getTextureId());
			depthSourceFb.bindAsReadBuffer();
			RenderSystem.copyTexImage2D(GL11.GL_TEXTURE_2D, 0, currentDepthFormat.getGlInternalFormat(), 0, 0, cachedWidth, cachedHeight, 0);
		} else {
			copyStrategy.copy(depthSourceFb, getDepthTexture(), noTranslucentsDestFb, noTranslucents.getTextureId(), getCurrentWidth(), getCurrentHeight());
		}
	}

	public void copyPreHandDepth() {
		if (handDepthDirty) {
			handDepthDirty = false;
			GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, noHand.getTextureId());
			depthSourceFb.bindAsReadBuffer();
			RenderSystem.copyTexImage2D(GL11.GL_TEXTURE_2D, 0, currentDepthFormat.getGlInternalFormat(), 0, 0, cachedWidth, cachedHeight, 0);
		} else {
			copyStrategy.copy(depthSourceFb, getDepthTexture(), noHandDestFb, noHand.getTextureId(), getCurrentWidth(), getCurrentHeight());
		}
	}

    public void onFullClear() {
		fullClearRequired = false;
	}

	public GlFramebuffer createFramebufferWritingToMain(int[] drawBuffers) {
		return createFullFramebuffer(false, drawBuffers);
	}

	public GlFramebuffer createFramebufferWritingToAlt(int[] drawBuffers) {
		return createFullFramebuffer(true, drawBuffers);
	}

	public GlFramebuffer createClearFramebuffer(boolean alt, int[] clearBuffers) {
		ImmutableSet<Integer> stageWritesToMain = ImmutableSet.of();

		if (!alt) {
			stageWritesToMain = invert(ImmutableSet.of(), clearBuffers);
		}

		return createColorFramebuffer(stageWritesToMain, clearBuffers);
	}

	private ImmutableSet<Integer> invert(ImmutableSet<Integer> base, int[] relevant) {
		ImmutableSet.Builder<Integer> inverted = ImmutableSet.builder();

		for (int i : relevant) {
			if (!base.contains(i)) {
				inverted.add(i);
			}
		}

		return inverted.build();
	}

	private GlFramebuffer createEmptyFramebuffer() {
		GlFramebuffer framebuffer = new GlFramebuffer();
		ownedFramebuffers.add(framebuffer);

		framebuffer.addDepthAttachment(currentDepthTexture);

		// NB: Before OpenGL 3.0, all framebuffers are required to have a color attachment no matter what.
		framebuffer.addColorAttachment(0, get(0).getMainTexture());
		framebuffer.noDrawBuffers();

		return framebuffer;
	}

	public GlFramebuffer createDHFramebuffer(ImmutableSet<Integer> stageWritesToAlt, int[] drawBuffers) {
		final GlFramebuffer framebuffer;

		if (drawBuffers.length == 0) {
			framebuffer = createEmptyFramebuffer();
		} else {
			ImmutableSet<Integer> stageWritesToMain = invert(stageWritesToAlt, drawBuffers);
			framebuffer = createColorFramebuffer(stageWritesToMain, drawBuffers);
		}

		// Distant Horizons owns the depth attachment of these framebuffers: its LOD pass
		// re-attaches DH's own depth texture via DHCompatInternal.reconnectDHTextures, so
		// window-depth rebuilds on resize must leave them alone.
		framebuffer.setDepthAttachmentManagedExternally(true);
		return framebuffer;
	}

	public GlFramebuffer createGbufferFramebuffer(ImmutableSet<Integer> stageWritesToAlt, int[] drawBuffers) {
		if (drawBuffers.length == 0) {
			return createEmptyFramebuffer();
		}

		ImmutableSet<Integer> stageWritesToMain = invert(stageWritesToAlt, drawBuffers);
        GlFramebuffer framebuffer =  createColorFramebuffer(stageWritesToMain, drawBuffers);
        framebuffer.addDepthAttachment(currentDepthTexture);

		return framebuffer;
	}

	private GlFramebuffer createFullFramebuffer(boolean clearsAlt, int[] drawBuffers) {
		if (drawBuffers.length == 0) {
			return createEmptyFramebuffer();
		}

		ImmutableSet<Integer> stageWritesToMain = ImmutableSet.of();

		if (!clearsAlt) {
			stageWritesToMain = invert(ImmutableSet.of(), drawBuffers);
		}

		return createColorFramebufferWithDepth(stageWritesToMain, drawBuffers);
	}

	public GlFramebuffer createColorFramebufferWithDepth(ImmutableSet<Integer> stageWritesToMain, int[] drawBuffers) {
        final GlFramebuffer framebuffer = createColorFramebuffer(stageWritesToMain, drawBuffers);
        framebuffer.addDepthAttachment(currentDepthTexture);

		return framebuffer;
	}

	public GlFramebuffer createColorFramebuffer(ImmutableSet<Integer> stageWritesToMain, int[] drawBuffers) {
		if (drawBuffers.length == 0) {
			throw new IllegalArgumentException("Framebuffer must have at least one color buffer");
		}

        final GlFramebuffer framebuffer = new GlFramebuffer();
        ownedFramebuffers.add(framebuffer);

		final int[] actualDrawBuffers = new int[drawBuffers.length];

		for (int i = 0; i < actualDrawBuffers.length; i++) {
			actualDrawBuffers[i] = i;
		}

		setupColorAttachments(framebuffer, stageWritesToMain, drawBuffers);

		framebuffer.drawBuffers(actualDrawBuffers);
        framebuffer.readBuffer(0);

		final int status = framebuffer.checkStatus();
		if (status == GL30.GL_FRAMEBUFFER_COMPLETE) {
			return framebuffer;
		}

		// Dump full diagnostics before failing: the raw exception alone hides the actual GL status.
		final String diagnostics = describeFramebuffer(stageWritesToMain, drawBuffers, framebuffer);
		Iris.logger.warn("Failed to create color framebuffer: status={} ({})\n{}",
				String.format("0x%04X", status), GLDebug.getFramebufferStatusName(status), diagnostics);

		if (isAdvisoryFboStatus()) {
			if (!loggedAdvisoryBypass) {
				loggedAdvisoryBypass = true;
				Iris.logger.info("Advisory FBO status mode active (actinium.advisoryFboStatus override or 'Ignore Framebuffer Errors' option); incomplete framebuffers will be used anyway");
			}
			Iris.logger.warn("Framebuffer reported incomplete but will be used anyway (advisory mode)");
			return framebuffer;
		}

		if (!FB_RETRY) {
			throw new IllegalStateException("Unexpected error while creating framebuffer: status="
					+ String.format("0x%04X", status) + " (" + GLDebug.getFramebufferStatusName(status) + ")\n" + diagnostics);
		}

		// Self-heal: unbind everything and rebuild the framebuffer from scratch. Works around GL
		// translation layers (e.g. MobileGlues) leaving stale framebuffer bindings behind.
		GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

		final GlFramebuffer retry = new GlFramebuffer();
		setupColorAttachments(retry, stageWritesToMain, drawBuffers);
		retry.drawBuffers(actualDrawBuffers);
		retry.readBuffer(0);

		final int retryStatus = retry.checkStatus();
		if (retryStatus == GL30.GL_FRAMEBUFFER_COMPLETE) {
			Iris.logger.warn("Framebuffer recovered on retry (first status={} ({}))",
					String.format("0x%04X", status), GLDebug.getFramebufferStatusName(status));
			ownedFramebuffers.add(retry);
			destroyFramebuffer(framebuffer);
			return retry;
		}

		destroyFramebuffer(retry);
		throw new IllegalStateException("Unexpected error while creating framebuffer: status="
				+ String.format("0x%04X", status) + " (" + GLDebug.getFramebufferStatusName(status) + "), retry status="
				+ String.format("0x%04X", retryStatus) + " (" + GLDebug.getFramebufferStatusName(retryStatus) + ")\n" + diagnostics);
	}

	private void setupColorAttachments(GlFramebuffer framebuffer, ImmutableSet<Integer> stageWritesToMain, int[] drawBuffers) {
		for (int i = 0; i < drawBuffers.length; i++) {
			if (drawBuffers[i] >= getRenderTargetCount()) {
				// TODO: This causes resource leaks, also we should really verify this in the shaderpack parser...
				throw new IllegalStateException("Render target with index " + drawBuffers[i] + " is not supported, only "
						+ getRenderTargetCount() + " render targets are supported.");
			}

			final RenderTarget target = this.get(drawBuffers[i]);

			final int textureId = stageWritesToMain.contains(drawBuffers[i]) ? target.getMainTexture() : target.getAltTexture();

			framebuffer.addColorAttachment(i, textureId);
        }
	}

	private String describeFramebuffer(ImmutableSet<Integer> stageWritesToMain, int[] drawBuffers, GlFramebuffer framebuffer) {
		final StringBuilder sb = new StringBuilder();

		for (int i = 0; i < drawBuffers.length; i++) {
			final RenderTarget target = this.get(drawBuffers[i]);
			final boolean main = stageWritesToMain.contains(drawBuffers[i]);
			final int textureId = main ? target.getMainTexture() : target.getAltTexture();
			sb.append(String.format("  drawBuffer[%d]: colortex%d texture=%d (%s) format=%s (gl=0x%X) size=%dx%d%n",
					i, drawBuffers[i], textureId, main ? "main" : "alt",
					target.getInternalFormat(), target.getInternalFormat().getGlFormat(),
					target.getWidth(), target.getHeight()));
		}

		for (int i = 0; i < drawBuffers.length; i++) {
			sb.append(String.format("  attachment[%d]: objectType=0x%X objectName=%d%n", i,
					framebuffer.getAttachmentParameter(i, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE),
					framebuffer.getAttachmentParameter(i, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME)));
		}

		for (int i = 0; i < drawBuffers.length; i++) {
			final RenderTarget target = this.get(drawBuffers[i]);
			final int textureId = stageWritesToMain.contains(drawBuffers[i]) ? target.getMainTexture() : target.getAltTexture();
			sb.append(String.format("  texture %d: level0 width=%d internalFormat=0x%X%n", textureId,
					RenderSystem.getTexLevelParameteri(textureId, 0, GL11.GL_TEXTURE_WIDTH),
					RenderSystem.getTexLevelParameteri(textureId, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT)));
		}

		return sb.toString();
	}

	public void destroyFramebuffer(GlFramebuffer framebuffer) {
		framebuffer.destroy();
		ownedFramebuffers.remove(framebuffer);
	}

	public int getCurrentWidth() {
		return cachedWidth;
	}

	public int getCurrentHeight() {
		return cachedHeight;
	}

	// -Dactinium.advisoryFboStatus=true|false forces advisory mode; otherwise the GUI option decides.
	private static boolean isAdvisoryFboStatus() {
		final String override = System.getProperty("actinium.advisoryFboStatus");
		if (override != null) {
			return Boolean.parseBoolean(override);
		}

		try {
			return IrisDebugOptions.ignoreFramebufferErrors();
		} catch (RuntimeException ignored) {
			return false;
		}
	}
}
