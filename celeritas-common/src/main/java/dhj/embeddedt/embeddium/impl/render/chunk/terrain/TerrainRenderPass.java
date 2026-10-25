package dhj.embeddedt.embeddium.impl.render.chunk.terrain;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.experimental.Accessors;
import dhj.embeddedt.embeddium.impl.gl.attribute.GlVertexFormat;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.sorting.ChunkPrimitiveType;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexType;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A terrain render pass corresponds to a draw call to render some subset of terrain geometry. Passes are generally
 * used for fixed configuration that will not change from quad to quad and allow for optimizations to be made
 * within the terrain shader code at compile time (e.g. omitting the fragment discard conditional entirely on the solid pass).
 * <p></p>
 * Geometry that shares the same terrain render pass may still be able to specify some more dynamic properties. See {@link Material}
 * for more information.
 */
@Accessors(fluent = true)
@EqualsAndHashCode
public class TerrainRenderPass {
    /**
     * Identifies the fixed-function terrain behavior represented by a render pass.
     */
    public enum Semantic {
        SOLID,
        CUTOUT,
        TRANSLUCENT,
        WATER;

        private static Semantic fromLegacyFlags(boolean useReverseOrder, boolean fragmentDiscard) {
            if (useReverseOrder) {
                return TRANSLUCENT;
            }
            return fragmentDiscard ? CUTOUT : SOLID;
        }
    }

    /**
     * The friendly name of this render pass.
     */
    @EqualsAndHashCode.Exclude
    private final String name;

    /**
     * A callback used to set up/clear GPU pipeline state.
     */
    private final PipelineState pipelineState;

    /**
     * Whether sections on this render pass should be rendered farthest-to-nearest, rather than nearest-to-farthest.
     */
    private final boolean useReverseOrder;
    /**
     * The stable semantic used by shader and pipeline integrations to classify this pass.
     */
    private final Semantic semantic;
    /**
     * Whether drawing this render pass updates the depth buffer.
     */
    private final boolean writesDepth;
    /**
     * Whether fragment alpha testing should be enabled for this render pass.
     */
    private final boolean fragmentDiscard;
    /**
     * Whether this render pass wants to opt in to translucency sorting if enabled.
     */
    private final boolean useTranslucencySorting;
    /**
     * Whether this render pass has no lightmap texture.
     */
    private final boolean hasNoLightmap;

    private final @NotNull ChunkPrimitiveType primitiveType;
    private final @NotNull ChunkVertexType vertexType;

    /**
     * Identifies one region tessellation. Passes can only share a region's geometry arena while their vertex
     * buffers are interpreted identically, so the key covers everything the vertex-array setup reads: the vertex
     * format, the primitive type and whether the pass draws from its own index buffer rather than the shared one.
     */
    public record TessellationKey(GlVertexFormat vertexFormat, ChunkPrimitiveType primitiveType, boolean sorted) {}

    @Getter
    @EqualsAndHashCode.Exclude
    private final TessellationKey tessellationKey;

    private final Map<String, String> extraDefines;

    /**
     * The depth-mask state active before this pass starts drawing.
     */
    @EqualsAndHashCode.Exclude
    private boolean previousDepthMaskEnabled;

    public TerrainRenderPass(String name,
                             PipelineState pipelineState,
                             boolean useReverseOrder,
                             boolean fragmentDiscard,
                             boolean useTranslucencySorting,
                             boolean hasNoLightmap,
                             @NotNull ChunkVertexType vertexType,
                             @NotNull ChunkPrimitiveType primitiveType,
                             Map<String, String> extraDefines) {
        this(name,
                pipelineState,
                useReverseOrder,
                fragmentDiscard,
                useTranslucencySorting,
                hasNoLightmap,
                vertexType,
                primitiveType,
                extraDefines,
                Semantic.fromLegacyFlags(useReverseOrder, fragmentDiscard),
                !useReverseOrder);
    }

    /**
     * Constructs a terrain render pass with an explicit depth-write policy.
     *
     * @param useReverseOrder whether sections are rendered farthest-to-nearest
     * @param fragmentDiscard whether fragment alpha testing is enabled
     */
    public TerrainRenderPass(String name,
                             PipelineState pipelineState,
                             boolean useReverseOrder,
                             boolean fragmentDiscard,
                             boolean useTranslucencySorting,
                             boolean hasNoLightmap,
                             @NotNull ChunkVertexType vertexType,
                             @NotNull ChunkPrimitiveType primitiveType,
                             Map<String, String> extraDefines,
                             boolean writesDepth) {
        this(name,
                pipelineState,
                useReverseOrder,
                fragmentDiscard,
                useTranslucencySorting,
                hasNoLightmap,
                vertexType,
                primitiveType,
                extraDefines,
                Semantic.fromLegacyFlags(useReverseOrder, fragmentDiscard),
                writesDepth);
    }

