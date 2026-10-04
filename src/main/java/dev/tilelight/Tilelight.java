package dev.tilelight;

import dev.tilelight.nativeapi.NativeLib;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Tilelight implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("tilelight");

	private static NativeLib nativeLib;

	@Override
	public void onInitializeClient() {
		String os = System.getProperty("os.name", "");
		String arch = System.getProperty("os.arch", "");
		if (!os.startsWith("Mac") || !arch.equals("aarch64")) {
			LOG.warn("Tilelight needs an Apple Silicon Mac; staying off on {} {}", os, arch);
			return;
		}
		try {
			NativeLib lib = NativeLib.load();
			int rc = lib.init();
			if (rc != NativeLib.OK) {
				LOG.error("Metal init failed with code {}; staying off", rc);
				return;
			}
			long gpuNs = lib.selftest(1 << 20);
			long caps = lib.capabilities();
			LOG.info("Metal device {} ready (raytracing={}, apple9={}), selftest {} us on the GPU",
					lib.deviceName(),
					(caps & NativeLib.CAP_RAYTRACING) != 0,
					(caps & NativeLib.CAP_APPLE9) != 0,
					gpuNs / 1000);
			nativeLib = lib;
		} catch (Throwable t) {
			LOG.error("Could not load the Metal backend; staying off", t);
		}
	}

	/** The loaded native library, or null if Tilelight is off. */
	public static NativeLib nativeLib() {
		return nativeLib;
	}
}
