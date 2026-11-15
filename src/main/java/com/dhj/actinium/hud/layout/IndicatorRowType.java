package com.dhj.actinium.hud.layout;

import java.util.Optional;

/**
 * One row the performance indicator can show.
 *
 * <p>The declaration order is the order the available column offers the rows in. Each constant
 * carries the id persisted in the config, which stays stable when the constant is renamed.
 */
public enum IndicatorRowType {
    FPS("fps"),
    FPS_LOW("fps_low"),
    MEMORY("memory"),
    CPU("cpu"),
    GPU("gpu"),
    VRAM("vram");

    private final String id;

    IndicatorRowType(String id) {
        this.id = id;
    }

    /** {@return the id persisted in the config} */
    public String id() {
        return this.id;
    }

    /**
     * Resolves a persisted row id.
     *
     * @param id row id read from the config
     * @return the matching row, or an empty optional for an id this build does not know
     */
    public static Optional<IndicatorRowType> fromId(String id) {
        for (IndicatorRowType type : values()) {
            if (type.id.equals(id)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
