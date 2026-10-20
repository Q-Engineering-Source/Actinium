package com.gtnewhorizons.angelica.client.rendering;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.coderbot.iris.gl.state.StateUpdateNotifiers;
import net.coderbot.iris.gl.state.MultiListenerNotifier;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.jetbrains.annotations.Nullable;

public class TextureTracker {
    public static final TextureTracker INSTANCE = new TextureTracker();

    private static final MultiListenerNotifier bindTextureListener = new MultiListenerNotifier();

    static {
        StateUpdateNotifiers.bindTextureNotifier = bindTextureListener;
    }

    private final Int2ObjectMap<AbstractTexture> textures = new Int2ObjectOpenHashMap<>();

    private TextureTracker() {
    }

    public void trackTexture(int id, AbstractTexture texture) {
        this.textures.put(id, texture);
    }

    @Nullable
    public AbstractTexture getTexture(int id) {
        return this.textures.get(id);
    }

    public void onBindTexture() {
        bindTextureListener.run();
    }

    public void onDeleteTexture(int id) {
        this.textures.remove(id);
    }
}
