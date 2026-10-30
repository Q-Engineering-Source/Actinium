package net.coderbot.iris.gl.blending;

import com.gtnewhorizons.angelica.glsm.states.ColorMask;
import com.gtnewhorizons.angelica.glsm.states.DepthState;
import com.gtnewhorizons.angelica.glsm.hooks.VanillaStateLayer;
import com.gtnewhorizons.angelica.glsm.GLStateManager;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import lombok.Getter;

public class DepthColorStorage {
	private static boolean originalDepthEnable;
	private static ColorMask originalColor;
	@Getter
    private static boolean depthColorLocked;
	@Getter
	private static boolean overrideHeld;

	private static final IntOpenHashSet ownedPrograms = new IntOpenHashSet();

	public static void registerOwnedProgram(int programId) {
		ownedPrograms.add(programId);
	}

	public static void unregisterOwnedProgram(int programId) {
		ownedPrograms.remove(programId);
	}

	public static boolean isOwnedProgram(int programId) {
		return ownedPrograms.contains(programId);
	}

    public static void disableDepthColor() {
		if (!overrideHeld) {
			// Only save the previous state if the depth and color mask wasn't already locked
			final var colorMask = GLStateManager.getColorMask();
			final DepthState depthState = GLStateManager.getDepthState();

			originalDepthEnable = depthState.isMaskEnabled();
			originalColor = new ColorMask();
			originalColor.setAll(colorMask.red, colorMask.green, colorMask.blue, colorMask.alpha);
		}

		overrideHeld = true;
		depthColorLocked = false;
		try {
			GLStateManager.glDepthMask(false);
			GLStateManager.glColorMask(false, false, false, false);
		} finally {
			depthColorLocked = true;
		}
	}

	public static void deferDepthEnable(boolean enabled) {
		originalDepthEnable = enabled;
	}

	public static void deferColorMask(boolean red, boolean green, boolean blue, boolean alpha) {
		originalColor.setAll(red, green, blue, alpha);
	}

	public static void unlockDepthColor() {
		if (!overrideHeld) {
			return;
		}

		depthColorLocked = false;

        GLStateManager.glDepthMask(originalDepthEnable);

        GLStateManager.glColorMask(originalColor.red, originalColor.green, originalColor.blue, originalColor.alpha);
		overrideHeld = false;
	}

	/** Exposes the deferred vanilla depth-write mask to GLSM. */
	public static final VanillaStateLayer<DepthState> DEPTH_LAYER = new VanillaStateLayer<>() {
		@Override
		public boolean isOverrideHeld() {
			return overrideHeld;
		}

		@Override
		public void readVanilla(DepthState into) {
			into.setMaskEnabled(originalDepthEnable);
		}

		@Override
		public void writeVanilla(DepthState from) {
			deferDepthEnable(from.isMaskEnabled());
		}
	};

	/** Exposes the deferred vanilla color mask to GLSM. */
	public static final VanillaStateLayer<ColorMask> COLOR_LAYER = new VanillaStateLayer<>() {
		@Override
		public boolean isOverrideHeld() {
			return overrideHeld;
		}

		@Override
		public void readVanilla(ColorMask into) {
			into.set(originalColor);
		}

		@Override
		public void writeVanilla(ColorMask from) {
			deferColorMask(from.red, from.green, from.blue, from.alpha);
		}
	};
}
