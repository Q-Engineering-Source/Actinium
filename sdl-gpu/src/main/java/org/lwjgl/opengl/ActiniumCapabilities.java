package org.lwjgl.opengl;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.FunctionProvider;

import java.util.Set;
import java.util.function.IntFunction;

/** Gives SDL GPU access to LWJGL's package-private capability constructor. */
public final class ActiniumCapabilities {
    private static final long EMULATED_FUNCTION = 0xDEAD_0000_0000_0001L;

    private ActiniumCapabilities() {}

    public static GLCapabilities create(Set<String> advertised) {
        FunctionProvider functions = name -> EMULATED_FUNCTION;
        IntFunction<PointerBuffer> buffers = BufferUtils::createPointerBuffer;
        return new GLCapabilities(functions, advertised, true, buffers);
    }
}
