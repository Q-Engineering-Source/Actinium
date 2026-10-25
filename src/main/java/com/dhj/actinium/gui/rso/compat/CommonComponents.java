package com.dhj.actinium.gui.rso.compat;

/**
 * Newer-Minecraft style common component helpers adapted to the 1.12.2 client.
 */
public final class CommonComponents {

    private CommonComponents() {
    }

    public static Component optionNameValue(Component name, Component value) {
        return new MutableComponent(name.unwrap().createCopy())
                .append(literal(": "))
                .append(value);
    }

    public static Component optionStatus(boolean enabled) {
        return translatable(enabled ? "options.on" : "options.off");
    }

    public static Component translatable(String key, Object... args) {
        return Component.translatable(key, args);
    }

    public static Component literal(String text) {
        return Component.literal(text);
    }
}
