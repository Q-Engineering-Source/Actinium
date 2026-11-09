package net.coderbot.iris.celeritas;

import dhj.embeddedt.embeddium.impl.render.chunk.RenderPassConfiguration;
import dhj.embeddedt.embeddium.impl.render.chunk.terrain.TerrainRenderPass;

public enum IrisTerrainPass {
    SHADOW("shadow"),
    SHADOW_CUTOUT("shadow"),
    SHADOW_TRANSLUCENT("shadow_water"),
    GBUFFER_SOLID("gbuffers_terrain"),
    GBUFFER_CUTOUT("gbuffers_terrain_cutout"),
    GBUFFER_TRANSLUCENT("gbuffers_water");

    public static final IrisTerrainPass[] VALUES = values();

    private final String name;

    IrisTerrainPass(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public boolean isShadow() {
        return this == SHADOW || this == SHADOW_CUTOUT || this == SHADOW_TRANSLUCENT;
    }

    /**
     * Whether the pass keeps vanilla's opaque alpha semantics. Vanilla draws the solid layer with
     * the alpha test disabled, so these passes must neither enable an alpha test nor discard
     * fragments by atlas alpha.
     */
    public boolean usesOpaqueAlpha() {
        return this == GBUFFER_SOLID || this == SHADOW;
    }

    public TerrainRenderPass toTerrainPass(RenderPassConfiguration<?> config) {
        return switch (this) {
            case SHADOW, GBUFFER_SOLID -> config.defaultSolidMaterial().pass;
            case SHADOW_CUTOUT, GBUFFER_CUTOUT -> config.defaultCutoutMippedMaterial().pass;
            case SHADOW_TRANSLUCENT, GBUFFER_TRANSLUCENT -> config.defaultTranslucentMaterial().pass;
        };
    }

    public static IrisTerrainPass fromTerrainPass(TerrainRenderPass pass, boolean isShadow) {
        return switch (pass.semantic()) {
            case WATER, TRANSLUCENT -> isShadow ? SHADOW_TRANSLUCENT : GBUFFER_TRANSLUCENT;
            case CUTOUT -> isShadow ? SHADOW_CUTOUT : GBUFFER_CUTOUT;
            case SOLID -> isShadow ? SHADOW : GBUFFER_SOLID;
        };
    }
}
