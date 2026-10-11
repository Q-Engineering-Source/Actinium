package net.coderbot.iris.gl.blending;

import com.gtnewhorizons.angelica.glsm.states.BlendState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deferred vanilla blend state must only be recorded when it actually moved: a redundant
 * {@code deferBlendFunc}/{@code deferBlendModeToggle} would otherwise force the override to be
 * flushed and re-applied on every vanilla blend call.
 */
class BlendModeStorageDeferDedupeTest {

    private static final int VANILLA_SRC_RGB = GL11.GL_ONE;
    private static final int VANILLA_DST_RGB = GL11.GL_ZERO;
    private static final int VANILLA_SRC_ALPHA = GL11.GL_ONE;
    private static final int VANILLA_DST_ALPHA = GL11.GL_ZERO;

    private RecordingAccess access;

    private static final class RecordingAccess implements BlendModeStorage.BlendStateAccess {
        private final BlendState blend = new BlendState();
        private boolean enabled;
        private int enableApplies;
        private int funcApplies;

        @Override
        public boolean isBlendEnabled() {
            return enabled;
        }

        @Override
        public void copyBlendState(BlendState destination) {
            destination.set(blend);
        }

        @Override
        public void setBlendEnabled(boolean enabled) {
            this.enabled = enabled;
            enableApplies++;
        }

        @Override
        public void setBlendFunction(BlendState state) {
            blend.set(state);
            funcApplies++;
        }

        @Override
        public void setBufferBlendEnabled(int index, boolean enabled) {}

        @Override
        public void setBufferBlendFunction(int index, BlendState state) {}
    }

    @BeforeEach
    void installRecordingAccess() {
        access = new RecordingAccess();
        access.blend.setAll(VANILLA_SRC_RGB, VANILLA_DST_RGB, VANILLA_SRC_ALPHA, VANILLA_DST_ALPHA);
        BlendModeStorage.setBlendStateAccessForTesting(access);
    }

    @AfterEach
    void restoreAccess() {
        BlendModeStorage.restoreDefaultBlendStateAccessForTesting();
    }

    @Test
    void blendFuncDefersOnlyOnChange() {
        BlendModeStorage.deferBlendFunc(VANILLA_SRC_RGB, VANILLA_DST_RGB, VANILLA_SRC_ALPHA, VANILLA_DST_ALPHA);
        BlendModeStorage.flushDeferredBlend();
        assertEquals(0, access.funcApplies, "a redundant func must not schedule an apply");

        BlendModeStorage.deferBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        BlendModeStorage.flushDeferredBlend();
        assertEquals(1, access.funcApplies, "a changed func must be applied exactly once");
        assertEquals(GL11.GL_SRC_ALPHA, access.blend.getSrcRgb());
        assertEquals(GL11.GL_ONE_MINUS_SRC_ALPHA, access.blend.getDstRgb());

        BlendModeStorage.deferBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
        BlendModeStorage.flushDeferredBlend();
        assertEquals(1, access.funcApplies, "re-deferring the same func must not apply again");
    }

    @Test
    void toggleDefersOnlyOnChange() {
        BlendModeStorage.deferBlendModeToggle(false);
        BlendModeStorage.flushDeferredBlend();
        assertEquals(0, access.enableApplies, "a redundant toggle must not schedule an apply");
        assertFalse(access.enabled);

        BlendModeStorage.deferBlendModeToggle(true);
        BlendModeStorage.flushDeferredBlend();
        assertTrue(access.enabled, "a changed toggle must be applied");
        assertEquals(1, access.enableApplies);
    }
}
