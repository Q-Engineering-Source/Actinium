package com.gtnewhorizons.angelica.render;

import com.gtnewhorizons.angelica.client.rendering.GlUniformFloat2v;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import dhj.embeddedt.embeddium.impl.gl.shader.GlProgram;
import dhj.embeddedt.embeddium.impl.gl.shader.GlShader;
import dhj.embeddedt.embeddium.impl.gl.shader.ShaderBindingContext;
import dhj.embeddedt.embeddium.impl.gl.shader.ShaderConstants;
import dhj.embeddedt.embeddium.impl.gl.shader.ShaderType;
import dhj.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformFloat;
import dhj.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformMatrix4f;
import dhj.embeddedt.embeddium.impl.render.shader.ShaderLoader;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import com.gtnewhorizons.angelica.glsm.GLSMBlend;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMDraws;
import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLSMRasterizer;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;

public class PanoramaRenderer {
    private static final int FBO_SIZE = 256;
    private static final int PANORAMA_GRID = 8;
    private static final float BLUR_RADIUS = 3.0f;
    private static final int VERTEX_STRIDE = 24;
    private static final int VERTS_PER_FACE = PANORAMA_GRID * PANORAMA_GRID * 4;
    private static final int INDICES_PER_FACE = PANORAMA_GRID * PANORAMA_GRID * 6;
    private static final Matrix4f[] FACE_ROTATIONS = new Matrix4f[6];
    private static PanoramaRenderer instance;

    static {
        FACE_ROTATIONS[0] = new Matrix4f();
        FACE_ROTATIONS[1] = new Matrix4f().rotateY((float) Math.toRadians(90.0));
        FACE_ROTATIONS[2] = new Matrix4f().rotateY((float) Math.toRadians(180.0));
        FACE_ROTATIONS[3] = new Matrix4f().rotateY((float) Math.toRadians(-90.0));
        FACE_ROTATIONS[4] = new Matrix4f().rotateX((float) Math.toRadians(90.0));
        FACE_ROTATIONS[5] = new Matrix4f().rotateX((float) Math.toRadians(-90.0));
    }

    private int fboA;
    private int fboB;
    private int texA;
    private int texB;
    private int cubeVao;
    private int cubeVbo;
    private int cubeIbo;
    private int emptyVao;
    private GlProgram<CubeUniforms> cubeProgram;
    private GlProgram<BlurUniforms> blurProgram;
    private GlProgram<BlitUniforms> blitProgram;
    private boolean initialized;
    private final Matrix4f mvpScratch = new Matrix4f();
    private final Matrix4f projScratch = new Matrix4f();
    private final Matrix4f baseMVScratch = new Matrix4f();

    public static PanoramaRenderer getInstance() {
        if (instance == null) {
            instance = new PanoramaRenderer();
        }

        return instance;
    }

    private void init() {
        if (this.initialized) {
            return;
        }

        this.initialized = true;
        this.texA = createTexture();
        this.texB = createTexture();
        this.fboA = createFbo(this.texA);
        this.fboB = createFbo(this.texB);
        this.buildCubeGeometry();
        this.emptyVao = GLSMVertexInput.glGenVertexArrays();
        this.initShaders();
    }

    private void initShaders() {
        this.cubeProgram = loadProgram("angelica:panorama_cube", "angelica:panorama_cube", "angelica:panorama_cube", CubeUniforms::new);
        this.blitProgram = loadProgram("angelica:panorama_blit", "angelica:panorama_blur", "angelica:panorama_blit", BlitUniforms::new);
        this.blurProgram = loadProgram("angelica:panorama_blur", "angelica:panorama_blur", "angelica:panorama_blur", BlurUniforms::new);
        this.blurProgram.bind();
        this.blurProgram.getInterface().radius.setFloat(BLUR_RADIUS);
        this.blurProgram.unbind();
    }

    private static <T> GlProgram<T> loadProgram(
        String name,
        String vertBase,
        String fragBase,
        Function<ShaderBindingContext, T> factory
    ) {
        final GlShader vert = ShaderLoader.loadShader(ShaderType.VERTEX, vertBase + ".vert", ShaderConstants.EMPTY);
        final GlShader frag = ShaderLoader.loadShader(ShaderType.FRAGMENT, fragBase + ".frag", ShaderConstants.EMPTY);

        try {
            final GlProgram<T> program = GlProgram.builder(name).attachShader(vert).attachShader(frag).link(factory);
            program.bind();
            GLSMPrograms.glUniform1i(GLSMPrograms.glGetUniformLocation(program.handle(), "u_Texture"), 0);
            program.unbind();
            return program;
        } finally {
            vert.delete();
            frag.delete();
        }
    }

