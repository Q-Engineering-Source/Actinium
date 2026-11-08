package dhj.embeddedt.embeddium.impl.gl.shader.uniform;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.GLStateManagerIntegrationFixture;
import dhj.embeddedt.embeddium.impl.gl.buffer.GlMutableBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Binds a uniform block through {@link GlUniformBlock} on a real context. The binding has to reach the GLSM indexed
 * binding cache, so a later binding query answers from the cache even when the driver slot was changed directly.
 */
class GlUniformBlockBindingGLTest extends GLStateManagerIntegrationFixture {

    private static final int BINDING_POINT = 2;
    private static final long BUFFER_SIZE = 64L;

    @BeforeAll
    static void enableStateCaching() {
        installDefaultGlsmConfig();
        GLStateManager.markSplashComplete("GlUniformBlockBindingGLTest");
        assumeTrue(GL.getCapabilities().OpenGL31, "no uniform buffer objects on this context");
    }

    @Test
    void bindBufferKeepsTheBindingInTheGlsmCacheWhenTheDriverSlotIsUnboundDirectly() {
        final GlMutableBuffer buffer = new GlMutableBuffer();
        try {
            GLStateManager.glBindBuffer(GL31.GL_UNIFORM_BUFFER, buffer.handle());
            GLStateManager.glBufferData(GL31.GL_UNIFORM_BUFFER, BUFFER_SIZE, GL15.GL_DYNAMIC_DRAW);
            GLStateManager.glBindBuffer(GL31.GL_UNIFORM_BUFFER, 0);

            new GlUniformBlock(BINDING_POINT).bindBuffer(buffer);
            assertEquals(buffer.handle(), GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING, BINDING_POINT),
                "bindBuffer must bind the buffer to the driver slot of its binding point");

            GL31.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, BINDING_POINT, 0);
            assertEquals(0, GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING, BINDING_POINT),
                "the direct driver unbind applies to the slot");

            assertEquals(buffer.handle(), GLStateManager.getIndexedBufferBinding(GL31.GL_UNIFORM_BUFFER, BINDING_POINT),
                "bindBuffer must record a successfully forwarded binding in the GLSM cache");
        } finally {
            GLStateManager.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, BINDING_POINT, 0);
            buffer.delete();
        }
    }
}
