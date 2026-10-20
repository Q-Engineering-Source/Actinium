package com.gtnewhorizons.angelica.glsm.stacks;

import com.gtnewhorizons.angelica.glsm.states.ClientPixelStoreSnapshot;
import org.lwjgl.opengl.GL11;

/** Mask-aware snapshots for the pixel-store and existing legacy vertex client scopes. */
public final class ClientAttribStack {
    public static final int MAX_DEPTH = 16;
    private final Snapshot[] stack = new Snapshot[MAX_DEPTH];
    private int depth;

    public void push(int mask, ClientPixelStoreSnapshot pixels, int textureUnit, int vertexFlags) {
        if (depth == stack.length) throw new IllegalStateException("Client attribute stack overflow");
        final boolean pixelStore = (mask & GL11.GL_CLIENT_PIXEL_STORE_BIT) != 0;
        final boolean vertexArrays = (mask & GL11.GL_CLIENT_VERTEX_ARRAY_BIT) != 0;
        stack[depth++] = new Snapshot(pixelStore ? pixels : null, vertexArrays, vertexArrays ? textureUnit : 0, vertexArrays ? vertexFlags : 0);
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

    /** Only masked categories are retained, so pixel-only scopes cannot restore vertex state. */
    public record Snapshot(ClientPixelStoreSnapshot pixels, boolean vertexArrays, int textureUnit, int vertexFlags) {}
}
