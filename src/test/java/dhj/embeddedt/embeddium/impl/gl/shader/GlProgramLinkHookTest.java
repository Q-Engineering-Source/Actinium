package dhj.embeddedt.embeddium.impl.gl.shader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the link-time hook a host installs to set the program parameters a link has to be told about
 * beforehand. Celeritas links its own programs, so this hook is the only place {@code GLStateManager}
 * settings like {@code GL_PROGRAM_BINARY_RETRIEVABLE_HINT} can reach them.
 */
class GlProgramLinkHookTest {

    @AfterEach
    void clearHook() {
        GlProgram.setLinkHook(null);
    }

    @Test
    void theHookReceivesEveryProgramAboutToBeLinked() {
        final List<Integer> linked = new ArrayList<>();
        GlProgram.setLinkHook(linked::add);

        GlProgram.applyLinkHook(42);
        GlProgram.applyLinkHook(7);

        assertEquals(List.of(42, 7), linked);
    }

    @Test
    void aClearedHookStopsReceivingPrograms() {
        final List<Integer> linked = new ArrayList<>();
        GlProgram.setLinkHook(linked::add);
        GlProgram.applyLinkHook(1);

        GlProgram.setLinkHook(null);
        GlProgram.applyLinkHook(2);

        assertEquals(List.of(1), linked);
    }

    @Test
    void aReplacedHookTakesOverTheRemainingLinks() {
        final List<Integer> first = new ArrayList<>();
        final List<Integer> second = new ArrayList<>();
        GlProgram.setLinkHook(first::add);
        GlProgram.applyLinkHook(1);

        GlProgram.setLinkHook(second::add);
        GlProgram.applyLinkHook(2);

        assertEquals(List.of(1), first, "an uninstalled hook must not keep receiving programs");
        assertEquals(List.of(2), second);
    }
}
