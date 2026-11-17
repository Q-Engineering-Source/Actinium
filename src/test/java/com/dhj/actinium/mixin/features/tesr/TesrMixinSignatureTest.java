package com.dhj.actinium.mixin.features.tesr;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the TESR injection handlers take the parameters their targets declare.
 *
 * <p>Mixinextras matches a handler's parameters against the target method (for {@code @WrapMethod})
 * or against the invoked method plus its receiver (for {@code @WrapOperation}). A parameter typed
 * {@code Object} where the target declares a specific class does not get coerced, so the mixin fails
 * when it is applied at startup rather than when it compiles. Comparing the descriptors catches that
 * before the game runs.</p>
 */
class TesrMixinSignatureTest {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String WRAP_METHOD = "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;";
    private static final String WRAP_OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
    private static final String OPERATION = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";

    private static final String[] MIXINS = {
        "com/dhj/actinium/mixin/features/tesr/MixinShadowRendererTesr",
        "com/dhj/actinium/mixin/features/tesr/MixinShadowRendererEntities",
        "com/dhj/actinium/mixin/features/tesr/MixinRenderManagerBatchEligibility",
        "com/dhj/actinium/mixin/features/tesr/MixinTileEntityRendererDispatcherTesr",
    };

    @Test
    void wrapMethodHandlersTakeTheTargetParameters() throws Exception {
        for (String mixinName : MIXINS) {
            final ClassNode mixin = readClass(mixinName);
            final ClassNode target = readClass(targetOf(mixin));
            for (MethodNode handler : annotatedMethods(mixin, WRAP_METHOD)) {
                final AnnotationNode annotation = annotation(handler, WRAP_METHOD);
                final Object method = value(annotation, "method");
                assertNotNull(method, mixinName + "." + handler.name + " names no target method");
                final MethodNode targetMethod = resolveTarget(target, first(method));
                assertNotNull(targetMethod, mixinName + "." + handler.name + " targets a missing method");
                assertEquals(parameterTypes(targetMethod.desc), handlerParameterTypes(handler.desc),
                    mixinName + "." + handler.name + " must take the target's parameters before the Operation");
            }
        }
    }

    @Test
    void wrapOperationHandlersTakeTheCalledMethodParameters() throws Exception {
        for (String mixinName : MIXINS) {
            final ClassNode mixin = readClass(mixinName);
            for (MethodNode handler : annotatedMethods(mixin, WRAP_OPERATION)) {
                final AnnotationNode annotation = annotation(handler, WRAP_OPERATION);
                final AnnotationNode at = (AnnotationNode) value(annotation, "at");
                assertNotNull(at, mixinName + "." + handler.name + " has no injection point");
                final List<String> expected = invokedParameters((String) value(at, "target"));
                assertEquals(expected, handlerParameterTypes(handler.desc),
                    mixinName + "." + handler.name + " must take the called method's receiver and parameters");
            }
        }
    }

    private static List<String> invokedParameters(String target) {
        final int semicolon = target.indexOf(';');
        final int paren = target.indexOf('(');
        final int close = target.indexOf(')');
        assertTrue(semicolon > 0 && paren > semicolon && close > paren, "unparsable injection point " + target);
        final List<String> parameters = new ArrayList<>();
        parameters.add(target.substring(0, semicolon + 1));
        for (Type argument : Type.getArgumentTypes(target.substring(paren, close + 1))) {
            parameters.add(argument.getDescriptor());
        }
        return parameters;
    }

    private static MethodNode resolveTarget(ClassNode target, String method) {
        final int paren = method.indexOf('(');
        final String name = paren < 0 ? method : method.substring(0, paren);
        final String descriptor = paren < 0 ? null : method.substring(paren);
        for (MethodNode candidate : target.methods) {
            if (!candidate.name.equals(name)) {
                continue;
            }
            if (descriptor == null || candidate.desc.equals(descriptor)) {
                return candidate;
            }
        }
        return null;
    }

    /** The handler parameters without the trailing {@code Operation}. */
    private static List<String> handlerParameterTypes(String descriptor) {
        final List<String> parameters = new ArrayList<>();
        for (Type argument : Type.getArgumentTypes(descriptor)) {
            parameters.add(argument.getDescriptor());
        }
        assertTrue(!parameters.isEmpty() && parameters.get(parameters.size() - 1).equals("L" + OPERATION + ";"),
            "the handler has to end with the Operation parameter");
        return parameters.subList(0, parameters.size() - 1);
    }

    private static List<String> parameterTypes(String descriptor) {
        final List<String> parameters = new ArrayList<>();
        for (Type argument : Type.getArgumentTypes(descriptor)) {
            parameters.add(argument.getDescriptor());
        }
        return parameters;
    }

    private static String targetOf(ClassNode mixin) {
        final AnnotationNode annotation = annotation(mixin, MIXIN);
        assertNotNull(annotation, mixin.name + " carries no @Mixin");
        final Object value = value(annotation, "value");
        assertTrue(value instanceof Type, mixin.name + " must declare its target class");
        return ((Type) value).getInternalName();
    }

    private static List<MethodNode> annotatedMethods(ClassNode node, String descriptor) {
        final List<MethodNode> methods = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (annotation(method, descriptor) != null) {
                methods.add(method);
            }
        }
        return methods;
    }

    private static AnnotationNode annotation(ClassNode node, String descriptor) {
        return find(node.visibleAnnotations, node.invisibleAnnotations, descriptor);
    }

    private static AnnotationNode annotation(MethodNode node, String descriptor) {
        return find(node.visibleAnnotations, node.invisibleAnnotations, descriptor);
    }

    private static AnnotationNode find(List<AnnotationNode> visible, List<AnnotationNode> invisible, String descriptor) {
        for (List<AnnotationNode> annotations : List.of(visible == null ? List.<AnnotationNode>of() : visible,
            invisible == null ? List.<AnnotationNode>of() : invisible)) {
            for (AnnotationNode annotation : annotations) {
                if (annotation.desc.equals(descriptor)) {
                    return annotation;
                }
            }
        }
        return null;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation == null || annotation.values == null) {
            return null;
        }
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) {
                final Object value = annotation.values.get(i + 1);
                return value instanceof List<?> list ? (list.isEmpty() ? null : list.get(0)) : value;
            }
        }
        return null;
    }

    private static String first(Object method) {
        return method instanceof String text ? text : String.valueOf(method);
    }

    private static ClassNode readClass(String internalName) throws IOException {
        final String resource = internalName + ".class";
        try (InputStream in = TesrMixinSignatureTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "class resource " + resource);
            final ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }
}
