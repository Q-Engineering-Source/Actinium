package com.dhj.actinium.hud.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable arrangement of the performance indicator rows.
 *
 * <p>Only the enabled column is stored. The available column is always the remaining
 * {@link IndicatorRowType} constants in declaration order, so both columns together hold every row
 * exactly once.
 */
public final class IndicatorLayout {
    private final List<IndicatorRowType> enabled;

    private IndicatorLayout(List<IndicatorRowType> enabled) {
        this.enabled = List.copyOf(enabled);
    }

    /**
     * Reads a persisted layout.
     *
     * <p>Ids this build does not know are dropped and a repeated id keeps its first position, so a
     * config written by another build still yields a usable layout. An empty list stays empty.
     *
     * @param rows persisted row ids, never {@code null}
     * @return layout holding the resolved rows in their stored order
     */
    public static IndicatorLayout fromConfig(List<String> rows) {
        Objects.requireNonNull(rows, "rows");
        List<IndicatorRowType> enabled = new ArrayList<>(rows.size());
        for (String id : rows) {
            IndicatorRowType type = IndicatorRowType.fromId(id).orElse(null);
            if (type != null && !enabled.contains(type)) {
                enabled.add(type);
            }
        }
        return new IndicatorLayout(enabled);
    }

    /** {@return the enabled rows in display order} */
    public List<IndicatorRowType> enabledRows() {
        return this.enabled;
    }

    /** {@return the rows that are not enabled, in declaration order} */
    public List<IndicatorRowType> availableRows() {
        List<IndicatorRowType> available = new ArrayList<>(IndicatorRowType.values().length - this.enabled.size());
        for (IndicatorRowType type : IndicatorRowType.values()) {
            if (!this.enabled.contains(type)) {
                available.add(type);
            }
        }
        return List.copyOf(available);
    }

    /**
     * Enables a row, or moves an already enabled row to another position.
     *
     * <p>{@code index} is the position the row occupies in the resulting enabled column, so moving a
     * row towards the end of its own column counts the positions left after it was lifted out.
     *
     * @param type row to enable or move
     * @param index target position in the resulting enabled column, from {@code 0} to its size
     * @return layout holding the row at {@code index}
     * @throws IndexOutOfBoundsException if {@code index} is negative or larger than that column
     */
    public IndicatorLayout insertEnabled(IndicatorRowType type, int index) {
        Objects.requireNonNull(type, "type");
        List<IndicatorRowType> updated = new ArrayList<>(this.enabled);
        updated.remove(type);
        if (index < 0 || index > updated.size()) {
            throw new IndexOutOfBoundsException("index " + index + " outside [0, " + updated.size() + "]");
        }
        updated.add(index, type);
        return new IndicatorLayout(updated);
    }

    /**
     * Removes a row from the enabled column, which puts it back into the available column.
     *
     * @param type row to remove
     * @return layout without the row, or this layout when the row was not enabled
     */
    public IndicatorLayout removeEnabled(IndicatorRowType type) {
        Objects.requireNonNull(type, "type");
        if (!this.enabled.contains(type)) {
            return this;
        }
        List<IndicatorRowType> updated = new ArrayList<>(this.enabled);
        updated.remove(type);
        return new IndicatorLayout(updated);
    }

    /** {@return the enabled row ids in display order, as persisted in the config} */
    public List<String> toConfig() {
        List<String> ids = new ArrayList<>(this.enabled.size());
        for (IndicatorRowType type : this.enabled) {
            ids.add(type.id());
        }
        return List.copyOf(ids);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IndicatorLayout layout && this.enabled.equals(layout.enabled);
    }

    @Override
    public int hashCode() {
        return this.enabled.hashCode();
    }

    @Override
    public String toString() {
        return "IndicatorLayout" + this.enabled;
    }
}
