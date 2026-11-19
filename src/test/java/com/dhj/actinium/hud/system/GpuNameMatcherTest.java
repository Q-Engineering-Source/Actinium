package com.dhj.actinium.hud.system;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GpuNameMatcherTest {
    @Test
    void aSingleCardIsChosenWithoutLookingAtTheName() {
        OptionalInt index = GpuNameMatcher.match("Mesa Intel(R) UHD Graphics 630 (CFL GT2)",
            List.of("NVIDIA GeForce RTX 4090"));

        assertEquals(OptionalInt.of(0), index);
    }

    @Test
    void aCardNameInsideTheDriverRendererStringSelectsTheCard() {
        String renderer = "ANGLE (NVIDIA, NVIDIA GeForce RTX 4070 Ti Direct3D11 vs_5_0 ps_5_0, D3D11)";
        List<String> cards = List.of("Intel(R) UHD Graphics 770", "NVIDIA GeForce RTX 4070 Ti");

        assertEquals(OptionalInt.of(1), GpuNameMatcher.match(renderer, cards));
    }

    @Test
    void aRendererStringInsideTheCardNameSelectsTheCard() {
        String renderer = "amd radeon rx 7900 xtx";
        List<String> cards = List.of("Intel(R) UHD Graphics 770", "AMD Radeon RX 7900 XTX (RADV NAVI31)");

        assertEquals(OptionalInt.of(1), GpuNameMatcher.match(renderer, cards));
    }

    @Test
    void aRendererWithoutAMatchingCardLeavesTheChoiceOpen() {
        List<String> cards = List.of("NVIDIA GeForce RTX 4090", "AMD Radeon RX 7900 XTX");

        assertTrue(GpuNameMatcher.match("Mesa Intel(R) UHD Graphics 630 (CFL GT2)", cards).isEmpty());
    }

    @Test
    void twoMatchingCardsAreAmbiguous() {
        List<String> cards = List.of("NVIDIA GeForce RTX 4070", "NVIDIA GeForce RTX 4070 Ti");

        assertTrue(GpuNameMatcher.match("NVIDIA GeForce RTX 4070", cards).isEmpty());
    }

    @Test
    void namesWithoutLettersOrDigitsNeverMatch() {
        List<String> cards = List.of("  ", "---", "NVIDIA GeForce RTX 4070");

        assertEquals(OptionalInt.of(2), GpuNameMatcher.match("NVIDIA GeForce RTX 4070", cards));
        assertTrue(GpuNameMatcher.match("---", cards).isEmpty());
        assertTrue(GpuNameMatcher.match("--- (0x0000744C)", cards).isEmpty());
    }

    @Test
    void noEnumeratedCardYieldsNoIndex() {
        assertTrue(GpuNameMatcher.match("NVIDIA GeForce RTX 4070", List.of()).isEmpty());
    }
}
