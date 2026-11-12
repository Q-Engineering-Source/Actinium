import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class DistributedForgeLoadingProbe {
    private static final String FOUNDATION_RESOURCE = "top/outlands/foundation/TransformerDelegate.class";
    private static final String MIXIN_EARLY = "com.dhj.actinium.mixins.MixinEarly";
    private static final String LATE_INSTALLER = "com.dhj.actinium.loading.fml.transformers.LateRedirectorInstaller";
    private static final String EARLY_REDIRECTOR =
        "com.gtnewhorizons.angelica.loading.fml.transformers.EarlyRedirectorTransformer";
    private static final String LATE_REDIRECTOR =
        "com.gtnewhorizons.angelica.loading.fml.transformers.AngelicaRedirectorTransformer";
    private static final String LOCK_FIX = "com.dhj.actinium.compat.MixinReEntranceLockFix";
    private static final List<String> REQUIRED_TRANSFORMERS = Arrays.asList(
        "com.dhj.actinium.loading.fml.transformers.LwjglContextVersionTransformer",
        "com.dhj.actinium.loading.fml.transformers.LwjglContextLifecycleTransformer",
        "com.dhj.actinium.loading.fml.transformers.SdlGpuHostTransformer"
    );

    private DistributedForgeLoadingProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (DistributedForgeLoadingProbe.class.getClassLoader().getResource(FOUNDATION_RESOURCE) != null) {
            throw new AssertionError("Outlands Foundation leaked into the Forge probe classpath");
        }
        List<URL> classpath = new ArrayList<URL>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
            classpath.add(new File(entry).toURI().toURL());
        }

        try (LaunchClassLoader loader = new LaunchClassLoader(classpath.toArray(new URL[0]))) {
            Launch.classLoader = loader;
            Class<?> pluginType = loader.loadClass(MIXIN_EARLY);
            Object plugin = pluginType.getDeclaredConstructor().newInstance();
            String[] transformerArray = (String[]) pluginType.getMethod("getASMTransformerClass").invoke(plugin);
            List<String> transformerNames = Arrays.asList(transformerArray);
            if (!transformerNames.containsAll(REQUIRED_TRANSFORMERS)) {
                throw new AssertionError("Early transformer entries are missing: " + transformerNames);
            }
            for (String transformerName : transformerNames) {
                loader.registerTransformer(transformerName);
            }
            for (String requiredName : transformerNames) {
                boolean registered = false;
                for (Object transformer : loader.getTransformers()) {
                    if (requiredName.equals(transformer.getClass().getName())) {
                        registered = true;
                        break;
                    }
                }
                if (!registered) {
                    throw new AssertionError("Forge did not register " + requiredName);
                }
            }

            Class<?> installer = loader.loadClass(LATE_INSTALLER);
            Method install = installer.getMethod("install");
            install.invoke(null);
            install.invoke(null);

            List<?> transformers = loader.getTransformers();
            int lateRegistrations = 0;
            Object early = null;
            for (Object transformer : transformers) {
                if (LATE_REDIRECTOR.equals(transformer.getClass().getName())) {
                    lateRegistrations++;
                }
                if (EARLY_REDIRECTOR.equals(transformer.getClass().getName())) {
                    early = transformer;
                }
            }
            if (lateRegistrations != 1 || early == null) {
                throw new AssertionError("Unexpected transformer registry: " + transformers);
            }
            byte[] bytes = {1};
            Object transformed = early.getClass()
                .getMethod("transform", String.class, String.class, byte[].class)
                .invoke(early, "cn.tesseract.mycelium.Example", "cn.tesseract.mycelium.Example", bytes);
            if (transformed != bytes) {
                throw new AssertionError("Early redirector remained active after late registration");
            }
            loader.loadClass(LOCK_FIX).getMethod("clearInvalidVanillaClasses").invoke(null);
        }
        System.out.println("Distributed Forge loading probe passed");
    }
}
