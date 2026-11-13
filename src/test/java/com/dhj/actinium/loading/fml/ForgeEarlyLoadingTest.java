package com.dhj.actinium.loading.fml;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Uses reflection to reproduce Forge's reflective entry points in an isolated loader that hides
 * Outlands classes and resources from the classes under test.
 */
class ForgeEarlyLoadingTest {
    private static final String MIXIN_EARLY = "com.dhj.actinium.mixins.MixinEarly";
    private static final String EXPLICIT_REGISTRATION =
        "com.dhj.actinium.loading.fml.transformers.OutlandsExplicitTransformerRegistration";
    private static final List<String> REQUIRED_TRANSFORMERS = List.of(
        "com.dhj.actinium.loading.fml.transformers.LwjglContextVersionTransformer",
        "com.dhj.actinium.loading.fml.transformers.LwjglContextLifecycleTransformer",
        "com.dhj.actinium.loading.fml.transformers.SdlGpuHostTransformer"
    );

    @Test
    void earlyLoadingDoesNotRequireOutlands() throws Exception {
        ClassLoader loader = new WithoutOutlandsClassLoader();
        for (String transformer : REQUIRED_TRANSFORMERS) {
            Class<?> transformerType = Class.forName(transformer, true, loader);
            assertTrue(IClassTransformer.class.isAssignableFrom(transformerType));
        }
        Class<?> pluginType = Class.forName(MIXIN_EARLY, false, loader);
        assertTrue(IFMLLoadingPlugin.class.isAssignableFrom(pluginType));

        Class<?> registration = Class.forName(EXPLICIT_REGISTRATION, true, loader);
        registration.getMethod("register").invoke(null);
    }

    @Test
    void invalidClassCacheCheckDoesNotResolveOutlands() throws Exception {
        WithoutOutlandsClassLoader loader = new WithoutOutlandsClassLoader();
        Class<?> fix = Class.forName("com.dhj.actinium.compat.MixinReEntranceLockFix", true, loader);

        fix.getMethod("clearInvalidVanillaClasses").invoke(null);

        assertTrue(loader.outlandsRequest == null, loader.outlandsRequest);
    }

    @Test
    void nonTargetTransformationDoesNotInitializeSdlBootstrap() throws Exception {
        WithoutOutlandsClassLoader loader = new WithoutOutlandsClassLoader();
        Class<?> transformerType = Class.forName(
            "com.dhj.actinium.loading.fml.transformers.SdlGpuHostTransformer",
            true,
            loader
        );
        IClassTransformer transformer = (IClassTransformer) transformerType.getDeclaredConstructor().newInstance();
        byte[] bytes = {1};

        byte[] transformed = transformer.transform("java.lang.Object", "java.lang.Object", bytes);

        assertTrue(transformed == bytes);
        assertTrue(loader.bootstrapRequest == null, loader.bootstrapRequest);
    }

    @Test
    void forgeEarlyConfigIsOptional() throws Exception {
        ClassLoader loader = new WithoutForgeEarlyConfigClassLoader();
        Class<?> compat = Class.forName("com.dhj.actinium.compat.ForgeEarlyConfigCompat", true, loader);
        Class<?> snapshotType = Class.forName(
            "com.dhj.actinium.compat.ForgeEarlyConfigCompat$Snapshot",
            false,
            loader
        );
        Object snapshot = compat.getMethod("snapshot").invoke(null);

        assertTrue((Integer) snapshotType.getMethod("major").invoke(snapshot) == 0);
        assertTrue((Integer) snapshotType.getMethod("minor").invoke(snapshot) == 0);
        assertTrue(!((Boolean) snapshotType.getMethod("compatibilityProfile").invoke(snapshot)));
        assertTrue(!((Boolean) snapshotType.getMethod("debugContext").invoke(snapshot)));
        compat.getMethod("applyCoreProfile", int.class, int.class, boolean.class)
            .invoke(null, 4, 6, false);
        compat.getMethod("restoreCompatibilityProfile", int.class, int.class, boolean.class)
            .invoke(null, 4, 6, false);
        compat.getMethod("restore", snapshotType).invoke(null, snapshot);
        compat.getMethod("persistCompatibilityProfile").invoke(null);
    }

    private static class WithoutOutlandsClassLoader extends ClassLoader {
        private String outlandsRequest;
        private String bootstrapRequest;

        private WithoutOutlandsClassLoader() {
            super(ForgeEarlyLoadingTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if ("com.dhj.actinium.startup.SdlGpuBootstrap".equals(name)) {
                bootstrapRequest = name;
            }
            if (name.startsWith("top.outlands.")) {
                outlandsRequest = name;
                throw new ClassNotFoundException(name);
            }
            synchronized (getClassLoadingLock(name)) {
                if (isChildFirst(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = findClass(name);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
                return super.loadClass(name, resolve);
            }
        }

        @Override
        public URL getResource(String name) {
            if (name.startsWith("top/outlands/")) {
                return null;
            }
            return super.getResource(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (name.startsWith("top/outlands/")) {
                return Collections.emptyEnumeration();
            }
            return super.getResources(name);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            String resource = name.replace('.', '/') + ".class";
            try (InputStream stream = getParent().getResourceAsStream(resource)) {
                if (stream == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = stream.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException failure) {
                throw new ClassNotFoundException(name, failure);
            }
        }

        private static boolean isChildFirst(String name) {
            return MIXIN_EARLY.equals(name)
                || "com.dhj.actinium.compat.MixinReEntranceLockFix".equals(name)
                || "com.dhj.actinium.compat.ForgeEarlyConfigCompat".equals(name)
                || name.startsWith("com.dhj.actinium.loading.fml.transformers.");
        }
    }

    private static final class WithoutForgeEarlyConfigClassLoader extends WithoutOutlandsClassLoader {
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if ("net.minecraftforge.common.ForgeEarlyConfig".equals(name)) {
                throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
        }

        @Override
        public URL getResource(String name) {
            if ("net/minecraftforge/common/ForgeEarlyConfig.class".equals(name)) {
                return null;
            }
            return super.getResource(name);
        }
    }
}
