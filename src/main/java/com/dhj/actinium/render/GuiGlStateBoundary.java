package com.dhj.actinium.render;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;

/**
 * Defines the fixed-function state owned by GUI render boundaries and tracks whether entity
 * rendering is running inside a GUI surface.
 */
public final class GuiGlStateBoundary {
    private static int entityGuiSurfaceDepth;

    private GuiGlStateBoundary() {
    }

    /**
     * Marks the start of entity rendering performed inside a GUI surface, such as a tooltip
     * armor preview. While the surface is active, Iris hooks that apply world-render state
     * (armor item ids, enchant glint conditions) must leave the pass untouched. Every begin
     * must be balanced by {@link EntitySurfaceState#restore()} on the returned token, even
     * when the nested render throws; Minecraft renders on a single thread, so a plain counter
     * is sufficient.
     */
    public static EntitySurfaceState beginEntitySurface() {
        entityGuiSurfaceDepth++;
        return new EntitySurfaceState();
    }

    /**
     * Returns whether the current pass is entity rendering nested inside a GUI surface.
     */
    public static boolean isEntityGuiSurface() {
        return entityGuiSurfaceDepth > 0;
    }

    /**
     * Restores the neutral state expected when Minecraft starts rendering the HUD.
     */
    public static void restoreHudBaseline() {
        GLStateManager.glUseProgram(0);
        GLStateManager.disableLighting();
        GLStateManager.disableFog();
        GLStateManager.enableAlphaTest();
        GLStateManager.glAlphaFunc(GL11.GL_GREATER, 0.1F);
        GLStateManager.glBlendFuncSeparate(
            GL11.GL_SRC_ALPHA,
            GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE,
            GL11.GL_ZERO
        );
        GLStateManager.disableBlend();

        disableTextureUnit(GL13.GL_TEXTURE3);
        disableTextureUnit(GL13.GL_TEXTURE2);
        disableTextureUnit(GL13.GL_TEXTURE1);
        GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);
        GLStateManager.enableTexture();
        GLStateManager.glTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, GL11.GL_MODULATE);
        GLStateManager.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Establishes straight-alpha blending for a translucent GUI layer.
     */
    public static void beginTranslucentLayer() {
        restoreHudBaseline();
        GLStateManager.enableBlend();
    }

    /**
     * Saves the mutable depth, mask, equation, cull, and shading state used by a Revo HUD surface.
     * Scissor ownership remains with the compositor and is deliberately untouched.
     */
    public static CompositorSurfaceState beginCompositorSurface() {
        CompositorSurfaceState previous = CompositorSurfaceState.capture();
        beginTranslucentLayer();
        GLStateManager.disableDepthTest();
        GLStateManager.glDepthMask(false);
        GLStateManager.glDepthFunc(GL11.GL_LEQUAL);
        GLStateManager.glColorMask(true, true, true, true);
        GLStateManager.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
        GLStateManager.disableCull();
        GLStateManager.glShadeModel(GL11.GL_FLAT);
        return previous;
    }

    /**
     * Disables texturing on a secondary unit before returning ownership to unit zero.
     */
    private static void disableTextureUnit(int textureUnit) {
        GLStateManager.glActiveTexture(textureUnit);
        GLStateManager.disableTexture();
    }

    /**
     * Restores only the explicit compositor surface state captured by {@link #beginCompositorSurface()}.
     */
    public static final class CompositorSurfaceState {
        private final boolean depthTest;
        private final boolean depthMask;
        private final int depthFunc;
        private final boolean colorRed;
        private final boolean colorGreen;
        private final boolean colorBlue;
        private final boolean colorAlpha;
        private final int blendEquationRgb;
        private final int blendEquationAlpha;
        private final boolean cull;
        private final int shadeModel;

        private CompositorSurfaceState(
            boolean depthTest,
            boolean depthMask,
            int depthFunc,
            boolean colorRed,
            boolean colorGreen,
            boolean colorBlue,
            boolean colorAlpha,
            int blendEquationRgb,
            int blendEquationAlpha,
            boolean cull,
            int shadeModel
        ) {
            this.depthTest = depthTest;
            this.depthMask = depthMask;
            this.depthFunc = depthFunc;
            this.colorRed = colorRed;
            this.colorGreen = colorGreen;
            this.colorBlue = colorBlue;
            this.colorAlpha = colorAlpha;
            this.blendEquationRgb = blendEquationRgb;
            this.blendEquationAlpha = blendEquationAlpha;
            this.cull = cull;
            this.shadeModel = shadeModel;
        }

        private static CompositorSurfaceState capture() {
            ByteBuffer colorMask = BufferUtils.createByteBuffer(4);
            GLStateManager.glGetBoolean(GL11.GL_COLOR_WRITEMASK, colorMask);
            return new CompositorSurfaceState(
                GLStateManager.glIsEnabled(GL11.GL_DEPTH_TEST),
                GLStateManager.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
                GLStateManager.glGetInteger(GL11.GL_DEPTH_FUNC),
                colorMask.get(0) != 0,
                colorMask.get(1) != 0,
                colorMask.get(2) != 0,
                colorMask.get(3) != 0,
                GLStateManager.glGetInteger(GL20.GL_BLEND_EQUATION_RGB),
                GLStateManager.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA),
                GLStateManager.glIsEnabled(GL11.GL_CULL_FACE),
                GLStateManager.glGetInteger(GL11.GL_SHADE_MODEL)
            );
        }

        /**
         * Restores the captured surface-owned state without changing the compositor's scissor state.
         */
        public void restore() {
            restoreCapability(GL11.GL_DEPTH_TEST, this.depthTest);
            GLStateManager.glDepthMask(this.depthMask);
            GLStateManager.glDepthFunc(this.depthFunc);
            GLStateManager.glColorMask(this.colorRed, this.colorGreen, this.colorBlue, this.colorAlpha);
            GLStateManager.glBlendEquationSeparate(this.blendEquationRgb, this.blendEquationAlpha);
            restoreCapability(GL11.GL_CULL_FACE, this.cull);
            GLStateManager.glShadeModel(this.shadeModel);
        }

        private static void restoreCapability(int capability, boolean enabled) {
            if (enabled) {
                GLStateManager.glEnable(capability);
            } else {
                GLStateManager.glDisable(capability);
            }
        }
    }

    /**
     * Balance token for {@link #beginEntitySurface()}; ends the surface exactly once.
     */
    public static final class EntitySurfaceState {
        private boolean spent;

        private EntitySurfaceState() {
        }

        /**
         * Marks the end of the GUI entity surface this token was created for.
         *
         * @throws IllegalStateException when this token has already been restored
         */
        public void restore() {
            if (spent) {
                throw new IllegalStateException("entity GUI surface restored twice");
            }
            spent = true;
            entityGuiSurfaceDepth--;
        }
    }
}
