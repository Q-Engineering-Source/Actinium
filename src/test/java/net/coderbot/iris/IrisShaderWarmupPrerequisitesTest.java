package net.coderbot.iris;

import net.coderbot.iris.pipeline.transform.TransformPatcher;
import net.coderbot.iris.shaderpack.DimensionId;
import net.coderbot.iris.shaderpack.ProgramSet;
import net.coderbot.iris.shaderpack.ShaderPack;
import net.coderbot.iris.shaderpack.ShaderPackFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the state a transform warmup must find initialized before it submits anything, and the refusal
 * that keeps it from filling the transform cache with products that state would not produce.
 *
 * <p>The state itself is only reachable after the render system has initialized, which a headless test does
 * not do, so what is covered here is the gate: preparation refuses to run at all without those
 * capabilities, and a warmup that finds them missing transforms nothing.</p>
 */
class IrisShaderWarmupPrerequisitesTest {

    @TempDir
    Path tempDir;

    @Test
    void preparingTheWarmupRequiresAnInitializedRenderSystem() {
        assertFalse(Iris.isShaderWarmupPrepared());

        assertThrows(IllegalStateException.class, Iris::prepareShaderWarmup,
            "the GLSL capabilities a transform hoists for are only known after the render system initialized");
        assertFalse(Iris.isShaderWarmupPrepared(), "a refused preparation must not report the transformer as initialized");
    }

    @Test
    void transformsAreNotWarmedBeforeTheTransformerIsInitialized() throws IOException {
        assertFalse(Iris.isShaderWarmupPrepared());
        final int cachedBefore = TransformPatcher.cacheSize();

        Iris.warmupPreparedTransforms("Pack", programs());

        assertEquals(cachedBefore, TransformPatcher.cacheSize(),
            "the transform cache does not key on the hoisting state, so warming before it is known must cache nothing");
    }

    private ProgramSet programs() throws IOException {
        final ShaderPack pack = ShaderPackFixtures.readMinimal(Files.createDirectories(tempDir.resolve("Pack/shaders")));
        return pack.getProgramSet(DimensionId.OVERWORLD.getCanonicalId(), DimensionId.OVERWORLD.getNumericId());
    }
}
