package com.dhj.actinium.compat.littletiles;

import com.creativemd.creativecore.client.rendering.model.BufferBuilderUtils;
import com.creativemd.creativecore.common.utils.mc.ColorUtils;
import com.creativemd.littletiles.client.render.cache.IRenderDataCache;
import com.creativemd.littletiles.client.render.cache.LayeredRenderBufferCache;
import com.creativemd.littletiles.common.tileentity.TileEntityLittleTiles;
import com.dhj.actinium.render.terrain.ActiniumWorldRenderer;
import com.dhj.actinium.render.terrain.compile.VintageChunkBuildContext;
import com.dhj.actinium.world.WorldSlice;
import com.dhj.actinium.mixin.mod.littletiles.mixinterface.AccessorAmbientOcclusionFace;
import com.dhj.actinium.mixin.mod.littletiles.mixinterface.AccessorVertexLighterFlat;
import dhj.embeddedt.embeddium.api.shader.buffer.BufferBuilderExtension;
import dhj.embeddedt.embeddium.api.shader.buffer.VanillaQuadContext;
import dhj.embeddedt.embeddium.api.shader.vertex.ExtendedDataHelper;
import net.coderbot.iris.block_rendering.BlockRenderingSettings;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.client.model.pipeline.BlockInfo;
import net.minecraftforge.client.model.pipeline.LightUtil;
import net.minecraftforge.client.model.pipeline.VertexLighterFlat;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static net.minecraft.block.material.Material.LAVA;
import static net.minecraft.block.material.Material.WATER;

/**
 * Compatibility for {@code LittleTiles} (mod id {@code littletiles}).
 *
 * <p>LittleTiles does not render its tiles through the block's baked model. Each
 * {@link TileEntityLittleTiles} owns a {@link LayeredRenderBufferCache}: raw vertex bytes in
 * vanilla {@code DefaultVertexFormats.BLOCK} layout, relative to the tile entity's own 16^3
 * section, built asynchronously by LittleTiles' {@code RenderingThread}. Vanilla Minecraft only
 * appends those bytes to the chunk upload buffer inside {@code ChunkRenderDispatcher.uploadChunk}
 * (an ASM hook installed by LittleTiles), a method Actinium's chunk pipeline never calls, so every
 * non-full-block LittleTiles block is invisible (issue #133).
 *
 * <p>Vanilla collects the LittleTiles tile entities of a chunk purely by
 * {@code instanceof TileEntityLittleTiles} (LittleTiles' ASM hook in {@code RenderChunk}), so the
 * tile entities are gathered here by scanning the section being meshed instead of reusing the
 * meshing task's TESR-bearing tile entity lists. Those lists cannot serve as the source: LittleTiles
 * registers its TESR only for the two <em>tick-rendering</em> tile entity variants
 * ({@code TileEntityLittleTilesRendered}/{@code TileEntityLittleTilesTickingRendered}), while
 * ordinary static tiles live in plain {@code TileEntityLittleTiles} without any TESR.
 *
 * <p>The vanilla {@code ViewFrustum} still exists under Actinium (created with zero render
 * distance, so it only holds placeholder render chunks), which is enough for LittleTiles' own
 * cache-build queue to function. What is missing is (a) consuming the caches during section
 * meshing and (b) translating "cache build finished" into a celeritas section rebuild, since the
 * vanilla {@code RenderChunk.setNeedsUpdate} flag LittleTiles flips is never polled.
 */
public final class LittleTilesCompat {
    private LittleTilesCompat() {
    }

    /** Returns whether LittleTiles must keep AO in the shader's separate vertex-alpha channel. */
    public static boolean shouldWriteSeparateAo() {
        return BlockRenderingSettings.INSTANCE.shouldUseSeparateAo();
    }