    private void buildCubeGeometry() {
        final int numPasses = PANORAMA_GRID * PANORAMA_GRID;
        final FloatBuffer vbuf = ByteBuffer.allocateDirect(VERTS_PER_FACE * VERTEX_STRIDE).order(ByteOrder.nativeOrder()).asFloatBuffer();

        for (int k = 0; k < numPasses; k++) {
            final float tx = ((float) (k % PANORAMA_GRID) / PANORAMA_GRID - 0.5f) / 64.0f;
            final float ty = ((float) (k / PANORAMA_GRID) / PANORAMA_GRID - 0.5f) / 64.0f;
            final float alpha = 1.0f / (k + 1);

            vbuf.put(-1.0f + tx).put(-1.0f + ty).put(1.0f).put(0.0f).put(0.0f).put(alpha);
            vbuf.put(1.0f + tx).put(-1.0f + ty).put(1.0f).put(1.0f).put(0.0f).put(alpha);
            vbuf.put(1.0f + tx).put(1.0f + ty).put(1.0f).put(1.0f).put(1.0f).put(alpha);
            vbuf.put(-1.0f + tx).put(1.0f + ty).put(1.0f).put(0.0f).put(1.0f).put(alpha);
        }

        vbuf.flip();

        final ShortBuffer ibuf = ByteBuffer.allocateDirect(INDICES_PER_FACE * 2).order(ByteOrder.nativeOrder()).asShortBuffer();

        for (int k = 0; k < numPasses; k++) {
            final int base = k * 4;
            ibuf.put((short) base).put((short) (base + 1)).put((short) (base + 2));
            ibuf.put((short) base).put((short) (base + 2)).put((short) (base + 3));
        }

        ibuf.flip();

        this.cubeVao = GLSMVertexInput.glGenVertexArrays();
        GLSMVertexInput.glBindVertexArray(this.cubeVao);

        this.cubeVbo = GLSMBuffers.glGenBuffers();
        GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.cubeVbo);
        GLSMBuffers.glBufferData(GL15.GL_ARRAY_BUFFER, vbuf, GL15.GL_STATIC_DRAW);

