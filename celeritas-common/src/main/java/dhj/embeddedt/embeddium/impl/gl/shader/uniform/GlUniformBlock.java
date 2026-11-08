package dhj.embeddedt.embeddium.impl.gl.shader.uniform;

import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.mitchej123.lwjgl.GL32;

import dhj.embeddedt.embeddium.impl.gl.buffer.GlBuffer;

public class GlUniformBlock {
    private final int binding;

    public GlUniformBlock(int uniformBlockBinding) {
        this.binding = uniformBlockBinding;
    }

    public void bindBuffer(GlBuffer buffer) {
        RenderSystem.bindBufferBase(GL32.GL_UNIFORM_BUFFER, this.binding, buffer.handle());
    }
}
