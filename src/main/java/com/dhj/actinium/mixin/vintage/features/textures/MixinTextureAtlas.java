package com.dhj.actinium.mixin.vintage.features.textures;

import com.dhj.actinium.texture.atlas.AtlasSessionHolder;
import com.dhj.actinium.render.terrain.sprite.VisibleTextureIterator;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.dhj.actinium.texture.SpriteExtension;
import com.dhj.actinium.texture.TextureMapExtension;
import com.dhj.actinium.texture.atlas.AtlasLoadSession;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions;
import dhj.embeddedt.embeddium.impl.texture.SpritePadding;
import dhj.embeddedt.embeddium.impl.util.collections.quadtree.QuadTree;
import dhj.embeddedt.embeddium.impl.util.collections.quadtree.Rect2i;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;
import java.util.Map;

@Mixin(TextureMap.class)
public class MixinTextureAtlas implements TextureMapExtension {
    @Unique
    private final VisibleTextureIterator celeritas$visibleTextureIterator = new VisibleTextureIterator();

    @Shadow
    @Final
    private Map<String, TextureAtlasSprite> mapUploadedSprites;

    @Shadow
    @Final
    private Map<String, TextureAtlasSprite> mapRegisteredSprites;

    @Shadow
    private int mipmapLevels;

    @Shadow
    @Final
    private TextureAtlasSprite missingImage;

    /** Gutter every sprite of the atlas currently being loaded is padded with; zero when padding is off. */
    @Unique
    private int celeritas$atlasGutter;

    private QuadTree<TextureAtlasSprite> celeritas$quadTree;

    private int celeritas$width, celeritas$height;

    /**
     * The slot width has to grow before the stitcher lays the atlas out, and only once the mip level count is
     * final: the mipmap scan can still drop levels after the sprites are registered.
     */
    @Inject(
        method = "loadTextureAtlas",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/Stitcher;doStitch()V"))
    private void angelica$assignGutters(CallbackInfo ci) {
        final boolean blocksAtlas = (Object) this == Minecraft.getMinecraft().getTextureMapBlocks();
        final int gutter = SpritePadding.gutterFor(this.mipmapLevels, blocksAtlas);
        this.celeritas$atlasGutter = gutter;

        for (TextureAtlasSprite sprite : this.mapRegisteredSprites.values()) {
            ((SpriteExtension) sprite).angelica$setGutterWidth(gutter);
        }
        ((SpriteExtension) this.missingImage).angelica$setGutterWidth(gutter);
    }

    /** The sprite's content is inset by its gutter, so the upload has to start one gutter further out. */
    @Redirect(
        method = "loadTextureAtlas",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/TextureUtil;uploadTextureMipmap([[IIIIIZZ)V"))
    private void angelica$uploadPaddedSprite(int[][] frameData, int width, int height, int originX, int originY,
        boolean blur, boolean clamp) {
        final AtlasLoadSession session = ((AtlasSessionHolder) this).angelica$atlasSession();
        if (session != null && session.takeUploaded(frameData)) {
            return;
        }
        SpritePadding.uploadPadded(frameData, width, height, originX, originY, this.celeritas$atlasGutter, blur, clamp);
    }

    @Inject(method = "loadTextureAtlas", at = @At("RETURN"))
    private void generateQuadTree(CallbackInfo ci, @Local(ordinal = 0) Stitcher stitcher) {
        if ((Object) this == Minecraft.getMinecraft().getTextureMapBlocks()) {
            // The sprite gutter, the pass defines and the terrain sampler's mip filter are all sized from the
            // mip level count this atlas really ended up with, and loadTextureAtlas has just lowered its own
            // count wherever the stitched sprites could not support the configured one.
            SodiumGameOptions.recordAtlasMipmapLevels(this.mipmapLevels);
        }

        this.celeritas$width = stitcher.getCurrentWidth();
        this.celeritas$height = stitcher.getCurrentHeight();
        Rect2i treeRect = new Rect2i(0, 0, celeritas$width, celeritas$height);
        int minSize = this.mapUploadedSprites.values().stream().mapToInt(sprite -> Math.max(sprite.getIconWidth(), sprite.getIconHeight())).min().getAsInt();
        this.celeritas$quadTree = new QuadTree<>(treeRect, minSize, this.mapUploadedSprites.values(), sprite -> new Rect2i(sprite.getOriginX(), sprite.getOriginY(), sprite.getIconWidth(), sprite.getIconHeight()));
    }

    @Override
    public QuadTree<TextureAtlasSprite> celeritas$getQuadTree() {
        return celeritas$quadTree;
    }

    @Override
    public TextureAtlasSprite celeritas$findFromUV(float u, float v) {
        int x = Math.round(u * this.celeritas$width), y = Math.round(v * this.celeritas$height);

        return this.celeritas$quadTree.find(x, y);
    }

    @Override
    public int celeritas$getAtlasWidth() {
        return this.celeritas$width;
    }

    @Override
    public int celeritas$getAtlasHeight() {
        return this.celeritas$height;
    }

    @Override
    public int celeritas$getAtlasGutter() {
        return this.celeritas$atlasGutter;
    }

    @ModifyExpressionValue(method = "updateAnimations", at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;"))
    private Iterator<TextureAtlasSprite> getFilteredIterator(Iterator<TextureAtlasSprite> iterator) {
        if (ActiniumRuntime.options().performance.animateOnlyVisibleTextures) {
            return this.celeritas$visibleTextureIterator.reset(iterator);
        } else {
            return iterator;
        }
    }
}
