package com.mitchej123.lwjgl;

/**
 * Test process that resolves {@link LWJGLServiceProvider#LWJGL} with the service override already set on its command
 * line. The provider reads the property while its own class initializes, which happens before any test method can run.
 */
public final class LwjglServiceOverrideChild {
    private LwjglServiceOverrideChild() {
    }

    /**
     * Prints the class of the resolved service and exits non-zero when the provider cannot resolve one.
     *
     * @param arguments unused
     */
    public static void main(String[] arguments) {
        System.out.println("service=" + LWJGLServiceProvider.LWJGL.getClass().getName());
    }
}
