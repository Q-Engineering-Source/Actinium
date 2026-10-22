package com.dhj.actinium.compat.dh;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DhGlBufferDiagnosticsAbiTest {
    private static final String TARGET_RESOURCE =
            "com/seibel/distanthorizons/common/render/openGl/glObject/buffer/GLBuffer.class";
    private static final String TARGET_INTERNAL_NAME =
            "com/seibel/distanthorizons/common/render/openGl/glObject/buffer/GLBuffer";

    @Test
    void acceptsTheExpectedDhBufferAbi() {
        DhGlBufferDiagnosticsAbi.ProbeResult result = DhGlBufferDiagnosticsAbi.inspect(
                classLoader(fixture(null, null, false, false)));

        assertTrue(result.compatible(), result.reason());
    }

    @Test
    void rejectsAnAbsentTargetClassResource() {
        DhGlBufferDiagnosticsAbi.ProbeResult result = DhGlBufferDiagnosticsAbi.inspect(classLoader(null));

        assertFalse(result.compatible());
        assertTrue(result.reason().contains("missing class resource"), result.reason());
    }

    @Test
    void rejectsAMissingShadowField() {
        DhGlBufferDiagnosticsAbi.ProbeResult result = DhGlBufferDiagnosticsAbi.inspect(
                classLoader(fixture("size", null, false, false)));

        assertFalse(result.compatible());
        assertTrue(result.reason().contains("missing field size:I"), result.reason());
    }

    @Test
    void rejectsAMissingInjectedMethod() {
        DhGlBufferDiagnosticsAbi.ProbeResult result = DhGlBufferDiagnosticsAbi.inspect(
                classLoader(fixture(null, "uploadSubData", false, false)));

        assertFalse(result.compatible());
        assertTrue(result.reason().contains("missing method uploadSubData"), result.reason());
    }

    @Test
    void rejectsAnInstanceFieldChangedToStatic() {
        DhGlBufferDiagnosticsAbi.ProbeResult result = DhGlBufferDiagnosticsAbi.inspect(
                classLoader(fixture(null, null, true, false)));

        assertFalse(result.compatible());
        assertTrue(result.reason().contains("field id:I has unsupported access flags"), result.reason());
    }

    @Test
    void rejectsAnInstanceMethodChangedToStatic() {
        DhGlBufferDiagnosticsAbi.ProbeResult result = DhGlBufferDiagnosticsAbi.inspect(
                classLoader(fixture(null, null, false, true)));

        assertFalse(result.compatible());
        assertTrue(result.reason().contains("method uploadBufferData"), result.reason());
        assertTrue(result.reason().contains("unsupported access flags"), result.reason());
    }

    private static ClassLoader classLoader(byte[] classBytes) {
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (!TARGET_RESOURCE.equals(name) || classBytes == null) {
                    return null;
                }
                return new ByteArrayInputStream(classBytes);
            }
        };
    }

    private static byte[] fixture(String omittedField, String omittedMethod,
                                  boolean staticId, boolean staticUploadBufferData) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                TARGET_INTERNAL_NAME, null, "java/lang/Object", null);

        addField(writer, omittedField, "bufferCount", "Ljava/util/concurrent/atomic/AtomicInteger;",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        addField(writer, omittedField, "id", "I",
                Opcodes.ACC_PROTECTED | Opcodes.ACC_VOLATILE
                        | (staticId ? Opcodes.ACC_STATIC : 0));
        addField(writer, omittedField, "size", "I", Opcodes.ACC_PROTECTED);

        addMethod(writer, omittedMethod, "destroyOldAndCreate", "(Z)V", Opcodes.ACC_PROTECTED, 2);
        addMethod(writer, omittedMethod, "uploadBufferData", "(Ljava/nio/ByteBuffer;I)V",
                Opcodes.ACC_PROTECTED | (staticUploadBufferData ? Opcodes.ACC_STATIC : 0), 3);
        addMethod(writer, omittedMethod, "uploadBufferStorage", "(Ljava/nio/ByteBuffer;)V",
                Opcodes.ACC_PROTECTED, 2);
        addMethod(writer, omittedMethod, "uploadSubData", "(Ljava/nio/ByteBuffer;II)V",
                Opcodes.ACC_PROTECTED, 4);
        addMethod(writer, omittedMethod, "destroyBufferIdNow", "(ILjava/lang/String;)V",
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, 2);

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void addField(ClassWriter writer, String omittedField, String name,
                                 String descriptor, int access) {
        if (!name.equals(omittedField)) {
            writer.visitField(access, name, descriptor, null, null).visitEnd();
        }
    }

    private static void addMethod(ClassWriter writer, String omittedMethod, String name,
                                  String descriptor, int access, int localCount) {
        if (name.equals(omittedMethod)) {
            return;
        }
        var method = writer.visitMethod(access, name, descriptor, null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, localCount);
        method.visitEnd();
    }
}
