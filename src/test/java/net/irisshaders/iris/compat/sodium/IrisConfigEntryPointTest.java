package net.irisshaders.iris.compat.sodium;

import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCache;
import com.gtnewhorizons.angelica.glsm.shader.ShaderDiskCache;
import net.coderbot.iris.PrecompileQueue;
import net.coderbot.iris.config.IrisConfig;
import net.coderbot.iris.gui.option.IrisVideoSettings;
import dhj.embeddedt.embeddium.api.options.structure.ExternalPage;
import dhj.embeddedt.embeddium.api.options.structure.Option;
import dhj.embeddedt.embeddium.api.options.structure.OptionPage;
import dhj.embeddedt.embeddium.impl.gui.framework.TextComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisConfigEntryPointTest {
    @AfterEach
    void resetCaches() {
        PrecompileQueue.cancel();
        ProgramBinaryCache.resetForTesting();
        ShaderDiskCache.resetForTesting();
    }

    @Test
    void buildsShadowOptionPageAndExternalPageAndAppliesInjectedBinding() {
        AtomicInteger persisted = new AtomicInteger(32);
        AtomicBoolean openedCalled = new AtomicBoolean();
        IrisConfigEntryPoint entryPoint = new IrisConfigEntryPoint(persisted::set, persisted::get,
                ignored -> openedCalled.set(true));

        List<OptionPage> pages = entryPoint.createPages();
        assertEquals(2, pages.size());
        OptionPage videoSettings = pages.stream()
                .filter(page -> "video_settings".equals(page.getId().getPath()))
                .findFirst().orElseThrow();
        ExternalPage shaderPacks = pages.stream()
                .filter(ExternalPage.class::isInstance)
                .map(ExternalPage.class::cast)
                .findFirst().orElseThrow();

        assertEquals("iris", videoSettings.getId().getModId());
        assertTrue(videoSettings.getName() instanceof TextComponent.Translatable);
        assertEquals("options.iris.title", ((TextComponent.Translatable) videoSettings.getName()).keys().get(0));
        assertEquals("options.iris.shaderPackSelection",
                ((TextComponent.Translatable) shaderPacks.getName()).keys().get(0));

        shaderPacks.getScreenConsumer().accept(null);
        assertTrue(openedCalled.get());

        @SuppressWarnings("unchecked")
        Option<Integer> shadow = (Option<Integer>) videoSettings.getOptions().stream()
                .filter(option -> "shadow_distance".equals(option.getId().getPath()))
                .findFirst().orElseThrow();
        assertEquals("options.iris.shadowDistance",
                ((TextComponent.Translatable) shadow.getName()).keys().get(0));
        String expectedTooltipKey = IrisVideoSettings.isShadowDistanceSliderEnabled()
                ? "options.iris.shadowDistance.enabled"
                : "options.iris.shadowDistance.disabled";
        assertEquals(expectedTooltipKey,
                ((TextComponent.Translatable) shadow.getTooltip()).keys().get(0));

        shadow.setValue(64);
        shadow.applyChanges();
        assertEquals(64, persisted.get());
    }

    @Test
    void externalPageActivationSkipsVideoSettingsWhenOpeningShaderPacks() {
        IrisConfigEntryPoint entryPoint = new IrisConfigEntryPoint(value -> { }, () -> 32, ignored -> { });
        List<OptionPage> pages = entryPoint.createPages();
        assertInstanceOf(ExternalPage.class, pages.get(1));
        assertEquals("shader_pack_selection", pages.get(1).getId().getPath());
    }

    @Test
    void compiledShaderCacheToggleReadsAndWritesTheInjectedBinding() {
        AtomicBoolean persisted = new AtomicBoolean(false);
        IrisConfigEntryPoint entryPoint = new IrisConfigEntryPoint(value -> { }, () -> 32,
                persisted::set, persisted::get, ignored -> { });

        OptionPage videoSettings = entryPoint.createPages().stream()
                .filter(page -> "video_settings".equals(page.getId().getPath()))
                .findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        Option<Boolean> saveCompiled = (Option<Boolean>) videoSettings.getOptions().stream()
                .filter(option -> "save_compiled_shaders".equals(option.getId().getPath()))
                .findFirst().orElseThrow();
        assertEquals("options.iris.saveCompiledShaders",
                ((TextComponent.Translatable) saveCompiled.getName()).keys().get(0));
        assertEquals(false, saveCompiled.getValue());

        saveCompiled.setValue(true);
        saveCompiled.applyChanges();
        assertTrue(persisted.get());
    }

    @Test
    void turningTheCacheOnOnlySavesTheSetting(@TempDir Path dir) throws IOException {
        final IrisConfig config = enabledConfig(dir, true);

        IrisConfigEntryPoint.applySaveCompiledShaders(true, config);

        assertTrue(config.shouldSaveCompiledShaders());
        assertFalse(PrecompileQueue.isRunning(), "enabling the cache must not queue any compilation");
        assertFalse(ProgramBinaryCache.isFullCompileRunning());
    }

    @Test
    void turningTheCacheOffDeletesCompiledPrograms(@TempDir Path dir) throws IOException {
        final IrisConfig config = enabledConfig(dir, true);
        ShaderDiskCache.configure(dir.resolve("cache"), "cache-toggle-test");
        ProgramBinaryCache.useDriverIdForTesting("test-driver");
        ProgramBinaryCache.setEnabled(true);
        ProgramBinaryCache.usePackForPrecompile("pack");
        ProgramBinaryCache.beginFullCompile();
        ProgramBinaryCache.finishFullCompile("settings");
        assertTrue(ProgramBinaryCache.wasCompiledFor("settings"));

        IrisConfigEntryPoint.applySaveCompiledShaders(false, config);

        assertFalse(config.shouldSaveCompiledShaders());
        assertFalse(ProgramBinaryCache.wasCompiledFor("settings"));
    }

    private static IrisConfig enabledConfig(Path dir, boolean shadersEnabled) throws IOException {
        final IrisConfig config = new IrisConfig(dir.resolve("shaders.properties"));
        config.setShaderPackName("pack");
        config.setShadersEnabled(shadersEnabled);
        config.save();
        config.initialize();
        return config;
    }
}