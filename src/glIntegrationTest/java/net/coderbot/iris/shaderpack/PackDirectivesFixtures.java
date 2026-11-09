package net.coderbot.iris.shaderpack;

import java.util.Set;

/**
 * Builds the shader pack directives a render target test needs. {@link PackDirectives} keeps its constructors
 * package private, so tests outside this package obtain instances through this class.
 */
public final class PackDirectivesFixtures {
    private PackDirectivesFixtures() {
    }

    /**
     * Returns directives that declare colortex0 as the only render target, with default formats and no scale
     * overrides.
     */
    public static PackDirectives colortex0() {
        return new PackDirectives(Set.of(0), ShaderProperties.empty());
    }
}
