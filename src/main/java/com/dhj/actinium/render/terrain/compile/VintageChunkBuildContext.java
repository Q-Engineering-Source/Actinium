package com.dhj.actinium.render.terrain.compile;

import dhj.embeddedt.embeddium.api.shader.buffer.BufferBuilderExtension;
import dhj.embeddedt.embeddium.api.shader.ShaderProvider;
import dhj.embeddedt.embeddium.api.shader.ShaderProviderHolder;
import dhj.embeddedt.embeddium.api.debug.RenderDebugHooksHolder;
import dhj.embeddedt.embeddium.api.shader.buffer.VanillaQuadContext;
import dhj.embeddedt.embeddium.api.shader.vertex.BlockRenderContext;
import dhj.embeddedt.embeddium.api.shader.vertex.ContextAwareChunkVertexEncoder;
import dhj.embeddedt.embeddium.api.shader.vertex.ExtendedDataHelper;
import lombok.Getter;
import net.coderbot.iris.block_rendering.BlockMaterialMapping;
import net.coderbot.iris.block_rendering.BlockRenderingSettings;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import dhj.embeddedt.embeddium.impl.model.quad.properties.ModelQuadFacing;
import dhj.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildBuffers;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.ChunkBuildContext;
import dhj.embeddedt.embeddium.impl.render.chunk.compile.buffers.ChunkModelBuilder;
import dhj.embeddedt.embeddium.impl.render.chunk.data.MinecraftBuiltRenderSectionData;
import dhj.embeddedt.embeddium.impl.render.chunk.sprite.SpriteTransparencyLevel;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.material.Material;
import dhj.embeddedt.embeddium.impl.render.chunk.vertex.format.ChunkVertexEncoder;
import dhj.embeddedt.embeddium.impl.util.QuadUtil;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.dhj.actinium.compat.architecturecraft.ArchitectureCraftCompat;
import com.dhj.actinium.compat.farmersfuturedelight.FarmersFutureDelightCompat;
import com.dhj.actinium.compat.snowrealmagic.SnowRealMagicCompat;
import com.dhj.actinium.texture.TextureMapExtension;
import com.dhj.actinium.world.WorldSlice;
import com.dhj.actinium.world.cloned.DeferredSlice;
import com.dhj.actinium.render.terrain.compile.light.LightDataCache;
import com.dhj.actinium.render.terrain.compile.pipeline.VintageBlockRenderer;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static net.minecraft.block.material.Material.LAVA;
import static net.minecraft.block.material.Material.WATER;

import static com.mitchej123.lwjgl.LWJGLServiceProvider.LWJGL;

public class VintageChunkBuildContext extends ChunkBuildContext {
    public static final BlockRenderLayer[] LAYERS = BlockRenderLayer.values();
    private final TextureMapExtension textureAtlas;
    private final net.minecraft.client.renderer.BufferBuilder[] worldRenderers = new net.minecraft.client.renderer.BufferBuilder[LAYERS.length];
    private final boolean[] usedWorldRenderers = new boolean[LAYERS.length];
    @Getter
    private int offX, offY, offZ;
    @Getter
    private WorldSlice worldSlice;
    @Getter
    private final VintageBlockRenderer blockRenderer;
    private final RenderPassConfiguration<?> renderPassConfiguration;
    private final LightDataCache lightDataCache;
    private final boolean useRenderPassOptimization;
    private final BlockRenderContext vanillaBlockRenderContext = new BlockRenderContext();

    // Per-task buffers owned by the deferred scheduler while this context renders a captured block.
    // They never replace `buffers`: cleanup() still releases the context's own buffers.
    private ChunkBuildBuffers handoffBuffers;

    // Blocks of the section being built that a build thread may not mesh. The mask is written by the
    // scan of the build that owns this context and read again while it renders, so no per-build
    // allocation is needed.
    private final long[] deferredMask = new long[64];

