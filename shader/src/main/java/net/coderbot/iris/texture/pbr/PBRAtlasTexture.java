package net.coderbot.iris.texture.pbr;

import com.gtnewhorizons.angelica.compat.mojang.AutoClosableAbstractTexture;
import com.gtnewhorizons.angelica.glsm.profiling.DebugCounters;
import lombok.Getter;
import net.coderbot.iris.Iris;
import net.coderbot.iris.debug.IrisDebugOptions;
import net.coderbot.iris.debug.PBRDebug;
import net.coderbot.iris.texture.SpriteAnimationGate;
import net.coderbot.iris.texture.SpriteAnimationTicker;
import net.coderbot.iris.texture.pbr.loader.AtlasPBRLoader;
import net.coderbot.iris.texture.util.TextureExporter;
import net.coderbot.iris.texture.util.TextureManipulationUtil;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.data.AnimationMetadataSection;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.util.ReportedException;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PBRAtlasTexture extends AutoClosableAbstractTexture {
	protected final TextureMap texMap;
	@Getter
    protected final PBRType type;
	protected final ResourceLocation id;
	protected final Map<ResourceLocation, TextureAtlasSprite> sprites = new HashMap<>();
	protected final List<SpriteAnimationTicker> gatedSprites = new ArrayList<>();
	protected final List<TextureAtlasSprite> alwaysAnimatedSprites = new ArrayList<>();

	public PBRAtlasTexture(TextureMap textureMap, PBRType type) {
		this.texMap = textureMap;
		this.type = type;
		id = type.appendToFileLocation(TextureMap.LOCATION_BLOCKS_TEXTURE);

	}

    public ResourceLocation getAtlasId() {
		return id;
	}

	public void addSprite(AtlasPBRLoader.PBRTextureAtlasSprite sprite) {
		sprites.put(completeResourceLocation(new ResourceLocation(sprite.getIconName())), sprite);
		if (!isAnimated(sprite)) {
			return;
		}

		// A PBR sprite is never sampled through sprite UVs, so its visibility can only come from the
		// base sprite. Without the animation mixins, or for a base sprite that carries no animation
		// metadata, nothing would ever mark it, so it keeps uploading every frame.
		if (sprite instanceof SpriteAnimationTicker ticker && sprite.getBaseSprite().hasAnimationMetadata()) {
			gatedSprites.add(ticker);
		} else {
			alwaysAnimatedSprites.add(sprite);
		}
	}

	@Nullable
	public TextureAtlasSprite getSprite(ResourceLocation id) {
		return sprites.get(id);
	}

	public void clear() {
		sprites.clear();
		gatedSprites.clear();
		alwaysAnimatedSprites.clear();
	}

	public void upload(int atlasWidth, int atlasHeight, int mipLevel, float anisotropicFiltering) {
		final int glId = getGlTextureId();
		TextureUtil.allocateTextureImpl(glId, mipLevel, atlasWidth, atlasHeight);
		TextureManipulationUtil.fillWithColor(glId, mipLevel, type.getDefaultValue());

		for (TextureAtlasSprite sprite : sprites.values()) {
			try {
				uploadSprite(sprite);
			} catch (Exception e) {
				CrashReport crashReport = CrashReport.makeCrashReport(e, "Stitching texture atlas");
				CrashReportCategory crashReportCategory = crashReport.makeCategory("Texture being stitched together");
				crashReportCategory.addCrashSection("Atlas path", id);
				crashReportCategory.addCrashSection("Sprite", sprite);
				throw new ReportedException(crashReport);
			}
		}

		if (!gatedSprites.isEmpty() || !alwaysAnimatedSprites.isEmpty()) {
			final PBRAtlasHolder pbrHolder = ((TextureAtlasExtension) texMap).getOrCreatePBRHolder();
			switch (type) {
			case NORMAL:
				pbrHolder.setNormalAtlas(this);
				break;
			case SPECULAR:
				pbrHolder.setSpecularAtlas(this);
				break;
			}
		}

		PBRDebug.atlasUploaded(this, atlasWidth, atlasHeight, mipLevel, sprites.size(), animatedSpriteCount());
		if (IrisDebugOptions.pbrDebugEnabled()) {
			TextureExporter.exportTextures("pbr_debug/atlas", id.getNamespace() + "_" + id.getPath().replaceAll("/", "_"), glId, mipLevel, atlasWidth, atlasHeight);
		}
	}

	public boolean tryUpload(int atlasWidth, int atlasHeight, int mipLevel, float anisotropicFiltering) {
		try {
			upload(atlasWidth, atlasHeight, mipLevel, anisotropicFiltering);
			return true;
		} catch (Exception t) {
            Iris.logger.error("Could not upload PBR texture", t);
			return false;
		}
	}

    @Override
    public void loadTexture(IResourceManager manager) throws IOException {
        // todo
    }

    protected void uploadSprite(TextureAtlasSprite sprite) {
		final AnimationMetadataSection metadata = sprite.animationMetadata;
		if (metadata != null && metadata.getFrameCount() > 0) {
			final int frameCount = sprite.getFrameCount();
			for (int frame = Math.min(sprite.frameCounter, metadata.getFrameCount() - 1); frame >= 0; frame--) {
				final int frameIndex = metadata.getFrameIndex(frame);
				if (frameIndex >= 0 && frameIndex < frameCount) {
                    TextureUtil.uploadTextureMipmap(sprite.getFrameTextureData(frameIndex), sprite.getIconWidth(), sprite.getIconHeight(), sprite.getOriginX(), sprite.getOriginY(), false, false);
					return;
				}
			}
		}
		TextureUtil.uploadTextureMipmap(sprite.getFrameTextureData(0), sprite.getIconWidth(), sprite.getIconHeight(), sprite.getOriginX(), sprite.getOriginY(), false, false);
	}

	private static boolean isAnimated(TextureAtlasSprite sprite) {
		final AnimationMetadataSection metadata = sprite.animationMetadata;
		return metadata != null && metadata.getFrameCount() > 1;
	}

	private int animatedSpriteCount() {
		return gatedSprites.size() + alwaysAnimatedSprites.size();
	}

	/**
	 * Advances every animated PBR sprite by one tick.
	 *
	 * <p>Sprites whose visibility the base sprite can mark are ticked through the gate, so only the
	 * visible ones upload their new frame while the rest only advance their frame counters.
	 * Everything else uploads unconditionally, because nothing would ever mark it visible.
	 */
	public void cycleAnimationFrames(boolean onlyVisible) {
		if (gatedSprites.isEmpty() && alwaysAnimatedSprites.isEmpty()) {
			return;
		}

		bind();
		int updated = alwaysAnimatedSprites.size();
		for (int i = 0; i < gatedSprites.size(); i++) {
			if (gatedSprites.get(i).tickAnimation(onlyVisible)) {
				updated++;
			}
		}
		for (int i = 0; i < alwaysAnimatedSprites.size(); i++) {
			alwaysAnimatedSprites.get(i).updateAnimation();
		}

		DebugCounters.PBR_ANIM_UPDATES.add(updated);
		DebugCounters.PBR_ANIM_DRY_RUNS.add(animatedSpriteCount() - updated);
	}

	/**
	 * Changes the animation frames of the PBR sprites with the gate the texture atlas resolved for
	 * this frame.
	 */
	public void cycleAnimationFrames() {
		final TextureMap map = texMap;
		final boolean onlyVisible = map instanceof SpriteAnimationGate gate && gate.isAnimatingOnlyVisibleSprites();
		cycleAnimationFrames(onlyVisible);
	}

	private ResourceLocation completeResourceLocation(ResourceLocation spriteName) {
		return new ResourceLocation(spriteName.getNamespace(), texMap.getBasePath() + "/" + spriteName.getPath() + ".png");
	}

	@Override
	public void close() {
		final PBRAtlasHolder pbrHolder = ((TextureAtlasExtension) texMap).getPBRHolder();
		if (pbrHolder != null) {
            switch (type) {
                case NORMAL -> {
                    if (pbrHolder.getNormalAtlas() == this) {
                        pbrHolder.setNormalAtlas(null);
                    }
                }
                case SPECULAR -> {
                    if (pbrHolder.getSpecularAtlas() == this) {
                        pbrHolder.setSpecularAtlas(null);
                    }
                }
            }
		}
		clear();
		PBRDebug.atlasClosed(this);
	}
}
