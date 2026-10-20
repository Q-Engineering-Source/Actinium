package net.coderbot.iris.shaderpack;

import net.coderbot.iris.Iris;
import net.coderbot.iris.features.FeatureFlags;
import net.minecraft.launchwrapper.Launch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers that reading a pack never acts on what it finds. A pack whose required features this context
 * cannot provide is parsed in full, reports them, and leaves the decision to disable shaders to the
 * main-thread load path - which is the only place allowed to touch the config, the pipeline and the
 * renderer.
 *
 * <p>The seam is the config: applying the decision used to start with {@code Iris.getIrisConfig()}, which is
 * null until the game has initialized, so a constructor that still applied it could not return here at
 * all.</p>
 */
class ShaderPackRequiredFeaturesTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initializeLaunchEnvironment() {
        Launch.blackboard = new HashMap<>();
        Launch.blackboard.put("fml.deobfuscatedEnvironment", false);
    }

    @Test
    void aPackWithoutRequiredFeaturesReportsNone() throws IOException {
        final ShaderPack pack = ShaderPackFixtures.readMinimal(shadersDirectory());
        assertTrue(pack.getMissingRequiredFeatures().isEmpty());
    }

    @Test
    void aPackWhoseRequiredFeaturesAreAvailableReportsNone() throws IOException {
        ShaderPackFixtures.writeProgram(shadersDirectory(), "gbuffers_basic", "base");
        ShaderPackFixtures.write(shadersDirectory(), "shaders.properties",
            "iris.features.required=SEPARATE_HARDWARE_SAMPLERS\n");

        final ShaderPack pack = ShaderPackFixtures.read(shadersDirectory(), Map.of());
        assertTrue(pack.getMissingRequiredFeatures().isEmpty());
    }

    @Test
    void aRequiredFeatureThisContextCannotProvideIsReportedInsteadOfApplied() throws IOException {
        ShaderPackFixtures.writeProgram(shadersDirectory(), "gbuffers_basic", "base");
        ShaderPackFixtures.write(shadersDirectory(), "shaders.properties",
            "iris.features.required=TESSELLATION_SHADERS\n");

        // The config the old constructor reached for; the load path is the only thing that may build it.
        assertNull(Iris.getIrisConfig(), "the test runs before the game has initialized its config");

        final ShaderPack pack = assertDoesNotThrow(() -> ShaderPackFixtures.read(shadersDirectory(), Map.of()),
            "reading a pack must not disable shaders, reload the pipeline or touch the config");

        assertEquals(List.of(FeatureFlags.TESSELLATION_SHADERS), pack.getMissingRequiredFeatures(),
            "the main-thread load path decides what a missing required feature means from this list");
        assertNull(Iris.getIrisConfig(), "reading a pack must leave the config untouched");
    }

    @Test
    void aRequiredFeatureThePackMisspellsIsReportedAsUnknown() throws IOException {
        ShaderPackFixtures.writeProgram(shadersDirectory(), "gbuffers_basic", "base");
        ShaderPackFixtures.write(shadersDirectory(), "shaders.properties",
            "iris.features.required=NOT_A_REAL_FEATURE\n");

        final ShaderPack pack = ShaderPackFixtures.read(shadersDirectory(), Map.of());
        assertEquals(List.of(FeatureFlags.UNKNOWN), pack.getMissingRequiredFeatures());
    }

    private Path shadersDirectory() {
        return tempDir.resolve("shaders");
    }
}
