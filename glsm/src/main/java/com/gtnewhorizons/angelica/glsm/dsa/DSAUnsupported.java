package com.gtnewhorizons.angelica.glsm.dsa;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.stacks.TextureBindingStack;
import org.lwjgl.opengl.GL11;

import static com.gtnewhorizons.angelica.glsm.backend.BackendManager.RENDER_BACKEND;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public class DSAUnsupported implements DSAAccess {

    @Override
    public void generateMipmaps(int texture, int target) {
        final int bindingTarget = bindingTarget(target);
        final int previous = previousBinding(bindingTarget);
        RENDER_BACKEND.bindTexture(bindingTarget, texture);
        try {
            RENDER_BACKEND.generateMipmap(target);
        } finally {
            RENDER_BACKEND.bindTexture(bindingTarget, previous);
        }
    }

    @Override
    public void textureImage2D(int texture, int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels) {
        final int bindingTarget = bindingTarget(target);
        final int previous = previousBinding(bindingTarget);
        RENDER_BACKEND.bindTexture(bindingTarget, texture);
        try {
            RENDER_BACKEND.texImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        } finally {
            RENDER_BACKEND.bindTexture(bindingTarget, previous);
        }
    }

    @Override
    public void textureImage2D(int texture, int target, int level, int internalformat, int width, int height, int border, int format, int type, IntBuffer pixels) {
        final int bindingTarget = bindingTarget(target);
        final int previous = previousBinding(bindingTarget);
        RENDER_BACKEND.bindTexture(bindingTarget, texture);
        try {
            RENDER_BACKEND.texImage2D(target, level, internalformat, width, height, border, format, type, pixels);
        } finally {
            RENDER_BACKEND.bindTexture(bindingTarget, previous);
        }
    }

    @Override
    public void textureSubImage2D(int texture, int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, ByteBuffer pixels) {
        final int bindingTarget = bindingTarget(target);
        final int previous = previousBinding(bindingTarget);
        RENDER_BACKEND.bindTexture(bindingTarget, texture);
        try {
            RENDER_BACKEND.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
        } finally {
            RENDER_BACKEND.bindTexture(bindingTarget, previous);
        }
    }

    @Override
    public void textureSubImage2D(int texture, int target, int level, int xoffset, int yoffset, int width, int height, int format, int type, IntBuffer pixels) {
        final int bindingTarget = bindingTarget(target);
        final int previous = previousBinding(bindingTarget);
        RENDER_BACKEND.bindTexture(bindingTarget, texture);
        try {
            RENDER_BACKEND.texSubImage2D(target, level, xoffset, yoffset, width, height, format, type, pixels);
        } finally {
            RENDER_BACKEND.bindTexture(bindingTarget, previous);
        }
    }

    @Override
    public void texParameteri(int texture, int target, int pname, int param) {
        final int previous = bindForEdit(target, texture);
        try {
            GLStateManager.glTexParameteri(target, pname, param);
        } finally {
            restoreBinding(target, previous);
        }
    }

    @Override
    public void texParameterf(int texture, int target, int pname, float param) {
        final int previous = bindForEdit(target, texture);
        try {
            GLStateManager.glTexParameterf(target, pname, param);
        } finally {
            restoreBinding(target, previous);
        }
    }

    @Override
    public void texParameteriv(int texture, int target, int pname, IntBuffer params) {
        final int previous = bindForEdit(target, texture);
        try {
            GLStateManager.glTexParameter(target, pname, params);
        } finally {
            restoreBinding(target, previous);
        }
    }

    @Override
    public void readBuffer(int framebuffer, int buffer) {
        GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        RENDER_BACKEND.readBuffer(buffer);
    }

    @Override
    public void drawBuffers(int framebuffer, IntBuffer buffers) {
        GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        RENDER_BACKEND.drawBuffers(buffers);
    }

    @Override
    public int getTexParameteri(int texture, int target, int pname) {
        final int previous = bindForEdit(target, texture);
        try {
            return GLStateManager.glGetTexParameteri(target, pname);
        } finally {
            restoreBinding(target, previous);
        }
    }

    @Override
    public int getTexLevelParameteri(int texture, int level, int pname) {
        final int previous = previousBinding(GL11.GL_TEXTURE_2D);
        RENDER_BACKEND.bindTexture(GL11.GL_TEXTURE_2D, texture);
        try {
            return RENDER_BACKEND.getTexLevelParameteri(GL11.GL_TEXTURE_2D, level, pname);
        } finally {
            RENDER_BACKEND.bindTexture(GL11.GL_TEXTURE_2D, previous);
        }
    }

    @Override
    public void copyTexSubImage2D(int destTexture, int target, int i, int i1, int i2, int i3, int i4, int width, int height) {
        final int bindingTarget = bindingTarget(target);
        final int previous = previousBinding(bindingTarget);
        RENDER_BACKEND.bindTexture(bindingTarget, destTexture);
        try {
            RENDER_BACKEND.copyTexSubImage2D(target, i, i1, i2, i3, i4, width, height);
        } finally {
            RENDER_BACKEND.bindTexture(bindingTarget, previous);
        }
    }

    @Override
    public void bindTextureToUnit(int unit, int texture) {
        GLStateManager.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, texture);
    }

    @Override
    public void bindTextureToUnit(int target, int unit, int texture) {
        GLStateManager.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GLStateManager.glBindTexture(target, texture);
    }

    @Override
    public int bufferStorage(int target, FloatBuffer data, int usage) {
        final int buffer = RENDER_BACKEND.genBuffers();
        GLStateManager.glBindBuffer(target, buffer);
        RENDER_BACKEND.bufferData(target, data, usage);
        GLStateManager.glBindBuffer(target, 0);

        return buffer;
    }

    @Override
    public void blitFramebuffer(int source, int dest, int offsetX, int offsetY, int width, int height, int offsetX2, int offsetY2, int width2, int height2,
        int bufferChoice, int filter) {
        GLStateManager.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source);
        GLStateManager.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, dest);
        RENDER_BACKEND.blitFramebuffer(offsetX, offsetY, width, height, offsetX2, offsetY2, width2, height2, bufferChoice, filter);
    }

    @Override
    public void framebufferTexture2D(int fb, int fbtarget, int attachment, int target, int texture, int levels) {
        GLStateManager.glBindFramebuffer(fbtarget, fb);
        RENDER_BACKEND.framebufferTexture2D(fbtarget, attachment, target, texture, levels);
    }

    @Override
    public int createFramebuffer() {
        final int framebuffer = GLStateManager.glGenFramebuffers();
        GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        return framebuffer;
    }

    @Override
    public int createTexture(int target) {
        final int texture = RENDER_BACKEND.genTextures();
        final int previous = previousBinding(target);
        // Register the target as well as creating the object; deferred deletion needs it.
        GLStateManager.glBindTexture(target, texture);
        restoreBinding(target, previous);
        return texture;
    }

    @Override
    public void textureStorage1D(int texture, int target, int levels, int internalFormat, int width) {
        final int bindingTarget = bindingTarget(target);
        final int previous = previousBinding(bindingTarget);
        RENDER_BACKEND.bindTexture(bindingTarget, texture);
        try {
            RENDER_BACKEND.texStorage1D(target, levels, internalFormat, width);
        } finally {
            RENDER_BACKEND.bindTexture(bindingTarget, previous);
        }
    }

    @Override
    public void textureStorage2D(int texture, int target, int levels, int internalFormat, int width, int height) {
        final int bindingTarget = bindingTarget(target);
        final int previous = previousBinding(bindingTarget);
        RENDER_BACKEND.bindTexture(bindingTarget, texture);
        try {
            RENDER_BACKEND.texStorage2D(target, levels, internalFormat, width, height);
        } finally {
            RENDER_BACKEND.bindTexture(bindingTarget, previous);
        }
    }

    @Override
    public void textureStorage3D(int texture, int target, int levels, int internalFormat, int width, int height, int depth) {
        final int bindingTarget = bindingTarget(target);
        final int previous = previousBinding(bindingTarget);
        RENDER_BACKEND.bindTexture(bindingTarget, texture);
        try {
            RENDER_BACKEND.texStorage3D(target, levels, internalFormat, width, height, depth);
        } finally {
            RENDER_BACKEND.bindTexture(bindingTarget, previous);
        }
    }

    /** Cube-face image operations edit the cube-map binding rather than a face binding. */
    static int bindingTarget(int target) {
        return switch (target) {
            case GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_X, GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_X,
                GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_Y, GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_Y,
                GL13.GL_TEXTURE_CUBE_MAP_POSITIVE_Z, GL13.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z -> GL13.GL_TEXTURE_CUBE_MAP;
            default -> target;
        };
    }

    /** Resolves the binding query for every texture target supported by the fallback. */
    static int bindingQuery(int target) {
        return switch (bindingTarget(target)) {
            case GL11.GL_TEXTURE_1D -> GL11.GL_TEXTURE_BINDING_1D;
            case GL11.GL_TEXTURE_2D -> GL11.GL_TEXTURE_BINDING_2D;
            case GL12.GL_TEXTURE_3D -> GL12.GL_TEXTURE_BINDING_3D;
            case GL13.GL_TEXTURE_CUBE_MAP -> GL13.GL_TEXTURE_BINDING_CUBE_MAP;
            case GL30.GL_TEXTURE_1D_ARRAY -> GL30.GL_TEXTURE_BINDING_1D_ARRAY;
            case GL30.GL_TEXTURE_2D_ARRAY -> GL30.GL_TEXTURE_BINDING_2D_ARRAY;
            case GL31.GL_TEXTURE_RECTANGLE -> GL31.GL_TEXTURE_BINDING_RECTANGLE;
            case GL31.GL_TEXTURE_BUFFER -> GL31.GL_TEXTURE_BINDING_BUFFER;
            case GL32.GL_TEXTURE_2D_MULTISAMPLE -> GL32.GL_TEXTURE_BINDING_2D_MULTISAMPLE;
            case GL32.GL_TEXTURE_2D_MULTISAMPLE_ARRAY -> GL32.GL_TEXTURE_BINDING_2D_MULTISAMPLE_ARRAY;
            case GL40.GL_TEXTURE_CUBE_MAP_ARRAY -> GL40.GL_TEXTURE_BINDING_CUBE_MAP_ARRAY;
            default -> throw new IllegalArgumentException("Unsupported texture target: " + target);
        };
    }

    private static int previousBinding(int target) {
        if (target == GL11.GL_TEXTURE_2D && GLStateManager.isCachingEnabled()) {
            final TextureBindingStack slot = GLStateManager.getTextures().getTextureUnitBindings(GLStateManager.getActiveTextureUnit());
            if (slot.getTarget() == 0 || slot.getTarget() == GL11.GL_TEXTURE_2D) return slot.getBinding();
        }
        return RENDER_BACKEND.getInteger(bindingQuery(target));
    }

    private static int bindForEdit(int target, int texture) {
        final int previous = previousBinding(target);
        if (target == GL11.GL_TEXTURE_2D) {
            if (GLStateManager.isCachingEnabled()) {
                final TextureBindingStack slot = GLStateManager.getTextures().getTextureUnitBindings(GLStateManager.getActiveTextureUnit());
                // A slot tagged for another target cannot satisfy a 2D cache hit.
                if (slot.getTarget() != 0 && slot.getTarget() != GL11.GL_TEXTURE_2D) slot.setBinding(previous, GL11.GL_TEXTURE_2D);
            }
            GLStateManager.glBindTexture(target, texture);
        } else {
            RENDER_BACKEND.bindTexture(target, texture);
        }
        return previous;
    }

    private static void restoreBinding(int target, int previous) {
        if (target == GL11.GL_TEXTURE_2D) GLStateManager.glBindTexture(target, previous);
        else RENDER_BACKEND.bindTexture(target, previous);
    }
}
