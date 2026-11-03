package net.coderbot.iris.celeritas;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import net.coderbot.iris.Iris;
import net.coderbot.iris.debug.IrisGlDebug;
import net.coderbot.iris.gl.blending.AlphaTestOverride;
import net.coderbot.iris.gl.blending.BlendModeOverride;
import net.coderbot.iris.gl.blending.BufferBlendOverride;
import net.coderbot.iris.gl.framebuffer.GlFramebuffer;
import net.coderbot.iris.gl.program.ProgramImages;
import net.coderbot.iris.gl.program.ProgramSamplers;
import net.coderbot.iris.gl.program.ProgramUniforms;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import net.coderbot.iris.samplers.IrisSamplers;
import net.coderbot.iris.shadows.ShadowRenderingState;
import net.coderbot.iris.uniforms.custom.CustomUniforms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.gtnewhorizon.gtnhlib.client.renderer.postprocessing.PostProcessingBridge;
import dhj.embeddedt.embeddium.impl.gl.shader.ShaderBindingContext;
import dhj.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformFloat3v;
import dhj.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformMatrix3f;
import dhj.embeddedt.embeddium.impl.gl.shader.uniform.GlUniformMatrix4f;
import dhj.embeddedt.embeddium.impl.gl.tessellation.GlPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderInterface;
import dhj.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderTextureSlot;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;

import java.util.List;
import com.gtnewhorizons.angelica.glsm.GLSMBuffers;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;
import com.gtnewhorizons.angelica.glsm.GLSMEnable;
import com.gtnewhorizons.angelica.glsm.GLSMTextures;
import com.gtnewhorizons.angelica.glsm.GLSMVertexInput;

public class IrisCeleritasChunkShaderInterface implements ChunkShaderInterface {
    @Nullable
    private final GlUniformMatrix4f uniformModelViewMatrix;
    @Nullable
    private final GlUniformMatrix4f uniformModelViewMatrixInverse;
    @Nullable
    private final GlUniformMatrix4f uniformProjectionMatrix;
    @Nullable
    private final GlUniformMatrix4f uniformProjectionMatrixInverse;
    @Nullable
    private final GlUniformMatrix3f uniformNormalMatrix;
    @Nullable
    private final GlUniformFloat3v uniformRegionOffset;
    private final int handle;
    private final boolean shadowPass;

    // Iris program state
    private final ProgramUniforms irisProgramUniforms;
    private final ProgramSamplers irisProgramSamplers;
    private final ProgramImages irisProgramImages;
    private final CustomUniforms customUniforms;

    // Rendering state
    @Nullable
    private final AlphaTestOverride alphaTestOverride;
    private final float alphaReference;
    private final BlendModeOverride blendModeOverride;
    private final List<BufferBlendOverride> bufferBlendOverrides;
    private final boolean hasOverrides;
    private boolean alphaTestOverrideApplied;
    private boolean depthStateOverridden;
    private boolean previousDepthTestEnabled;
    private boolean previousDepthMaskEnabled;
    private int previousDepthFunc;

    // Stored matrices for inverse and normal matrix computation
    private final Matrix4f projectionMatrixInverse = new Matrix4f();
    private final Matrix4f modelViewMatrixInverse = new Matrix4f();
    private final Matrix3f normalMatrix = new Matrix3f();

