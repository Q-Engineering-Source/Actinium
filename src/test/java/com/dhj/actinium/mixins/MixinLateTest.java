package com.dhj.actinium.mixins;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MixinLateTest {
    @Test
    void selectsExactConfigsForLoadedMods() {
        assertEquals(Set.of(), Set.copyOf(MixinLate.configsFor(modId -> false, className -> false)));

        // The lumenized config is gated on the embedded bloom class, not on a mod id:
        // it loads when the class is present even with no matching mod (GTCEu provides the
        // same class without a "lumenized" mod id), and stays off when only the mod id matches
        // but the class is absent.
        assertEquals(
            Set.of("mixins.actinium.lumenized.json"),
            Set.copyOf(MixinLate.configsFor(modId -> false, "gregtech.client.utils.BloomEffectUtil"::equals))
        );

        assertEquals(
            Set.of(),
            Set.copyOf(MixinLate.configsFor("lumenized"::equals, className -> false))
        );

        assertEquals(
            Set.of(),
            Set.copyOf(MixinLate.configsFor("betterfoliage"::equals, className -> false))
        );

        assertEquals(
            Set.of("mixins.actinium.betterfoliage.json"),
            Set.copyOf(MixinLate.configsFor(
                "betterfoliage"::equals,
                "mods.betterfoliage.client.Hooks"::equals
            ))
        );

        assertEquals(
            Set.of("mixins.actinium.rlfoliage.json"),
            Set.copyOf(MixinLate.configsFor(
                "betterfoliage"::equals,
                "betterfoliage.render.feature.RenderingHandler"::equals
            ))
        );

        assertEquals(
            Set.of("mixins.actinium.ccl.json"),
            Set.copyOf(MixinLate.configsFor("codechickenlib"::equals, className -> false))
        );

        assertEquals(
            Set.of("mixins.actinium.cofhcore.json"),
            Set.copyOf(MixinLate.configsFor("cofhcore"::equals, className -> false))
        );

        assertEquals(
            Set.of("mixins.actinium.gvclib.json"),
            Set.copyOf(MixinLate.configsFor("gvclib"::equals, className -> false))
        );

        assertEquals(
            Set.of(
                "mixins.actinium.gibbed.json",
                "mixins.actinium.ichunutil.json",
                "mixins.actinium.lumenized.json",
                "mixins.actinium.revoui.json",
                "mixins.actinium.betterfoliage.json",
                "mixins.actinium.rlfoliage.json",
                "mixins.actinium.ccl.json",
                "mixins.actinium.voxelmap.json",
                "mixins.actinium.extrautils2.json",
                "mixins.actinium.cofhcore.json",
                "mixins.actinium.oldresearch.json",
                "mixins.actinium.botania.json",
                "mixins.actinium.hbm.json",
                "mixins.actinium.gvclib.json",
                "mixins.actinium.storagedrawers.json",
                "mixins.actinium.scannable.json",
                "mixins.actinium.littletiles.json",
                "mixins.actinium.architecturecraft.json",
                "mixins.actinium.obscuretooltips.json"
            ),
            Set.copyOf(MixinLate.configsFor(modId -> true, className -> true))
        );
    }
}
