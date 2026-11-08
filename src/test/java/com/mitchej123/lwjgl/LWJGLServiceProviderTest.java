package com.mitchej123.lwjgl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LWJGLServiceProviderTest {
    private static final String OVERRIDE_PROPERTY = "actinium.lwjglService";

    /**
     * Absent on purpose: {@code constructInstance} then fails naming it, which shows the override reached the lookup
     * instead of the default service being constructed.
     */
    private static final String ABSENT_OVERRIDE = "com.mitchej123.lwjgl.AbsentLWJGLService";

    @TempDir
    Path temporaryDirectory;

    /**
     * The override is read during {@link LWJGLServiceProvider} class initialization, so it takes effect only in a
     * process that starts with the property set. The arguments go through a Java argument file because the test
     * classpath alone exceeds the Windows command-line limit.
     */
    @Test
    void overridePropertySelectsTheServiceToConstruct() throws Exception {
        Path argumentFile = createJavaArgumentFile();
        Process child = new ProcessBuilder(javaExecutable().toString(), "@" + argumentFile)
            .redirectErrorStream(true)
            .start();

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(60), () -> assertOverrideReachedTheLookup(child));
        } finally {
            child.destroyForcibly();
            child.waitFor(10L, TimeUnit.SECONDS);
        }
    }

    private static void assertOverrideReachedTheLookup(Process child) throws Exception {
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(child.waitFor(30L, TimeUnit.SECONDS), () -> "Child process did not exit:\n" + output);
        assertNotEquals(0, child.exitValue(), () -> "Child resolved a service instead of failing on the override:\n" + output);
        assertTrue(output.contains(ABSENT_OVERRIDE), () -> "Child did not construct the override service:\n" + output);
    }

    private Path createJavaArgumentFile() throws IOException {
        String classPath = System.getProperty("java.class.path").replace('\\', '/');
        String arguments = "-classpath\n"
            + quote(classPath) + "\n"
            + "-D" + OVERRIDE_PROPERTY + "=" + ABSENT_OVERRIDE + "\n"
            + LwjglServiceOverrideChild.class.getName() + "\n";
        Path argumentFile = this.temporaryDirectory.resolve("lwjgl-service-child.args");
        Files.writeString(argumentFile, arguments, StandardCharsets.UTF_8);
        return argumentFile;
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    private static String quote(String value) {
        return '"' + value.replace("\"", "\\\"") + '"';
    }
}
