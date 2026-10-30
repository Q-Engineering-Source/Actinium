package com.gtnewhorizons.angelica.glsm.context;

/** Exposes the native handle of a transformed LWJGL context. */
public interface NativeContextHandle {
    /** Returns the native window or SDL context handle owned by this context. */
    long actinium$nativeHandle();
}
