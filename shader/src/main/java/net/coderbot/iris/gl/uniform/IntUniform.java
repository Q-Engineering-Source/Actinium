package net.coderbot.iris.gl.uniform;

import com.gtnewhorizons.angelica.glsm.RenderSystem;
import net.coderbot.iris.gl.state.ValueUpdateNotifier;

import java.util.function.IntSupplier;

public class IntUniform extends Uniform {
	private int cachedValue;
	private final IntSupplier value;

	IntUniform(int location, IntSupplier value) {
		this(location, value, null);
	}

	IntUniform(int location, IntSupplier value, ValueUpdateNotifier notifier) {
		super(location, notifier);

		this.cachedValue = 0;
		this.value = value;
	}

	@Override
	protected void updateValue() {
		int newValue = value.getAsInt();

		if (cachedValue != newValue) {
			cachedValue = newValue;
			RenderSystem.uniform1i(location, newValue);
		}
	}
}
