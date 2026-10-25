package net.coderbot.iris.config;

import com.gtnewhorizons.angelica.glsm.shader.ProgramBinaryCache;
import net.coderbot.iris.Iris;
import net.coderbot.iris.gui.option.IrisVideoSettings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * A class dedicated to storing the config values of shaderpacks. Right now it only stores the path to the current shaderpack
 */
public class IrisConfig {
	private static final String COMMENT =
		"This file stores configuration options for " + Iris.MODNAME + ", such as the currently active shaderpack";

	/**
	 * The path to the current shaderpack. Null if the internal shaderpack is being used.
	 */
	private String shaderPackName;

	/**
	 * Whether or not shaders are used for rendering. False to disable all shader-based rendering, true to enable it.
	 */
	private boolean enableShaders;

	/**
	 * Set by button/API toggles before a reload. initialize() must keep
	 * this value instead of re-deriving it from the on-disk properties.
	 */
	private boolean enableShadersExplicitlySet;

	/**
	 * If the update notification should be disabled or not.
	 */
	private boolean disableUpdateMessage;

	/**
	 * If debug features should be enabled. Gives much more detailed OpenGL error outputs at the cost of performance.
	 */
	private boolean enableDebugOptions;

	/**
	 * Whether linked programs are written to disk so later runs restore them instead of compiling them again.
	 * Off by default: the first load of a pack pays the extra compile either way, so the option only becomes
	 * a win once the pack is loaded a second time.
	 */
	private boolean saveCompiledShaders;

	private final Path propertiesPath;

	public IrisConfig(Path propertiesPath) {
		shaderPackName = null;
		enableShaders = true;
		disableUpdateMessage = false;
		enableDebugOptions = false;
		saveCompiledShaders = false;
		this.propertiesPath = propertiesPath;
	}

	/**
	 * Initializes the configuration, loading it if it is present and creating a default config otherwise.
	 *
	 * @throws IOException file exceptions
	 */
	public void initialize() throws IOException {
		load();
		// With no shader pack selected there is nothing to render, so keep the
		// enable switch off instead of inheriting the "enabled by default" state.
		if (shaderPackName == null && !enableShadersExplicitlySet) {
			enableShaders = false;
		}
		if (!Files.exists(propertiesPath)) {
			save();
		}
	}

	/**
	 * returns whether or not the current shaderpack is internal
	 */
	public boolean isInternal() {
		return false;
	}

	/**
	 * Returns the name of the current shaderpack
	 */
	public Optional<String> getShaderPackName() {
		return Optional.ofNullable(shaderPackName);
	}

	/**
	 * Sets the name of the current shaderpack
	 */
	public void setShaderPackName(String name) {
		if (name == null || name.equals("(internal)") || name.isEmpty()) {
			this.shaderPackName = null;
		} else {
			this.shaderPackName = name;
		}
	}

	/**
	 * Determines whether or not shaders are used for rendering.
	 *
	 * @return False to disable all shader-based rendering, true to enable shader-based rendering.
	 */
	public boolean areShadersEnabled() {
		return enableShaders;
	}

	public boolean shouldDisableUpdateMessage() {
		return disableUpdateMessage;
	}

	/**
	 * Sets whether shaders should be used for rendering.
	 */
	public void setShadersEnabled(boolean enabled) {
		this.enableShaders = enabled;
		this.enableShadersExplicitlySet = true;
	}

	public boolean areDebugOptionsEnabled() {
		return enableDebugOptions;
	}

	/** {@return whether linked programs are saved to disk and restored on the next load} */
	public boolean shouldSaveCompiledShaders() {
		return saveCompiledShaders;
	}

	/**
	 * Turns saving compiled programs on or off and persists the choice. The saved-program cache is told
	 * immediately so the next link already follows the new setting instead of waiting for a restart.
	 */
	public void setSaveCompiledShaders(boolean save) throws IOException {
		this.saveCompiledShaders = save;
		ProgramBinaryCache.setEnabled(save);
		save();
	}

	/**
	 * loads the config file and then populates the string, int, and boolean entries with the parsed entries
	 *
	 * @throws IOException if the file cannot be loaded
	 */

	public void load() throws IOException {
		if (!Files.exists(propertiesPath)) {
			return;
		}

		Properties properties = new Properties();
		// NB: This uses ISO-8859-1 with unicode escapes as the encoding
		try (InputStream is = Files.newInputStream(propertiesPath)) {
			properties.load(is);
		}
		shaderPackName = properties.getProperty("shaderPack");
		// A toggle issued before a reload (button/API) wins over the on-disk value;
		// only derive the flag from disk when no explicit value is pending.
		if (!enableShadersExplicitlySet) {
			enableShaders = !"false".equals(properties.getProperty("enableShaders"));
		}
		enableDebugOptions = "true".equals(properties.getProperty("enableDebugOptions"));
		disableUpdateMessage = "true".equals(properties.getProperty("disableUpdateMessage"));
		saveCompiledShaders = "true".equals(properties.getProperty("saveCompiledShaders"));
		// The cache gates this on its own: no driver support or no selected pack keeps it off until both
		// are true, so pushing the flag here cannot start a save before the renderer is initialized.
		ProgramBinaryCache.setEnabled(saveCompiledShaders);
        // TODO: GUI
        try {
            IrisVideoSettings.shadowDistance = Integer.parseInt(properties.getProperty("maxShadowRenderDistance", "32"));
        } catch (NumberFormatException e) {
            Iris.logger.error("Shadow distance setting reset; value is invalid.");
            IrisVideoSettings.shadowDistance = 32;
            save();
        }

		if (shaderPackName != null) {
			if (shaderPackName.equals("(internal)") || shaderPackName.isEmpty()) {
				shaderPackName = null;
			}
		}
	}

	/**
	 * Serializes the config into a file. Should be called whenever any config values are modified.
	 *
	 * @throws IOException file exceptions
	 */
	public void save() throws IOException {
		final Properties properties = new Properties();
		properties.setProperty("shaderPack", getShaderPackName().orElse(""));
		properties.setProperty("enableShaders", enableShaders ? "true" : "false");
		properties.setProperty("enableDebugOptions", enableDebugOptions ? "true" : "false");
		properties.setProperty("disableUpdateMessage", disableUpdateMessage ? "true" : "false");
		properties.setProperty("saveCompiledShaders", saveCompiledShaders ? "true" : "false");
		properties.setProperty("maxShadowRenderDistance", String.valueOf(IrisVideoSettings.shadowDistance));
		// NB: This uses ISO-8859-1 with unicode escapes as the encoding
		try (OutputStream os = Files.newOutputStream(propertiesPath)) {
			properties.store(os, COMMENT);
		}
	}
}
