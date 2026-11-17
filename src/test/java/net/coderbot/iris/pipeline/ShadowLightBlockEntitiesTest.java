package net.coderbot.iris.pipeline;

import com.google.common.collect.ImmutableList;
import net.coderbot.iris.shaderpack.PackShadowDirectives;
import net.coderbot.iris.shaderpack.ShaderProperties;
import net.coderbot.iris.shaderpack.StringPair;
import net.coderbot.iris.shaderpack.include.AbsolutePackPath;
import net.coderbot.iris.shaderpack.include.IncludeGraph;
import net.coderbot.iris.shaderpack.option.ShaderPackOptions;
import net.minecraft.init.Blocks;
import net.minecraft.init.Bootstrap;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@code shadowLightBlockEntities}: a pack that switched the full block entity set off can still
 * keep the ones whose block emits light in the shadow map.
 */
class ShadowLightBlockEntitiesTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void bootstrapMinecraft() {
        // ShaderProperties reads the launch environment, and the block states need the registry.
        Launch.blackboard = new HashMap<>();
        Launch.blackboard.put("fml.deobfuscatedEnvironment", false);
        Bootstrap.register();
    }

    @Test
    void lightsOnlyPassIsOffByDefault() throws IOException {
        assertFalse(new PackShadowDirectives(properties("")).shouldRenderLightBlockEntities(),
            "a pack that says nothing keeps the full set only");
    }

    @Test
    void packDirectiveEnablesTheLightsOnlyPass() throws IOException {
        assertTrue(new PackShadowDirectives(properties("shadowLightBlockEntities=true")).shouldRenderLightBlockEntities());
    }

    @Test
    void copiesKeepTheLightsOnlyDirective() throws IOException {
        final PackShadowDirectives enabled = new PackShadowDirectives(properties("shadowLightBlockEntities=true"));
        final PackShadowDirectives disabled = new PackShadowDirectives(properties(""));

        assertTrue(new PackShadowDirectives(enabled).shouldRenderLightBlockEntities());
        assertFalse(new PackShadowDirectives(disabled).shouldRenderLightBlockEntities());
    }

    @Test
    void lightsOnlyKeepsTheBlockEntitiesWhoseBlockEmitsLight() {
        assertTrue(ShadowRenderer.emitsLight(Blocks.GLOWSTONE.getDefaultState(), null, BlockPos.ORIGIN));
        assertFalse(ShadowRenderer.emitsLight(Blocks.STONE.getDefaultState(), null, BlockPos.ORIGIN));
    }

    private ShaderProperties properties(String source) throws IOException {
        final Path entry = tempDir.resolve("entry.glsl");
        Files.writeString(entry, "void main() {}\n");
        final IncludeGraph graph = new IncludeGraph(tempDir,
            ImmutableList.of(AbsolutePackPath.fromAbsolutePath("/entry.glsl")));
        return new ShaderProperties(source, new ShaderPackOptions(graph, Map.of()), List.<StringPair>of(), () -> true);
    }
}
