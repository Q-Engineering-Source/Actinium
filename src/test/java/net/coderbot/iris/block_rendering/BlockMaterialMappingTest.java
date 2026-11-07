package net.coderbot.iris.block_rendering;

import com.dhj.actinium.world.EmptyBlockAccess;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.coderbot.iris.shaderpack.materialmap.BlockEntry;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BlockMaterialMappingTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.register();
    }

    @Test
    void modernPlantEntriesResolveToDistinctMaterials() {
        Int2ObjectMap<List<BlockEntry>> entries = new Int2ObjectLinkedOpenHashMap<>();
        entries.put(10001, List.of(BlockEntry.parse("fern"), BlockEntry.parse("grass")));
        entries.put(10003, List.of(BlockEntry.parse("dandelion"), BlockEntry.parse("poppy")));
        entries.put(10004, List.of(BlockEntry.parse("tall_grass:half=upper")));
        entries.put(10005, List.of(BlockEntry.parse("tall_grass:half=lower")));

        BlockMaterialMapping.BlockIdMaps maps = BlockMaterialMapping.createBlockIdMaps(entries, false);

        assertEquals(10001, materialId(maps, Blocks.TALLGRASS, 1), "grass");
        assertEquals(10001, materialId(maps, Blocks.TALLGRASS, 2), "fern");
        assertEquals(10003, materialId(maps, Blocks.YELLOW_FLOWER, 0), "dandelion");
        assertEquals(10003, materialId(maps, Blocks.RED_FLOWER, 0), "poppy");
        assertEquals(10005, materialId(maps, Blocks.DOUBLE_PLANT, 2), "tall_grass lower half");
        assertEquals(10004, materialId(maps, Blocks.DOUBLE_PLANT, 10), "tall_grass upper half");
    }

    @Test
    void legacyDoublePlantRulesKeepLowerAndUpperHalvesApart() {
        Int2ObjectMap<List<BlockEntry>> entries = new Int2ObjectLinkedOpenHashMap<>();
        entries.put(10005, List.of(BlockEntry.parse("double_plant:half=lower")));
        entries.put(10004, List.of(BlockEntry.parse("double_plant:half=upper")));

        BlockMaterialMapping.BlockIdMaps maps = BlockMaterialMapping.createBlockIdMaps(entries, true);

        for (int meta = 0; meta <= 5; meta++) {
            assertEquals(10005, materialId(maps, Blocks.DOUBLE_PLANT, meta), "double_plant lower half meta " + meta);
        }
        for (int meta = 8; meta <= 13; meta++) {
            assertEquals(10004, materialId(maps, Blocks.DOUBLE_PLANT, meta), "double_plant upper half meta " + meta);
        }
    }

    @Test
    void cauldronKeepsEmptyAndWaterLevelsOnTheirOwnMaterials() {
        Int2ObjectMap<List<BlockEntry>> entries = new Int2ObjectLinkedOpenHashMap<>();
        entries.put(10045, List.of(BlockEntry.parse("cauldron")));
        entries.put(10049, List.of(BlockEntry.parse("water_cauldron")));

        BlockMaterialMapping.BlockIdMaps maps = BlockMaterialMapping.createBlockIdMaps(entries, false);

        assertEquals(10045, materialId(maps, Blocks.CAULDRON, 0), "empty cauldron");
        for (int level = 1; level <= 3; level++) {
            assertEquals(10049, materialId(maps, Blocks.CAULDRON, level), "water cauldron level " + level);
        }
    }

    @Test
    void bareCauldronWithoutWaterVariantKeepsWildcardMetadata() {
        Int2ObjectMap<List<BlockEntry>> entries = new Int2ObjectLinkedOpenHashMap<>();
        entries.put(118, List.of(BlockEntry.parse("cauldron")));

        BlockMaterialMapping.BlockIdMaps maps = BlockMaterialMapping.createBlockIdMaps(entries, false);

        assertAll(
                () -> assertEquals(118, materialId(maps, Blocks.CAULDRON, 0), "bare cauldron without metadata"),
                () -> assertEquals(118, materialId(maps, Blocks.CAULDRON, 1), "bare cauldron level 1"),
                () -> assertEquals(118, materialId(maps, Blocks.CAULDRON, 3), "bare cauldron level 3"));
    }

    @Test
    void doublePlantUpperHalfResolvesVariantFromActualState() {
        IBlockState upperState = doublePlantState(BlockDoublePlant.EnumBlockHalf.UPPER,
                BlockDoublePlant.EnumPlantType.SUNFLOWER, EnumFacing.SOUTH);
        IBlockAccess blockAccess = lowerHalfAccess();
        int metadata = upperState.getBlock().getMetaFromState(upperState);

        assertEquals(8, metadata, "upper half metadata encodes the south facing");
        assertEquals(BlockDoublePlant.EnumPlantType.SUNFLOWER, upperState.getValue(BlockDoublePlant.VARIANT),
                "the upper half state keeps the block default variant");
        assertEquals(BlockDoublePlant.EnumPlantType.GRASS,
                upperState.getActualState(blockAccess, BlockPos.ORIGIN).getValue(BlockDoublePlant.VARIANT),
                "the variant is read back from the lower half");
        assertEquals(10, BlockMaterialMapping.resolveShaderMetadata(upperState, BlockPos.ORIGIN, blockAccess, metadata),
                "upper half must carry the lower half's grass variant");
    }

    @Test
    void doublePlantLowerHalfKeepsVariantMetadata() {
        IBlockState lowerState = doublePlantState(BlockDoublePlant.EnumBlockHalf.LOWER,
                BlockDoublePlant.EnumPlantType.GRASS, EnumFacing.NORTH);
        int metadata = lowerState.getBlock().getMetaFromState(lowerState);

        assertEquals(2, metadata, "lower half metadata is the grass variant");
        assertEquals(2, BlockMaterialMapping.resolveShaderMetadata(
                lowerState, BlockPos.ORIGIN, EmptyBlockAccess.INSTANCE, metadata));
    }

    @Test
    void resolveShaderMetadataKeepsSnowyBit() {
        IBlockState upperState = doublePlantState(BlockDoublePlant.EnumBlockHalf.UPPER,
                BlockDoublePlant.EnumPlantType.SUNFLOWER, EnumFacing.SOUTH);
        assertEquals(10 | BlockMaterialMapping.SNOWY_META_BIT, BlockMaterialMapping.resolveShaderMetadata(
                upperState, BlockPos.ORIGIN, lowerHalfAccess(),
                8 | BlockMaterialMapping.SNOWY_META_BIT),
                "the double plant rewrite must keep the snowy bit");

        IBlockState lowerState = doublePlantState(BlockDoublePlant.EnumBlockHalf.LOWER,
                BlockDoublePlant.EnumPlantType.GRASS, EnumFacing.NORTH);
        assertEquals(2 | BlockMaterialMapping.SNOWY_META_BIT, BlockMaterialMapping.resolveShaderMetadata(
                lowerState, BlockPos.ORIGIN, EmptyBlockAccess.INSTANCE,
                2 | BlockMaterialMapping.SNOWY_META_BIT),
                "the lower half must keep the snowy bit");
    }

    private static IBlockAccess lowerHalfAccess() {
        IBlockState lowerState = doublePlantState(BlockDoublePlant.EnumBlockHalf.LOWER,
                BlockDoublePlant.EnumPlantType.GRASS, EnumFacing.NORTH);
        return new LowerHalfBlockAccess(BlockPos.ORIGIN, lowerState);
    }

    private static IBlockState doublePlantState(BlockDoublePlant.EnumBlockHalf half,
                                                BlockDoublePlant.EnumPlantType variant,
                                                EnumFacing facing) {
        return Blocks.DOUBLE_PLANT.getDefaultState()
                .withProperty(BlockDoublePlant.HALF, half)
                .withProperty(BlockDoublePlant.VARIANT, variant)
                .withProperty(BlockDoublePlant.FACING, facing);
    }

    private static int materialId(BlockMaterialMapping.BlockIdMaps maps, Block block, int metadata) {
        Int2IntMap metaMap = maps.blockMetaMap().get(block);
        assertNotNull(metaMap, "no material mapping registered for " + block.getRegistryName());
        return BlockMaterialMapping.resolveId(metaMap, metadata);
    }

    /**
     * Serves a real lower half directly below the queried position so vanilla
     * {@link BlockDoublePlant#getActualState} can copy the variant across, matching what the chunk
     * mesher sees in a world. Every other query falls through to {@link EmptyBlockAccess}.
     */
    private static final class LowerHalfBlockAccess implements IBlockAccess {
        private final BlockPos lowerPos;
        private final IBlockState lowerState;

        private LowerHalfBlockAccess(BlockPos upperPos, IBlockState lowerState) {
            this.lowerPos = upperPos.down();
            this.lowerState = lowerState;
        }

        @Override
        public TileEntity getTileEntity(BlockPos pos) {
            return EmptyBlockAccess.INSTANCE.getTileEntity(pos);
        }

        @Override
        public int getCombinedLight(BlockPos pos, int lightValue) {
            return EmptyBlockAccess.INSTANCE.getCombinedLight(pos, lightValue);
        }

        @Override
        public IBlockState getBlockState(BlockPos pos) {
            return lowerPos.equals(pos) ? lowerState : EmptyBlockAccess.INSTANCE.getBlockState(pos);
        }

        @Override
        public boolean isAirBlock(BlockPos pos) {
            return EmptyBlockAccess.INSTANCE.isAirBlock(pos);
        }

        @Override
        public Biome getBiome(BlockPos pos) {
            return EmptyBlockAccess.INSTANCE.getBiome(pos);
        }

        @Override
        public int getStrongPower(BlockPos pos, EnumFacing direction) {
            return EmptyBlockAccess.INSTANCE.getStrongPower(pos, direction);
        }

        @Override
        public WorldType getWorldType() {
            return EmptyBlockAccess.INSTANCE.getWorldType();
        }

        @Override
        public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean defaultValue) {
            return EmptyBlockAccess.INSTANCE.isSideSolid(pos, side, defaultValue);
        }
    }
}
