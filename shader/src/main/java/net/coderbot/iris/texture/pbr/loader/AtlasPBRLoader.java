package net.coderbot.iris.texture.pbr.loader;

import com.gtnewhorizons.angelica.compat.mojang.NativeImage;
import com.gtnewhorizons.angelica.glsm.texture.TextureInfo;
import com.gtnewhorizons.angelica.glsm.texture.TextureInfoCache;
import net.coderbot.iris.debug.PBRDebug;
import net.coderbot.iris.Iris;
import net.coderbot.iris.texture.format.TextureFormat;
import net.coderbot.iris.texture.format.TextureFormatLoader;
import net.coderbot.iris.texture.mipmap.ChannelMipmapGenerator;
import net.coderbot.iris.texture.mipmap.CustomMipmapGenerator;
import net.coderbot.iris.texture.mipmap.LinearBlendFunction;
import net.coderbot.iris.texture.pbr.PBRAtlasTexture;
import net.coderbot.iris.texture.pbr.PBRSpriteHolder;
import net.coderbot.iris.texture.pbr.PBRType;
import net.coderbot.iris.texture.pbr.TextureAtlasSpriteExtension;
import net.coderbot.iris.texture.util.ImageManipulationUtil;
import dhj.embeddedt.embeddium.impl.texture.SpritePaddingExtension;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.data.AnimationFrame;
import net.minecraft.client.resources.data.AnimationMetadataSection;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nullable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AtlasPBRLoader implements PBRTextureLoader<TextureMap> {
    public static final ChannelMipmapGenerator LINEAR_MIPMAP_GENERATOR = new ChannelMipmapGenerator(
        LinearBlendFunction.INSTANCE,
        LinearBlendFunction.INSTANCE,
        LinearBlendFunction.INSTANCE,
        LinearBlendFunction.INSTANCE
    );

    @Override
    public void load(TextureMap texMap, IResourceManager resourceManager, PBRTextureConsumer pbrTextureConsumer) {
        final TextureInfo textureInfo = TextureInfoCache.INSTANCE.getInfo(texMap.getGlTextureId());
        final int atlasWidth = textureInfo.getWidth();
        final int atlasHeight = textureInfo.getHeight();
        final int mipLevel = fetchAtlasMipLevel(texMap);

        PBRAtlasTexture normalAtlas = null;
        PBRAtlasTexture specularAtlas = null;
        for (TextureAtlasSprite sprite : texMap.mapUploadedSprites.values()) {
            if (!(sprite.getIconName().equals("missingno"))) {
                TextureAtlasSprite normalSprite = createPBRSprite(sprite, resourceManager, texMap, atlasWidth, atlasHeight, mipLevel, PBRType.NORMAL);
                TextureAtlasSprite specularSprite = createPBRSprite(sprite, resourceManager, texMap, atlasWidth, atlasHeight, mipLevel, PBRType.SPECULAR);
                if (normalSprite != null) {
                    if (normalAtlas == null) {
                        normalAtlas = new PBRAtlasTexture(texMap, PBRType.NORMAL);
                    }
                    normalAtlas.addSprite(normalSprite);
                    final PBRSpriteHolder pbrSpriteHolder = ((TextureAtlasSpriteExtension) sprite).getOrCreatePBRHolder();
                    pbrSpriteHolder.setNormalSprite(normalSprite);
                }
                if (specularSprite != null) {
                    if (specularAtlas == null) {
                        specularAtlas = new PBRAtlasTexture(texMap, PBRType.SPECULAR);
                    }
                    specularAtlas.addSprite(specularSprite);
                    final PBRSpriteHolder pbrSpriteHolder = ((TextureAtlasSpriteExtension) sprite).getOrCreatePBRHolder();
                    pbrSpriteHolder.setSpecularSprite(specularSprite);
                }
            }
        }

        if (normalAtlas != null) {
            if (normalAtlas.tryUpload(atlasWidth,
                atlasHeight,
                mipLevel,
                1.0F)) {
                pbrTextureConsumer.acceptNormalTexture(normalAtlas);
            }
        }
        if (specularAtlas != null) {
            if (specularAtlas.tryUpload(atlasWidth, atlasHeight, mipLevel, 1.0F)) {
                pbrTextureConsumer.acceptSpecularTexture(specularAtlas);
            }
        }
    }

    protected static int fetchAtlasMipLevel(TextureMap texMap) {
        return texMap.getMipmapLevels();
    }

    @Nullable
    protected TextureAtlasSprite createPBRSprite(TextureAtlasSprite sprite, IResourceManager resourceManager, TextureMap texMap, int atlasWidth, int atlasHeight, int mipLevel, PBRType pbrType) {
        final ResourceLocation spriteName = new ResourceLocation(sprite.getIconName());
        final ResourceLocation imageLocation = completeResourceLocation(texMap, spriteName);
        final ResourceLocation pbrImageLocation = pbrType.appendToFileLocation(imageLocation);

        TextureAtlasSprite pbrSprite = null;

        try  {
            // This is no longer closable. Not sure about this.
            final IResource resource = resourceManager.getResource(pbrImageLocation);
            if (PBRType.hasDirectionalSiblings(pbrImageLocation, resourceManager)) {
                // Looks like a cardinal-direction texture set (e.g. "_n"/"_s"/"_e"/"_w" for block faces),
                // not an actual PBR map. Don't treat it as one.
                return null;
            }
            NativeImage nativeImage = NativeImage.read(resource.getInputStream());
            AnimationMetadataSection animationMetadata = (AnimationMetadataSection) resource.getMetadata("animation");
            if (animationMetadata == null) {
                final IResource resourceOriginal = resourceManager.getResource(imageLocation);
                animationMetadata = (AnimationMetadataSection) resourceOriginal.getMetadata("animation");
            }

            final Pair<Integer, Integer> frameSize = this.getFrameSize(nativeImage.getWidth(), nativeImage.getHeight(), animationMetadata);
            int frameWidth = frameSize.getLeft();
            int frameHeight = frameSize.getRight();
            final int targetFrameWidth = sprite.getIconWidth();
            final int targetFrameHeight = sprite.getIconHeight();
            if (frameWidth != targetFrameWidth || frameHeight != targetFrameHeight) {
                final int imageWidth = nativeImage.getWidth();
                final int imageHeight = nativeImage.getHeight();

                // We can assume the following is always true as a result of getFrameSize's check:
                // imageWidth % frameWidth == 0 && imageHeight % frameHeight == 0
                final int targetImageWidth = imageWidth / frameWidth * targetFrameWidth;
                final int targetImageHeight = imageHeight / frameHeight * targetFrameHeight;

                final NativeImage scaledImage;
                if (targetImageWidth % imageWidth == 0 && targetImageHeight % imageHeight == 0) {
                    scaledImage = ImageManipulationUtil.scaleNearestNeighbor(nativeImage, targetImageWidth, targetImageHeight);
                } else {
                    scaledImage = ImageManipulationUtil.scaleBilinear(nativeImage, targetImageWidth, targetImageHeight);
                }

                // This is no longer closeable either
//                nativeImage.close();
                nativeImage = scaledImage;

                frameWidth = targetFrameWidth;
                frameHeight = targetFrameHeight;

                if (animationMetadata != null) {
                    if (animationMetadata.getFrameWidth() != -1) {
                        animationMetadata.frameWidth = frameWidth;
                    }
                    if (animationMetadata.getFrameHeight() != -1) {
                        animationMetadata.frameHeight = frameHeight;
                    }
                }
            }

            final ResourceLocation pbrSpriteName = new ResourceLocation(spriteName.getNamespace(), spriteName.getPath() + pbrType.getSuffix());
            final TextureAtlasSpriteInfo pbrSpriteInfo = new PBRTextureAtlasSpriteInfo(pbrSpriteName, frameWidth, frameHeight, pbrType);

            animationMetadata = normalizeAnimationMetadata(animationMetadata, nativeImage.getHeight(), frameWidth, frameHeight);

            final int gutter = ((SpritePaddingExtension) sprite).angelica$getGutterWidth();

            pbrSprite = new PBRTextureAtlasSprite(pbrSpriteInfo, animationMetadata, atlasWidth, atlasHeight,
                sprite.getOriginX(), sprite.getOriginY(), gutter, nativeImage, mipLevel);
            syncAnimation(sprite, pbrSprite);
            PBRDebug.spriteLoaded(pbrType, pbrImageLocation, pbrSprite);
        } catch (FileNotFoundException e) {
            PBRDebug.spriteMissing(pbrType, pbrImageLocation, e);
            return null;
        } catch (RuntimeException e) {
            PBRDebug.spriteFailed(pbrType, pbrImageLocation, e);
            Iris.logger.error("Unable to parse metadata from {} : {}", pbrImageLocation, e);
        } catch (IOException e) {
            PBRDebug.spriteFailed(pbrType, pbrImageLocation, e);
            Iris.logger.error("Unable to load {} : {}", pbrImageLocation, e);
        }

        return pbrSprite;
    }


	protected void syncAnimation(TextureAtlasSprite source, TextureAtlasSprite target) {
        if (!isAnimated(source) || !isAnimated(target)) {
			return;
		}

        final AnimationMetadataSection sourceMetadata = source.animationMetadata;

		int ticks = 0;
		for (int f = 0; f < source.frameCounter; f++) {
			ticks += sourceMetadata.getFrameTimeSingle(f);
		}

        final AnimationMetadataSection targetMetadata = target.animationMetadata;

		int cycleTime = 0;
        final int frameCount = targetMetadata.getFrameCount();
		for (int f = 0; f < frameCount; f++) {
			cycleTime += targetMetadata.getFrameTimeSingle(f);
		}
		if (cycleTime <= 0) {
			return;
		}
		ticks %= cycleTime;

		int targetFrame = 0;
		while (true) {
            final int time = targetMetadata.getFrameTimeSingle(targetFrame);
			if (ticks >= time) {
				targetFrame++;
				ticks -= time;
			} else {
				break;
			}
		}

		target.frameCounter = targetFrame;
		target.tickCounter = ticks + source.tickCounter;
	}

    private static boolean isAnimated(TextureAtlasSprite sprite) {
        final AnimationMetadataSection metadata = sprite.animationMetadata;
        return metadata != null && metadata.getFrameCount() > 1;
    }

    private static AnimationMetadataSection normalizeAnimationMetadata(@Nullable AnimationMetadataSection metadata,
                                                                       int imageHeight, int frameWidth, int frameHeight) {
        if (metadata == null || metadata.getFrameCount() > 0) {
            return metadata;
        }

        int frameCount = imageHeight / frameHeight;
        if (frameCount <= 1) {
            return null;
        }

        List<AnimationFrame> frames = new ArrayList<>(frameCount);
        for (int frame = 0; frame < frameCount; frame++) {
            frames.add(new AnimationFrame(frame, -1));
        }
        return new AnimationMetadataSection(frames, frameWidth, frameHeight,
            metadata.getFrameTime(), metadata.isInterpolate());
    }

	protected static class PBRTextureAtlasSpriteInfo extends TextureAtlasSpriteInfo {
		protected final PBRType pbrType;

		public PBRTextureAtlasSpriteInfo(ResourceLocation name, int width, int height, PBRType pbrType) {
			super(name, width, height);
			this.pbrType = pbrType;
		}
	}

    public static class PBRTextureAtlasSprite extends TextureAtlasSprite implements CustomMipmapGenerator.Provider {
        // This feels super janky
        protected PBRTextureAtlasSprite(TextureAtlasSpriteInfo info, AnimationMetadataSection animationMetaDataSection, int atlasWidth, int atlasHeight, int x, int y, int gutter, NativeImage nativeImage, int miplevel) {
            super(info.name().toString());
            // The sprite's own origin is inset from the slot by its gutter, so the slot origin has to be
            // handed to initSprite for the shared inset to land on the same texel as the base atlas.
            ((SpritePaddingExtension) this).angelica$setGutterWidth(gutter);
            super.setIconWidth(info.width());
            super.setIconHeight(info.height());
            super.initSprite(atlasWidth, atlasHeight, x - gutter, y - gutter, false);
            this.animationMetadata = animationMetaDataSection;

            final CustomMipmapGenerator mipmapGenerator = getMipmapGenerator(info, atlasWidth, atlasHeight);
            final Set<Integer> frameIndices = new HashSet<>();
            if (animationMetaDataSection == null) {
                frameIndices.add(0);
            } else {
                for (int frame = 0; frame < animationMetaDataSection.getFrameCount(); frame++) {
                    frameIndices.add(animationMetaDataSection.getFrameIndex(frame));
                }
            }

            int maxFrameIndex = frameIndices.stream().mapToInt(Integer::intValue).max().orElse(0);
            for (int frame = 0; frame <= maxFrameIndex; frame++) {
                this.framesTextureData.add(null);
            }

            for (int frameIndex : frameIndices) {
                int frameY = frameIndex * info.height();
                if (frameIndex < 0 || frameY + info.height() > nativeImage.getHeight()) {
                    throw new IllegalArgumentException("PBR animation frame " + frameIndex + " is outside image bounds");
                }
                NativeImage frameImage = copyFrame(nativeImage, frameY, info.width(), info.height());
                this.framesTextureData.set(frameIndex, toMipmapData(mipmapGenerator.generateMipLevels(frameImage, miplevel)));
            }
        }

        @Override
        public CustomMipmapGenerator getMipmapGenerator(TextureAtlasSpriteInfo info, int atlasWidth, int atlasHeight) {
            if (info instanceof PBRTextureAtlasSpriteInfo pbrInfo) {
                final PBRType pbrType = pbrInfo.pbrType;
                final TextureFormat format = TextureFormatLoader.getFormat();
                if (format != null) {
                    final CustomMipmapGenerator generator = format.getMipmapGenerator(pbrType);
                    if (generator != null) {
                        return generator;
                    }
                }
            }
            return LINEAR_MIPMAP_GENERATOR;
        }
    }

    private static ResourceLocation completeResourceLocation(TextureMap texMap, ResourceLocation spriteName) {
        return new ResourceLocation(spriteName.getNamespace(), texMap.getBasePath() + "/" + spriteName.getPath() + ".png");
    }

    private static int[][] toMipmapData(NativeImage[] mipmaps) {
        int[][] data = new int[mipmaps.length][];
        for (int i = 0; i < mipmaps.length; i++) {
            NativeImage image = mipmaps[i];
            data[i] = new int[image.getWidth() * image.getHeight()];
            image.getRGB(0, 0, image.getWidth(), image.getHeight(), data[i], 0, image.getWidth());
        }
        return data;
    }

    private static NativeImage copyFrame(NativeImage source, int sourceY, int width, int height) {
        NativeImage frame = new NativeImage(source.getFormat(), width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                frame.setPixelRGBA(x, y, source.getPixelRGBA(x, sourceY + y));
            }
        }
        return frame;
    }

    private Pair<Integer, Integer> getFrameSize(int i, int j, @Nullable AnimationMetadataSection animationMetadataSection) {
        final Pair<Integer, Integer> pair = this.calculateFrameSize(i, j, animationMetadataSection);
        final int k = pair.getLeft();
        final int l = pair.getRight();
        if (isDivisionInteger(i, k) && isDivisionInteger(j, l)) {
            return pair;
        } else {
            throw new IllegalArgumentException(String.format("Image size %s,%s is not multiply of frame size %s,%s", i, j, k, l));
        }
    }

    private Pair<Integer, Integer> calculateFrameSize(int i, int j, @Nullable AnimationMetadataSection animationMetadataSection) {
        if (animationMetadataSection == null) {
            return Pair.of(i, j);
        }
        if (animationMetadataSection.getFrameWidth() != -1) {
            return animationMetadataSection.getFrameHeight() != -1 ? Pair.of(animationMetadataSection.getFrameWidth(), animationMetadataSection.getFrameHeight()) : Pair.of(animationMetadataSection.getFrameWidth(), j);
        } else if (animationMetadataSection.getFrameHeight() != -1) {
            return Pair.of(i, animationMetadataSection.getFrameHeight());
        } else {
            int k = Math.min(i, j);
            return Pair.of(k, k);
        }
    }

    private static boolean isDivisionInteger(int i, int j) {
        return j > 0 && i % j == 0;
    }

}
