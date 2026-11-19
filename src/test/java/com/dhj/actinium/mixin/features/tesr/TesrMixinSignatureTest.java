package com.dhj.actinium.mixin.features.tesr;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
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
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String CALLBACK_INFO = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String OPERATION = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";

    private static final String[] MIXINS = {
        "com/dhj/actinium/mixin/features/tesr/MixinShadowRendererTesr",
        "com/dhj/actinium/mixin/features/tesr/MixinShadowRendererEntities",
        "com/dhj/actinium/mixin/features/tesr/MixinRenderManagerBatchEligibility",
        "com/dhj/actinium/mixin/features/tesr/MixinTileEntityRendererDispatcherTesr",
        "com/dhj/actinium/mixin/features/iris/RenderIrisMixin",
        "com/dhj/actinium/mixin/features/iris/RenderLivingBaseIrisMixin",
        "com/dhj/actinium/mixin/features/tesr/MixinLayerArmorBaseGlint",
        "com/dhj/actinium/mixin/features/tesr/MixinLayerHeldBlockBatchEligibility",
        "com/dhj/actinium/mixin/features/tesr/MixinRenderEntityFireBatch",
        "com/dhj/actinium/mixin/features/tesr/MixinRenderEntityShadowBatch",
        "com/dhj/actinium/mixin/features/tesr/MixinRenderItemHeldInstancing",
        "com/dhj/actinium/mixin/features/tesr/MixinTileEntityChestRenderer",
        "com/dhj/actinium/mixin/features/tesr/MixinTileEntityEnderChestRenderer",
        "com/dhj/actinium/mixin/features/tesr/MixinTileEntitySignRenderer",
        "com/dhj/actinium/mixin/features/tesr/MixinTileEntitySkullRenderer",
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
            final ClassNode owner = readClass(targetOf(mixin));
            for (MethodNode handler : annotatedMethods(mixin, WRAP_OPERATION)) {
                final AnnotationNode annotation = annotation(handler, WRAP_OPERATION);
                final AnnotationNode at = (AnnotationNode) value(annotation, "at");
                final String label = mixinName + "." + handler.name;
                assertNotNull(at, label + " has no injection point");
                final String target = (String) value(at, "target");
                final String method = first(value(annotation, "method"));
                final MethodNode body = resolveTarget(owner, method);
                assertNotNull(body, label + " targets a missing method " + method);
                final List<MethodInsnNode> calls = new ArrayList<>();
                for (var instruction : body.instructions) {
                    if (instruction instanceof MethodInsnNode call && target.equals("L" + call.owner + ";" + call.name + call.desc)) {
                        calls.add(call);
                    }
                }
                final Object ordinal = value(at, "ordinal");
                final int index = ordinal instanceof Integer number && number >= 0 ? number : 0;
                assertTrue(index < calls.size(), label + " cannot find " + target + " in " + method);
                final MethodInsnNode call = calls.get(index);
                final List<String> expected = parameterTypes(call.desc);
                if (call.getOpcode() != Opcodes.INVOKESTATIC) {
                    expected.addFirst("L" + call.owner + ";");
                }
                assertEquals(expected, handlerParameterTypes(handler.desc),
                    label + " must take the called method's receiver and parameters before the Operation");
            }
        }
    }

    @Test
    void injectHandlersMatchTargetParametersAndStaticness() throws Exception {
        for (String mixinName : MIXINS) {
            final ClassNode mixin = readClass(mixinName);
            final ClassNode target = readClass(targetOf(mixin));
            for (MethodNode handler : annotatedMethods(mixin, INJECT)) {
                final AnnotationNode annotation = annotation(handler, INJECT);
                final MethodNode targetMethod = resolveTarget(target, first(value(annotation, "method")));
                final String label = mixinName + "." + handler.name;
                assertNotNull(targetMethod, label + " targets a missing method");
                final List<String> parameters = parameterTypes(targetMethod.desc);
                final int callback = parameters.size();
                assertTrue(callback < Type.getArgumentTypes(handler.desc).length, label + " has no callback parameter");
                assertEquals(CALLBACK_INFO, Type.getArgumentTypes(handler.desc)[callback].getDescriptor(),
                    label + " must take CallbackInfo immediately after the target parameters");
                assertEquals(parameters, handlerParameterTypes(handler.desc, callback),
                    label + " must take the target method's parameters before CallbackInfo");
                assertEquals((targetMethod.access & Opcodes.ACC_STATIC) != 0, (handler.access & Opcodes.ACC_STATIC) != 0,
                    label + " must match the target method's staticness");
            }
        }
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

    /** The handler parameters before {@code Operation} and any captured locals. */
    private static List<String> handlerParameterTypes(String descriptor) {
        final List<String> parameters = new ArrayList<>();
        for (Type argument : Type.getArgumentTypes(descriptor)) {
            parameters.add(argument.getDescriptor());
        }
        final int operationIndex = parameters.indexOf("L" + OPERATION + ";");
        assertTrue(operationIndex >= 0, "the handler must take an Operation parameter");
        return parameters.subList(0, operationIndex);
    }

    private static List<String> handlerParameterTypes(String descriptor, int count) {
        final List<String> parameters = parameterTypes(descriptor);
        return parameters.subList(0, count);
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
