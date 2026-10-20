package net.coderbot.iris.gbuffer_overrides.matching;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProgramTableTest {
    private final ProgramTable<CompiledPass> table = new ProgramTable<>(CompiledPass::new);

    @Test
    void opaqueShadowSelectsTexturedLightmappedProgramWithEveryTextureEnableCombination() {
        for (int packed = 0; packed < 4; packed++) {
            CompiledPass pass = table.match(RenderCondition.SHADOW, InputAvailability.unpack(packed));

            assertEquals(RenderCondition.SHADOW, pass.condition());
            assertEquals(new InputAvailability(true, true), pass.inputs());
        }
    }

    @Test
    void translucentShadowSelectsTexturedLightmappedProgramWithEveryTextureEnableCombination() {
        for (int packed = 0; packed < 4; packed++) {
            CompiledPass pass = table.match(RenderCondition.SHADOW_TRANSLUCENT, InputAvailability.unpack(packed));

            assertEquals(RenderCondition.SHADOW_TRANSLUCENT, pass.condition());
            assertEquals(new InputAvailability(true, true), pass.inputs());
        }
    }

    @Test
    void allWorldPassesContinueSelectingTheirActualTextureAndLightmapInputs() {
        for (RenderCondition condition : RenderCondition.values()) {
            if (condition == RenderCondition.SHADOW || condition == RenderCondition.SHADOW_TRANSLUCENT) {
                continue;
            }
            for (int packed = 0; packed < 4; packed++) {
                InputAvailability inputs = InputAvailability.unpack(packed);
                CompiledPass pass = table.match(condition, inputs);

                assertEquals(condition, pass.condition());
                assertEquals(inputs, pass.inputs(), condition + " with " + inputs);
            }
        }
    }

    /** Represents the condition and inputs a real pass constructor received during table creation. */
    private record CompiledPass(RenderCondition condition, InputAvailability inputs) {
    }
}
