package com.mitchej123.lwjgl;

public final class LWJGLServiceProvider {
    /**
     * Names the {@link LWJGLService} implementation to construct instead of the default LWJGL 3 one. Tests use it to
     * run rendering code without a GL context.
     */
    private static final String SERVICE_OVERRIDE_PROPERTY = "actinium.lwjglService";

    public static final LWJGLService LWJGL = createInstance();
    public static final int POINTER_SIZE = LWJGL.getPointerSize();
    public static final long NULL = 0L;

    private LWJGLServiceProvider() {
    }

    static LWJGLService constructInstance(String className) {
        try {
            var clz = Class.forName(className);
            var method = clz.getDeclaredMethod("create");
            return (LWJGLService) method.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static LWJGLService createInstance() {
        var override = System.getProperty(SERVICE_OVERRIDE_PROPERTY);

        if (override != null) {
            return constructInstance(override);
        }

        return constructInstance("com.mitchej123.lwjgl.lwjgl3.LWJGL3Service");
    }
}

