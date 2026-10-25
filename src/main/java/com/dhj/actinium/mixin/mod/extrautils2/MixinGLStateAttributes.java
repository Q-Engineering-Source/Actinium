package com.dhj.actinium.mixin.mod.extrautils2;

import com.dhj.actinium.compat.extrautils2.ExtraUtils2GLStateCompat;
import com.rwtema.extrautils2.utils.client.GLStateAttributes;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;

/**
 * Fixes Extra Utilities 2 GUI rendering (issue #48: opening a Slightly-Larger Chest renders
 * the GUI fully transparent with white items).
 *
 * <p>{@code GLStateAttributes.loadStates()} snapshots vanilla {@code GlStateManager} state
 * fields, which never update under the GLSM redirect; {@code restore()} then unbinds the
 * GUI texture. The constructor injection re-reads every snapshotted state through
 * {@link ExtraUtils2GLStateCompat} (the GLSM state cache where available, the live GL
 * state otherwise). Texture-generation states stay at their constructed values: they are
 * never enabled during GUI rendering.
 */
@Mixin(value = GLStateAttributes.class, remap = false)
public abstract class MixinGLStateAttributes {
    @Shadow
    private int alphaState_alphaFunc;
    @Shadow
    private float alphaState_alphaRef;
    @Shadow
    private boolean textureState_enabled;
    @Shadow
    private int textureState_name;
    @Shadow
    private int colorMaterialState_face;
    @Shadow
    private int colorMaterialState_mode;
    @Shadow
    private int blendState_srcFactor;
    @Shadow
    private int blendState_dstFactor;
    @Shadow
    private int blendState_srcFactorAlpha;
    @Shadow
    private int blendState_dstFactorAlpha;
    @Shadow
    private boolean depthState_maskEnabled;
    @Shadow
    private int depthState_depthFunc;
    @Shadow
    private int fogState_mode;
    @Shadow
    private float fogState_density;
    @Shadow
    private float fogState_start;
    @Shadow
    private float fogState_end;
    @Shadow
    private int cullState_field_179053_b;
    @Shadow
    private float polygonOffsetState_factor;
    @Shadow
    private float polygonOffsetState_units;
    @Shadow
    private int colorLogicState_field_179196_b;
    @Shadow
    private double clearState_field_179205_a;
    @Shadow
    private float clearState_field_179203_b_r;
    @Shadow
    private float clearState_field_179203_b_g;
    @Shadow
    private float clearState_field_179203_b_b;
    @Shadow
    private float clearState_field_179203_b_a;
    @Shadow
    private int activeTextureUnit;
    @Shadow
    private int activeShadeModel;
    @Shadow
    private boolean colorMaskState_r;
    @Shadow
    private boolean colorMaskState_g;
    @Shadow
    private boolean colorMaskState_b;
    @Shadow
    private boolean colorMaskState_a;
    @Shadow
    private float r;
    @Shadow
    private float g;
    @Shadow
    private float b;
    @Shadow
    private float a;
    @Shadow
    private boolean[] boolStates;

