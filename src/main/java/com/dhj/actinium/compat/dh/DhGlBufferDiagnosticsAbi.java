package com.dhj.actinium.compat.dh;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resource-only ABI gate for the optional Distant Horizons GL buffer diagnostics mixin.
 *
 * <p>The gate reads the target class bytes without loading or initializing Distant Horizons
 * classes, so unsupported releases keep their normal rendering path without receiving this
 * diagnostics-only mixin.</p>
 */
public final class DhGlBufferDiagnosticsAbi {
    private static final Logger LOGGER = LogManager.getLogger("ActiniumMemory");
    private static final String TARGET_RESOURCE =
            "com/seibel/distanthorizons/common/render/openGl/glObject/buffer/GLBuffer.class";
    private static final String TARGET_INTERNAL_NAME =
            "com/seibel/distanthorizons/common/render/openGl/glObject/buffer/GLBuffer";
    private static final String ATOMIC_INTEGER_DESCRIPTOR = "Ljava/util/concurrent/atomic/AtomicInteger;";
    private static final String BYTE_BUFFER_DESCRIPTOR = "Ljava/nio/ByteBuffer;";
    private static final AtomicBoolean INCOMPATIBILITY_LOGGED = new AtomicBoolean();

    private DhGlBufferDiagnosticsAbi() {
    }

    /**
     * Whether the available DH GLBuffer bytecode exposes every member required by the
     * diagnostics mixin. An incompatibility is logged once when DH is installed.
     *
     * @return true if the specific hook ABI is present
     */
    public static boolean isCompatible() {
        ProbeResult result = inspect(DhGlBufferDiagnosticsAbi.class.getClassLoader());
        if (!result.compatible() && INCOMPATIBILITY_LOGGED.compareAndSet(false, true)) {
            LOGGER.warn("[ActiniumMemory] Distant Horizons GL buffer diagnostics disabled: {}",
                    result.reason());
        }
        return result.compatible();
    }

    /**
     * Inspects one class resource for the exact field and method descriptors used by the
     * DH diagnostics mixin. This method is package-visible for bytecode-fixture tests.
     *
     * @param classLoader loader supplying the DH class resource
     * @return compatibility state and an explanatory reason when incompatible
     */
    static ProbeResult inspect(ClassLoader classLoader) {
        try (InputStream stream = classLoader.getResourceAsStream(TARGET_RESOURCE)) {
            if (stream == null) {
                return ProbeResult.unsupported("missing class resource " + TARGET_RESOURCE);
            }

            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node,
                    ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            if (!TARGET_INTERNAL_NAME.equals(node.name)) {
                return ProbeResult.unsupported("resource declares unexpected class " + node.name);
            }

            ProbeResult result = checkField(node, "bufferCount", ATOMIC_INTEGER_DESCRIPTOR,
                    Opcodes.ACC_PUBLIC, true, false);
            if (!result.compatible()) {
                return result;
            }

            result = checkField(node, "id", "I", Opcodes.ACC_PROTECTED, false, true);
            if (!result.compatible()) {
                return result;
            }

            result = checkField(node, "size", "I", Opcodes.ACC_PROTECTED, false, false);
            if (!result.compatible()) {
                return result;
            }

            result = checkMethod(node, "destroyOldAndCreate", "(Z)V", Opcodes.ACC_PROTECTED, false);
            if (!result.compatible()) {
                return result;
            }

            result = checkMethod(node, "uploadBufferData", "(" + BYTE_BUFFER_DESCRIPTOR + "I)V",
                    Opcodes.ACC_PROTECTED, false);
            if (!result.compatible()) {
                return result;
            }

            result = checkMethod(node, "uploadBufferStorage", "(" + BYTE_BUFFER_DESCRIPTOR + ")V",
                    Opcodes.ACC_PROTECTED, false);
            if (!result.compatible()) {
                return result;
            }

            result = checkMethod(node, "uploadSubData", "(" + BYTE_BUFFER_DESCRIPTOR + "II)V",
                    Opcodes.ACC_PROTECTED, false);
            if (!result.compatible()) {
                return result;
            }

            return checkMethod(node, "destroyBufferIdNow", "(ILjava/lang/String;)V",
                    Opcodes.ACC_PRIVATE, true);
        } catch (IOException | IllegalArgumentException | SecurityException exception) {
            String detail = exception.getMessage();
            return ProbeResult.unsupported("cannot read target class bytes"
                    + (detail == null || detail.isBlank() ? "" : ": " + detail));
        }
    }

    private static ProbeResult checkField(ClassNode node, String name, String descriptor,
                                          int visibility, boolean isStatic, boolean isVolatile) {
        FieldNode field = node.fields.stream()
                .filter(candidate -> candidate.name.equals(name) && candidate.desc.equals(descriptor))
                .findFirst()
                .orElse(null);
        if (field == null) {
            return ProbeResult.unsupported("missing field " + name + ":" + descriptor);
        }

        int visibilityMask = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE;
        boolean actualStatic = (field.access & Opcodes.ACC_STATIC) != 0;
        boolean actualVolatile = (field.access & Opcodes.ACC_VOLATILE) != 0;
        if ((field.access & visibilityMask) != visibility || actualStatic != isStatic
                || actualVolatile != isVolatile) {
            return ProbeResult.unsupported("field " + name + ":" + descriptor
                    + " has unsupported access flags 0x" + Integer.toHexString(field.access));
        }
        return ProbeResult.supported();
    }

    private static ProbeResult checkMethod(ClassNode node, String name, String descriptor,
                                           int visibility, boolean isStatic) {
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals(name) && candidate.desc.equals(descriptor))
                .findFirst()
                .orElse(null);
        if (method == null) {
            return ProbeResult.unsupported("missing method " + name + descriptor);
        }

        int visibilityMask = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE;
        boolean actualStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        boolean hasCodeBody = (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
        if ((method.access & visibilityMask) != visibility || actualStatic != isStatic) {
            return ProbeResult.unsupported("method " + name + descriptor
                    + " has unsupported access flags 0x" + Integer.toHexString(method.access));
        }
        if (!hasCodeBody) {
            return ProbeResult.unsupported("method " + name + descriptor + " has no injectable code body");
        }
        return ProbeResult.supported();
    }

    /** Result of checking a DH class resource against the diagnostic hook ABI. */
    record ProbeResult(boolean compatible, String reason) {
        private static ProbeResult supported() {
            return new ProbeResult(true, "compatible");
        }

        private static ProbeResult unsupported(String reason) {
            return new ProbeResult(false, reason);
        }
    }
}