    public IrisCeleritasChunkShaderInterface(int handle, ShaderBindingContext context, CeleritasTerrainPipeline pipeline, boolean isShadowPass, @Nullable AlphaTestOverride alphaTestOverride, float alphaReference, BlendModeOverride blendModeOverride, List<BufferBlendOverride> bufferBlendOverrides, CustomUniforms customUniforms) {
        this.handle = handle;
        this.shadowPass = isShadowPass;
        this.uniformModelViewMatrix = context.bindUniformIfPresent("iris_ModelViewMatrix", GlUniformMatrix4f::new);
        this.uniformModelViewMatrixInverse = context.bindUniformIfPresent("iris_ModelViewMatrixInverse", GlUniformMatrix4f::new);
        this.uniformProjectionMatrix = context.bindUniformIfPresent("iris_ProjectionMatrix", GlUniformMatrix4f::new);
        this.uniformProjectionMatrixInverse = context.bindUniformIfPresent("iris_ProjectionMatrixInverse", GlUniformMatrix4f::new);
        this.uniformNormalMatrix = context.bindUniformIfPresent("iris_NormalMatrix", GlUniformMatrix3f::new);
        this.uniformRegionOffset = context.bindUniformIfPresent("u_RegionOffset", GlUniformFloat3v::new);

        this.alphaTestOverride = alphaTestOverride;
        this.alphaReference = alphaReference;
        this.blendModeOverride = blendModeOverride;
        this.bufferBlendOverrides = bufferBlendOverrides;
        this.hasOverrides = bufferBlendOverrides != null && !bufferBlendOverrides.isEmpty();
        this.customUniforms = customUniforms;

        final ProgramUniforms.Builder builder = pipeline.initUniforms(handle);
        customUniforms.mapholderToPass(builder, this);
        this.irisProgramUniforms = builder.buildUniforms();
        this.irisProgramSamplers = isShadowPass ? pipeline.initShadowSamplers(handle) : pipeline.initTerrainSamplers(handle);
        this.irisProgramImages = isShadowPass ? pipeline.initShadowImages(handle) : pipeline.initTerrainImages(handle);
    }

