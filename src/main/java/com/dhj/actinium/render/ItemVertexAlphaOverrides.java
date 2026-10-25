package com.dhj.actinium.render;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Query point for the {@link ItemVertexAlphaOverride}s that make Actinium's fast lit item path
 * unsound. {@code ForgeHooksClient.renderLitItem} is intercepted by Actinium whenever no shader
 * pack is in use; a mod that scales item vertex alpha during a GUI open/close animation
 * (NeverEnoughAnimation, through its {@code LightUtil.renderQuadColor} and
 * {@code BufferBuilder.color} hooks) is invisible to both the raw append (which drops the
 * scaling) and the display list cache (which bakes the alpha of the compile frame into the cache
 * and replays it for every later frame). Registered sources are therefore polled before the fast
 * path accepts a model; while any of them is active the item is drawn through Forge's own
 * {@code renderLitItem}, which keeps the fade visible and never caches it.
 */
public final class ItemVertexAlphaOverrides {

    /** The multiplier reported by a source that does not touch vertex alpha. */
    private static final float NO_OVERRIDE = 1.0F;

    /**
     * Copy-on-write so the per-item poll on the client render thread neither blocks nor allocates;
     * registration happens during initialization, long before the first GUI frame.
     */
    private static final List<ItemVertexAlphaOverride> OVERRIDES = new CopyOnWriteArrayList<>();

    private ItemVertexAlphaOverrides() {
    }

    /**
     * Registers an override source. Called once per source from the host mod's init phase, before
     * any GUI is drawn.
     */
    public static void register(ItemVertexAlphaOverride override) {
        OVERRIDES.add(Objects.requireNonNull(override, "override"));
    }

    /**
     * Returns whether any registered source is currently scaling item vertex alpha, in which case
     * the fast lit item path must not be used. Polled on the client render thread for every item
     * draw.
     */
    public static boolean isActive() {
        for (int i = 0; i < OVERRIDES.size(); i++) {
            if (OVERRIDES.get(i).getAlphaMultiplier() != NO_OVERRIDE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes every registered source, so a later registration starts from a clean slate. Used by
     * tests and by reload paths that re-register their sources.
     */
    static void clear() {
        OVERRIDES.clear();
    }
}
