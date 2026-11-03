package com.gtnewhorizons.angelica.client.rendering;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import dhj.embeddedt.embeddium.impl.gl.shader.uniform.GlUniform;
import com.gtnewhorizons.angelica.glsm.GLSMPrograms;

public class GlUniformFloat2v extends GlUniform<float[]> {
    public GlUniformFloat2v(int index) {
        super(index);
    }

    @Override
    public void set(float[] value) {
        if (value.length != 2) {
            throw new IllegalArgumentException("value.length != 2");
        }

        GLSMPrograms.glUniform2f(this.index, value[0], value[1]);
    }

    public void set(float x, float y) {
        GLSMPrograms.glUniform2f(this.index, x, y);
    }
}
