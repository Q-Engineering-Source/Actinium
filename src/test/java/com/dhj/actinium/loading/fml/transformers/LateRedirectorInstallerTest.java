package com.dhj.actinium.loading.fml.transformers;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Uses reflection because the vanilla LaunchClassLoader and its loaded entry points must share an
 * isolated loader, while the surrounding test runner provides an Outlands-modified loader instead.
 */
class LateRedirectorInstallerTest {
    private static final String LAUNCH = "net.minecraft.launchwrapper.Launch";
    private static final String LAUNCH_CLASS_LOADER = "net.minecraft.launchwrapper.LaunchClassLoader";
    private static final String INSTALLER = "com.dhj.actinium.loading.fml.transformers.LateRedirectorInstaller";
    private static final String EARLY =
        "com.gtnewhorizons.angelica.loading.fml.transformers.EarlyRedirectorTransformer";
    private static final String LATE =
        "com.gtnewhorizons.angelica.loading.fml.transformers.AngelicaRedirectorTransformer";
    private static final String LATE_TWEAKER =
        "com.gtnewhorizons.angelica.loading.fml.tweakers.AngelicaLateTweaker";

    @Test
    void forgeInstallationRemovesEarlyTransformerAndRegistersLateTransformerOnce() throws Exception {
        try (VanillaForgeClassLoader loader = new VanillaForgeClassLoader(vanillaLaunchwrapperJar())) {
            Class<?> launchClassLoaderType = loader.loadClass(LAUNCH_CLASS_LOADER);
            Object launchClassLoader = newLaunchClassLoader(loader, launchClassLoaderType);
            try {
                launchClassLoaderType.getMethod("registerTransformer", String.class)
                    .invoke(launchClassLoader, EARLY);

                Class<?> installer = (Class<?>) launchClassLoaderType.getMethod("loadClass", String.class)
                    .invoke(launchClassLoader, INSTALLER);
                Method install = installer.getMethod("install");
                install.invoke(null);
                install.invoke(null);

                List<?> transformers = (List<?>) launchClassLoaderType.getMethod("getTransformers")
                    .invoke(launchClassLoader);
                assertEquals(1, transformers.stream().filter(transformer -> LATE.equals(
                    transformer.getClass().getName())).count());
                assertEquals(0, transformers.stream().filter(transformer -> EARLY.equals(
                    transformer.getClass().getName())).count());
            } finally {
                ((AutoCloseable) launchClassLoader).close();
            }
        }
    }

    @Test
    void lateTweakerLoadsAfterForgePackageExclusion() throws Exception {
        try (VanillaForgeClassLoader loader = new VanillaForgeClassLoader(vanillaLaunchwrapperJar())) {
            Class<?> launchClassLoaderType = loader.loadClass(LAUNCH_CLASS_LOADER);
            Object launchClassLoader = newLaunchClassLoader(loader, launchClassLoaderType);
            try {
                Class<?> installer = (Class<?>) launchClassLoaderType.getMethod("loadClass", String.class)
                    .invoke(launchClassLoader, INSTALLER);
                installer.getMethod("prepareTweaker").invoke(null);
                launchClassLoaderType.getMethod("addClassLoaderExclusion", String.class)
                    .invoke(launchClassLoader, "com.gtnewhorizons.angelica.loading.fml.tweakers");
                Class.forName(LATE_TWEAKER, true, (ClassLoader) launchClassLoader);
                Map<?, ?> blackboard = (Map<?, ?>) loader.loadClass(LAUNCH).getField("blackboard").get(null);
                List<?> tweaks = (List<?>) blackboard.get("TweakClasses");
                assertEquals(1, tweaks.stream().filter(LATE_TWEAKER::equals).count());
            } finally {
                ((AutoCloseable) launchClassLoader).close();
            }
        }
    }

    private static Object newLaunchClassLoader(
        VanillaForgeClassLoader loader,
        Class<?> launchClassLoaderType
    ) throws Exception {
        Object launchClassLoader = launchClassLoaderType.getConstructor(URL[].class)
            .newInstance((Object) forgeClasspathUrls());
        Class<?> launchType = loader.loadClass(LAUNCH);
        launchType.getField("blackboard").set(null, new HashMap<String, Object>());
        launchType.getField("classLoader").set(null, launchClassLoader);
        return launchClassLoader;
    }

    private static Path vanillaLaunchwrapperJar() {
        String jar = System.getProperty("actinium.vanillaLaunchwrapper");
        assumeTrue(jar != null, "Run this test through Gradle to provision vanilla LaunchWrapper");
        return Path.of(jar);
    }

    private static URL[] forgeClasspathUrls() {
        return classpathUrls("actinium.forgeTestClasspath");
    }

    private static URL[] forgeDependencyUrls() {
        return classpathUrls("actinium.forgeDependencyClasspath");
    }

    private static URL[] classpathUrls(String property) {
        String classpath = System.getProperty(property);
        assumeTrue(classpath != null, "Run this test through Gradle to provision the Forge classpath");
        return Arrays.stream(classpath.split(File.pathSeparator))
            .map(Path::of)
            .map(path -> {
                try {
                    return path.toUri().toURL();
                } catch (IOException failure) {
                    throw new IllegalStateException("Invalid Forge test classpath entry " + path, failure);
                }
            })
            .toArray(URL[]::new);
    }

    private static final class VanillaForgeClassLoader extends ClassLoader implements AutoCloseable {
        private final URLClassLoader dependencyLoader;
        private final URLClassLoader launchwrapper;

        private VanillaForgeClassLoader(Path launchwrapperJar) throws IOException {
            super(LateRedirectorInstallerTest.class.getClassLoader());
            dependencyLoader = new URLClassLoader(forgeDependencyUrls(), null);
            launchwrapper = new URLClassLoader(new URL[] {launchwrapperJar.toUri().toURL()}, dependencyLoader) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    synchronized (getClassLoadingLock(name)) {
                        if (name.startsWith("net.minecraft.launchwrapper.")) {
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
            };
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("top.outlands.")) {
                throw new ClassNotFoundException(name);
            }
            if (name.startsWith("net.minecraft.launchwrapper.")) {
                return launchwrapper.loadClass(name);
            }
            synchronized (getClassLoadingLock(name)) {
                if (INSTALLER.equals(name)
                    || name.startsWith("com.gtnewhorizons.angelica.loading.fml.transformers.")) {
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

        @Override
        public void close() throws IOException {
            launchwrapper.close();
            dependencyLoader.close();
        }
    }
}
