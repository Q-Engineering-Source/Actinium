package com.dhj.actinium.world.cloned;

import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import lombok.Getter;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import dhj.embeddedt.embeddium.impl.util.position.SectionPos;
import org.jetbrains.annotations.Nullable;
import com.dhj.actinium.compat.depthsupdate.DepthsUpdateCompat;
import com.dhj.actinium.compat.fluidlogged.FluidStateStorage;
import com.dhj.actinium.compat.fluidlogged.FluidloggedCompat;
import com.dhj.actinium.world.WorldSlice;

import java.util.Arrays;
import java.util.Map;

/**
 * An immutable snapshot of one chunk section for use off the main thread.
 *
 * <p>The snapshot owns an independent {@link ExtendedBlockStorage} copy rather than keeping the
 * live section's storage, so a block change made on the main thread while a chunk build is running
 * cannot alter the geometry the worker produces. Light arrays, the biome table and the tile entity
 * map are copied for the same reason. A section recycled through the cache's free queue is fully
 * re-initialized: {@link #init(SectionPos)} refuses a section that is still referenced by a task or
 * still cached, and overwrites every copied array, so no data of a previous position survives.
 */
public class ClonedChunkSection {
    private static final ExtendedBlockStorage EMPTY_SECTION = new ExtendedBlockStorage(0, false);

    private static final int SECTION_BLOCK_COUNT = 16 * 16 * 16;

    /** Lazily built and shared; see {@link #getUnpackedFluidData()}. */
    private static volatile Object[] EMPTY_FLUID_STATES;

    private final Short2ObjectMap<TileEntity> blockEntities = new Short2ObjectOpenHashMap<>();
    private final World world;

    private ExtendedBlockStorage data;

    @Getter
    private @Nullable FluidStateStorage fluidData;

    /** Unpacked form of {@link #fluidData}; rebuilt after every {@link #init(SectionPos)}. */
    private volatile Object[] unpackedFluidData;

    private final Biome[] biomeData = new Biome[16 * 16];

    // Light arrays are copied eagerly while this section is being cloned on the main thread.
    // Chunk-builder workers read them while the light engine keeps mutating the live
    // NibbleArrays, and a torn read bakes transient darkness into the mesh (e.g. water and
    // fluidlogged quads turning black after a burst of relighting).
    private final NibbleArray[] lightData = new NibbleArray[EnumSkyBlock.values().length];

    private long lastUsedTimestamp = Long.MAX_VALUE;

    private SectionPos sectionPos;

    /** Number of tasks that currently hold this section; maintained by {@link ClonedChunkSectionCache}. */
    int refs;

    /** Whether the section is still registered in the cache's position map. */
    boolean cached;

    ClonedChunkSection(World world) {
        this.world = world;
    }

    /**
     * Fills this section with a fresh snapshot of the section at {@code pos}.
     *
     * @throws IllegalStateException when the section is still cached or still referenced by a task,
     *                               because overwriting it would corrupt an in-flight build
     */
    void init(SectionPos pos) {
        if (this.refs != 0 || this.cached) {
            throw new IllegalStateException("Reusing a live ClonedChunkSection (refs=" + this.refs
                    + ", cached=" + this.cached + ")");
        }

        Chunk chunk = this.world.getChunk(pos.x(), pos.z());

        if (chunk == null) {
            throw new RuntimeException(String.format("Couldn't retrieve chunk at %d, %d", pos.x(), pos.z()));
        }

        ExtendedBlockStorage live = getChunkSection(this.world, chunk, pos.y());

        this.sectionPos = pos;
        this.data = live == null || live.isEmpty() ? EMPTY_SECTION : copyIntoOwnedStorage(live);
        copyBlockEntities(this.blockEntities, chunk, pos);
        copyBiomeData(this.biomeData, this.world, pos.x(), pos.z());
        copyLightData(live, this.lightData);
        this.fluidData = FluidloggedCompat.IS_LOADED ? new FluidStateStorage(chunk, pos.minY()) : null;
        this.unpackedFluidData = null;
    }

    /**
     * Drops every reference this section holds into the live world and clears the copied data, so a
     * section waiting in the cache's free queue cannot keep a chunk's tile entities, biomes or light
     * arrays alive. The block state copy is kept: it holds immutable block state objects only and is
     * overwritten in full by the next {@link #init(SectionPos)}.
     */
    void reset() {
        this.sectionPos = null;
        this.fluidData = null;
        this.unpackedFluidData = null;
        this.blockEntities.clear();
        Arrays.fill(this.biomeData, null);
        Arrays.fill(this.lightData, null);
    }

    private ExtendedBlockStorage copyIntoOwnedStorage(ExtendedBlockStorage live) {
        ExtendedBlockStorage target = this.data;

        if (target == null || target == EMPTY_SECTION
                || target.getYLocation() != live.getYLocation()
                || (target.getSkyLight() != null) != (live.getSkyLight() != null)) {
            target = new ExtendedBlockStorage(live.getYLocation(), live.getSkyLight() != null);
        }

        copyBlockStates(live, target);
        return target;
    }

