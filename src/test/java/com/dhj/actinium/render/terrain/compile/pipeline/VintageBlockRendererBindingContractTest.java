package com.dhj.actinium.render.terrain.compile.pipeline;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the third-party addon binding surface of {@code VintageBlockRenderer}. Celeritas addons
 * adapted for Actinium (e.g. celeritasleafculling's VintageBlockRendererMixin) @Shadow the private
 * fields and the private {@code renderQuadList} method, and @Redirect the {@code renderQuadList}
 * call sites inside {@code renderBlock}; renaming or re-signing any of these breaks those addons
 * silently at runtime.
 *
 * <p>Since the 2026 class-path refactor the parameter types in that descriptor live under
 * {@code dhj.embeddedt.embeddium.impl}, not the upstream {@code org.embeddedt.embeddium.impl}
 * namespace, so an addon that still binds the upstream binary names has to migrate with Actinium.
 *
 * <p>This contract replaces the bridge-era
 * {@code CeleritasCompatBridgeJarTest#legacyRendererRetainsThirdPartyMixinBindingContract}, which
 * guarded the same surface on the removed compatibility bridge.
 *
 * <p>The face-culling call site in {@code renderBlock} is locked here as a deliberate invariant
 * rather than as an observed addon binding: celeritasleafculling's adapted
 * {@code VintageBlockRendererMixin} binds only the two shadowed fields and the
 * {@code renderQuadList} call sites, but the class is a third-party mixin target, so an addon that
 * {@code @Redirect}s {@code IBlockState.shouldSideBeRendered} inside {@code renderBlock} would
 * silently stop matching if that instruction were moved into a helper method.
 */
class VintageBlockRendererBindingContractTest {
    private static final String CLASS_NAME =
            "com/dhj/actinium/render/terrain/compile/pipeline/VintageBlockRenderer";
    private static final String BLOCK_STATE_DESCRIPTOR = "Lnet/minecraft/block/state/IBlockState;";
    private static final String BLOCK_ACCESS_DESCRIPTOR =
            "Lcom/dhj/actinium/world/cloned/ActiniumBlockAccess;";
    private static final String SHOULD_SIDE_BE_RENDERED_DESCRIPTOR =
            "(Lnet/minecraft/world/IBlockAccess;"
            + "Lnet/minecraft/util/math/BlockPos;"
            + "Lnet/minecraft/util/EnumFacing;)Z";
    private static final String RENDER_QUAD_LIST_DESCRIPTOR =
            "(Ldhj/embeddedt/embeddium/impl/render/chunk/compile/buffers/ChunkModelBuilder;"
            + "Ldhj/embeddedt/embeddium/impl/render/chunk/compile/ChunkBuildBuffers;"
            + "Ldhj/embeddedt/embeddium/impl/render/chunk/terrain/material/Material;"
            + "Lnet/minecraft/util/math/BlockPos;"
            + "Lnet/minecraft/util/EnumFacing;"
            + "Ldhj/embeddedt/embeddium/impl/model/light/LightPipeline;"
            + "Lnet/minecraft/client/renderer/color/IBlockColor;"
            + "Lnet/minecraft/util/math/Vec3d;"
            + "Ljava/util/List;)V";

    @Test
    void retainsShadowedPrivateFields() throws IOException {
        ClassNode node = readRendererClass();

        FieldNode currentState = findField(node, "currentState");
        assertTrue((currentState.access & Opcodes.ACC_PRIVATE) != 0, "currentState must stay private");
        assertEquals(BLOCK_STATE_DESCRIPTOR, currentState.desc, "currentState descriptor");

        FieldNode currentBlockAccess = findField(node, "currentBlockAccess");
        assertTrue((currentBlockAccess.access & Opcodes.ACC_PRIVATE) != 0,
                "currentBlockAccess must stay private");
        assertEquals(BLOCK_ACCESS_DESCRIPTOR, currentBlockAccess.desc, "currentBlockAccess descriptor");
    }

    @Test
    void retainsRenderQuadListSignature() throws IOException {
        ClassNode node = readRendererClass();
        MethodNode renderQuadList = node.methods.stream()
                .filter(method -> method.name.equals("renderQuadList"))
                .findFirst()
                .orElse(null);

        assertNotNull(renderQuadList, "renderQuadList must exist for addon @Shadow/@Redirect binding");
        assertTrue((renderQuadList.access & Opcodes.ACC_PRIVATE) != 0, "renderQuadList must stay private");
        assertEquals(RENDER_QUAD_LIST_DESCRIPTOR, renderQuadList.desc,
                "renderQuadList descriptor is part of the addon binding contract");
    }

    @Test
    void renderBlockKeepsRenderQuadListCallSites() throws IOException {
        ClassNode node = readRendererClass();
        int callSites = 0;

        for (MethodNode method : node.methods) {
            if (!method.name.equals("renderBlock")) {
                continue;
            }
            for (var instruction = method.instructions.getFirst(); instruction != null;
                 instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals(CLASS_NAME)
                        && call.name.equals("renderQuadList")
                        && call.desc.equals(RENDER_QUAD_LIST_DESCRIPTOR)) {
                    callSites++;
                }
            }
        }

        assertTrue(callSites >= 2,
                "renderBlock must keep the per-face and no-face renderQuadList call sites addons "
                        + "@Redirect (found " + callSites + ")");
    }

    @Test
    void renderBlockKeepsTheFaceCullingCallSite() throws IOException {
        ClassNode node = readRendererClass();
        int callSites = 0;

        for (MethodNode method : node.methods) {
            if (!method.name.equals("renderBlock")) {
                continue;
            }
            for (var instruction = method.instructions.getFirst(); instruction != null;
                 instruction = instruction.getNext()) {
                if (instruction instanceof MethodInsnNode call
                        && call.owner.equals("net/minecraft/block/state/IBlockState")
                        && call.name.equals("shouldSideBeRendered")
                        && call.desc.equals(SHOULD_SIDE_BE_RENDERED_DESCRIPTOR)) {
                    callSites++;
                }
            }
        }

        assertEquals(1, callSites,
                "renderBlock must keep its IBlockState.shouldSideBeRendered call at the call site: "
                        + "addon mixins @Redirect instructions inside renderBlock, so moving the "
                        + "predicate into a helper method would silently drop their injection "
                        + "(found " + callSites + ")");
    }

    private static ClassNode readRendererClass() throws IOException {
        ClassNode node = new ClassNode();
        String resourceName = CLASS_NAME + ".class";
        try (InputStream stream = Objects.requireNonNull(
                VintageBlockRendererBindingContractTest.class.getClassLoader().getResourceAsStream(resourceName),
                "Missing " + resourceName)) {
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return node;
    }

    private static FieldNode findField(ClassNode node, String name) {
        return node.fields.stream()
                .filter(field -> field.name.equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " is part of the addon binding contract"));
    }
}
