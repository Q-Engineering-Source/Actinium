package net.coderbot.iris.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DimensionIdTest {
    @Test
    void canonicalizesVanillaNumericIdsOnlyWithoutAProviderName() {
        assertEquals("minecraft:the_nether", DimensionId.canonicalize(null, -1));
        assertEquals("minecraft:overworld", DimensionId.canonicalize("", 0));
        assertEquals("minecraft:the_end", DimensionId.canonicalize("   ", 1));
        assertEquals("legacy:dimension_7", DimensionId.canonicalize(null, 7));
        assertEquals("Custom End", DimensionId.canonicalize("Custom End", 1));
    }

    @Test
    void canonicalizesVanillaAliasesAndPreservesCustomDimensions() {
        assertEquals("minecraft:overworld", DimensionId.canonicalize("Overworld"));
        assertEquals("minecraft:overworld", DimensionId.canonicalize("overworld"));
        assertEquals("minecraft:overworld", DimensionId.canonicalize("minecraft:overworld"));
        assertEquals("minecraft:the_nether", DimensionId.canonicalize("Nether"));
        assertEquals("minecraft:the_nether", DimensionId.canonicalize("the_nether"));
        assertEquals("minecraft:the_nether", DimensionId.canonicalize("minecraft:nether"));
        assertEquals("minecraft:the_nether", DimensionId.canonicalize("minecraft:the_nether"));
        assertEquals("minecraft:the_end", DimensionId.canonicalize("The End"));
        assertEquals("minecraft:the_end", DimensionId.canonicalize("the_end"));
        assertEquals("minecraft:the_end", DimensionId.canonicalize("end"));
        assertEquals("minecraft:the_end", DimensionId.canonicalize("minecraft:end"));
        assertEquals("minecraft:the_end", DimensionId.canonicalize("minecraft:the_end"));
        assertEquals("Twilight Forest", DimensionId.canonicalize("Twilight Forest"));
    }

    @Test
    void endExactMappingWinsOverOverworldWildcard() {
        Map<String, String> mappings = Map.of(
            "minecraft:the_end", "world1",
            "*", "world0"
        );

        assertEquals("world1", ShaderPack.resolveDimensionFolder(
            mappings,
            Set.of("world0", "world1"),
            "the_end",
            1,
            true
        ));
    }

    @Test
    void overworldAndNetherExactMappingsWinOverWildcard() {
        Map<String, String> mappings = Map.of(
            "minecraft:overworld", "world0",
            "minecraft:the_nether", "world-1",
            "*", "fallback"
        );
        Set<String> folders = Set.of("world0", "world-1", "fallback");

        assertEquals("world0", ShaderPack.resolveDimensionFolder(mappings, folders, "Overworld", 0, true));
        assertEquals("world-1", ShaderPack.resolveDimensionFolder(mappings, folders, "Nether", -1, true));
    }

    @Test
    void legacyEndFolderWorksWithoutDimensionProperties() {
        assertEquals("world1", ShaderPack.resolveDimensionFolder(
            Map.of(),
            Set.of("world0", "world-1", "world1"),
            "the_end",
            1,
            false
        ));
    }

    @Test
    void customExactMappingWinsOverWildcard() {
        assertEquals("custom_dim", ShaderPack.resolveDimensionFolder(
            Map.of("Aether", "custom_dim", "*", "world0"),
            Set.of("custom_dim", "world0"),
            "Aether",
            7,
            true
        ));
    }

    @Test
    void customProviderNameWinsOverVanillaNumericIdAndLegacyFolder() {
        assertEquals("custom_dim", ShaderPack.resolveDimensionFolder(
            Map.of("Custom End", "custom_dim", "*", "world0"),
            Set.of("custom_dim", "world0", "world1"),
            "Custom End",
            1,
            true
        ));
    }

    @Test
    void complementaryVanillaFoldersAreAllPinnedThroughWildcardResolution() {
        Map<String, String> mappings = Map.of(
            "minecraft:the_nether", "world-1",
            "minecraft:the_end", "world1",
            "*", "world0"
        );

        assertEquals(Set.of("world0", "world-1", "world1"), ShaderPack.resolvePinnedDimensionFolders(
            mappings,
            Set.of("world0", "world-1", "world1"),
            true
        ));
    }

    @Test
    void mappedUnknownDimensionsUseWildcardThenBase() {
        assertEquals("world0", ShaderPack.resolveDimensionFolder(
            Map.of("*", "world0"),
            Set.of("world0"),
            "Unknown Dimension",
            42,
            true
        ));
        assertNull(ShaderPack.resolveDimensionFolder(
            Map.of(),
            Set.of("world0"),
            "Unknown Dimension",
            42,
            true
        ));
    }

    @Test
    void legacyUnknownDimensionUsesWorldZeroDefault() {
        assertEquals("world0", ShaderPack.resolveDimensionFolder(
            Map.of("minecraft:overworld", "world0"), Set.of("world0"), "Twilight Forest", 7, false));
    }

    @Test
    void legacyExactNumericFolderWinsOverWorldZeroDefault() {
        assertEquals("world7", ShaderPack.resolveDimensionFolder(
            Map.of(), Set.of("world0", "world7"), "Twilight Forest", 7, false));
    }

    @Test
    void legacyPackWithoutWorldZeroUsesBase() {
        assertNull(ShaderPack.resolveDimensionFolder(
            Map.of(), Set.of("world-1", "world1"), "Twilight Forest", 7, false));
    }

    @Test
    void missingExplicitFolderDoesNotSelectLegacyDefaultOrWildcard() {
        assertNull(ShaderPack.resolveDimensionFolder(
            Map.of("Twilight Forest", "missing", "*", "world0"), Set.of("world0", "world7"),
            "Twilight Forest", 7, true));
        assertNull(ShaderPack.resolveDimensionFolder(
            Map.of("*", "missing"), Set.of("world0", "world7"), "Twilight Forest", 7, true));
    }

    @Test
    void legacyDefaultIsPinnedForVanillaDimensionsMissingTheirOwnFolder() {
        assertEquals(Set.of("world0"), ShaderPack.resolvePinnedDimensionFolders(Map.of(), Set.of("world0"), false));
    }
}
