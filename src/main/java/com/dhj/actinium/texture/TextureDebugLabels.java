package com.dhj.actinium.texture;

import com.gtnewhorizons.angelica.glsm.GLDebug;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.function.IntConsumer;

/** Applies resource names to texture objects without taking ownership of their allocation. */
public final class TextureDebugLabels {
    private TextureDebugLabels() {
    }

    /** Labels textures registered with TextureManager when GL calls can safely execute. */
    public static void nameTextureObject(ResourceLocation resource, ITextureObject texture) {
        // CQR registers textures on context-less threads; querying their ID can force a GL upload.
        if (texture == null || texture == TextureUtil.MISSING_TEXTURE || GLStateManager.isRecordingDisplayList()
                || !GLStateManager.hasContext()) {
            return;
        }

        int currentTexture = GLStateManager.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        nameTextureObject(texture, textureId -> {
            GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, textureId);
            try {
                GLDebug.nameObject(GL11.GL_TEXTURE, textureId, resource.toString());
            } finally {
                GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, currentTexture);
            }
        });
    }

    static void nameTextureObject(ITextureObject texture, IntConsumer labelTexture) {
        int textureId = texture.getGlTextureId();
        // Betweenlands registers framebuffer-backed textures before allocating their attachments.
        if (textureId < 0) {
            return;
        }
        labelTexture.accept(textureId);
    }
}
