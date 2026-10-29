package com.dhj.actinium.render.terrain.compile;

import java.util.List;

/** Keeps model face-culling and post-transform empty-quad decisions shared with direct tests. */
public final class DispatchedModelRenderDecision {
    private DispatchedModelRenderDecision() {
    }

    /** Returns whether a face with quads survives the vanilla side-culling policy. */
    public static boolean shouldRenderFace(boolean hasQuads, boolean checkSides,
                                           boolean sideVisible, boolean neighbourHidden) {
        return hasQuads && (!checkSides || sideVisible || neighbourHidden);
    }

    /** Returns whether a transformed list still contains quads that can be submitted. */
    public static boolean hasQuads(List<?> quads) {
        return !quads.isEmpty();
    }
}