    /**
     * Constructs a terrain render pass with explicit semantic and depth-write policies.
     *
     * @param useReverseOrder whether sections are rendered farthest-to-nearest
     * @param fragmentDiscard whether fragment alpha testing is enabled
     * @param semantic the fixed-function behavior represented by this pass
     */
    public TerrainRenderPass(String name,
                             PipelineState pipelineState,
                             boolean useReverseOrder,
                             boolean fragmentDiscard,
                             boolean useTranslucencySorting,
                             boolean hasNoLightmap,
                             @NotNull ChunkVertexType vertexType,
                             @NotNull ChunkPrimitiveType primitiveType,
                             Map<String, String> extraDefines,
                             @NotNull Semantic semantic,
                             boolean writesDepth) {
        if(name == null || name.length() == 0) {
            throw new IllegalArgumentException("Name not specified for terrain pass");
        }
        Objects.requireNonNull(vertexType);
        Objects.requireNonNull(primitiveType);
        Objects.requireNonNull(semantic);

        this.name = name;
        this.pipelineState = pipelineState != null ? pipelineState : PipelineState.DEFAULT;
        this.useReverseOrder = useReverseOrder;
        this.semantic = semantic;
        this.writesDepth = writesDepth;
        this.fragmentDiscard = fragmentDiscard;
        this.useTranslucencySorting = useTranslucencySorting;
        this.hasNoLightmap = hasNoLightmap;
        this.primitiveType = primitiveType;
        this.vertexType = vertexType;
        this.tessellationKey = new TessellationKey(vertexType.getVertexFormat(), primitiveType, useTranslucencySorting);
        this.extraDefines = extraDefines != null ? Map.copyOf(extraDefines) : Map.of();
    }

    public static TerrainRenderPassBuilder builder() {
        return new TerrainRenderPassBuilder();
    }

    public String name() {
        return this.name;
    }

    public boolean isReverseOrder() {
        return this.useReverseOrder;
    }

    public Semantic semantic() {
        return this.semantic;
    }

    public boolean writesDepth() {
        return this.writesDepth;
    }

    public boolean isSorted() {
        return this.useTranslucencySorting;
    }

    public boolean hasNoLightmap() {
        return this.hasNoLightmap;
    }

    public void startDrawing() {
        this.previousDepthMaskEnabled = GLStateManager.getDepthState().isMaskEnabled();
        this.pipelineState.setup();
        GLStateManager.glDepthMask(this.writesDepth);
    }

    public void endDrawing() {
        try {
            this.pipelineState.clear();
        } finally {
            GLStateManager.glDepthMask(this.previousDepthMaskEnabled);
        }
    }

    public boolean supportsFragmentDiscard() {
        return this.fragmentDiscard;
    }

    public ChunkPrimitiveType primitiveType() {
        return this.primitiveType;
    }

    public ChunkVertexType vertexType() {
        return this.vertexType;
    }

    public Map<String, String> extraDefines() {
        return this.extraDefines;
    }

    @Override
    public String toString() {
        return "TerrainRenderPass[name=" + this.name + "]";
    }

    public interface PipelineState {
        PipelineState DEFAULT = new PipelineState() {
            @Override
            public void setup() {

            }

            @Override
            public void clear() {

            }
        };

        void setup();
        void clear();
    }

    public static final class TerrainRenderPassBuilder {
        private String name;
        private PipelineState pipelineState;
        private boolean useReverseOrder;
        private Semantic semantic;
        private Boolean writesDepth;
        private boolean fragmentDiscard;
        private boolean useTranslucencySorting;
        private boolean hasNoLightmap;
        private ChunkPrimitiveType primitiveType;
        private ChunkVertexType vertexType;
        private final Map<String, String> extraDefines = new HashMap<>();

        public TerrainRenderPassBuilder name(String name) {
            this.name = name;
            return this;
        }

        public TerrainRenderPassBuilder pipelineState(PipelineState pipelineState) {
            this.pipelineState = pipelineState;
            return this;
        }

        public TerrainRenderPassBuilder useReverseOrder(boolean useReverseOrder) {
            this.useReverseOrder = useReverseOrder;
            return this;
        }

        public TerrainRenderPassBuilder semantic(Semantic semantic) {
            this.semantic = semantic;
            return this;
        }

        public TerrainRenderPassBuilder writesDepth(boolean writesDepth) {
            this.writesDepth = writesDepth;
            return this;
        }

        public TerrainRenderPassBuilder fragmentDiscard(boolean fragmentDiscard) {
            this.fragmentDiscard = fragmentDiscard;
            return this;
        }

        public TerrainRenderPassBuilder useTranslucencySorting(boolean useTranslucencySorting) {
            this.useTranslucencySorting = useTranslucencySorting;
            return this;
        }

        public TerrainRenderPassBuilder hasNoLightmap(boolean hasNoLightmap) {
            this.hasNoLightmap = hasNoLightmap;
            return this;
        }

        public TerrainRenderPassBuilder primitiveType(ChunkPrimitiveType primitiveType) {
            this.primitiveType = primitiveType;
            return this;
        }

        public TerrainRenderPassBuilder vertexType(ChunkVertexType vertexType) {
            this.vertexType = vertexType;
            return this;
        }

        public TerrainRenderPassBuilder extraDefines(Map<String, String> extraDefines) {
            this.extraDefines.clear();
            if (extraDefines != null) {
                this.extraDefines.putAll(extraDefines);
            }
            return this;
        }

        public TerrainRenderPass build() {
            return new TerrainRenderPass(
                    this.name,
                    this.pipelineState,
                    this.useReverseOrder,
                    this.fragmentDiscard,
                    this.useTranslucencySorting,
                    this.hasNoLightmap,
                    this.vertexType,
                    this.primitiveType,
                    this.extraDefines,
                    this.semantic != null
                            ? this.semantic
                            : Semantic.fromLegacyFlags(this.useReverseOrder, this.fragmentDiscard),
                    this.writesDepth != null ? this.writesDepth : !this.useReverseOrder
            );
        }
    }
}
