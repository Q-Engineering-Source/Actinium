package com.gtnewhorizons.angelica.glsm.states;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL21;

/** Complete client pixel-transfer state, including both PBO bindings. */
public record ClientPixelStoreSnapshot(PixelPackState pack, PixelUnpackState unpack, int packBuffer, int unpackBuffer) {
    public static ClientPixelStoreSnapshot capture(PixelStoreAccess access) {
        return new ClientPixelStoreSnapshot(
            new PixelPackState(access.getInteger(GL11.GL_PACK_ALIGNMENT), access.getInteger(GL11.GL_PACK_ROW_LENGTH),
                access.getInteger(GL11.GL_PACK_SKIP_ROWS), access.getInteger(GL11.GL_PACK_SKIP_PIXELS),
                access.getInteger(GL12.GL_PACK_IMAGE_HEIGHT), access.getInteger(GL12.GL_PACK_SKIP_IMAGES),
                access.getInteger(GL11.GL_PACK_SWAP_BYTES) != 0, access.getInteger(GL11.GL_PACK_LSB_FIRST) != 0),
            new PixelUnpackState(access.getInteger(GL11.GL_UNPACK_ALIGNMENT), access.getInteger(GL11.GL_UNPACK_ROW_LENGTH),
                access.getInteger(GL11.GL_UNPACK_SKIP_ROWS), access.getInteger(GL11.GL_UNPACK_SKIP_PIXELS),
                access.getInteger(GL12.GL_UNPACK_IMAGE_HEIGHT), access.getInteger(GL12.GL_UNPACK_SKIP_IMAGES),
                access.getInteger(GL11.GL_UNPACK_SWAP_BYTES) != 0, access.getInteger(GL11.GL_UNPACK_LSB_FIRST) != 0),
            access.getInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING), access.getInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING));
    }

    /** Replays only changed parameters and bindings, preserving the other client state. */
    public void restore(ClientPixelStoreSnapshot current, PixelStoreAccess access) {
        restore(access, GL11.GL_PACK_ALIGNMENT, current.pack.alignment(), pack.alignment());
        restore(access, GL11.GL_PACK_ROW_LENGTH, current.pack.rowLength(), pack.rowLength());
        restore(access, GL11.GL_PACK_SKIP_ROWS, current.pack.skipRows(), pack.skipRows());
        restore(access, GL11.GL_PACK_SKIP_PIXELS, current.pack.skipPixels(), pack.skipPixels());
        restore(access, GL12.GL_PACK_IMAGE_HEIGHT, current.pack.imageHeight(), pack.imageHeight());
        restore(access, GL12.GL_PACK_SKIP_IMAGES, current.pack.skipImages(), pack.skipImages());
        restore(access, GL11.GL_PACK_SWAP_BYTES, current.pack.swapBytes() ? 1 : 0, pack.swapBytes() ? 1 : 0);
        restore(access, GL11.GL_PACK_LSB_FIRST, current.pack.lsbFirst() ? 1 : 0, pack.lsbFirst() ? 1 : 0);
        restore(access, GL11.GL_UNPACK_ALIGNMENT, current.unpack.alignment(), unpack.alignment());
        restore(access, GL11.GL_UNPACK_ROW_LENGTH, current.unpack.rowLength(), unpack.rowLength());
        restore(access, GL11.GL_UNPACK_SKIP_ROWS, current.unpack.skipRows(), unpack.skipRows());
        restore(access, GL11.GL_UNPACK_SKIP_PIXELS, current.unpack.skipPixels(), unpack.skipPixels());
        restore(access, GL12.GL_UNPACK_IMAGE_HEIGHT, current.unpack.imageHeight(), unpack.imageHeight());
        restore(access, GL12.GL_UNPACK_SKIP_IMAGES, current.unpack.skipImages(), unpack.skipImages());
        restore(access, GL11.GL_UNPACK_SWAP_BYTES, current.unpack.swapBytes() ? 1 : 0, unpack.swapBytes() ? 1 : 0);
        restore(access, GL11.GL_UNPACK_LSB_FIRST, current.unpack.lsbFirst() ? 1 : 0, unpack.lsbFirst() ? 1 : 0);
        if (packBuffer != current.packBuffer) access.bindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
        if (unpackBuffer != current.unpackBuffer) access.bindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
    }

    private static void restore(PixelStoreAccess access, int pname, int previous, int value) {
        if (previous != value) access.pixelStorei(pname, value);
    }
}
