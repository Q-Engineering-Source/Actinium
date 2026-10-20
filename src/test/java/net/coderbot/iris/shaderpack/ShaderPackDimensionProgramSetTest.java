package net.coderbot.iris.shaderpack;

import net.coderbot.iris.Iris;
import net.minecraft.launchwrapper.Launch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the explicit-dimension {@link ShaderPack#getProgramSet(String, int)} entry point: it resolves the
 * dimension it is given rather than the one this client happens to be in, which is what lets a pack be read
 * ahead of time on the main menu, where there is no world at all.
 *
 * <p>The legacy single-argument overload cannot be driven here: it reads the current world through {@code
 * Minecraft.getMinecraft()}, and a headless test has no client instance. It is a one-line delegation to this
 * overload, and what it delegates is the whole rule - which dimension is being asked for - so the rule is
 * what these tests cover.</p>
 */
class ShaderPackDimensionProgramSetTest {

    private static final String OVERWORLD_MARKER = "world0_marker";
    private static final String NETHER_MARKER = "world_minus_one_marker";

    @TempDir
    Path tempDir;

    private String lastDimensionName;

    private int lastDimensionId;

    @BeforeAll
    static void initializeLaunchEnvironment() {
        Launch.blackboard = new HashMap<>();
        Launch.blackboard.put("fml.deobfuscatedEnvironment", false);
    }

    @AfterEach
    void restoreDimensionState() {
        Iris.lastDimensionName = lastDimensionName;
        Iris.lastDimensionId = lastDimensionId;
    }

    @Test
    void resolvesEachDimensionItIsGivenInsteadOfOneGlobalState() throws IOException {
        final ShaderPack pack = readPackWithDimensionFolders();
        rememberDimensionState();

        final ProgramSet overworld = pack.getProgramSet(DimensionId.OVERWORLD.getCanonicalId(), DimensionId.OVERWORLD.getNumericId());
        final ProgramSet nether = pack.getProgramSet(DimensionId.NETHER.getCanonicalId(), DimensionId.NETHER.getNumericId());

        assertNotSame(overworld, nether, "the two dimensions have their own folders, so their program sets differ");
        assertTrue(vertexSource(overworld).contains(OVERWORLD_MARKER));
        assertTrue(vertexSource(nether).contains(NETHER_MARKER));

        // The global state a legacy caller would resolve from is ignored entirely
        Iris.lastDimensionName = DimensionId.NETHER.getCanonicalId();
        Iris.lastDimensionId = DimensionId.NETHER.getNumericId();
        assertSame(overworld, pack.getProgramSet(DimensionId.OVERWORLD.getCanonicalId(), DimensionId.OVERWORLD.getNumericId()),
            "a resolved program set is cached, and the global state does not change which one it is");
    }

    @Test
    void resolvesADimensionNamedWithoutItsNamespace() throws IOException {
        final ShaderPack pack = readPackWithDimensionFolders();
        rememberDimensionState();

        assertSame(pack.getProgramSet(DimensionId.OVERWORLD.getCanonicalId(), DimensionId.OVERWORLD.getNumericId()),
            pack.getProgramSet("Overworld", DimensionId.OVERWORLD.getNumericId()),
            "a pack dimension name and its canonical key resolve to the same folder");
    }

    @Test
    void aDimensionWithoutAFolderOfItsOwnFallsBackToTheBasePrograms() throws IOException {
        final ShaderPack pack = readPackWithDimensionFolders();
        rememberDimensionState();

        final ProgramSet overworld = pack.getProgramSet(DimensionId.OVERWORLD.getCanonicalId(), DimensionId.OVERWORLD.getNumericId());
        final ProgramSet unmapped = pack.getProgramSet("Unknown Dimension", 42);

        assertNotSame(overworld, unmapped);
        assertSame(unmapped, pack.getProgramSet("Another Unknown Dimension", 43),
            "every dimension without a folder of its own falls back to the same base programs");
        assertTrue(vertexSource(unmapped).isEmpty(), "the base folder of this pack holds no programs");
    }

    private void rememberDimensionState() {
        lastDimensionName = Iris.lastDimensionName;
        lastDimensionId = Iris.lastDimensionId;
    }

    private static String vertexSource(ProgramSet programs) {
        return programs.getGbuffersBasic().flatMap(ProgramSource::getVertexSource).orElse("");
    }

    private ShaderPack readPackWithDimensionFolders() throws IOException {
        final Path shaders = Files.createDirectories(tempDir.resolve("Pack/shaders"));
        ShaderPackFixtures.writeProgram(shaders.resolve("world0"), "gbuffers_basic", OVERWORLD_MARKER);
        ShaderPackFixtures.writeProgram(shaders.resolve("world-1"), "gbuffers_basic", NETHER_MARKER);
        ShaderPackFixtures.write(shaders, "dimension.properties", """
            dimension.world0=minecraft:overworld
            dimension.world-1=minecraft:the_nether
            """);

        return ShaderPackFixtures.read(shaders, Map.of());
    }
}
