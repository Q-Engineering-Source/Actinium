package com.dhj.actinium.world;

import git.jbredwards.fluidlogged_api.api.util.FluidState;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeColorHelper;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.structure.StructureBoundingBox;
import net.minecraftforge.client.model.pipeline.LightUtil;
import net.minecraftforge.fml.common.Optional;
import dhj.embeddedt.embeddium.impl.util.position.SectionPos;
import org.jetbrains.annotations.Nullable;
import com.dhj.actinium.compat.depthsupdate.DepthsUpdateCompat;
import com.dhj.actinium.compat.fluidlogged.FluidloggedCompat;
import com.dhj.actinium.runtime.ActiniumRuntime;
import com.dhj.actinium.world.biome.BiomeColorCache;
import com.dhj.actinium.world.biome.BiomeColorNoise;
import com.dhj.actinium.world.cloned.ActiniumBlockAccess;
import com.dhj.actinium.world.cloned.ChunkRenderContext;
import com.dhj.actinium.world.cloned.ClonedChunkSection;
import com.dhj.actinium.world.cloned.ClonedChunkSectionCache;

import java.util.Arrays;

/**
 * Takes a slice of world state (block states, biome and light data arrays) and copies the data for use in off-thread
 * operations. This allows chunk build tasks to see a consistent snapshot of chunk data at the exact moment the task was
 * created.
 *
 * World slices are not safe to use from multiple threads at once, but the data they contain is safe from modification
 * by the main client thread.
 *
 * Object pooling should be used to avoid huge allocations as this class contains many large arrays.
 */
public class WorldSlice implements ActiniumBlockAccess {
    // The number of blocks on each axis in a section.
    private static final int SECTION_BLOCK_LENGTH = 16;

    // The number of blocks in a section.
    private static final int SECTION_BLOCK_COUNT = SECTION_BLOCK_LENGTH * SECTION_BLOCK_LENGTH * SECTION_BLOCK_LENGTH;

    // The radius of blocks around the origin chunk that should be copied.
    private static final int NEIGHBOR_BLOCK_RADIUS = 2;

    // The radius of chunks around the origin chunk that should be copied.
    private static final int NEIGHBOR_CHUNK_RADIUS = MathHelper.roundUp(NEIGHBOR_BLOCK_RADIUS, 16) >> 4;

    // The number of sections on each axis of this slice.
    private static final int SECTION_LENGTH = 1 + (NEIGHBOR_CHUNK_RADIUS * 2);

    // The size of the lookup tables used for mapping values to coordinate int pairs. The lookup table size is always
    // a power of two so that multiplications can be replaced with simple bit shifts in hot code paths.
    private static final int TABLE_LENGTH = MathHelper.smallestEncompassingPowerOfTwo(SECTION_LENGTH);

    // The number of bits needed for each X/Y/Z component in a lookup table.
    private static final int TABLE_BITS = Integer.bitCount(TABLE_LENGTH - 1);

    // The default block state used for out-of-bounds access
    private static final IBlockState EMPTY_BLOCK_STATE = Blocks.AIR.getDefaultState();

    // The array size for the section lookup table.
    private static final int SECTION_TABLE_ARRAY_SIZE = TABLE_LENGTH * TABLE_LENGTH * TABLE_LENGTH;

    // The world this slice has copied data from
    private final World world;
    private final WorldType worldType;
    private final int defaultSkyLightValue;


    // Local Section->BlockState table.
    private final IBlockState[][] blockStatesArrays;

    // Local Section->FluidState table.
    private final Object[][] fluidStatesArrays;

    // Local section copies. Read-only. The array itself belongs to this slice and is reused across
    // chunk build tasks: copyData refills it from the task-owned snapshot array, and reset clears
    // the element references so no ClonedChunkSection outlives its task inside this pooled object.
    private final ClonedChunkSection[] sections = new ClonedChunkSection[SECTION_TABLE_ARRAY_SIZE];

    // Biome data of the captured chunk neighbourhood. Biome tables are per chunk, so this covers
    // SECTION_LENGTH chunks per axis rather than one table per section (see BiomeLookup).
    private final BiomeLookup biomeLookup = new BiomeLookup(SECTION_LENGTH);

    // The biome blend caches for each color resolver type
    private final BiomeColorCache biomeColorCache;

    // The starting point from which this slice captures blocks
    private int baseX, baseY, baseZ;

    // The chunk origin of this slice
    private SectionPos origin;

