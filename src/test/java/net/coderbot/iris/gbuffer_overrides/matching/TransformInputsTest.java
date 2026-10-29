package net.coderbot.iris.gbuffer_overrides.matching;

import net.coderbot.iris.shaderpack.loading.ProgramId;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransformInputsTest {
    /**
     * The program table hands every unpacked availability to a pass, so a submission set that misses one
     * leaves that pass transforming on the thread building the pipeline. The lightmap-only combination is
     * the one a hand-written list loses, since the packed values suggest four combinations of two inputs.
     */
    @Test
    void submittedAvailabilitiesCoverEveryAvailabilityAPassCanAskFor() {
        final Set<InputAvailability> submitted = new HashSet<>(TransformInputs.availabilities());

        for (final ProgramId id : ProgramId.values()) {
            for (int packed = 0; packed < InputAvailability.NUM_VALUES; packed++) {
                final InputAvailability lookup = TransformInputs.of(id, InputAvailability.unpack(packed));
                assertTrue(submitted.contains(lookup),
                    "no transform is submitted for " + id + " seeing " + lookup);
            }
        }
    }

    /**
     * A duplicate would submit the same transform twice, and the sky variant has to be there on top of the
     * packed combinations because no packed value reports the color input as unavailable.
     */
    @Test
    void submittedAvailabilitiesAreTheDistinctLookups() {
        final List<InputAvailability> submitted = TransformInputs.availabilities();

        assertTrue(submitted.containsAll(InputAvailability.unpackAll()));
        assertTrue(submitted.contains(TransformInputs.NO_COLOR));
        assertEquals(InputAvailability.unpackAll().size() + 1, submitted.size());
        assertEquals(submitted.size(), new HashSet<>(submitted).size());
    }
}
