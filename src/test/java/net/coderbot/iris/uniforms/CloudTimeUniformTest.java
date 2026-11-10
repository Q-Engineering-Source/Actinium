package net.coderbot.iris.uniforms;

import net.minecraft.client.multiplayer.WorldClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudTimeUniformTest {

    @AfterEach
    void restoreTheDefaultScrollPeriod() {
        IrisExclusiveUniforms.setCloudScrollPeriod(period(256, 1));
    }

    @Test
    void theCloudTimeStartsAtZeroAndAdvances() {
        assertEquals(0.0F, IrisExclusiveUniforms.cloudTime(0L, 0.0F), 0.0F, "the world time starts at zero");
        assertTrue(IrisExclusiveUniforms.cloudTime(1L, 0.0F) > IrisExclusiveUniforms.cloudTime(0L, 0.0F),
                "the time advances with the world time");
        assertTrue(IrisExclusiveUniforms.cloudTime(100L, 0.0F) > IrisExclusiveUniforms.cloudTime(99L, 0.0F),
                "the time keeps advancing further into the cycle");
        assertTrue(IrisExclusiveUniforms.cloudTime(100L, 0.5F) > IrisExclusiveUniforms.cloudTime(100L, 0.0F),
                "the partial tick advances the time within one tick");
    }

    @Test
    void theCloudTimeWrapsAfterOneFullCycle() {
        final long cycle = IrisExclusiveUniforms.cloudScrollCycle();
        assertTrue(cycle > 1L, "one cycle spans more than one tick");

        assertEquals(IrisExclusiveUniforms.cloudTime(0L, 0.0F), IrisExclusiveUniforms.cloudTime(cycle, 0.0F), 0.0F,
                "one full cycle returns to the start of the animation");
        assertEquals(IrisExclusiveUniforms.cloudTime(7L, 0.0F),
                IrisExclusiveUniforms.cloudTime(cycle + 7L, 0.0F), 0.0F,
                "the wrap keeps the offset inside the cycle");
        assertTrue(IrisExclusiveUniforms.cloudTime(cycle - 1L, 0.0F)
                        > IrisExclusiveUniforms.cloudTime(cycle, 0.0F),
                "the last tick before the wrap holds the largest time");
    }

    @Test
    void theCloudTimeFollowsTheRegisteredPeriod() {
        final long defaultCycle = IrisExclusiveUniforms.cloudScrollCycle();
        IrisExclusiveUniforms.setCloudScrollPeriod(period(3, 2));
        final long cycle = IrisExclusiveUniforms.cloudScrollCycle();

        assertNotEquals(defaultCycle, cycle, "the registered period replaces the default one");
        assertEquals(IrisExclusiveUniforms.cloudTime(0L, 0.0F), IrisExclusiveUniforms.cloudTime(cycle, 0.0F), 0.0F,
                "the registered period is where the animation repeats");
        assertTrue(IrisExclusiveUniforms.cloudTime(cycle - 1L, 0.0F)
                        > IrisExclusiveUniforms.cloudTime(cycle, 0.0F),
                "the cycle of the registered period is the one the wrap uses");
    }

    @Test
    void theCloudTimeIsZeroWithoutAWorld() {
        assertEquals(0.0F, IrisExclusiveUniforms.cloudTime((WorldClient) null, 0.5F), 0.0F);
    }

    @Test
    void aZeroWidthScrollPeriodIsRejectedAtRegistration() {
        assertThrows(IllegalArgumentException.class,
                () -> IrisExclusiveUniforms.setCloudScrollPeriod(period(0, 1)));
    }

    @Test
    void aNonPositiveScaleIsRejectedAtRegistration() {
        assertThrows(IllegalArgumentException.class,
                () -> IrisExclusiveUniforms.setCloudScrollPeriod(period(256, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> IrisExclusiveUniforms.setCloudScrollPeriod(period(256, -1)));
    }

    @Test
    void aNullScrollPeriodIsRejectedAtRegistration() {
        assertThrows(NullPointerException.class, () -> IrisExclusiveUniforms.setCloudScrollPeriod(null));
    }

    @Test
    void aRejectedScrollPeriodLeavesTheRegisteredOneInEffect() {
        IrisExclusiveUniforms.setCloudScrollPeriod(period(4, 1));
        final long cycle = IrisExclusiveUniforms.cloudScrollCycle();

        assertThrows(IllegalArgumentException.class,
                () -> IrisExclusiveUniforms.setCloudScrollPeriod(period(0, 1)));
        assertThrows(NullPointerException.class, () -> IrisExclusiveUniforms.setCloudScrollPeriod(null));

        assertEquals(0.0F, IrisExclusiveUniforms.cloudTime(cycle, 0.0F), 0.0F,
                "the registered period still decides where the time wraps");
        assertEquals(IrisExclusiveUniforms.cloudTime(cycle - 1L, 0.0F),
                IrisExclusiveUniforms.cloudTime(2L * cycle - 1L, 0.0F), 0.0F,
                "the rejected periods did not change the animation");
    }

    private static IrisExclusiveUniforms.CloudScrollPeriod period(int textureWidth, int scale) {
        return new IrisExclusiveUniforms.CloudScrollPeriod() {
            @Override
            public int textureWidth() {
                return textureWidth;
            }

            @Override
            public int scale() {
                return scale;
            }
        };
    }
}