    public VintageChunkBuildContext(WorldClient world, RenderPassConfiguration renderPassConfiguration) {
        super(renderPassConfiguration);
        this.renderPassConfiguration = renderPassConfiguration;
        this.worldSlice = new WorldSlice(world);
        this.lightDataCache = new LightDataCache(this.worldSlice);
        this.blockRenderer = new VintageBlockRenderer(this, lightDataCache);
        this.textureAtlas = (TextureMapExtension) Minecraft.getMinecraft().getTextureMapBlocks();
        this.useRenderPassOptimization = ActiniumRuntime.options().performance.useRenderPassOptimization;
    }

    /**
     * Replaces the slice this context renders from and returns the one it used before. The light
     * cache follows the new slice, so a context that outlives its build task can render a later
     * snapshot without reading the previous slice's blocks.
     *
     * <p>The deferred capture hands in {@link DeferredSlice} snapshots; this context renders chunk
     * builds, which need the concrete {@link WorldSlice} for {@code copyData} and the block-access
     * proxy, so it accepts the interface and keeps the concrete type internally.</p>
     */
    public DeferredSlice swapWorldSlice(DeferredSlice worldSlice) {
        WorldSlice previous = this.worldSlice;
        this.worldSlice = requireWorldSlice(worldSlice);
        this.lightDataCache.rebind(this.worldSlice);
        return previous;
    }

    private static WorldSlice requireWorldSlice(DeferredSlice slice) {
        if (slice instanceof WorldSlice worldSlice) {
            return worldSlice;
        }

        throw new IllegalArgumentException("VintageChunkBuildContext renders from WorldSlice snapshots, got "
                + slice.getClass().getName());
    }

    /**
     * Points this context at the scheduler-owned buffers of a deferred handoff. The context keeps
     * ownership of its own {@code buffers}; the handoff buffers are released by the task that
     * provided them.
     */
    public void setHandoffBuffers(@Nullable ChunkBuildBuffers buffers) {
        this.handoffBuffers = buffers;
    }

    /** Buffers the current render must write to: the handoff buffers when set, otherwise the context's own. */
    public ChunkBuildBuffers getRenderBuffers() {
        ChunkBuildBuffers buffers = this.handoffBuffers;
        return buffers != null ? buffers : this.buffers;
    }

    /**
     * The section pass mask the deferred scan fills in. Every bit is a block the build thread may not
     * mesh, so the render-thread capture has to draw it.
     */
    public long[] getDeferredMask() {
        return this.deferredMask;
    }

    public void setupTranslation(int x, int y, int z) {
        this.lightDataCache.reset(x, y, z);

        this.offX = x;
        this.offY = y;
        this.offZ = z;
    }