    @Shadow
    private static ArrayList<GlStateManager.BooleanState> booleanStates;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void celeritas$readLiveGlState(CallbackInfo ci) {
        this.alphaState_alphaFunc = ExtraUtils2GLStateCompat.getInteger(GL11.GL_ALPHA_TEST_FUNC);
        this.alphaState_alphaRef = ExtraUtils2GLStateCompat.getFloat(GL11.GL_ALPHA_TEST_REF);
        this.textureState_enabled = ExtraUtils2GLStateCompat.isEnabled(GL11.GL_TEXTURE_2D);
        this.textureState_name = ExtraUtils2GLStateCompat.getInteger(GL11.GL_TEXTURE_BINDING_2D);
        this.colorMaterialState_face = ExtraUtils2GLStateCompat.getInteger(GL11.GL_COLOR_MATERIAL_FACE);
        this.colorMaterialState_mode = ExtraUtils2GLStateCompat.getInteger(GL11.GL_COLOR_MATERIAL_PARAMETER);
        this.blendState_srcFactor = ExtraUtils2GLStateCompat.getInteger(GL14.GL_BLEND_SRC_RGB);
        this.blendState_dstFactor = ExtraUtils2GLStateCompat.getInteger(GL14.GL_BLEND_DST_RGB);
        this.blendState_srcFactorAlpha = ExtraUtils2GLStateCompat.getInteger(GL14.GL_BLEND_SRC_ALPHA);
        this.blendState_dstFactorAlpha = ExtraUtils2GLStateCompat.getInteger(GL14.GL_BLEND_DST_ALPHA);
        this.depthState_maskEnabled = ExtraUtils2GLStateCompat.getInteger(GL11.GL_DEPTH_WRITEMASK) != 0;
        this.depthState_depthFunc = ExtraUtils2GLStateCompat.getInteger(GL11.GL_DEPTH_FUNC);
        this.fogState_mode = ExtraUtils2GLStateCompat.getInteger(GL11.GL_FOG_MODE);
        this.fogState_density = ExtraUtils2GLStateCompat.getFloat(GL11.GL_FOG_DENSITY);
        this.fogState_start = ExtraUtils2GLStateCompat.getFloat(GL11.GL_FOG_START);
        this.fogState_end = ExtraUtils2GLStateCompat.getFloat(GL11.GL_FOG_END);
        this.cullState_field_179053_b = ExtraUtils2GLStateCompat.getInteger(GL11.GL_CULL_FACE_MODE);
        this.polygonOffsetState_factor = ExtraUtils2GLStateCompat.getFloat(GL11.GL_POLYGON_OFFSET_FACTOR);
        this.polygonOffsetState_units = ExtraUtils2GLStateCompat.getFloat(GL11.GL_POLYGON_OFFSET_UNITS);
        this.colorLogicState_field_179196_b = ExtraUtils2GLStateCompat.getInteger(GL11.GL_LOGIC_OP_MODE);
        this.clearState_field_179205_a = ExtraUtils2GLStateCompat.getFloat(GL11.GL_DEPTH_CLEAR_VALUE);
        FloatBuffer clearColor = BufferUtils.createFloatBuffer(4);
        ExtraUtils2GLStateCompat.getFloat(GL11.GL_COLOR_CLEAR_VALUE, clearColor);
        this.clearState_field_179203_b_r = clearColor.get(0);
        this.clearState_field_179203_b_g = clearColor.get(1);
        this.clearState_field_179203_b_b = clearColor.get(2);
        this.clearState_field_179203_b_a = clearColor.get(3);
        this.activeTextureUnit = ExtraUtils2GLStateCompat.getInteger(GL13.GL_ACTIVE_TEXTURE) - GL13.GL_TEXTURE0;
        this.activeShadeModel = ExtraUtils2GLStateCompat.getInteger(GL11.GL_SHADE_MODEL);
        IntBuffer colorMask = BufferUtils.createIntBuffer(4);
        ExtraUtils2GLStateCompat.getInteger(GL11.GL_COLOR_WRITEMASK, colorMask);
        this.colorMaskState_r = colorMask.get(0) != 0;
        this.colorMaskState_g = colorMask.get(1) != 0;
        this.colorMaskState_b = colorMask.get(2) != 0;
        this.colorMaskState_a = colorMask.get(3) != 0;
        FloatBuffer currentColor = BufferUtils.createFloatBuffer(4);
        ExtraUtils2GLStateCompat.getFloat(GL11.GL_CURRENT_COLOR, currentColor);
        this.r = currentColor.get(0);
        this.g = currentColor.get(1);
        this.b = currentColor.get(2);
        this.a = currentColor.get(3);
        for (int i = 0; i < this.boolStates.length; i++) {
            int cap = ((BooleanStateCapAccessor) (Object) booleanStates.get(i)).getCap();
            this.boolStates[i] = ExtraUtils2GLStateCompat.isEnabled(cap);
        }
    }
}