package com.dhj.actinium.mixin.features.iris;

import net.minecraft.client.renderer.EntityRenderer;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks where the main-view frustum reaches the shadow replay.
 *
 * <p>{@code renderEntities} runs from {@code renderWorldPass}, not from {@code renderWorld}: an
 * injection anchored on the call inside {@code renderWorld} never applies, and the mixin would fail
 * the whole class when the configuration asks for it. The handler also has to take the pass method's
 * parameters before the callback, otherwise Mixin refuses the signature.</p>
 */
class EntityRendererMainViewAnchorTest {

    private static final String RENDER_GLOBAL = "net/minecraft/client/renderer/RenderGlobal";
    private static final String RENDER_ENTITIES = "renderEntities";
    private static final String RENDER_ENTITIES_DESC =
        "(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;F)V";
    private static final String HANDLER = "actinium$publishMainViewFrustum";
    private static final String HANDLER_DESC =
        "(IFJLorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;Lnet/minecraft/client/renderer/culling/ICamera;)V";

    @Test
    void theFrustumHookAnchorsOnThePassThatRendersEntities() throws Exception {
        final ClassNode entityRenderer = readClass(EntityRenderer.class);
        final MethodNode world = findMethod(entityRenderer, "renderWorld", "(FJ)V");
        assertNotNull(world, "renderWorld must still exist");
        assertFalse(containsRenderEntities(world),
            "renderWorld does not render entities; anchoring there makes the injection unapplicable");

        final AnnotationNode inject = findInjectAnnotation(readClass(EntityRendererIrisMixin.class));
        assertNotNull(inject, "the handler must carry the injection that publishes the frustum");
        final String target = (String) annotationValue(inject, "method");
        assertNotNull(target);
        assertEquals("renderWorldPass(IFJ)V", target,
            "the frustum has to be published from the pass that renders the entity list");

        final int paren = target.indexOf('(');
        final MethodNode anchored = findMethod(entityRenderer, target.substring(0, paren), target.substring(paren));
        assertNotNull(anchored, "the anchored method must exist on the target class");
        assertTrue(containsRenderEntities(anchored), "the anchored method has to contain the entity call site");
    }

    @Test
    void theHandlerTakesThePassParametersAndTheFrustum() throws Exception {
        final ClassNode mixin = readClass(EntityRendererIrisMixin.class);
        final MethodNode handler = findMethod(mixin, HANDLER, HANDLER_DESC);
        assertNotNull(handler, "the handler has to take (int, float, long, CallbackInfo, ICamera)");
        assertEquals(Opcodes.ACC_PRIVATE, handler.access & Opcodes.ACC_PRIVATE);
    }

    private static boolean containsRenderEntities(MethodNode method) {
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && call.owner.equals(RENDER_GLOBAL)
                && call.name.equals(RENDER_ENTITIES)
                && call.desc.equals(RENDER_ENTITIES_DESC)) {
                return true;
            }
        }
        return false;
    }

    private static AnnotationNode findInjectAnnotation(ClassNode mixin) {
        final MethodNode handler = findMethod(mixin, HANDLER, HANDLER_DESC);
        assertNotNull(handler, "the handler has to take (int, float, long, CallbackInfo, ICamera)");
        final List<AnnotationNode> annotations = handler.visibleAnnotations != null
            ? handler.visibleAnnotations : handler.invisibleAnnotations;
        if (annotations == null) {
            return null;
        }
        for (AnnotationNode annotation : annotations) {
            if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")) {
                return annotation;
            }
        }
        return null;
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        if (annotation.values == null) {
            return null;
        }
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) {
                final Object value = annotation.values.get(i + 1);
                return value instanceof List<?> list ? list.get(0) : value;
            }
        }
        return null;
    }

    private static MethodNode findMethod(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                return method;
            }
        }
        return null;
    }

    private static ClassNode readClass(Class<?> type) throws Exception {
        final String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, "class resource " + resource);
            final ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }
}
