package net.coderbot.iris.gl.framebuffer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;

public final class MinecraftFramebufferHelper {
    private MinecraftFramebufferHelper() {
    }

    public static void bindMainFramebuffer(boolean setViewport) {
        final Framebuffer main = Minecraft.getMinecraft().getFramebuffer();
        main.bindFramebuffer(setViewport);

        restoreMinecraftFramebufferBuffers();
    }

    public static void restoreMainFramebuffer(boolean setViewport) {
        bindMainFramebuffer(setViewport);
        GLSMPrograms.glUseProgram(0);
        GLSMVertexInput.glBindVertexArray(0);
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);
    }

    public static void restoreMinecraftFramebufferBuffers() {
        // Iris uses custom FBOs with explicit COLOR_ATTACHMENT draw/read buffers.
        // Minecraft's own framebuffer is also an FBO on 1.12, so restore its single color attachment
        // whenever Minecraft binds it directly.
        GLSMFramebuffer.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GLSMFramebuffer.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
    }

    public static void restoreDefaultFramebufferBuffers() {
        GLSMFramebuffer.glDrawBuffer(GL11.GL_BACK);
        GLSMFramebuffer.glReadBuffer(GL11.GL_BACK);
    }

    public static void bindDefaultFramebuffer() {
        GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        restoreDefaultFramebufferBuffers();
    }
}
