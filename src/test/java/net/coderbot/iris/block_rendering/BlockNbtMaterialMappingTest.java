package net.coderbot.iris.block_rendering;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.coderbot.iris.shaderpack.materialmap.BlockEntry;
import net.coderbot.iris.shaderpack.materialmap.NamespacedId;
import net.coderbot.iris.shaderpack.materialmap.PropertiesTokenizer;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockNbtMaterialMappingTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.register();
    }

    @AfterEach
    void clearCache() {
        BlockRenderingSettings.clearTeNbtCache();
        BlockRenderingSettings.INSTANCE.setHasSnowyEntries(false);
    }

    @Test
    void blockAndNamespacedRegistrationPreserveMetadataConstraints() {
        NamespacedId furnace = new NamespacedId("minecraft", "furnace");
        Int2ObjectOpenHashMap<List<BlockEntry>> entries = new Int2ObjectOpenHashMap<>();
        entries.put(101, List.of(new BlockEntry(furnace, Set.of(2), Map.of(), condition())));

        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setString("mode", "active");
        NbtConditionalIdMap<Block> blockMap = BlockMaterialMapping.createBlockIdMaps(entries, true).blockNbtMap();
        assertEquals(101, blockMap.resolve(Blocks.FURNACE, nbt, 2));
        assertEquals(-1, blockMap.resolve(Blocks.FURNACE, nbt, 3));

        NbtConditionalIdMap<NamespacedId> namespacedMap = BlockMaterialMapping.createNamespacedNbtMap(entries);
        assertEquals(101, namespacedMap.resolve(furnace, nbt, 2));
        assertEquals(-1, namespacedMap.resolve(furnace, nbt, 3));
        assertEquals(101, namespacedMap.resolve(furnace, nbt));
    }

    @Test
    void cacheReusesMatchingStateAndResolvesMetadataChangesImmediately() {
        BlockRenderingSettings settings = settings();
        TestTileEntity tileEntity = new TestTileEntity();

        assertEquals(101, settings.resolveBlockNbtId(Blocks.FURNACE, 2, tileEntity));
        assertEquals(101, settings.resolveBlockNbtId(Blocks.FURNACE, 2, tileEntity));
        assertEquals(1, tileEntity.serializations);
        assertEquals(102, settings.resolveBlockNbtId(Blocks.FURNACE, 3, tileEntity));
        assertEquals(2, tileEntity.serializations);
        assertEquals(-1, settings.resolveBlockNbtId(Blocks.FURNACE, 4, tileEntity));
        assertEquals(3, tileEntity.serializations);
        assertEquals(101, settings.resolveBlockNbtId(Blocks.FURNACE, 2, tileEntity));
    }

    @Test
    void cacheDoesNotReuseOtherBlockOrReplacementTileEntityAtSamePosition() {
        BlockRenderingSettings settings = settings();
        TestTileEntity tileEntity = new TestTileEntity();

        assertEquals(101, settings.resolveBlockNbtId(Blocks.FURNACE, 2, tileEntity));
        assertEquals(201, settings.resolveBlockNbtId(Blocks.CHEST, 2, tileEntity));

        TestTileEntity replacement = new TestTileEntity();
        replacement.mode = "inactive";
        assertEquals(-1, settings.resolveBlockNbtId(Blocks.FURNACE, 2, replacement));
        assertEquals(1, replacement.serializations);
    }

    @Test
    void replacingRulesInvalidatesCachedIds() {
        BlockRenderingSettings settings = settings();
        TestTileEntity tileEntity = new TestTileEntity();
        assertEquals(101, settings.resolveBlockNbtId(Blocks.FURNACE, 2, tileEntity));

        NbtConditionalIdMap<Block> replacement = new NbtConditionalIdMap<>();
        replacement.addCondition(Blocks.FURNACE, condition(), 301, Set.of(2));
        settings.setBlockNbtMap(replacement);
        assertEquals(301, settings.resolveBlockNbtId(Blocks.FURNACE, 2, tileEntity));
        assertEquals(2, tileEntity.serializations);
    }

    private static BlockRenderingSettings settings() {
        BlockRenderingSettings settings = new BlockRenderingSettings();
        NbtConditionalIdMap<Block> map = new NbtConditionalIdMap<>();
        map.addCondition(Blocks.FURNACE, condition(), 101, Set.of(2));
        map.addCondition(Blocks.FURNACE, condition(), 102, Set.of(3));
        map.addCondition(Blocks.CHEST, condition(), 201, Set.of(2));
        settings.setBlockNbtMap(map);
        return settings;
    }

    private static Map<String, PropertiesTokenizer.NbtValue> condition() {
        return Map.of("mode", new PropertiesTokenizer.NbtValue("active", true));
    }

    private static final class TestTileEntity extends TileEntity {
        private String mode = "active";
        private int serializations;

        @Override
        public NBTTagCompound writeToNBT(NBTTagCompound compound) {
            serializations++;
            compound.setString("mode", mode);
            return compound;
        }
    }
}
