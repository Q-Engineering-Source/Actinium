package com.gtnewhorizons.angelica.sdlgpu.compat.lwjgly;

import com.cleanroommc.lwjgly.spi.WindowBridge;

/**
 * Test double for LWJGLY's window bridge.
 *
 * <p>{@code LwjglyWindowHost} consumes four operations of the bridge - the window handle, its size
 * and the resize notification - so those behave like a real host window here. Everything else belongs
 * to the GL and input paths, which the SDL backend does not take from the bridge, and is refused
 * loudly rather than answered with a value that would hide an unintended call.</p>
 */
final class TestWindowBridge implements WindowBridge {

    private final long handle;
    private int width;
    private int height;
    private boolean resized;

    TestWindowBridge(long handle, int width, int height) {
        this.handle = handle;
        this.width = width;
        this.height = height;
    }

    void resize(int newWidth, int newHeight) {
        this.width = newWidth;
        this.height = newHeight;
        this.resized = true;
    }

    @Override
    public long handle() {
        return handle;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public boolean consumeResized() {
        final boolean pending = resized;
        resized = false;
        return pending;
    }

    @Override
    public String title() {
        throw unused("title()");
    }

    @Override
    public void title(String title) {
        throw unused("title(String)");
    }

    @Override
    public boolean closeRequested() {
        throw unused("closeRequested()");
    }

    @Override
    public boolean focused() {
        throw unused("focused()");
    }

    @Override
    public boolean fullscreen() {
        throw unused("fullscreen()");
    }

    @Override
    public void fullscreen(boolean fullscreen) {
        throw unused("fullscreen(boolean)");
    }

    @Override
    public void vsync(boolean vsync) {
        throw unused("vsync(boolean)");
    }

    @Override
    public ContextResult adoptContext(ContextRequest request) {
        throw unused("adoptContext(ContextRequest)");
    }

    @Override
    public void makeCurrent() {
        throw unused("makeCurrent()");
    }

    @Override
    public void releaseContext() {
        throw unused("releaseContext()");
    }

    @Override
    public void swapBuffers() {
        throw unused("swapBuffers()");
    }

    @Override
    public void pump() {
        throw unused("pump()");
    }

    @Override
    public KeyEvent nextKeyEvent() {
        throw unused("nextKeyEvent()");
    }

    @Override
    public int queuedKeyEvents() {
        throw unused("queuedKeyEvents()");
    }

    @Override
    public boolean keyDown(int sdlScancode) {
        throw unused("keyDown(int)");
    }

    @Override
    public void textInput(boolean enabled) {
        throw unused("textInput(boolean)");
    }

    @Override
    public MouseEvent nextMouseEvent() {
        throw unused("nextMouseEvent()");
    }

    @Override
    public int queuedMouseEvents() {
        throw unused("queuedMouseEvents()");
    }

    @Override
    public boolean mouseButtonDown(int sdlButton) {
        throw unused("mouseButtonDown(int)");
    }

    @Override
    public float mouseX() {
        throw unused("mouseX()");
    }

    @Override
    public float mouseY() {
        throw unused("mouseY()");
    }

    @Override
    public float takeMouseDeltaX() {
        throw unused("takeMouseDeltaX()");
    }

    @Override
    public float takeMouseDeltaY() {
        throw unused("takeMouseDeltaY()");
    }

    @Override
    public float takeMouseWheel() {
        throw unused("takeMouseWheel()");
    }

    @Override
    public void mousePosition(float x, float y) {
        throw unused("mousePosition(float,float)");
    }

    @Override
    public void grabMouse(boolean grab) {
        throw unused("grabMouse(boolean)");
    }

    @Override
    public boolean mouseGrabbed() {
        throw unused("mouseGrabbed()");
    }

    private static UnsupportedOperationException unused(String member) {
        return new UnsupportedOperationException(
            "TestWindowBridge: " + member + " is not part of what the SDL window host consumes");
    }
}
