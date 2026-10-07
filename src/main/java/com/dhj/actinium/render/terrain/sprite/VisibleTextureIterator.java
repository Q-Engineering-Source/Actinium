package com.dhj.actinium.render.terrain.sprite;

import com.dhj.actinium.texture.SpriteExtension;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.util.Iterator;
import java.util.NoSuchElementException;

/** Reusable filter iterator for the atlas animation pass. */
public final class VisibleTextureIterator implements Iterator<TextureAtlasSprite> {
    private Iterator<TextureAtlasSprite> source;
    private TextureAtlasSprite next;
    private boolean ready;

    public VisibleTextureIterator reset(final Iterator<TextureAtlasSprite> source) {
        this.source = source;
        this.next = null;
        this.ready = false;
        return this;
    }

    @Override
    public boolean hasNext() {
        if (this.ready) {
            return true;
        }
        while (this.source.hasNext()) {
            final TextureAtlasSprite candidate = this.source.next();
            if (((SpriteExtension) candidate).celeritas$shouldUpdate()) {
                this.next = candidate;
                this.ready = true;
                return true;
            }
        }
        return false;
    }

    @Override
    public TextureAtlasSprite next() {
        if (!this.hasNext()) {
            throw new NoSuchElementException();
        }
        final TextureAtlasSprite result = this.next;
        this.next = null;
        this.ready = false;
        return result;
    }
}
