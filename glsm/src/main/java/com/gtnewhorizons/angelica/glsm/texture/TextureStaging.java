package com.gtnewhorizons.angelica.glsm.texture;

import java.nio.ByteBuffer;

/** CPU-side pixel storage prepared for an upload into a texture region. */
public interface TextureStaging {

    /** Returns the writable pixel buffer for worker-side staging. */
    ByteBuffer buffer();

    /** Returns whether staged pixels use BGRA component ordering. */
    boolean bgra();

    /** Returns the mip level targeted by this staging buffer. */
    int level();
}
