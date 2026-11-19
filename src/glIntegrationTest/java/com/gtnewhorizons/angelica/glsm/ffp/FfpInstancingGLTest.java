package com.gtnewhorizons.angelica.glsm.ffp;

import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FfpInstancingGLTest extends GLStateManagerIntegrationFixture {

    private static final int BIT_OVERLAY_INSTANCED = 13;

    @Test
    void templateOverlayLinksItsVertexAndFragmentShaders() {
        final String vertexSource = VertexShaderGenerator.generate(
            VertexKey.fromPacked(VertexKey.withInstancing(0L, Instancing.TEMPLATE)));
        final String fragmentSource = FragmentShaderGenerator.generate(
            FragmentKey.fromPacked(new long[] {1L << BIT_OVERLAY_INSTANCED}, 1));

        int vertex = 0;
        int fragment = 0;
        int program = 0;
        try {
            vertex = compile(GL20.GL_VERTEX_SHADER, vertexSource);
            fragment = compile(GL20.GL_FRAGMENT_SHADER, fragmentSource);
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertex);
            GL20.glAttachShader(program, fragment);
            GL20.glLinkProgram(program);
            assertEquals(GL11.GL_TRUE, GL20.glGetProgrami(program, GL20.GL_LINK_STATUS),
                GL20.glGetProgramInfoLog(program));
        } finally {
            if (program != 0) GL20.glDeleteProgram(program);
            if (fragment != 0) GL20.glDeleteShader(fragment);
            if (vertex != 0) GL20.glDeleteShader(vertex);
        }
    }

    private static int compile(int type, String source) {
        final int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        final int status = GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS);
        if (status != GL11.GL_TRUE) {
            final String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new AssertionError(log);
        }
        return shader;
    }
}
