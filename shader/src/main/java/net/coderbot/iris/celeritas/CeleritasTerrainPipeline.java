package net.coderbot.iris.celeritas;

import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import lombok.Getter;
import lombok.experimental.Accessors;
import net.coderbot.iris.Iris;
import net.coderbot.iris.gl.blending.AlphaTest;
import net.coderbot.iris.gl.blending.AlphaTestFunction;
import net.coderbot.iris.gl.blending.AlphaTestOverride;
import net.coderbot.iris.gl.blending.BlendModeOverride;
import net.coderbot.iris.gl.blending.BufferBlendOverride;
import net.coderbot.iris.gl.framebuffer.GlFramebuffer;
import net.coderbot.iris.shaderpack.loading.ProgramId;
import net.coderbot.iris.gl.program.ProgramImages;
import net.coderbot.iris.gl.program.ProgramSamplers;
import net.coderbot.iris.gl.program.ProgramUniforms;
import net.coderbot.iris.pipeline.PatchedShaderPrinter;
import net.coderbot.iris.pipeline.transform.PatchShaderType;
import net.coderbot.iris.rendertarget.RenderTargets;
import net.coderbot.iris.gl.state.FogMode;
import net.coderbot.iris.uniforms.CommonUniforms;
import net.coderbot.iris.uniforms.builtin.BuiltinReplacementUniforms;
import net.coderbot.iris.shaderpack.ProgramSource;
import net.coderbot.iris.uniforms.custom.CustomUniforms;
import net.coderbot.iris.celeritas.vertices.TerrainVertexFormatRequirements;
import dhj.embeddedt.embeddium.api.shader.ShaderProvider;
import dhj.embeddedt.embeddium.api.shader.ShaderProviderHolder;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;

public class CeleritasTerrainPipeline {
    private final EnumMap<IrisTerrainPass, PassInfo> passInfoMap = new EnumMap<>(IrisTerrainPass.class);

    @Getter
    private final CustomUniforms customUniforms;

    @Getter
    private final TerrainVertexFormatRequirements vertexFormatRequirements;

    private final IntFunction<ProgramSamplers> createTerrainSamplers;
    private final IntFunction<ProgramSamplers> createShadowSamplers;

    private final IntFunction<ProgramImages> createTerrainImages;
    private final IntFunction<ProgramImages> createShadowImages;

    @Getter
    @Accessors(fluent = true)
    public static final class PassInfo {
        private final EnumMap<PatchShaderType, Optional<String>> sources = new EnumMap<>(PatchShaderType.class);
        private GlFramebuffer framebuffer;
        private AlphaTestOverride alphaTestOverride;
        private BlendModeOverride blendModeOverride;
        private List<BufferBlendOverride> bufferBlendOverrides;
        private float alphaReference;

        private PassInfo() {
            for (PatchShaderType type : PatchShaderType.VALUES) {
                sources.put(type, Optional.empty());
            }
        }

        private void setSource(PatchShaderType type, @Nullable String source) {
            sources.put(type, Optional.ofNullable(source));
        }
    }

