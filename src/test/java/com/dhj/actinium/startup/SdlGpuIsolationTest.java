package com.dhj.actinium.startup;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdlGpuIsolationTest {

    private static final String SDL_GATE = "com/gtnewhorizons/angelica/sdlgpu/SDLGPUGate";
    private static final String TRANSFORMER = "com/gtnewhorizons/angelica/glsm/CompatShaderTransformer";

    @Test
    void irisBridgeDoesNotLoadTheSdlBackend() throws IOException {
        ClassNode node = read("com/gtnewhorizons/angelica/iris/IrisGLSMBridge.class");
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    assertFalse(SDL_GATE.equals(call.owner),
                        () -> "IrisGLSMBridge loads SDLGPUGate in " + method.name);
                }
            }
        }
    }

    @Test
    void sdlGateOwnsThePrewarmRegistration() throws IOException {
        ClassNode node = read("com/gtnewhorizons/angelica/sdlgpu/SDLGPUGate.class");
        boolean registered = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                    && TRANSFORMER.equals(call.owner)
                    && "setPostTransformListener".equals(call.name)) {
                    registered = true;
                    break;
                }
            }
        }
        assertTrue(registered, "SDLGPUGate must install the prewarm listener after its dependency checks");
    }

    private static ClassNode read(String resource) throws IOException {
        ClassNode node = new ClassNode();
        try (InputStream stream = SdlGpuIsolationTest.class.getClassLoader().getResourceAsStream(resource)) {
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return node;
    }
}
