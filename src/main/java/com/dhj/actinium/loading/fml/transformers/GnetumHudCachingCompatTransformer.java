package com.dhj.actinium.loading.fml.transformers;

import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Bridges Gnetum's HUD caching with the GLSM state cache. Gnetum renders HUD elements into its own
 * cache framebuffers while {@code Gnetum.rendering} is true, forcing alpha factors
 * {@code (ONE, ONE_MINUS_SRC_ALPHA)} so the cache texture holds premultiplied alpha for the final
 * blit; under Actinium every caller is redirected to the GLSM cache, so Gnetum's interceptors never
 * run and the cache holds straight alpha, showing translucent HUD elements with flickering wrong
 * opacity.
 *
 * Gnetum registers its mixin configs through MixinBooter's early loader, so a mixin targeting its
 * classes cannot be guaranteed to apply; a launchwrapper transformer runs before the class is defined
 * regardless of load timing. This transformer mirrors every constant {@code Gnetum.rendering} store
 * inside {@code GuiIngameForgeMixin} onto {@code GLSMConfig.hudCacheOverride}.
 */
public final class GnetumHudCachingCompatTransformer implements IClassTransformer {
    private static final Logger LOGGER = LogManager.getLogger("Actinium");

    private static final String TARGET_CLASS =
        "me.decce.gnetum.mixins.early.GuiIngameForgeMixin";

    private static final String OVERRIDE_OWNER = "me/decce/gnetum/Gnetum";
    private static final String OVERRIDE_FIELD = "rendering";
    private static final String OVERRIDE_DESC = "Z";

    private static final String GLSM_CONFIG_OWNER =
        "com/gtnewhorizons/angelica/glsm/hooks/GLSMConfig";
    private static final String GLSM_CONFIG_FIELD = "hudCacheOverride";
    private static final String GLSM_CONFIG_DESC = "Z";

    // Gnetum 1.4.3 opens and closes the window exactly once per HUD render.
    private static final int EXPECTED_STORES = 2;

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || !TARGET_CLASS.equals(transformedName)) {
            return basicClass;
        }

        ClassReader classReader = new ClassReader(basicClass);
        ClassNode classNode = new ClassNode();
        classReader.accept(classNode, 0);

        int mirrored = 0;
        for (MethodNode method : classNode.methods) {
            mirrored += mirrorRenderingStores(method);
        }

        if (mirrored == 0) {
            LOGGER.warn(
                "Could not find any Gnetum.rendering stores in {}; "
                    + "Gnetum's cached HUD may flicker",
                TARGET_CLASS
            );
            return basicClass;
        }
        if (mirrored != EXPECTED_STORES) {
            LOGGER.warn(
                "Expected {} Gnetum.rendering stores in {} but found {}; mirrored all of them "
                    + "(untested Gnetum version?)",
                EXPECTED_STORES, TARGET_CLASS, mirrored
            );
        }

        // No branches are added and the stack balance is unchanged, so the
        // original stack map frames stay valid; copying them avoids resolving
        // classes while this class is still being defined.
        ClassWriter writer = new ClassWriter(classReader, 0);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    private static int mirrorRenderingStores(MethodNode method) {
        int mirrored = 0;

        for (AbstractInsnNode instruction = method.instructions.getFirst();
             instruction != null;
             instruction = instruction.getNext()) {

            if (instruction.getOpcode() == Opcodes.PUTSTATIC
                && isOverrideStore((FieldInsnNode) instruction)) {
                AbstractInsnNode pushed = instruction.getPrevious();
                int value = pushed != null ? constantValue(pushed) : -1;
                if (value < 0) {
                    LOGGER.warn("Gnetum.rendering store without a constant push in {}; aborting", TARGET_CLASS);
                    return 0;
                }
                // The pushed constant was consumed by the original store, so
                // re-push it for the mirrored store (net stack change: zero).
                // InsnList has no "insert after", so anchor on the next node.
                AbstractInsnNode anchor = instruction.getNext();
                InsnList mirror = new InsnList();
                mirror.add(new InsnNode(value == 1 ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
                mirror.add(new FieldInsnNode(
                    Opcodes.PUTSTATIC, GLSM_CONFIG_OWNER, GLSM_CONFIG_FIELD, GLSM_CONFIG_DESC));
                if (anchor == null) {
                    method.instructions.add(mirror);
                } else {
                    method.instructions.insertBefore(anchor, mirror);
                }
                mirrored++;
            }
        }

        return mirrored;
    }

    private static boolean isOverrideStore(FieldInsnNode field) {
        return OVERRIDE_OWNER.equals(field.owner)
            && OVERRIDE_FIELD.equals(field.name)
            && OVERRIDE_DESC.equals(field.desc);
    }

    private static int constantValue(AbstractInsnNode instruction) {
        if (instruction.getOpcode() == Opcodes.ICONST_0) {
            return 0;
        }
        if (instruction.getOpcode() == Opcodes.ICONST_1) {
            return 1;
        }
        return -1;
    }
}