    public CeleritasTerrainPipeline(
            IntFunction<ProgramSamplers> createTerrainSamplers,
            IntFunction<ProgramSamplers> createShadowSamplers,
            IntFunction<ProgramImages> createTerrainImages,
            IntFunction<ProgramImages> createShadowImages,
            CustomUniforms customUniforms,
            Optional<ProgramSource> terrainSource,
            Optional<ProgramSource> translucentSource,
            Optional<ProgramSource> shadowSource,
            Optional<ProgramSource> shadowTranslucentSource,
            CompletableFuture<Map<PatchShaderType, String>> terrainFuture,
            CompletableFuture<Map<PatchShaderType, String>> translucentFuture,
            CompletableFuture<Map<PatchShaderType, String>> shadowFuture,
            CompletableFuture<Map<PatchShaderType, String>> shadowTranslucentFuture,
            RenderTargets renderTargets,
            ImmutableSet<Integer> flippedAfterPrepare,
            ImmutableSet<Integer> flippedAfterTranslucent,
            @Nullable GlFramebuffer shadowFramebuffer
    ) {
        this.customUniforms = customUniforms;
        this.createTerrainSamplers = createTerrainSamplers;
        this.createShadowSamplers = createShadowSamplers;
        this.createTerrainImages = createTerrainImages;
        this.createShadowImages = createShadowImages;

        // Build program source map (local - only needed during construction)
        final EnumMap<IrisTerrainPass, Optional<ProgramSource>> gbufferProgramSource = new EnumMap<>(IrisTerrainPass.class);
        gbufferProgramSource.put(IrisTerrainPass.GBUFFER_SOLID, terrainSource);
        gbufferProgramSource.put(IrisTerrainPass.GBUFFER_CUTOUT, terrainSource);
        gbufferProgramSource.put(IrisTerrainPass.GBUFFER_TRANSLUCENT, translucentSource.isPresent() ? translucentSource : terrainSource);
        gbufferProgramSource.put(IrisTerrainPass.SHADOW, shadowSource);
        gbufferProgramSource.put(IrisTerrainPass.SHADOW_CUTOUT, shadowSource);
        gbufferProgramSource.put(IrisTerrainPass.SHADOW_TRANSLUCENT, shadowTranslucentSource.isPresent() ? shadowTranslucentSource : shadowSource);

        // Initialize PassInfo, framebuffers, blend modes, and alpha in single pass
        for (IrisTerrainPass pass : IrisTerrainPass.VALUES) {
            PassInfo passInfo = new PassInfo();
            passInfoMap.put(pass, passInfo);

            // Set up framebuffer, blend mode, and buffer blend overrides
            final ProgramId programId = switch (pass) {
                case GBUFFER_TRANSLUCENT -> ProgramId.Water;
                case SHADOW_TRANSLUCENT -> ProgramId.ShadowWater;
                case SHADOW, SHADOW_CUTOUT -> ProgramId.Shadow;
                default -> ProgramId.Terrain;
            };

            if (pass.isShadow()) {
                passInfo.framebuffer = shadowFramebuffer;
                passInfo.blendModeOverride = programId.getBlendModeOverride();
                passInfo.bufferBlendOverrides = Collections.emptyList();
            } else if (renderTargets != null) {
                ImmutableSet<Integer> flipped = pass == IrisTerrainPass.GBUFFER_TRANSLUCENT ? flippedAfterTranslucent : flippedAfterPrepare;

                Optional<ProgramSource> programSource = gbufferProgramSource.get(pass);
                programSource.ifPresentOrElse(source -> {
                    passInfo.framebuffer = renderTargets.createGbufferFramebuffer(flipped, source.getDirectives().getDrawBuffers());
                    passInfo.blendModeOverride = source.getDirectives().getBlendModeOverride().orElse(programId.getBlendModeOverride());

                    passInfo.bufferBlendOverrides = new ArrayList<>();
                    source.getDirectives().getBufferBlendOverrides().forEach(information -> {
                        int index = Ints.indexOf(source.getDirectives().getDrawBuffers(), information.getIndex());
                        if (index > -1) {
                            passInfo.bufferBlendOverrides.add(new BufferBlendOverride(index, information.getBlendMode()));
                        }
                    });
                }, () -> {
                    passInfo.framebuffer = renderTargets.createGbufferFramebuffer(flipped, new int[] {0});
                    passInfo.blendModeOverride = programId.getBlendModeOverride();
                    passInfo.bufferBlendOverrides = Collections.emptyList();
                });
            } else {
                passInfo.blendModeOverride = programId.getBlendModeOverride();
                passInfo.bufferBlendOverrides = Collections.emptyList();
            }

            // Set alpha reference. The shader pack directive wins; otherwise match Iris/Sodium defaults
            // for terrain passes so translucent water does not inherit a stale vanilla alpha test.
            passInfo.alphaReference = switch (pass) {
                case GBUFFER_CUTOUT, SHADOW_CUTOUT -> 0.1f;
                case GBUFFER_TRANSLUCENT, SHADOW_TRANSLUCENT -> 0.0001f;
                default -> 0.0f;
            };
            passInfo.alphaTestOverride = resolveAlphaTestOverride(
                    gbufferProgramSource.get(pass).flatMap(source -> source.getDirectives().getAlphaTestOverride()),
                    pass);
        }

        // Process and apply shader sources
        List<String> transformedVertexSources = new ArrayList<>();
        processShaderFuture(terrainFuture, terrainSource, transformedVertexSources, passInfoMap.get(IrisTerrainPass.GBUFFER_SOLID), passInfoMap.get(IrisTerrainPass.GBUFFER_CUTOUT));
        processShaderFuture(translucentFuture, translucentSource, transformedVertexSources, passInfoMap.get(IrisTerrainPass.GBUFFER_TRANSLUCENT));
        processShaderFuture(shadowFuture, shadowSource, transformedVertexSources, passInfoMap.get(IrisTerrainPass.SHADOW), passInfoMap.get(IrisTerrainPass.SHADOW_CUTOUT));
        processShaderFuture(shadowTranslucentFuture, shadowTranslucentSource.isPresent() ? shadowTranslucentSource : shadowSource, transformedVertexSources, passInfoMap.get(IrisTerrainPass.SHADOW_TRANSLUCENT));
        this.vertexFormatRequirements = TerrainVertexFormatRequirements.analyze(transformedVertexSources);
        updateVertexFormatRequirements(this.vertexFormatRequirements);
    }

