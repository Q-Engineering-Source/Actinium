package net.coderbot.iris.celeritas;

import com.gtnewhorizons.angelica.glsm.GLDebug;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCache;
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
import dhj.embeddedt.embeddium.impl.gl.shader.ShaderBindingContext;
import dhj.embeddedt.embeddium.impl.gl.shader.ShaderType;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import dhj.embeddedt.embeddium.impl.render.chunk.shader.ChunkShaderInterface;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexType;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.KHRDebug;

import java.util.EnumMap;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

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

    /**
     * Builds the key a terrain program is saved under. The attribute bindings have to match the ones
     * {@link GlProgram.Builder#link} links with exactly: a binary restored with a different attribute layout
     * would feed the chunk vertex data into the wrong slots.
     */
    @Nullable
    private static ProgramBinaryCache.Key cacheKey(CeleritasTerrainPipeline.PassInfo passInfo, ChunkVertexType vertexType) {
        if (!ProgramBinaryCache.isEnabled()) return null;
        final String vertex = passInfo.sources().get(PatchShaderType.VERTEX).orElse(null);
        final String geometry = passInfo.sources().get(PatchShaderType.GEOMETRY).orElse(null);
        final String fragment = passInfo.sources().get(PatchShaderType.FRAGMENT).orElse(null);
        if (vertex == null || fragment == null) return null;

        final ProgramBinaryCache.Key key = ProgramBinaryCache.key();
        int attrIndex = 0;
        for (var attr : vertexType.getVertexFormat().getAttributes()) {
            key.attribute(attr.getName(), attrIndex++);
        }
        key.stage(ShaderType.VERTEX.id, vertex);
        if (geometry != null) key.stage(ShaderType.GEOM.id, geometry);
        key.stage(ShaderType.FRAGMENT.id, fragment);
        return key;
    }

    /** A program restored from the saved-program cache instead of linked from source. */
    private static final class SavedProgram extends GlProgram<IrisCeleritasChunkShaderInterface> {
        SavedProgram(int program, Function<ShaderBindingContext, IrisCeleritasChunkShaderInterface> factory) {
            super(program, factory);
        }
    }

    @Nullable
    private GlProgram<IrisCeleritasChunkShaderInterface> createShader(IrisTerrainPass pass, CeleritasTerrainPipeline pipeline, ChunkVertexType vertexType) {
        final CeleritasTerrainPipeline.PassInfo passInfo = pipeline.getPassInfo(pass);
        final String name = "iris:celeritas-chunk-" + pass.getName();
        final BlendModeOverride blendOverride = passInfo.blendModeOverride();
        final List<BufferBlendOverride> bufferOverrides = passInfo.bufferBlendOverrides();
        final Function<ShaderBindingContext, IrisCeleritasChunkShaderInterface> factory = context -> {
            IrisGlDebug.logCeleritasProgram(pass.getName(), ((GlObject) context).handle(), vertexType.getVertexFormat().getAttributes());
            return new IrisCeleritasChunkShaderInterface(((GlObject) context).handle(), context, pipeline, pass.isShadow(), passInfo.alphaTestOverride(), passInfo.alphaReference(), blendOverride, bufferOverrides, pipeline.getCustomUniforms());
        };

        final ProgramBinaryCache.Key cacheKey = cacheKey(passInfo, vertexType);
        if (cacheKey != null) {
            final int saved = ProgramBinaryCache.load(cacheKey);
            if (saved != 0) {
                try {
                    GLDebug.nameObject(KHRDebug.GL_PROGRAM, saved, name);
                    return new SavedProgram(saved, factory);
                } catch (RuntimeException | Error failure) {
                    try {
                        GLStateManager.glDeleteProgram(saved);
                    } catch (RuntimeException | Error cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                    throw failure;
                }
            }
        }

        final List<GlShader> shaders = new ArrayList<>();
        GlProgram<IrisCeleritasChunkShaderInterface> program = null;
        Throwable buildFailure = null;
        try {
            final GlShader vertShader = createVertexShader(pass, pipeline);
            if (vertShader != null) shaders.add(vertShader);
            final GlShader geomShader = createGeometryShader(pass, pipeline);
            if (geomShader != null) shaders.add(geomShader);
            final GlShader fragShader = createFragmentShader(pass, pipeline);
            if (fragShader != null) shaders.add(fragShader);
            if (vertShader == null || fragShader == null) {
                return null;
            }

            try (GlProgram.Builder builder = GlProgram.builder(name)) {
                builder.attachShader(vertShader);
                if (geomShader != null) {
                    builder.attachShader(geomShader);
                }
                builder.attachShader(fragShader);

                // Bind all attributes from the vertex format (includes base + Iris extended attributes)
                int attrIndex = 0;
                for (var attr : vertexType.getVertexFormat().getAttributes()) {
                    builder.bindAttribute(attr.getName(), attrIndex++);
                }

                program = builder.link(factory);
            }
            if (cacheKey != null) {
                ProgramBinaryCache.save(cacheKey, program.handle());
            }
            return program;
        } catch (RuntimeException | Error failure) {
            buildFailure = failure;
            if (program != null) {
                try {
                    program.delete();
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        } finally {
            final Throwable shaderCleanupFailure = deleteShaders(shaders);
            if (shaderCleanupFailure != null) {
                if (buildFailure != null) {
                    buildFailure.addSuppressed(shaderCleanupFailure);
                } else {
                    if (program != null) {
                        try {
                            program.delete();
                        } catch (RuntimeException | Error programCleanupFailure) {
                            shaderCleanupFailure.addSuppressed(programCleanupFailure);
                        }
                    }
                    if (shaderCleanupFailure instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    throw (Error) shaderCleanupFailure;
                }
            }
        }
    }

    private static Throwable deleteShaders(List<GlShader> shaders) {
        Throwable failure = null;
        for (GlShader shader : shaders) {
            try {
                shader.delete();
            } catch (RuntimeException | Error cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }
        return failure;
    }

    /**
     * Create shaders for all Iris terrain passes of one pipeline.
     */
    private EnumMap<IrisTerrainPass, GlProgram<IrisCeleritasChunkShaderInterface>> createShaders(CeleritasTerrainPipeline pipeline, RenderPassConfiguration<?> configuration) {
        final EnumMap<IrisTerrainPass, GlProgram<IrisCeleritasChunkShaderInterface>> programs = new EnumMap<>(IrisTerrainPass.class);
        try {
            if (pipeline != null) {
                for (IrisTerrainPass pass : IrisTerrainPass.VALUES) {
                    if (pass.isShadow() && !pipeline.hasShadowPass()) {
                        programs.put(pass, null);
                        continue;
                    }
                    // Resolved per pass so the cache key and the attribute bindings describe the same vertex format.
                    programs.put(pass, createShader(pass, pipeline, pass.toTerrainPass(configuration).vertexType()));
                }
            }
            return programs;
        } catch (RuntimeException | Error failure) {
            for (GlProgram<IrisCeleritasChunkShaderInterface> program : programs.values()) {
                if (program == null) {
                    continue;
                }
                try {
                    program.delete();
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    /**
     * Builds every terrain program of a pipeline and drops it again, filling the saved-program cache for a
     * pipeline that is never rendered with.
     *
     * <p>Celeritas terrain programs are linked through {@link GlProgram} rather than through the gbuffer
     * program builder, so a full compile of the pack only covers them when it is asked to build them
     * here as well.</p>
     *
     * @param pipeline the pipeline to build the programs of, or null for a program set without terrain
     *                 shaders
     * @param vertexType the vertex format the programs are linked for; the caller owns it because only it
     *                   knows which format the chunk renderer will use
     */
    public void compileAndDiscard(CeleritasTerrainPipeline pipeline, ChunkVertexType vertexType) {
        if (pipeline == null) return;
        for (IrisTerrainPass pass : IrisTerrainPass.VALUES) {
            if (pass.isShadow() && !pipeline.hasShadowPass()) continue;
            final GlProgram<IrisCeleritasChunkShaderInterface> program = createShader(pass, pipeline, vertexType);
            if (program != null) program.delete();
        }
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
