package com.gtnewhorizons.angelica.iris;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShaderWarmupClassListTest {

    @Test
    void listsShaderClassesOfAClassesDirectory(@TempDir Path root) throws Exception {
        writeFile(root, "net/coderbot/iris/pipeline/DeferredWorldRenderingPipeline.class");
        writeFile(root, "net/coderbot/iris/gl/program/Program$1.class");
        writeFile(root, "kroppeb/stareval/parser/ExpressionParser.class");
        writeFile(root, "net/coderbot/iris/compat/dh/DHCompatInternal.class");
        writeFile(root, "com/dhj/actinium/Actinium.class");
        writeFile(root, "net/coderbot/iris/shaders.properties");

        assertEquals(
            List.of(
                "kroppeb.stareval.parser.ExpressionParser",
                "net.coderbot.iris.gl.program.Program$1",
                "net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline"
            ),
            ShaderWarmup.listClasses(root));
    }

    @Test
    void listsShaderClassesOfAJar(@TempDir Path root) throws Exception {
        final Path jar = root.resolve("Actinium.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            addEntry(out, "net/coderbot/iris/gl/program/Program.class");
            addEntry(out, "kroppeb/stareval/element/Element.class");
            addEntry(out, "net/coderbot/iris/compat/dh/DHCompat.class");
            addEntry(out, "assets/actinium/lang/en_us.lang");
        }

        assertEquals(
            List.of("kroppeb.stareval.element.Element", "net.coderbot.iris.gl.program.Program"),
            ShaderWarmup.listClasses(jar));
    }

    @Test
    void listsNothingWithoutASource() throws Exception {
        assertEquals(List.of(), ShaderWarmup.listClasses(null));
    }

    private static void writeFile(Path root, String entry) throws IOException {
        final Path file = root.resolve(entry);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] { 0 });
    }

    private static void addEntry(JarOutputStream out, String entry) throws IOException {
        out.putNextEntry(new JarEntry(entry));
        out.write(new byte[] { 0 });
        out.closeEntry();
    }
}