    // Bounding box reused for every snapshot volume. copyData rewrites all six coordinates in full
    // before publishing it, so coordinates of a previous task are never observable.
    private final StructureBoundingBox volumeBox = new StructureBoundingBox(0, 0, 0, 0, 0, 0);

    // The volume that this slice contains; only non-null between copyData and reset.
    @Nullable
    private StructureBoundingBox volume;

    // A fallback BlockPos object to use when retrieving data from the level directly
    private final BlockPos.MutableBlockPos fallbackPos = new BlockPos.MutableBlockPos();

    public static ChunkRenderContext prepare(World world, SectionPos origin, ClonedChunkSectionCache sectionCache) {
        Chunk chunk = world.getChunk(origin.x(), origin.z());
        int storageIndex = DepthsUpdateCompat.toStorageIndex(world, origin.y());
        ExtendedBlockStorage section = storageIndex >= 0 ? chunk.getBlockStorageArray()[storageIndex] : null;

        // If the chunk section is absent or empty, simply terminate now. There will never be anything in this chunk
        // section to render, so we need to signal that a chunk render task shouldn't created. This saves a considerable
        // amount of time in queueing instant build tasks and greatly accelerates how quickly the world can be loaded.
        if (section == null || section.isEmpty()) {
            return null;
        }

        // The snapshot volume is derived from the origin by the consuming slice, so the context
        // only carries the section coordinates and the cloned sections acquired here.
        // The min/max bounds of the chunks copied by this slice
        final int minChunkX = origin.x() - NEIGHBOR_CHUNK_RADIUS;
        final int minChunkY = origin.y() - NEIGHBOR_CHUNK_RADIUS;
        final int minChunkZ = origin.z() - NEIGHBOR_CHUNK_RADIUS;

        final int maxChunkX = origin.x() + NEIGHBOR_CHUNK_RADIUS;
        final int maxChunkY = origin.y() + NEIGHBOR_CHUNK_RADIUS;
        final int maxChunkZ = origin.z() + NEIGHBOR_CHUNK_RADIUS;

        ClonedChunkSection[] sections = new ClonedChunkSection[SECTION_TABLE_ARRAY_SIZE];

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (int chunkY = minChunkY; chunkY <= maxChunkY; chunkY++) {
                    sections[getLocalSectionIndex(chunkX - minChunkX, chunkY - minChunkY, chunkZ - minChunkZ)] =
                            sectionCache.acquire(chunkX, chunkY, chunkZ);
                }
            }
        }

        return new ChunkRenderContext(origin, sections);
    }

    private boolean hasSkyLight() {
        //? if >1.10.2 {
        return this.world.provider.hasSkyLight();
        //?} else
        /*return true;*/
    }

    public WorldSlice(World world) {
        this.world = world;
        this.worldType = world.getWorldType();
        this.defaultSkyLightValue = this.hasSkyLight() ? EnumSkyBlock.SKY.defaultLightValue : 0;

        this.blockStatesArrays = new IBlockState[SECTION_TABLE_ARRAY_SIZE][];
        this.biomeColorCache = new BiomeColorCache(this, ActiniumRuntime.options().quality.legacyBiomeBlendRadius);
        // Only the outer table: each entry is aliased to the cloned section's shared unpacked fluid data, so no
        // per-slice copy of 4096 fluid states is needed.
        if(!FluidloggedCompat.IS_LOADED) this.fluidStatesArrays = null;
        else this.fluidStatesArrays = new Object[SECTION_TABLE_ARRAY_SIZE][];

        for (int x = 0; x < SECTION_LENGTH; x++) {
            for (int y = 0; y < SECTION_LENGTH; y++) {
                for (int z = 0; z < SECTION_LENGTH; z++) {
                    int i = getLocalSectionIndex(x, y, z);

                    this.blockStatesArrays[i] = new IBlockState[SECTION_BLOCK_COUNT];
                    Arrays.fill(this.blockStatesArrays[i], EMPTY_BLOCK_STATE);


                }
            }
        }
    }

    public void copyData(ChunkRenderContext context) {
        SectionPos origin = context.getOrigin();
        this.origin = origin;

        // The snapshot array travels with the pending build task, so its entries are copied into
        // this slice's persistent array instead of being aliased; the task keeps its own container.
        System.arraycopy(context.getSections(), 0, this.sections, 0, SECTION_TABLE_ARRAY_SIZE);

        this.volumeBox.minX = origin.minX() - NEIGHBOR_BLOCK_RADIUS;
        this.volumeBox.minY = origin.minY() - NEIGHBOR_BLOCK_RADIUS;
        this.volumeBox.minZ = origin.minZ() - NEIGHBOR_BLOCK_RADIUS;
        this.volumeBox.maxX = origin.maxX() + NEIGHBOR_BLOCK_RADIUS;
        this.volumeBox.maxY = origin.maxY() + NEIGHBOR_BLOCK_RADIUS;
        this.volumeBox.maxZ = origin.maxZ() + NEIGHBOR_BLOCK_RADIUS;
        this.volume = this.volumeBox;

        this.biomeColorCache.update(origin);

        this.baseX = (this.origin.x() - NEIGHBOR_CHUNK_RADIUS) << 4;
        this.baseY = (this.origin.y() - NEIGHBOR_CHUNK_RADIUS) << 4;
        this.baseZ = (this.origin.z() - NEIGHBOR_CHUNK_RADIUS) << 4;

        // Biome tables are per chunk column, so the snapshot publishes one per captured chunk
        // rather than one per section: every section of a chunk carries the same table. The
        // y = 0 section is used as that column's carrier.
        this.biomeLookup.beginSnapshot(this.baseX, this.baseZ);

        for (int x = 0; x < SECTION_LENGTH; x++) {
            for (int z = 0; z < SECTION_LENGTH; z++) {
                this.biomeLookup.setChunk(x, z, this.sections[getLocalChunkIndex(x, z)].getBiomeData());
            }
        }

        for (int x = 0; x < SECTION_LENGTH; x++) {
            for (int y = 0; y < SECTION_LENGTH; y++) {
                for (int z = 0; z < SECTION_LENGTH; z++) {
                    int idx = getLocalSectionIndex(x, y, z);

                    ClonedChunkSection section = this.sections[idx];

                    this.unpackBlockData(this.blockStatesArrays[idx], section, this.volumeBox);

                    if (FluidloggedCompat.IS_LOADED) {
                        this.fluidStatesArrays[idx] = section.getUnpackedFluidData();
                    }
                }
            }
        }
    }

    public void reset() {
        Arrays.fill(this.sections, null);

        this.biomeLookup.clearSnapshot();

        this.volumeBox.minX = 0;
        this.volumeBox.minY = 0;
        this.volumeBox.minZ = 0;
        this.volumeBox.maxX = 0;
        this.volumeBox.maxY = 0;
        this.volumeBox.maxZ = 0;

        this.origin = null;
        this.volume = null;
    }

    private void unpackBlockData(IBlockState[] states, ClonedChunkSection section, StructureBoundingBox box) {
        if (this.origin.equals(section.getPosition()))  {
            this.unpackBlockDataZ(states, section);
        } else {
            this.unpackBlockDataR(states, section, box);
        }
    }


    private static void copyBlocks(IBlockState[] blocks, ClonedChunkSection section, int minBlockY, int maxBlockY, int minBlockZ, int maxBlockZ, int minBlockX, int maxBlockX) {
        for (int y = minBlockY; y <= maxBlockY; y++) {
            for (int z = minBlockZ; z <= maxBlockZ; z++) {
                for (int x = minBlockX; x <= maxBlockX; x++) {
                    final int blockIdx = getLocalBlockIndex(x & 15, y & 15, z & 15);
                    blocks[blockIdx] = section.getBlockState(x & 15, y & 15, z & 15);
                }
            }
        }
    }

    private void unpackBlockDataR(IBlockState[] states, ClonedChunkSection section, StructureBoundingBox box) {
        SectionPos pos = section.getPosition();

        int minBlockX = Math.max(box.minX, pos.minX());
        int maxBlockX = Math.min(box.maxX, pos.maxX());

        int minBlockY = Math.max(box.minY, pos.minY());
        int maxBlockY = Math.min(box.maxY, pos.maxY());

        int minBlockZ = Math.max(box.minZ, pos.minZ());
        int maxBlockZ = Math.min(box.maxZ, pos.maxZ());

        copyBlocks(states, section, minBlockY, maxBlockY, minBlockZ, maxBlockZ, minBlockX, maxBlockX);
    }

    private void unpackBlockDataZ(IBlockState[] states, ClonedChunkSection section) {
        // TODO: Look into a faster copy for this?
        final SectionPos pos = section.getPosition();

        final int minBlockX = pos.minX();
        final int maxBlockX = pos.maxX();

        final int minBlockY = pos.minY();
        final int maxBlockY = pos.maxY();

        final int minBlockZ = pos.minZ();
        final int maxBlockZ = pos.maxZ();

        // TODO: Can this be optimized?
        copyBlocks(states, section, minBlockY, maxBlockY, minBlockZ, maxBlockZ, minBlockX, maxBlockX);
    }

    /**
     * Whether the given block coordinate lies inside the given snapshot volume.
     *
     * <p>A {@code null} volume is legal: the slice is a reused snapshot object that only covers
     * anything between {@link #copyData(ChunkRenderContext)} and {@link #reset()}. Renderers
     * outside the chunk build pipeline may retain the {@link IBlockAccess} instance handed to
     * {@code BlockRendererDispatcher} and query it later on the main thread (MalisisCore's
     * {@code AnimatedRenderer} does exactly this from {@code RenderWorldLastEvent}, see issue
     * #57); after the slice has been reset it covers nothing, so every coordinate is out of
     * bounds and the callers' fallback paths apply instead of dereferencing a null box.</p>
     */
    static boolean blockBoxContains(@Nullable StructureBoundingBox box, int x, int y, int z) {
        if (box == null) {
            return false;
        }
        return x >= box.minX &&
                x <= box.maxX &&
                y >= box.minY &&
                y <= box.maxY &&
                z >= box.minZ &&
                z <= box.maxZ;
    }

    @Override
    public IBlockState getBlockState(BlockPos pos) {
        return this.getBlockState(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public boolean isAirBlock(BlockPos pos) {
        IBlockState state = this.getBlockState(pos);
        return state.getBlock().isAir(state, this, pos);
    }

    public IBlockState getBlockState(int x, int y, int z) {
        if (!blockBoxContains(this.volume, x, y, z)) {
            return this.getBlockStateFallback(x, y, z);
        }

        int relX = x - this.baseX;
        int relY = y - this.baseY;
        int relZ = z - this.baseZ;

        return this.blockStatesArrays[getLocalSectionIndex(relX >> 4, relY >> 4, relZ >> 4)]
                [getLocalBlockIndex(relX & 15, relY & 15, relZ & 15)];
    }

    public IBlockState getBlockStateRelative(int x, int y, int z) {
        // NOTE: Not bounds checked. We assume ChunkRenderRebuildTask is the only function using this
        return this.blockStatesArrays[getLocalSectionIndex(x >> 4, y >> 4, z >> 4)]
                [getLocalBlockIndex(x & 15, y & 15, z & 15)];
    }

    @Override
    public TileEntity getTileEntity(BlockPos pos) {
        return this.getBlockEntity(pos.getX(), pos.getY(), pos.getZ());
    }

    public TileEntity getBlockEntity(int x, int y, int z) {
        if (!blockBoxContains(this.volume, x, y, z)) {
            return null;
        }

        int relX = x - this.baseX;
        int relY = y - this.baseY;
        int relZ = z - this.baseZ;

        return this.sections[getLocalSectionIndex(relX >> 4, relY >> 4, relZ >> 4)]
                .getBlockEntity(relX & 15, relY & 15, relZ & 15);
    }

    @Override
    public int getCombinedLight(BlockPos pos, int ambientLight) {
        if (!blockBoxContains(this.volume, pos.getX(), pos.getY(), pos.getZ())) {
            return (this.defaultSkyLightValue << 20) | (ambientLight << 4);
        }

        int i = this.getLightFromNeighborsFor(EnumSkyBlock.SKY, pos);
        int j = this.getLightFromNeighborsFor(EnumSkyBlock.BLOCK, pos);

        if (j < ambientLight)
        {
            j = ambientLight;
        }

        return i << 20 | j << 4;
    }

    private int getLightFor(EnumSkyBlock type, int relX, int relY, int relZ) {
        ClonedChunkSection section = this.sections[getLocalSectionIndex(relX >> 4, relY >> 4, relZ >> 4)];

        return section.getLightLevel(relX & 15, relY & 15, relZ & 15, type);
    }

    private int getLightFromNeighborsFor(EnumSkyBlock type, BlockPos pos) {
        if(!this.hasSkyLight() && type == EnumSkyBlock.SKY) {
            return this.defaultSkyLightValue;
        }

        int relX = pos.getX() - this.baseX;
        int relY = pos.getY() - this.baseY;
        int relZ = pos.getZ() - this.baseZ;

        IBlockState state = this.getBlockStateRelative(relX, relY, relZ);

        if(!state.useNeighborBrightness()) {
            return getLightFor(type, relX, relY, relZ);
        } else {
            int west = getLightFor(type, relX - 1, relY, relZ);
            int east = getLightFor(type, relX + 1, relY, relZ);
            int up = getLightFor(type, relX, relY + 1, relZ);
            int down = getLightFor(type, relX, relY - 1, relZ);
            int north = getLightFor(type, relX, relY, relZ + 1);
            int south = getLightFor(type, relX, relY, relZ - 1);

            if(east > west) {
                west = east;
            }

            if(up > west) {
                west = up;
            }

            if(down > west) {
                west = down;
            }

            if(north > west) {
                west = north;
            }

            if(south > west) {
                west = south;
            }

            return west;
        }
    }

    @Override
    public Biome getBiome(BlockPos pos) {
        return this.biomeLookup.getBiome(pos.getX(), pos.getZ());
    }

    @Override
    public int getBlockTint(BlockPos pos, BiomeColorHelper.ColorResolver resolver) {
        if(!blockBoxContains(this.volume, pos.getX(), pos.getY(), pos.getZ())) {
            // Same noise treatment as the cached path so out-of-volume queries stay consistent.
            return BiomeColorNoise.applyForResolver(resolver, pos.getX(), pos.getY(), pos.getZ(),
                    resolver.getColorAtPos(Biomes.PLAINS, pos));
        }

        return this.biomeColorCache.getColor(resolver, pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getStrongPower(BlockPos pos, EnumFacing direction) {
        IBlockState state = this.getBlockState(pos);
        return state.getBlock().getStrongPower(state, this, pos, direction);
    }

    @Override
    public WorldType getWorldType() {
        return this.worldType;
    }

    @Override
    public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean _default) {
        return getBlockState(pos).isSideSolid(this, pos, side);
    }

    /**
     * Gets the biome at the given global coordinates from the captured snapshot.
     *
     * <p>{@code y} is ignored: biome data is stored per chunk column, so every height of a column
     * carries the same biome table. Positions outside the captured chunk neighbourhood resolve to
     * the nearest captured position instead of failing, which is what biome-blending callers
     * (Better Biome Blend, issue #162) rely on; see {@link BiomeLookup}.</p>
     */
    public Biome getBiome(int x, int y, int z) {
        return this.biomeLookup.getBiome(x, z);
    }

    public SectionPos getOrigin() {
        return this.origin;
    }

    public float getBrightness(EnumFacing direction, boolean shaded) {
        if (!shaded) {
            return !hasSkyLight() ? 0.9f : 1.0f;
        }
        return LightUtil.diffuseLight(direction);
    }

    /**
     * Outside the captured slice, return live data only on the main thread. Worker threads must stay within the
     * snapshot to avoid mixing freshly cloned world data into an in-flight chunk build.
     */
    private IBlockState getBlockStateFallback(int x, int y, int z) {
        if (Minecraft.getMinecraft().isCallingFromMinecraftThread()) {
            this.fallbackPos.setPos(x, y, z);
            return this.world.getBlockState(this.fallbackPos);
        }

        return EMPTY_BLOCK_STATE;
    }

    // Fluidlogged compat

    @Override
    @Optional.Method(modid = FluidloggedCompat.MODID)
    public FluidState getFluidState(int x, int y, int z) {
        if (!blockBoxContains(this.volume, x, y, z)) {
            return FluidloggedCompat.getEmptyFluidState();
        }

        int relX = x - this.baseX;
        int relY = y - this.baseY;
        int relZ = z - this.baseZ;

        return (FluidState)this.fluidStatesArrays[getLocalSectionIndex(relX >> 4, relY >> 4, relZ >> 4)]
                [getLocalBlockIndex(relX & 15, relY & 15, relZ & 15)];
    }

    @Override
    @Optional.Method(modid = FluidloggedCompat.MODID)
    public World getWorld() {
        return world;
    }

    // [VanillaCopy] PalettedContainer#toIndex
    public static int getLocalBlockIndex(int x, int y, int z) {
        return y << 8 | z << 4 | x;
    }

    public static int getLocalSectionIndex(int x, int y, int z) {
        return y << TABLE_BITS << TABLE_BITS | z << TABLE_BITS | x;
    }

    public static int getLocalChunkIndex(int x, int z) {
        return z << TABLE_BITS | x;
    }
}


