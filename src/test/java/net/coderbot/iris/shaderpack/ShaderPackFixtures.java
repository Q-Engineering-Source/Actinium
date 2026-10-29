package net.coderbot.iris.shaderpack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Builds the smallest shader packs the headless tests can read: a folder of shader files, written under a
 * temporary directory.
 *
 * <p>Reading a pack is pure now, so a pack can be read without a render context. The fixtures therefore
 * cover the real constructor rather than a stand-in for it.</p>
 */
public final class ShaderPackFixtures {

    /** The environment defines a pack is normally read with, with nothing that depends on the machine. */
    private static final List<StringPair> ENVIRONMENT = List.of(
        new StringPair("MC_VERSION", "11202"),
        new StringPair("MC_GL_VERSION", "450")
    );

    private static final String VERTEX_PROGRAM = """
        #version 120
        // %s
        void main() { gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex; }
        """;

    private static final String FRAGMENT_PROGRAM = """
        #version 120
        // %s
        void main() { gl_FragColor = vec4(1.0); }
        """;

    private ShaderPackFixtures() {}

    /** {@return the environment defines the fixtures are read with} */
    public static List<StringPair> environment() {
        return ENVIRONMENT;
    }

    /** {@return a pack read from a folder holding one base program and nothing else} */
    public static ShaderPack readMinimal(Path shadersDirectory) throws IOException {
        writeProgram(shadersDirectory, "gbuffers_basic", "base");
        return read(shadersDirectory, Map.of());
    }

    /** Reads the pack rooted at a folder, with no option overrides. */
    public static ShaderPack read(Path shadersDirectory, Map<String, String> changedConfigs) throws IOException {
        return new ShaderPack(shadersDirectory, changedConfigs, ENVIRONMENT);
    }

    /** Writes a vertex/fragment program pair whose source carries a marker, under a folder. */
    public static void writeProgram(Path directory, String name, String marker) throws IOException {
        write(directory, name + ".vsh", VERTEX_PROGRAM.formatted(marker));
        write(directory, name + ".fsh", FRAGMENT_PROGRAM.formatted(marker));
    }

    /** Writes a UTF-8 text file, creating the folders above it. */
    public static void write(Path directory, String file, String contents) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(file), contents, StandardCharsets.UTF_8);
    }
}
