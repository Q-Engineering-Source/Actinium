package net.coderbot.iris.gl.uniform;

import com.gtnewhorizons.angelica.glsm.RenderSystem;
import net.coderbot.iris.gl.state.ValueUpdateNotifier;

import java.util.Arrays;
import java.util.function.Supplier;

public class Vector4ArrayUniform extends Uniform {
    private final Supplier<float[]> value;
    // Held by value, so that reused supplier arrays cannot alias the cache
    private final float[] cachedValue;

    Vector4ArrayUniform(int location, Supplier<float[]> value) {
        this(location, value, null);
    }

    Vector4ArrayUniform(int location, Supplier<float[]> value, ValueUpdateNotifier notifier) {
        super(location, notifier);

        this.cachedValue = new float[4];
        this.value = value;
    }

    @Override
    public void update() {
        updateValue();

        if (notifier != null) {
            notifier.setListener(this::updateValue);
        }
    }

    private void updateValue() {
        float[] newValue = value.get();

        if (!Arrays.equals(newValue, cachedValue)) {
            System.arraycopy(newValue, 0, cachedValue, 0, 4);
            RenderSystem.uniform4f(location, cachedValue[0], cachedValue[1], cachedValue[2], cachedValue[3]);
        }
    }
}
