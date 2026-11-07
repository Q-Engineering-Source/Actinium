package net.coderbot.iris.shaderpack;

import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import net.coderbot.iris.block_rendering.BlockMaterialMapping;
import net.coderbot.iris.shaderpack.include.AbsolutePackPath;
import net.coderbot.iris.shaderpack.include.IncludeGraph;
import net.coderbot.iris.shaderpack.materialmap.BlockEntry;
import net.coderbot.iris.shaderpack.materialmap.NamespacedId;
import net.coderbot.iris.shaderpack.option.ShaderPackOptions;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdMapTest {
    private static final List<StringPair> LEGACY_ENVIRONMENT = List.of(
            new StringPair("MC_VERSION", "11202"),
            new StringPair("ACTINIUM_TEST_DEFINE", "1")
    );

    @TempDir
    Path tempDir;

    @BeforeAll
    static void bootstrapMinecraft() {
        Bootstrap.register();
    }

    @Test
    void modernMcVersionConditionalWithoutFallbackDoesNotEnableLegacySection() {
        String properties = """
                #if defined SHADER_GRASS_SETTING && MC_VERSION == 12001 && defined SHADER_GRASS_UNSUPPORTED_FIX
                block.12=minecraft:short_grass minecraft:grass
                #endif
                #ifdef BOES_EARTH_BLOCKSTATES
                #else
                #endif
                """;

        assertFalse(IdMap.hasLegacySection(properties));
    }

    @Test
    void explicitLegacyVersionEnablesLegacySection() {
        String properties = """
                #if MC_VERSION == 11202
                block.12=minecraft:tallgrass
                #endif
                """;

        assertTrue(IdMap.hasLegacySection(properties));
    }

    @Test
    void mcVersionFallbackWithoutExplicit11202StaysOnModernVersion() {
        String fallbackBranch = """
                #if MC_VERSION >= 11400
                block.12=minecraft:short_grass
                #else
                block.12=minecraft:tallgrass
                #endif
                """;
        String elifChain = """
                #if MC_VERSION >= 11400
                block.12=minecraft:short_grass
                #elif MC_VERSION >= 11300
                block.12=minecraft:grass
                #endif
                """;

        assertAll(
                () -> assertFalse(IdMap.hasLegacySection(fallbackBranch)),
                () -> assertFalse(IdMap.hasLegacySection(elifChain))
        );
    }

    @Test
    void packWithoutLegacySectionIsEvaluatedAsModernVersionAndKeepsOtherDefines() throws IOException {
        writePackFile("block.properties", """
                #if MC_VERSION == 260101 && defined ACTINIUM_TEST_DEFINE
                block.1=minecraft:short_grass
                #endif
                """);
        writePackFile("item.properties", """
                #if MC_VERSION == 260101
                item.2=minecraft:iron_sword
                #endif
                """);

        IdMap idMap = new IdMap(tempDir, createOptions(), LEGACY_ENVIRONMENT);

        assertFalse(idMap.hasLegacySection());
        assertEquals(Map.of(1, List.of(BlockEntry.parse("minecraft:short_grass"))), idMap.getBlockProperties());
        assertEquals(2, idMap.getItemIdMap().getInt(new NamespacedId("minecraft", "iron_sword")));
    }

    @Test
    void packWithExplicit11202SectionKeepsTheRealMcVersion() throws IOException {
        writePackFile("block.properties", """
                #if MC_VERSION >= 11300
                block.1=minecraft:short_grass
                #endif
                #if MC_VERSION == 11202 && defined ACTINIUM_TEST_DEFINE
                block.1=minecraft:tallgrass
                #endif
                """);
        writePackFile("item.properties", """
                #if MC_VERSION >= 11300
                item.2=minecraft:netherite_sword
                #endif
                #if MC_VERSION == 11202 && defined ACTINIUM_TEST_DEFINE
                item.2=minecraft:iron_sword
                #endif
                """);

        IdMap idMap = new IdMap(tempDir, createOptions(), LEGACY_ENVIRONMENT);

        assertTrue(idMap.hasLegacySection());
        assertEquals(Map.of(1, List.of(BlockEntry.parse("minecraft:tallgrass"))), idMap.getBlockProperties());
        assertEquals(2, idMap.getItemIdMap().getInt(new NamespacedId("minecraft", "iron_sword")));
        assertEquals(-1, idMap.getItemIdMap().getInt(new NamespacedId("minecraft", "netherite_sword")));
    }

    @Test
    void explicitLegacyVerdictKeepsTheRealMcVersionAndModernVerdictForcesTheModernOne() throws IOException {
        writePackFile("block.properties", """
                #if MC_VERSION >= 11300
                block.1=minecraft:short_grass
                #else
                block.1=minecraft:tallgrass
                #endif
                """);

        ShaderPackOptions options = createOptions();
        IdMap legacy = new IdMap(tempDir, true, options, LEGACY_ENVIRONMENT);
        IdMap modern = new IdMap(tempDir, false, options, LEGACY_ENVIRONMENT);

        assertAll(
                () -> assertTrue(legacy.hasLegacySection()),
                () -> assertEquals(Map.of(1, List.of(BlockEntry.parse("minecraft:tallgrass"))), legacy.getBlockProperties()),
                () -> assertFalse(modern.hasLegacySection()),
                () -> assertEquals(Map.of(1, List.of(BlockEntry.parse("minecraft:short_grass"))), modern.getBlockProperties())
        );
    }

    @Test
    void packWithMcVersionFallbackButWithoutExplicit11202KeepsTheModernBranch() throws IOException {
        writePackFile("block.properties", """
                #if MC_VERSION >= 11300
                block.10001=minecraft:short_grass minecraft:fern
                #else
                block.10005=minecraft:double_plant
                #endif
                """);

        IdMap idMap = new IdMap(tempDir, createOptions(), LEGACY_ENVIRONMENT);

        assertAll(
                () -> assertFalse(idMap.hasLegacySection()),
                () -> assertEquals(
                        Map.of(10001, List.of(BlockEntry.parse("minecraft:short_grass"), BlockEntry.parse("minecraft:fern"))),
                        idMap.getBlockProperties())
        );
    }

    @Test
    void blockRulesKeepDeclarationOrder() throws IOException {
        writePackFile("block.properties", """
                block.10004=minecraft:tall_grass:half=upper
                block.10005=minecraft:tall_grass:half=lower
                """);

        IdMap idMap = new IdMap(tempDir, createOptions(), LEGACY_ENVIRONMENT);

        assertEquals(List.of(10004, 10005), List.copyOf(idMap.getBlockProperties().keySet()));
    }

    @Test
    void earlierBlockRuleWinsForOverlappingMetas() throws IOException {
        writePackFile("block.properties", """
                block.10004=minecraft:double_plant
                block.10005=minecraft:double_plant:2,10
                """);

        IdMap idMap = new IdMap(tempDir, createOptions(), LEGACY_ENVIRONMENT);
        BlockMaterialMapping.BlockIdMaps maps = BlockMaterialMapping.createBlockIdMaps(
                idMap.getBlockProperties(), idMap.hasLegacySection());

        assertAll(
                () -> assertEquals(10004, resolve(maps, Blocks.DOUBLE_PLANT, 2)),
                () -> assertEquals(10004, resolve(maps, Blocks.DOUBLE_PLANT, 10))
        );
    }

    @Test
    void derivativeContinuationBlockKeepsEveryBranchOfTheModernGrassMapping() throws IOException {
        writePackFile("block.properties", """
                #if MC_VERSION >= 11300

                    # Grass
                    block.10001 = fern \\
                            #if MC_VERSION < 12003
                                grass \\
                            #else
                                short_grass \\
                            #endif

                    # Wheat
                    block.10002 = wheat carrots potatoes beetroots seagrass

                #endif
                """);

        IdMap idMap = new IdMap(tempDir, createOptions(), LEGACY_ENVIRONMENT);
        BlockMaterialMapping.BlockIdMaps maps = BlockMaterialMapping.createBlockIdMaps(
                idMap.getBlockProperties(), idMap.hasLegacySection());

        assertAll(
                () -> assertFalse(idMap.hasLegacySection()),
                () -> assertEquals(
                        List.of(BlockEntry.parse("minecraft:fern"), BlockEntry.parse("minecraft:short_grass")),
                        idMap.getBlockProperties().get(10001)),
                () -> assertEquals(
                        List.of(
                                BlockEntry.parse("minecraft:wheat"),
                                BlockEntry.parse("minecraft:carrots"),
                                BlockEntry.parse("minecraft:potatoes"),
                                BlockEntry.parse("minecraft:beetroots"),
                                BlockEntry.parse("minecraft:seagrass")),
                        idMap.getBlockProperties().get(10002)),
                () -> assertEquals(10001, resolve(maps, Blocks.TALLGRASS, 1), "grass"),
                () -> assertEquals(10001, resolve(maps, Blocks.TALLGRASS, 2), "fern")
        );
    }

    private static int resolve(BlockMaterialMapping.BlockIdMaps maps, Block block, int meta) {
        Int2IntMap metaMap = maps.blockMetaMap().get(block);
        return metaMap == null ? -1 : BlockMaterialMapping.resolveId(metaMap, meta);
    }

    private void writePackFile(String name, String contents) throws IOException {
        Files.writeString(tempDir.resolve(name), contents);
    }

    private ShaderPackOptions createOptions() throws IOException {
        Files.writeString(tempDir.resolve("entry.glsl"), "void main() {}\n");
        IncludeGraph graph = new IncludeGraph(
                tempDir,
                ImmutableList.of(AbsolutePackPath.fromAbsolutePath("/entry.glsl"))
        );
        return new ShaderPackOptions(graph, Map.of());
    }
}
