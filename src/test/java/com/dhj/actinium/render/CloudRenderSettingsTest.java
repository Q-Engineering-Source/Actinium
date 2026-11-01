package com.dhj.actinium.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CloudRenderSettingsTest {
    // Below the sky render distance floor vanilla hides clouds, so Actinium answers the cloud queries on
    // that path itself. The answer must be the option the player selected; mapping the off setting to
    // fancy clouds makes the cloud option impossible to disable.

    @Test
    void keepsCloudsOff() {
        assertEquals(0, CloudRenderSettings.effectiveCloudMode(0));
    }

    @Test
    void keepsFastClouds() {
        assertEquals(1, CloudRenderSettings.effectiveCloudMode(1));
    }

    @Test
    void keepsFancyClouds() {
        assertEquals(2, CloudRenderSettings.effectiveCloudMode(2));
    }
}
