package com.gtnewhorizons.angelica.loading.fml.transformers;

import com.gtnewhorizons.angelica.glsm.redirect.RedirectorDebugOptions;
import com.gtnewhorizons.angelica.glsm.redirect.GLSMRedirector;
import com.gtnewhorizons.angelica.loading.shared.AngelicaClassDump;
import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class EarlyRedirectorTransformer implements IClassTransformer {

    private static final boolean DEBUG = RedirectorDebugOptions.enableDebug();
    private static final String[] EARLY_REDIRECTOR_TARGETS = {
        "cn.tesseract.mycelium.",
    };

    private final GLSMRedirector core = new GLSMRedirector();
    private final String[] exclusions;

    public EarlyRedirectorTransformer() {
        List<String> excl = new ArrayList<>(Arrays.asList(core.getCoreExclusions()));
        excl.add("com.gtnewhorizons.angelica.lwjgl3.");
        excl.add("com.gtnewhorizons.angelica.loading.");
        excl.add("com.gtnewhorizons.angelica.glsm.loading.");
        excl.add("com.gtnewhorizons.angelica.transform");
        exclusions = excl.toArray(new String[0]);
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }

        boolean targeted = false;
        for (String pkg : EARLY_REDIRECTOR_TARGETS) {
            if (transformedName.startsWith(pkg)) {
                targeted = true;
                break;
            }
        }
        if (!targeted) {
            return basicClass;
        }

        for (String exclusion : exclusions) {
            if (transformedName.startsWith(exclusion)) {
                return basicClass;
            }
        }

        if (!core.shouldTransform(basicClass)) {
            return basicClass;
        }

        ClassReader cr = new ClassReader(basicClass);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        if (!core.transformClassNode(transformedName, cn)) {
            return basicClass;
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        byte[] bytes = cw.toByteArray();
        if (DEBUG) {
            System.out.println("[ActiniumRedirector] early transformed " + transformedName);
        }
        AngelicaClassDump.dumpClass(transformedName, basicClass, bytes, this);
        return bytes;
    }
}