    /** Captures the current quad's colors before CreativeCore overwrites its vanilla lighting. */
    public static int[] snapshotCurrentQuadColors(BufferBuilder buffer) {
        int[] colors = new int[4];
        for (int index = 1; index <= 4; index++) {
            colors[index - 1] = BufferBuilderUtils.get(buffer, buffer.getColorIndex(index));
        }
        return colors;
    }

    /** Reads the AO scalar for each recent quad vertex from the lighting path CreativeCore used. */
    public static float[] snapshotSeparateAoFactors(Object ambientOcclusionFace,
                                                    VertexLighterFlat lighter,
                                                    BakedQuad quad,
                                                    int[] colorsBeforeOverwrite) {
        if (!shouldWriteSeparateAo()) {
            return null;
        }

        if (ambientOcclusionFace != null) {
            if (!(ambientOcclusionFace instanceof AccessorAmbientOcclusionFace accessor)) {
                throw new IllegalStateException("LittleTiles AO face is missing Actinium's accessor");
            }
            float[] vertexFactors = accessor.actinium$getVertexColorMultiplier();
            float[] bufferOrderFactors = new float[4];
            for (int index = 1; index <= 4; index++) {
                bufferOrderFactors[index - 1] = vertexFactors[4 - index];
            }
            return bufferOrderFactors;
        }

        if (lighter != null) {
            if (!(lighter instanceof AccessorVertexLighterFlat accessor)) {
                throw new IllegalStateException("LittleTiles Forge light pipeline is missing Actinium's accessor");
            }
            BlockInfo blockInfo = accessor.actinium$getBlockInfo();
            int originalMultiplier = blockInfo.getColorMultiplier(quad.getTintIndex());
            float[] factors = new float[4];
            for (int i = 0; i < factors.length; i++) {
                factors[i] = extractAoFactor(colorsBeforeOverwrite[i], originalMultiplier);
            }
            return factors;
        }

        return null;
    }

    /** Moves CreativeCore's CPU-baked AO factor out of RGB and into alpha for the active pack. */
    public static void moveCurrentQuadAoToAlpha(BufferBuilder buffer,
                                                BakedQuad quad,
                                                float[] aoFactors) {
        int[] colorsAfterOverwrite = snapshotCurrentQuadColors(buffer);
        float directionalDiffuse = BlockRenderingSettings.INSTANCE.shouldDisableDirectionalShading()
                && quad.shouldApplyDiffuseLighting()
                ? LightUtil.diffuseLight(quad.getFace())
                : 1.0F;
        float[] alphaFactors = aoFactors != null ? Arrays.copyOf(aoFactors, aoFactors.length) : null;
        if (alphaFactors != null && directionalDiffuse > 0.000001F && directionalDiffuse != 1.0F) {
            for (int i = 0; i < alphaFactors.length; i++) {
                alphaFactors[i] = Math.max(0.0F, Math.min(1.0F, alphaFactors[i] / directionalDiffuse));
            }
        }

        if (alphaFactors != null) {
            for (int index = 1; index <= 4; index++) {
                int outputColor = colorsAfterOverwrite[index - 1];
                float bakedShading = aoFactors[index - 1];
                float ao = alphaFactors[index - 1];
                int red = ColorUtils.getRed(outputColor);
                int green = ColorUtils.getGreen(outputColor);
                int blue = ColorUtils.getBlue(outputColor);
                if (bakedShading > 0.000001F) {
                    red = Math.min(255, Math.round(red / bakedShading));
                    green = Math.min(255, Math.round(green / bakedShading));
                    blue = Math.min(255, Math.round(blue / bakedShading));
                }
                int alpha = Math.max(0, Math.min(255, Math.round(ColorUtils.getAlpha(outputColor) * ao)));
                buffer.putColorRGBA(buffer.getColorIndex(index), red, green, blue, alpha);
            }
        }
    }