    public net.minecraft.client.renderer.BufferBuilder getBufferForLayer(BlockRenderLayer layer) {
        var builder = this.worldRenderers[layer.ordinal()];
        if (builder == null) {
            builder = new ChunkBufferBuilder(131072, this, layer);
            this.worldRenderers[layer.ordinal()] = builder;
        }
        if (!this.usedWorldRenderers[layer.ordinal()]) {
            builder.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK);
            builder.setTranslation(-this.offX, -this.offY, -this.offZ);
            this.usedWorldRenderers[layer.ordinal()] = true;
        }
        return builder;
    }

    public void convertVanillaDataToCeleritasData(ChunkBuildBuffers buffers) {
        var renderers = this.worldRenderers;
        var used = this.usedWorldRenderers;
        for (int i = 0; i < renderers.length; i++) {
            if(!used[i]) {
                continue;
            }
            var bufferBuilder = Objects.requireNonNull(renderers[i]);
            bufferBuilder.finishDrawing();
            used[i] = false;
            ByteBuffer rawBuffer = bufferBuilder.getByteBuffer();
            BufferBuilderExtension extension = bufferBuilder instanceof BufferBuilderExtension value ? value : null;
            BlockRenderLayer layer = LAYERS[i];
            List<VanillaQuadContext> quadContexts = extension != null
                    ? extension.actinium$consumeQuadContexts()
                    : Collections.emptyList();
            var material = buffers.getRenderPassConfiguration().getMaterialForRenderType(layer);
            copyBlockData(rawBuffer, buffers, material, layer, quadContexts);
        }
    }

    public void beginVanillaBlockRender(BufferBuilder buffer, BlockPos pos, IBlockState state,
                                        BlockRenderLayer layer) {
        // Set before the extension check: the dispatcher hook reads this flag from the buffer even
        // when the buffer carries no quad-context extension.
        if (buffer instanceof ChunkBufferBuilder chunkBuffer) {
            chunkBuffer.setForceVanillaRendering(
                    SnowRealMagicCompat.shouldForceVanillaRender(state.getBlock())
                            || ArchitectureCraftCompat.shouldForceVanillaRender(state.getBlock()));
        }

        if (!(buffer instanceof BufferBuilderExtension extension)) {
            return;
        }

        boolean waterloggedWater = FarmersFutureDelightCompat.usesWaterIdentity(state, layer);
        Block shaderBlock = waterloggedWater ? Blocks.WATER : state.getBlock();
        int shaderMetadata = waterloggedWater
                ? 0
                : BlockMaterialMapping.resolveShaderMetadata(state, pos, this.worldSlice, state.getBlock().getMetaFromState(state));
        int shaderBlockId = resolveShaderBlockStateId(shaderBlock, shaderMetadata);
        if (!waterloggedWater) {
            int nbtBlockId = resolveBlockNbtId(state, pos);
            if (nbtBlockId != -1) {
                shaderBlockId = nbtBlockId;
            }
        }
        short renderType = waterloggedWater
                || state.getMaterial() == WATER || state.getMaterial() == LAVA
                ? ExtendedDataHelper.FLUID_RENDER_TYPE
                : ExtendedDataHelper.BLOCK_RENDER_TYPE;

        extension.actinium$setActiveQuadContext(new VanillaQuadContext(
                pos.getX() & 15,
                pos.getY() & 15,
                pos.getZ() & 15,
                shaderBlockId,
                renderType,
                (byte) state.getLightValue(this.worldSlice, pos)
        ));
    }

    public void beginVanillaFluidRender(BufferBuilder buffer, BlockPos pos, IBlockState state) {
        if (buffer instanceof ChunkBufferBuilder chunkBuffer) {
            // A Fluidlogged fluid can be a MODEL state, so the dispatcher hook has to be told that
            // this render belongs to the vanilla path.
            chunkBuffer.setForceVanillaRendering(true);
        }

        if (!(buffer instanceof BufferBuilderExtension extension)) {
            return;
        }

        int effectiveMetadata = BlockMaterialMapping.resolveShaderMetadata(state, pos, this.worldSlice, state.getBlock().getMetaFromState(state));
        extension.actinium$setActiveQuadContext(new VanillaQuadContext(
                pos.getX() & 15,
                pos.getY() & 15,
                pos.getZ() & 15,
                resolveShaderBlockStateId(state.getBlock(), effectiveMetadata),
                ExtendedDataHelper.FLUID_RENDER_TYPE,
                (byte) state.getLightValue(this.worldSlice, pos)
        ));
    }

    public void endVanillaRender(BufferBuilder buffer) {
        if (buffer instanceof ChunkBufferBuilder chunkBuffer) {
            chunkBuffer.setForceVanillaRendering(false);
        }
        if (buffer instanceof BufferBuilderExtension extension) {
            extension.actinium$setActiveQuadContext(null);
        }
    }

    @Override
    public void cleanup() {
        super.cleanup();
        this.handoffBuffers = null;
        this.worldSlice.reset();
        this.discardVanillaBuffers();
    }

    /**
     * Drops the vertices a vanilla render appended to the per-layer buffers without encoding them.
     *
     * <p>The deferred capture reads the bytes of one block out of these buffers and hands them to a
     * build thread, so it has to start every capture from empty buffers. The immediate path instead
     * drains them through {@link #convertVanillaDataToCeleritasData(ChunkBuildBuffers)}.</p>
     */
    public void discardVanillaBuffers() {
        for (int i = 0; i < LAYERS.length; i++) {
            if (!this.usedWorldRenderers[i]) {
                continue;
            }

            BufferBuilder builder = Objects.requireNonNull(this.worldRenderers[i]);

            // A render that failed may already have ended the drawing itself; finishing it twice
            // would replace the failure that is on its way to the crash report.
            if (builder instanceof BufferBuilderExtension extension) {
                if (extension.actinium$isDrawing()) {
                    builder.finishDrawing();
                }
                extension.actinium$consumeQuadContexts();
            } else {
                builder.finishDrawing();
            }

            builder.reset();
            this.usedWorldRenderers[i] = false;
        }
    }

    @Override
    public void destroy() {
        // cleanup() returns the snapshot to the section cache and drops the vanilla render buffers;
        // skipping it here would leave the cloned sections referenced by a discarded context.
        cleanup();
        super.destroy();
    }

    private Material selectMaterial(Material material, TextureAtlasSprite sprite, ChunkVertexEncoder.Vertex[] quad) {
        SpriteTransparencyLevel transparencyLevel = null;
        if (sprite != null && sprite.getClass() == TextureAtlasSprite.class && !sprite.hasAnimationMetadata() && this.useRenderPassOptimization) {
            transparencyLevel = ((SpriteTransparencyLevel.Holder)sprite).embeddium$getTransparencyLevel();
        }
        return VanillaQuadMaterial.select(material, this.renderPassConfiguration, transparencyLevel, quad);
    }

    private static final int BLOCK_VERTEX_FORMAT_SIZE;

    static {
        var format = DefaultVertexFormats.BLOCK;
        int size = 0;
        for (int i = 0; i < format.getElementCount(); i++) {
            size += format.getElement(i).getSize();
        }
        BLOCK_VERTEX_FORMAT_SIZE = size;
    }

    /**
     * Encodes vanilla-format vertices into the mesh buffers.
     *
     * <p>The immediate path calls this for the whole layer buffer once a section is meshed; the
     * finish step of a deferred mesh calls it once per captured block, with a view of exactly that
     * block's bytes.</p>
     *
     * @param source vertices in {@code DefaultVertexFormats.BLOCK} layout, read from its position to its limit
     * @param buffers mesh buffers the encoded quads are written to
     * @param material material of the source layer
     * @param layer vanilla render layer the vertices were produced for
     * @param quadContexts shader metadata of the quads, in quad order
     */
    public void copyBlockData(ByteBuffer source, ChunkBuildBuffers buffers, Material material, BlockRenderLayer layer, List<VanillaQuadContext> quadContexts) {
        int vsize = BLOCK_VERTEX_FORMAT_SIZE;
        int numQuads = source.limit() / (vsize * 4);
        long ptr = LWJGL.memAddress(source);
        var quad = ChunkVertexEncoder.Vertex.uninitializedQuad();
        var animatedSpritesList = ((MinecraftBuiltRenderSectionData<TextureAtlasSprite, TileEntity>)buffers.getSectionContextBundle()).animatedSprites;

        for(int q = 0; q < numQuads; q++) {
            float uSum = 0, vSum = 0;
            for(int v = 0; v < 4; v++) {
                var vertex = quad[v];
                vertex.x = LWJGL.memGetFloat(ptr);
                vertex.y = LWJGL.memGetFloat(ptr + 4);
                vertex.z = LWJGL.memGetFloat(ptr + 8);
                vertex.color = LWJGL.memGetInt(ptr + 12);
                vertex.u = LWJGL.memGetFloat(ptr + 16);
                vertex.v = LWJGL.memGetFloat(ptr + 20);
                uSum += vertex.u;
                vSum += vertex.v;
                vertex.light = LWJGL.memGetInt(ptr + 24);
                ptr += vsize;
            }
            TextureAtlasSprite sprite = this.textureAtlas.celeritas$findFromUV(uSum * 0.25f, vSum * 0.25f);
            if (sprite != null && sprite.hasAnimationMetadata()) {
                animatedSpritesList.add(sprite);
            }
            int trueNormal = QuadUtil.calculateNormal(quad);
            for (int v = 0; v < 4; v++) {
                var vertex = quad[v];
                vertex.vanillaNormal = trueNormal;
                vertex.trueNormal = trueNormal;
            }
            ModelQuadFacing facing = QuadUtil.findNormalFace(trueNormal);
            VanillaQuadContext quadContext = q < quadContexts.size() ? quadContexts.get(q) : null;
            if (quadContext != null) {
                quadContext = resolveDeferredBlockContext(quadContext);
            }
            boolean isFluidQuad = quadContext != null && quadContext.renderType() == ExtendedDataHelper.FLUID_RENDER_TYPE;
            Material correctMaterial = isFluidQuad
                    ? VanillaQuadMaterial.selectFluid(material, buffers.getRenderPassConfiguration(), layer)
                    : selectMaterial(material, sprite, quad);

            ChunkModelBuilder builder = buffers.get(correctMaterial);
            ContextAwareChunkVertexEncoder encoder = this.prepareVanillaEncoder(builder, quadContext);
            builder.getVertexBuffer(facing).push(quad, correctMaterial);
            if (encoder != null) {
                encoder.finishRenderingBlock();
            }
        }
    }

    private VanillaQuadContext resolveDeferredBlockContext(VanillaQuadContext context) {
        IBlockState deferredState = context.deferredBlockState();
        if (deferredState == null) {
            return context;
        }

        BlockPos pos = new BlockPos(
                this.offX + context.localPosX(),
                this.offY + context.localPosY(),
                this.offZ + context.localPosZ()
        );
        Block block = deferredState.getBlock();
        int metadata = block.getMetaFromState(deferredState);
        int shaderBlockId = resolveShaderBlockStateId(block,
                BlockMaterialMapping.resolveShaderMetadata(deferredState, pos, this.worldSlice, metadata));
        byte lightValue = (byte) deferredState.getLightValue(this.worldSlice, pos);
        return context.withResolvedBlockState(shaderBlockId, lightValue);
    }

    private ContextAwareChunkVertexEncoder prepareVanillaEncoder(ChunkModelBuilder builder, VanillaQuadContext quadContext) {
        if (!(builder.getEncoder() instanceof ContextAwareChunkVertexEncoder encoder) || quadContext == null) {
            return null;
        }

        this.vanillaBlockRenderContext.set(
                quadContext.localPosX(),
                quadContext.localPosY(),
                quadContext.localPosZ(),
                quadContext.blockStateId(),
                quadContext.renderType(),
                quadContext.lightValue()
        );
        RenderDebugHooksHolder.logTerrainMaterialSample(
                "vanilla-fallback",
                quadContext.blockStateId(),
                quadContext.renderType(),
                quadContext.lightValue(),
                quadContext.localPosX(),
                quadContext.localPosY(),
                quadContext.localPosZ(),
                0.0f,
                0.0f
        );
        encoder.prepareToRenderVanilla(this.vanillaBlockRenderContext);
        return encoder;
    }

    private static int resolveShaderBlockStateId(Block block, int metadata) {
        ShaderProvider provider = ShaderProviderHolder.getProvider();
        return provider != null ? provider.getBlockStateId(block, metadata) : Block.getIdFromBlock(block);
    }

    private int resolveBlockNbtId(IBlockState state, BlockPos pos) {
        if (BlockRenderingSettings.INSTANCE.getBlockNbtMap() == null || !state.getBlock().hasTileEntity(state)) {
            return -1;
        }

        TileEntity tileEntity = this.worldSlice.getTileEntity(pos);
        int metadata = state.getBlock().getMetaFromState(state);
        return BlockRenderingSettings.INSTANCE.resolveBlockNbtId(state.getBlock(), metadata, tileEntity);
    }
}

