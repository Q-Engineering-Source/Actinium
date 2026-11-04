package net.coderbot.iris.gl.blending;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.hooks.VanillaBooleanLayer;
import com.gtnewhorizons.angelica.glsm.hooks.VanillaStateLayer;
import com.gtnewhorizons.angelica.glsm.states.AlphaState;
import lombok.Getter;
import com.gtnewhorizons.angelica.glsm.GLSMDepthStencilAlpha;

public class AlphaTestStorage {
	private static boolean originalAlphaTestEnable;
    private static AlphaTest originalAlphaTest;
	@Getter
    private static boolean alphaTestLocked;
	@Getter
	private static boolean overrideHeld;

    public static void overrideAlphaTest(AlphaTest override) {
		if (!overrideHeld) {
            final AlphaState alphaState = GLStateManager.getAlphaState();

			// Only save the previous state if the alpha test wasn't already locked
			originalAlphaTestEnable = GLStateManager.getAlphaTest().isEnabled();
			originalAlphaTest = new AlphaTest(AlphaTestFunction.fromGlId(alphaState.getFunction()).orElse(AlphaTestFunction.ALWAYS), alphaState.getReference());
		}

		overrideHeld = true;
		alphaTestLocked = false;
		try {
			if (override == null) {
				GLStateManager.disableAlphaTest();
			} else {
				GLStateManager.enableAlphaTest();
				GLSMDepthStencilAlpha.glAlphaFunc(override.getFunction().getGlId(), override.getReference());
			}
		} finally {
			alphaTestLocked = true;
		}
	}

	public static void deferAlphaTestToggle(boolean enabled) {
		originalAlphaTestEnable = enabled;
	}

	public static void deferAlphaFunc(int function, float reference) {
		originalAlphaTest = new AlphaTest(AlphaTestFunction.fromGlId(function).get(), reference);
	}

	public static void restoreAlphaTest() {
		if (!overrideHeld) {
			return;
		}

		alphaTestLocked = false;

		if (originalAlphaTestEnable) {
            GLStateManager.enableAlphaTest();
		} else {
            GLStateManager.disableAlphaTest();
		}

        GLSMDepthStencilAlpha.glAlphaFunc(originalAlphaTest.getFunction().getGlId(), originalAlphaTest.getReference());
		overrideHeld = false;
	}

	/** Exposes the deferred vanilla alpha-test enable state to GLSM. */
	public static final VanillaBooleanLayer ENABLE_LAYER = new VanillaBooleanLayer() {
		@Override
		public boolean isOverrideHeld() {
			return overrideHeld;
		}

		@Override
		public boolean getVanilla() {
			return originalAlphaTestEnable;
		}

		@Override
		public void setVanilla(boolean enabled) {
			deferAlphaTestToggle(enabled);
		}
	};

	/** Exposes the deferred vanilla alpha-test function to GLSM. */
	public static final VanillaStateLayer<AlphaState> FUNC_LAYER = new VanillaStateLayer<>() {
		@Override
		public boolean isOverrideHeld() {
			return overrideHeld;
		}

		@Override
		public void readVanilla(AlphaState into) {
			into.setFunction(originalAlphaTest.getFunction().getGlId());
			into.setReference(originalAlphaTest.getReference());
		}

		@Override
		public void writeVanilla(AlphaState from) {
			deferAlphaFunc(from.getFunction(), from.getReference());
		}
	};
}
