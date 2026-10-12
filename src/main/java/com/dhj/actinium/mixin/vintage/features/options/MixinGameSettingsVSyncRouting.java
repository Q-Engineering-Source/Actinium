package com.dhj.actinium.mixin.vintage.features.options;

import com.gtnewhorizons.angelica.proxy.ClientProxy;
import net.minecraft.client.settings.GameSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Routes the VSync option through the render backend.
 *
 * <p>The pacer decides whether the driver will block on the swap from the backend's effective vsync
 * mode, and only the backend knows what it actually asked the driver for. Vanilla's own switch
 * would change the swap interval behind the backend's back, leaving the pacer believing a tearing
 * interval is a tear-free one; it would then pace every frame onto the panel's cadence with vsync
 * off. The backend's entry point performs exactly the call vanilla would have made, and records
 * which tear-free mode the toggle resolved to so the three-state option shows the same thing.</p>
 */
@Mixin(GameSettings.class)
public class MixinGameSettingsVSyncRouting {

    @Redirect(
            method = "setOptionValue",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;setVSyncEnabled(Z)V", remap = false)
    )
    private void actinium$routeVSync(boolean sync) {
        ClientProxy.toggleVSync(sync);
    }
}
