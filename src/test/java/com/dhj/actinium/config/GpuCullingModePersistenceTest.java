package com.dhj.actinium.config;

import dhj.embeddedt.embeddium.impl.gui.SodiumGameOptions;
import dhj.embeddedt.embeddium.impl.render.culling.GpuCullingMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The GPU culling mode is part of the persisted Actinium options, so a mode chosen in the advanced page has to
 * survive a restart instead of falling back to the default on the next load.
 */
class GpuCullingModePersistenceTest {
    private static final String CONFIG_NAME = "actinium-options-gpu-culling-test.json";
    private static final Path CONFIG_PATH = Path.of("config", CONFIG_NAME);

    @BeforeEach
    void removeStaleConfig() throws IOException {
        Files.deleteIfExists(CONFIG_PATH);
    }

    @AfterEach
    void removeWrittenConfig() throws IOException {
        Files.deleteIfExists(CONFIG_PATH);
    }

    @Test
    void selectedModeSurvivesASaveAndReload() throws IOException {
        SodiumGameOptions options = SodiumGameOptions.load(CONFIG_NAME);
        options.performance.gpuCullingMode = GpuCullingMode.COMPUTE;

        SodiumGameOptions.writeToDisk(options);

        assertEquals(GpuCullingMode.COMPUTE, SodiumGameOptions.load(CONFIG_NAME).performance.gpuCullingMode);
    }
}
