package com.gtnewhorizons.angelica.sdlgpu;

import net.minecraft.launchwrapper.Launch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class SDLGPUWindowHandoverTest {

    private static final long WINDOW = 0x0000_7FF6_1234_5678L;

    @AfterEach
    void clearHandoverEntry() {
        Launch.blackboard.remove(SDLGPUWindowHandover.WINDOW_KEY);
    }

    @Test
    void readsTheWindowTheBridgePublished() {
        Launch.blackboard.put(SDLGPUWindowHandover.WINDOW_KEY, WINDOW);

        assertEquals(WINDOW, SDLGPUWindowHandover.readWindow());
    }

    @Test
    void refusesToStartWithoutAWindow() {
        final IllegalStateException failure =
            assertThrows(IllegalStateException.class, SDLGPUWindowHandover::readWindow);

        assertTrue(failure.getMessage().contains(SDLGPUWindowHandover.WINDOW_KEY), failure.getMessage());
        assertTrue(failure.getMessage().contains(SDLGPUWindowHandover.BRIDGE_CLASS), failure.getMessage());
    }

    @Test
    void rejectsAnEntryThatIsNotAHandle() {
        Launch.blackboard.put(SDLGPUWindowHandover.WINDOW_KEY, Long.toHexString(WINDOW));

        final IllegalStateException failure =
            assertThrows(IllegalStateException.class, SDLGPUWindowHandover::readWindow);

        assertTrue(failure.getMessage().contains(String.class.getName()), failure.getMessage());
    }

    @Test
    void rejectsANullHandle() {
        Launch.blackboard.put(SDLGPUWindowHandover.WINDOW_KEY, 0L);

        assertThrows(IllegalStateException.class, SDLGPUWindowHandover::readWindow);
    }

    @Test
    void seesTheBridgeOnTheClasspath() {
        assertTrue(SDLGPUWindowHandover.isBridgePresent(SDLGPUWindowHandoverTest.class.getClassLoader()));
    }

    @Test
    void reportsAMissingBridge() {
        // A loader with no parent can only reach the bootstrap classpath, so the bridge plugin class
        // is definitively not visible through it.
        assertFalse(SDLGPUWindowHandover.isBridgePresent(new ClassLoader(null) {}));
    }
}