    /**
     * Copies every block state of {@code source} into {@code target}. All 4096 entries are written,
     * so a target reused from an earlier position cannot keep stale blocks.
     */
    static void copyBlockStates(ExtendedBlockStorage source, ExtendedBlockStorage target) {
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    target.set(x, y, z, source.get(x, y, z));
                }
            }
        }
    }

    /**
     * Copies the light arrays of the live section, replacing the entries of {@code target}. An array
     * the live section does not have (block light of a sky-less section, or sky light in a world
     * without sky) is stored as {@code null} so the previous position's light cannot survive.
     */
    static void copyLightData(@Nullable ExtendedBlockStorage source, NibbleArray[] target) {
        target[EnumSkyBlock.BLOCK.ordinal()] = copyLightArray(source == null ? null : source.getBlockLight());
        target[EnumSkyBlock.SKY.ordinal()] = copyLightArray(source == null ? null : source.getSkyLight());
    }

    private static NibbleArray copyLightArray(@Nullable NibbleArray source) {
        return source == null ? null : new NibbleArray(source.getData().clone());
    }

    /** Re-fills the whole biome table of the chunk column {@code (chunkX, chunkZ)}. */
    static void copyBiomeData(Biome[] target, World world, int chunkX, int chunkZ) {
        BlockPos.MutableBlockPos biomePos = new BlockPos.MutableBlockPos();

        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                biomePos.setPos(baseX + x, 100, baseZ + z);
                target[((z & 15) << 4) | (x & 15)] = world.getBiome(biomePos);
            }
        }
    }

    /**
     * Re-fills {@code target} with the tile entities of {@code chunk} that fall inside the section at
     * {@code pos}, clearing the entries of a previous position first.
     */
    static void copyBlockEntities(Short2ObjectMap<TileEntity> target, Chunk chunk, SectionPos pos) {
        target.clear();

        if (chunk.getTileEntityMap().isEmpty()) {
            return;
        }

        StructureBoundingBox box = new StructureBoundingBox(pos.minX(), pos.minY(), pos.minZ(),
                pos.maxX(), pos.maxY(), pos.maxZ());

        for (Map.Entry<BlockPos, TileEntity> entry : chunk.getTileEntityMap().entrySet()) {
            BlockPos entityPos = entry.getKey();

            if (box.isVecInside(entityPos)) {
                target.put(packLocal(entityPos.getX() & 15, entityPos.getY() & 15, entityPos.getZ() & 15), entry.getValue());
            }
        }
    }

    public IBlockState getBlockState(int x, int y, int z) {
        return this.data.get(x, y, z);
    }

    public Biome[] getBiomeData() {
        return this.biomeData;
    }

    public TileEntity getBlockEntity(int x, int y, int z) {
        return this.blockEntities.get(packLocal(x, y, z));
    }

    public SectionPos getPosition() {
        return this.sectionPos;
    }

    public int getLightLevel(int x, int y, int z, EnumSkyBlock type) {
        NibbleArray lightArray = this.lightData[type.ordinal()];
        return lightArray != null ? lightArray.get(x, y, z) : type.defaultLightValue;
    }

    private static ExtendedBlockStorage getChunkSection(World world, Chunk chunk, int y) {
        ExtendedBlockStorage section = null;

        var storageArray = chunk.getBlockStorageArray();

        int storageIndex = DepthsUpdateCompat.toStorageIndex(world, y);
        if (storageIndex >= 0 && storageIndex < storageArray.length) {
            section = storageArray[storageIndex];
        }

        return section == Chunk.NULL_BLOCK_STORAGE ? null : section;
    }

    public long getLastUsedTimestamp() {
        return this.lastUsedTimestamp;
    }

    public void setLastUsedTimestamp(long timestamp) {
        this.lastUsedTimestamp = timestamp;
    }

    static short packLocal(int x, int y, int z) {
        return (short) (x << 8 | z << 4 | y);
    }

    /**
     * The section's fluid states as a flat 16x16x16 table, unpacked once and then shared by every slice that clones
     * this section. {@link FluidStateStorage} is already an immutable copy taken while the section was cloned on the
     * main thread, so the table never changes once built and needs no per-task copy.
     */
    public Object[] getUnpackedFluidData() {
        Object[] unpacked = this.unpackedFluidData;

        if (unpacked == null) {
            unpacked = this.buildUnpackedFluidData();
            this.unpackedFluidData = unpacked;
        }

        return unpacked;
    }

    private Object[] buildUnpackedFluidData() {
        if (this.fluidData == null || this.fluidData.isEmpty()) {
            return emptyFluidStates();
        }

        Object[] unpacked = new Object[SECTION_BLOCK_COUNT];

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    unpacked[WorldSlice.getLocalBlockIndex(x, y, z)] = this.fluidData.get(x, y, z);
                }
            }
        }

        return unpacked;
    }

    /** One shared table of empty fluid states, since most sections carry none and every slice needs the same one. */
    private static Object[] emptyFluidStates() {
        Object[] states = EMPTY_FLUID_STATES;

        if (states == null) {
            states = new Object[SECTION_BLOCK_COUNT];
            Arrays.fill(states, FluidloggedCompat.getEmptyFluidState());
            EMPTY_FLUID_STATES = states;
        }

        return states;
    }
}
