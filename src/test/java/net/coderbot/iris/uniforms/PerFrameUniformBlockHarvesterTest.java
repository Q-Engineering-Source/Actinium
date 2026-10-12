package net.coderbot.iris.uniforms;

import com.gtnewhorizons.angelica.glsm.backend.BackendManager;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks;
import com.gtnewhorizons.angelica.glsm.hooks.PerFrameUniformBlock;
import com.gtnewhorizons.angelica.glsm.shader.UniformType;
import net.coderbot.iris.gl.state.FogMode;
import net.coderbot.iris.gl.state.ValueUpdateNotifier;
import net.coderbot.iris.gl.uniform.ManifestCollectingHolder;
import net.coderbot.iris.gl.uniform.UniformManifest;
import net.minecraft.launchwrapper.Launch;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2i;
import org.joml.Vector2ic;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static net.coderbot.iris.gl.uniform.UniformUpdateFrequency.PER_FRAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerFrameUniformBlockHarvesterTest {

    private static final Vector3fc VEC3 = new Vector3f();
    private static final Matrix4fc MAT4 = new Matrix4f();

    @BeforeAll
    static void initializeLaunchEnvironment() {
        Launch.blackboard = new HashMap<>();
        Launch.blackboard.put("fml.deobfuscatedEnvironment", false);
    }

    @BeforeEach
    void resetBlocks() {
        PerFrameUniformBlockHarvester.clear();
    }

    /** A pack's per-frame declarations, as the real registration code would report them. */
    private static ManifestCollectingHolder declaredUniforms() {
        final ManifestCollectingHolder holder = new ManifestCollectingHolder();
        holder
            .uniform3f(PER_FRAME, "cameraPosition", () -> VEC3)
            .uniform3f(PER_FRAME, "fogColor", () -> VEC3)
            .uniform1f(PER_FRAME, "iris_FogStart", () -> 0.0f)
            .uniform1f(PER_FRAME, "iris_FogEnd", () -> 0.0f)
            .uniform1f(PER_FRAME, "iris_FogDensity", () -> 0.0f)
            .uniformMatrix(PER_FRAME, "gbufferModelView", () -> MAT4)
            .uniformMatrix(PER_FRAME, "gbufferModelViewInverse", () -> MAT4)
            .uniformMatrix(PER_FRAME, "gbufferProjection", () -> MAT4)
            .uniformMatrix(PER_FRAME, "shadowModelView", () -> MAT4);
        return holder;
    }

    private static List<String> blockNames(UniformManifest manifest) {
        return PerFrameUniformBlockHarvester.toBlock(manifest, manifest).members().stream()
            .map(PerFrameUniformBlock.Member::name)
            .toList();
    }

    private static List<String> perPassNames(UniformManifest manifest) {
        return PerFrameUniformBlockHarvester.toPerPassBlock(manifest, manifest).members().stream()
            .map(PerFrameUniformBlock.Member::name)
            .toList();
    }

    @Test
    void liveStateUniformsAreExcluded() {
        final List<String> names = blockNames(declaredUniforms().build());
        for (String excluded : List.of("fogColor", "iris_FogStart", "iris_FogEnd", "iris_FogDensity",
            "gbufferModelView", "gbufferModelViewInverse", "gbufferProjection")) {
            assertFalse(names.contains(excluded), excluded + " reads live per-draw state and must stay on the push path");
        }
    }

    @Test
    void frameGlobalUniformsAreKept() {
        final List<String> names = blockNames(declaredUniforms().build());
        assertTrue(names.contains("cameraPosition"), "camera position is frame-scoped");
        assertTrue(names.contains("shadowModelView"), "shadow matrices are pure functions of pack directives");
    }

    @Test
    void blockLayoutFollowsDeclarationOrder() {
        assertEquals(List.of("cameraPosition", "shadowModelView"), blockNames(declaredUniforms().build()),
            "the block layout is the declaration order of everything that stays in it");
    }

    @Test
    void perPassBlockHoldsExactlyTheLiveStateUniforms() {
        assertEquals(List.of("fogColor", "iris_FogStart", "iris_FogEnd", "iris_FogDensity",
            "gbufferModelView", "gbufferModelViewInverse", "gbufferProjection"),
            perPassNames(declaredUniforms().build()));
    }

    @Test
    void blocksAreDisjoint() {
        final UniformManifest manifest = declaredUniforms().build();
        final List<String> perFrame = blockNames(manifest);
        for (String name : perPassNames(manifest)) {
            assertFalse(perFrame.contains(name), name + " must live in exactly one block");
        }
    }

    @Test
    void dynamicUniformsStayOutOfTheBlock() {
        final ManifestCollectingHolder holder = declaredUniforms();
        holder.uniform2i("atlasSize", () -> new Vector2i(), ValueUpdateNotifier.NONE)
            .uniform1i("gtextureId", () -> 0, ValueUpdateNotifier.NONE);

        final UniformManifest manifest = holder.build();
        assertEquals(List.of("atlasSize", "gtextureId"), List.copyOf(manifest.dynamicNames()));
        assertFalse(blockNames(manifest).contains("atlasSize"), "a pushed uniform is not part of the frame block");
    }

    @Test
    void externallyManagedUniformsStayOutOfTheBlock() {
        final ManifestCollectingHolder holder = declaredUniforms();
        holder.uniformMatrix(PER_FRAME, "iris_ModelViewMatrix", () -> MAT4)
            .externallyManagedUniform("iris_ModelViewMatrix", UniformType.MAT4);

        final UniformManifest manifest = holder.build();
        assertFalse(blockNames(manifest).contains("iris_ModelViewMatrix"),
            "another owner fills this uniform, so declaring it in the block would fight that owner");
    }

    @Test
    void aNameDeclaresOnce() {
        final ManifestCollectingHolder holder = declaredUniforms();
        holder.uniform3f(PER_FRAME, "cameraPosition", () -> VEC3);

        assertEquals(List.of("cameraPosition", "shadowModelView"), blockNames(holder.build()),
            "re-declaring a name must not append a second block member");
    }

    @Test
    void theRealDynamicRegistrationPathStaysOutOfTheBlock() {
        final ManifestCollectingHolder holder = new ManifestCollectingHolder();
        CommonUniforms.addDynamicUniforms(holder, FogMode.PER_VERTEX);
        final UniformManifest manifest = holder.build();

        assertFalse(manifest.dynamicNames().isEmpty(), "the push-notified registration path has to reach the harvester");
        final List<String> names = blockNames(manifest);
        for (String dynamic : manifest.dynamicNames()) {
            assertFalse(names.contains(dynamic), dynamic + " is pushed when its state changes, so it cannot be frame-global");
        }
    }

    @Test
    void noBlockIsHarvestedOffTheSdlBackend() {
        assertFalse(BackendManager.RENDER_BACKEND.isSDLGPU(), "test backend must not be SDL-GPU for this guard to mean anything");
        GLSMHooks.perFrameUniformBlock = new PerFrameUniformBlock(List.of());
        GLSMHooks.perPassUniformBlock = new PerFrameUniformBlock(List.of());
        PerFrameUniformBlockHarvester.harvest(null);
        assertNull(GLSMHooks.perFrameUniformBlock, "harvest must clear the block on any non-SDL backend");
        assertNull(GLSMHooks.perPassUniformBlock, "harvest must clear the per-pass block on any non-SDL backend");
    }
}
