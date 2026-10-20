package com.gtnewhorizons.angelica.glsm.shader;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * The OpenGL entry points {@link ProgramBinaryCache} needs to move a linked program to disk and back.
 *
 * <p>The cache owns two very different kinds of state: the disk bookkeeping, which is plain Java, and the
 * handful of GL calls that make a program readable and restorable. Only the second half needs a live GL
 * context, and it is also the half whose failure modes matter most: a driver may reject a stored binary,
 * or advertise no binary format at all. Splitting those calls behind this port keeps the bookkeeping and
 * the rejection state machine testable without a context, and gives a single place to install a fake
 * driver.</p>
 *
 * <p>{@link ProgramBinaryOpsImpl} is the production implementation and forwards to {@code RenderSystem}
 * and {@code GLStateManager}.</p>
 */
public interface ProgramBinaryOps {

    /**
     * Whether the active backend and driver can read a linked program back as a binary.
     *
     * @return true when {@code glGetProgramBinary} produces a usable payload
     */
    boolean isSupported();

    /**
     * A stable identifier of the driver that produced the binaries. A binary is only valid for the driver
     * that linked it, and this value is part of every cache key.
     *
     * @return the vendor, renderer, version, GLSL version, GLES flag and minimum GLSL version joined together
     */
    String driverFingerprint();

    /** Creates an empty program object and returns its GL name. */
    int createProgram();

    /** Deletes a program object; a zero name is ignored. */
    void deleteProgram(int program);

    /** Reads a single integer program parameter, such as {@code GL_LINK_STATUS}. */
    int getProgrami(int program, int pname);

    /** Sets a link-time program parameter, such as {@code GL_PROGRAM_BINARY_RETRIEVABLE_HINT}. */
    void programParameteri(int program, int pname, int value);

    /** Reads back the linked binary, its written length and its driver-chosen format. */
    void getProgramBinary(int program, IntBuffer length, IntBuffer binaryFormat, ByteBuffer binary);

    /** Replaces the link with a previously saved driver binary. */
    void programBinary(int program, int binaryFormat, ByteBuffer binary);
}