    private static float extractAoFactor(int packedColor, int originalMultiplier) {
        float factor = ColorUtils.getRed(originalMultiplier) > 0
                ? (float) ColorUtils.getRed(packedColor) / ColorUtils.getRed(originalMultiplier)
                : ColorUtils.getGreen(originalMultiplier) > 0
                        ? (float) ColorUtils.getGreen(packedColor) / ColorUtils.getGreen(originalMultiplier)
                        : ColorUtils.getBlue(originalMultiplier) > 0
                                ? (float) ColorUtils.getBlue(packedColor) / ColorUtils.getBlue(originalMultiplier)
                                : 1.0f;
        return Float.isFinite(factor) ? factor : 1.0f;
    }

    /** Clears the temporary cube state after LittleTiles finishes writing one face. */
    public static void endEmbeddedBlockRender(BufferBuilder buffer) {
        if (buffer instanceof BufferBuilderExtension extension) {
            extension.actinium$setActiveQuadContext(null);
        }
    }

    /** Snapshots source builder contexts before LittleTiles retains its buffer. */
    public static void captureCachedQuadContexts(LittleTilesQuadContextCarrier cache, BufferBuilder builder) {
        if (!(builder instanceof BufferBuilderExtension extension)) {
            throw new IllegalStateException("LittleTiles cache builder is missing Actinium's quad-context extension");
        }
        cache.actinium$setQuadContexts(extension.actinium$copyQuadContexts());
    }

    /** Mirrors LittleTiles' first-then-second raw byte concatenation for shader contexts. */
    public static void combineCachedQuadContexts(IRenderDataCache output, IRenderDataCache first, IRenderDataCache second) {
        if (output == null) {
            return;
        }

        List<VanillaQuadContext> firstContexts = cachedQuadContexts(first);
        List<VanillaQuadContext> secondContexts = cachedQuadContexts(second);
        if (firstContexts.isEmpty() && secondContexts.isEmpty()) {
            return;
        }
        if (!(output instanceof LittleTilesQuadContextCarrier carrier)) {
            throw new IllegalStateException("LittleTiles combined cache is missing its quad-context carrier");
        }

        List<VanillaQuadContext> merged = new ArrayList<>(firstContexts.size() + secondContexts.size());
        merged.addAll(firstContexts);
        merged.addAll(secondContexts);
        carrier.actinium$setQuadContexts(merged);
    }

    /** Copies contexts when LittleTiles wraps cached data in a GPU buffer link. */
    public static void copyCachedQuadContexts(IRenderDataCache output, IRenderDataCache source) {
        List<VanillaQuadContext> contexts = cachedQuadContexts(source);
        if (contexts.isEmpty()) {
            return;
        }
        if (!(output instanceof LittleTilesQuadContextCarrier carrier)) {
            throw new IllegalStateException("LittleTiles copied cache is missing its quad-context carrier");
        }
        carrier.actinium$setQuadContexts(contexts);
    }

    private static List<VanillaQuadContext> cachedQuadContexts(IRenderDataCache cache) {
        return cache instanceof LittleTilesQuadContextCarrier carrier
                ? carrier.actinium$getQuadContexts()
                : List.of();
    }

    /** Starts a context for the constituent block state used by LittleTiles' face renderer. */
    public static void beginEmbeddedBlockRender(BufferBuilder buffer, IBlockState state, BlockPos pos) {
        if (buffer instanceof BufferBuilderExtension extension) {
            short renderType = state.getMaterial() == WATER || state.getMaterial() == LAVA
                    ? ExtendedDataHelper.FLUID_RENDER_TYPE
                    : ExtendedDataHelper.BLOCK_RENDER_TYPE;
            extension.actinium$setActiveQuadContext(new VanillaQuadContext(
                    pos.getX() & 15,
                    pos.getY() & 15,
                    pos.getZ() & 15,
                    state,
                    renderType
            ));
        }
    }

