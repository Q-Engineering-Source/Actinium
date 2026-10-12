package com.dhj.actinium.mixin.vintage.features.textures;

import com.dhj.actinium.mixin.vintage.features.textures.mixinterface.AtlasSessionHolder;
import com.dhj.actinium.texture.TextureMapExtension;
import com.dhj.actinium.texture.atlas.AtlasAssembler;
import com.dhj.actinium.texture.atlas.AtlasLoadSession;
import com.dhj.actinium.texture.atlas.AtlasLoadStats;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Drives the parallel atlas load from {@code loadTextureAtlas}.
 *
 * <p>Vanilla stitches the atlas first and only then decodes each sprite, builds its mipmaps and uploads it,
 * one sprite at a time. That order is kept, but the three steps are widened: the decode lane is seeded with
 * the stitched sprites in the order the loop will consume them, the loop's own generation call is what feeds
 * the mipmap workers, and once every sprite is generated the whole atlas is uploaded in batches. The vanilla
 * upload loop then finds every sprite already written and skips it.
 */
@Mixin(TextureMap.class)
public abstract class MixinTextureAtlasParallelLoad implements AtlasSessionHolder {

    @Shadow
    @Final
    private TextureAtlasSprite missingImage;

    @Shadow
    private int mipmapLevels;

    @Unique
    private AtlasLoadSession angelica$atlasSession;

    @Override
    public AtlasLoadSession angelica$atlasSession() {
        return this.angelica$atlasSession;
    }

    @Invoker("getResourceLocation")
    abstract ResourceLocation angelica$resourceLocationOf(TextureAtlasSprite sprite);

    @Invoker("generateMipmaps")
    abstract boolean angelica$generateMipmaps(IResourceManager manager, TextureAtlasSprite sprite);

    @Inject(
        method = "loadTextureAtlas",
        at = @At(value = "INVOKE", target = "Ljava/util/Map;entrySet()Ljava/util/Set;", remap = false))
    private void angelica$beginAtlasSession(IResourceManager manager, CallbackInfo ci) {
        final AtlasLoadSession previous = this.angelica$atlasSession;
        if (previous != null) {
            previous.close();
        }
        this.angelica$atlasSession = AtlasLoadSession.begin((TextureMap) (Object) this, manager);
    }

    /** Starts the build report, so its total covers the whole load including the stitching. */
    @Inject(method = "loadTextureAtlas", at = @At("HEAD"))
    private void angelica$beginAtlasReport(IResourceManager manager, CallbackInfo ci) {
        AtlasLoadStats.begin();
    }

    /**
     * Hands the prefetched resource of this sprite to the atlas loop instead of letting it open the pack
     * again on the main thread.
     */
    @WrapOperation(
        method = "generateMipmaps(Lnet/minecraft/client/resources/IResourceManager;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/resources/IResourceManager;getResource(Lnet/minecraft/util/ResourceLocation;)Lnet/minecraft/client/resources/IResource;"))
    private IResource angelica$takePrefetchedResource(IResourceManager manager, ResourceLocation location,
        Operation<IResource> original,
        @Local(argsOnly = true, ordinal = 0) TextureAtlasSprite sprite) {
        final AtlasLoadSession session = this.angelica$atlasSession;
        if (session != null) {
            final ResourceLocation expected = this.angelica$resourceLocationOf(sprite);
            if (location.equals(expected)) {
                final IResource resource = session.takeResource(sprite, location);
                if (resource != null) {
                    return resource;
                }
            }
        }
        return original.call(manager, location);
    }

    /**
     * Moves one sprite's mipmap generation onto a worker; the main thread reads the frames again only after
     * the session has joined every offloaded mipmap, which happens before the atlas is assembled.
     */
    @WrapOperation(
        method = "generateMipmaps(Lnet/minecraft/client/resources/IResourceManager;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;generateMipmaps(I)V"))
    private void angelica$offloadMipmaps(TextureAtlasSprite sprite, int levels, Operation<Void> original) {
        final AtlasLoadSession session = this.angelica$atlasSession;
        if (session != null && session.offloadMipmaps(sprite, levels)) {
            return;
        }
        original.call(sprite, levels);
    }

    /**
     * Generates every stitched sprite before the upload loop starts, so the mipmaps of one sprite overlap
     * with the decodes of the next, and then assembles the atlas in batches.
     */
    @WrapOperation(
        method = "loadTextureAtlas",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/Stitcher;getStichSlots()Ljava/util/List;"))
    private List<TextureAtlasSprite> angelica$assembleAtlas(Stitcher stitcher, Operation<List<TextureAtlasSprite>> original,
        @Local(argsOnly = true) IResourceManager manager) {
        final long stitchStart = System.nanoTime();
        final List<TextureAtlasSprite> sprites = original.call(stitcher);
        AtlasLoadStats.recordStitch(System.nanoTime() - stitchStart);
        AtlasLoadStats.recordSprites(sprites.size());

        final AtlasLoadSession session = this.angelica$atlasSession;
        if (session != null) {
            final long prepareStart = System.nanoTime();
            session.prepare(sprites, this.missingImage, this::angelica$resourceLocationOf,
                sprite -> this.angelica$generateMipmaps(manager, sprite));
            AtlasLoadStats.recordPrepare(System.nanoTime() - prepareStart);

            final long assembleStart = System.nanoTime();
            AtlasAssembler.upload(sprites, this.mipmapLevels, stitcher.getCurrentWidth(), stitcher.getCurrentHeight(),
                session::markUploaded);
            AtlasLoadStats.recordAssemble(System.nanoTime() - assembleStart);
        }
        return sprites;
    }

    /**
     * Skips the per-sprite generation the session already ran, so the upload loop only does its bookkeeping
     * and the uploads the assembler left to it.
     */
    @WrapOperation(
        method = "loadTextureAtlas",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureMap;generateMipmaps(Lnet/minecraft/client/resources/IResourceManager;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;)Z"))
    private boolean angelica$reusePreparedSprite(TextureMap map, IResourceManager manager, TextureAtlasSprite sprite,
        Operation<Boolean> original) {
        final AtlasLoadSession session = this.angelica$atlasSession;
        if (session != null) {
            final Boolean prepared = session.preparedResult(sprite);
            if (prepared != null) {
                return prepared;
            }
        }
        return original.call(map, manager, sprite);
    }

    @Inject(method = "loadTextureAtlas", at = @At("RETURN"))
    private void angelica$closeAtlasSession(CallbackInfo ci) {
        final AtlasLoadSession session = this.angelica$atlasSession;
        if (session != null) {
            this.angelica$atlasSession = null;
            session.close();
        }
        AtlasLoadStats.finish(((TextureMap) (Object) this).getBasePath(), this.mipmapLevels,
            ((TextureMapExtension) this).celeritas$getAtlasGutter());
    }
}
