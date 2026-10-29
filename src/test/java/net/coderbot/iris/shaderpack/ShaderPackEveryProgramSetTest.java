package net.coderbot.iris.shaderpack;

import net.minecraft.launchwrapper.Launch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the enumeration a full compile of a pack walks: every folder that holds shader files, and the
 * base programs when some dimension can still fall back to them.
 *
 * <p>Reading a pack is pure, so this drives the real constructor and the real dimension resolution rather
 * than a stand-in for them.</p>
 */
class ShaderPackEveryProgramSetTest {

    private static final String OVERWORLD_MARKER = "world0_marker";
    private static final String NETHER_MARKER = "world_minus_one_marker";
    private static final String END_MARKER = "custom_marker";

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initializeLaunchEnvironment() {
        Launch.blackboard = new HashMap<>();
        Launch.blackboard.put("fml.deobfuscatedEnvironment", false);
    }

    @Test
    void enumeratesEveryFolderThatHoldsShaderFilesWithTheBaseProgramsLast() throws IOException {
        final Path shaders = Files.createDirectories(tempDir.resolve("Pack/shaders"));
        ShaderPackFixtures.writeProgram(shaders, "gbuffers_basic", "base");
        ShaderPackFixtures.writeProgram(shaders.resolve("world0"), "gbuffers_basic", OVERWORLD_MARKER);
        ShaderPackFixtures.writeProgram(shaders.resolve("world-1"), "gbuffers_basic", NETHER_MARKER);
        ShaderPackFixtures.writeProgram(shaders.resolve("custom"), "gbuffers_basic", END_MARKER);
        ShaderPackFixtures.write(shaders, "dimension.properties", """
            dimension.world0=minecraft:overworld
            dimension.world-1=minecraft:the_nether
            dimension.custom=minecraft:the_end
            """);
        final ShaderPack pack = ShaderPackFixtures.read(shaders, Map.of());

        final Map<String, ProgramSet> sets = pack.getEveryProgramSet();

        // Sorted by folder name, so the order a full compile visits them in is the same from run to run;
        // the base programs are only reachable through the fallback, so they come last.
        assertEquals(Arrays.asList("custom", "world-1", "world0", null), new ArrayList<>(sets.keySet()));
        assertTrue(vertexSource(sets.get("world0")).contains(OVERWORLD_MARKER), "each folder is enumerated with its own programs");
        assertTrue(vertexSource(sets.get("world-1")).contains(NETHER_MARKER));
        assertTrue(vertexSource(sets.get("custom")).contains(END_MARKER));
        assertTrue(vertexSource(sets.get(null)).contains("base"), "the base programs are the ones the fallback uses");
        assertSame(pack.getProgramSet(DimensionId.OVERWORLD.getCanonicalId(), DimensionId.OVERWORLD.getNumericId()),
            sets.get("world0"), "enumerating a folder reuses the ProgramSet the pack already resolved");
    }

    @Test
    void leavesOutTheBaseProgramsWhenEveryDimensionHasAFolderOfItsOwn() throws IOException {
        final Path shaders = Files.createDirectories(tempDir.resolve("Pack/shaders"));
        ShaderPackFixtures.writeProgram(shaders.resolve("world0"), "gbuffers_basic", OVERWORLD_MARKER);
        final ShaderPack pack = ShaderPackFixtures.read(shaders, Map.of());

        final Map<String, ProgramSet> sets = pack.getEveryProgramSet();

        // Legacy folder detection maps the overworld to world0 and lets every unmapped dimension use it
        // too, so nothing resolves to the (empty) base programs.
        assertEquals(List.of("world0"), new ArrayList<>(sets.keySet()));
        assertTrue(vertexSource(sets.get("world0")).contains(OVERWORLD_MARKER));
    }

    @Test
    void aPackWithNoDimensionFoldersOnlyHasTheBasePrograms() throws IOException {
        final Path shaders = Files.createDirectories(tempDir.resolve("Pack/shaders"));
        ShaderPackFixtures.writeProgram(shaders, "gbuffers_basic", "only_base");

        final Map<String, ProgramSet> sets = ShaderPackFixtures.read(shaders, Map.of()).getEveryProgramSet();

        assertEquals(1, sets.size());
        assertTrue(vertexSource(sets.get(null)).contains("only_base"));
    }

    @Test
    void aDimensionWithoutAFolderOfItsOwnFallsBackToTheBasePrograms() {
        assertTrue(ShaderPack.canFallBackToBase(Map.of("minecraft:overworld", "world0"), Set.of("world0"), true),
            "a mapped pack names some dimensions nowhere, and those use the base programs");
        assertFalse(ShaderPack.canFallBackToBase(Map.of("*", "world0"), Set.of("world0"), true),
            "a wildcard mapping covers every dimension, so none of them needs the base programs");
        assertTrue(ShaderPack.canFallBackToBase(Map.of("minecraft:overworld", "missing"), Set.of("world0"), true),
            "a mapping that points at a folder without shader files falls back to the base programs");
        assertFalse(ShaderPack.canFallBackToBase(Map.of(), Set.of("world0"), false),
            "a legacy pack without dimension.properties sends unmapped dimensions to world0");
        assertTrue(ShaderPack.canFallBackToBase(Map.of(), Set.of(), false),
            "a pack with no dimension folders at all only has the base programs");
    }

    private static String vertexSource(ProgramSet programs) {
        return programs.getGbuffersBasic().flatMap(ProgramSource::getVertexSource).orElse("");
    }
}
