package dhj.embeddedt.embeddium.impl.gui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SodiumGameOptionsSerializationTest {
    private static final String TEST_CONFIG_NAME = "actinium-options-serialization-test.json";
    private static final Path TEST_CONFIG_PATH = Paths.get("config", TEST_CONFIG_NAME);

    @BeforeEach
    void deleteStaleConfig() throws IOException {
        Files.deleteIfExists(TEST_CONFIG_PATH);
    }

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

    @Test
    void performanceIndicatorSurvivesASaveAndReload() {
        SodiumGameOptions options = SodiumGameOptions.load(TEST_CONFIG_NAME);
        options.performanceIndicator.enabled = true;
        options.performanceIndicator.refreshIntervalMillis = 1250;
        options.performanceIndicator.rows = new ArrayList<>(List.of("gpu", "fps", "memory"));

        options.save();

        SodiumGameOptions reloaded = SodiumGameOptions.load(TEST_CONFIG_NAME);
        assertTrue(reloaded.performanceIndicator.enabled);
        assertEquals(1250, reloaded.performanceIndicator.refreshIntervalMillis);
        assertEquals(List.of("gpu", "fps", "memory"), reloaded.performanceIndicator.rows);
    }

    @Test
    void emptyPerformanceIndicatorRowsStayEmptyAfterReload() {
        SodiumGameOptions options = SodiumGameOptions.load(TEST_CONFIG_NAME);
        options.performanceIndicator.rows = new ArrayList<>();

        options.save();

        assertTrue(SodiumGameOptions.load(TEST_CONFIG_NAME).performanceIndicator.rows.isEmpty());
    }

    @Test
    void storedPerformanceIndicatorKeysPopulateTheSettings() throws IOException {
        Files.createDirectories(TEST_CONFIG_PATH.getParent());
        Files.writeString(
            TEST_CONFIG_PATH,
            "{\"performance_indicator\": {\"enabled\": true, \"refresh_interval_millis\": 1250, "
                + "\"rows\": [\"memory\", \"fps\"]}}",
            StandardCharsets.UTF_8
        );

        SodiumGameOptions options = SodiumGameOptions.load(TEST_CONFIG_NAME);

        assertTrue(options.performanceIndicator.enabled);
        assertEquals(1250, options.performanceIndicator.refreshIntervalMillis);
        assertEquals(List.of("memory", "fps"), options.performanceIndicator.rows);
    }

    @Test
    void nullPerformanceIndicatorRowsRestoreTheDefaultRow() throws IOException {
        Files.createDirectories(TEST_CONFIG_PATH.getParent());
        Files.writeString(TEST_CONFIG_PATH, "{\"performance_indicator\": {\"rows\": null}}", StandardCharsets.UTF_8);

        SodiumGameOptions options = SodiumGameOptions.load(TEST_CONFIG_NAME);

        assertEquals(List.of("fps"), options.performanceIndicator.rows);
    }
}
