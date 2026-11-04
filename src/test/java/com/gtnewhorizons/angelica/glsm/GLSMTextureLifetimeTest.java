package com.gtnewhorizons.angelica.glsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the deferred texture deletion state machine without a GL context: a deleted name starts out released after the
 * configured delay, a name still attached to a framebuffer slot waits for the last slot to drop it, and a reclaimed
 * name leaves the machine entirely. The release action is injected so the transitions can be observed without a driver.
 */
class GLSMTextureLifetimeTest {

    /** Distinct texture names, one per scenario, so the shared static maps never collide across tests. */
    private static final int RELEASED = 101;
    private static final int ATTACHED = 102;
    private static final int RESCUED = 103;
    private static final int DETACHED = 104;
    private static final int NON_2D = 105;
    private static final int SLOT = 0;

    private final List<Integer> released = new ArrayList<>();

    @BeforeEach
    void resetStateMachine() {
        GLSMTextureLifetime.resetForTesting();
        released.clear();
    }

    private void framesPass(int frames) {
        for (int i = 0; i < frames; i++) {
            GLSMTextureLifetime.advanceFrameForTesting();
            GLSMTextureLifetime.releaseDueTextures(released::add);
        }
    }

    private static int releaseDelay() {
        return GLSMTextureLifetime.RELEASE_DELAY_FRAMES;
    }

    @Test
    void releasedNameKeepsItsStorageUntilTheDelayElapses() {
        GLSMTextureLifetime.markDeferred(RELEASED);
        assertTrue(GLSMTextureLifetime.isDeferredDelete(RELEASED));
        assertEquals(1, GLSMTextureLifetime.deferredTexturesAwaitingRelease());

        framesPass(releaseDelay() - 1);

        assertTrue(GLSMTextureLifetime.isDeferredDelete(RELEASED), "the name stays deferred inside the delay window");
        assertEquals(List.of(), released, "storage is kept for the whole delay");

        framesPass(1);

        assertEquals(List.of(RELEASED), released, "the storage is released once the delay elapses");
        assertTrue(GLSMTextureLifetime.isDeferredDelete(RELEASED), "a released name is still deferred until flushed");
    }

    @Test
    void repeatedFramesAfterReleaseDoNotReleaseAgain() {
        GLSMTextureLifetime.markDeferred(RELEASED);
        framesPass(releaseDelay());

        framesPass(1);

        assertEquals(List.of(RELEASED), released, "a name is released exactly once");
    }

    @Test
    void attachedNameIsReleasedOnlyAfterTheLastSlotDropsIt() {
        final int[] slots = new int[GLSMTextureLifetime.FRAMEBUFFER_SLOTS];
        GLSMTextureLifetime.setFramebufferSlot(slots, SLOT, ATTACHED);
        GLSMTextureLifetime.markDeferred(ATTACHED);

        framesPass(releaseDelay() * 2);

        assertEquals(List.of(), released, "an attached name keeps its storage however long it is deferred");
        assertTrue(GLSMTextureLifetime.isDeferredDelete(ATTACHED));

        GLSMTextureLifetime.setFramebufferSlot(slots, SLOT, 0);
        framesPass(releaseDelay() - 1);

        assertEquals(List.of(), released, "detaching starts the delay, it does not release immediately");

        framesPass(1);

        assertEquals(List.of(ATTACHED), released, "the delay counts from the detach");
    }

    @Test
    void nameDetachedBeforeAnyFrameReleasesOnSchedule() {
        final int[] slots = new int[GLSMTextureLifetime.FRAMEBUFFER_SLOTS];
        GLSMTextureLifetime.setFramebufferSlot(slots, SLOT, DETACHED);
        GLSMTextureLifetime.markDeferred(DETACHED);
        GLSMTextureLifetime.setFramebufferSlot(slots, SLOT, 0);

        framesPass(releaseDelay());

        assertEquals(List.of(DETACHED), released);
    }

    @Test
    void claimForBindRescuesANameAndStopsTheRelease() {
        GLSMTextureLifetime.markDeferred(RESCUED);

        assertNotEquals(GLSMTextureLifetime.DEFERRED_ABSENT, GLSMTextureLifetime.claimForBind(RESCUED, GL11.GL_TEXTURE_2D),
            "a deferred name reports the state it was reclaimed from");

        assertFalse(GLSMTextureLifetime.isDeferredDelete(RESCUED), "a reclaimed name leaves the state machine");
        framesPass(releaseDelay() * 2);

        assertEquals(List.of(), released, "a reclaimed name is never released");
        assertEquals(0, GLSMTextureLifetime.deferredTexturesAwaitingRelease());
    }

    @Test
    void claimForAnUnknownNameLeavesItUntracked() {
        assertEquals(GLSMTextureLifetime.DEFERRED_ABSENT, GLSMTextureLifetime.claimForBind(RESCUED, GL11.GL_TEXTURE_2D));

        assertFalse(GLSMTextureLifetime.isDeferredDelete(RESCUED));
    }

    @Test
    void claimWithAMismatchedTargetRejectsTheBindAndKeepsTheNameDeferred() {
        GLSMTextureLifetime.markDeferred(NON_2D);

        assertEquals(GLSMTextureLifetime.BIND_REJECTED, GLSMTextureLifetime.claimForBind(NON_2D, GL12.GL_TEXTURE_3D));

        assertTrue(GLSMTextureLifetime.isDeferredDelete(NON_2D), "a rejected bind must not reclaim the name");
    }

    @Test
    void non2DNameIsDeferredWithoutA2DStorageRelease() {
        GLSMTextureLifetime.recordTarget(NON_2D, GL12.GL_TEXTURE_3D);
        GLSMTextureLifetime.markDeferred(NON_2D);

        framesPass(releaseDelay());

        assertEquals(List.of(), released, "a non-2D name has no 2D storage to shrink");
        assertTrue(GLSMTextureLifetime.isDeferredDelete(NON_2D), "it still enters the released state");
        assertEquals(0, GLSMTextureLifetime.deferredTexturesAwaitingRelease());
    }

    @Test
    void flushDropsEveryReleasedName() {
        GLSMTextureLifetime.markDeferred(RELEASED);
        framesPass(releaseDelay());

        assertEquals(1, GLSMTextureLifetime.flushReleasedForTesting());

        assertFalse(GLSMTextureLifetime.isDeferredDelete(RELEASED), "a flushed name is handed back to the driver");
    }

    @Test
    void flushKeepsAnAttachedName() {
        final int[] slots = new int[GLSMTextureLifetime.FRAMEBUFFER_SLOTS];
        GLSMTextureLifetime.setFramebufferSlot(slots, SLOT, ATTACHED);
        GLSMTextureLifetime.markDeferred(ATTACHED);
        framesPass(releaseDelay() * 2);

        assertEquals(0, GLSMTextureLifetime.flushReleasedForTesting());

        assertTrue(GLSMTextureLifetime.isDeferredDelete(ATTACHED), "an attached name outlives a flush");
    }
}
