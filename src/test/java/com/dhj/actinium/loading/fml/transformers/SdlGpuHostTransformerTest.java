package com.dhj.actinium.loading.fml.transformers;

import com.dhj.actinium.startup.SdlGpuBootstrap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SdlGpuHostTransformerTest {
    private final SdlGpuHostTransformer transformer = new SdlGpuHostTransformer();

    @BeforeEach
    void requireOutlandsHost() {
        assumeTrue(SdlGpuBootstrap.isSupported());
    }

    @Test
    void actualOutlandsClassesRemainValidAndIdempotent() throws Exception {
        for (String target : SdlGpuHostTransformer.TARGETS) {
            byte[] transformed = transformer.transform(target, target, resource(target));
            ClassNode node = read(transformed);
            for (MethodNode method : node.methods) {
                if ((method.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) == 0) {
                    new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
                }
            }
            assertSame(transformed, transformer.transform(target, target, transformed));
        }
    }

    @Test
    void lifecycleTransformerCanRunAgainAfterSdlRoutesAreAdded() throws Exception {
        LwjglContextLifecycleTransformer lifecycle = new LwjglContextLifecycleTransformer();
        for (String target : List.of("org.lwjgl.opengl.Display", "org.lwjgl.opengl.DrawableGL")) {
            byte[] first = lifecycle.transform(target, target, resource(target));
            byte[] routed = transformer.transform(target, target, first);
            assertSame(routed, lifecycle.transform(target, target, routed));
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
    }

    private static byte[] resource(String name) throws IOException {
        URL host = SdlGpuHostTransformerTest.class.getClassLoader()
            .getResource("org/lwjgl/opengl/DisplayEvents.class");
        assertNotNull(host);
        URL target = new URL(host, "/" + name.replace('.', '/') + ".class");
        try (InputStream input = target.openStream()) {
            return input.readAllBytes();
        }
    }
}
