package net.coderbot.iris.shaderpack;

import com.google.common.collect.ImmutableList;
import net.coderbot.iris.gl.state.FogMode;
import net.coderbot.iris.gl.state.ValueUpdateNotifier;
import net.coderbot.iris.gl.uniform.DynamicUniformHolder;
import net.coderbot.iris.gl.uniform.FloatSupplier;
import net.coderbot.iris.gl.uniform.UniformHolder;
import com.gtnewhorizons.angelica.glsm.shader.UniformType;
import net.coderbot.iris.gl.uniform.UniformUpdateFrequency;
import net.coderbot.iris.shaderpack.include.AbsolutePackPath;
import net.coderbot.iris.shaderpack.include.IncludeGraph;
import net.coderbot.iris.shaderpack.option.ShaderPackOptions;
import net.coderbot.iris.shaderpack.StringPair;
import net.coderbot.iris.uniforms.CommonUniforms;
import net.coderbot.iris.uniforms.FrameUpdateNotifier;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector2ic;
import org.joml.Vector3d;
import org.joml.Vector3fc;
import org.joml.Vector3ic;
import org.joml.Vector4fc;
import org.joml.Vector4ic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CommonUniformsRegistrationTest {
    @TempDir
    Path tempDir;

    @BeforeAll
    static void initializeLaunchEnvironment() {
        Launch.blackboard = new HashMap<>();
        Launch.blackboard.put("fml.deobfuscatedEnvironment", false);
    }

    @Test
    void registersGTextureIdAndPi() throws IOException {
        RecordingUniformHolder uniforms = new RecordingUniformHolder();
        PackDirectives directives = createDirectives();

        CommonUniforms.addDynamicUniforms(uniforms, FogMode.OFF);
        CommonUniforms.generalCommonUniforms(uniforms, new FrameUpdateNotifier(), directives);

        assertTrue(uniforms.names.contains("gtextureId"));
        assertTrue(uniforms.names.contains("pi"));
    }

    private PackDirectives createDirectives() throws IOException {
        Path entry = tempDir.resolve("entry.glsl");
        Files.writeString(entry, "void main() {}\n");
        IncludeGraph graph = new IncludeGraph(
            tempDir,
            ImmutableList.of(AbsolutePackPath.fromAbsolutePath("/entry.glsl"))
        );
        ShaderPackOptions options = new ShaderPackOptions(graph, Map.of());
        ShaderProperties properties = new ShaderProperties("", options, List.<StringPair>of(), () -> true);
        return new PackDirectives(Set.of(), properties);
    }

    private static final class RecordingUniformHolder implements DynamicUniformHolder {
        private final Set<String> names = new HashSet<>();

        @Override
        public UniformHolder uniform1f(UniformUpdateFrequency frequency, String name, FloatSupplier value) {
            return record(name);
        }

        @Override
        public UniformHolder uniform1f(UniformUpdateFrequency frequency, String name, IntSupplier value) {
            return record(name);
        }

        @Override
        public UniformHolder uniform1f(UniformUpdateFrequency frequency, String name, DoubleSupplier value) {
            return record(name);
        }

        @Override
        public UniformHolder uniform1i(UniformUpdateFrequency frequency, String name, IntSupplier value) {
            return record(name);
        }

        @Override
        public UniformHolder uniform1b(UniformUpdateFrequency frequency, String name, BooleanSupplier value) {
            return record(name);
        }

        @Override
        public UniformHolder uniform2f(UniformUpdateFrequency frequency, String name, Supplier<Vector2f> value) {
            return record(name);
        }

        @Override
        public UniformHolder uniform2i(UniformUpdateFrequency frequency, String name, Supplier<Vector2ic> value) {
            return record(name);
        }

        @Override
        public UniformHolder uniform3f(UniformUpdateFrequency frequency, String name, Supplier<Vector3fc> value) {
            return record(name);
        }

        @Override
        public UniformHolder uniform3i(UniformUpdateFrequency frequency, String name, Supplier<Vector3ic> value) {
            return record(name);
        }

        @Override
        public UniformHolder uniformVanilla3f(UniformUpdateFrequency frequency, String name, Supplier<Vec3d> value) {
            return record(name);
        }

        @Override
        public UniformHolder uniformTruncated3f(UniformUpdateFrequency frequency, String name, Supplier<Vector4fc> value) {
            return record(name);
        }

        @Override
        public UniformHolder uniform3d(UniformUpdateFrequency frequency, String name, Supplier<Vector3d> value) {
            return record(name);
        }

        @Override
        public UniformHolder uniform4f(UniformUpdateFrequency frequency, String name, Supplier<Vector4fc> value) {
            return record(name);
        }

        @Override
        public UniformHolder uniform4fArray(UniformUpdateFrequency frequency, String name, Supplier<float[]> value) {
            return record(name);
        }

        @Override
        public UniformHolder uniformMatrix(UniformUpdateFrequency frequency, String name, Supplier<Matrix4fc> value) {
            return record(name);
        }

        @Override
        public UniformHolder externallyManagedUniform(String name, UniformType type) {
            return record(name);
        }

        @Override
        public DynamicUniformHolder uniform1f(String name, FloatSupplier value, ValueUpdateNotifier notifier) {
            return record(name);
        }

        @Override
        public DynamicUniformHolder uniform1f(String name, IntSupplier value, ValueUpdateNotifier notifier) {
            return record(name);
        }

        @Override
        public DynamicUniformHolder uniform1f(String name, DoubleSupplier value, ValueUpdateNotifier notifier) {
            return record(name);
        }

        @Override
        public DynamicUniformHolder uniform1i(String name, IntSupplier value, ValueUpdateNotifier notifier) {
            return record(name);
        }

        @Override
        public DynamicUniformHolder uniform2i(String name, Supplier<Vector2ic> value, ValueUpdateNotifier notifier) {
            return record(name);
        }

        @Override
        public DynamicUniformHolder uniform3i(String name, Supplier<Vector3ic> value, ValueUpdateNotifier notifier) {
            return record(name);
        }

        @Override
        public DynamicUniformHolder uniform4f(String name, Supplier<Vector4fc> value, ValueUpdateNotifier notifier) {
            return record(name);
        }

        @Override
        public DynamicUniformHolder uniform4fArray(String name, Supplier<float[]> value, ValueUpdateNotifier notifier) {
            return record(name);
        }

        @Override
        public DynamicUniformHolder uniform4i(String name, Supplier<Vector4ic> value, ValueUpdateNotifier notifier) {
            return record(name);
        }

        private DynamicUniformHolder record(String name) {
            names.add(name);
            return this;
        }
    }
}
