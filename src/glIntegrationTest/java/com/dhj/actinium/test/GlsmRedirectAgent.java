package com.dhj.actinium.test;

import com.gtnewhorizons.angelica.loading.shared.transformers.AngelicaRedirector;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Installs the production GL redirector in the integration-test JVM.
 *
 * <p>The GL integration tests call the GLSM facades directly, but the fixed-function and vertex-format paths are
 * normally reached through the redirector that rewrites GTNHLib's GL calls. Running the suite without it exercises
 * a different call sequence than production, which is why {@code GLStateManagerIntegrationFixture} has to route
 * {@code VertexFormat.setupBufferState} by hand. With this agent installed that workaround can be dropped.</p>
 *
 * <p>The agent is only attached when the build sets {@code -PglsmRedirectAgent}, so the default test environment
 * stays unchanged until the suite has been verified with redirection on.</p>
 */
public final class GlsmRedirectAgent {

    private static final String TARGET_PREFIX = "com/gtnewhorizon/gtnhlib/";

    private GlsmRedirectAgent() {}

    public static void premain(String arguments, Instrumentation instrumentation) {
        final AngelicaRedirector redirector = new AngelicaRedirector();
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> redefined,
                                    ProtectionDomain domain, byte[] bytes) {
                if (className == null || !className.startsWith(TARGET_PREFIX)) return null;
                try {
                    if (!redirector.shouldTransform(bytes)) return null;
                    final ClassNode node = new ClassNode();
                    new ClassReader(bytes).accept(node, 0);
                    if (!redirector.transformClassNode(className.replace('/', '.'), node)) return null;
                    final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    node.accept(writer);
                    return writer.toByteArray();
                } catch (Throwable failure) {
                    return null;
                }
            }
        });
    }
}
