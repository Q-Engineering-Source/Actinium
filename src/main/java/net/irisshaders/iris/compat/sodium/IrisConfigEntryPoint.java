package net.irisshaders.iris.compat.sodium;

import net.coderbot.iris.Iris;
import net.coderbot.iris.gui.option.IrisVideoSettings;
import net.coderbot.iris.gui.screen.ShaderPackScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import dhj.embeddedt.embeddium.api.options.OptionIdentifier;
import dhj.embeddedt.embeddium.api.options.control.ControlValueFormatter;
import dhj.embeddedt.embeddium.api.options.control.SliderControl;
import dhj.embeddedt.embeddium.api.options.control.TickBoxControl;
import dhj.embeddedt.embeddium.api.options.structure.ExternalPage;
import dhj.embeddedt.embeddium.api.options.structure.OptionGroup;
import dhj.embeddedt.embeddium.api.options.structure.OptionImpl;
import dhj.embeddedt.embeddium.api.options.structure.OptionPage;
import dhj.embeddedt.embeddium.api.options.structure.OptionStorage;
import dhj.embeddedt.embeddium.impl.gui.framework.TextComponent;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Exposes the supported Iris settings through the embeddium option model.
 * The entrypoint deliberately binds only the legacy Iris shadow distance
 * value; shader pack selection is a navigation-only external page.
 */
public final class IrisConfigEntryPoint {
    private static final Logger LOGGER = LogManager.getLogger("Actinium-Iris-Config");
    private static final String MOD_ID = "iris";
    private static final int SHADOW_DISTANCE_MIN = 0;
    private static final int SHADOW_DISTANCE_MAX = 256;
    private static final int SHADOW_DISTANCE_DEFAULT = 32;

    private final Consumer<Integer> shadowSaver;
    private final Supplier<Integer> shadowLoader;
    private final Consumer<Boolean> shaderCacheSaver;
    private final Supplier<Boolean> shaderCacheLoader;
    private final Consumer<GuiScreen> shaderPackOpener;

    /** Creates the production entrypoint backed by Iris' legacy settings and screen. */
    public IrisConfigEntryPoint() {
        this(value -> {
            IrisVideoSettings.shadowDistance = value;
            saveIrisConfig();
        }, () -> IrisVideoSettings.shadowDistance,
                IrisConfigEntryPoint::setSaveCompiledShaders,
                () -> Iris.getIrisConfig().shouldSaveCompiledShaders(),
                parent -> Minecraft.getMinecraft().displayGuiScreen(new ShaderPackScreen(parent)));
    }

    /** Allows direct logic tests to supply persistence and screen boundaries without client bootstrapping. */
    public IrisConfigEntryPoint(Consumer<Integer> shadowSaver, Supplier<Integer> shadowLoader,
                                Consumer<GuiScreen> shaderPackOpener) {
        this(shadowSaver, shadowLoader, value -> { }, () -> false, shaderPackOpener);
    }

    /** Variant that also wires the compiled shader cache toggle. */
    public IrisConfigEntryPoint(Consumer<Integer> shadowSaver, Supplier<Integer> shadowLoader,
                                Consumer<Boolean> shaderCacheSaver, Supplier<Boolean> shaderCacheLoader,
                                Consumer<GuiScreen> shaderPackOpener) {
        this.shadowSaver = Objects.requireNonNull(shadowSaver, "Iris shadow saver must not be null");
        this.shadowLoader = Objects.requireNonNull(shadowLoader, "Iris shadow loader must not be null");
        this.shaderCacheSaver = Objects.requireNonNull(shaderCacheSaver, "Iris shader cache saver must not be null");
        this.shaderCacheLoader = Objects.requireNonNull(shaderCacheLoader, "Iris shader cache loader must not be null");
        this.shaderPackOpener = Objects.requireNonNull(shaderPackOpener, "Iris shader pack opener must not be null");
    }

    /** Builds the Iris option page (shadow distance slider) and the shader pack external page. */
    public List<OptionPage> createPages() {
        OptionImpl<Object, Integer> shadow = OptionImpl.createBuilder(int.class, new IrisStorage())
                .setId(OptionIdentifier.create(MOD_ID, "shadow_distance", int.class))
                .setName(TextComponent.translatable("options.iris.shadowDistance"))
                .setTooltip(TextComponent.translatable(IrisVideoSettings.isShadowDistanceSliderEnabled()
                        ? "options.iris.shadowDistance.enabled"
                        : "options.iris.shadowDistance.disabled"))
                .setControl(option -> new SliderControl(option,
                        SHADOW_DISTANCE_MIN, SHADOW_DISTANCE_MAX, 1, ControlValueFormatter.number()))
                .setBinding((ignored, value) -> this.shadowSaver.accept(value), ignored -> this.shadowLoader.get())
                .setDefaultValue(SHADOW_DISTANCE_DEFAULT)
                .setEnabledPredicate(() -> Iris.enabled)
                .build();

        OptionGroup group = OptionGroup.createBuilder()
                .setId(OptionIdentifier.create(MOD_ID, "shadow"))
                .add(shadow)
                .build();

        OptionImpl<Object, Boolean> saveCompiled = OptionImpl.createBuilder(boolean.class, new IrisStorage())
                .setId(OptionIdentifier.create(MOD_ID, "save_compiled_shaders", boolean.class))
                .setName(TextComponent.translatable("options.iris.saveCompiledShaders"))
                .setTooltip(TextComponent.translatable("options.iris.saveCompiledShaders.tooltip"))
                .setControl(TickBoxControl::new)
                .setBinding((ignored, value) -> this.shaderCacheSaver.accept(value), ignored -> this.shaderCacheLoader.get())
                .setDefaultValue(false)
                .setEnabledPredicate(() -> Iris.enabled)
                .build();

        OptionGroup cacheGroup = OptionGroup.createBuilder()
                .setId(OptionIdentifier.create(MOD_ID, "shader_cache"))
                .add(saveCompiled)
                .build();

        OptionPage page = new OptionPage(
                OptionIdentifier.create(MOD_ID, "video_settings"),
                TextComponent.translatable("options.iris.title"),
                List.of(group, cacheGroup));

        ExternalPage shaderPacks = new ExternalPage(
                OptionIdentifier.create(MOD_ID, "shader_pack_selection"),
                TextComponent.translatable("options.iris.shaderPackSelection"),
                this::openShaderPackScreen);

        return List.of(page, shaderPacks);
    }

    private void openShaderPackScreen(GuiScreen parent) {
        this.shaderPackOpener.accept(parent);
    }

    private static void saveIrisConfig() {
        try {
            Iris.getIrisConfig().save();
        } catch (IOException exception) {
            LOGGER.error("Failed to save Iris configuration after applying options", exception);
        }
    }

    private static void setSaveCompiledShaders(boolean save) {
        try {
            Iris.getIrisConfig().setSaveCompiledShaders(save);
        } catch (IOException exception) {
            LOGGER.error("Failed to save the compiled shader cache setting", exception);
        }
    }

    /** Placeholder storage: Iris values read/write IrisVideoSettings; save persists to disk. */
    private static final class IrisStorage implements OptionStorage<Object> {
        @Override
        public Object getData() {
            return this;
        }

        @Override
        public void save() {
            saveIrisConfig();
        }
    }
}
