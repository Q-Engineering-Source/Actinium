package com.dhj.actinium.texture;

import com.gtnewhorizons.angelica.glsm.texture.TextureInfoCache;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.resources.IResourceManager;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TextureDebugLabelsTest {
    @Test
    void registrationBeforeFramebufferAllocationDoesNotBindTheUnallocatedId() {
        DeferredFramebufferTexture texture = new DeferredFramebufferTexture();
        texture.loadTexture(null);

        assertDoesNotThrow(() -> TextureDebugLabels.nameTextureObject(texture,
            id -> TextureInfoCache.INSTANCE.getInfo(id).setTarget(GL11.GL_TEXTURE_2D)));
    }

    @Test
    void labelsTheCurrentFramebufferAttachmentAfterAllocationAndRecreation() {
        DeferredFramebufferTexture texture = new DeferredFramebufferTexture();
        List<Integer> labeledIds = new ArrayList<>();

        TextureDebugLabels.nameTextureObject(texture, labeledIds::add);
        texture.attachmentId = 42;
        TextureDebugLabels.nameTextureObject(texture, labeledIds::add);
        texture.attachmentId = -1;
        TextureDebugLabels.nameTextureObject(texture, labeledIds::add);
        texture.attachmentId = 73;
        TextureDebugLabels.nameTextureObject(texture, labeledIds::add);

        assertEquals(List.of(42, 73), labeledIds);
    }

    /** Matches Betweenlands GeometryBuffer's no-op load and framebuffer-owned texture ID. */
    private static final class DeferredFramebufferTexture implements ITextureObject {
        private int attachmentId = -1;

        @Override
        public void loadTexture(IResourceManager resourceManager) {
            // Registration does not allocate the framebuffer attachment.
        }

        @Override
        public int getGlTextureId() {
            return attachmentId;
        }

        @Override
        public void setBlurMipmap(boolean blur, boolean mipmap) {
            throw new AssertionError("Debug labeling must not change texture filtering");
        }

        @Override
        public void restoreLastBlurMipmap() {
            throw new AssertionError("Debug labeling must not change texture filtering");
        }
    }
}
