package com.gtnewhorizons.angelica.sdlgpu;

import com.gtnewhorizons.angelica.sdlgpu.device.SDLDrawable;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.SharedDrawable;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SDLGPUDisplayBridgeTest {
    @Test
    void sharedDrawableRetainsAnIndependentTokenAndTheSdlSource() throws Exception {
        SDLDrawable display = new SDLDrawable();
        SharedDrawable first = new SharedDrawable(display);
        SharedDrawable nested = new SharedDrawable(first);

        assertSame(display, first.getSharingDrawable());
        assertSame(first, nested.getSharingDrawable());
        assertNotSame(display.getContext(), first.getContext());
        assertNotSame(first.getContext(), nested.getContext());
        assertTrue(SDLGPUDisplayBridge.isSdlSharedDrawable(first));
        assertTrue(SDLGPUDisplayBridge.isSdlSharedDrawable(nested));
    }
}
