package com.gtnewhorizons.angelica.glsm.hooks;

public interface DeferredAlphaHandler {
    boolean isAlphaTestLocked();
    boolean isOverrideHeld();
    void deferAlphaTestToggle(boolean enabled);
    void deferAlphaFunc(int function, float reference);
}
