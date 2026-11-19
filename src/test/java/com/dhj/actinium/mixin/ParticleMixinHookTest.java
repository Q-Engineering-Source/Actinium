package com.dhj.actinium.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the shape of the particle call-site hook by reading the compiled mixin, the way the mixin
 * configuration test does.
 *
 * <p>The hook wraps the two {@code Particle.renderParticle} invocations and keeps one
 * {@code Operation} that performs the original call. ParticleCulling redirects the same two
 * invocations at the default priority, so this mixin carries a lower priority value and is applied
 * ahead of it to find its targets first; a plain redirect here would take the invocation instead and
 * leave no place for the other mod.</p>
 */
class ParticleMixinHookTest {

    private static final String MIXIN_RESOURCE = "com/dhj/actinium/mixin/features/iris/ParticleManagerCullingMixin.class";
    private static final String MIXIN_DESCRIPTOR = Type.getDescriptor(Mixin.class);
    private static final String REDIRECT_DESCRIPTOR = Type.getDescriptor(Redirect.class);
    private static final String AT_DESCRIPTOR = Type.getDescriptor(At.class);
    private static final String WRAP_OPERATION_DESCRIPTOR = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
    private static final String RENDER_PARTICLE_TARGET =
        "Lnet/minecraft/client/particle/Particle;renderParticle(Lnet/minecraft/client/renderer/BufferBuilder;Lnet/minecraft/entity/Entity;FFFFFF)V";

    /** Mixin applies the lowest priority value first, so the hook has to stay under this one. */
    private static final int DEFAULT_PRIORITY = 1000;

    @Test
    void appliesBeforeTheDefaultPriorityRedirect() throws IOException {
        final ClassNode node = read();
        final AnnotationNode mixin = annotation(node.invisibleAnnotations, MIXIN_DESCRIPTOR);
        assertNotNull(mixin, "the class has to carry @Mixin");

        final int priority = (Integer) annotationValue(mixin, "priority");
        assertTrue(priority < DEFAULT_PRIORITY,
            "a lower value is applied first, so " + priority + " against the default " + DEFAULT_PRIORITY
                + " is what makes the wrap discover its targets before the redirect rewrites them");
    }

    @Test
    void wrapsBothCallSitesInsteadOfRedirectingThem() throws IOException {
        final ClassNode node = read();
        final Map<String, List<String>> wrappedTargets = new LinkedHashMap<>();
        for (MethodNode method : node.methods) {
            for (AnnotationNode annotation : annotationsOf(method)) {
                assertFalse(REDIRECT_DESCRIPTOR.equals(annotation.desc),
                    method.name + " must not occupy the invocation with a redirect");
                if (WRAP_OPERATION_DESCRIPTOR.equals(annotation.desc)) {
                    wrappedTargets.put(single(annotationValue(annotation, "method")), atTargets(annotation));
                }
            }
        }

        assertEquals(List.of(RENDER_PARTICLE_TARGET), wrappedTargets.get("renderParticles"),
            "the main particle loop wraps Particle.renderParticle");
        assertEquals(List.of(RENDER_PARTICLE_TARGET), wrappedTargets.get("renderLitParticles"),
            "the lit particle loop wraps Particle.renderParticle");
        assertEquals(2, wrappedTargets.size(), "no other call site is wrapped: " + wrappedTargets);
    }

    /** {@code @WrapOperation} nests its injection points under {@code at}, one {@link At} per target. */
    private static List<String> atTargets(AnnotationNode wrapOperation) {
        final List<String> targets = new ArrayList<>();
        for (AnnotationNode at : annotationNodes(annotationValue(wrapOperation, "at"))) {
            assertEquals(AT_DESCRIPTOR, at.desc, "@WrapOperation nests an @At");
            targets.add((String) annotationValue(at, "target"));
        }
        return targets;
    }

    /** A single annotation value arrives as the node itself, an array of them as a list. */
    private static List<AnnotationNode> annotationNodes(Object value) {
        final List<AnnotationNode> nodes = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object element : list) {
                nodes.add((AnnotationNode) element);
            }
        } else if (value instanceof AnnotationNode node) {
            nodes.add(node);
        }
        return nodes;
    }

    /** Array-valued annotation elements arrive as a list, single values as themselves. */
    private static String single(Object value) {
        if (value instanceof List<?> list) {
            assertEquals(1, list.size(), "one target method per wrap");
            return (String) list.get(0);
        }
        return (String) value;
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
