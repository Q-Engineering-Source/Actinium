package net.coderbot.iris.pipeline;

import net.coderbot.iris.gbuffer_overrides.matching.InputAvailability;
import net.coderbot.iris.gbuffer_overrides.matching.RenderCondition;
import net.coderbot.iris.shaderpack.loading.ProgramId;
import org.junit.jupiter.api.Test;

import static net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline.gbufferProgramId;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Locks the program id a rendering condition renders with. The table holds one column of ids per input
 * combination, and a condition that has no id of its own for a combination falls back to the unconditioned id of
 * the same column - so a column that is null in both places renders with no program of its own.
 */
class GbufferProgramIdTest {
    private static final int COLUMNS = 3;
    private static final InputAvailability PLAIN = new InputAvailability(false, false);
    private static final InputAvailability TEXTURED = new InputAvailability(true, false);
    private static final InputAvailability TEXTURED_LIT = new InputAvailability(true, true);

    @Test
    void eachInputCombinationPicksItsOwnColumn() {
        final ProgramId[] ids = emptyTable();
        set(ids, RenderCondition.DEFAULT, 0, ProgramId.Basic);
        set(ids, RenderCondition.DEFAULT, 1, ProgramId.Textured);
        set(ids, RenderCondition.DEFAULT, 2, ProgramId.TexturedLit);

        assertEquals(ProgramId.Basic, gbufferProgramId(ids, RenderCondition.DEFAULT, PLAIN));
        assertEquals(ProgramId.Textured, gbufferProgramId(ids, RenderCondition.DEFAULT, TEXTURED));
        assertEquals(ProgramId.TexturedLit, gbufferProgramId(ids, RenderCondition.DEFAULT, TEXTURED_LIT));
    }

    @Test
    void aConditionWithItsOwnIdUsesIt() {
        final ProgramId[] ids = emptyTable();
        set(ids, RenderCondition.DEFAULT, 0, ProgramId.Basic);
        set(ids, RenderCondition.GLINT, 0, ProgramId.ArmorGlint);

        assertEquals(ProgramId.ArmorGlint, gbufferProgramId(ids, RenderCondition.GLINT, PLAIN));
        assertEquals(ProgramId.Basic, gbufferProgramId(ids, RenderCondition.DEFAULT, PLAIN));
    }

    @Test
    void aNullSlotFallsBackToTheUnconditionedIdOfTheSameColumn() {
        final ProgramId[] ids = emptyTable();
        set(ids, RenderCondition.DEFAULT, 0, ProgramId.Basic);
        set(ids, RenderCondition.DEFAULT, 1, ProgramId.Textured);
        set(ids, RenderCondition.DEFAULT, 2, ProgramId.TexturedLit);

        assertEquals(ProgramId.Textured, gbufferProgramId(ids, RenderCondition.GLINT, TEXTURED),
            "glint has no textured id, so it uses the unconditioned one");
        assertEquals(ProgramId.TexturedLit, gbufferProgramId(ids, RenderCondition.GLINT, TEXTURED_LIT));
    }

    @Test
    void aColumnThatIsNullInBothPlacesResolvesToNothing() {
        final ProgramId[] ids = emptyTable();
        set(ids, RenderCondition.DEFAULT, 0, ProgramId.Basic);

        assertNull(gbufferProgramId(ids, RenderCondition.GLINT, TEXTURED));
        assertNull(gbufferProgramId(ids, RenderCondition.DEFAULT, TEXTURED));
    }

    private static ProgramId[] emptyTable() {
        return new ProgramId[RenderCondition.values().length * COLUMNS];
    }

    private static void set(ProgramId[] ids, RenderCondition condition, int column, ProgramId id) {
        ids[condition.ordinal() * COLUMNS + column] = id;
    }
}
