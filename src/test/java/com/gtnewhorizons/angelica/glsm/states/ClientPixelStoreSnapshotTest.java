package com.gtnewhorizons.angelica.glsm.states;

import com.gtnewhorizons.angelica.glsm.stacks.ClientAttribStack;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL21;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPixelStoreSnapshotTest {
    private static final ClientPixelStoreSnapshot INITIAL = new ClientPixelStoreSnapshot(
        new PixelPackState(1, 7, 2, 3, 4, 5, true, true),
        new PixelUnpackState(2, 8, 3, 4, 5, 6, true, true), 11, 12);
    private static final ClientPixelStoreSnapshot CHANGED = new ClientPixelStoreSnapshot(
        new PixelPackState(8, 17, 12, 13, 14, 15, false, false),
        new PixelUnpackState(4, 18, 13, 14, 15, 16, false, false), 21, 22);
    private static final ClientPixelStoreSnapshot DEFAULT = new ClientPixelStoreSnapshot(
        PixelPackState.DEFAULT, PixelUnpackState.DEFAULT, 0, 0);

    @Test
    void restoresAllPackUnpackParametersAndBothPboBindings() {
        final MemoryPixelStoreAccess access = new MemoryPixelStoreAccess();
        CHANGED.restore(DEFAULT, access);
        access.writes = 0;

        INITIAL.restore(ClientPixelStoreSnapshot.capture(access), access);

        assertEquals(INITIAL, ClientPixelStoreSnapshot.capture(access));
        assertEquals(18, access.writes, "all sixteen parameters and both PBOs changed");
    }

    @Test
    void unchangedScopeDoesNotIssueDriverWrites() {
        final MemoryPixelStoreAccess access = new MemoryPixelStoreAccess();
        INITIAL.restore(DEFAULT, access);
        access.writes = 0;
        INITIAL.restore(ClientPixelStoreSnapshot.capture(access), access);
        assertEquals(0, access.writes);
    }

    @Test
    void nestedMasksKeepPixelAndVertexScopesIndependent() {
        final MemoryPixelStoreAccess access = new MemoryPixelStoreAccess();
        INITIAL.restore(DEFAULT, access);
        final ClientAttribStack scopes = new ClientAttribStack();
        scopes.push(GL11.GL_CLIENT_PIXEL_STORE_BIT, ClientPixelStoreSnapshot.capture(access), 1, 7);
        CHANGED.restore(INITIAL, access);
        scopes.push(GL11.GL_CLIENT_VERTEX_ARRAY_BIT, ClientPixelStoreSnapshot.capture(access), 3, 9);
        DEFAULT.restore(CHANGED, access);

        final ClientAttribStack.Snapshot vertexScope = scopes.pop();
        assertTrue(vertexScope.vertexArrays());
        assertEquals(3, vertexScope.textureUnit());
        assertEquals(9, vertexScope.vertexFlags());
        assertNull(vertexScope.pixels());
        assertEquals(DEFAULT, ClientPixelStoreSnapshot.capture(access), "vertex-only pop must leave pixel changes visible");

        final ClientAttribStack.Snapshot pixelScope = scopes.pop();
        assertFalse(pixelScope.vertexArrays());
        pixelScope.pixels().restore(ClientPixelStoreSnapshot.capture(access), access);
        assertEquals(INITIAL, ClientPixelStoreSnapshot.capture(access));
        assertEquals(0, scopes.depth());
    }

    @Test
    void nestedPixelScopesAndUnboundPbosRestoreInOrder() {
        final MemoryPixelStoreAccess access = new MemoryPixelStoreAccess();
        final ClientAttribStack scopes = new ClientAttribStack();
        scopes.push(GL11.GL_CLIENT_ALL_ATTRIB_BITS, DEFAULT, 2, 5);
        INITIAL.restore(DEFAULT, access);
        scopes.push(GL11.GL_CLIENT_PIXEL_STORE_BIT, INITIAL, 0, 0);
        CHANGED.restore(INITIAL, access);

        scopes.pop().pixels().restore(ClientPixelStoreSnapshot.capture(access), access);
        assertEquals(INITIAL, ClientPixelStoreSnapshot.capture(access));
        final ClientAttribStack.Snapshot outer = scopes.pop();
        assertTrue(outer.vertexArrays());
        assertEquals(2, outer.textureUnit());
        assertEquals(5, outer.vertexFlags());
        outer.pixels().restore(ClientPixelStoreSnapshot.capture(access), access);
        assertEquals(DEFAULT, ClientPixelStoreSnapshot.capture(access));
    }

    /** In-memory pixel-transfer context exercising the production capture/restore implementation. */
    private static final class MemoryPixelStoreAccess implements PixelStoreAccess {
        private final Map<Integer, Integer> parameters = new HashMap<>();
        private int writes;

        private MemoryPixelStoreAccess() {
            parameters.put(GL11.GL_PACK_ALIGNMENT, 4);
            parameters.put(GL11.GL_UNPACK_ALIGNMENT, 4);
        }

        @Override
        public int getInteger(int pname) {
            return parameters.getOrDefault(pname, 0);
        }

        @Override
        public void pixelStorei(int pname, int value) {
            parameters.put(pname, value);
            writes++;
        }

        @Override
        public void bindBuffer(int target, int buffer) {
            final int binding = switch (target) {
                case GL21.GL_PIXEL_PACK_BUFFER -> GL21.GL_PIXEL_PACK_BUFFER_BINDING;
                case GL21.GL_PIXEL_UNPACK_BUFFER -> GL21.GL_PIXEL_UNPACK_BUFFER_BINDING;
                default -> throw new IllegalArgumentException("Unexpected buffer target: " + target);
            };
            parameters.put(binding, buffer);
            writes++;
        }
    }
}
