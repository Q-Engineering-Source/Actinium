package net.coderbot.iris.gbuffer_overrides.matching;

import net.coderbot.iris.shaderpack.loading.ProgramId;

import java.util.ArrayList;
import java.util.List;

/**
 * The input availabilities a pass asks its attribute transform for.
 *
 * <p>{@link ProgramTable} builds a pass for every availability {@link InputAvailability#unpack(int)}
 * reports, and {@link #of} substitutes a no-color variant for the sky, so submitters of transforms have
 * to cover both. Kept out of the pipeline so that the coverage is checkable without a render context.</p>
 */
public final class TransformInputs {
	/**
	 * What sky programs that read a texture but no lightmap are transformed as: the sky disc carries no
	 * vertex color, and a packed availability always reports the color input as available.
	 */
	public static final InputAvailability NO_COLOR = new InputAvailability(true, false, false);

	private static final List<InputAvailability> AVAILABILITIES = buildAvailabilities();

	private TransformInputs() {}

	/**
	 * {@return every availability a pass can ask a transform for}
	 *
	 * <p>Submitting exactly these is what lets a pipeline read its transforms back out of the transform
	 * cache: an availability this list does not cover is transformed on the thread building it.</p>
	 */
	public static List<InputAvailability> availabilities() {
		return AVAILABILITIES;
	}

	/** {@return the availability the attribute transform of a pass of {@code id} is keyed on} */
	public static InputAvailability of(ProgramId id, InputAvailability inputs) {
		if (id == ProgramId.SkyTextured && inputs.texture && !inputs.lightmap) {
			return NO_COLOR;
		}

		return inputs;
	}

	private static List<InputAvailability> buildAvailabilities() {
		final List<InputAvailability> values = new ArrayList<>(InputAvailability.unpackAll());
		values.add(NO_COLOR);
		return List.copyOf(values);
	}
}
