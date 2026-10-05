package dev.tilelight;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Settings from {@code config/tilelight.json}, written with defaults on first run. Any field can be
 * overridden with a {@code -Dtilelight.<name>=...} system property (used by the benchmark scripts).
 */
public final class TilelightConfig {
	/**
	 * off | noop | bilinear | metalfx | temporal. MetalFX spatial is the default: temporal is sharper
	 * and anti-aliased but costs about 5 ms more per frame on a base M5 (see docs/ARCHITECTURE.md).
	 */
	public String upscaler = "metalfx";
	/** Fraction of the window the world renders at before upscaling, in (0, 1]. */
	public float renderScale = 0.67f;
	/** Draw and light opaque terrain on Metal (Gate 3). */
	public boolean terrain = true;
	public boolean shadows = true;
	public boolean volumetric = true;
	public boolean water = true;
	/**
	 * Run our Metal work on MoltenVK's own command queue, in order with Minecraft's Vulkan work,
	 * instead of a separate queue. Avoids ~0.3 ms of GPU idle per Vulkan/Metal switch.
	 */
	public boolean sameQueue = true;
	/** Haze density per block. */
	public float fogDensity = 0.004f;

	private static TilelightConfig instance;

	public static synchronized TilelightConfig get() {
		if (instance == null) {
			instance = load(FabricLoader.getInstance().getConfigDir().resolve("tilelight.json"));
		}
		return instance;
	}

	static TilelightConfig load(Path file) {
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		TilelightConfig config = new TilelightConfig();
		if (Files.isRegularFile(file)) {
			try (Reader in = Files.newBufferedReader(file)) {
				TilelightConfig read = gson.fromJson(in, TilelightConfig.class);
				if (read != null) config = read;
			} catch (IOException | JsonParseException e) {
				Tilelight.LOG.warn("Could not read {}; using defaults", file, e);
			}
		}
		// Rewrite so new fields appear with their defaults.
		try (Writer out = Files.newBufferedWriter(file)) {
			gson.toJson(config, out);
		} catch (IOException e) {
			Tilelight.LOG.warn("Could not write {}", file, e);
		}
		config.applySystemProperties();
		config.validate();
		return config;
	}

	private void applySystemProperties() {
		this.upscaler = System.getProperty("tilelight.mode", this.upscaler);
		this.renderScale = Float.parseFloat(System.getProperty("tilelight.scale", Float.toString(this.renderScale)));
		this.terrain = Boolean.parseBoolean(System.getProperty("tilelight.terrain", Boolean.toString(this.terrain)));
		this.shadows = Boolean.parseBoolean(System.getProperty("tilelight.shadows", Boolean.toString(this.shadows)));
		this.volumetric = Boolean.parseBoolean(System.getProperty("tilelight.volumetric", Boolean.toString(this.volumetric)));
		this.water = Boolean.parseBoolean(System.getProperty("tilelight.water", Boolean.toString(this.water)));
		this.sameQueue = Boolean.parseBoolean(System.getProperty("tilelight.sameQueue", Boolean.toString(this.sameQueue)));
		this.fogDensity = Float.parseFloat(System.getProperty("tilelight.fogDensity", Float.toString(this.fogDensity)));
	}

	private void validate() {
		switch (this.upscaler) {
			case "off", "noop", "bilinear", "metalfx", "temporal" -> {
			}
			default -> {
				Tilelight.LOG.warn("Unknown upscaler '{}'; using metalfx", this.upscaler);
				this.upscaler = "metalfx";
			}
		}
		if (!(this.renderScale > 0f && this.renderScale <= 1f)) {
			Tilelight.LOG.warn("renderScale {} is outside (0, 1]; using 0.67", this.renderScale);
			this.renderScale = 0.67f;
		}
	}
}
