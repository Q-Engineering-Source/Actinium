package com.dhj.actinium.mixins;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MixinEarlyInitializationTest {
    @Test
    void grammarLoadsBeforeBootstrapAndLateTweaker() throws IOException {
        final List<String> calls = new ArrayList<>();
        try (InputStream stream = MixinEarlyInitializationTest.class.getClassLoader()
            .getResourceAsStream("com/dhj/actinium/mixins/MixinEarly.class")) {
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (!"injectData".equals(name)) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                            if ("com/gtnewhorizons/angelica/glsm/GlslTransformUtils".equals(owner) && "warmupGrammar".equals(name)) {
                                calls.add("warmup");
                            } else if ("com/dhj/actinium/startup/SdlGpuBootstrap".equals(owner) && "initialize".equals(name)) {
                                calls.add("bootstrap");
                            } else if ("com/dhj/actinium/loading/fml/transformers/LateRedirectorInstaller".equals(owner) && "prepareTweaker".equals(name)) {
                                calls.add("lateTweaker");
                            }
                        }
                    };
                }
            }, 0);
        }
        assertEquals(List.of("warmup", "bootstrap", "lateTweaker"), calls);
    }
}
