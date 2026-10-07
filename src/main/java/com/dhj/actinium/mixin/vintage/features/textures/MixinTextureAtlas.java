package com.dhj.actinium.mixin.vintage.features.textures;

import com.dhj.actinium.render.terrain.sprite.VisibleTextureIterator;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import dhj.embeddedt.embeddium.impl.util.collections.quadtree.QuadTree;
import dhj.embeddedt.embeddium.impl.util.collections.quadtree.Rect2i;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.dhj.actinium.texture.TextureMapExtension;

import java.util.Iterator;
import java.util.Map;

@Mixin(TextureMap.class)
public class MixinTextureAtlas implements TextureMapExtension {
    @Unique
    private final VisibleTextureIterator celeritas$visibleTextureIterator = new VisibleTextureIterator();

    @Shadow
    @Final
    private Map<String, TextureAtlasSprite> mapUploadedSprites;

    private QuadTree<TextureAtlasSprite> celeritas$quadTree;

    private int celeritas$width, celeritas$height;

    @Inject(method = "loadTextureAtlas", at = @At("RETURN"))
    private void generateQuadTree(CallbackInfo ci, @Local(ordinal = 0) Stitcher stitcher) {
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

    @ModifyExpressionValue(method = "updateAnimations", at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;"))
    private Iterator<TextureAtlasSprite> getFilteredIterator(Iterator<TextureAtlasSprite> iterator) {
        if (ActiniumRuntime.options().performance.animateOnlyVisibleTextures) {
            return this.celeritas$visibleTextureIterator.reset(iterator);
        } else {
            return iterator;
        }
    }
}
