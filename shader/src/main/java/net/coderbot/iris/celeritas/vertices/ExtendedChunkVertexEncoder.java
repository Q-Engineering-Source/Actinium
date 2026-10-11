package net.coderbot.iris.celeritas.vertices;

import net.coderbot.iris.debug.IrisGlDebug;
import net.coderbot.iris.vertices.NormalHelper;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDynamicLiquid;
import net.minecraft.block.BlockStaticLiquid;
import dhj.embeddedt.embeddium.api.util.NormI8;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexEncoder;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.impl.VanillaLikeChunkVertex;
import org.joml.Vector3f;
import dhj.embeddedt.embeddium.api.shader.ShaderProvider;
import dhj.embeddedt.embeddium.api.shader.ShaderProviderHolder;
import dhj.embeddedt.embeddium.api.shader.vertex.BlockRenderContext;
import dhj.embeddedt.embeddium.api.shader.vertex.ContextAwareChunkVertexEncoder;
import dhj.embeddedt.embeddium.api.shader.vertex.ExtendedDataHelper;

import static com.mitchej123.lwjgl.LWJGLServiceProvider.LWJGL;

public class ExtendedChunkVertexEncoder implements ContextAwareChunkVertexEncoder {
    private final ExtendedChunkVertexType vertexType;
    private final ChunkVertexEncoder baseEncoder = VanillaLikeChunkVertex.createBaseEncoder();
    private final LwjglQuadView quad = new LwjglQuadView();
    private final Vector3f normal = new Vector3f();
    private final int midTexOffset;
    private final int tangentOffset;
    private final int normalOffset;
    private final int mcEntityOffset;
    private final int midBlockOffset;
    private final int stride;

    private BlockRenderContext context;
    private int vertexCount;
    private float uSum;
    private float vSum;

    public ExtendedChunkVertexEncoder(ExtendedChunkVertexType vertexType) {
        this.vertexType = vertexType;
        this.midTexOffset = getOffset(TerrainVertexFormatRequirements.Attribute.MID_TEX_COORD, "mc_midTexCoord");
        this.tangentOffset = getOffset(TerrainVertexFormatRequirements.Attribute.TANGENT, "at_tangent");
        this.normalOffset = getOffset(TerrainVertexFormatRequirements.Attribute.NORMAL, "iris_Normal");
        this.mcEntityOffset = getOffset(TerrainVertexFormatRequirements.Attribute.MC_ENTITY, "mc_Entity");
        this.midBlockOffset = getOffset(TerrainVertexFormatRequirements.Attribute.MID_BLOCK, "at_midBlock");
        this.stride = vertexType.getVertexFormat().getStride();
    }

    @Override
    public boolean supportsBilinearCorrection() {
        return false;
    }

    @Override
    public void prepareToRenderBlock(BlockRenderContext ctx, Block block, int metadata, short renderType, byte lightValue) {
        if (!requiresBlockContext()) {
            return;
        }
        this.context = ctx;
        if (this.mcEntityOffset >= 0) {
            ctx.blockId = resolveBlockStateId(block, metadata);
            ctx.renderType = renderType;
        }
        if (this.midBlockOffset >= 0) {
            ctx.lightValue = lightValue;
        }
    }

    @Override
    public void prepareToRenderFluid(BlockRenderContext ctx, Block block, int metadata, byte lightValue) {
        if (!requiresBlockContext()) {
            return;
        }
        this.context = ctx;
        if (this.mcEntityOffset >= 0) {
            ctx.blockId = resolveFluidBlockStateId(block, metadata);
            ctx.renderType = ExtendedDataHelper.FLUID_RENDER_TYPE;
        }
        if (this.midBlockOffset >= 0) {
            ctx.lightValue = lightValue;
        }
    }

    @Override
    public void prepareToRenderVanilla(BlockRenderContext ctx) {
        if (requiresBlockContext()) {
            this.context = ctx;
        }
    }

    @Override
    public void finishRenderingBlock() {
        if (this.context != null) {
            this.context.reset();
            this.context = null;
        }
        this.vertexCount = 0;
        this.uSum = 0.0f;
        this.vSum = 0.0f;
    }

