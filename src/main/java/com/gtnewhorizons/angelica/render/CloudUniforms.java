package com.gtnewhorizons.angelica.render;

import dhj.embeddedt.embeddium.impl.gl.shader.ShaderBindingContext;
import dhj.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformFloat;
import dhj.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformFloat4v;
import dhj.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformInt;
import dhj.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformMatrix4f;

/**
 * Uniform handles of one cloud program, with the change tracking that keeps per frame uploads small.
 *
 * <p>Every setter skips the GL call when the value did not move since the last upload, because the same
 * program draws every cloud layer several times per frame and the fog, colour and scroll inputs rarely
 * change between those draws. Fields are {@code null} for uniforms the variant does not declare, so a
 * setter must go through the corresponding {@code set*} method instead of touching the handle.
 */
final class CloudUniforms {
    /** Opacity of a cloud layer whose provider supplies no alpha, matching the vanilla cloud blend. */
    static final float ALPHA = 0.8f;

    /** Clip space transform of the current draw. */
    final GlUniformMatrix4f mvp;
    /** World to eye transform, used to compute the fog distance per vertex. */
    final GlUniformMatrix4f modelView;
    /** Constant tint multiplied into the fragment colour, including the layer alpha. */
    final GlUniformFloat4v colorMult;
    /** Fog curve parameters: x, y for linear, z for the density, w for the mode. */
    final GlUniformFloat4v fogParams;
    /** Fog colour mixed into the fragment colour. */
    final GlUniformFloat4v fogColor;
    /** Whether fog applies at all for this draw. */
    final GlUniformInt fogEnabled;
    /** Texture unit the cloud texture is bound to, absent from the UNTEXTURED variants. */
    final GlUniformInt textureUnit;
    /** Vertical size of a cloud cell in blocks, present only in the instanced face variants. */
    final GlUniformFloat cellHeight;
    /** Texture scroll and scale as {@code (scrollX, scrollZ, scaleX, scaleZ)}, present only in the textured face variants. */
    final GlUniformFloat4v scroll;
    /** Scratch vector reused for every four component upload to avoid allocating per uniform write. */
    private final float[] vec4Buf = new float[4];
    /** Scroll value last uploaded, kept to skip repeated work. */
    private float lastScrollX = Float.NaN, lastScrollZ = Float.NaN;
    /** Texture scale last uploaded. */
    private float lastTextureScaleX = Float.NaN, lastTextureScaleZ = Float.NaN;
    /** Fog enable state last uploaded, {@code -1} before the first upload. */
    private int lastFogEnabled = -1;
    /** Fog colour last uploaded. */
    private float lastFogR = Float.NaN, lastFogG = Float.NaN, lastFogB = Float.NaN;
    /** Colour multiplier last uploaded. */
    private float lastColorR = Float.NaN, lastColorG = Float.NaN, lastColorB = Float.NaN, lastColorA = Float.NaN;
    /** Fog parameters last uploaded. */
    private float lastFogParamX = Float.NaN, lastFogParamY = Float.NaN, lastFogParamZ = Float.NaN, lastFogParamW = Float.NaN;
    /** Cell height last uploaded. */
    private float lastCellHeight = Float.NaN;

    /**
     * Resolves the uniform handles of a linked program. The frame uniforms are required, the variant
     * specific ones are optional so one implementation serves all four programs.
     */
    CloudUniforms(ShaderBindingContext context) {
        mvp = context.bindUniform("u_MVPMatrix", GlUniformMatrix4f::new);
        modelView = context.bindUniform("u_MVMatrix", GlUniformMatrix4f::new);
        colorMult = context.bindUniform("u_ColorMult", GlUniformFloat4v::new);
        fogParams = context.bindUniform("u_FogParams", GlUniformFloat4v::new);
        fogColor = context.bindUniform("u_FogColor", GlUniformFloat4v::new);
        fogEnabled = context.bindUniform("u_FogEnabled", GlUniformInt::new);
        textureUnit = context.bindUniformIfPresent("u_Tex", GlUniformInt::new);
        cellHeight = context.bindUniformIfPresent("u_CellHeight", GlUniformFloat::new);
        scroll = context.bindUniformIfPresent("u_Scroll", GlUniformFloat4v::new);
    }

    /**
     * Uploads the texture scroll and scale, the inputs the face vertex shader needs to place the per cell
     * UVs. Skipped for the plane programs, which carry the UVs in the vertex data.
     */
    void setScroll(float x, float z, float textureScaleX, float textureScaleZ) {
        if (scroll == null || (x == lastScrollX && z == lastScrollZ && textureScaleX == lastTextureScaleX && textureScaleZ == lastTextureScaleZ)) return;
        lastScrollX = x;
        lastScrollZ = z;
        lastTextureScaleX = textureScaleX;
        lastTextureScaleZ = textureScaleZ;
        vec4Buf[0] = x;
        vec4Buf[1] = z;
        vec4Buf[2] = textureScaleX;
        vec4Buf[3] = textureScaleZ;
        scroll.set(vec4Buf);
    }

    /** Uploads the cell height in blocks, which the face vertex shader expands the walls to. */
    void setCellHeight(float blocks) {
        if (blocks == lastCellHeight) return;
        lastCellHeight = blocks;
        cellHeight.setFloat(blocks);
    }

    /** Uploads the fog enable state of the current pass. */
    void setFogEnabled(boolean enabled) {
        final int enabledValue = enabled ? 1 : 0;
        if (enabledValue != lastFogEnabled) {
            lastFogEnabled = enabledValue;
            fogEnabled.setInt(enabledValue);
        }
    }

    /** Uploads the fog colour, keeping alpha at one so it never attenuates the cloud coverage. */
    void setFogColor(float r, float g, float b) {
        if (r != lastFogR || g != lastFogG || b != lastFogB) {
            lastFogR = r;
            lastFogG = g;
            lastFogB = b;
            vec4Buf[0] = r;
            vec4Buf[1] = g;
            vec4Buf[2] = b;
            vec4Buf[3] = 1.0f;
            fogColor.set(vec4Buf);
        }
    }

    /** Uploads the fog curve of the current pass. */
    void setFogParams(float x, float y, float z, float w) {
        if (x != lastFogParamX || y != lastFogParamY || z != lastFogParamZ || w != lastFogParamW) {
            lastFogParamX = x;
            lastFogParamY = y;
            lastFogParamZ = z;
            lastFogParamW = w;
            vec4Buf[0] = x;
            vec4Buf[1] = y;
            vec4Buf[2] = z;
            vec4Buf[3] = w;
            fogParams.set(vec4Buf);
        }
    }

    /**
     * Uploads the layer tint and alpha. This is the colour the renderer passes to {@code glColor4f} in the
     * fixed function path; the meshes bake the same colour into their vertex attribute for the pack path.
     */
    void setColorMult(float r, float g, float b, float alpha) {
        if (r != lastColorR || g != lastColorG || b != lastColorB || alpha != lastColorA) {
            lastColorR = r;
            lastColorG = g;
            lastColorB = b;
            lastColorA = alpha;
            vec4Buf[0] = r;
            vec4Buf[1] = g;
            vec4Buf[2] = b;
            vec4Buf[3] = alpha;
            colorMult.set(vec4Buf);
        }
    }
}