        GLSMVertexInput.glEnableVertexAttribArray(0);
        GLSMVertexInput.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, VERTEX_STRIDE, 0L);
        GLSMVertexInput.glEnableVertexAttribArray(1);
        GLSMVertexInput.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, VERTEX_STRIDE, 12L);
        GLSMVertexInput.glEnableVertexAttribArray(2);
        GLSMVertexInput.glVertexAttribPointer(2, 1, GL11.GL_FLOAT, false, VERTEX_STRIDE, 20L);

        this.cubeIbo = GLSMBuffers.glGenBuffers();
        GLSMBuffers.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, this.cubeIbo);
        GLSMBuffers.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, ibuf, GL15.GL_STATIC_DRAW);

        GLSMVertexInput.glBindVertexArray(0);
    }

    private static int createTexture() {
        final int tex = GLSMTextures.glGenTextures();
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GLSMTextures.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, FBO_SIZE, FBO_SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GLSMTextures.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        return tex;
    }

    private static int createFbo(int colorTex) {
        final int fbo = GLSMFramebuffer.glGenFramebuffers();
        GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GLSMFramebuffer.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorTex, 0);
        final int status = GLSMFramebuffer.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);

        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Panorama FBO incomplete: 0x" + Integer.toHexString(status));
        }

        GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        return fbo;
    }

    public void renderSkybox(
        int panoramaTimer,
        float partialTicks,
        ResourceLocation[] panoramaPaths,
        Minecraft mc,
        int screenWidth,
        int screenHeight,
        float zLevel
    ) {
        this.init();

        GLSMBlend.glColorMask(true, true, true, true);
        GLStateManager.disableCull();
        GLStateManager.disableScissorTest();
        GLStateManager.disableDepthTest();

        mc.getFramebuffer().unbindFramebuffer();
        GLSMRasterizer.glViewport(0, 0, FBO_SIZE, FBO_SIZE);
        GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fboA);
        GLSMBlend.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLSMFramebuffer.glClear(GL11.GL_COLOR_BUFFER_BIT);

        this.drawCubeFaces(panoramaTimer, partialTicks, panoramaPaths, mc);

        GLStateManager.disableBlend();
        this.blurProgram.bind();
        GLSMVertexInput.glBindVertexArray(this.emptyVao);
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);

        final BlurUniforms blurUniforms = this.blurProgram.getInterface();

        GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fboB);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, this.texA);
        blurUniforms.direction.set(1.0f / FBO_SIZE, 0.0f);
        GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

        GLSMFramebuffer.glBindFramebuffer(GL30.GL_FRAMEBUFFER, this.fboA);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, this.texB);
        blurUniforms.direction.set(0.0f, 1.0f / FBO_SIZE);
        GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

        mc.getFramebuffer().bindFramebuffer(true);
        GLSMRasterizer.glViewport(0, 0, mc.displayWidth, mc.displayHeight);

        this.blitProgram.bind();
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, this.texA);

        final float scale = screenWidth > screenHeight ? 120.0F / screenWidth : 120.0F / screenHeight;
        this.blitProgram.getInterface().scaleV.setFloat(screenHeight * scale / 256.0F);
        this.blitProgram.getInterface().scaleU.setFloat(screenWidth * scale / 256.0F);
        GLSMDraws.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

        GLSMVertexInput.glBindVertexArray(0);
        this.blitProgram.unbind();

        GLStateManager.enableDepthTest();
        GLStateManager.enableCull();
        GLStateManager.enableBlend();
    }

    private void drawCubeFaces(int panoramaTimer, float partialTicks, ResourceLocation[] panoramaPaths, Minecraft mc) {
        final float pitch = MathHelper.sin(((float) panoramaTimer + partialTicks) / 400.0F) * 25.0F + 20.0F;
        final float yaw = -((float) panoramaTimer + partialTicks) * 0.1F;
        final Matrix4f base = this.baseMVScratch
            .identity()
            .rotateX((float) Math.toRadians(180.0))
            .rotateZ((float) Math.toRadians(90.0))
            .rotateX((float) Math.toRadians(pitch))
            .rotateY((float) Math.toRadians(yaw));
        final Matrix4f proj = this.projScratch.setPerspective((float) Math.toRadians(120.0), 1.0f, 0.05f, 10.0f);

        GLStateManager.enableBlend();
        GLStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        GLSMDepthStencilAlpha.glDepthMask(false);

        this.cubeProgram.bind();
        GLSMVertexInput.glBindVertexArray(this.cubeVao);
        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);

        final CubeUniforms cubeUniforms = this.cubeProgram.getInterface();

        for (int face = 0; face < 6; face++) {
            proj.mul(base, this.mvpScratch).mul(FACE_ROTATIONS[face]);
            cubeUniforms.mvp.set(this.mvpScratch);
            mc.getTextureManager().bindTexture(panoramaPaths[face]);
            GLSMDraws.glDrawElements(GL11.GL_TRIANGLES, INDICES_PER_FACE, GL11.GL_UNSIGNED_SHORT, 0L);
        }

        GLSMVertexInput.glBindVertexArray(0);
        this.cubeProgram.unbind();
        GLSMDepthStencilAlpha.glDepthMask(true);
    }

    private static class CubeUniforms {
        final GlUniformMatrix4f mvp;

        CubeUniforms(ShaderBindingContext ctx) {
            this.mvp = ctx.bindUniform("u_MVP", GlUniformMatrix4f::new);
        }
    }

    private static class BlurUniforms {
        final GlUniformFloat2v direction;
        final GlUniformFloat radius;

        BlurUniforms(ShaderBindingContext ctx) {
            this.direction = ctx.bindUniform("u_Direction", GlUniformFloat2v::new);
            this.radius = ctx.bindUniform("u_Radius", GlUniformFloat::new);
        }
    }

    private static class BlitUniforms {
        final GlUniformFloat scaleV;
        final GlUniformFloat scaleU;

        BlitUniforms(ShaderBindingContext ctx) {
            this.scaleV = ctx.bindUniform("u_ScaleV", GlUniformFloat::new);
            this.scaleU = ctx.bindUniform("u_ScaleU", GlUniformFloat::new);
        }
    }
}