    @Override
    public long write(long ptr, Material material, Vertex vertex, int sectionIndex) {
        if (this.midTexOffset >= 0) {
            this.uSum += vertex.u;
            this.vSum += vertex.v;
        }
        if (needsQuadData()) {
            this.vertexCount++;
        }

        vertex.rdhFactor = 0;
        this.baseEncoder.write(ptr, material, vertex, sectionIndex);

        BlockRenderContext ctx = this.context;
        if (this.mcEntityOffset >= 0 || this.midBlockOffset >= 0) {
            if (ctx == null) {
                ctx = new BlockRenderContext();
            }
        }

        if (this.mcEntityOffset >= 0) {
            LWJGL.memPutInt(ptr + this.mcEntityOffset, ((ctx.blockId + 1) << 1) | (ctx.renderType & 1));
            if (this.vertexCount == 1) {
                IrisGlDebug.logTerrainMaterialSample(
                        "encoder",
                        ctx.blockId,
                        ctx.renderType,
                        ctx.lightValue,
                        ctx.localPosX,
                        ctx.localPosY,
                        ctx.localPosZ,
                        vertex.u,
                        vertex.v
                );
            }
        }

        if (this.midBlockOffset >= 0) {
            int midBlock = ExtendedDataHelper.computeMidBlock(vertex.x, vertex.y, vertex.z, ctx.localPosX, ctx.localPosY, ctx.localPosZ);
            LWJGL.memPutInt(ptr + this.midBlockOffset, midBlock);
            LWJGL.memPutByte(ptr + this.midBlockOffset + 3L, ctx.lightValue);
        }

        if (needsQuadData() && this.vertexCount == 4) {
            this.vertexCount = 0;

            if (this.midTexOffset >= 0) {
                int midUV = ExtendedChunkVertexType.encodeMidTexture(this.uSum * 0.25f, this.vSum * 0.25f);
                writeQuadInt(ptr, this.midTexOffset, midUV);
                this.uSum = 0.0f;
                this.vSum = 0.0f;
            }

            if (this.normalOffset >= 0 || this.tangentOffset >= 0) {
                this.quad.setup(ptr, this.stride);
                NormalHelper.computeFaceNormal(this.normal, this.quad);
                if (this.normalOffset >= 0) {
                    writeQuadInt(ptr, this.normalOffset, NormI8.pack(this.normal));
                }
                if (this.tangentOffset >= 0) {
                    int tangent = NormalHelper.computeTangent(this.normal.x(), this.normal.y(), this.normal.z(), this.quad);
                    writeQuadInt(ptr, this.tangentOffset, tangent);
                }
            }
        }

        return ptr + this.stride;
    }

    private int getOffset(TerrainVertexFormatRequirements.Attribute attribute, String name) {
        return this.vertexType.requires(attribute) ? this.vertexType.getVertexFormat().getAttribute(name).getPointer() : -1;
    }

    private boolean requiresBlockContext() {
        return this.mcEntityOffset >= 0 || this.midBlockOffset >= 0;
    }

    private boolean needsQuadData() {
        return this.midTexOffset >= 0 || this.normalOffset >= 0 || this.tangentOffset >= 0;
    }

    private void writeQuadInt(long ptr, int offset, int value) {
        for (int vertexIndex = 0; vertexIndex < 4; vertexIndex++) {
            LWJGL.memPutInt(ptr - (long) vertexIndex * this.stride + offset, value);
        }
    }

    private static int resolveBlockStateId(Block block, int metadata) {
        ShaderProvider provider = ShaderProviderHolder.getProvider();
        return provider != null ? provider.getBlockStateId(block, metadata) : Block.getIdFromBlock(block);
    }

    private static int resolveFluidBlockStateId(Block block, int metadata) {
        int blockStateId = resolveBlockStateId(block, metadata);
        if (blockStateId != -1) {
            return blockStateId;
        }

        Block counterpart = liquidCounterpart(block);
        return counterpart != null ? resolveBlockStateId(counterpart, metadata) : -1;
    }

    private static Block liquidCounterpart(Block block) {
        if (block instanceof BlockStaticLiquid) {
            return Block.getBlockById(Block.getIdFromBlock(block) - 1);
        }

        if (block instanceof BlockDynamicLiquid) {
            return Block.getBlockById(Block.getIdFromBlock(block) + 1);
        }

        return null;
    }
}

