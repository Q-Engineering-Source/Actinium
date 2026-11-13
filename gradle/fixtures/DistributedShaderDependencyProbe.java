import com.gtnewhorizons.angelica.shadow.org.anarres.cpp.CppReader;
import com.gtnewhorizons.angelica.shadow.org.taumc.glsl.ShaderParser;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.util.spvc.Spvc;

import java.io.StringReader;

/**
 * Runs against the assembled distribution jar with no ContainedDeps jars on the class path.
 * The host LWJGL core jar and extracted native files represent the loader supplied runtime.
 */
public final class DistributedShaderDependencyProbe {
    private DistributedShaderDependencyProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (ShaderParser.parseShader("#version 330\nvoid main() {}\n") == null) {
            throw new AssertionError("GLSL parser did not produce a parse tree");
        }

        try (CppReader reader = new CppReader(new StringReader("#define VALUE 3\nVALUE\n"))) {
            char[] buffer = new char[128];
            int length = reader.read(buffer);
            String preprocessed = new String(buffer, 0, length);
            if (!preprocessed.contains("3")) {
                throw new AssertionError("JCPP did not expand the macro");
            }
        }

        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0L) {
            throw new AssertionError("shaderc native compiler initialization failed");
        }
        Shaderc.shaderc_compiler_release(compiler);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer context = stack.mallocPointer(1);
            if (Spvc.spvc_context_create(context) != Spvc.SPVC_SUCCESS || context.get(0) == 0L) {
                throw new AssertionError("SPIRV-Cross native context initialization failed");
            }
            Spvc.spvc_context_destroy(context.get(0));
        }

        System.out.println("PASS: distributed shader dependencies are available from the mod body");
    }
}
