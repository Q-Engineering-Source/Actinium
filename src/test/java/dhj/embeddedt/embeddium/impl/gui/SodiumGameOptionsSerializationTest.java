package dhj.embeddedt.embeddium.impl.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SodiumGameOptionsSerializationTest {
    private static final String TEST_CONFIG_NAME = "actinium-options-serialization-test.json";
    private static final Path TEST_CONFIG_PATH = Paths.get("config", TEST_CONFIG_NAME);

    @AfterEach
    void deleteTestConfig() throws IOException {
        Files.deleteIfExists(TEST_CONFIG_PATH);
    }

    @Test
    void disableErrorChecksSurvivesASaveAndReload() {
        SodiumGameOptions options = SodiumGameOptions.load(TEST_CONFIG_NAME);
        assertFalse(options.debug.disableErrorChecks, "error-check skipping must stay off for existing configs");

        options.debug.disableErrorChecks = true;
        options.save();

        assertTrue(SodiumGameOptions.load(TEST_CONFIG_NAME).debug.disableErrorChecks);
    }

    @Test
    void entityRenderDiagnosticsSurvivesASaveAndReload() {
        SodiumGameOptions options = SodiumGameOptions.load(TEST_CONFIG_NAME);
        assertFalse(options.debug.enableEntityRenderDiagnostics);

        options.debug.enableEntityRenderDiagnostics = true;
        options.save();

        assertTrue(SodiumGameOptions.load(TEST_CONFIG_NAME).debug.enableEntityRenderDiagnostics);
    }

    @Test
    void explicitNullExclusionListKeepsTheFieldDefaults() throws IOException {
        Files.createDirectories(TEST_CONFIG_PATH.getParent());
        Files.writeString(TEST_CONFIG_PATH, "{\"performance\":{\"entity_shadow_replay_excluded_classes\":null}}");

        SodiumGameOptions loaded = SodiumGameOptions.load(TEST_CONFIG_NAME);

        assertEquals(new SodiumGameOptions().performance.entityShadowReplayExcludedClasses,
                loaded.performance.entityShadowReplayExcludedClasses);
    }
}
