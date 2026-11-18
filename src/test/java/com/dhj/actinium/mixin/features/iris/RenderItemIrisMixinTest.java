package com.dhj.actinium.mixin.features.iris;

import com.dhj.actinium.runtime.ActiniumRuntime;
import com.gtnewhorizons.angelica.config.AngelicaConfig;
import com.gtnewhorizons.angelica.rendering.items.DroppedItemInstancer;
import net.coderbot.iris.gbuffer_overrides.matching.SpecialCondition;
import net.coderbot.iris.layer.GbufferPrograms;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RenderItemIrisMixinTest {

    private boolean previousGlintWorld;

    @BeforeEach
    void openDroppedItemScope() {
        clearSpecialConditions();
        previousGlintWorld = AngelicaConfig.glintWorld();
        ActiniumRuntime.options().quality.glintWorld = false;
        DroppedItemInstancer.enterScope();
    }

    @AfterEach
    void restoreOptionsAndState() {
        DroppedItemInstancer.exitScope();
        ActiniumRuntime.options().quality.glintWorld = previousGlintWorld;
        clearSpecialConditions();
    }

    @Test
    void cancelledWorldGlintDoesNotLeakSpecialCondition() {
        final RenderItemIrisMixin mixin = new RenderItemIrisMixin();
        mixin.actinium$beginItemGlint(null, new CallbackInfo("renderEffect", true));

        assertNull(GbufferPrograms.getSpecialCondition(),
            "the cancelled renderEffect must not select the glint program for later draws");
    }

    @Test
    void enabledGlintScopeRestoresThePreviousCondition() {
        ActiniumRuntime.options().quality.glintWorld = true;
        final RenderItemIrisMixin mixin = new RenderItemIrisMixin();
        final CallbackInfo callback = new CallbackInfo("renderEffect", true);

        mixin.actinium$beginItemGlint(null, callback);
        assertEquals(SpecialCondition.GLINT, GbufferPrograms.getSpecialCondition());
        mixin.actinium$endItemGlint(null, callback);
        assertNull(GbufferPrograms.getSpecialCondition());
    }

    private static void clearSpecialConditions() {
        while (GbufferPrograms.getSpecialCondition() != null) {
            GbufferPrograms.teardownSpecialRenderCondition();
        }
    }
}