    private void processShaderFuture(@Nullable CompletableFuture<Map<PatchShaderType, String>> future, Optional<ProgramSource> source,
                                     List<String> transformedVertexSources, PassInfo... targets) {
        if (future == null || source.isEmpty()) {
            return;
        }
        final String sourceName = source.get().getName();
        try {
            final Map<PatchShaderType, String> result = future.join();
            final String vertexSource = result.get(PatchShaderType.VERTEX);
            transformedVertexSources.add(vertexSource == null ? "" : vertexSource);
            for (PassInfo target : targets) {
                target.setSource(PatchShaderType.VERTEX, vertexSource);
                target.setSource(PatchShaderType.GEOMETRY, result.get(PatchShaderType.GEOMETRY));
                target.setSource(PatchShaderType.FRAGMENT, result.get(PatchShaderType.FRAGMENT));
                // The image-store extraction turns terrain image writes into a compute pre-pass that
                // replays the pass's vertices; the shadow voxelizer consumes it for the shadow pass.
                target.setSource(PatchShaderType.COMPUTE, result.get(PatchShaderType.COMPUTE));
            }
            PatchedShaderPrinter.debugPatchedShaders(sourceName + "_celeritas",
                result.get(PatchShaderType.VERTEX), result.get(PatchShaderType.GEOMETRY), result.get(PatchShaderType.FRAGMENT));
        } catch (Exception e) {
            Iris.logger.error("Failed to transform shader for Celeritas: {}", sourceName, e);
            throw new RuntimeException("Shader transformation failed for " + sourceName, e);
        }
    }

    private static void updateVertexFormatRequirements(TerrainVertexFormatRequirements requirements) {
        ShaderProvider provider = ShaderProviderHolder.getProvider();
        if (provider instanceof IrisCeleritasShaderProvider celeritasProvider) {
            celeritasProvider.setVertexFormatRequirements(requirements);
        }
    }

    public PassInfo getPassInfo(IrisTerrainPass pass) {
        return passInfoMap.get(pass);
    }

    public ProgramUniforms.Builder initUniforms(int programId) {
        final ProgramUniforms.Builder uniforms = ProgramUniforms.builder("<celeritas shaders>", programId);

        CommonUniforms.addDynamicUniforms(uniforms, FogMode.PER_VERTEX);
        customUniforms.assignTo(uniforms);

        BuiltinReplacementUniforms.addBuiltinReplacementUniforms(uniforms);
        return uniforms;
    }

    public boolean hasShadowPass() {
        return createShadowSamplers != null;
    }

    public ProgramSamplers initTerrainSamplers(int programId) {
        return createTerrainSamplers.apply(programId);
    }

    public ProgramSamplers initShadowSamplers(int programId) {
        return createShadowSamplers.apply(programId);
    }

    public ProgramImages initTerrainImages(int programId) {
        return createTerrainImages.apply(programId);
    }

    public ProgramImages initShadowImages(int programId) {
        return createShadowImages.apply(programId);
    }

    /**
     * {@return the compute pre-pass the shadow pass's image writes were extracted into}
     *
     * <p>Shaderpacks that write images from the shadow terrain stage get a compute program that
     * replays the pass's vertices; the SDL GPU backend's shadow voxelizer drives it from the chunk
     * render lists. Absent when the pack writes no images there.</p>
     */
    public Optional<String> getShadowVoxelizationComputeSource() {
        final PassInfo shadow = passInfoMap.get(IrisTerrainPass.SHADOW);
        if (shadow == null) return Optional.empty();
        return shadow.sources.get(PatchShaderType.COMPUTE);
    }

    static AlphaTestOverride resolveAlphaTestOverride(Optional<AlphaTestOverride> shaderPackOverride, IrisTerrainPass pass) {
        return shaderPackOverride.orElseGet(() -> switch (pass) {
            case GBUFFER_CUTOUT, SHADOW_CUTOUT -> new AlphaTestOverride(new AlphaTest(AlphaTestFunction.GREATER, 0.1f));
            case GBUFFER_TRANSLUCENT, SHADOW_TRANSLUCENT -> new AlphaTestOverride(new AlphaTest(AlphaTestFunction.GREATER, 0.0001f));
            default -> AlphaTestOverride.OFF;
        });
    }
}
