package dhj.embeddedt.embeddium.impl.render.shader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import dhj.embeddedt.embeddium.impl.gl.shader.GlShader;
import dhj.embeddedt.embeddium.impl.gl.shader.ShaderConstants;
import dhj.embeddedt.embeddium.impl.gl.shader.ShaderParser;
import dhj.embeddedt.embeddium.impl.gl.shader.ShaderType;

import static com.mitchej123.lwjgl.LWJGLServiceProvider.LWJGL;

public class ShaderLoader {
    /**
     * Creates an OpenGL shader from GLSL sources. The GLSL source file should be made available on the classpath at the
     * path of `/assets/{namespace}/shaders/{path}`. User defines can be used to declare variables in the shader source
     * after the version header, allowing for conditional compilation with macro code.
     */
    public static GlShader loadShader(ShaderType type, String name, ShaderConstants constants) {
        return new GlShader(type, name, downgradeIfNeeded(type, ShaderParser.parseShader(getShaderSource(name), ShaderLoader::getShaderSource, constants)));
    }

    private static final Pattern VERSION_DIRECTIVE = Pattern.compile("^#version.*$", Pattern.MULTILINE);
    private static final Pattern IN_PARAM = Pattern.compile("^in ", Pattern.MULTILINE);
    private static final Pattern OUT_PARAM = Pattern.compile("^out ", Pattern.MULTILINE);
    // GL_EXT_gpu_shader4 supplies the integer and unsigned integer types the terrain shaders use; without it the
    // downgraded sources fail to compile on GLSL 1.20.
    private static final String LEGACY_PREAMBLE = String.join("\n",
            "#version 120",
            "#extension GL_EXT_gpu_shader4 : require",
            "#define LEGACY",
            "#define uint unsigned int",
            "#define texture texture2D"
    ) + "\n";

    /**
     * Reports whether shaders must be rewritten to GLSL 1.20 because the context does not expose GL 3.2.
     */
    public static boolean useLegacyGlsl() {
        return !LWJGL.isOpenGLVersionSupported(3, 2);
    }

    /**
     * Rewrites a parsed shader source down to GLSL 1.20 when the context cannot compile the original version
     * directive. Returns the source unchanged on capable contexts.
     */
    public static String downgradeIfNeeded(ShaderType type, String shaderSource) {
        if (useLegacyGlsl()) {
            if (type != ShaderType.VERTEX && type != ShaderType.FRAGMENT) {
                throw new IllegalStateException("Cannot load non-vertex/fragment shader on old GL");
            }
            // Downlevel to GLSL 1.20
            shaderSource = VERSION_DIRECTIVE.matcher(shaderSource).replaceFirst(LEGACY_PREAMBLE);
            if (type == ShaderType.VERTEX) {
                shaderSource = IN_PARAM.matcher(shaderSource).replaceAll("attribute ");
            } else {
                shaderSource = IN_PARAM.matcher(shaderSource).replaceAll("varying ");
            }
            shaderSource = OUT_PARAM.matcher(shaderSource).replaceAll("varying ");
        }
        return shaderSource;
    }

    public static String getShaderSource(String name) {
        String[] splitStr;
        if(name.contains(":")) {
            splitStr = name.split(":", 2);
        } else {
            splitStr = new String[] { "minecraft", name };
        }
        String path = String.format("/assets/%s/shaders/%s", splitStr[0], splitStr[1]);

        try (InputStream in = ShaderLoader.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new RuntimeException("Shader not found: " + path);
            }

            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader source for " + path, e);
        }
    }
}
