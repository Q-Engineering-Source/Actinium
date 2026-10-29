package com.gtnewhorizons.angelica.glsm.stacks;

import com.gtnewhorizons.angelica.glsm.context.GLContextIdentity;
import com.gtnewhorizons.angelica.glsm.states.BufferGenerationTracker;
import com.gtnewhorizons.angelica.glsm.states.ClientPixelStoreSnapshot;
import com.gtnewhorizons.angelica.glsm.states.PixelPackState;
import com.gtnewhorizons.angelica.glsm.states.PixelUnpackState;
import com.gtnewhorizons.angelica.glsm.states.VertexAttribState;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ClientAttribStackSnapshotTest {

    @Test
    void pixelStoreOnlyMaskDoesNotCaptureVertexArrays() {
        final VertexAttribState arrays = new VertexAttribState(99L, new BufferGenerationTracker(99L));
        arrays.initForContext(3);
        final VertexAttribState.Snapshot vertexSnapshot = arrays.snapshot(
            new GLContextIdentity(1L, 0xA11CEL, 99L), 3, 0, 0, 0);
        final ClientPixelStoreSnapshot pixels = new ClientPixelStoreSnapshot(
            PixelPackState.DEFAULT, PixelUnpackState.DEFAULT, 0, 0);
        final ClientAttribStack stack = new ClientAttribStack();

        stack.push(GL11.GL_CLIENT_PIXEL_STORE_BIT, pixels, null, 0);
        assertNull(stack.pop().vertexArrays());

        stack.push(GL11.GL_CLIENT_VERTEX_ARRAY_BIT, null, vertexSnapshot, 0);
        assertSame(vertexSnapshot, stack.pop().vertexArrays());
    }
}
