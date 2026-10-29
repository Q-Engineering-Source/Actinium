package com.gtnewhorizons.angelica.glsm.stacks;

import com.gtnewhorizons.angelica.glsm.states.ClientPixelStoreSnapshot;
import com.gtnewhorizons.angelica.glsm.states.VertexAttribState;
import org.lwjgl.opengl.GL11;

/** Mask-aware snapshots for the pixel-store and existing legacy vertex client scopes. */
public final class ClientAttribStack {
    public static final int MAX_DEPTH = 16;
    private final Snapshot[] stack = new Snapshot[MAX_DEPTH];
    private int depth;

    public void push(int mask, ClientPixelStoreSnapshot pixels, VertexAttribState.Snapshot vertexArrays, int vertexFlags) {
        if (depth == stack.length) throw new IllegalStateException("Client attribute stack overflow");
        final boolean pixelStore = (mask & GL11.GL_CLIENT_PIXEL_STORE_BIT) != 0;
        final boolean captureArrays = (mask & GL11.GL_CLIENT_VERTEX_ARRAY_BIT) != 0;
        if (captureArrays && vertexArrays == null) {
            throw new IllegalArgumentException("Vertex-array client scopes require a complete vertex-array snapshot");
        }
        stack[depth++] = new Snapshot(
            pixelStore ? pixels : null,
            captureArrays ? vertexArrays : null,
            captureArrays ? vertexFlags : 0
        );
    }

    public Snapshot pop() {
        if (depth == 0) throw new IllegalStateException("Client attribute stack underflow");
        final Snapshot snapshot = stack[--depth];
        stack[depth] = null;
        return snapshot;
    }

    public int depth() {
        return depth;
    }

    /** Clears every snapshot when the native context that owned the stack is retired. */
    public void clear() {
        while (depth > 0) {
            stack[--depth] = null;
        }
    }

    /** Only masked categories are retained, so pixel-only scopes cannot restore vertex state. */
    public record Snapshot(ClientPixelStoreSnapshot pixels, VertexAttribState.Snapshot vertexArrays, int vertexFlags) {}
}
