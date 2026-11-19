package com.dhj.actinium.hud.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class UnavailableSystemMetricsSourceImplTest {
    @Test
    void duplicateStartIsRejectedUntilStop() {
        UnavailableSystemMetricsSourceImpl source = new UnavailableSystemMetricsSourceImpl();
        source.start("renderer", 500);

        assertThrows(IllegalStateException.class, () -> source.start("renderer", 500));

        source.stop();
        source.start("renderer", 500);
        source.stop();
    }

    @Test
    void refreshIntervalChangesWithoutRestartingTheSource() {
        UnavailableSystemMetricsSourceImpl source = new UnavailableSystemMetricsSourceImpl();
        source.start("renderer", 500);

        source.setRefreshIntervalMillis(250);

        source.stop();
        assertThrows(IllegalStateException.class, () -> source.setRefreshIntervalMillis(100));
    }

    @Test
    void closedSourceCannotStartAgain() {
        UnavailableSystemMetricsSourceImpl source = new UnavailableSystemMetricsSourceImpl();
        source.start("renderer", 500);
        source.close();
        source.close();

        assertThrows(IllegalStateException.class, () -> source.start("renderer", 500));
    }
}
