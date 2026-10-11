package com.dhj.vintagesdlbridge;

/**
 * Test stand-in for the Vintage-SDL-Bridge coremod plugin class.
 *
 * <p>Only its presence on the classpath is significant: {@code SDLGPUWindowHandover.isBridgePresent}
 * probes for this name, so the test tree needs a class under exactly this binary name to exercise
 * the "bridge installed" branch.</p>
 */
public final class VintageSdlBridgeLoadingPlugin {
    private VintageSdlBridgeLoadingPlugin() {}
}
