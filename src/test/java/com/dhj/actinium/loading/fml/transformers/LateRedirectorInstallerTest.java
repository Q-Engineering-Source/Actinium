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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    @Test
    void forgeInstallationDisablesEarlyTransformerAndRegistersLateTransformerOnce() throws Exception {
        try (VanillaForgeClassLoader loader = new VanillaForgeClassLoader(vanillaLaunchwrapperJar())) {
            Class<?> launchClassLoaderType = loader.loadClass(LAUNCH_CLASS_LOADER);
            Object launchClassLoader = launchClassLoaderType.getConstructor(URL[].class)
                .newInstance((Object) forgeClasspathUrls());
            try {
                Method registerTransformer = launchClassLoaderType.getMethod("registerTransformer", String.class);
                registerTransformer.invoke(launchClassLoader, EARLY);
                loader.loadClass(LAUNCH).getField("classLoader").set(null, launchClassLoader);

                Class<?> installer = (Class<?>) launchClassLoaderType.getMethod("loadClass", String.class)
                    .invoke(launchClassLoader, INSTALLER);
                Method install = installer.getMethod("install");
                install.invoke(null);
                install.invoke(null);

                List<?> transformers = (List<?>) launchClassLoaderType.getMethod("getTransformers")
                    .invoke(launchClassLoader);
                assertEquals(1, transformers.stream().filter(transformer -> LATE.equals(
                    transformer.getClass().getName())).count());
                Object early = transformers.stream()
                    .filter(transformer -> EARLY.equals(transformer.getClass().getName()))
                    .findFirst()
                    .orElseThrow();
                byte[] bytes = {1};
                Object transformed = early.getClass()
                    .getMethod("transform", String.class, String.class, byte[].class)
                    .invoke(early, "cn.tesseract.mycelium.Example", "cn.tesseract.mycelium.Example", bytes);
                assertSame(bytes, transformed);
            } finally {
                ((AutoCloseable) launchClassLoader).close();
            }
        }
    }

    private static Path vanillaLaunchwrapperJar() {
        String jar = System.getProperty("actinium.vanillaLaunchwrapper");
        assumeTrue(jar != null, "Run this test through Gradle to provision vanilla LaunchWrapper");
        return Path.of(jar);
    }

    private static URL[] forgeClasspathUrls() {
        String classpath = System.getProperty("actinium.forgeTestClasspath");
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
        private final URLClassLoader launchwrapper;

        private VanillaForgeClassLoader(Path launchwrapperJar) throws IOException {
            super(LateRedirectorInstallerTest.class.getClassLoader());
            launchwrapper = new URLClassLoader(
                new URL[] {launchwrapperJar.toUri().toURL()},
                LateRedirectorInstallerTest.class.getClassLoader()
            ) {
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
        }
    }
}
