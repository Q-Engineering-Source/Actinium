package com.gtnewhorizons.angelica.rendering.tesr;

import com.gtnewhorizons.angelica.glsm.GLSMFramebuffer;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import net.coderbot.iris.gl.blending.DepthColorStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BatchDrawDefaultsGLTest extends GLStateManagerIntegrationFixture {
    @BeforeAll
    static void initializeGlsm() {
        installDefaultGlsmConfig();
        GLStateManager.bindDisplayContext(new Object(), context);
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void fixedFunctionBatchUsesItsOwnStateAndRestoresTheCaller(boolean irisOwned) {
        assertNull(TesrBatchRenderer.deferredPipeline());
        final int program = linkProgram();
        final int callerFramebuffer = GLSMFramebuffer.glGenFramebuffers();
        final BatchDrawDefaults batch = new BatchDrawDefaults();
        try {
            if (irisOwned) DepthColorStorage.registerOwnedProgram(program);
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
            BatchDrawDefaults.capturePassFramebuffer();
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, callerFramebuffer);
            GLSMPrograms.glUseProgram(program);

            try {
                batch.apply();
                assertBindings(0, 0);
            } finally {
                batch.restore();
            }
            assertBindings(program, callerFramebuffer);
        } finally {
            GLSMPrograms.glUseProgram(0);
            DepthColorStorage.unregisterOwnedProgram(program);
            GLSMPrograms.glDeleteProgram(program);
            GLSMFramebuffer.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
            GLSMFramebuffer.glDeleteFramebuffers(callerFramebuffer);
        }
    }

    private static void assertBindings(int program, int framebuffer) {
        assertEquals(program, GLSMPrograms.getActiveProgram(), "GLSM program binding");
        assertEquals(program, GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), "driver program binding");
        assertEquals(framebuffer, GLSMFramebuffer.getDrawFramebuffer(), "GLSM draw framebuffer binding");
        assertEquals(framebuffer, GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), "driver draw framebuffer binding");
    }

    private static int linkProgram() {
        final int vertex = compile(GL20.GL_VERTEX_SHADER, """
            #version 330 core
            void main() { gl_Position = vec4(0.0); }
            """);
        final int fragment = compile(GL20.GL_FRAGMENT_SHADER, """
            #version 330 core
            out vec4 color;
            void main() { color = vec4(1.0); }
            """);
        final int program = GLSMPrograms.glCreateProgram();
        try {
            GLSMPrograms.glAttachShader(program, vertex);
            GLSMPrograms.glAttachShader(program, fragment);
            GLSMPrograms.glLinkProgram(program);
            assertEquals(GL11.GL_TRUE, GL20.glGetProgrami(program, GL20.GL_LINK_STATUS), () -> GL20.glGetProgramInfoLog(program));
            return program;
        } finally {
            GLSMPrograms.glDeleteShader(vertex);
            GLSMPrograms.glDeleteShader(fragment);
        }
    }

    private static int compile(int type, String source) {
        final int shader = GLSMPrograms.glCreateShader(type);
        GLSMPrograms.glShaderSource(shader, source);
        GLSMPrograms.glCompileShader(shader);
        assertEquals(GL11.GL_TRUE, GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS), () -> GL20.glGetShaderInfoLog(shader));
        return shader;
    }
}
