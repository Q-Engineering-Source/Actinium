package net.coderbot.iris.gl.uniform;

import com.gtnewhorizons.angelica.glsm.RenderSystem;
import org.joml.Vector2f;

import java.util.function.Supplier;

public class Vector2Uniform extends Uniform {
	private final Supplier<Vector2f> value;
	// Held by value, so that reused supplier vectors cannot alias the cache
	private final Vector2f cachedValue;

	Vector2Uniform(int location, Supplier<Vector2f> value) {
		super(location);

		this.cachedValue = new Vector2f();
		this.value = value;

	}

	@Override
	public void update() {
		Vector2f newValue = value.get();

		if (!newValue.equals(cachedValue)) {
			cachedValue.set(newValue);
			RenderSystem.uniform2f(this.location, cachedValue.x, cachedValue.y);
		}
	}
}
