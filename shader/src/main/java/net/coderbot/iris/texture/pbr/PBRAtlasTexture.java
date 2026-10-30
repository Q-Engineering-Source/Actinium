package net.coderbot.iris.texture.pbr;

import net.coderbot.iris.debug.IrisDebugOptions;
import com.gtnewhorizons.angelica.compat.mojang.AutoClosableAbstractTexture;
import net.coderbot.iris.debug.PBRDebug;
import lombok.Getter;
import net.coderbot.iris.Iris;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PBRAtlasTexture extends AutoClosableAbstractTexture {
	protected final TextureMap texMap;
	@Getter
    protected final PBRType type;
	protected final ResourceLocation id;
	protected final Map<ResourceLocation, TextureAtlasSprite> sprites = new HashMap<>();
	protected final Set<TextureAtlasSprite> animatedSprites = new HashSet<>();

	public PBRAtlasTexture(TextureMap textureMap, PBRType type) {
		this.texMap = textureMap;
		this.type = type;
		id = type.appendToFileLocation(TextureMap.LOCATION_BLOCKS_TEXTURE);

	}

    public ResourceLocation getAtlasId() {
		return id;
	}

	public void addSprite(TextureAtlasSprite sprite) {
		sprites.put(completeResourceLocation(new ResourceLocation(sprite.getIconName())), sprite);
		if (isAnimated(sprite)) {
			animatedSprites.add(sprite);
		}
	}

	@Nullable
	public TextureAtlasSprite getSprite(ResourceLocation id) {
		return sprites.get(id);
	}

	public void clear() {
		sprites.clear();
		animatedSprites.clear();
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

		if (!animatedSprites.isEmpty()) {
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

		PBRDebug.atlasUploaded(this, atlasWidth, atlasHeight, mipLevel, sprites.size(), animatedSprites.size());
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

	public void cycleAnimationFrames() {
		if (animatedSprites.isEmpty()) {
			return;
		}

		bind();
		for (TextureAtlasSprite sprite : animatedSprites) {
            sprite.updateAnimation();
		}
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
