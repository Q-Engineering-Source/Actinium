package com.dhj.actinium.mixin.vintage.features.textures;

import com.dhj.actinium.texture.atlas.AtlasSessionHolder;
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
 * one sprite at a time. That order is kept, but the steps are widened: the decode lane is seeded with the
 * stitched sprites in the order the upload loop will consume them, the vanilla per-sprite generation then
 * runs on the prefetched images with its mipmap levels built on workers, and once every sprite is generated
 * the whole atlas is uploaded in batches. The vanilla upload loop then finds every sprite already written and
 * skips it.
 *
 * <p>1.12.2 runs that per-sprite generation from the stitching loop, before the atlas exists and before the
 * stitched sprites are known, so the loop's own generation is deferred to this phase; see
 * {@code angelica$deferSpriteGeneration}.
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

    /** The manager of the load in progress; the generation phase runs from a method that has none of its own. */
    @Unique
    private IResourceManager angelica$atlasManager;

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
        at = @At(value = "INVOKE", target = "Ljava/util/HashMap;entrySet()Ljava/util/Set;", remap = false))
    private void angelica$beginAtlasSession(IResourceManager manager, CallbackInfo ci) {
        final AtlasLoadSession previous = this.angelica$atlasSession;
        if (previous != null) {
            previous.close();
        }
        this.angelica$atlasManager = manager;
        this.angelica$atlasSession = AtlasLoadSession.begin((TextureMap) (Object) this, manager);
    }

    /** Starts the build report, so its total covers the whole load including the stitching. */
    @Inject(method = "loadTextureAtlas", at = @At("HEAD"))
    private void angelica$beginAtlasReport(IResourceManager manager, CallbackInfo ci) {
        AtlasLoadStats.begin();
    }

    /**
     * Hands the prefetched resource of this sprite to the session's generation phase instead of letting it
     * open the pack again on the main thread.
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
     *
     * <p>The list is handed back to the vanilla loop, so the second {@code getStichSlots} call of the method
     * is the one that starts the loop; the first only sizes the progress bar.
     */
    @WrapOperation(
        method = "finishLoading",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/Stitcher;getStichSlots()Ljava/util/List;",
            ordinal = 1))
    private List<TextureAtlasSprite> angelica$assembleAtlas(Stitcher stitcher, Operation<List<TextureAtlasSprite>> original) {
        final long stitchStart = System.nanoTime();
        final List<TextureAtlasSprite> stitched = original.call(stitcher);
        AtlasLoadStats.recordStitch(System.nanoTime() - stitchStart);
        AtlasLoadStats.recordSprites(stitched.size());

        final AtlasLoadSession session = this.angelica$atlasSession;
        if (session == null) {
            return stitched;
        }

        final long prepareStart = System.nanoTime();
        session.prepare(stitched, this.missingImage, this::angelica$resourceLocationOf,
            sprite -> this.angelica$generateMipmaps(this.angelica$atlasManager, sprite));
        AtlasLoadStats.recordPrepare(System.nanoTime() - prepareStart);

        // The generation these sprites were stitched for is deferred to this phase, so the stitcher had no
        // result to refuse them with: a sprite the vanilla generation failed for is left out of the upload
        // loop here, which is where vanilla leaves it, and the leftover pass that copies the missing sprite
        // over every remaining registered sprite then repairs it.
        final List<TextureAtlasSprite> generated = stitched.stream()
            .filter(sprite -> sprite == this.missingImage || Boolean.TRUE.equals(session.preparedResult(sprite)))
            .toList();

        final long assembleStart = System.nanoTime();
        AtlasAssembler.upload(generated, this.mipmapLevels, stitcher.getCurrentWidth(), stitcher.getCurrentHeight(),
            session::markUploaded);
        AtlasLoadStats.recordAssemble(System.nanoTime() - assembleStart);
        return generated;
    }

    /**
     * Defers the per-sprite generation of this loop to {@code angelica$assembleAtlas}, which runs it through
     * the session so the decodes of the stitched sprites can come off the decode lane and their mipmap levels
     * can be built on workers.
     *
     * <p>1.12.2 generates a sprite here, in the stitching loop that runs before the atlas is stitched and
     * before the session knows which sprites to prepare, so generating here as well would build every
     * sprite's mip chain twice. The stitcher only needs the size the sprite's registration has already read,
     * so reporting success is enough for it to keep the sprite.
     */
    @WrapOperation(
        method = "loadTexture(Lnet/minecraft/client/renderer/texture/Stitcher;Lnet/minecraft/client/resources/IResourceManager;Lnet/minecraft/util/ResourceLocation;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;Lnet/minecraftforge/fml/common/ProgressManager$ProgressBar;II)I",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureMap;generateMipmaps(Lnet/minecraft/client/resources/IResourceManager;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;)Z"))
    private boolean angelica$deferSpriteGeneration(TextureMap map, IResourceManager manager, TextureAtlasSprite sprite,
        Operation<Boolean> original) {
        if (this.angelica$atlasSession != null) {
            return true;
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
        this.angelica$atlasManager = null;
        AtlasLoadStats.finish(((TextureMap) (Object) this).getBasePath(), this.mipmapLevels,
            ((TextureMapExtension) this).celeritas$getAtlasGutter());
    }
}