    @Override
    public void setupState(TerrainRenderPass pass) {
        bindFramebuffer(pass);

        if (!depthStateOverridden) {
            previousDepthTestEnabled = GLSMEnable.glIsEnabled(GL11.GL_DEPTH_TEST);
            previousDepthMaskEnabled = GLStateManager.getDepthState().isMaskEnabled();
            previousDepthFunc = GLStateManager.getDepthState().getFunc();
            depthStateOverridden = true;
        }

        // Hand and fullscreen passes can leave depth disabled or mask writes off before terrain draws.
        GLStateManager.enableDepthTest();
        GLSMDepthStencilAlpha.glDepthFunc(GL11.GL_LEQUAL);
        GLSMDepthStencilAlpha.glDepthMask(shouldWriteDepth(pass, ShadowRenderingState.areShadowsCurrentlyBeingRendered()));

        if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
            GLStateManager.disableCull();
        }

        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0 + IrisSamplers.ALBEDO_TEXTURE_UNIT);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, Minecraft.getMinecraft().getTextureMapBlocks().getGlTextureId());

        GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0 + IrisSamplers.LIGHTMAP_TEXTURE_UNIT);
        final DynamicTexture lightmapTexture = PostProcessingBridge.getLightmapTexture(Minecraft.getMinecraft().entityRenderer);
        GLSMTextures.glBindTexture(GL11.GL_TEXTURE_2D, lightmapTexture.getGlTextureId());

        if (blendModeOverride != null) {
            blendModeOverride.apply();
        }

        if (hasOverrides) {
            bufferBlendOverrides.forEach(BufferBlendOverride::apply);
        }

        // Upload the alpha-test state before uniforms read iris_currentAlphaTest.
        if (alphaTestOverride != null) {
            alphaTestOverride.apply();
            alphaTestOverrideApplied = true;
        } else {
            AlphaTestOverride.restore();
            alphaTestOverrideApplied = false;
        }

        if (irisProgramUniforms != null) {
            irisProgramUniforms.update();
        }
        if (irisProgramSamplers != null) {
            irisProgramSamplers.update();
        }
        if (irisProgramImages != null) {
            irisProgramImages.update();
        }

        customUniforms.push(this);
        IrisGlDebug.logCeleritasTerrainState(pass.name(), this.handle, alphaTestOverride != null, alphaReference);
    }

    public static boolean shouldWriteDepth(TerrainRenderPass pass, boolean shadowPass) {
        // The main translucent pass must never write depth: under shaders, block entities render
        // after translucent terrain, so glass windows (EnderIO fluid tanks, issue #58) writing
        // depth would occlude the TESR fluid behind them. The pass's writesDepth flag carries the
        // shaderless (fixed-function) policy, where vanilla keeps the mask on and TESRs draw first;
        // the Iris path therefore applies the per-semantic override itself instead of deferring to
        // the flag. Water keeps writing depth so stacked water sorts correctly (#79).
        return shadowPass || (pass.writesDepth() && pass.semantic() != TerrainRenderPass.Semantic.TRANSLUCENT);
    }

    @Override
    public void restoreState() {
        if (depthStateOverridden) {
            if (previousDepthTestEnabled) {
                GLStateManager.enableDepthTest();
            } else {
                GLStateManager.disableDepthTest();
            }
            GLSMDepthStencilAlpha.glDepthFunc(previousDepthFunc);
            GLSMDepthStencilAlpha.glDepthMask(previousDepthMaskEnabled);
            depthStateOverridden = false;
        }

        if (alphaTestOverrideApplied) {
            AlphaTestOverride.restore();
            alphaTestOverrideApplied = false;
        }

        if (blendModeOverride != null || hasOverrides) {
            BlendModeOverride.restore();
        }

        ProgramUniforms.clearActiveUniforms();
        ProgramSamplers.clearActiveSamplers();

        if (this.shadowPass) {
            // Shadow entity rendering still uses fixed-function pointers on some drivers.
            GLSMVertexInput.glBindVertexArray(0);
            GLSMBuffers.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            GLSMBuffers.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, 0);
            GLSMTextures.glActiveTexture(GL13.GL_TEXTURE0);

            for (int attribute = 0; attribute <= 14; attribute++) {
                GL20.glDisableVertexAttribArray(attribute);
            }
        }
    }

    @Override
    public GlPrimitiveType getPrimitiveType() {
        return GlPrimitiveType.TRIANGLES;
    }

    @Override
    public void setProjectionMatrix(Matrix4fc matrix) {
        if (uniformProjectionMatrix != null) {
            uniformProjectionMatrix.set(matrix);
        }

        if (uniformProjectionMatrixInverse != null) {
            projectionMatrixInverse.set(matrix);
            projectionMatrixInverse.invert();
            uniformProjectionMatrixInverse.set(projectionMatrixInverse);
        }
    }

    @Override
    public void setModelViewMatrix(Matrix4fc modelView) {
        if (uniformModelViewMatrix != null) {
            uniformModelViewMatrix.set(modelView);
        }

        if (uniformModelViewMatrixInverse != null) {
            modelViewMatrixInverse.set(modelView);
            modelViewMatrixInverse.invert();
            uniformModelViewMatrixInverse.set(modelViewMatrixInverse);
        }

        if (uniformNormalMatrix != null) {
            normalMatrix.set(modelView);
            normalMatrix.invert();
            normalMatrix.transpose();
            uniformNormalMatrix.set(normalMatrix);
        }
    }

    @Override
    public void setRegionOffset(float x, float y, float z) {
        if (uniformRegionOffset != null) {
            uniformRegionOffset.set(x, y, z);
        }
    }

    @Override
    public void setTextureSlot(ChunkShaderTextureSlot slot, int val) {
        // No-op - Iris manages texture state internally
    }

    private void bindFramebuffer(TerrainRenderPass pass) {
        final CeleritasTerrainPipeline pipeline = getCeleritasTerrainPipeline();
        if (pipeline == null) {
            return;
        }

        final boolean isShadow = ShadowRenderingState.areShadowsCurrentlyBeingRendered();
        final IrisTerrainPass irisPass = IrisTerrainPass.fromTerrainPass(pass, isShadow);

        final GlFramebuffer framebuffer = pipeline.getPassInfo(irisPass).framebuffer();
        if (framebuffer != null) {
            framebuffer.bind();
        }
    }

    @org.jetbrains.annotations.Nullable
    private CeleritasTerrainPipeline getCeleritasTerrainPipeline() {
        final WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        return pipeline != null ? pipeline.getCeleritasTerrainPipeline() : null;
    }
}
