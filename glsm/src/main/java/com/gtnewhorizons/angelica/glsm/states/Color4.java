package com.gtnewhorizons.angelica.glsm.states;

import lombok.Getter;
import lombok.Setter;

import java.nio.FloatBuffer;

@Getter @Setter
public class Color4 implements ISettableState<Color4> {
    protected float red = 1.0F;
    protected float green = 1.0F;
    protected float blue = 1.0F;
    protected float alpha = 1.0F;

    public Color4() {
    }

    public Color4(float red, float green, float blue, float alpha) {
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.alpha = alpha;
    }

    /**
     * Clamps one floating-point {@code glColor} component to [0,1], mirroring the GL API-boundary
     * semantics: per the GL specification values out of range are clamped when {@code glColor*} is
     * specified, so the current-color cache must never hold a negative (or above-one) component.
     *
     * <p>{@code GLStateManager.changeColor} applies this to every incoming {@code glColor*} call so
     * the FFP uniform upload never sees an out-of-range color that
     * {@code Uniforms.sanitizeUniformColor} would mistake for the {@code clearCurrentColor} dirty
     * sentinel — that misreading turned GalaxySpace's legitimately negative night sky color into an
     * opaque white sky dome (issue #164).</p>
     *
     * @param value raw component supplied by the {@code glColor*} caller
     * @return the component clamped into [0,1]
     */
    public static float clamp01(float value) {
        if (value < 0.0F) return 0.0F;
        if (value > 1.0F) return 1.0F;
        return value;
    }

    @Override
    public Color4 set(Color4 state) {
        this.red = state.red;
        this.green = state.green;
        this.blue = state.blue;
        this.alpha = state.alpha;

        return this;
    }

    public void get(FloatBuffer params) {
        params.put(0, red);
        params.put(1, green);
        params.put(2, blue);
        params.put(3, alpha);
    }

    @Override
    public boolean sameAs(Object state) {
        if (this == state) return true;
        if (!(state instanceof Color4 color4)) return false;
        return Float.compare(color4.red, red) == 0 && Float.compare(color4.green, green) == 0 && Float.compare(color4.blue, blue) == 0 && Float.compare(color4.alpha, alpha) == 0;
    }
    @Override
    public Color4 copy() {
        return new Color4().set(this);
    }
}
