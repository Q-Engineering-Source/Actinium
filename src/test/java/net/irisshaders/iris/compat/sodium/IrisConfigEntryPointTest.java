package net.irisshaders.iris.compat.sodium;

import com.gtnewhorizons.angelica.glsm.RenderSystem;
import net.coderbot.iris.gui.option.IrisVideoSettings;
import dhj.embeddedt.embeddium.api.options.control.TickBoxControl;
import dhj.embeddedt.embeddium.api.options.structure.ExternalPage;
import dhj.embeddedt.embeddium.api.options.structure.Option;
import dhj.embeddedt.embeddium.api.options.structure.OptionPage;
import dhj.embeddedt.embeddium.impl.gui.framework.TextComponent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisConfigEntryPointTest {
    @Test
    void buildsShadowOptionPageAndExternalPageAndAppliesInjectedBinding() {
        AtomicInteger persisted = new AtomicInteger(32);
        AtomicBoolean saveCompiledPersisted = new AtomicBoolean();
        AtomicBoolean openedCalled = new AtomicBoolean();
        IrisConfigEntryPoint entryPoint = new IrisConfigEntryPoint(persisted::set, persisted::get,
                ignored -> openedCalled.set(true), saveCompiledPersisted::set, saveCompiledPersisted::get);

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

        @SuppressWarnings("unchecked")
        Option<Boolean> saveCompiled = (Option<Boolean>) videoSettings.getOptions().stream()
                .filter(option -> "save_compiled_shaders".equals(option.getId().getPath()))
                .findFirst().orElseThrow();
        assertInstanceOf(TickBoxControl.class, saveCompiled.getControl());
        assertEquals("options.iris.saveCompiledShaders",
                ((TextComponent.Translatable) saveCompiled.getName()).keys().get(0));
        String expectedSaveCompiledTooltipKey = RenderSystem.supportsProgramBinary()
                ? "options.iris.saveCompiledShaders.tooltip"
                : "options.iris.saveCompiledShaders.tooltipUnsupported";
        assertEquals(expectedSaveCompiledTooltipKey,
                ((TextComponent.Translatable) saveCompiled.getTooltip()).keys().get(0));

        saveCompiled.setValue(true);
        saveCompiled.applyChanges();
        assertTrue(saveCompiledPersisted.get());
    }

    @Test
    void externalPageActivationSkipsVideoSettingsWhenOpeningShaderPacks() {
        IrisConfigEntryPoint entryPoint = new IrisConfigEntryPoint(value -> { }, () -> 32, ignored -> { },
                value -> { }, () -> false);
        List<OptionPage> pages = entryPoint.createPages();
        assertInstanceOf(ExternalPage.class, pages.get(1));
        assertEquals("shader_pack_selection", pages.get(1).getId().getPath());
    }
}
