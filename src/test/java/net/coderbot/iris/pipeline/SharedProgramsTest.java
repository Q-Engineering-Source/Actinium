package net.coderbot.iris.pipeline;

import net.coderbot.iris.gbuffer_overrides.matching.InputAvailability;
import net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline.SharedProgramKey;
import net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline.SharedPrograms;
import net.coderbot.iris.pipeline.transform.PatchShaderType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Covers the decision a gbuffer pass makes before it builds anything: whether it may reuse a program an earlier
 * pass of the same pipeline build already linked, and which passes are allowed to share at all.
 *
 * <p>Several rendering conditions resolve to the same {@code ProgramSource} specialised to the same inputs, and
 * without this table each of them would compile and link an identical program. Sharing a program that is not
 * actually identical is the dangerous direction - the program would be wired for the wrong inputs - so the key
 * is exercised component by component.</p>
 */
class SharedProgramsTest {
    private static final String SOURCE = "gbuffers_armor_glint";
    private static final InputAvailability PLAIN = new InputAvailability(false, false);
    private static final InputAvailability TEXTURED = new InputAvailability(true, false);
    private static final InputAvailability TEXTURED_LIT = new InputAvailability(true, true);
    private static final Map<PatchShaderType, String> TRANSFORMED =
        Map.of(PatchShaderType.VERTEX, "void main() {}", PatchShaderType.FRAGMENT, "void main() {}");

    /**
     * A pass whose transform was not computed ahead of the build transforms its stages synchronously, keyed on
     * the program id, so its stages may differ from another pass's even where the share key would be equal.
     */
    @Test
    void aPassWithoutAPrecomputedTransformNeverShares() {
        assertNull(SharedProgramKey.of(null, SOURCE, TEXTURED, TEXTURED_LIT, false));
    }

    @Test
    void theKeyIsWhatThePassIsBuiltFrom() {
        final SharedProgramKey key = SharedProgramKey.of(TRANSFORMED, SOURCE, TEXTURED, TEXTURED_LIT, true);

        assertEquals(new SharedProgramKey(SOURCE, TEXTURED, TEXTURED_LIT, true), key);
    }

    @Test
    void keysWithAnyDifferentComponentDoNotMatch() {
        final SharedProgramKey key = key(SOURCE, TEXTURED, TEXTURED_LIT, true);

        assertEquals(key, key(SOURCE, TEXTURED, TEXTURED_LIT, true), "equal keys must be interchangeable");
        assertEquals(key.hashCode(), key(SOURCE, TEXTURED, TEXTURED_LIT, true).hashCode());

        // Every component is a reason to link a different program, so none of them may be dropped from equality.
        assertNotEquals(key, key("gbuffers_water", TEXTURED, TEXTURED_LIT, true), "a different source");
        assertNotEquals(key, key(SOURCE, PLAIN, TEXTURED_LIT, true), "a different attribute transform input");
        assertNotEquals(key, key(SOURCE, TEXTURED, PLAIN, true), "a different declared input");
        assertNotEquals(key, key(SOURCE, TEXTURED, TEXTURED_LIT, false), "the shadow pass binds other samplers");
    }

    /** The pass that misses builds and records; every later pass with an equal key reuses that one program. */
    @Test
    void severalPassesWithTheSameKeyBuildOneProgram() {
        final SharedPrograms<String> programs = new SharedPrograms<>();
        final List<String> served = new ArrayList<>();
        int builds = 0;

        for (int pass = 0; pass < 4; pass++) {
            final SharedProgramKey key = SharedProgramKey.of(TRANSFORMED, SOURCE, TEXTURED, TEXTURED_LIT, false);
            String program = programs.find(key);

            if (program == null) {
                builds++;
                program = "linked-" + builds;
                programs.record(key, program);
            }

            served.add(program);
        }

        assertEquals(1, builds, "only the first pass compiles and links");
        assertEquals(List.of("linked-1", "linked-1", "linked-1", "linked-1"), served);
    }

    @Test
    void aPassWithADifferentKeyBuildsItsOwnProgram() {
        final SharedPrograms<String> programs = new SharedPrograms<>();
        final SharedProgramKey gbuffer = key(SOURCE, TEXTURED, TEXTURED_LIT, false);
        final SharedProgramKey shadow = key(SOURCE, TEXTURED, TEXTURED_LIT, true);

        programs.record(gbuffer, "gbuffer program");

        assertSame("gbuffer program", programs.find(key(SOURCE, TEXTURED, TEXTURED_LIT, false)));
        assertNull(programs.find(shadow), "the shadow pass must not take the gbuffer program");
    }

    /** A pass that has no key keeps its program to itself, so nothing may be served for it. */
    @Test
    void aPassWithoutAKeyIsNeitherServedNorKept() {
        final SharedPrograms<String> programs = new SharedPrograms<>();
        programs.record(null, "built on its own");

        assertNull(programs.find(null));
        assertNull(programs.find(key(SOURCE, PLAIN, PLAIN, false)));
    }

    private static SharedProgramKey key(String sourceName, InputAvailability transformAvailability,
                                        InputAvailability availability, boolean shadow) {
        return new SharedProgramKey(sourceName, transformAvailability, availability, shadow);
    }
}