    /**
     * Appends the cached vertex data of every loaded {@link TileEntityLittleTiles} in the section
     * to the vanilla-format per-layer buffers of the in-flight section build. Called from the
     * meshing task right before {@code convertVanillaDataToCeleritasData}, so the appended quads
     * are converted to celeritas vertex format like any other vanilla-fallback geometry.
     */
    public static void appendSectionGeometry(VintageChunkBuildContext buildContext) {
        WorldSlice slice = buildContext.getWorldSlice();
        int minX = buildContext.getOffX();
        int minY = buildContext.getOffY();
        int minZ = buildContext.getOffZ();

        for (int y = minY; y < minY + 16; y++) {
            for (int z = minZ; z < minZ + 16; z++) {
                for (int x = minX; x < minX + 16; x++) {
                    TileEntity blockEntity = slice.getBlockEntity(x, y, z);
                    if (blockEntity instanceof TileEntityLittleTiles te && te.hasLoaded()) {
                        appendTileEntity(buildContext, te);
                    }
                }
            }
        }
    }

    private static void appendTileEntity(VintageChunkBuildContext buildContext, TileEntityLittleTiles te) {
        // Picks up light/neighbour dirty flags and re-queues the cache build, mirroring what
        // the vanilla uploadChunk hook does on every chunk upload. The chunk argument is
        // unused by LittleTiles beyond its signature.
        te.updateQuadCache(null);
        synchronized (te.render) {
            LayeredRenderBufferCache cache = te.render.getBufferCache();
            for (BlockRenderLayer layer : VintageChunkBuildContext.LAYERS) {
                IRenderDataCache data = cache.get(layer.ordinal());
                if (data == null) {
                    continue;
                }
                ByteBuffer source = data.byteBuffer();
                if (source == null || data.vertexCount() == 0) {
                    continue;
                }
                int sourceVertexCount = data.vertexCount();
                if ((sourceVertexCount & 3) != 0) {
                    throw new IllegalStateException("LittleTiles cache contains an incomplete quad at " + te.getPos());
                }
                int sourceQuadCount = sourceVertexCount / 4;
                List<VanillaQuadContext> contexts = cachedQuadContexts(data);
                if (contexts.size() != sourceQuadCount) {
                    throw new IllegalStateException("LittleTiles cache context count " + contexts.size()
                            + " does not match its " + sourceQuadCount + " quads at " + te.getPos()
                            + " in layer " + layer);
                }
                BufferBuilder buffer = buildContext.getBufferForLayer(layer);
                if (!(buffer instanceof BufferBuilderExtension extension)) {
                    throw new IllegalStateException("Actinium chunk buffer is missing its quad-context extension");
                }
                // Same append mechanics LittleTiles itself uses on the vanilla upload buffer:
                // grow first, then raw-copy the bytes and bump the vertex count.
                BufferBuilderUtils.growBufferSmall(buffer, data.length() + buffer.getVertexFormat().getSize());
                BufferBuilderUtils.addBuffer(buffer, source.duplicate(), data.length(), data.vertexCount());
                extension.actinium$appendQuadContexts(contexts);
            }
        }
    }

    /**
     * Handles a finished asynchronous LittleTiles cache build (fired from
     * {@code TileEntityRenderManager.finishBuildingCache} on LittleTiles' rendering thread).
     * Vanilla would mark the render chunk for an update through {@code RenderChunk.setNeedsUpdate},
     * a flag the celeritas pipeline never polls, so schedule the section rebuild directly.
     */
    public static void onCacheBuildFinished(TileEntityLittleTiles te) {
        if (te.getWorld() != Minecraft.getMinecraft().world) {
            // SubWorld tile entities belong to animated structures, which render through
            // LittleTiles' own animation path rather than section geometry.
            return;
        }
        ActiniumWorldRenderer renderer = ActiniumWorldRenderer.instanceNullable();
        if (renderer == null) {
            return;
        }
        BlockPos pos = te.getPos();
        renderer.getRenderSectionManager().scheduleRebuild(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4, false);
    }
}
