package com.dhj.actinium.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the shape of the particle call-site hook by reading the compiled mixin, the way the mixin
 * configuration test does.
 *
 * <p>The hook wraps the two {@code Particle.renderParticle} invocations and keeps one
 * {@code Operation} that performs the original call. ParticleCulling redirects the same two
 * invocations at the default priority, so this mixin has to be ordered ahead of it to find its
 * targets first; a plain redirect here would take the invocation instead and leave no place for the
 * other mod.</p>
 */
class ParticleMixinHookTest {

    private static final String MIXIN_RESOURCE = "com/dhj/actinium/mixin/features/iris/ParticleManagerCullingMixin.class";
    private static final String MIXIN_DESCRIPTOR = Type.getDescriptor(Mixin.class);
    private static final String REDIRECT_DESCRIPTOR = Type.getDescriptor(Redirect.class);
    private static final String WRAP_OPERATION_DESCRIPTOR = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";

    @Test
    void appliesBeforeTheDefaultPriorityRedirect() throws IOException {
        final ClassNode node = read();
        final AnnotationNode mixin = annotation(node.invisibleAnnotations, MIXIN_DESCRIPTOR);
        assertNotNull(mixin, "the class has to carry @Mixin");

        assertEquals(900, annotationValue(mixin, "priority"),
            "the wrap has to discover its targets before the default priority redirect rewrites them");
    }

    @Test
    void wrapsBothCallSitesInsteadOfRedirectingThem() throws IOException {
        final ClassNode node = read();
        final List<String> wrapOperations = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AnnotationNode annotation : annotationsOf(method)) {
                assertFalse(REDIRECT_DESCRIPTOR.equals(annotation.desc),
                    method.name + " must not occupy the invocation with a redirect");
                if (WRAP_OPERATION_DESCRIPTOR.equals(annotation.desc)) {
                    wrapOperations.add(method.name);
                }
            }
        }

        assertEquals(2, wrapOperations.size(), "both particle call sites are wrapped: " + wrapOperations);
    }

    /** Mixin annotations have class retention, so they sit in the invisible list. */
    private static List<AnnotationNode> annotationsOf(MethodNode method) {
        final List<AnnotationNode> annotations = new ArrayList<>();
        if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
        return annotations;
    }

    private static AnnotationNode annotation(List<AnnotationNode> annotations, String descriptor) {
        if (annotations == null) return null;
        for (AnnotationNode annotation : annotations) {
            if (descriptor.equals(annotation.desc)) return annotation;
        }
        return null;
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        if (annotation.values == null) return null;
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) {
                return annotation.values.get(i + 1);
            }
        }
        return null;
    }

    private static ClassNode read() throws IOException {
        final ClassNode node = new ClassNode();
        try (InputStream stream = Objects.requireNonNull(
            ParticleMixinHookTest.class.getClassLoader().getResourceAsStream(MIXIN_RESOURCE),
            "Missing " + MIXIN_RESOURCE)) {
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return node;
    }
}
