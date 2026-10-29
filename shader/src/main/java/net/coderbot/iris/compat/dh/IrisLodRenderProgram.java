package net.coderbot.iris.compat.dh;

import com.google.common.primitives.Ints;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;
import net.coderbot.iris.gl.blending.AlphaTestOverride;
import net.coderbot.iris.gl.blending.BlendModeOverride;
import net.coderbot.iris.gl.blending.BufferBlendOverride;
import net.coderbot.iris.gl.ResourceCleanup;
import net.coderbot.iris.gl.program.ProgramImages;
import net.coderbot.iris.gl.program.ProgramSamplers;
import net.coderbot.iris.gl.program.ProgramUniforms;
import net.coderbot.iris.gl.state.FogMode;
import net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline;
import net.coderbot.iris.pipeline.PatchedShaderPrinter;
import net.coderbot.iris.pipeline.transform.PatchShaderType;
import net.coderbot.iris.pipeline.transform.TransformPatcher;
import net.coderbot.iris.samplers.IrisSamplers;
import net.coderbot.iris.shaderpack.ProgramSource;
import net.coderbot.iris.uniforms.CommonUniforms;
import net.coderbot.iris.uniforms.builtin.BuiltinReplacementUniforms;
import net.coderbot.iris.uniforms.custom.CustomUniforms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.gtnewhorizon.gtnhlib.client.renderer.postprocessing.PostProcessingBridge;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import com.mitchej123.lwjgl.MemoryStack;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class IrisLodRenderProgram {
    // Uniforms
    public final int modelOffsetUniform;
    public final int worldYOffsetUniform;
    public final int mircoOffsetUniform;
    public final int modelViewUniform;
    public final int modelViewInverseUniform;
    public final int projectionUniform;
    public final int projectionInverseUniform;
    public final int normalMatrix3fUniform;
    // Fog/Clip Uniforms
    public final int clipDistanceUniform;
    private final int id;
    private boolean freed;
    private final ProgramUniforms uniforms;
    private final CustomUniforms customUniforms;
    private final ProgramSamplers samplers;
    private final ProgramImages images;
    private final AlphaTestOverride alphaTestOverride;
    private final BlendModeOverride blend;
    private final BufferBlendOverride[] bufferBlendOverrides;
    private final Matrix4f tempMat4a = new Matrix4f();
    private final Matrix4f tempMat4b = new Matrix4f();
    private final Matrix3f tempMat3 = new Matrix3f();

    // This will bind  AbstractVertexAttribute
    private IrisLodRenderProgram(String name, boolean isShadowPass, boolean translucent, AlphaTestOverride alphaTestOverride, BlendModeOverride override, BufferBlendOverride[] bufferBlendOverrides, String vertex, String tessControl, String tessEval, String geometry, String fragment, CustomUniforms customUniforms, DeferredWorldRenderingPipeline pipeline, ProgramSource source) {
		id = IrisDhProgramCompiler.create(name, vertex, tessControl, tessEval, geometry, fragment,
			new String[] {"vPosition", "iris_color", "irisExtra"}, new int[] {0, 1, 2});

		try {
        this.bufferBlendOverrides = bufferBlendOverrides;
        this.alphaTestOverride = alphaTestOverride;

        blend = override;
        ProgramUniforms.Builder uniformBuilder = ProgramUniforms.builder(name, id);
        ProgramSamplers.Builder samplerBuilder = ProgramSamplers.builder(id, IrisSamplers.WORLD_RESERVED_TEXTURE_UNITS);
        CommonUniforms.addNonDynamicUniforms(uniformBuilder, source.getParent().getPack().getIdMap(), source.getParent().getPackDirectives(), pipeline.getFrameUpdateNotifier());
        CommonUniforms.addDynamicUniforms(uniformBuilder, FogMode.PER_VERTEX);
        customUniforms.assignTo(uniformBuilder);
        BuiltinReplacementUniforms.addBuiltinReplacementUniforms(uniformBuilder);
        ProgramImages.Builder builder = ProgramImages.builder(id);
        pipeline.addGbufferOrShadowSamplers(samplerBuilder, builder,
            isShadowPass ? pipeline::getFlippedBeforeShadow : () -> translucent ? pipeline.getFlippedAfterTranslucent() : pipeline.getFlippedAfterPrepare(),
            isShadowPass, false, true, false);
        customUniforms.mapholderToPass(uniformBuilder, this);
        this.uniforms = uniformBuilder.buildUniforms();
        this.customUniforms = customUniforms;
        samplers = samplerBuilder.build();
        images = builder.build();

        modelOffsetUniform = tryGetUniformLocation2("modelOffset");
        worldYOffsetUniform = tryGetUniformLocation2("worldYOffset");
        mircoOffsetUniform = tryGetUniformLocation2("mircoOffset");
        projectionUniform = tryGetUniformLocation2("iris_ProjectionMatrix");
        projectionInverseUniform = tryGetUniformLocation2("iris_ProjectionMatrixInverse");
        modelViewUniform = tryGetUniformLocation2("iris_ModelViewMatrix");
        modelViewInverseUniform = tryGetUniformLocation2("iris_ModelViewMatrixInverse");
        normalMatrix3fUniform = tryGetUniformLocation2("iris_NormalMatrix");

        // Fog/Clip Uniforms
        clipDistanceUniform = tryGetUniformLocation2("clipDistance");
		} catch (RuntimeException | Error failure) {
			ResourceCleanup.runAll(failure, () -> GLStateManager.glDeleteProgram(id));
			throw failure;
		}
    }

    public static IrisLodRenderProgram createProgram(String name, boolean isShadowPass, boolean translucent, ProgramSource source, CustomUniforms uniforms, DeferredWorldRenderingPipeline pipeline) {
        Map<PatchShaderType, String> transformed = TransformPatcher.patchDHTerrain(
            name,
            source.getVertexSource().orElseThrow(RuntimeException::new),
            source.getTessControlSource().orElse(null),
            source.getTessEvalSource().orElse(null),
            source.getGeometrySource().orElse(null),
            source.getFragmentSource().orElseThrow(RuntimeException::new),
            pipeline.getTextureMap());
        String vertex = transformed.get(PatchShaderType.VERTEX);
        String tessControl = transformed.get(PatchShaderType.TESS_CONTROL);
        String tessEval = transformed.get(PatchShaderType.TESS_EVAL);
        String geometry = transformed.get(PatchShaderType.GEOMETRY);
        String fragment = transformed.get(PatchShaderType.FRAGMENT);
        PatchedShaderPrinter.debugPatchedShaders(source.getName(), vertex, geometry, tessControl, tessEval, fragment);

        List<BufferBlendOverride> bufferOverrides = new ArrayList<>();

        source.getDirectives().getBufferBlendOverrides().forEach(information -> {
            int index = Ints.indexOf(source.getDirectives().getDrawBuffers(), information.getIndex());
            if (index > -1) {
                bufferOverrides.add(new BufferBlendOverride(index, information.getBlendMode()));
            }
        });

        return new IrisLodRenderProgram(name, isShadowPass, translucent,
            source.getDirectives().getAlphaTestOverride().orElse(AlphaTestOverride.OFF),
            source.getDirectives().getBlendModeOverride().orElse(null),
            bufferOverrides.toArray(BufferBlendOverride[]::new),
            vertex, tessControl, tessEval, geometry, fragment, uniforms, pipeline, source);
    }

    // Noise Uniforms

    public int tryGetUniformLocation2(CharSequence name) {
        return GLStateManager.glGetUniformLocation(this.id, name);
    }

    public void setUniform(int index, Matrix4fc matrix) {
        if (index == -1 || matrix == null) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.callocFloat(16);
            matrix.get(buffer);
            buffer.rewind();

            GLStateManager.glUniformMatrix4(index, false, buffer);
        }
    }

    public void setUniform(int index, Matrix3f matrix) {
        if (index == -1) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.callocFloat(9);
            matrix.get(buffer);
            buffer.rewind();

            GLStateManager.glUniformMatrix3(index, false, buffer);
        }
    }

    // Override ShaderProgram.bind()
    public void bind() {
        GLStateManager.glUseProgram(id);
        if (alphaTestOverride != null) {
            alphaTestOverride.apply();
        }
        if (blend != null) blend.apply();

        for (BufferBlendOverride override : bufferBlendOverrides) {
            override.apply();
        }
    }

    public void unbind() {
        GLStateManager.glUseProgram(0);
        AlphaTestOverride.restore();
        BlendModeOverride.restore();
        ProgramUniforms.clearActiveUniforms();
        ProgramSamplers.clearActiveSamplers();
    }

    public void free() {
		if (freed) {
			return;
		}
		freed = true;
		ResourceCleanup.runAll(null,
			uniforms::removeListeners,
			samplers::removeListeners,
			() -> GLStateManager.glDeleteProgram(id));
    }

    public void fillUniformData(Matrix4fc projection, Matrix4fc modelView, int worldYOffset, float partialTicks) {
        GLStateManager.glUseProgram(id);

        GLStateManager.glActiveTexture(GL13.GL_TEXTURE0 + IrisSamplers.LIGHTMAP_TEXTURE_UNIT);
        DynamicTexture lightmapTexture = PostProcessingBridge.getLightmapTexture(Minecraft.getMinecraft().entityRenderer);
        GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, lightmapTexture.getGlTextureId());
        setUniform(modelViewUniform, modelView);
        setUniform(modelViewInverseUniform, modelView.invert(tempMat4a));
        setUniform(projectionUniform, projection);
        setUniform(projectionInverseUniform, projection.invert(tempMat4b));
        setUniform(normalMatrix3fUniform, tempMat4a.set(modelView).invert().transpose3x3(tempMat3));

        setUniform(mircoOffsetUniform, 0.01f); // 0.01 block offset

        if (worldYOffsetUniform != -1) setUniform(worldYOffsetUniform, (float) worldYOffset);

        // Fog/Clip Uniforms
        float dhNearClipDistance = DhApi.Delayed.renderProxy.getNearClipPlaneDistanceInBlocks(partialTicks);
        setUniform(clipDistanceUniform, dhNearClipDistance);

        samplers.update();
        uniforms.update();

        customUniforms.push(this);

        images.update();
    }

    private void setUniform(int index, float value) {
        GLStateManager.glUniform1f(index, value);
    }

    public void setModelPos(DhApiVec3f modelPos) {
        setUniform(modelOffsetUniform, modelPos);
    }

    private void setUniform(int index, DhApiVec3f pos) {
        GLStateManager.glUniform3f(index, pos.x, pos.y, pos.z);
    }

}

