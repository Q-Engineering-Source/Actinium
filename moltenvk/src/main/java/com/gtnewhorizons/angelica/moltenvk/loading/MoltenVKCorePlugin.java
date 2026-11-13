package com.gtnewhorizons.angelica.moltenvk.loading;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import java.util.Map;

/**
 * Marks the MoltenVK distribution as a core mod so Forge exposes the bundled native library before
 * the render backend is selected. The jar carries no game patches; its only payload is the dylib
 * under {@code macos/x64} and {@code macos/arm64}.
 */
@IFMLLoadingPlugin.Name("Actinium MoltenVK")
@IFMLLoadingPlugin.MCVersion("1.12.2")
public class MoltenVKCorePlugin implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return null;
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
