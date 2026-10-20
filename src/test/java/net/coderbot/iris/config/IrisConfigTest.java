package net.coderbot.iris.config;

import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisConfigTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void releaseProgramCache() {
        // The switch also drives the program cache, which is process-wide state
        ProgramBinaryCache.setEnabled(false);
    }

    @Test
    void initializeKeepsExplicitlyEnabledShaderPackAfterReload() throws Exception {
        Path properties = tempDir.resolve("shaders.properties");
        IrisConfig config = new IrisConfig(properties);
        config.setShaderPackName("BSL");
        config.setShadersEnabled(true);
        config.save();

        // new instance because reload() calls initialize() on a fresh object after changing the flag
        IrisConfig reloadingConfig = new IrisConfig(properties);
        reloadingConfig.setShadersEnabled(true);
        reloadingConfig.initialize();

        assertTrue(reloadingConfig.areShadersEnabled(), "reload must not turn shaders off when a valid pack is selected");
    }

    @Test
    void initializeKeepsExplicitlyDisabledShaderPackAfterReload() throws Exception {
        Path properties = tempDir.resolve("shaders.properties");
        IrisConfig config = new IrisConfig(properties);
        config.setShaderPackName("BSL");
        config.setShadersEnabled(true);
        config.save();

        // The RAM-only initializer should not re-read the old disk value after the user
        // disabled shaders and reload is about to re-create the pipeline.
        IrisConfig disablingConfig = new IrisConfig(properties);
        disablingConfig.setShadersEnabled(false);
        disablingConfig.initialize();

        assertFalse(disablingConfig.areShadersEnabled(), "reload must not re-enable shaders after the user disabled them");
    }

    @Test
    void initializeDisablesShadersWithoutSelectedPack() throws Exception {
        Path properties = tempDir.resolve("shaders.properties");
        IrisConfig config = new IrisConfig(properties);
        config.setShaderPackName(null);
        config.setShadersEnabled(true);
        config.save();

        IrisConfig loaded = new IrisConfig(properties);
        loaded.initialize();

        assertFalse(loaded.areShadersEnabled(), "no shader pack means shaders cannot be active");
    }

    @Test
    void theSavedProgramSwitchIsStoredAsSoonAsItIsToggled() throws Exception {
        final Path properties = tempDir.resolve("shaders.properties");
        final IrisConfig config = new IrisConfig(properties);
        config.initialize();
        assertFalse(config.shouldSaveCompiledShaders(), "saving linked programs is off by default");

        // The screen must not need a second save after the switch is pressed
        config.setSaveCompiledShaders(true);
        assertTrue(config.shouldSaveCompiledShaders());

        final IrisConfig reloaded = new IrisConfig(properties);
        reloaded.initialize();

        assertTrue(reloaded.shouldSaveCompiledShaders(), "the switch has to survive a restart without another save");
    }
}