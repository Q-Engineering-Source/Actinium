package com.gtnewhorizons.angelica.glsm.states;

/** Pixel transfer operations needed to capture and restore client pixel-store scopes. */
public interface PixelStoreAccess {
    /** Reads a pixel-store parameter or pixel-buffer binding from the active context. */
    int getInteger(int pname);

    /** Restores a pack or unpack parameter without changing unrelated client state. */
    void pixelStorei(int pname, int value);

    /** Restores the pack or unpack buffer used by subsequent pixel transfers. */
    void bindBuffer(int target, int buffer);
}
