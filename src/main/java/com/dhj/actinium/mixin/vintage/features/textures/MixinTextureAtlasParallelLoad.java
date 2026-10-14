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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drives the parallel atlas load from {@code loadTextureAtlas}: frames load in vanilla order in the
 * registration loop, while each sprite's mipmap levels go where {@link AtlasLoadSession} decides (worker,
 * post-loop pass, or inline).
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

    @Unique
    private IResourceManager angelica$atlasManager;

    @Override
    public AtlasLoadSession angelica$atlasSession() {
        return this.angelica$atlasSession;
    }

    @Invoker("getResourceLocation")
    abstract ResourceLocation angelica$resourceLocationOf(TextureAtlasSprite sprite);

    /** Starts the build report, so its total covers the whole load including the stitching. */
    @Inject(method = "loadTextureAtlas", at = @At("HEAD"))
    private void angelica$beginAtlasReport(IResourceManager manager, CallbackInfo ci) {
        this.angelica$atlasManager = manager;
        AtlasLoadStats.begin();
    }

    /** Opens the session over the registry copy the registration loop is about to walk. */
    @WrapOperation(
        method = "loadTextureAtlas",
        at = @At(value = "INVOKE", target = "Ljava/util/HashMap;entrySet()Ljava/util/Set;", remap = false))
    private Set<Map.Entry<String, TextureAtlasSprite>> angelica$openAtlasSession(
        HashMap<String, TextureAtlasSprite> registered, Operation<Set<Map.Entry<String, TextureAtlasSprite>>> original) {
        final AtlasLoadSession previous = this.angelica$atlasSession;
        if (previous != null) {
            previous.close();
        }

        final AtlasLoadSession session = AtlasLoadSession.begin((TextureMap) (Object) this, this.angelica$atlasManager,
            this.missingImage, this::angelica$resourceLocationOf);
        this.angelica$atlasSession = session;
        if (session != null) {
            session.beginRegistration(registered);
        }
        return original.call(registered);
    }

    /** Hands this sprite's prefetched resource to the main thread instead of letting it re-open the pack. */
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
     * Builds one sprite's levels where the session decides, or inline where vanilla does. Called once the
     * sprite's frames are in place.
     */
    @WrapOperation(
        method = "generateMipmaps(Lnet/minecraft/client/resources/IResourceManager;Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;generateMipmaps(I)V"))
    private void angelica$takeMipmapLevels(TextureAtlasSprite sprite, int levels, Operation<Void> original) {
        final AtlasLoadSession session = this.angelica$atlasSession;
        if (session != null && session.takeGeneration(sprite, levels)) {
            return;
        }
        original.call(sprite, levels);
    }

    /**
     * Builds the deferred level chains, joins the workers, then uploads in batches. Hooked at the second
     * {@code getStichSlots} call; the first only sizes the progress bar.
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
        session.prepare(stitched, this.mipmapLevels);
        AtlasLoadStats.recordPrepare(System.nanoTime() - prepareStart);

        final long assembleStart = System.nanoTime();
        AtlasAssembler.upload(stitched, this.mipmapLevels, stitcher.getCurrentWidth(), stitcher.getCurrentHeight(),
            session::markUploaded);
        AtlasLoadStats.recordAssemble(System.nanoTime() - assembleStart);
        return stitched;
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
