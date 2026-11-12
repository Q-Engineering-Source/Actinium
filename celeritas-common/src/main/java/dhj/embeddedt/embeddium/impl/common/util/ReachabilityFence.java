package dhj.embeddedt.embeddium.impl.common.util;

import java.lang.ref.Reference;

/**
 * Keeps an object reachable until the native resource it owns has been released.
 *
 * <p>{@link Reference#reachabilityFence(Object)} only exists from Java 9 and the vanilla Forge
 * client runs on Java 8, so the call is confined to a class that is never loaded on older
 * runtimes; there the fence degrades to a no-op.</p>
 */
public final class ReachabilityFence {

    private static final boolean SUPPORTED = Runtime.version().feature() >= 9;

    private ReachabilityFence() {}

    /**
     * Prevents the JIT from treating {@code ref} as unreachable before this call returns.
     */
    public static void accept(Object ref) {
        if (SUPPORTED) {
            Supported.accept(ref);
        }
    }

    private static final class Supported {
        static void accept(Object ref) {
            Reference.reachabilityFence(ref);
        }
    }
}
