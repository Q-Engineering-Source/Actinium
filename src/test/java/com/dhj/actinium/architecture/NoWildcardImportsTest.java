package com.dhj.actinium.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps non-static wildcard imports out of the source tree.
 *
 * <p>A wildcard import makes the compiler pick between same-named classes by package order instead of by an
 * explicit choice, which matters here because the terrain code and Minecraft both declare {@code BlockRenderLayer}
 * and several renderer helpers. Static wildcards stay allowed: the buffer utilities are meant to be read as bare
 * calls.</p>
 *
 * <p>The baseline lists the files that already carried a wildcard when the rule was introduced. It may only
 * shrink: a file that no longer has one must be removed from the list, so the set cannot silently grow again.</p>
 */
class NoWildcardImportsTest {

    private static final List<String> SOURCE_ROOTS = List.of(
        "src/main/java",
        "src/test/java",
        "src/glIntegrationTest/java",
        "glsm/src/main/java",
        "celeritas-common/src/main/java",
        "shader/src/main/java",
        "GTNHLib/src/main/java",
        "sdl-gpu/src/main/java",
        "lwjgl2-backend/src/main/java");

    private static final Set<String> BASELINE = Set.of(
        "GTNHLib/src/main/java/com/gtnewhorizon/gtnhlib/client/renderer/postprocessing/CustomFramebuffer.java",
        "GTNHLib/src/main/java/com/gtnewhorizon/gtnhlib/client/renderer/postprocessing/shaders/BloomShader.java",
        "GTNHLib/src/main/java/com/gtnewhorizon/gtnhlib/client/renderer/shader/AutoShaderUpdater.java",
        "celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/asm/ProxyClassGenerator.java",
        "celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/gl/arena/staging/MappedStagingBuffer.java",
        "celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/gl/device/CommandList.java",
        "celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/gl/device/GLRenderDevice.java",
        "celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/gl/shader/ShaderConstants.java",
        "celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/gui/CeleritasVideoOptionsController.java",
        "celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/gui/frame/tab/TabFrame.java",
        "celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/gui/options/CommonOptionPages.java",
        "celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/render/chunk/DefaultChunkRenderer.java",
        "celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/render/chunk/RenderSectionManager.java",
        "celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/render/chunk/ShaderChunkRenderer.java",
        "celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildBuffers.java",
        "celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/render/chunk/data/MinecraftBuiltRenderSectionData.java",
        "celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/render/chunk/lists/RenderListManager.java",
        "celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/render/chunk/map/ChunkTracker.java",
        "celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/render/chunk/region/RenderRegion.java",
        "celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/render/chunk/region/RenderRegionManager.java",
        "celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/render/terrain/SimpleWorldRenderer.java",
        "celeritas-common/src/main/java/dhj/embeddedt/embeddium/impl/util/collections/map/StampedMap.java",
        "lwjgl2-backend/src/main/java/com/mitchej123/lwjgl/lwjgl2/LWJGL2DebugSupport.java",
        "src/main/java/com/dhj/actinium/mixin/vintage/core/frustum/MixinFrustum.java",
        "src/main/java/com/dhj/actinium/mixin/vintage/core/terrain/MixinRenderGlobal.java",
        "src/main/java/com/dhj/actinium/render/terrain/ActiniumWorldRenderer.java",
        "src/main/java/com/dhj/actinium/render/terrain/VintageRenderSectionManager.java");

    private static final Pattern WILDCARD_IMPORT = Pattern.compile("^\\s*import\\s+((?!static\\s)[\\w.]+\\.\\*)\\s*;.*$");

    @Test
    void noNewWildcardImports() throws IOException {
        final String projectRoot = System.getProperty("actinium.projectRoot");
        assertFalse(projectRoot == null, "Missing system property actinium.projectRoot; run the tests through the root test task");

        final List<String> violations = new ArrayList<>();
        final Set<String> withWildcards = new LinkedHashSet<>();
        for (String root : SOURCE_ROOTS) {
            final Path rootPath = Paths.get(projectRoot, root);
            assertTrue(Files.isDirectory(rootPath), root + " is not a directory");
            try (Stream<Path> files = Files.walk(rootPath)) {
                final List<Path> javaFiles = files.filter(path -> path.toString().endsWith(".java")).collect(Collectors.toList());
                for (Path file : javaFiles) {
                    final Matcher matcher = WILDCARD_IMPORT.matcher("");
                    final String[] lines = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).split("\r?\n");
                    for (int line = 0; line < lines.length; line++) {
                        matcher.reset(lines[line]);
                        if (!matcher.matches()) continue;
                        final String relative = projectRootPath(projectRoot, file);
                        withWildcards.add(relative);
                        if (!BASELINE.contains(relative)) {
                            violations.add("  " + relative + ":" + (line + 1) + " imports " + matcher.group(1));
                        }
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
            "Wildcard imports are not allowed outside the baseline; import the classes explicitly:\n"
                + String.join("\n", violations));

        final List<String> stale = new ArrayList<>();
        for (String entry : BASELINE) {
            if (!withWildcards.contains(entry)) stale.add("  " + entry);
        }
        assertTrue(stale.isEmpty(),
            "These baseline entries no longer carry a wildcard import and must be removed from BASELINE:\n"
                + String.join("\n", stale));
    }

    private static String projectRootPath(String projectRoot, Path file) {
        return Paths.get(projectRoot).relativize(file).toString().replace('\\', '/');
    }
}
