package net.coderbot.iris.celeritas;

import net.coderbot.iris.Iris;
import net.coderbot.iris.debug.IrisGlDebug;
import net.coderbot.iris.gl.blending.BlendModeOverride;
import net.coderbot.iris.gl.blending.BufferBlendOverride;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import net.coderbot.iris.pipeline.transform.PatchShaderType;
import net.coderbot.iris.shadows.ShadowRenderingState;
import dhj.embeddedt.embeddium.impl.gl.GlObject;
import dhj.embeddedt.embeddium.impl.gl.shader.GlProgram;
import dhj.embeddedt.embeddium.impl.gl.shader.GlShader;
import dhj.embeddedt.embeddium.impl.gl.shader.ShaderType;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import dhj.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderInterface;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class IrisCeleritasChunkProgramOverrides {
    // Programs embed per-pipeline state (custom uniforms, pass info), so each world pipeline gets
    // its own cached program set. Portal-style mods (BetterPortals) alternate dimensions within a
    // frame; rebuilding on every switch would stall the render loop.
    private final Map<WorldRenderingPipeline, EnumMap<IrisTerrainPass, GlProgram<IrisCeleritasChunkShaderInterface>>> programsPerPipeline = new IdentityHashMap<>();
    private int versionCounterForShaderReload = -1;

    @Nullable
    private GlShader createVertexShader(IrisTerrainPass pass, CeleritasTerrainPipeline pipeline) {
        final CeleritasTerrainPipeline.PassInfo info = pipeline.getPassInfo(pass);
        final Optional<String> source = info.sources().get(PatchShaderType.VERTEX);

        return source.map(s -> new GlShader(ShaderType.VERTEX,
            "iris:celeritas-terrain-" + pass.toString().toLowerCase(Locale.ROOT) + ".vsh", s))
            .orElse(null);
    }

    @Nullable
    private GlShader createGeometryShader(IrisTerrainPass pass, CeleritasTerrainPipeline pipeline) {
        final CeleritasTerrainPipeline.PassInfo info = pipeline.getPassInfo(pass);
        final Optional<String> source = info.sources().get(PatchShaderType.GEOMETRY);

        return source.map(s -> new GlShader(ShaderType.GEOM,
            "iris:celeritas-terrain-" + pass.toString().toLowerCase(Locale.ROOT) + ".gsh", s))
            .orElse(null);
    }

    @Nullable
    private GlShader createFragmentShader(IrisTerrainPass pass, CeleritasTerrainPipeline pipeline) {
        final CeleritasTerrainPipeline.PassInfo info = pipeline.getPassInfo(pass);
        final Optional<String> source = info.sources().get(PatchShaderType.FRAGMENT);

        return source.map(s -> new GlShader(ShaderType.FRAGMENT,
            "iris:celeritas-terrain-" + pass.toString().toLowerCase(Locale.ROOT) + ".fsh", s))
            .orElse(null);
    }

    @Nullable
    private GlProgram<IrisCeleritasChunkShaderInterface> createShader(IrisTerrainPass pass, CeleritasTerrainPipeline pipeline, RenderPassConfiguration<?> configuration) {
        final GlShader vertShader = createVertexShader(pass, pipeline);
        final GlShader geomShader = createGeometryShader(pass, pipeline);
        final GlShader fragShader = createFragmentShader(pass, pipeline);

        if (vertShader == null || fragShader == null) {
            if (vertShader != null) vertShader.delete();
            if (geomShader != null) geomShader.delete();
            if (fragShader != null) fragShader.delete();
            return null;
        }

        try {
            final GlProgram.Builder builder = GlProgram.builder("iris:celeritas-chunk-" + pass.getName());

            builder.attachShader(vertShader);
            if (geomShader != null) {
                builder.attachShader(geomShader);
            }
            builder.attachShader(fragShader);

            // Bind all attributes from the vertex format (includes base + Iris extended attributes)
            final var vertexType =pass.toTerrainPass(configuration).vertexType();
            int attrIndex = 0;
            for (var attr : vertexType.getVertexFormat().getAttributes()) {
                builder.bindAttribute(attr.getName(), attrIndex++);
            }

            final CeleritasTerrainPipeline.PassInfo passInfo = pipeline.getPassInfo(pass);
            final BlendModeOverride blendOverride = passInfo.blendModeOverride();
            final List<BufferBlendOverride> bufferOverrides = passInfo.bufferBlendOverrides();

            return builder.link(context -> {
                IrisGlDebug.logCeleritasProgram(pass.getName(), ((GlObject) context).handle(), vertexType.getVertexFormat().getAttributes());
                return new IrisCeleritasChunkShaderInterface(((GlObject) context).handle(), context, pipeline, pass.isShadow(), passInfo.alphaTestOverride(), passInfo.alphaReference(), blendOverride, bufferOverrides, pipeline.getCustomUniforms());
            });
        } finally {
            vertShader.delete();
            if (geomShader != null) geomShader.delete();
            fragShader.delete();
        }
    }

    /**
     * Create shaders for all Iris terrain passes of one pipeline.
     */
    private EnumMap<IrisTerrainPass, GlProgram<IrisCeleritasChunkShaderInterface>> createShaders(CeleritasTerrainPipeline pipeline, RenderPassConfiguration<?> configuration) {
        final EnumMap<IrisTerrainPass, GlProgram<IrisCeleritasChunkShaderInterface>> programs = new EnumMap<>(IrisTerrainPass.class);
        if (pipeline != null) {
            for (IrisTerrainPass pass : IrisTerrainPass.VALUES) {
                if (pass.isShadow() && !pipeline.hasShadowPass()) {
                    programs.put(pass, null);
                    continue;
                }
                programs.put(pass, createShader(pass, pipeline, configuration));
            }
        }
        return programs;
    }

    @Nullable
    public GlProgram<? extends ChunkShaderInterface> getProgramOverride(TerrainRenderPass pass, RenderPassConfiguration<?> configuration) {
        if (versionCounterForShaderReload != Iris.getPipelineManager().getVersionCounterForSodiumShaderReload()) {
            versionCounterForShaderReload = Iris.getPipelineManager().getVersionCounterForSodiumShaderReload();
            deleteShaders();
        }

        // Drop programs whose dimension pipeline has been evicted or destroyed since the last
        // lookup; their GL objects would otherwise leak until the next full shader reload.
        programsPerPipeline.keySet().removeIf(candidate -> !Iris.getPipelineManager().isPipelineCached(candidate));

        final WorldRenderingPipeline worldPipeline = Iris.getPipelineManager().getPipelineNullable();
        if (worldPipeline == null) {
            return null;
        }

        final CeleritasTerrainPipeline celeritasPipeline = worldPipeline.getCeleritasTerrainPipeline();
        final EnumMap<IrisTerrainPass, GlProgram<IrisCeleritasChunkShaderInterface>> programs =
            programsPerPipeline.computeIfAbsent(worldPipeline, p -> createShaders(celeritasPipeline, configuration));

        final boolean isShadow = ShadowRenderingState.areShadowsCurrentlyBeingRendered();
        if (isShadow) {
            if (celeritasPipeline != null && !celeritasPipeline.hasShadowPass()) {
                throw new IllegalStateException("Shadow program requested, but shader pack has no shadow pass");
            }
        }

        return programs.get(IrisTerrainPass.fromTerrainPass(pass, isShadow));
    }

    public void deleteShaders() {
        for (EnumMap<IrisTerrainPass, GlProgram<IrisCeleritasChunkShaderInterface>> programs : programsPerPipeline.values()) {
            for (GlProgram<?> program : programs.values()) {
                if (program != null) {
                    program.delete();
                }
            }
        }
        programsPerPipeline.clear();
    }
}
