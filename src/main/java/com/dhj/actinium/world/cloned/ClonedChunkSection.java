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

public class ClonedChunkSection {
    private static final ExtendedBlockStorage EMPTY_SECTION = new ExtendedBlockStorage(0, false);

    private static final int SECTION_BLOCK_COUNT = 16 * 16 * 16;

    /** Lazily built and shared; see {@link #getUnpackedFluidData()}. */
    private static volatile Object[] EMPTY_FLUID_STATES;

    private final Short2ObjectMap<TileEntity> blockEntities;
    private final World world;

    private final ExtendedBlockStorage data;
    @Getter
    private final FluidStateStorage fluidData;

    /** Unpacked form of {@link #fluidData}; built on first use and shared by every slice. */
    private volatile Object[] unpackedFluidData;

    private final Biome[] biomeData;

    // Light arrays are copied eagerly while this section is being cloned on the main thread.
    // Chunk-builder workers read them while the light engine keeps mutating the live
    // NibbleArrays, and a torn read bakes transient darkness into the mesh (e.g. water and
    // fluidlogged quads turning black after a burst of relighting).
    private final NibbleArray[] lightData = new NibbleArray[EnumSkyBlock.values().length];

    private long lastUsedTimestamp = Long.MAX_VALUE;

    private final SectionPos sectionPos;

    ClonedChunkSection(World world, int x, int y, int z) {
        this.world = world;
        this.blockEntities = new Short2ObjectOpenHashMap<>();
        this.sectionPos = new SectionPos(x, y, z);

        Chunk chunk = world.getChunk(x, z);

        if (chunk == null) {
            throw new RuntimeException(String.format("Couldn't retrieve chunk at %d, %d", x, z));
        }

        ExtendedBlockStorage section = getChunkSection(world, chunk, y);

        if (section == Chunk.NULL_BLOCK_STORAGE/*ChunkSection.isEmpty(section)*/) {
            section = EMPTY_SECTION;
        }

        this.data = section;
        this.lightData[EnumSkyBlock.BLOCK.ordinal()] = copyLightArray(section.getBlockLight());
        this.lightData[EnumSkyBlock.SKY.ordinal()] = copyLightArray(section.getSkyLight());
        if (FluidloggedCompat.IS_LOADED) {
            this.fluidData = new FluidStateStorage(chunk, y << 4);
        } else {
            this.fluidData = null;
        }
        this.biomeData = new Biome[chunk.getBiomeArray().length];

        if (!chunk.getTileEntityMap().isEmpty()) {
            StructureBoundingBox box = new StructureBoundingBox(this.sectionPos.minX(), this.sectionPos.minY(), this.sectionPos.minZ(),
                    this.sectionPos.maxX(), this.sectionPos.maxY(), this.sectionPos.maxZ());

            for (Map.Entry<BlockPos, TileEntity> entry : chunk.getTileEntityMap().entrySet()) {
                BlockPos entityPos = entry.getKey();

                if (box.isVecInside(entityPos)) {
                    this.blockEntities.put(packLocal(entityPos.getX() & 15, entityPos.getY() & 15, entityPos.getZ() & 15), entry.getValue());
                }
            }
        }

        populateBiomeData(x, z, world);

    }

    private void populateBiomeData(int chunkX, int chunkZ, World world) {
        BlockPos.MutableBlockPos biomePos = new BlockPos.MutableBlockPos();

        chunkX *= 16;
        chunkZ *= 16;
        // Fill biome data
        for(int z = 0; z < 16; z++) {
            for(int x = 0; x < 16; x++) {
                biomePos.setPos(chunkX + x, 100, chunkZ + z);
                this.biomeData[((z & 15) << 4) | (x & 15)] = world.getBiome(biomePos);
            }
        }
    }

    public IBlockState getBlockState(int x, int y, int z) {
        return data.get(x, y, z);
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

    private static NibbleArray copyLightArray(@Nullable NibbleArray source) {
        return source == null ? null : new NibbleArray(source.getData().clone());
    }

    private static ExtendedBlockStorage getChunkSection(World world, Chunk chunk, int y) {
        ExtendedBlockStorage section = null;

        var storageArray = chunk.getBlockStorageArray();

        int storageIndex = DepthsUpdateCompat.toStorageIndex(world, y);
        if (storageIndex >= 0 && storageIndex < storageArray.length) {
            section = storageArray[storageIndex];
        }

        return section;
    }

    public long getLastUsedTimestamp() {
        return this.lastUsedTimestamp;
    }

    public void setLastUsedTimestamp(long timestamp) {
        this.lastUsedTimestamp = timestamp;
    }

    /**
     * @param x The local x-coordinate
     * @param y The local y-coordinate
     * @param z The local z-coordinate
     * @return An index which can be used to key entities or blocks within a chunk
     */
    private static short packLocal(int x, int y, int z) {
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
        if (this.fluidData.isEmpty()) {
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
