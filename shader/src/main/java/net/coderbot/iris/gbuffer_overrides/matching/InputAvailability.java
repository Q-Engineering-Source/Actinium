package net.coderbot.iris.gbuffer_overrides.matching;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class InputAvailability {
	public static final int NUM_VALUES = 8;

	public final boolean texture;
	public final boolean lightmap;
	public final boolean color;

	public InputAvailability(boolean texture, boolean lightmap) {
		this(texture, lightmap, true);
	}

	public InputAvailability(boolean texture, boolean lightmap, boolean color) {
		this.texture = texture;
		this.lightmap = lightmap;
		this.color = color;
	}

	public static InputAvailability unpack(int packed) {
		return new InputAvailability((packed & 1) == 1, (packed & 2) == 2, true);
	}

	/**
	 * {@return every distinct availability a packed value unpacks to, in packed order}
	 *
	 * <p>{@link #unpack(int)} always reports the color input as available, so the distinct combinations
	 * are fewer than {@link #NUM_VALUES}. Callers that have to cover the whole program table enumerate
	 * through this rather than writing the combinations out by hand: a hand-written list only has to
	 * forget one of them for a lookup to fall through to a synchronous transform.</p>
	 */
	public static List<InputAvailability> unpackAll() {
		final Set<InputAvailability> values = new LinkedHashSet<>(NUM_VALUES);

		for (int packed = 0; packed < NUM_VALUES; packed++) {
			values.add(unpack(packed));
		}

		return List.copyOf(values);
	}

	public int pack() {
		int packed = 0;

		if (lightmap) {
			packed |= 2;
		}

		if (texture) {
			packed |= 1;
		}

		return packed;
	}

	@Override
	public String toString() {
		return "InputAvailability{" +
			"texture=" + texture +
			", lightmap=" + lightmap +
			", color=" + color +
			'}';
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + (lightmap ? 1231 : 1237);
		result = prime * result + (texture ? 1231 : 1237);
		result = prime * result + (color ? 1231 : 1237);
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		InputAvailability other = (InputAvailability) obj;
		if (lightmap != other.lightmap)
			return false;
		if (texture != other.texture)
			return false;
		if (color != other.color)
			return false;
		return true;
	}
}
