package net.coderbot.iris.gbuffer_overrides.matching;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

public class ProgramTable<T> {
	private static final InputAvailability SHADOW_INPUTS = new InputAvailability(true, true);
	private final List<T> table = new ArrayList<>();

	public ProgramTable(BiFunction<RenderCondition, InputAvailability, T> constructor) {
		for (RenderCondition condition : RenderCondition.values()) {
			for (int packedAvailability = 0; packedAvailability < InputAvailability.NUM_VALUES; packedAvailability++) {
				InputAvailability availability = InputAvailability.unpack(packedAvailability);

				table.add(constructor.apply(condition, availability));
			}
		}
	}

	/** Selects a compiled pass from vertex inputs, independently of fixed-function shadow texture enables. */
	public T match(RenderCondition condition, InputAvailability availability) {
		// Shadow draws retain texture and lightmap coordinates even with their fixed-function units disabled.
		if (condition == RenderCondition.SHADOW || condition == RenderCondition.SHADOW_TRANSLUCENT) {
			availability = SHADOW_INPUTS;
		}
		int index = (condition.ordinal() * InputAvailability.NUM_VALUES) + availability.pack();

		return table.get(index);
	}

	public void forEach(Consumer<T> consumer) {
		table.forEach(consumer);
	}
}
