package dev.tilelight.nativeapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Runs against the real GPU; build.gradle points tilelight.nativeDir at the freshly built natives. */
class NativeLibTest {
	private static NativeLib lib;

	@BeforeAll
	static void load() throws Exception {
		lib = NativeLib.load();
		assertEquals(NativeLib.OK, lib.init());
		assertEquals(NativeLib.OK, lib.init(), "second init must be a no-op");
	}

	@AfterAll
	static void unload() {
		lib.shutdown();
	}

	@Test
	void reportsAnAppleDevice() {
		String name = lib.deviceName();
		assertFalse(name.isBlank());
		assertTrue(name.startsWith("Apple"), name);
		long caps = lib.capabilities();
		assertTrue((caps & NativeLib.CAP_UNIFIED_MEMORY) != 0);
		assertTrue((caps & NativeLib.CAP_METAL3) != 0);
	}

	@Test
	void computeSelftestMatchesCpu() {
		// Not a multiple of the threadgroup size, to cover the bounds check in the kernel.
		long gpuNs = lib.selftest((1 << 20) + 7);
		assertTrue(gpuNs > 0, "GPU time should be positive, got " + gpuNs);
	}

	@Test
	void selftestRejectsEmptyInput() {
		IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(
				IllegalStateException.class, () -> lib.selftest(0));
		assertTrue(e.getMessage().contains("-7"), e.getMessage());
	}
}
